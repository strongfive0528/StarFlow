package com.moe.starflow.novel.translate

import com.moe.starflow.manga.OcrLock
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 队列阶段。 */
enum class NovelQueuePhase {
    /** 未启用 / 面板打开中 / 手动模式闲着。 */
    IDLE,
    /** 抢翻译锁中（漫画/悬浮窗翻译在跑时会出现）。 */
    WAITING_LOCK,
    /** 正在翻某一批。 */
    TRANSLATING,
    /** 当前页翻完、配额用尽或章末 —— 停下等用户翻页/切模式。 */
    DRAINED,
}

data class NovelQueueState(
    val phase: NovelQueuePhase = NovelQueuePhase.IDLE,
    val chapterIndex: Int = -1,
    /** 正在翻的这一批（段号）；空 = 没在翻。 */
    val batchParaIndexes: List<Int> = emptyList(),
    /** 本次启动已经翻完多少批。 */
    val batchesDone: Int = 0,
    /** 增量还剩多少批配额；手动/自动为 null。 */
    val remaining: Int? = null,
)

/**
 * 按**批**推进的翻译队列。
 *
 * ### 三种模式（用户口径，别再改错）
 * - **手动**：不进队列。按钮点一次 = 从**当前页**第一段没翻的段起翻**一批**（见 [translateOneBatch]）
 * - **自动**：盯着**当前页**，只要本页还有没翻的段就一批批翻下去；本页翻完 → `DRAINED`，
 *   等用户翻到下一页再自动接上
 * - **增量**：在自动的基础上**不受当前页限制**，从**当前页第一段**起向后再翻
 *   「向后批数 × 每批段数」段，到窗口边界或章末停下
 *
 * ⚠️ 增量是「从当前页第一段向后 X **段**」的**滑动窗口**，不是一次性额度 ——
 * 做成"额度用完就永久停"的话用户翻页也毫无反应（用户明确要求"配额用完要根据翻页刷新"）。
 * 窗口跟着当前页走，翻页即前移、自动接着翻。
 *
 * ⚠️ 增量是「向后 N **批**」，不是「向后 N 章」—— 这条曾经做错过一次，
 * 语义由 `NovelBatchPlannerTest` 与 `NovelTranslationQueueTest` 两处钉死。
 *
 * ⚠️ 三条纪律别改回去（都是踩过的坑）：
 * 1. **`OcrLock` 必须轮询等**，拿不到就 return 会让本轮什么都不做、下一轮又选到同一批 → 空转
 * 2. **失败的批要记进 [failedAnchors] 跳过**，否则每轮重挑同一批 → 无限重试、额度烧光
 * 3. **面板打开时状态置空**（`NovelQueueState()`），否则用户看到一条永远不动的假进度
 *
 * ### 并发（2026-10 加，用户口径；2026-10-03 收紧）
 * 「最多 N 个批同时在飞、错峰启动（默认 500ms 一个），第 1 个结束后立刻补第 N+1 个」——
 * N = [concurrency]（远端 API 由设置里的「同时 API 请求数」决定，**本地引擎恒 1**）。
 *
 * ⚠️ **自动模式恒为 1（串行）**：用户口径 2026-10-03「自动翻译只会一批一批向后翻译，
 * 前一批没结束不会去开启下一批；**只有增量与整章**才受最大请求数限制」。
 * 所以 [slots] 在 AUTO 下被夹成 1，N 只作用于 AHEAD（整章走 `ChapterJobRunner`，是另一条路）。
 * ⚠️ 但**持不持 `OcrLock` 只看引擎**（[serial] = `concurrency() == 1`）：锁的语义是"本地引擎串行"，
 * 与"自动模式串行"是两件事，别用 `slots == 1` 去推。
 *
 * ⚠️ 只是把「串行等结果」换成「最多 N 个在飞」，**推进语义一个字都没改**：
 * 锚点/成批仍走 [NovelBatchPlanner]、`done` 仍是 `已译 ∪ failedAnchors ∪ 在飞批段号`、
 * 失败仍记账跳过、增量窗口仍按当前页现算。
 *
 * ### ⚠️ 防抖 = 「翻屏」+「上一批收尾」两个触发点（2026-10-04 用户口径修）
 * 用户报的现场：「**黄色翻译中的背景在上一批翻译完后就会立刻跳到当前正在翻的页面**」——
 * 旧写法把防抖只挂在"翻屏"上，而翻屏时上一批还在飞：那次 `delay(debounce)` 在"等在飞批"
 * 的过程中被白白消耗掉，等上一批真收尾时防抖早已过期 → 立刻认领下一批，黄底无缝跳过去。
 * 现在两个触发点**都会排一次「等待页面稳定」**（两个 `AtomicBoolean`：`spanChanged` / `settled`
 * - 待翻的那一屏（`currentPageSpanIndexes`）变了 → 置位（连翻时一轮轮重计）
 * - **有批收尾（在飞清零）** → 置位
 * 调度循环见到这一位就先 `delay(debounceMs())` 再认领，所以「上一批结束 → 停一拍 → 才轮到下一批」，
 * 黄底之间一定看得见间隔；`N > 1`（增量）时后续批只受错峰控制，不被它拖成串行。
 *
 * ### ⚠️ 锁纪律（并发化之后**必须**遵守，踩过就是崩进程）
 * queue 的 scope **未必是单线程派发器**（宿主给的是应用级 `Dispatchers.IO`，而
 * `translateBatch` 内部还会 `withContext(IO)`）→ 工人协程与调度协程真的会同时跑。所以：
 * - `failedAnchors` / 在飞账本（`inflight` / `inflightParas`）一律用**并发集合**；
 * - 在飞账本的**读取与迭代**（拼状态里的批次并集、判空、计数）**一律在 [lock] 里拿快照**，
 *   绝不裸迭代 —— 边迭代边被工人 `removeAll` 就是 `ConcurrentModificationException`，
 *   而且抛在 `launch` 里没人接 → 直接崩进程；
 * - `batchesDone` 这种被工人改、被调度读的计数同样只在锁里读写。
 */
class NovelTranslationQueue(
    private val scope: CoroutineScope,
    private val translator: NovelBatchTranslator,
    /** 取某章的段落（由仓库提供，带缓存）。 */
    private val paragraphsOf: suspend (ImportedNovel, Int) -> List<NovelParagraph>,
    private val sourceLang: () -> String,
    private val targetLang: () -> String,
    /**
     * 该章**已被「翻译本章」任务占住**的段（宿主提供；默认空）。
     * 它们要计进 `done`，否则自动/增量队列会把同一段再发一次请求。
     */
    private val excludedParas: (chapterIndex: Int) -> Set<Int> = { emptySet() },
    private val translatorName: () -> String,
    private val batchSize: () -> Int,
    private val debounceMs: () -> Int,
    /** 当前页显示的段（按顺序）—— 手动/自动的锚点。 */
    private val currentPageParaIndexes: () -> List<Int>,
    /**
     * 当前视野里**全部**段（含不可翻译的图片/短行）—— **只用来给增量窗口定位起点**。
     * ⚠️ 别拿 [currentPageParaIndexes] 顶替（宿主传进来的那份已滤掉不可翻译段）：本页一个可翻译段
     * 都没有时窗口会退化成「章首」，窗口左边界与 `remaining` 全算错
     * （用户报的「始终 0 批」有一半来自这里）。调用方没接就回落到它（等价旧行为）。
     */
    private val currentPageSpanIndexes: () -> List<Int> = currentPageParaIndexes,
    /** 整章的段（按顺序）—— 增量在页内翻完后从这里向后找。 */
    private val chapterParaIndexes: suspend (ImportedNovel, Int) -> List<Int>,
    /** 某章已有译文的段。 */
    private val translatedIndexes: suspend (ImportedNovel, Int) -> Set<Int>,
    /**
     * 同时最多几个批在飞。默认 1 = **旧行为**（批跑完才挑下一批）—— 既有单测与不关心并发的
     * 调用方不受影响。宿主传 `TranslationConcurrency.novelConcurrency(context, prefs)`
     * （本地引擎它会返回 1 → 天然串行）。
     */
    private val concurrency: () -> Int = { 1 },
    /**
     * 错峰间隔：并发 > 1 时每启动一个批至少隔这么久，避免同一瞬间打出一串请求。
     * 并发 == 1 时**完全不用它**（节奏仍由 [debounceMs] 控制，与旧版逐字一致）。
     */
    private val staggerMs: () -> Long = { DEFAULT_STAGGER_MS },
) {

    private companion object {
        const val TAG = "NovelTranslationQueue"

        /** `OcrLock` 没有 await，只能轮询。 */
        const val LOCK_POLL_MS = 200L
        const val LOCK_WAIT_TIMEOUT_MS = 30_000L

        /**
         * 持锁期间的心跳间隔。必须**远小于** `OcrLock.STALE_TIMEOUT_MS`(30s) ——
         * 本地引擎整批几分钟，不打心跳会被自愈机制误判成"持有者已死"（见 [withLockHeartbeat]）。
         */
        const val LOCK_HEARTBEAT_MS = 5_000L

        /** 停下之后的空转间隔（等用户翻页/切模式）。 */
        const val DRAINED_POLL_MS = 800L

        /** 在飞批已经占满并发数时的轮询间隔（等一个批结束，**立刻**补下一个）。 */
        const val SLOT_POLL_MS = 60L

        /** 错峰启动间隔（并发 > 1 时每启动一个批至少隔这么久）。 */
        const val DEFAULT_STAGGER_MS = 500L
    }

    private val _state = MutableStateFlow(NovelQueueState())
    val state: StateFlow<NovelQueueState> = _state.asStateFlow()

    private var job: Job? = null
    private var panelOpen = false

    /**
     * 保护**并发工人**会一起动的几个字段（[failedAnchors] / 在飞账本 / 批计数）。
     *
     * 并发 == 1 时这些对象只有一个协程在碰（与旧版一样），加锁只是零成本的保险；
     * 并发 > 1 时远端 API 的多个工人可能落在不同线程上，不加锁就是"读-改-写"丢更新。
     */
    private val lock = Any()

    /**
     * **翻过但没拿到译文**的批（记的是锚点段号）。[start] 会清空 —— 换模式/重启之后值得再试一次。
     *
     * 不记的话每轮都会重挑同一批：无限重试、额度烧光，而且「有批在翻」不成立，
     * 用户连进度提示都看不到（只觉得队列卡死）。
     *
     * ⚠️ 用**并发集合**：并发 > 1 时它是被 N 个工人协程读写的（读在调度循环、写在工人收尾）。
     */
    private val failedAnchors = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    /**
     * ⚠️ **没有"用户清除了译文 → 自动别再翻回来"这份账了**（2026-10-04 用户口径撤销了它）：
     * 「清译文后重翻只有手动生效，自动和增量即使当前页面刚才删除了译文，开启也没有任何翻译，
     * 不会翻译。你要考虑到自动/增量/本章翻译模式下编辑删除的情况」。
     * 现在的口径是 —— **清译文 = 用户的一次显式编辑**：由宿主当场**停掉前台队列并回退手动**
     * （见 `NovelReaderActivity.clearSelectedTranslations`），之后用户**再开启**自动/增量时，
     * 那几段已经是"没有译文"的普通段，照常被翻（不会再被静默跳过）。
     * 所以这里不需要任何"跳过账"—— 需要的是**别在用户刚清完的瞬间自己又翻一遍**，
     * 而那由"清完就停队列 + 回退手动"保证。
     */

    /**
     * **在飞批的段并集**（只读快照，给宿主做"别重复翻同一段"的反向去重）。
     *
     * ⚠️ 必须与 job 内部的 `inflightParas` 在**同一个进锁块**里更新，不要另算一套 ——
     * 章任务排批时读它（[NovelChapterJobHost.queueInFlightParas]），读到的必须是同一份账。
     */
    @Volatile
    private var inflightParasView: Set<Int> = emptySet()

    /** 在飞批的段（空 = 当前没有在飞的批）。 */
    fun inflightParaIndexes(): Set<Int> = inflightParasView

    /** 自动/增量每轮并进 `done` 的"别再挑"账（只有失败的锚点，避免无限重试同一批）。 */
    private fun skipSetFor(translated: Set<Int>): Set<Int> = translated + failedAnchors

    /**
     * 启动（或按新参数重启）队列。[NovelTranslateMode.MANUAL] 时只把状态清空 ——
     * 手动不进循环，由按钮直接调 [translateOneBatch]。
     *
     * @param quota 增量模式的批配额；手动/自动传什么都行
     * @param currentChapter 每轮**重新求值**（lambda 而非值）：切章不重启队列
     * @param onBatchSettled **一批结束时**回调（成功给译文表，失败给空表）。
     *   ⚠️ 失败**也必须调**：宿主靠它刷新统计与失败明细 —— 只报成功的话，
     *   面板里的「失败」状态永远不出现（用户报的「失败的状态不会实时同步」）。
     */
    fun start(
        book: ImportedNovel,
        mode: NovelTranslateMode,
        quota: NovelQuota = NovelQuota.of(NovelQuota.DEFAULT),
        currentChapter: () -> Int,
        onBatchSettled: suspend (Int, NovelBatchResult) -> Unit,
    ) {
        job?.cancel()
        failedAnchors.clear()
        if (mode == NovelTranslateMode.MANUAL) {
            _state.value = NovelQueueState()
            return
        }
        job = scope.launch {
            var batchesDone = 0
            // 在飞批的账本（锚点 → 占位）：**纯内存**，随 job 消失。并发调度的唯一依据就是它。
            //
            // ⚠️ **这两份账本的一切读写都必须进 [lock]，且用并发集合**：写它的是 N 个工人协程
            // （收尾时 remove），读/迭代它的是调度协程（拼状态里的批次并集）—— queue 的 scope
            // 未必是单线程派发器（宿主给的是应用级 IO，`translateBatch` 内部还会 withContext(IO)），
            // 一边迭代一边被改就是 `ConcurrentModificationException`，抛在 `launch` 里没人接 → **崩进程**。
            val inflight = java.util.concurrent.ConcurrentHashMap<Int, Unit>()
            val inflightParas = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
            // 并发：**AUTO 恒 1**（用户口径 2026-10-03：「自动翻译只会一批一批向后翻译，
            // 前一批没结束不会去开启下一批」），N 只作用于增量（整章走 ChapterJobRunner 那条路）。
            // ⚠️ `serial`（持 OcrLock）只看**引擎**并发，与 AUTO 被夹成 1 无关：那把锁是"本地引擎串行"，
            // 远端 API 下 AUTO 也不该去抢 OCR 锁。
            val engineSlots = concurrency().coerceAtLeast(1)
            val serial = engineSlots == 1
            val slots = if (mode == NovelTranslateMode.AUTO) 1 else engineSlots
            /** 已经为哪一屏排过防抖（`null` = 还没排过）。只在"这一屏第一次要翻"时排一次。 */
            var debouncedSpanKey: String? = null
            /**
             * **该等一次防抖**（认领下一批之前先停一拍）。两个来源分开记（日志里要看清是哪一个）：
             * - [spanChanged]、翻屏（`spanKey` 变了）
             * - [settled]、**有批收尾**（`inflight` 清零，见工人 finally）
             * ⚠️ 用户报的「上一批翻完黄底立刻跳到当前页」就是原来只有①、而①又被"等在飞批"白白
             * 消耗掉了。两个都用 `AtomicBoolean`：置位的是**工人协程**、读取/清位的是**调度协程**，
             * 普通 `var` 的"调度刚清位、工人同时置位"会**丢掉这一拍**（正好就是这条 bug 的形态）。
             */
            val spanChanged = java.util.concurrent.atomic.AtomicBoolean(false)
            val settled = java.util.concurrent.atomic.AtomicBoolean(false)
            while (isActive) {
                if (panelOpen) {
                    // 面板打开：状态置空 + 不动。用户在看面板时翻下去既浪费额度也可能翻错
                    _state.value = NovelQueueState()
                    delay(LOCK_POLL_MS)
                    continue
                }
                val inflightNow = synchronized(lock) { inflight.size }
                if (inflightNow >= slots) {
                    // 并发占满：等一个批结束（下一轮**立刻**补位，不再等错峰/防抖）
                    delay(SLOT_POLL_MS)
                    continue
                }
                // 当前视野里**全部**段（含不可翻译的图片/短行）—— 两个用途：① 增量窗口定位起点；
                // ② **防抖的「屏」标识**。⚠️ 不能拿 `page`（已滤掉不可翻译段）顶替：本页一个可翻译段
                // 都没有时窗口会退化成「章首」，窗口左边界与 `remaining` 全算错。
                val spanPage = currentPageSpanIndexes()
                val spanKey = if (spanPage.isEmpty()) {
                    ""
                } else {
                    "${spanPage.first()}:${spanPage.last()}:${spanPage.size}"
                }
                if (spanKey.isNotEmpty() && spanKey != debouncedSpanKey) {
                    // 翻页/滚动到新的一屏 → 防抖重新计时（连翻时一轮一轮往后推，只有停下来的那屏才开翻）
                    debouncedSpanKey = spanKey
                    spanChanged.set(true)
                    // ⚠️ **翻屏 = 用户还在找要读的那一页：黄底只留"这一屏上真的在飞"的段**
                    // （用户口径 2026-10-04：「我一直翻页还是会有黄底！应该是页面稳定停留
                    // XXXms 才开始翻译出现黄底，增量也是一样的」）。
                    //
                    // 现象解释：请求已经发出去了（黄得有理），但发的是**上一屏**的段；用户往前翻时
                    // 会翻**进**这些段所在的页（增量更是"往后预翻"，本来就在前面几页），于是"一边翻
                    // 一边满屏黄"。调度这边其实一批都没多发（日志里 `防抖停一拍` 一直在重排）。
                    // 所以这里把在飞批里**不在当前屏**的那些从高亮里摘掉：翻走了就看不到黄，
                    // 停下来等过防抖、真的为这一屏发出请求时黄底才会（重新）出现。
                    if (inflightNow > 0) {
                        val visible = spanPage.toSet()
                        val shown = synchronized(lock) { inflightParas.filter { it in visible } }
                        val cur = _state.value
                        if ((cur.phase == NovelQueuePhase.TRANSLATING ||
                                cur.phase == NovelQueuePhase.WAITING_LOCK) &&
                            cur.batchParaIndexes.toSet() != shown.toSet()
                        ) {
                            _state.value = cur.copy(batchParaIndexes = shown)
                        }
                    }
                }
                // ⚠️ **两个标志分开取**，日志里才分得清"是翻屏还是上批收尾"排的这一拍
                val bySpan = spanChanged.getAndSet(false)
                val bySettle = settled.getAndSet(false)
                if (bySpan || bySettle) {
                    val ms = debounceMs().toLong()
                    LogCollector.d(
                        TAG,
                        "页面稳定等待 ${ms}ms（${if (bySpan) "翻屏，页面刚变" else "上批收尾"}）",
                    )
                    delay(ms)
                    continue
                }

                val chapter = currentChapter()
                val chapterParas = chapterParaIndexes(book, chapter)
                // ⚠️ 在飞的批也算"已完成"：不然同一个锚点会被反复挑中
                //（并发下就是同一时间发出 N 个一模一样的请求，白烧额度）
                val done = skipSetFor(translatedIndexes(book, chapter)) +
                    runCatching { excludedParas(chapter) }.getOrDefault(emptySet()) +
                    synchronized(lock) { inflightParas.toList() }
                val page = currentPageParaIndexes()
                val size = batchSize()

                // ── 增量窗口（用户口径，别再改回"一次性额度"）──
                // 窗口 = **当前页第一段**往后 X 段（X = 向后批数 × 每批段数）。
                // ⚠️ 做成"窗口"而不是"一次性配额"：额度用完就永久停住的话，用户翻页也没反应；
                // 窗口跟着当前页走，**翻页窗口自己就前移、自动接着翻**。
                val windowStart = chapterParas.indexOf(
                    spanPage.firstOrNull() ?: page.firstOrNull() ?: chapterParas.firstOrNull()
                ).takeIf { it >= 0 } ?: 0
                val limitIdx = (windowStart + quota.remaining * size).coerceAtMost(chapterParas.size)
                val aheadLimitPara = chapterParas.getOrNull(limitIdx) ?: Int.MAX_VALUE
                // 窗口内还剩多少批（面板/芯片上的"剩 N 批"）
                val leftInWindow = chapterParas.subList(windowStart, limitIdx)
                    .count { it !in done }
                val remaining = if (mode == NovelTranslateMode.AHEAD) {
                    (leftInWindow + size - 1) / size
                } else {
                    null
                }

                val anchor = NovelBatchPlanner.anchorForMode(
                    mode = mode,
                    pageParaIndexes = page,
                    chapterParaIndexes = chapterParas,
                    translated = done,
                    aheadLimitPara = aheadLimitPara,
                    aheadStartPara = chapterParas.getOrNull(windowStart),
                )
                val batch = if (anchor == null) {
                    emptyList()
                } else {
                    NovelBatchPlanner.nextBatch(chapterParas, anchor, done, size)
                }
                if (anchor == null || batch.isEmpty()) {
                    if (synchronized(lock) { inflight.isNotEmpty() }) {
                        // 还有在途批：先等它们收尾再判 DRAINED（否则状态里的批次信息会被清掉）
                        delay(SLOT_POLL_MS)
                        continue
                    }
                    // 自动：当前页翻完（等翻页）；增量：窗口翻完或本章翻完（不越到下一章）
                    _state.value = drainState(chapter, synchronized(lock) { batchesDone }, mode, remaining)
                    delay(DRAINED_POLL_MS)
                    continue
                }

                // 认领这一批（写账本 + 取状态里要用的批次并集，**同一次进锁**，视图一致）
                val claimedParas = synchronized(lock) {
                    inflight[anchor] = Unit
                    inflightParas += batch
                    inflightParasView = inflightParas.toSet()
                    inflightParas.toList().sorted()
                }
                _state.value = NovelQueueState(
                    NovelQueuePhase.TRANSLATING, chapter, claimedParas,
                    synchronized(lock) { batchesDone }, remaining,
                )

                // 并发 == 1（本地引擎）时**整批持 OcrLock** —— 与旧实现逐字一致，
                // 也保证本地推理/别的翻译任务不会同时进来；并发 > 1（远端 API）不持锁：
                // OcrLock 是 **OCR 引擎**锁，小说的翻译请求不碰 OCR（漫画 translatePhase 同样不持锁）
                // ⚠️ 用 [serial]（只看引擎）而不是 `slots == 1`：AUTO 被夹成 1 是**调度**上的串行，
                // 远端 API 下 AUTO 不该去抢 OCR 锁。
                launch {
                    try {
                        runInFlight(book, chapter, batch, serial) { result ->
                            synchronized(lock) {
                                if (result.isEmpty) {
                                    // 内容性失败（空响应 / 解析不出编号 / 锁超时）：记账跳过，别死循环重试
                                    LogCollector.w(TAG, "第 $chapter 章一批（${batch.size} 段）失败：${result.error}")
                                    failedAnchors += anchor
                                } else {
                                    batchesDone += 1
                                }
                            }
                            // ⚠️ 失败**也必须**通知宿主：面板要立刻显示这一批失败（带原始原因）
                            onBatchSettled(chapter, result)
                        }
                    } finally {
                        val idle = synchronized(lock) {
                            inflight.remove(anchor)
                            inflightParas.removeAll(batch)
                            inflightParasView = inflightParas.toSet()
                            // ⚠️ **一批一落定就把它从高亮里摘掉**（"出结果立刻擦"，别让它黄到下一批起飞）：
                            // 状态里的段号换成"还剩下的在飞段"，全空就整块黄底消失。
                            val remaining = inflightParas.toList().sorted()
                            val cur = _state.value
                            if ((cur.phase == NovelQueuePhase.TRANSLATING ||
                                    cur.phase == NovelQueuePhase.WAITING_LOCK) &&
                                cur.batchParaIndexes != remaining
                            ) {
                                _state.value = cur.copy(batchParaIndexes = remaining)
                            }
                            inflight.isEmpty()
                        }
                        // ⚠️ **有批收尾（在飞清零）→ 排一次防抖**（用户口径 2026-10-04：
                        // 「黄色翻译中的背景在上一批翻译完后就会立刻跳到当前正在翻的页面」）。
                        // 认领下一批之前先停一拍，黄底之间才看得见间隔；`N > 1`（增量）时
                        // 只要还有别的批在飞就不会被它拖住。
                        if (idle) settled.set(true)
                    }
                }

                // 错峰：只在「并发 > 1 且还没占满」时等 —— 占满了本来就要等槽位，
                // 并发 == 1 时完全不等（节奏由防抖控制，与旧版行为一致）
                if (slots > 1 && synchronized(lock) { inflight.size } < slots) delay(staggerMs())
            }
        }
    }

    /**
     * 一个在飞批的实际请求：抢锁（仅 [serial]）→ 翻 → 落库（在 [NovelBatchTranslator] 里）。
     *
     * ⚠️ 与 [runBatch] 的区别只有两处：**不写 `_state`**（并发下多个工人会互相覆盖状态，
     * 调度器才是状态的唯一写入者），以及只在 [serial] 时持 `OcrLock`（见调用点的说明）。
     */
    private suspend fun runInFlight(
        book: ImportedNovel,
        chapterIndex: Int,
        batch: List<Int>,
        serial: Boolean,
        settled: suspend (NovelBatchResult) -> Unit,
    ) {
        var lockToken = 0L
        try {
            if (serial) {
                _state.value = NovelQueueState(NovelQueuePhase.WAITING_LOCK, chapterIndex, batch)
                lockToken = acquireLockWithWait()
                if (lockToken == 0L) {
                    settled(NovelBatchResult(emptyMap(), "翻译引擎被占用（别的翻译正在跑）"))
                    return
                }
                _state.value = NovelQueueState(NovelQueuePhase.TRANSLATING, chapterIndex, batch)
            }
            settled(
                withLockHeartbeat(lockToken) {
                    translator.translateBatch(
                        book = book,
                        chapterIndex = chapterIndex,
                        paragraphs = paragraphsOf(book, chapterIndex),
                        paraIndexes = batch,
                        sourceLang = sourceLang(),
                        targetLang = targetLang(),
                        translatorName = translatorName(),
                    )
                },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogCollector.e(TAG, "第 $chapterIndex 章翻译失败", e)
            settled(
                NovelBatchResult(emptyMap(), "${e.javaClass.simpleName}: ${e.message.orEmpty()}"),
            )
        } finally {
            if (lockToken != 0L) OcrLock.release(lockToken)
        }
    }

    private fun drainState(
        chapter: Int,
        batchesDone: Int,
        mode: NovelTranslateMode,
        /** 增量：窗口内还剩几批；自动/手动 null。 */
        remaining: Int?,
    ) = NovelQueueState(
        phase = NovelQueuePhase.DRAINED,
        chapterIndex = chapter,
        batchesDone = batchesDone,
        remaining = if (mode == NovelTranslateMode.AHEAD) remaining else null,
    )

    /**
     * **手动**：从当前页第一段没翻的段起，翻一批就返回。
     *
     * 与队列共用同一套锚点/成批规则（[NovelBatchPlanner]）—— 两条路各写一套迟早会不一致。
     */
    suspend fun translateOneBatch(book: ImportedNovel, chapterIndex: Int): NovelBatchResult {
        val chapterParas = chapterParaIndexes(book, chapterIndex)
        val done = translatedIndexes(book, chapterIndex)
        val anchor = NovelBatchPlanner.anchorOnPage(currentPageParaIndexes(), done)
            ?: chapterParas.firstOrNull { it !in done && it !in failedAnchors }
            ?: return NovelBatchResult(emptyMap(), "没有待翻译的段落了")
        val batch = NovelBatchPlanner.nextBatch(chapterParas, anchor, done, batchSize())
        if (batch.isEmpty()) return NovelBatchResult(emptyMap(), "没有待翻译的段落了")
        return runBatch(book, chapterIndex, batch)
    }

    /**
     * 翻**指定的这几段**（选择模式：长按多选后翻译 / 重翻）。
     *
     * ⚠️ 与 [translateOneBatch] 的关键区别：**不经过规划器**。选中的段原样送出 ——
     * 规划器会把「已有译文」的段当成已完成而跳过，那样**重翻就永远翻不动**。
     *
     * ⚠️ 一次调用 = 一次请求。按「每批段数」拆批由调用方做（只有它知道面板怎么设置的），
     * 这样「选 20 段、每批 3 段」就是 7 次请求、7 次上屏。
     */
    suspend fun translateExact(
        book: ImportedNovel,
        chapterIndex: Int,
        paraIndexes: List<Int>,
    ): NovelBatchResult {
        if (paraIndexes.isEmpty()) return NovelBatchResult(emptyMap())
        return runBatch(book, chapterIndex, paraIndexes)
    }

    /**
     * **翻译整章**（面板上的「翻译本章」）：从本章第一段没翻的段起，一批批翻到章末。
     *
     * 与三种模式都不同：它不盯页、也不受增量窗口限制，就是"把这一章翻完"。
     * ⚠️ 这颗按钮长期写着「翻译本章」却只翻**一批**（文案与行为对不上，用户直接问了
     * "不是有翻译本章的功能吗"）—— 现在它是真的翻整章。
     *
     * ⚠️ 开始前把 [failedAnchors] 清掉：那是"别再自动挑"的记账，而这里是用户**明确要求重来一次**。
     * 不清的话，之前自动模式里失败过的那几段会被静默跳过，任务却报「已翻完 N 批」
     * —— 用户以为翻完了，其实那几段永远是原文。
     * （"用户清过译文"的账**没有**了，见类注释：清译文 = 一次显式编辑，再开启翻译就该照翻。）
     *
     * @param onBatch 每批落定就回调一次（宿主逐批上屏 + 刷新统计）
     * @return 成功翻完的**批数**；一批都没翻成才 0
     */
    suspend fun translateWholeChapter(
        book: ImportedNovel,
        chapterIndex: Int,
        onBatch: suspend (NovelBatchResult) -> Unit,
    ): Int {
        failedAnchors.clear()
        val chapterParas = chapterParaIndexes(book, chapterIndex)
        var done = 0
        while (currentCoroutineContext().isActive) {
            val translated = skipSetFor(translatedIndexes(book, chapterIndex))
            val anchor = chapterParas.firstOrNull { it !in translated } ?: return done
            val batch = NovelBatchPlanner.nextBatch(chapterParas, anchor, translated, batchSize())
            if (batch.isEmpty()) return done
            val result = runBatch(book, chapterIndex, batch)
            onBatch(result)
            if (result.isEmpty) {
                // 失败就停（不是跳过接着翻）：整章任务要的可预测 —— 停下来把原始原因报出去，
                // 让用户决定重试还是改设置；自动/增量那两条路才做"跳过继续"
                return done
            }
            done += 1
        }
        return done
    }

    /** 一批（已确定段号）的实际请求：抢锁 → 翻 → 落库（在 [NovelBatchTranslator] 里）。 */
    private suspend fun runBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        batch: List<Int>,
    ): NovelBatchResult {
        _state.value = NovelQueueState(NovelQueuePhase.WAITING_LOCK, chapterIndex, batch)
        val lockToken = acquireLockWithWait()
        if (lockToken == 0L) return NovelBatchResult(emptyMap(), "翻译引擎被占用（别的翻译正在跑）")
        try {
            _state.value = NovelQueueState(NovelQueuePhase.TRANSLATING, chapterIndex, batch)
            return withLockHeartbeat(lockToken) {
                translator.translateBatch(
                    book = book,
                    chapterIndex = chapterIndex,
                    paragraphs = paragraphsOf(book, chapterIndex),
                    paraIndexes = batch,
                    sourceLang = sourceLang(),
                    targetLang = targetLang(),
                    translatorName = translatorName(),
                )
            }
        } finally {
            OcrLock.release(lockToken)
            _state.value = NovelQueueState()
        }
    }

    /**
     * 持锁跑 [block]，整段期间在后台打心跳（[token] 为 0 = 没持锁，直接跑）。
     *
     * ⚠️ **心跳是必须的**：本地引擎（LlamaCpp / NLLB）一整批可以跑几分钟，而 `OcrLock` 的
     * `STALE_TIMEOUT_MS`(30s) 无心跳自愈会把**仍活着**的持有者判成死锁 → 强制放锁 →
     * 另一个翻译任务（另一本书的章节任务 / 漫画阅读器 OCR / 超分）随即拿到锁，
     * 两个线程同时用同一个本地引擎。漫画侧 `ReaderTranslationController.runTranslate`
     * 一直在这么打，小说侧这两处一直漏。
     */
    private suspend fun <T> withLockHeartbeat(token: Long, block: suspend () -> T): T {
        if (token == 0L) return block()
        return coroutineScope {
            // ⚠️ **不要写 `launch(Dispatchers.IO)`**：本 queue 的 scope 在宿主那边就是应用级
            //    `Dispatchers.IO`，继承即可；显式指定真实派发器会把「跑到真实线程池上」
            //    这个非确定性带进用 `runTest` 虚拟时钟写的单测里（实测让 `NovelTranslationQueueTest`
            //    多挑一轮批次而挂掉）。心跳本身只是一次赋值，不需要专门换线程。
            val ticker = launch {
                while (isActive) {
                    OcrLock.heartbeat(token)
                    delay(LOCK_HEARTBEAT_MS)
                }
            }
            try {
                block()
            } finally {
                ticker.cancel()
            }
        }
    }

    /** 轮询等锁：拿到 true；超时 false。 */
    private suspend fun acquireLockWithWait(): Long {
        var waited = 0L
        while (true) {
            OcrLock.acquire().let { if (it != 0L) return it }
            if (waited >= LOCK_WAIT_TIMEOUT_MS) return 0L
            delay(LOCK_POLL_MS)
            waited += LOCK_POLL_MS
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // ⚠️ 光取消协程**不会中止已经发出的 HTTP 请求**：那些请求会继续跑到服务端返回，
        // 白烧额度、退出也被拖慢。用户口径 2026-10-03「退出阅读器要**立刻终止**自动/增量翻译」
        // → 连在途请求一起中止。引擎侧语义是「取消该实例上所有在飞请求」，
        // 这里要的正是全停（不存在误伤同实例其它批的问题：整章任务用的是**另一个**引擎实例）。
        runCatching { translator.abortAll() }
        inflightParasView = emptySet()
        _state.value = NovelQueueState()
    }

    /**
     * 翻译面板开合：**打开即暂停**（与漫画 `pauseToManual` 同义，「回退手动」由宿主做）。
     *
     * 用户打开面板多半是要改设置，此时继续按旧设置翻下去既浪费额度也可能翻错；
     * 关闭面板后由调用方按新参数重启队列。
     */
    fun setPanelOpen(open: Boolean) {
        panelOpen = open
    }
}
