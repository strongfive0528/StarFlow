package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * **导出 PDF 的页数/计数语义**（行为测试，不是源码 grep）。
 *
 * 为什么值得单独测：这三条口径我改过两次，而且都错了 ——
 * ① 双语包"快照里有译文、渲染却给了 null"（用户中途删了译文）时**两个计数都不涨**，
 *    结果只剩原图页却报"0 页跳过"，用户以为整本都翻了；
 * ② 译文 PDF 把未译页**回填原图**（页数完整）却报"N 页跳过"，用户以为 PDF 缺页；
 * ③ 写到一半 IOException（磁盘满）时把**没有 xref 的截断 PDF** 当成功后交付。
 * 这三条的共同点是"看起来一切正常"，所以只能靠行为断言钉住。
 *
 * 编码器被替换成假实现（`encodeJpeg` 测试缝）：这样测试不依赖 Skia/真 JPEG，
 * 只验证**页数与计数**；真实编码 + 系统 `PdfRenderer` 能否打开由真机探针覆盖
 * （`tools/verify-pdf-import.ps1` 里的 `PdfReadPathProbe`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderPdfExportAccountingTest {

    private companion object {
        /** 假 JPEG：非空即可（PdfJpegWriter 只负责原样嵌入） */
        fun fakeJpeg(): ByteArray = ByteArray(64) { 0x11 }
    }

    @Before
    fun setUp() {
        // 导出每页都会 withContext(Dispatchers.Main) 上报进度；让它内联执行。
        // ⚠️ 不这么做就是**死锁**：Robolectric 的主循环只有在测试线程空闲时才跑，
        //    而测试线程正卡在 runBlocking 里等它 —— 表现是 Gradle 测试卡住不结束（我踩过一次）。
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun bmp(w: Int = 100, h: Int = 140): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.eraseColor(0xFF808080.toInt()) }

    /** 从写出的字节里读回 `/Count N`（本写入器的结构固定，可直接正则）。 */
    private fun pdfPageCount(bytes: ByteArray): Int {
        val text = String(bytes, StandardCharsets.US_ASCII)
        val match = Regex("/Type /Pages /Kids \\[[^]]*] /Count (\\d+)").find(text) ?: return -1
        return match.groupValues[1].toInt()
    }

    private fun export(
        pageCount: Int,
        translatedPages: Set<Int>,
        both: Boolean,
        readOriginal: (Int) -> Bitmap? = { bmp() },
        renderTranslated: (Int) -> Bitmap? = { if (it in translatedPages) bmp() else null },
        out: OutputStream = ByteArrayOutputStream(),
        jpegSize: Int = 64,
        /** true = 这个用例本来就期望失败（例如模拟磁盘满），别把 Failed 当成"意外"报出来 */
        allowFailure: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<ExportOutcome, ByteArray> = runBlocking {
        val outcome = ReaderPdfExport.export(
            pageCount = pageCount,
            translatedPages = translatedPages,
            readOriginal = readOriginal,
            renderTranslated = renderTranslated,
            both = both,
            out = out,
            onProgress = onProgress,
            encodeJpeg = { ByteArray(jpegSize) { 0x11 } },
        )
        // ⚠️ 括号必须写全：`a to x ?: y` 会解析成 `(a to x) ?: y`（infix 比 elvis 绑得紧），
        //    推断出的类型会退化成 Serializable，编译期就报"expected Pair"。
        val bytes = (out as? ByteArrayOutputStream)?.toByteArray() ?: ByteArray(0)
        if (!allowFailure && outcome is ExportOutcome.Failed) {
            // 导出内部把异常吞成 Failed（生产里必须这样，否则进程崩）——测试里要把它捞出来看，
            // 否则只能对着"Failed"猜。LogCollector 是进程级单例（其他测试也会写），所以按 tag 过滤。
            val mine = LogCollector.getAllLogs().filter { it.tag == "ReaderPdfExport" }.takeLast(3)
            val tail = LogCollector.getAllLogs().takeLast(5)
            throw AssertionError(
                "导出意外失败。\nReaderPdfExport 日志：" +
                    mine.joinToString("\n") { it.toString() } +
                    "\n最近日志（可能被其他测试污染）：" +
                    tail.joinToString("\n") { it.toString() },
            )
        }
        outcome to bytes
    }

    /** 译文 PDF：**全页**输出，未译页回填原图 → 页数 == 总页数，两个计数分开。 */
    @Test
    fun `translatedPdfKeepsEveryPageAndCountsUntranslatedSeparately`() {
        val (outcome, bytes) = export(pageCount = 3, translatedPages = setOf(0), both = false)

        assertEquals(
            "3 页都要在（未译页回填原图）；只有「没译文」这 2 页，没有一页被真跳过",
            ExportOutcome.Done(written = 3, skipped = 0, untranslated = 2),
            outcome,
        )
        assertEquals("PDF 页数必须等于原书页数", 3, pdfPageCount(bytes))
    }

    /** 双语 PDF：已译页「原图页 + 译图页」，未译页只出原图页 → 页数 = N + 已译页数。 */
    @Test
    fun `bilingualPdfPairsOriginalWithTranslatedOnlyWhereTranslated`() {
        val (outcome, bytes) = export(pageCount = 3, translatedPages = setOf(0, 2), both = true)

        // 已译 2 页各出 2 张（4）+ 未译 1 页只出原图（1）= 5；未译那页记 untranslated
        assertEquals(ExportOutcome.Done(written = 5, skipped = 0, untranslated = 1), outcome)
        assertEquals("页数 = N + 已译页数", 5, pdfPageCount(bytes))
    }

    /**
     * **导出途中译文被删**（快照里还有、载荷已空）：双语包只剩原图页，但必须如实计入 untranslated，
     * 而不是"两个计数都是 0"（那会让用户以为整本都翻了）。
     */
    @Test
    fun `translationDeletedMidExportIsCountedInsteadOfSilentlyDroppingThePage`() {
        val (outcome, bytes) = export(
            pageCount = 2,
            translatedPages = setOf(0, 1),
            both = true,
            renderTranslated = { null },        // 快照说应该有，实际取不到
        )

        // 每页只剩原图那一张：写进 2 页、跳过 0 页、**2 页没有译文**
        assertEquals(ExportOutcome.Done(written = 2, skipped = 0, untranslated = 2), outcome)
        assertEquals(2, pdfPageCount(bytes))
    }

    /** 整页连原图都取不到 → 这才是真·跳过（PDF 会少页），必须计入 skipped。 */
    @Test
    fun `aPageThatCannotBeReadAtAllCountsAsSkipped`() {
        val (outcome, _) = export(
            pageCount = 3,
            translatedPages = setOf(0),
            both = false,
            readOriginal = { page -> if (page == 1) null else bmp() },   // 第 2 页读不出原图
        )

        assertEquals(ExportOutcome.Done(written = 2, skipped = 1, untranslated = 1), outcome)
    }

    /** 一页都没翻过 → 不出包（与 zip 导出同一口径），且不写任何字节。 */
    @Test
    fun `noTranslatedPagesYieldsEmptyAndWritesNothing`() {
        val (outcome, bytes) = export(pageCount = 3, translatedPages = emptySet(), both = false)
        assertEquals(ExportOutcome.Empty, outcome)
        assertEquals(0, bytes.size)
    }

    /** 页数为 0 → Empty（不是"失败的下载"）。 */
    @Test
    fun `zeroPageBookYieldsEmpty`() {
        val (outcome, bytes) = export(pageCount = 0, translatedPages = setOf(0), both = false)
        assertEquals(ExportOutcome.Empty, outcome)
        assertEquals(0, bytes.size)
    }

    /**
     * **写到一半失败绝不能报成功**：截断的 PDF 没有 xref/trailer，阅读器根本打不开
     * （zip 截断还能解开一部分，PDF 不能）。所以"没写完 trailer"一律 Failed ——
     * 哪怕**已经有几页写进去了**（那正是"部分成功"最容易骗过人的形态）。
     */
    @Test
    fun `aMidWriteFailureIsReportedAsFailedNotPartialSuccess`() {
        // 前 4KB 正常，之后像磁盘满一样抛
        val failing = object : OutputStream() {
            var received = 0
            private fun guard(len: Int) {
                received += len
                if (received > 4096) throw IOException("disk full")
            }
            override fun write(b: Int) { guard(1) }
            override fun write(b: ByteArray, off: Int, len: Int) { guard(len) }
        }

        val (outcome, _) = export(
            pageCount = 5,
            translatedPages = setOf(0, 1, 2, 3, 4),
            both = true,
            out = failing,
            // 每页图片给足 4KB：总输出远大于阈值，保证**写到一半**才炸（否则测的是"一条都没写成"）
            jpegSize = 4096,
            allowFailure = true,
        )

        assertTrue("必须先真的写进去一部分，这条断言才有意义（实际写入 ${failing.received}）",
            failing.received > 4096)
        assertEquals("已有页写进去也不算成功：没写到 trailer 就是打不开的 PDF",
            ExportOutcome.Failed, outcome)
    }

    /** 进度按**原书页**上报（双语下一页写两张，但进度只走一遍）。 */
    @Test
    fun `progressRunsOverSourcePagesOnce`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        export(
            pageCount = 3,
            translatedPages = setOf(0, 1, 2),
            both = true,
            onProgress = { done, total -> seen += done to total },
        )
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), seen)
    }

    /** 每页的"纸"尺寸按 300dpi 反推（= 原页面尺寸），确保导出 PDF 尺寸不随字节数漂移。 */
    @Test
    fun `pageMediaBoxMatchesThePixelSizeAt300dpi`() {
        val (_, bytes) = export(pageCount = 1, translatedPages = setOf(0), both = false)
        val text = String(bytes, StandardCharsets.US_ASCII)
        // 100x140 px @300dpi → 24.00 x 33.60 pt
        assertTrue("MediaBox 必须按 RASTER_DPI 反推：$text", text.contains("/MediaBox [0 0 24.00 33.60]"))
    }
}
