package com.moe.starflow.mangaimport.reader

import android.graphics.Bitmap
import com.moe.starflow.mangaimport.data.PdfRasterPolicy
import com.moe.starflow.mangaimport.translate.ReaderTranslationController
import com.moe.starflow.utils.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * PDF 书的「打包下载」：把译文/双语写成 PDF，**直接写进调用方给的流**。
 *
 * ## 为什么吃 OutputStream 而不是自己落一个临时文件
 * PDF 书动辄 100MB~1GB（双语更多），而 `cacheDir` 在 /data 分区 —— 先写一份临时文件再复制到
 * Downloads 等于**同一份数据在磁盘上出现两次**（还要 2~3 倍瞬时空间），手机上很容易「导出失败」
 * 而用户完全不知道为什么（源文件明明在）。所以这里只负责"往流里写"，落盘/回滚由调用方管
 * （见 `MangaReaderActivity.streamToDownloads`：建 MediaStore 行 → 写 → 失败删行）。
 *
 * ## 与 zip 导出（[ReaderExport]）的三点不同
 * 1. **出口是 PDF 而不是 zip**：PDF 书的用户预期就是拿到 PDF（也才能再导回别处读）
 * 2. **译文 PDF = 全页**：已翻译页出译图，未翻译/失败页**回填原图** ——
 *    PDF 少页会让整本的页码错位，比"某几页还是原文"难用得多
 *    （zip 导出刻意相反：只出已翻译页，因为包里没有"页"的概念）
 * 3. **双语 PDF = 已翻译页「原图页 + 译图页」成对**，未翻译页只出原图页（不重复一张一样的图）。
 *    页数因此是 `N + 已翻译页数`，不是 `2N`
 *
 * ## 体积与耗时（用户要能预期）
 * 每页都要：渲染（PDF 页现渲 / 译图实时渲）→ JPEG 编码 → 嵌入 PDF。
 * 一页约 0.3~1s，300 页的书要几分钟；体积 ≈ 各页 JPEG 之和（见 [PdfJpegWriter] 的说明）。
 *
 * 进度：每处理完**一页原书页**回调一次（双语模式下这一页可能写了两张）。
 */
internal object ReaderPdfExport {

    private const val TAG = "ReaderPdfExport"

    /** 嵌入 PDF 的 JPEG 质量：肉眼无损区间的下限，体积换得动（300 页能差出一倍）。 */
    private const val JPEG_QUALITY = 88

    /**
     * @param both true = 双语 PDF（已译页：原图页 + 译图页）；false = 译文 PDF（全页，未译页原图）
     * @param out **调用方拥有**的输出流（本函数只 flush，不 close）
     * @param onProgress 每处理完一页原书页回调一次（**主线程**），参数为 (已处理, 总页数)
     */
    suspend fun export(
        source: ReaderPageSource,
        controller: ReaderTranslationController,
        both: Boolean,
        out: OutputStream,
        onProgress: (done: Int, total: Int) -> Unit,
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val total = source.size
        if (total <= 0) return@withContext ExportOutcome.Empty
        val translated = controller.translatedPages()
        // 一页都没翻过 → 与 zip 导出同一条口径：不产出"纯原图"的假译文包
        if (translated.isEmpty()) return@withContext ExportOutcome.Empty

        var written = 0
        var skipped = 0
        /** 是否真的写到了 trailer —— 没有它的话，中途 IOException 会被当成"部分成功"交付一个打不开的 PDF。 */
        var completed = false
        val buffered = BufferedOutputStream(out, 64 * 1024)
        try {
            val writer = PdfJpegWriter(buffered)
            for (page in 0 until total) {
                var pageFailed = false
                // ① 双语：先出"原图页"（未翻译页就靠这一张，所以不能省）
                if (both) {
                    if (writeSourcePage(writer, source, page)) written++ else pageFailed = true
                }
                // ② 译图页：按导出开始时的快照判断"这页应该有译文"，再现读载荷渲染
                val expected = page in translated
                val rendered = if (expected) controller.renderForExport(page) else null
                if (expected && rendered == null) {
                    // ⚠️ **必须记一笔**：导出是跨 onStop 继续跑的长任务，用户完全可能中途
                    //    「删除该页译文 / 清空本章」，于是快照里还有、载荷已经取不到了。
                    //    不记的话双语包会只剩原图页、skipped 还是 0 —— 用户以为整本都翻了。
                    pageFailed = true
                    LogCollector.w(TAG, "译文页渲染失败（导出途中译文被删除/载荷为空）page=$page")
                }
                if (rendered != null) {
                    try {
                        if (writeBitmapPage(writer, rendered)) written++ else pageFailed = true
                    } finally {
                        // renderForExport 给的是独立副本 → 用完即回收（别攒内存）
                        rendered.recycle()
                    }
                } else if (!both) {
                    // 译文 PDF：未翻译页回填原图，保证整本页数一致、可连续阅读
                    if (writeSourcePage(writer, source, page)) written++ else pageFailed = true
                }
                if (pageFailed) skipped++
                withContext(Dispatchers.Main) { onProgress(page + 1, total) }
            }
            writer.finish()
            buffered.flush()
            completed = true
            // 一页都没写进去 = 没交付任何东西，不能算成功
            if (written <= 0) ExportOutcome.Failed else ExportOutcome.Done(written, skipped)
        } catch (e: CancellationException) {
            // 取消（退出阅读器 → onDestroy 取消 lifecycleScope）要按结构化并发继续上抛
            throw e
        } catch (e: Exception) {
            // 必须落盘：磁盘满 / 渲染异常 / JPEG 编码失败在用户侧长得一模一样
            LogCollector.e(TAG, "exportPdf failed both=$both written=$written completed=$completed", e)
            // ⚠️ **没写完 trailer 的 PDF 是打不开的**（没有 xref）——宁可报失败也不能当成功交付：
            //    zip 截断还能解开一部分，PDF 截断连页数都读不出来。
            if (!completed) ExportOutcome.Failed
            else if (written > 0) ExportOutcome.Done(written, skipped)
            else ExportOutcome.Failed
        }
    }

    /** 原图页（PDF 现渲）。返回是否写成功；位图用完即回收。 */
    private fun writeSourcePage(writer: PdfJpegWriter, source: ReaderPageSource, page: Int): Boolean {
        val bmp = source.loadFull(page) ?: return false
        return try {
            writeBitmapPage(writer, bmp)
        } finally {
            bmp.recycle()
        }
    }

    /** 位图 → JPEG → 一页 PDF。页的**点尺寸**由像素尺寸按栅格化 DPI 反推（= 原页面尺寸）。 */
    private fun writeBitmapPage(writer: PdfJpegWriter, bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val jpeg = ByteArrayOutputStream().use { buffer ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)) return false
            buffer.toByteArray()
        }
        if (jpeg.isEmpty()) return false
        val ptPerPx = 72f / PdfRasterPolicy.RASTER_DPI
        writer.addPage(jpeg, w, h, w * ptPerPx, h * ptPerPx)
        return true
    }
}
