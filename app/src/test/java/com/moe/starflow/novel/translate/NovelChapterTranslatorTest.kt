package com.moe.starflow.novel.translate

import android.content.Context
import androidx.room.Room
import com.moe.starflow.data.NovelParagraphTranslation
import com.moe.starflow.data.NovelParagraphTranslationDao
import com.moe.starflow.data.TranslationHistoryDatabase
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import com.moe.starflow.translate.TranslationResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelChapterTranslatorTest {

    private lateinit var db: TranslationHistoryDatabase
    private lateinit var dao: NovelParagraphTranslationDao

    private val book = ImportedNovel(
        id = 1,
        title = "书",
        localRoot = "/tmp/1",
        format = NovelFormat.TXT,
        chapterCount = 2,
        addedAt = 5000L,
    )

    /** 段 0、2 是正文，段 1 是过短段（不参与翻译，但**占 index**）。 */
    private val paragraphs = listOf(
        NovelParagraph(0, NovelParagraphType.TEXT, "第一段原文"),
        NovelParagraph(1, NovelParagraphType.SKIP, "……"),
        NovelParagraph(2, NovelParagraphType.TEXT, "第二段原文"),
    )

    /**
     * 假翻译器：解析 prompt 里的 `[N]` 前缀，按**同样的编号**回译文，并统计调用次数。
     * 这样它同时验证了「引擎把真实 paraIndex 发出去」这件事。
     */
    private class FakeTranslator(
        var calls: Int = 0,
        var failAll: Boolean = false,
        /** true = 回文里丢掉编号（用于测位置兜底）。 */
        var dropNumbering: Boolean = false,
    ) : NovelTextTranslator {
        override val name: String get() = "fake"

        override fun translate(
            prompt: String,
            sourceLang: String,
            targetLang: String,
            callback: (TranslationResult) -> Unit,
        ) {
            calls++
            if (failAll) {
                callback(TranslationResult.Error(Exception("boom")))
                return
            }
            val matched = prompt.lineSequence()
                .mapNotNull { LINE.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }
                .toList()
            val body = if (dropNumbering) {
                matched.joinToString("\n\n") { (_, text) -> "T:$text" }
            } else {
                // ⚠️ **逆序**回包：按请求顺序回的话「按键位对应」和「按位置对应」结果完全相同，
                // 改成位置映射也能过 —— 那是假绿（译文静默错位是最怕的一种错）
                matched.reversed().joinToString("\n") { (n, text) -> "[$n] T:$text" }
            }
            callback(TranslationResult.Success(body))
        }

        override fun cancel() {}

        companion object {
            private val LINE = Regex("""^\[(\d+)]\s*(.*)$""")
        }
    }

    private fun translatorFor(
        fake: FakeTranslator,
        splitVersion: Int = SPLIT_VERSION,
    ) = NovelChapterTranslator(dao, NovelTranslationEngine(fake), splitVersion)

    // ===== 图片段（epub 插图：一张图 = 一个段 = 一次 OCR + 一次翻译请求）=====

    /** 含一张插图的段落表：段 1 是图。 */
    private val withImage = listOf(
        NovelParagraph(0, NovelParagraphType.TEXT, "第一段原文"),
        NovelParagraph(1, NovelParagraphType.IMAGE, "📷 [图片]"),
        NovelParagraph(2, NovelParagraphType.TEXT, "第二段原文"),
    )

    private fun payloadOf(blocks: Int = 2) = NovelImageTranslation(
        sourceLang = "ja",
        targetLang = "zh",
        ocrEngine = "ppocrv6",
        blocks = (0 until blocks).map {
            NovelImageTextBlock(0, it * 50, 100, it * 50 + 40, "原文$it", "译文$it", vertical = false)
        },
    )

    /**
     * 开着「同时翻译图片」时：**图片段走宿主（OCR+翻译）并带载荷落库**，
     * 而文本段依旧走文本引擎 —— 两条路各写各的字段。
     */
    @Test
    fun `图片段走宿主并落载荷`() = runBlocking {
        var hostCalls = 0
        val fake = FakeTranslator()
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            imageHost = {
                NovelImageHost(
                    fileOf = { _, _, _ -> java.io.File("/tmp/1/cover.png") },
                    translate = { _, _, _ ->
                        hostCalls++
                        NovelImageOutcome(payloadOf(), null)
                    },
                )
            },
        )

        val result = t.translateBatch(book, 0, withImage, listOf(0, 1), "ja", "zh", "fake")

        assertEquals("图片段也要算进结果（进度/「已翻」判据吃它）", setOf(0, 1), result.translations.keys)
        assertEquals(1, hostCalls)
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION).associateBy { it.paraIndex }
        val imageRow = rows[1]!!
        assertEquals(NovelParagraphTranslation.STATE_SUCCESS, imageRow.state)
        assertEquals("原文0\n原文1", imageRow.sourceText)
        assertEquals("译文0\n译文1", imageRow.translatedText)
        val decoded = NovelImagePayloadCodec.decode(imageRow.imagePayload)!!
        assertEquals(2, decoded.blocks.size)
        // 载荷要能被读回（三态切换/重新渲染全靠它，不该重跑 OCR）
        assertEquals(1, t.loadImageTranslations(book, 0).keys.single())
        // 文本段那条路没被图片带偏
        assertEquals("T:第一段原文", rows[0]!!.translatedText)
    }

    /** 关着开关（没有宿主）= **老行为**：图片段一个都不碰，也不写库。 */
    @Test
    fun `没有图片宿主时图片段照旧被跳过`() = runBlocking {
        val fake = FakeTranslator()
        val t = translatorFor(fake)
        val result = t.translateBatch(book, 0, withImage, listOf(0, 1), "ja", "zh", "fake")
        assertEquals(setOf(0), result.translations.keys)
        assertEquals("只有文本段那一次请求（图片段没被送出去）", 1, fake.calls)
        // ⚠️ 分母会把本章**所有**可翻译段建成 IDLE 行（段 0 与 2），所以这里只断言"图片段没有行"
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertTrue("图片段不该有行", rows.none { it.paraIndex == 1 })
    }

    /** 图片翻译失败（OCR 没认到字 / 引擎忙）：**标失败带原始原因**，不能静默。 */
    @Test
    fun `图片翻译失败会写失败原因`() = runBlocking {
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(FakeTranslator()), SPLIT_VERSION,
            imageHost = {
                NovelImageHost(
                    fileOf = { _, _, _ -> java.io.File("/tmp/1/cover.png") },
                    translate = { _, _, _ -> NovelImageOutcome(null, "这张图里没识别到文字") },
                )
            },
        )
        val result = t.translateBatch(book, 0, withImage, listOf(1), "ja", "zh", "fake")
        assertTrue("失败就没有译文", result.isEmpty)
        val row = dao.forChapter(1, "5000", 0, SPLIT_VERSION).single { it.paraIndex == 1 }
        assertEquals(NovelParagraphTranslation.STATE_FAILED, row.state)
        assertEquals("这张图里没识别到文字", row.failCode)
    }

    // ===== 批内拆请求 + 先完成先显示（用户口径 2026-10-04）=====

    /**
     * **文本，图片，文本 → 三次请求**，且**顺序就是文档顺序**。
     *
     * 用户口径：「例如一个批次：文本，图片，文本，三个段落全部变黄，但是要分三次 api 请求」。
     * ⚠️ 三次请求 ≠ 三个批：批仍是进度/高亮/配额的单位（这一条由 `NovelBatchPlannerTest` 与
     * 队列的并发测试分别钉着）。
     */
    @Test
    fun `文图文一批发三次请求`() = runBlocking {
        var hostCalls = 0
        val fake = FakeTranslator()
        val settled = mutableListOf<List<Int>>()
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            imageHost = {
                NovelImageHost(
                    fileOf = { _, _, _ -> java.io.File("/tmp/1/cover.png") },
                    translate = { _, _, _ -> hostCalls++; NovelImageOutcome(payloadOf(), null) },
                )
            },
            onUnitSettled = { _, paras -> settled += paras },
        )

        val r = t.translateBatch(book, 0, withImage, listOf(0, 1, 2), "ja", "zh", "fake")

        assertEquals("两条文本段各自一次请求（被插图隔开，不能合并）", 2, fake.calls)
        assertEquals("插图一次 OCR", 1, hostCalls)
        assertEquals(setOf(0, 1, 2), r.translations.keys)
        // **先完成先显示**：落库顺序 = 请求顺序 = 文本0 → 图 → 文本2
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), settled)
    }

    /**
     * **文本，文本，图片 → 两次请求**（前两段合并成一次）。
     *
     * 用户口径：「如果是文本，文本，图片的批次，则前两个作为一次请求，图片识别的结果单独一个 api 请求」。
     */
    @Test
    fun `文文图一批发两次请求`() = runBlocking {
        var hostCalls = 0
        val fake = FakeTranslator()
        val settled = mutableListOf<List<Int>>()
        // 段 0/1 正文、段 2 插图（**按文档顺序**：文本，文本，图片）
        val batch = listOf(
            NovelParagraph(0, NovelParagraphType.TEXT, "第一段原文"),
            NovelParagraph(1, NovelParagraphType.TEXT, "第二段原文"),
            NovelParagraph(2, NovelParagraphType.IMAGE, "📷 [图片]"),
        )
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            imageHost = {
                NovelImageHost(
                    fileOf = { _, _, _ -> java.io.File("/tmp/1/cover.png") },
                    translate = { _, _, _ -> hostCalls++; NovelImageOutcome(payloadOf(), null) },
                )
            },
            onUnitSettled = { _, paras -> settled += paras },
        )

        t.translateBatch(book, 0, batch, listOf(0, 1, 2), "ja", "zh", "fake")

        assertEquals("两段文本合并成一次请求", 1, fake.calls)
        assertEquals("插图一次 OCR", 1, hostCalls)
        assertEquals("合并的那一次要把两段一起报上来", listOf(listOf(0, 1), listOf(2)), settled)
    }

    /**
     * **一张插图落库就报一次**（不必等这一批剩下的插图/文本跑完）。
     *
     * 用户口径：「应该是**先 ocr 完成返回的就先显示结果**，而不是非要等到整个批次都完成再一起显示」。
     * 三张图的批（每批 3 段）→ 三次上报，每次一段，顺序即 OCR 顺序。
     */
    @Test
    fun `三张插图逐张上报`() = runBlocking {
        val three = listOf(
            NovelParagraph(0, NovelParagraphType.IMAGE, "📷 [图片]"),
            NovelParagraph(1, NovelParagraphType.IMAGE, "📷 [图片]"),
            NovelParagraph(2, NovelParagraphType.IMAGE, "📷 [图片]"),
        )
        val settled = mutableListOf<List<Int>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(FakeTranslator()), SPLIT_VERSION,
            imageHost = {
                NovelImageHost(
                    fileOf = { _, _, _ -> java.io.File("/tmp/1/cover.png") },
                    translate = { _, _, _ -> NovelImageOutcome(payloadOf(), null) },
                )
            },
            onImageProgress = { _, i, n -> progress += i to n },
            onUnitSettled = { _, paras -> settled += paras },
        )

        val r = t.translateBatch(book, 0, three, listOf(0, 1, 2), "ja", "zh", "fake")

        assertEquals(setOf(0, 1, 2), r.translations.keys)
        assertEquals("逐张上报（先完成先显示）", listOf(listOf(0), listOf(1), listOf(2)), settled)
        assertEquals("提示系统按张计数，跑完清零", listOf(1 to 3, 2 to 3, 3 to 3, 0 to 3), progress)
    }

    /** 文本那半被超长预警取消时，同一批里的插图**照常翻**（不该被一起吞掉）。 */
    @Test
    fun `取消文本不影响同批插图`() = runBlocking {
        var hostCalls = 0
        val fake = FakeTranslator()
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(threshold = { 1 }, confirm = { _, _ -> false }),
            imageHost = {
                NovelImageHost(
                    fileOf = { _, _, _ -> java.io.File("/tmp/1/cover.png") },
                    translate = { _, _, _ -> hostCalls++; NovelImageOutcome(payloadOf(), null) },
                )
            },
        )

        val r = t.translateBatch(book, 0, withImage, listOf(0, 1, 2), "ja", "zh", "fake")

        assertEquals("文本一个请求都不发", 0, fake.calls)
        assertEquals("插图照翻", 1, hostCalls)
        assertEquals(setOf(1), r.translations.keys)
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertTrue("被取消的文本一个字的库都不写（可重来）", rows.none { it.paraIndex == 0 })
        assertEquals(
            "插图那一行是成功的",
            NovelParagraphTranslation.STATE_SUCCESS,
            rows.first { it.paraIndex == 1 }.state,
        )
    }

    @Before
    fun setUp() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(ctx, TranslationHistoryDatabase::class.java).build()
        dao = db.novelParagraphTranslationDao()
    }

    @After
    fun tearDown() = db.close()

    // ===== 正常路径 =====

    /** 一批一批翻完，每批都落库（「翻译本章」走的就是这条批路径）。 */
    @Test
    fun `逐批翻完并落库`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "fake")
        t.translateBatch(book, 0, paragraphs, listOf(2), "ja", "zh", "fake")

        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
            .filter { it.state == NovelParagraphTranslation.STATE_SUCCESS }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.novelKey == "5000" })
    }

    /** 过短段不参与翻译，但它的 index 必须被跳过而不是被占用。 */
    @Test
    fun `SKIP 段不送翻译且不占译文行`() = runBlocking {
        translatorFor(FakeTranslator()).translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(listOf(0, 2), rows.map { it.paraIndex })
    }

    /** 真实 paraIndex 必须原样发出去 —— 发成「位置」会让译文整体错位。 */
    @Test
    fun `译文按键位对应而不是按回文顺序`() = runBlocking {
        // 段号故意不连续：0 与 2
        translatorFor(FakeTranslator()).translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        val map = translatorFor(FakeTranslator()).loadTranslations(book, 0)
        assertEquals("T:第一段原文", map[0])
        assertEquals("T:第二段原文", map[2])
        assertTrue("段 1 是 SKIP，不该有译文", !map.containsKey(1))
    }

    // ===== 失败路径 =====

    /**
     * **分母**必须是「整章可翻译段数」，不是"这一批写了几行"。
     *
     * 回归（用户报的「某一章没翻完却显示已经全部翻译完成」）：章徽章/筛选/目录的判据是
     * `success >= total`，而 `total` 是 `COUNT(*)`。行是**按批惰性写的** —— 只翻一批时
     * `total == success`，于是刚点了「翻译本章」的一章立刻被标成「已翻译」。
     * 修法是在翻每一批之前先把整章的可翻译段落补齐成 IDLE 行（`ensureChapterRows`）。
     */
    @Test
    fun `翻一批后本章分母立刻是整章可翻译段数`() = runBlocking {
        val t = translatorFor(FakeTranslator())

        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "fake")

        val stat = t.chapterStats(book)[0]
        assertEquals("分母 = 段 0 + 段 2（段 1 是 SKIP，不算）", 2, stat?.total)
        assertEquals("只翻了一段", 1, stat?.success)
        val idle = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
            .filter { it.state == NovelParagraphTranslation.STATE_IDLE }
        assertEquals("没翻的那段是 IDLE（待翻），不是缺行", listOf(2), idle.map { it.paraIndex })
    }

    /**
     * 失败必须写 FAILED 且**译文为空**（不是空串成功）。
     * 空串一旦被当成成功，该段永远显示空白且再也不会被重试。
     */
    @Test
    fun `全部失败时标记 FAILED 且不写空译文`() = runBlocking {
        translatorFor(FakeTranslator(failAll = true))
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "x")
        val rows = dao.forChapter(1, "5000", 0, SPLIT_VERSION)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.state == NovelParagraphTranslation.STATE_FAILED })
        assertTrue(rows.all { it.translatedText.isEmpty() })
        assertTrue(rows.all { it.failCode != null })
    }

    /**
     * **失败原因必须带上原始报错**：用户要的不是"失败了"，是"为什么失败"
     * （`failCode` 会原样显示在面板章行的展开里）。
     */
    @Test
    fun `失败时 failCode 带出原始报错`() = runBlocking {
        val boom = object : NovelTextTranslator {
            override fun translate(
                prompt: String,
                sourceLang: String,
                targetLang: String,
                callback: (TranslationResult) -> Unit,
            ) {
                callback(TranslationResult.Error(java.io.IOException("HTTP 429 Too Many Requests")))
            }
        }
        val t = NovelChapterTranslator(dao, NovelTranslationEngine(boom), SPLIT_VERSION)

        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "boom")

        val row = dao.forChapter(1, "5000", 0, SPLIT_VERSION).first { it.paraIndex == 0 }
        assertEquals(NovelParagraphTranslation.STATE_FAILED, row.state)
        assertTrue("failCode 要含原始报错，实际=${row.failCode}", row.failCode.orEmpty().contains("HTTP 429"))
    }

    /** 整批被拒（内容审查等）时会降级为逐段重试，把其余段落救回来。 */
    @Test
    fun `整批失败会降级逐段重试一次`() = runBlocking {
        val fake = FirstCallFailsTranslator()
        val r = NovelChapterTranslator(dao, NovelTranslationEngine(fake), SPLIT_VERSION)
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "flaky")

        assertEquals("T:第一段原文", r.translations[0])
        assertEquals("T:第二段原文", r.translations[2])
        assertEquals("整批 1 次 + 逐段 2 次", 3, fake.calls)
    }

    /** 第一次调用失败、之后成功；回文带真实编号。 */
    private class FirstCallFailsTranslator : NovelTextTranslator {
        var calls = 0
        override val name: String get() = "flaky"

        override fun translate(
            prompt: String,
            sourceLang: String,
            targetLang: String,
            callback: (TranslationResult) -> Unit,
        ) {
            calls++
            if (calls == 1) {
                callback(TranslationResult.Error(Exception("batch rejected")))
                return
            }
            val matched = prompt.lineSequence()
                .mapNotNull { LINE.find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }
                .joinToString("\n") { (n, t) -> "[$n] T:$t" }
            callback(TranslationResult.Success(matched))
        }

        override fun cancel() {}

        private companion object {
            val LINE = Regex("""^\[(\d+)]\s*(.*)$""")
        }
    }

    // ===== 位置兜底 =====

    /** 模型完全丢掉编号时按位置兜底（条数一致才接受）。 */
    @Test
    fun `模型丢掉编号时按位置兜底`() = runBlocking {
        val r = translatorFor(FakeTranslator(dropNumbering = true))
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertEquals("T:第一段原文", r.translations[0])
        assertEquals("T:第二段原文", r.translations[2])
    }

    // ===== 读回与版本 =====

    @Test
    fun `splitVersion 不同的旧译文读不出来`() = runBlocking {
        translatorFor(FakeTranslator(), splitVersion = 1)
            .translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertTrue(translatorFor(FakeTranslator(), splitVersion = 2).loadTranslations(book, 0).isEmpty())
    }

    @Test
    fun `resetStale 清掉残留的翻译中状态`() = runBlocking {
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "5000", chapterIndex = 0, paraIndex = 0,
                sourceText = "x", state = NovelParagraphTranslation.STATE_TRANSLATING,
                splitVersion = SPLIT_VERSION,
            )
        )
        translatorFor(FakeTranslator()).resetStale(book)
        assertEquals(
            NovelParagraphTranslation.STATE_IDLE,
            dao.forChapter(1, "5000", 0, SPLIT_VERSION).single().state,
        )
    }

    @Test
    fun `章状态聚合给出成功数`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertEquals(2, t.chapterStats(book)[0]?.success)
        assertEquals(2, t.chapterStats(book)[0]?.total)
    }

    /**
     * 删除必须按 (id, key) **成对**：书籍 id 会被复用，只按 id 删会把复用同 id 的**新书**译文删掉。
     * （书架删书走的就是这条 SQL —— `NovelShelfFragment` 的清理路径。）
     */
    @Test
    fun `按指纹删且不误伤同 id 的另一本书`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "9999", chapterIndex = 0, paraIndex = 0,
                sourceText = "别的书", state = NovelParagraphTranslation.STATE_SUCCESS,
                splitVersion = SPLIT_VERSION,
            )
        )
        dao.deleteForNovelScoped(1, "5000")
        assertTrue(t.loadTranslations(book, 0).isEmpty())
        assertEquals("同 id 不同指纹的书不能被误删", 1, dao.countFor(1, "9999"))
    }

    /**
     * 进入阅读器时顺手清掉**旧分段版本**的行（`resetStale` 里一起做）。
     *
     * 分段规则升级后旧行的段号映射已经错了，留着既会显示错位的译文、又会占着主键。
     */
    @Test
    fun `resetStale 清掉非当前分段版本的旧行`() = runBlocking {
        dao.upsert(
            NovelParagraphTranslation(
                novelId = 1, novelKey = "5000", chapterIndex = 0, paraIndex = 0,
                sourceText = "旧版本的段", state = NovelParagraphTranslation.STATE_SUCCESS,
                translatedText = "旧译文", splitVersion = SPLIT_VERSION - 1,
            )
        )

        translatorFor(FakeTranslator()).resetStale(book)

        assertTrue(
            "旧版本的行必须被清掉",
            dao.forChapter(1, "5000", 0, SPLIT_VERSION - 1).isEmpty(),
        )
    }

    /**
     * **清除选中段的译文**（长按多选 → 右下角清除）：
     * 读回立刻为空（阅读器随即回落原文），没被选的段不受影响，**行数不变**（那是本章分母）。
     */
    @Test
    fun `清除选中段的译文`() = runBlocking {
        val t = translatorFor(FakeTranslator())
        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")
        assertEquals(2, t.loadTranslations(book, 0).size)

        t.clearParagraphs(book, 0, listOf(0))

        val left = t.loadTranslations(book, 0)
        assertTrue("被清的段不能再读到译文", !left.containsKey(0))
        assertEquals("没被选的段要留着", "T:第二段原文", left[2])
        val stat = t.chapterStats(book)[0]
        assertEquals("分母（库里行数）不能变少 —— 清除是重置不是删行", 2, stat?.total)
        assertEquals("成功数要少一个", 1, stat?.success)
    }

    /** 清除也要清掉失败原因（否则章行还挂着旧报错）。 */
    @Test
    fun `清除会一并清掉失败原因`() = runBlocking {
        val boom = object : NovelTextTranslator {
            override fun translate(
                prompt: String,
                sourceLang: String,
                targetLang: String,
                callback: (TranslationResult) -> Unit,
            ) {
                callback(TranslationResult.Error(java.io.IOException("HTTP 429 Too Many Requests")))
            }
        }
        val t = NovelChapterTranslator(dao, NovelTranslationEngine(boom), SPLIT_VERSION)
        t.translateBatch(book, 0, paragraphs, listOf(0), "ja", "zh", "boom")
        assertTrue("前提：这一段确实失败了", t.failuresOf(book).isNotEmpty())

        t.clearParagraphs(book, 0, listOf(0))

        assertTrue("清除后失败明细里不该还有它", t.failuresOf(book).isEmpty())
    }

    /** 整章只有不可翻译段（SKIP）→ 一个请求都不发，也不写任何行。 */
    @Test
    fun `没有可翻译段时不发请求`() = runBlocking {
        val fake = FakeTranslator()
        val only = listOf(NovelParagraph(0, NovelParagraphType.SKIP, "……"))

        val r = translatorFor(fake).translateBatch(book, 0, only, listOf(0), "ja", "zh", "fake")

        assertEquals(0, fake.calls)
        assertTrue(r.isEmpty)
        assertTrue(dao.forChapter(1, "5000", 0, SPLIT_VERSION).isEmpty())
    }

    // ===== 单批超长预警（阈值判据收在 NovelChapterTranslator.translateBatch 一处） =====

    /**
     * 超过阈值 → 问用户；用户**取消** → 这一批不发请求、不写库
     * （段保持"没翻过"，不是 FAILED：用户改大阈值或直接重来即可）。
     */
    @Test
    fun `超长批用户取消时不发请求也不写库`() = runBlocking {
        val fake = FakeTranslator()
        var asked: Pair<Int, Int>? = null
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(
                threshold = { 3 },
                confirm = { est, th -> asked = est to th; false },
            ),
        )

        val r = t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")

        assertTrue(r.isEmpty)
        assertEquals("问过一次", 1, (if (asked != null) 1 else 0))
        assertEquals("阈值原样传下去", 3, asked?.second)
        assertTrue("估算必须真的超过阈值（否则这条测试没测到）", (asked?.first ?: 0) > 3)
        assertEquals("不能发请求", 0, fake.calls)
        assertTrue("不能写任何行", dao.forChapter(1, "5000", 0, SPLIT_VERSION).isEmpty())
    }

    /** 用户选「继续」→ 照常翻译并落库。 */
    @Test
    fun `超长批用户继续时照常翻译`() = runBlocking {
        val fake = FakeTranslator()
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(fake), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(threshold = { 1 }, confirm = { _, _ -> true }),
        )

        val r = t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")

        assertEquals(mapOf(0 to "T:第一段原文", 2 to "T:第二段原文"), r.translations)
        assertEquals(2, t.chapterStats(book)[0]?.success)
    }

    /** 没超阈值（或没接预警）时**一次都不问**，老路径零回归。 */
    @Test
    fun `没超阈值时不打扰用户`() = runBlocking {
        var asked = 0
        val t = NovelChapterTranslator(
            dao, NovelTranslationEngine(FakeTranslator()), SPLIT_VERSION,
            warnGate = NovelBatchWarnGate(threshold = { NovelBatchWarning.DEFAULT_THRESHOLD }) { _, _ ->
                asked++
                true
            },
        )

        t.translateBatch(book, 0, paragraphs, listOf(0, 2), "ja", "zh", "fake")

        assertEquals(0, asked)
    }

    private companion object {
        const val SPLIT_VERSION = 1
    }
}
