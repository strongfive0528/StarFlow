package com.moe.starflow.novel.translate

import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列推进的**语义守卫**（纯 JVM，用假翻译器驱动）。
 *
 * 这里钉死的是三种模式到底怎么往前走 —— 这一块曾经整体做错过一次
 * （把「增量」实现成了「向后 N **章**」），所以每条语义都配一条断言：
 * 自动只在本页内推进、增量向后按**批**、章末停下、配额用尽停下、一批失败不无限重试。
 */
class NovelTranslationQueueTest {

    private class FakeTranslator : NovelBatchTranslator {
        val batches = mutableListOf<List<Int>>()
        var failAll = false

        /** 一批"跑"多久（虚拟时间）；> 0 时用来制造"上一批还在飞"的场景。 */
        var holdMs = 0L

        /** 同时在跑的批数 / 历史峰值 —— 并发与"图片串行"都靠它断言（比数批次数可靠）。 */
        var concurrent = 0
        var maxConcurrent = 0

        override suspend fun translateBatch(
            book: ImportedNovel,
            chapterIndex: Int,
            paragraphs: List<NovelParagraph>,
            paraIndexes: List<Int>,
            sourceLang: String,
            targetLang: String,
            translatorName: String,
        ): NovelBatchResult {
            batches += paraIndexes
            concurrent++
            if (concurrent > maxConcurrent) maxConcurrent = concurrent
            try {
                if (holdMs > 0) kotlinx.coroutines.delay(holdMs)
                if (failAll) return NovelBatchResult(emptyMap(), "fake 失败")
                return NovelBatchResult(paraIndexes.associateWith { "译$it" })
            } finally {
                concurrent--
            }
        }
    }

    private fun book() = ImportedNovel(
        id = 1, title = "t", localRoot = "/tmp", format = NovelFormat.TXT, chapterCount = 3, addedAt = 1,
    )

    private fun queue(
        scope: CoroutineScope,
        translator: FakeTranslator,
        translated: MutableSet<Int>,
        page: () -> List<Int>,
        chapterSize: Int,
        batchSize: Int,
        /** 当前页**全部**段（含不可翻译）—— 默认与 [page] 相同（绝大多数用例没有不可翻译段）。 */
        span: () -> List<Int> = page,
        /** 启动延迟（防抖）；默认 0 让既有用例的时序保持逐字不变。 */
        debounce: Int = 0,
        /** 同时最多几个批在飞；默认 1（与旧行为一致）。 */
        concurrency: Int = 1,
        /** 图片段判据（默认"没有图片段"）；图片单位要独占一批并串行。 */
        isImage: (Int) -> Boolean = { false },
    ) = NovelTranslationQueue(
        scope = scope,
        translator = translator,
        paragraphsOf = { _, _ ->
            (0 until chapterSize).map { NovelParagraph(it, NovelParagraphType.TEXT, "p$it") }
        },
        sourceLang = { "en" },
        targetLang = { "zh" },
        translatorName = { "fake" },
        batchSize = { batchSize },
        debounceMs = { debounce },
        currentPageParaIndexes = page,
        currentPageSpanIndexes = span,
        chapterParaIndexes = { _, _ -> (0 until chapterSize).toList() },
        translatedIndexes = { _, _ -> translated.toSet() },
        concurrency = { concurrency },
        isImagePara = { _, p -> isImage(p) },
    )

    /**
     * ⚠️ **本页一个可翻译段都没有**（图片页 / 整页都是 <4 字短行）时，增量必须拿「当前页**全部**段」
     * 定位窗口起点 —— 既不能退回章首（会翻到用户早就不看的地方），也不能停住
     * （用户报的「当前页段数不足 → 增量始终 0 批」）。
     */
    @Test
    fun `增量在本页无可翻译段时用当前页位置定位窗口`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(
            backgroundScope, t, translated,
            page = { emptyList() },       // 本页没有可翻译段
            span = { listOf(10, 11) },    // 但本页确实落在第 10 段那一位
            chapterSize = 30, batchSize = 2,
        )

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(2), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(10_000)

        assertEquals("第一批必须从当前页位置（第 10 段）起，不能从章首起", listOf(10, 11), t.batches.first())
        assertTrue("不许翻到当前页之前的段", t.batches.flatten().all { it >= 10 })
        q.stop()
    }

    /** 自动：当前页是唯一锚点 —— 本页翻完就停，**不越页**。 */
    @Test
    fun `自动模式把当前页翻完就停`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertEquals("只翻当前页那一批", listOf(listOf(0, 1)), t.batches)
        q.stop()
    }

    // ===== 插图（用户口径 2026-10-04：「图片算段落的一段！每批 3 段，哪怕三张都是图片，
    //       也要先后翻译三张图片」）=====

    /**
     * **插图不拆批**：批按「每批段数」切，一批里可以混着文本与插图 ——
     * 老实现把图拆成独占批，用户实测就是「每批段数的设置遇到图片就中断」。
     *
     * "一张一张来"改由 `NovelChapterTranslator` 在批内串行（它自己拿 OCR 锁）实现，
     * 队列这边只管照常按段数成批。
     */
    @Test
    fun `插图与文本同批且按每批段数切`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        // 章内 0..5，第 2 段是插图；每批 3 段
        val q = queue(
            backgroundScope, t, translated,
            page = { listOf(0, 1, 2, 3, 4, 5) }, chapterSize = 6, batchSize = 3,
            isImage = { it == 2 },
        )

        q.start(book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(10), currentChapter = { 0 }) { _, r ->
            translated += r.translations.keys
        }
        advanceTimeBy(30_000)

        assertEquals(
            "严格按每批段数切，插图留在自己那一批里（顺序不变）",
            listOf(listOf(0, 1, 2), listOf(3, 4, 5)),
            t.batches,
        )
        q.stop()
    }

    /** **三张连续的图 = 一批三张**（用户点名的场景）。 */
    @Test
    fun `三张连续插图同一批`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(
            backgroundScope, t, translated,
            page = { listOf(0, 1, 2, 3) }, chapterSize = 4, batchSize = 3,
            isImage = { true },
        )

        q.start(book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(10), currentChapter = { 0 }) { _, r ->
            translated += r.translations.keys
        }
        advanceTimeBy(30_000)

        assertEquals("三张图一批、第四张下一批", listOf(listOf(0, 1, 2), listOf(3)), t.batches)
        q.stop()
    }

    /**
     * **含插图的批之间不并发**：OCR 是单例引擎（`OcrLock` 一把），两批含图的批同时起飞
     * 只会让后来那批在锁上干等、还白占并发槽位。
     *
     * 断言用**并发峰值**（假翻译器记 `maxConcurrent`）：并发 4 下也必须是 1。
     */
    @Test
    fun `含插图的批之间串行`() = runTest {
        val t = FakeTranslator()
        t.holdMs = 1_000
        val translated = mutableSetOf<Int>()
        // 六张图、每批 3 段 → 两批，各自含图
        val q = queue(
            backgroundScope, t, translated,
            page = { (0..5).toList() }, chapterSize = 6, batchSize = 3,
            concurrency = 4, isImage = { true },
        )

        q.start(book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(10), currentChapter = { 0 }) { _, r ->
            translated += r.translations.keys
        }
        advanceTimeBy(10_000)

        assertEquals("同一时刻只允许一批含图的批在飞（并发 4 也一样）", 1, t.maxConcurrent)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), t.batches)
        q.stop()
    }

    /** 自动：本页有多批时要**一批批翻到整页翻完**（用户确认过的口径）。 */
    @Test
    fun `自动模式在当前页内一批批翻到翻完`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1, 2) }, chapterSize = 6, batchSize = 1)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), t.batches)
        q.stop()
    }

    /** 增量：页内翻完后**继续在本章向后**，翻满配额即停（不是翻后面几章）。 */
    @Test
    fun `增量模式向后翻满配额即停`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 20, batchSize = 2)

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(2), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(60_000)

        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), t.batches)
        q.stop()
    }

    /** 增量：章末就停，**不去下一章**。 */
    @Test
    fun `增量模式在本章末尾停下`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(4) }, chapterSize = 5, batchSize = 2)

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(9), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(60_000)

        assertEquals(listOf(listOf(4)), t.batches)
        q.stop()
    }

    /** 手动不进队列循环；点一次 = 从当前页锚点翻**一批**。 */
    @Test
    fun `手动模式不进队列但要一批就翻一批`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(2, 3) }, chapterSize = 20, batchSize = 2)

        q.start(book(), NovelTranslateMode.MANUAL, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        assertTrue("手动模式队列不该自己翻", t.batches.isEmpty())

        val got = q.translateOneBatch(book(), 0)
        assertEquals(mapOf(2 to "译2", 3 to "译3"), got.translations)
        assertEquals(listOf(listOf(2, 3)), t.batches)
        q.stop()
    }

    /** 面板打开即暂停；关掉之后按同一套参数接着走。 */
    @Test
    fun `面板打开暂停关闭后继续`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.setPanelOpen(true)
        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        assertTrue("面板开着时不该翻", t.batches.isEmpty())

        q.setPanelOpen(false)
        advanceTimeBy(5_000)
        assertEquals(listOf(listOf(0, 1)), t.batches)
        q.stop()
    }

    /**
     * 一批彻底失败（模型回空）不能变成无限重试：失败的锚点要记账跳过，
     * 否则每轮重挑同一批 —— 额度烧光、进度条永远不动。
     */
    @Test
    fun `失败的批不会无限重试`() = runTest {
        val t = FakeTranslator().apply { failAll = true }
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        val afterFirst = t.batches.size

        advanceTimeBy(60_000)
        assertEquals("失败后不该继续重挑同一批", afterFirst, t.batches.size)
        assertEquals("失败 [0,1] → 锚点前移再失败 [1,2] → 之后再无待翻段，停", 2, afterFirst)
        q.stop()
    }

    /**
     * **重翻**（长按多选 → 重新翻译）：已经在译文表里的段**照样送出去**。
     *
     * 这条路刻意不经过 `NovelBatchPlanner` —— 规划器把「已有译文」当成已完成而跳过，
     * 用了它重翻就永远翻不动（用户看到的是"点了重翻但什么都没变"）。
     */
    @Test
    fun `按段翻译不跳过已有译文的段`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf(0, 1, 5)
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 3)

        val got = q.translateExact(book(), 0, listOf(0, 1, 4))

        assertEquals(mapOf(0 to "译0", 1 to "译1", 4 to "译4"), got.translations)
        assertEquals("选谁翻谁、一次请求", listOf(listOf(0, 1, 4)), t.batches)
        q.stop()
    }

    /**
     * **面板「翻译本章」= 真的翻整章**：一批批翻到章末，不是只翻一批。
     * （这颗按钮长期写着「翻译本章」却只翻一批，文案与行为对不上。）
     */
    @Test
    fun `翻译整章会一批批翻到章末`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 5, batchSize = 2)

        val done = q.translateWholeChapter(book(), 0) { r -> translated += r.translations.keys }

        assertEquals("5 段 / 每批 2 段 → 3 批", 3, done)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), t.batches)
        q.stop()
    }

    /**
     * **「翻译本章」必须重试之前失败过的段**（回归）。
     *
     * `failedAnchors` 是自动/增量模式"这批别再空转"的记账（见类注释纪律 2），而
     * 「翻译本章」是用户**明确要求重来一次**。不清它的话，自动模式里失败过的那几段会被
     * 静默跳过，任务却报「已翻完 N 批」—— 用户以为翻完了，其实那几段永远是原文。
     */
    @Test
    fun `翻译整章会重试之前失败过的段`() = runTest {
        val t = FakeTranslator().apply { failAll = true }
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 4, batchSize = 2)

        // 自动模式先全失败一轮 —— 每个锚点都进 failedAnchors
        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        assertTrue("前提：自动模式确实失败过", t.batches.isNotEmpty())
        q.stop()

        t.failAll = false
        val done = q.translateWholeChapter(book(), 0) { r -> translated += r.translations.keys }

        assertEquals("4 段 / 每批 2 段 → 必须真的翻成 2 批（跳过失败段的话是 0）", 2, done)
        assertTrue("4 段必须都拿到译文", translated.containsAll(listOf(0, 1, 2, 3)))
        q.stop()
    }

    /**
     * **用户清掉译文的段，重新开启自动/增量时要照常翻**（用户口径 2026-10-04）。
     *
     * 用户报的现场：「清译文后重翻只有手动生效，自动和增量即使当前页面刚才删除了译文，
     * 开启也没有任何翻译，不会翻译」—— 老实现有一份"用户清过的段别再自动挑"的账，
     * 清完再开自动就全被静默跳过。现在这份账**没有了**：
     * 清译文 = 一次显式编辑（宿主当场停队列 + 回退手动），之后那几段就是普通"没译文的段"。
     * 判据很直白：**译文表里没有它们** → 自动模式把它们当成待翻段。
     */
    @Test
    fun `清掉译文的段重新开启自动照常翻`() = runTest {
        val t = FakeTranslator()
        // 清掉译文 = 库里那几段回到"没有译文"的状态（translations 里没有它们）
        val translated = mutableSetOf<Int>()
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 4, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertEquals("清过的段（库里没译文）自动模式必须照常翻", listOf(listOf(0, 1)), t.batches)
        q.stop()
    }

    /**
     * **上一批收尾后要等一次防抖才认领下一批**（用户口径 2026-10-04）。
     *
     * 用户报的现场：「**黄色翻译中的背景在上一批翻译完后就会立刻跳到当前正在翻的页面**」——
     * 旧写法把防抖只挂在"翻屏"上，而翻屏时上一批还在飞：那次防抖在"等在飞批"的过程中被白白
     * 消耗掉，上一批真收尾时防抖早已过期 → 立刻认领，黄底无缝跳过去。
     * 现在**有批收尾（在飞清零）也会排一次防抖**，所以两批之间一定看得见间隔。
     */
    @Test
    fun `上一批收尾后要等防抖才发下一批`() = runTest {
        val t = FakeTranslator().apply { holdMs = 300 }
        val translated = mutableSetOf<Int>()
        val q = queue(
            backgroundScope, t, translated, page = { listOf(0, 1, 2, 3) },
            chapterSize = 8, batchSize = 2, debounce = 500,
        )

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }

        // t=500（首屏防抖）认领第 1 批；它 300ms 后收尾
        advanceTimeBy(520)
        assertEquals("首批在防抖后发出", listOf(listOf(0, 1)), t.batches)
        advanceTimeBy(400)   // t=920：第 1 批已收尾（t=800），防抖要到 t=1300
        assertEquals("上一批收尾后必须停一拍，不能立刻跳下一批", 1, t.batches.size)

        advanceTimeBy(500)   // t=1420：防抖过去了 → 第 2 批
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), t.batches)
        q.stop()
    }

    /** 空选择不发请求（白抢一次锁、白烧一次额度）。 */
    @Test
    fun `空选择不发请求`() = runTest {
        val t = FakeTranslator()
        val q = queue(backgroundScope, t, mutableSetOf(), page = { listOf(0) }, chapterSize = 3, batchSize = 3)

        val got = q.translateExact(book(), 0, emptyList())

        assertTrue(got.isEmpty)
        assertTrue(t.batches.isEmpty())
        q.stop()
    }

    /**
     * **增量窗口跟着当前页走**（用户口径）：额度用完不是终点 —— 用户翻页后窗口前移，
     * 自动接着往后翻。做成"一次性额度"的话翻页也毫无反应（用户报的"配额用完就不动了"）。
     */
    @Test
    fun `增量窗口用完后再翻页会接着往后翻`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        var page = listOf(0, 1)
        val q = queue(backgroundScope, t, translated, page = { page }, chapterSize = 30, batchSize = 2)

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(2), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }
        advanceTimeBy(10_000)

        assertEquals("本页一批 + 窗口内向后一批（2 批 × 2 段 = 4 段）", listOf(listOf(0, 1), listOf(2, 3)), t.batches)
        advanceTimeBy(60_000)
        assertEquals("窗口内翻完就停，不越界继续往后", 2, t.batches.size)
        assertEquals("窗口翻完 → 剩 0 批（不是负、也不是还剩一批）", 0, q.state.value.remaining)

        page = listOf(10, 11)
        advanceTimeBy(10_000)
        assertEquals(
            "翻页 → 窗口前移（10 起往后 4 段），接着翻两批",
            listOf(listOf(10, 11), listOf(12, 13)),
            t.batches.drop(2),
        )
        advanceTimeBy(60_000)
        assertEquals("新窗口翻完又停下，不会一路翻到章末", 4, t.batches.size)
        q.stop()
    }

    /**
     * **翻页后必须重新等一次防抖**（用户口径 2026-10-03：「前面的页还在翻译，我翻到别的地方，
     * 别的页**立刻**变黄 —— 防抖机制完全没起作用」）。
     *
     * 回归点：旧实现的防抖是"整条流水线空着才等一次" —— 上一屏还有批在飞时，翻到新一屏
     * **一个防抖都不等**就认领新批（用户看到的"别的地方立刻变黄"）。现在防抖按**屏**重计：
     * 用户还在连翻就一轮一轮往后推，只有停下来的那一屏才开始翻。
     */
    @Test
    fun `翻页后要重新等防抖才认领新页`() = runTest {
        val t = FakeTranslator().apply { holdMs = 5_000 }
        val translated = mutableSetOf<Int>()
        var page = listOf(0, 1)
        val q = queue(
            backgroundScope, t, translated, page = { page },
            chapterSize = 30, batchSize = 2, debounce = 500, concurrency = 3,
        )

        q.start(
            book(), NovelTranslateMode.AHEAD, quota = NovelQuota.of(10), currentChapter = { 0 },
        ) { _, r -> translated += r.translations.keys }

        // t=500：首批（防抖 500ms 后）认领，此后一直在飞
        // ⚠️ `advanceTimeBy(500)` 不会执行**正好落在 500ms** 的任务，所以多推一点点
        advanceTimeBy(520)
        assertEquals(listOf(listOf(0, 1)), t.batches)

        // 翻到新的一屏：调度器看到新页 → **重新**防抖，防抖没到就不许认领
        page = listOf(2, 3)
        advanceTimeBy(700)
        assertEquals("翻页后不足一个防抖窗口，不许认领新页（实际 ${t.batches}）", 1, t.batches.size)

        // 防抖到点 → 才开始翻新的一屏
        advanceTimeBy(600)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), t.batches)
        q.stop()
    }

    /**
     * **翻页后待翻的屏变了 → 自动接着翻新屏**（没有"清过译文的账"要维护之后，这条更直接：
     * 只要库里没译文就是待翻段）。
     */
    @Test
    fun `翻页后自动接着翻新的一屏`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf<Int>()
        var page = listOf(0, 1)
        val q = queue(backgroundScope, t, translated, page = { page }, chapterSize = 20, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)
        assertEquals(listOf(listOf(0, 1)), t.batches)

        page = listOf(2, 3)
        advanceTimeBy(5_000)
        page = listOf(0, 1)      // 翻回来：这两段已经有译文了，不该重复发
        advanceTimeBy(5_000)

        assertEquals(
            "划过一屏翻回来：已译的段不重发、新屏照翻",
            listOf(listOf(0, 1), listOf(2, 3)),
            t.batches,
        )
        q.stop()
    }

    /**
     * **等页面稳定期间要有明确的阶段**（用户口径 2026-10-04：「我没看到任何等待页面稳定…的相关提示信息」）。
     *
     * 这一拍**还没发请求**，所以：阶段 = `WAITING_STABLE`、**高亮段为空**（不画黄底）；
     * 稳定期满、真的为这一屏发出请求时才转 `TRANSLATING`。
     */
    @Test
    fun `等页面稳定期间是 WAITING_STABLE 且没有高亮`() = runTest {
        // 批要"跑"一会儿：否则它瞬间收尾，又会立刻排下一拍等待（阶段在两种态间来回跳，测不出东西）
        val t = FakeTranslator().apply { holdMs = 3_000 }
        val q = queue(
            backgroundScope, t, mutableSetOf(), page = { listOf(0, 1) },
            chapterSize = 4, batchSize = 2, debounce = 500,
        )

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, _ -> }

        advanceTimeBy(100)   // 还在"等页面稳定"窗口里
        assertEquals(NovelQueuePhase.WAITING_STABLE, q.state.value.phase)
        assertTrue("等待期间还没发请求 → 不该有黄底段", q.state.value.batchParaIndexes.isEmpty())

        advanceTimeBy(500)   // t=600：稳定期满 → 认领这一屏
        assertEquals(NovelQueuePhase.TRANSLATING, q.state.value.phase)
        assertEquals(listOf(listOf(0, 1)), t.batches)
        assertEquals("认领后才有黄底（这几段真在飞）", listOf(0, 1), q.state.value.batchParaIndexes)
        q.stop()
    }

    /** 没有可翻的段（页内全翻完）→ 停在 DRAINED，而不是空转刷状态。 */
    @Test
    fun `没有待翻的段时停在 DRAINED`() = runTest {
        val t = FakeTranslator()
        val translated = mutableSetOf(0, 1)
        val q = queue(backgroundScope, t, translated, page = { listOf(0, 1) }, chapterSize = 6, batchSize = 2)

        q.start(book(), NovelTranslateMode.AUTO, currentChapter = { 0 }) { _, r -> translated += r.translations.keys }
        advanceTimeBy(5_000)

        assertTrue(t.batches.isEmpty())
        assertEquals(NovelQueuePhase.DRAINED, q.state.value.phase)
        assertNull("自动模式没有配额概念", q.state.value.remaining)
        q.stop()
    }
}
