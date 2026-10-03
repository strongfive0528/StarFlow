package com.moe.starflow.mangaimport.reader

import java.io.OutputStream
import java.util.Locale

/**
 * 极简 PDF 写入器：把「每页一张 JPEG」写成合法的 PDF（每页一个 `DCTDecode` 图像 XObject）。
 *
 * ## 为什么不用 `android.graphics.pdf.PdfDocument`
 * Skia 的 PDF 后端对**整页位图**走无损压缩（FlateDecode）：一张 300DPI 的页图 ≈17~26MB 原始像素，
 * 压完仍有十几 MB —— 一本 300 页的译文 PDF 能到几个 GB，双语（每页两张）更夸张。
 * 这里把**已经编码好的 JPEG 字节原样嵌入**（DCTDecode，PDF 原生支持的图像滤镜），
 * 体积 ≈ 各页 JPEG 之和（每页 1~3MB），也就与用户手里那种扫描 PDF 的实际形态一致。
 *
 * ## 结构（最小可用 PDF 1.4）
 * ```
 * 1 0 obj  Catalog → 2 0 obj Pages → 每页 3 个对象：Page / Contents / Image
 * 尾部：xref 表 + trailer + startxref + %%EOF
 * ```
 * ⚠️ **对象顺序无所谓，字节偏移必须准**：PDF 靠 xref 里的偏移定位对象，写歪一个字节就可能
 * 被阅读器判成损坏。所以本类所有输出都过同一对 `ascii/raw` 计数器（JPEG 字节也计数），
 * `PdfJpegWriterTest` 会逐对象核对偏移。
 *
 * ## 用法
 * 边渲染边写（**不要**把所有页的 JPEG 攒进内存：300 页 ×2MB = 600MB）：
 * ```kotlin
 * FileOutputStream(tmp).use { fos -> BufferedOutputStream(fos).use { out ->
 *     val w = PdfJpegWriter(out)
 *     pages.forEach { w.addPage(jpeg, px.w, px.h, pt.w, pt.h) }
 *     w.finish()
 * }}
 * ```
 *
 * 纯 JVM 实现（除了 `OutputStream` 不碰 Android）→ 可单测、也能在 PC 上生成测试 PDF。
 */
internal class PdfJpegWriter(private val out: OutputStream) {

    private companion object {
        /** 每个 PDF 页占 3 个对象（Page / Contents / Image），对象 1、2 固定是 Catalog、Pages。 */
        const val OBJECTS_PER_PAGE = 3
        const val FIRST_PAGE_OBJECT = 3
    }

    private var offset = 0L
    private var pageCount = 0
    private val pageObjectNumbers = StringBuilder()
    private val objectOffsets = ArrayList<Long>().apply { add(0L) } // 下标 = 对象号，0 是空位
    private var finished = false

    val pages: Int get() = pageCount

    init {
        // 头 + 二进制标记（惯例：让工具把文件当二进制处理，别按文本换行改字节）
        ascii("%PDF-1.4\n")
        raw(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))
        // 1 = Catalog（指向最后才写的 Pages 对象：前向引用合法，xref 里有偏移）
        beginObject(1)
        ascii("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
    }

    /**
     * 追加一页。
     *
     * @param jpeg 基线 JPEG 字节（`Bitmap.compress(JPEG, …)` 的产物即可）
     * @param widthPx/heightPx 图像像素尺寸（PDF 用它定位图像采样）
     * @param widthPt/heightPt 该页在 PDF 里的**点尺寸**（1/72 英寸）——决定"纸"多大
     */
    fun addPage(jpeg: ByteArray, widthPx: Int, heightPx: Int, widthPt: Float, heightPt: Float) {
        check(!finished) { "addPage() 不能在 finish() 之后调用" }
        require(jpeg.isNotEmpty()) { "空 JPEG 不能作为 PDF 页" }
        val wPt = fmt(widthPt)
        val hPt = fmt(heightPt)
        val wPx = widthPx.coerceAtLeast(1)
        val hPx = heightPx.coerceAtLeast(1)
        val pageObj = FIRST_PAGE_OBJECT + pageCount * OBJECTS_PER_PAGE
        val contentObj = pageObj + 1
        val imageObj = pageObj + 2

        // Page
        beginObject(pageObj)
        ascii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $wPt $hPt] " +
                "/Resources << /XObject << /Im0 $imageObj 0 R >> >> " +
                "/Contents $contentObj 0 R >>\nendobj\n"
        )

        // Contents：把单位正方形铺满整页（`cm` 矩阵），再把图像画上去
        val content = "q $wPt 0 0 $hPt 0 0 cm /Im0 Do Q\n".toByteArray(Charsets.US_ASCII)
        beginObject(contentObj)
        ascii("<< /Length ${content.size} >>\nstream\n")
        raw(content)
        ascii("endstream\nendobj\n")

        // Image（JPEG 原样嵌入：DCTDecode 就是 PDF 对 JPEG 的原生滤镜，不重新编码）
        beginObject(imageObj)
        ascii(
            "<< /Type /XObject /Subtype /Image /Width $wPx /Height $hPx " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode " +
                "/Length ${jpeg.size} >>\nstream\n"
        )
        raw(jpeg)
        ascii("\nendstream\nendobj\n")

        pageObjectNumbers.append(pageObj).append(" 0 R ")
        pageCount++
    }

    /**
     * 收尾：写 Pages 对象（此时才知道全部 Kids）、xref 表、trailer。
     *
     * ⚠️ 必须调；不调就是**没有 xref 的残缺文件**（多数阅读器直接打不开）。
     */
    fun finish() {
        check(!finished) { "finish() 只能调一次" }
        finished = true
        // 2 = Pages（放在最后写：Kids 要等所有页都加完）
        beginObject(2)
        ascii("<< /Type /Pages /Kids [$pageObjectNumbers] /Count $pageCount >>\nendobj\n")

        val xrefOffset = offset
        val objectCount = FIRST_PAGE_OBJECT + pageCount * OBJECTS_PER_PAGE // 含 0 号空位 → 正好是总数
        ascii("xref\n0 $objectCount\n")
        ascii("0000000000 65535 f \n")
        for (obj in 1 until objectCount) {
            // ⚠️ 偏移必须**恰好 10 位**：多一位这条 xref 记录就变成 21 字节，整张表错位 → 文件读不了。
            //    10 位够到 9.99GB（300dpi 的双语 300 页才 1~2GB），真到了就**报错**而不是写坏文件。
            val objectOffset = requireNotNull(objectOffsets.getOrNull(obj)) {
                "对象 $obj 没有偏移记录（写 xref 表时发现内容与对象表不一致）"
            }
            check(objectOffset < 10_000_000_000L) {
                "输出超过 10GB（offset=$objectOffset），xref 表宽度不够"
            }
            // 固定 20 字节一条（10 位偏移 + 空格 + 5 位代次 + 空格 + 类型 + 2 字节行尾）
            ascii(String.format(Locale.US, "%010d 00000 n \n", objectOffset))
        }
        ascii("trailer\n<< /Size $objectCount /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")
        out.flush()
    }

    private fun beginObject(number: Int) {
        while (objectOffsets.size <= number) objectOffsets.add(0L)
        objectOffsets[number] = offset
        ascii("$number 0 obj\n")
    }

    /**
     * 只写 ASCII。
     *
     * ⚠️ PDF 的**语法骨架**（对象头/xref/字典键）本来就只能是 ASCII，而 `US_ASCII` 会把非 ASCII
     * 字符**静默替换成 `?`** —— 今天写进去的全是结构与数字所以没问题，但将来若往这里塞中文
     * （比如给导出 PDF 加 `/Title` 书名），那就无声坏掉。要加非 ASCII 内容必须先定编码
     * （PDF 的文本串默认 PDFDocEncoding，中文要 UTF-16BE + BOM），别直接往 [ascii] 里塞。
     */
    private fun ascii(text: String) = raw(text.toByteArray(Charsets.US_ASCII))

    private fun raw(bytes: ByteArray) {
        out.write(bytes)
        offset += bytes.size
    }

    /** 点尺寸格式：两位小数、固定小数点（避免本地化逗号、也避免科学计数法）。 */
    private fun fmt(value: Float): String {
        val safe = if (value.isFinite() && value > 0f) value else 1f
        return String.format(Locale.US, "%.2f", safe)
    }
}
