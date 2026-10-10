package com.moe.starflow.novel.translate

import com.moe.starflow.data.NovelChapterStat
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.NovelFailureRow
import com.moe.starflow.data.NovelParagraphTranslationDao
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.utils.LogCollector

/**
 * 队列真正需要的那一件事：**翻一批并落库**。
 *
 * 抽成接口只有一个目的 —— 让队列的推进逻辑（页锚点、批配额、何时停）能在普通单测里
 * 用假实现驱动。那部分语义已经做错过一次（增量被做成"向后 N 章"），必须能在 JVM 单测里钉住，
 * 而 `NovelChapterTranslator` 直接依赖 Room DAO，测试里造不起。
 */
interface NovelBatchTranslator {
    suspend fun translateBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        paraIndexes: List<Int>,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
    ): NovelBatchResult

    /**
     * **中止该引擎实例上所有在飞请求**（阅读器退出 / 打开面板 / 强制退出时用）。
     *
     * ⚠️ 与「某一次请求被取消」不同：接口层没有请求句柄，一取消就是取消全部 ——
     * 这里要的正是全停（退出阅读器 = 这一次自动/增量翻译就此结束）。
     * 默认空实现：纯逻辑单测的 Fake 不受影响。
     */
    fun abortAll() {}
}

/**
 * **图片段翻译的宿主钩子**（注入 [NovelChapterTranslator]）。
 *
 * 图片段的翻译不是"把文字发出去"，而是「取插图 → OCR → 翻译 → 渲染载荷」——
 * 这两件事分别属于阅读器（知道图在哪）与图片流水线（知道怎么 OCR）。翻译器只做编排：
 * 拿到载荷就按普通译文行落库（`translatedText` = 拼接译文、`imagePayload` = 载荷 JSON）。
 *
 * ⚠️ **返回 null = 不翻图片**（阅读器按面板开关决定）：关掉开关的机器上一个图片段都不碰。
 */
class NovelImageHost(
    /** 取某段的插图文件；null = 这一段没有可用的图（回落成"跳过"）。 */
    val fileOf: suspend (book: ImportedNovel, chapterIndex: Int, paraIndex: Int) -> java.io.File?,
    /** OCR + 翻译一张插图（真实实现在 [NovelImagePipeline]）。 */
    val translate: suspend (file: java.io.File, sourceLang: String, targetLang: String) -> NovelImageOutcome,
)

/**
 * 章翻译编排：引擎产出 + 状态机写库。
 *
 * ### 状态机（**逐批**推进，不是整章一把）
 * 每一批：本批写 `TRANSLATING` → 整章其余可翻译段补成 `IDLE`（分母）→ 引擎翻 →
 * 拿到的段写 `SUCCESS`、**没拿到的段立刻写 `FAILED`**（带 `failCode` 原始原因）。
 *
 * ⚠️ **失败绝不写空译文**：空串会被读回路径当成「已成功翻译」而永不重试，
 * 于是该段永远显示空白。参考实现就踩了这个（把空串 putIfAbsent 进缓存）。
 *
 * ### 为什么 `TRANSLATING` 要落库
 * 它是跨进程的「本章正在翻」标记，也是 [resetStale] 的清理依据 —— 进程被杀会让它永久残留。
 */
class NovelChapterTranslator(
    private val dao: NovelParagraphTranslationDao,
    private val engine: NovelTranslationEngine,
    /** 必须与 `NovelParagraphSplitter.SPLIT_VERSION` 一致；不一致的旧译文读不出来。 */
    private val splitVersion: Int,
    /**
     * **单批超长预警**（[NovelBatchWarning]）：一批合并文本的 token 估算超过用户设的阈值时
     * 由宿主弹窗问「继续 / 取消这次翻译」。
     *
     * 放在这里（而不是各个入口）是因为**所有批量路径都汇到 [translateBatch]**：
     * 翻译本章 / 多选翻译 / 自动 / 增量 都会走它，收在这一处才不会漏掉某条路。
     * null = 不预警（纯逻辑单测与不关心预警的老调用方）。
     */
    private val warnGate: NovelBatchWarnGate? = null,
    /**
     * 图片段翻译的宿主（**返回 null = 不翻图片**，见 [NovelImageHost]）。
     *
     * ⚠️ 用 lambda 而不是直接传实例：阅读器是**按值缓存**这份 translator 的，而面板上的
     * 「同时翻译图片」开关随时会变 —— 传值的话开关要重启阅读器才生效。
     */
    private val imageHost: (() -> NovelImageHost?)? = null,
    /**
     * 批内插图的**进度上报**：`(chapterIndex, 第几张(1 起), 共几张)`。
     *
     * 用户口径 2026-10-04：「由于 ocr 串行，**提示系统要显示当前正在翻译的图片状态**」——
     * 一批里三张图要一张张 OCR，界面必须说得出"现在是第 2 张"，否则用户只看到一段很长的
     * "翻译中"却不知道在等什么。null = 不上报（纯逻辑单测 / 不关心进度的调用方）。
     */
    private val onImageProgress: ((Int, Int, Int) -> Unit)? = null,
    /**
     * **一个请求单位翻完并落库**时上报：`(chapterIndex, 这一单位翻出来的段号)`。
     *
     * 用户口径 2026-10-04：「当前多张图片的结果**一起出现**，应该是**先 ocr 完成返回的就先显示结果**，
     * 而不是非要等到整个批次都完成再一起显示」—— 一批现在可以发好几个请求
     * （见 `NovelBatchPlanner.requestRuns`：连续文本合并、每张插图各自一个），
     * 每落库一个就报一次，阅读器立刻把那一段/那张图上屏。
     *
     * ⚠️ 报的是「**已经写进库**」的段：调用方直接去库里读，不需要另传译文文本
     * （图片要的是载荷，不是文本；统一从库里读才不会出现"两处各存一份"）。
     * ⚠️ 失败的段**不上报**（它们的红底由失败明细那条路报），所以这个回调里拿到的段永远是成功的。
     */
    private val onUnitSettled: ((Int, List<Int>) -> Unit)? = null,
) : NovelBatchTranslator {

    private companion object {
        const val TAG = "NovelChapterTranslator"

        /** ⚠️ 与显示端共用同一个字面量（`NovelFailCode`）：两处各写一份，改一处就静默不一致 */
        const val FAIL_CODE_EMPTY = com.moe.starflow.novel.reader.NovelFailCode.TRANSLATE_EMPTY
    }

    /**
     * 把段落表映射成**未翻译（IDLE）**的行对象，且**不带译文**。
     *
     * ⚠️ 只用于建行（`insertIgnore`），绝不能拿它去 `upsertAll`（REPLACE）：
     * `translatedText` 是空串，REPLACE 会把已有的译文覆盖成空 ——
     * 用户看到的就是「翻过的段又变回空白」。
     */
    private fun idleRows(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
        /**
         * 把**图片段**也建成行（分母里算上它）。
         *
         * ⚠️ 只在「同时翻译图片」开着时为 true：关着的时候图片段按设计不翻译，
         * 算进分母会让"已翻 x/y"永远差着那几张图（用户会以为漏翻了）。
         */
        includeImages: Boolean = false,
    ): List<NovelParagraphTranslation> {
        val now = System.currentTimeMillis()
        return paragraphs
            .filter {
                (it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank()) ||
                    (includeImages && it.type == NovelParagraphType.IMAGE)
            }
            .map { p ->
                NovelParagraphTranslation(
                    novelId = book.id,
                    novelKey = book.translationKey,
                    chapterIndex = chapterIndex,
                    paraIndex = p.index,
                    sourceText = p.originalText,
                    translatedText = "",
                    state = NovelParagraphTranslation.STATE_IDLE,
                    translatorName = translatorName,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                    splitVersion = splitVersion,
                    updatedAt = now,
                )
            }
    }

    /**
     * 已经补齐过「本章全部可翻译段」的章（键 = 书 + 指纹 + 章 + 切分版本）。
     *
     * ⚠️ 不记这个标记的话，**每一批**都会把该章所有段重新造成对象再写一次库：
     * `insertIgnore` 对已存在的行确实不写，但代价在 `O(章内段数)` 的对象分配 + N 条语句执行上 ——
     * 一章 300 段、按默认每批 3 段翻完就是 100 轮 × 300 条 ≈ 3 万条 INSERT 语句；
     * 无章节标记的 txt（`TxtChapterSplitter.wholeBook`，整本当一章）段数上万，
     * 逐批累积会把翻译吞吐拖垮，用户看到的就是「越翻越慢」。
     */
    private val ensuredChapters: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun ensureKey(book: ImportedNovel, chapterIndex: Int, includeImages: Boolean = false) =
        "${book.id}:${book.translationKey}:$chapterIndex:$splitVersion${if (includeImages) ":img" else ""}"

    /**
     * 把该章**所有可翻译段**先落成 IDLE 行（`insertIgnore`：已有行一个字段都不动）。
     *
     * ⚠️ 这是「本章翻完了吗」的**分母**来源。SQL 那边是 `COUNT(*)`，而行是**按批惰性写的** ——
     * 只写这一批的话，分母退化成"已经尝试过的段数"，于是翻了几段就把整章标成「已翻译」
     * （用户报的「某一章没翻完却显示已经全部翻译完成」就是这个）。补齐之后
     * `success >= total` 才真的等于「全章可翻译段都翻完了」。
     *
     * ⚠️ 每章只补一次（见 [ensuredChapters]）。清空本章译文时标记要一起清掉（[clearChapter]），
     * 否则行被删了而标记还在 —— 分母再也补不回来，该章永远显示不出「已完成」。
     */
    private suspend fun ensureChapterRows(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
        includeImages: Boolean = false,
        /**
         * 要不要把"本章已补齐"记进 [ensuredChapters]。
         *
         * ⚠️ **只传了单段时必须是 false**（长按翻译一张图那条路）：否则那一章会被标成
         * "分母已补齐"，而库里其实只有那一段的行 —— 后面整章翻译再也不补分母，
         * 章行显示「已翻 1/1」却还有几十段没翻。
         */
        markEnsured: Boolean = true,
    ) {
        val key = ensureKey(book, chapterIndex, includeImages)
        if (markEnsured && !ensuredChapters.add(key)) return
        try {
            val rows = idleRows(
                book, chapterIndex, paragraphs, translatorName, sourceLang, targetLang, includeImages,
            )
            if (rows.isNotEmpty()) dao.insertIgnore(rows)
        } catch (e: Exception) {
            // 写失败就把标记退回去：否则这次失败会让该章的分母**永久**缺失
            if (markEnsured) ensuredChapters.remove(key)
            throw e
        }
    }

    /**
     * 一批开翻前的两步准备：**先补齐整章的行（分母），再把这一批标成「翻译中」**。
     *
     * 两步都别挪走、别合并：
     * - 补齐整章可翻译段是**分母**的来源（章徽章 / 筛选 / 目录的分母是 `COUNT(*)`）。
     *   只写这一批的话分母退化成"翻过的段数"，于是翻了几段就把整章标成「已翻译」——
     *   用户报的「没翻完却显示全部完成」。
     * - 标「翻译中」必须走 `dao.markTranslatingRows`（**UPDATE**）而不是 `INSERT OR IGNORE`：
     *   行刚被上一步建出来，`insertIgnore` 对已存在的行是空操作 —— 早先靠「先标记再补齐」的
     *   顺序绕过，但那只对该章的**第一批**有效，第二批起仍然标不上（见 DAO 的注释）。
     */
    private suspend fun prepareChapterForBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        batch: List<NovelParagraph>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
        includeImages: Boolean = false,
        /** 见 [ensureChapterRows] 的 `markEnsured`（单段调用必须传 false）。 */
        markEnsured: Boolean = true,
    ) {
        ensureChapterRows(
            book, chapterIndex, paragraphs, translatorName, sourceLang, targetLang, includeImages,
            markEnsured,
        )
        val inBatch = batch
            .filter {
                (it.type == NovelParagraphType.TEXT && it.originalText.isNotBlank()) ||
                    (includeImages && it.type == NovelParagraphType.IMAGE)
            }
            .map { it.index }
        if (inBatch.isEmpty()) return
        dao.markTranslatingRows(
            novelId = book.id,
            novelKey = book.translationKey,
            chapterIndex = chapterIndex,
            splitVersion = splitVersion,
            paraIndexes = inBatch,
            translatorName = translatorName,
            now = System.currentTimeMillis(),
        )
    }

    private suspend fun persist(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        translations: Map<Int, String>,
        translatorName: String,
        sourceLang: String,
        targetLang: String,
    ) {
        if (translations.isEmpty()) return
        val key = book.translationKey
        val now = System.currentTimeMillis()
        val sourceByIndex = paragraphs.associate { it.index to it.originalText }
        val rows = translations.map { (paraIndex, text) ->
            NovelParagraphTranslation(
                novelId = book.id,
                novelKey = key,
                chapterIndex = chapterIndex,
                paraIndex = paraIndex,
                sourceText = sourceByIndex[paraIndex] ?: "",
                translatedText = text,
                state = NovelParagraphTranslation.STATE_SUCCESS,
                translatorName = translatorName,
                sourceLang = sourceLang,
                targetLang = targetLang,
                splitVersion = splitVersion,
                updatedAt = now,
            )
        }
        dao.upsertAll(rows)
    }

    /**
     * 进入阅读器时清理残留的「翻译中」。
     *
     * ⚠️ 必须在**进入时**清（而不是退出时）：进程被杀/崩溃时「退出时」的清理根本不会执行，
     * 而残留的 TRANSLATING 会让该章既不显示译文也再也翻不了。
     */
    suspend fun resetStale(book: ImportedNovel) {
        dao.resetTranslating(book.id, book.translationKey)
        // ⚠️ 同一个入口顺手清掉**非当前分段版本**的行：主键不含 splitVersion，留着它们会让
        // 新版本的行 `insertIgnore` 静默写不进去（分母补不齐、「翻译中」标不上）。
        // 放在进入阅读器时做，和 resetTranslating 同一个理由（进程被杀时"退出时清理"不会执行）。
        dao.deleteOtherVersions(book.id, book.translationKey, splitVersion)
    }

    /**
     * 只把残留的「翻译中」复位成 IDLE（**不动**别的版本的行）。
     *
     * 用途：**强制退出在途翻译**（停队列 / 打开面板 / 切后台）时那一批被 cancel，
     * 写 SUCCESS/FAILED 的代码根本不会执行 → 库里留下 TRANSLATING，面板会把它永远显示成
     * "翻译中"（用户看到的是"卡住了"）。这里当场复位，不等下次进阅读器的 [resetStale]。
     */
    suspend fun resetTranslating(book: ImportedNovel) {
        dao.resetTranslating(book.id, book.translationKey)
    }

    /** 见 [NovelBatchTranslator.abortAll]：整批任务/阅读器退出时把在途请求真的掐掉。 */
    override fun abortAll() {
        engine.abortInFlight()
    }

    /**
     * 翻**一批**并落库（[paraIndexes] 来自 `NovelBatchPlanner.nextBatch`）。
     *
     * **一批 = 若干次请求**（见 `NovelBatchPlanner.requestRuns` 与用户口径）：连续的文本段合成**一次**
     * 请求，**每张插图各占一次**请求（OCR + 翻译），批内**串行**发出。批仍是进度/高亮/配额的单位
     * —— 所以「每批 3 段」遇到三张图就是三个请求、三张图同时黄、OCR 完一张就先显示一张。
     *
     * 这一批里没拿到的段标 `FAILED`（带 failCode）—— **绝不写空译文**：
     * 空串会被读回路径当成「已成功翻译」而永不重试（见 [persist] 的说明）。
     */
    override suspend fun translateBatch(
        book: ImportedNovel,
        chapterIndex: Int,
        paragraphs: List<NovelParagraph>,
        paraIndexes: List<Int>,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
    ): NovelBatchResult {
        if (paraIndexes.isEmpty()) return NovelBatchResult(emptyMap())
        val host = imageHost?.invoke()
        val includeImages = host != null
        // ── 批内**请求单位**（用户口径 2026-10-04，见 `NovelBatchPlanner.requestRuns`）──
        // 连续文本合并成一个请求、**每张插图独占一个请求**；批本身不拆
        //（「图片算段落的一段！每批 3 段，哪怕三张都是图片，也要先后翻译三张图片」）。
        // ⚠️ 顺序原样保留：`[文本, 图片, 文本]` 就是三次请求，不是"文本先合并、图片后跑"。
        val runs = NovelBatchPlanner.requestRuns(paragraphs, paraIndexes, includeImages)
        if (runs.isEmpty()) return NovelBatchResult(emptyMap())
        val textIndexes = runs.filter { !it.isImage }.flatMap { it.paraIndexes }
        val imageRuns = runs.filter { it.isImage }
        val byIndex = paragraphs.associateBy { it.index }

        // **单批超长预警**：合并文本估算超过用户设的阈值 → 由宿主弹窗问「继续 / 取消这次翻译」。
        // 用户取消 → 这一批的**文本**不发请求、不写库（不写 FAILED：那些行保持 IDLE，用户可以改大阈值或
        // 直接重来），只回一个空结果，由调用方按"没翻成"处理（队列会记账跳过、不无限重试）。
        // ⚠️ 估算口径仍按「这一批的**全部**文本段之和」（拆请求前后一致 —— 一批纯文本时它就等于那一个
        // 请求的正文；混合批里略微保守，属于刻意：宁可早问一次也别把整章正文塞进一次上下文）。
        var textDeclined = false
        val gate = warnGate
        if (gate != null && textIndexes.isNotEmpty()) {
            val threshold = gate.threshold()
            val estimate = NovelBatchWarning.estimateOf(
                textIndexes.mapNotNull { byIndex[it]?.originalText },
            )
            if (NovelBatchWarning.shouldWarn(estimate, threshold)) {
                val go = gate.confirm(estimate, threshold)
                LogCollector.w(
                    TAG,
                    "第 $chapterIndex 章一批内容约 $estimate tokens（阈值 $threshold），用户选择继续=$go",
                )
                // ⚠️ 只跳过**文本**那一半：图片本来就不走超长预警（OCR 出来的短句不可能超），
                // 用户选择"取消本次翻译"时不该把同一批里的插图也一起吞掉
                if (!go) textDeclined = true
            }
        }

        // 这一批的段**一起标成「翻译中」**（分母 + 状态）：三张图也要同时显示黄色状态
        //（用户口径：「这三张图片都要显示黄色状态」），而不是按请求一个个标。
        // 落库前的两步准备工作合在一处（顺序敏感，见 prepareChapterForBatch 的说明）。
        if (textDeclined) {
            // 用户取消了这一批的**文本**：一个字的库都不写（老行为 —— 那几段保持 IDLE 可重来），
            // 只把同一批里的插图准备一下（它们本就不走超长预警，不该被一起吞掉）。
            // ⚠️ 这里只喂插图段：喂整章 `paragraphs` 会把整章的分母行建出来，等于"取消"还写了库。
            if (imageRuns.isNotEmpty()) {
                val imgParas = imageRuns.mapNotNull { byIndex[it.paraIndexes.first()] }
                prepareChapterForBatch(
                    book, chapterIndex, imgParas, imgParas, translatorName, sourceLang, targetLang,
                    includeImages = true, markEnsured = false,
                )
            }
        } else {
            prepareChapterForBatch(
                book, chapterIndex, paragraphs,
                runs.flatMap { run -> run.paraIndexes.mapNotNull { byIndex[it] } },
                translatorName, sourceLang, targetLang, includeImages,
            )
        }

        val got = LinkedHashMap<Int, String>()
        var error: String? = null

        // ── 逐个请求单位串行发出 ──
        // 批内的请求是**串行**的：插图 OCR 是单例引擎（`OcrLock`），文本与插图也没必要抢带宽。
        // 跨批的并发仍由队列的「最大同时请求数」管 —— 所以同时飞出去的请求数**不会**因为拆请求而变多。
        var imageDone = 0
        for (run in runs) {
            if (!run.isImage) {
                if (textDeclined) continue
                val result = engine.translateBatch(paragraphs, run.paraIndexes, sourceLang, targetLang)
                got.putAll(result.translations)
                if (result.error != null && error == null) error = result.error
                persist(
                    book = book,
                    chapterIndex = chapterIndex,
                    paragraphs = paragraphs,
                    translations = result.translations,
                    translatorName = translatorName,
                    sourceLang = sourceLang,
                    targetLang = targetLang,
                )
                // **落库即上报**：阅读器马上把这几段上屏（不必等这一批剩下的请求跑完）
                // ⚠️ 排一下序：`result.translations` 是引擎按**回文顺序**建的（模型可能逆序回包），
                // 上报顺序跟着它走会让日志/断言随模型脾气变 —— 这里要的是"段号集合"，排一下更稳。
                if (result.translations.isNotEmpty()) {
                    onUnitSettled?.invoke(chapterIndex, result.translations.keys.sorted())
                }
            } else {
                val para = byIndex[run.paraIndexes.first()] ?: continue
                // 批内插图进度：`i/N` 里的 N 是**这一批的插图数**（用户口径「提示系统要显示
                // 当前正在翻译的图片状态」）
                imageDone++
                onImageProgress?.invoke(chapterIndex, imageDone, imageRuns.size)
                val one = translateOneImage(
                    book, chapterIndex, para, host!!, sourceLang, targetLang, translatorName,
                )
                if (one.first != null) {
                    got[para.index] = one.first!!
                    // **OCR 完一张就先显示一张**（用户口径：先完成先显示，不等整批）
                    onUnitSettled?.invoke(chapterIndex, listOf(para.index))
                } else {
                    one.second?.let { if (error == null) error = it }
                }
            }
        }
        // 这一批的插图跑完了（或本来就没有）→ 清掉"第 i/N 张"的提示，别让它挂到下一批
        if (imageRuns.isNotEmpty()) onImageProgress?.invoke(chapterIndex, 0, imageRuns.size)

        val missed = (textIndexes + imageRuns.map { it.paraIndexes.first() })
            // 用户主动取消的那一半文本**不标失败**（保持 IDLE，改大阈值就能重来）
            .filter { it !in got && !(textDeclined && it in textIndexes) }
        if (missed.isNotEmpty()) {
            dao.markFailed(
                novelId = book.id,
                novelKey = book.translationKey,
                chapterIndex = chapterIndex,
                splitVersion = splitVersion,
                paraIndexes = missed,
                // ⚠️ failCode 存**原始原因**（异常类型 + message 整条链 / 模型到底回了什么），
                // 不是一句"失败"：用户在章行展开里看到的就是它，含糊的文案等于没报错。
                failCode = error ?: FAIL_CODE_EMPTY,
                now = System.currentTimeMillis(),
            )
            LogCollector.w(TAG, "第 $chapterIndex 章有 ${missed.size} 段没拿到译文：$error")
        }
        return NovelBatchResult(got, error)
    }

    /**
     * 翻**一张插图**：取图 → OCR + 翻译（[NovelImageHost.translate]）→ 落库（带载荷）。
     *
     * 返回 `(拼接译文, 错误原因)`：译文非 null = 成功；两个都是 null = 没有可翻的图（跳过）。
     *
     * ⚠️ 载荷里有**矩形与 OCR 原文**，所以三态切换、重新渲染都不用重跑 OCR；
     * `translatedText` 同时写一份，导出/进度/「已翻」判据都复用它。
     *
     * ⚠️ 「翻译中」的行状态由**调用方**（[translateBatch]）在这一批开跑前统一标好
     * （整批一起标：三张图要同时黄）。这里不再重复标一次 —— 那只是每张图多两条 SQL。
     */
    private suspend fun translateOneImage(
        book: ImportedNovel,
        chapterIndex: Int,
        para: NovelParagraph,
        host: NovelImageHost,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
    ): Pair<String?, String?> {
        val file = runCatching { host.fileOf(book, chapterIndex, para.index) }.getOrNull()
            ?: return null to "这一段没有可用的插图"
        val outcome = runCatching { host.translate(file, sourceLang, targetLang) }
            .onFailure { LogCollector.e(TAG, "第 $chapterIndex 章插图（段 ${para.index}）翻译失败", it) }
            .getOrElse { e -> NovelImageOutcome(null, "${e.javaClass.simpleName}: ${e.message.orEmpty()}") }
        val payload = outcome.payload
        if (payload == null) {
            // error == null 表示"用户取消/中止" —— 不标失败，让下一轮重挑
            return null to outcome.error
        }
        dao.upsert(
            NovelParagraphTranslation(
                novelId = book.id,
                novelKey = book.translationKey,
                chapterIndex = chapterIndex,
                paraIndex = para.index,
                sourceText = payload.sourceText,
                translatedText = payload.translatedText,
                state = NovelParagraphTranslation.STATE_SUCCESS,
                translatorName = translatorName,
                sourceLang = sourceLang,
                targetLang = targetLang,
                splitVersion = splitVersion,
                updatedAt = System.currentTimeMillis(),
                imagePayload = NovelImagePayloadCodec.encode(payload),
            ),
        )
        LogCollector.i(
            TAG,
            "第 $chapterIndex 章插图（段 ${para.index}）翻译完成：${payload.blocks.size} 块",
        )
        return payload.translatedText to null
    }

    /**
     * 把**一张插图的译文载荷**落库（长按单张翻译那条路用）。
     *
     * 与批量路径写的是同一套字段：`translatedText` = 拼接译文（导出/进度/「已翻」判据都吃它）、
     * `sourceText` = OCR 原文、`imagePayload` = 载荷 JSON、`state` = SUCCESS。
     * 走 [NovelChapterTranslator] 而不是阅读器直接写 DAO，是为了让"怎么算一条成功的图片译文"
     * 只有一个实现 —— 两条路各写一份，迟早一条改了另一条没改。
     */
    suspend fun saveImageTranslation(
        book: ImportedNovel,
        chapterIndex: Int,
        paraIndex: Int,
        payload: NovelImageTranslation,
        sourceLang: String,
        targetLang: String,
        translatorName: String,
    ) {
        // 分母：图片段也要有行（否则面板的 x/y 里没有它，翻完了也看不出来）
        // ⚠️ `markEnsured = false`：长按单张图只拿到这一段，不能宣布"本章分母已补齐"
        ensureChapterRows(
            book = book,
            chapterIndex = chapterIndex,
            paragraphs = listOf(NovelParagraph(paraIndex, NovelParagraphType.IMAGE, payload.sourceText)),
            translatorName = translatorName,
            sourceLang = sourceLang,
            targetLang = targetLang,
            includeImages = true,
            markEnsured = false,
        )
        dao.upsert(
            NovelParagraphTranslation(
                novelId = book.id,
                novelKey = book.translationKey,
                chapterIndex = chapterIndex,
                paraIndex = paraIndex,
                sourceText = payload.sourceText,
                translatedText = payload.translatedText,
                state = NovelParagraphTranslation.STATE_SUCCESS,
                translatorName = translatorName,
                sourceLang = sourceLang,
                targetLang = targetLang,
                splitVersion = splitVersion,
                updatedAt = System.currentTimeMillis(),
                imagePayload = NovelImagePayloadCodec.encode(payload),
            ),
        )
    }

    /**
     * 清除指定段的译文（长按多选 → 清除）。
     *
     * ⚠️ **不删行**，只把状态重置为未翻译 —— 理由见 DAO 的 `resetToIdle`（那几行是分母的来源）。
     */
    suspend fun clearParagraphs(
        book: ImportedNovel,
        chapterIndex: Int,
        paraIndexes: List<Int>,
    ) {
        if (paraIndexes.isEmpty()) return
        dao.resetToIdle(
            novelId = book.id,
            novelKey = book.translationKey,
            chapterIndex = chapterIndex,
            splitVersion = splitVersion,
            paraIndexes = paraIndexes,
            now = System.currentTimeMillis(),
        )
    }

    /** 失败明细（章行展开显示原因用），按章分组。 */
    suspend fun failuresOf(book: ImportedNovel): Map<Int, List<NovelFailureRow>> =
        dao.failures(book.id, book.translationKey, splitVersion).groupBy { it.chapterIndex }

    /**
     * 只清**本章**译文（用户明确要求：不要一点就清整本）。
     *
     * ⚠️ 行删了，[ensuredChapters] 里那个标记也必须删：留着它的话下次翻这一章不会再补分母，
     * `COUNT(*)` 恒为 0，该章永远显示不出「已完成」。
     */
    suspend fun clearChapter(book: ImportedNovel, chapterIndex: Int) {
        dao.deleteForChapter(book.id, book.translationKey, chapterIndex)
        ensuredChapters.remove(ensureKey(book, chapterIndex))
    }

    /** 本章已成功的译文：`paraIndex -> 译文`。 */
    suspend fun loadTranslations(book: ImportedNovel, chapterIndex: Int): Map<Int, String> =
        dao.forChapter(book.id, book.translationKey, chapterIndex, splitVersion)
            .filter { it.state == NovelParagraphTranslation.STATE_SUCCESS && it.translatedText.isNotEmpty() }
            .associate { it.paraIndex to it.translatedText }

    /**
     * 本章**图片段**的译文载荷：`paraIndex -> 载荷`（解析不出来 / 没有载荷的行直接跳过）。
     *
     * ⚠️ 与 [loadTranslations] 各读各的：后者给的是"这段显示什么文字"（导出、三态、进度都用它），
     * 这里给的是"这张图怎么画"（矩形 + OCR 原文 + 译文）。两张表都以 `state == SUCCESS` 为准。
     */
    suspend fun loadImageTranslations(
        book: ImportedNovel,
        chapterIndex: Int,
    ): Map<Int, NovelImageTranslation> =
        dao.forChapter(book.id, book.translationKey, chapterIndex, splitVersion)
            .filter { it.state == NovelParagraphTranslation.STATE_SUCCESS && !it.imagePayload.isNullOrBlank() }
            .mapNotNull { row -> NovelImagePayloadCodec.decode(row.imagePayload)?.let { row.paraIndex to it } }
            .toMap()

    /** 章状态聚合（目录徽章 / 队列判断用）。 */
    suspend fun chapterStats(book: ImportedNovel): Map<Int, NovelChapterStat> =
        dao.chapterStats(book.id, book.translationKey, splitVersion).associateBy { it.chapterIndex }
}
