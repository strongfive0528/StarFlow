package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * 手写 PDF 写入器（[PdfJpegWriter]）的结构校验。
 *
 * 为什么值得单独测：PDF 是**靠字节偏移定位对象**的格式 —— 头部、对象体、xref 表、trailer
 * 四处只要错一个字节，很多阅读器就直接判"文件已损坏"，而我们在设备上只能看到"打不开"。
 * 这里把写出来的字节**当成一个 PDF 解析一遍**：xref 里每个偏移都必须正落在 `N 0 obj` 上、
 * `startxref` 必须正指向 `xref` 关键字、每个图像流的 `/Length` 必须等于真实 JPEG 字节数。
 *
 * （真正"能不能被系统渲染"由真机验证兜底：导入我们自己导出的 PDF 即可 ——
 *  `PdfRenderer` 打不开就说明结构错了。）
 */
class PdfJpegWriterTest {

    private companion object {
        val ASCII = StandardCharsets.US_ASCII

        /** 造一段"像 JPEG"的字节：SOI + 填充 + EOI（写入器不解析内容，只负责原样嵌入）。 */
        fun fakeJpeg(seed: Int, size: Int): ByteArray {
            val out = ByteArray(size)
            out[0] = 0xFF.toByte()
            out[1] = 0xD8.toByte()
            out[size - 2] = 0xFF.toByte()
            out[size - 1] = 0xD9.toByte()
            for (i in 2 until size - 2) out[i] = ((seed + i) and 0xFF).toByte()
            return out
        }
    }

    private fun ByteArray.text(from: Int, length: Int): String =
        String(this, from, length, ASCII)

    private fun ByteArray.indexOfAscii(needle: String, from: Int = 0): Int {
        val bytes = needle.toByteArray(ASCII)
        if (bytes.isEmpty() || size < bytes.size) return -1
        outer@ for (start in from..(size - bytes.size)) {
            for (i in bytes.indices) if (this[start + i] != bytes[i]) continue@outer
            return start
        }
        return -1
    }

    /** 行尾（`\n`）在 [from] 之后的位置；找不到返回 size。 */
    private fun ByteArray.lineEnd(from: Int): Int {
        for (i in from until size) if (this[i] == '\n'.code.toByte()) return i
        return size
    }

    private fun build(vararg pages: Triple<ByteArray, Int, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = PdfJpegWriter(out)
        pages.forEach { (jpeg, w, h) ->
            writer.addPage(jpeg, w, h, w * 72f / 300f, h * 72f / 300f)
        }
        writer.finish()
        return out.toByteArray()
    }

    @Test
    fun `headerTrailerAndStartxrefAreWellFormed`() {
        val bytes = build(Triple(fakeJpeg(1, 2048), 1750, 2479))
        assertEquals("%PDF-1.4", bytes.text(0, 8))
        val tail = bytes.text(bytes.size - 6, 6)
        assertEquals("%%EOF\n", tail)

        // startxref 必须正指向 "xref" 关键字（多数阅读器从这里开始读交叉引用表）
        val sx = bytes.indexOfAscii("startxref\n")
        assertTrue("找不到 startxref", sx > 0)
        val end = bytes.lineEnd(sx + 10)
        val offset = bytes.text(sx + 10, end - (sx + 10)).trim().toInt()
        assertEquals("xref\n", bytes.text(offset, 5))
    }

    @Test
    fun `everyXrefEntryPointsAtItsObjectHeader`() {
        val bytes = build(
            Triple(fakeJpeg(2, 1024), 1750, 2479),
            Triple(fakeJpeg(3, 1024), 2150, 3033),
        )
        val xref = bytes.indexOfAscii("xref\n")
        assertTrue(xref > 0)
        // 对象编号 1..lastObject：1=Catalog、2=Pages，之后每页 3 个（Page / Contents / Image）
        val lastObject = 2 + 3 * 2
        val headerEnd = bytes.lineEnd(xref + 5)
        val header = bytes.text(xref + 5, headerEnd - (xref + 5) + 1)   // "0 9\n"
        assertEquals("0 ${lastObject + 1}\n", header)
        val tableStart = headerEnd + 1
        // 第 0 条固定是空对象
        assertTrue(
            "0 号条目必须是空闲对象",
            bytes.text(tableStart, 20).startsWith("0000000000 65535 f"),
        )
        for (obj in 1..lastObject) {
            val entry = bytes.text(tableStart + obj * 20, 20)
            assertEquals("条目必须是 20 字节（含行尾）: '$entry'", 20, entry.length)
            assertTrue("第 $obj 条必须是使用中(n): '$entry'", entry.endsWith("n \n"))
            val offset = entry.substring(0, 10).toInt()
            assertEquals(
                "对象 $obj 的 xref 偏移必须正落在对象头",
                "$obj 0 obj\n",
                bytes.text(offset, "$obj 0 obj\n".length),
            )
        }
    }

    @Test
    fun `pageCountKidsAndMediaBoxMatchWhatWasAdded`() {
        val bytes = build(
            Triple(fakeJpeg(4, 512), 1750, 2479),
            Triple(fakeJpeg(5, 512), 2150, 3033),
        )
        val text = String(bytes, ASCII)
        assertTrue("/Count 2", text.contains("/Count 2"))
        // 页对象号 = 3, 6, 9…（每页 3 个对象，1=Catalog、2=Pages）
        assertTrue("Kids 必须列出全部页对象", text.contains("/Kids [3 0 R 6 0 R ]"))
        // 点尺寸 = 像素 / 300dpi * 72（导出页的"纸"与原页面同尺寸）
        assertTrue(text.contains("/MediaBox [0 0 420.00 594.96]"))
        assertTrue(text.contains("/MediaBox [0 0 516.00 727.92]"))
    }

    @Test
    fun `jpegPayloadIsEmbeddedByteForByteWithACorrectLength`() {
        val jpeg1 = fakeJpeg(6, 3000)
        val jpeg2 = fakeJpeg(7, 5000)
        val bytes = build(
            Triple(jpeg1, 1000, 1400),
            Triple(jpeg2, 1000, 1400),
        )
        // 原样嵌入（DCTDecode）：不重新编码、不转义 —— 体积可控的前提就是这个
        assertTrue("第一页 JPEG 未原样嵌入", bytes.indexOfAsciiSubArray(jpeg1) > 0)
        assertTrue("第二页 JPEG 未原样嵌入", bytes.indexOfAsciiSubArray(jpeg2) > 0)
        // /Length 必须等于真实字节数（写错 → 阅读器读多/读少 → 图像花掉或整页打不开）
        val text = String(bytes, ASCII)
        assertTrue(text.contains("/Filter /DCTDecode /Length ${jpeg1.size} >>"))
        assertTrue(text.contains("/Filter /DCTDecode /Length ${jpeg2.size} >>"))
        assertTrue(text.contains("/Width 1000 /Height 1400"))
    }

    @Test
    fun `objectNumbersNeverOverlapBetweenPages`() {
        val bytes = build(
            Triple(fakeJpeg(8, 128), 100, 100),
            Triple(fakeJpeg(9, 128), 100, 100),
            Triple(fakeJpeg(10, 128), 100, 100),
        )
        val text = String(bytes, ASCII)
        // 3 页 → 对象 1..11：Page/Contents/Image 依次 3/4/5、6/7/8、9/10/11
        listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11).forEach { obj ->
            assertTrue("缺少对象 $obj", text.contains("$obj 0 obj"))
        }
        assertTrue(text.contains("/Kids [3 0 R 6 0 R 9 0 R ]"))
    }

    @Test
    fun `writingAfterFinishIsRejectedInsteadOfSilentlyCorrupting`() {
        val out = ByteArrayOutputStream()
        val writer = PdfJpegWriter(out)
        writer.addPage(fakeJpeg(11, 64), 100, 100, 24f, 24f)
        writer.finish()
        try {
            writer.addPage(fakeJpeg(12, 64), 100, 100, 24f, 24f)
            fail("finish() 之后还能加页 = 写出的 xref 与内容不一致")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        }
        try {
            writer.finish()
            fail("finish() 重复调用会写第二份 xref/trailer")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun `emptyJpegIsRejected`() {
        val writer = PdfJpegWriter(ByteArrayOutputStream())
        try {
            writer.addPage(ByteArray(0), 100, 100, 24f, 24f)
            fail("空 JPEG 写进 PDF = 阅读器渲染一页空白/报错")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    // ===== 审查补充：0 页 / Size / 内容流长度 / 目录链接 / 退化页尺寸 =====

    /**
     * **0 页也要是合法 PDF**（只调 `finish()`）。
     *
     * 导出路径会在"一页都没翻"时提前返回，但写入器本身不该因为 `pageCount == 0` 就算错对象数 ——
     * `/Count 0`、`/Kids []`、xref 条目数 = 3（0 号空位 + Catalog + Pages）都必须成立。
     */
    @Test
    fun `zeroPageDocumentIsStillWellFormed`() {
        val out = ByteArrayOutputStream()
        PdfJpegWriter(out).finish()
        val bytes = out.toByteArray()
        val text = String(bytes, ASCII)

        assertEquals("%PDF-1.4", bytes.text(0, 8))
        assertTrue("0 页也要有 Pages 对象", text.contains("/Type /Pages /Kids [] /Count 0"))
        assertTrue("目录对象必须存在", text.contains("/Type /Catalog /Pages 2 0 R"))
        // 条目数 = 3 + 3×0 = 3 → "0 3"；/Size 用同一个数字
        assertTrue("xref 子段头必须是 `0 3`", text.contains("xref\n0 3\n"))
        assertTrue("/Size 必须与 xref 条目数一致", text.contains("/Size 3 /Root 1 0 R"))

        val xref = bytes.indexOfAscii("xref\n")
        val tableStart = bytes.lineEnd(xref + 5) + 1
        for (obj in 1..2) {
            val entry = bytes.text(tableStart + obj * 20, 20)
            assertEquals("条目必须 20 字节", 20, entry.length)
            val offset = entry.substring(0, 10).toInt()
            assertEquals("$obj 0 obj\n", bytes.text(offset, "$obj 0 obj\n".length))
        }
    }

    /**
     * `/Size` **必须等于 xref 条目数**（= 最高对象号 + 1 = 3 + 3×页数）。
     *
     * 只断言 `xref` 子段头是不够的：两处是同一变量派生的，但一旦有人把它们拆开写就会分叉，
     * 而多数阅读器先看 trailer 的 `/Size` —— 对不上直接判损坏。
     */
    @Test
    fun `sizeMatchesTheXrefEntryCountForEveryPageCount`() {
        listOf(1, 2, 5).forEach { pages ->
            val out = ByteArrayOutputStream()
            val writer = PdfJpegWriter(out)
            repeat(pages) { writer.addPage(fakeJpeg(it, 256), 100, 100, 24f, 24f) }
            writer.finish()
            val text = String(out.toByteArray(), ASCII)
            val entries = 3 + 3 * pages
            assertTrue("$pages 页：xref 子段头必须是 0 $entries", text.contains("xref\n0 $entries\n"))
            assertTrue("$pages 页：/Size 必须是 $entries", text.contains("/Size $entries /Root 1 0 R"))
            assertTrue("$pages 页：/Count 必须是 $pages", text.contains("/Count $pages >>"))
        }
    }

    /**
     * **内容流的 `/Length` 必须等于真实字节数**。
     *
     * 之前只核了图像流的 `/Length`：内容流写错长度，阅读器会把后面那半截 `endstream/endobj`
     * 当成页面绘制指令读进去 → 图像位置胡乱偏移甚至整页不显示。
     */
    @Test
    fun `contentStreamLengthMatchesItsBytes`() {
        val out = ByteArrayOutputStream()
        val writer = PdfJpegWriter(out)
        writer.addPage(fakeJpeg(21, 512), 1750, 2479, 420f, 594.96f)
        writer.finish()
        val bytes = out.toByteArray()
        val text = String(bytes, ASCII)

        val declared = Regex("/Contents (\\d+) 0 R >>").find(text)
        assertNotNull("Page 字典里必须有 /Contents", declared)
        // 单页文档里对象编号固定：3=Page、4=Contents、5=Image
        assertEquals("4", declared!!.groupValues[1])
        // 内容流的字典形如 "<< /Length NN >>\nstream\n…endstream"
        val contentDict = Regex("<< /Length (\\d+) >>\\nstream\\n(.*?)endstream", RegexOption.DOT_MATCHES_ALL)
            .find(text)
        assertNotNull("找不到内容流", contentDict)
        val declaredLength = contentDict!!.groupValues[1].toInt()
        // 写 stream 时数据前是 "stream\n"、数据后**直接**接 "endstream"（没有额外换行），
        // 所以这段正文必须与 /Length 严格相等
        val actualBytes = contentDict.groupValues[2].toByteArray(ASCII).size
        assertEquals("内容流 /Length 必须等于实际字节数", actualBytes, declaredLength)
        assertTrue("内容流要把图像铺满整页", contentDict.groupValues[2].contains("/Im0 Do"))
    }

    /** trailer 必须真的指向目录对象（`/Root 1 0 R` 且 1 号对象是 `/Type /Catalog`）。 */
    @Test
    fun `trailerLinksToTheCatalogObject`() {
        val text = String(build(Triple(fakeJpeg(22, 128), 100, 100)), ASCII)
        assertTrue("trailer 必须指向 /Root 1 0 R", text.contains("/Size 6 /Root 1 0 R"))
        // 1 号对象紧随头部（二进制标记之后）
        assertTrue("1 号对象必须是 Catalog", text.contains("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>"))
    }

    /** 退化的页尺寸（0 / 负数 / NaN）不能写出 `MediaBox [0 0 0.00 0.00]` 这种坏页。 */
    @Test
    fun `degeneratePageSizesAreClampedInsteadOfWrittenAsZero`() {
        listOf(0f, -10f, Float.NaN, Float.POSITIVE_INFINITY).forEach { bad ->
            val out = ByteArrayOutputStream()
            val writer = PdfJpegWriter(out)
            writer.addPage(fakeJpeg(23, 128), 100, 100, bad, bad)
            writer.finish()
            val text = String(out.toByteArray(), ASCII)
            assertTrue("非法尺寸 $bad 必须被夹到 1.00", text.contains("/MediaBox [0 0 1.00 1.00]"))
            assertFalse("不能出现 0.00 的纸", text.contains("/MediaBox [0 0 0.00"))
        }
    }

    /** 子数组查找（JPEG 是二进制，不能用 `String.contains`）。 */
    private fun ByteArray.indexOfAsciiSubArray(needle: ByteArray, from: Int = 0): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (start in from..(size - needle.size)) {
            for (i in needle.indices) if (this[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }
}
