package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * **用真实 JPEG 让 [PdfJpegWriter] 再产一份 PDF**（opt-in，默认跳过）。
 *
 * 为什么需要它：`PdfJpegWriterTest` 只做结构校验（假 JPEG 字节足够），而"**这份 PDF 真的能被系统
 * `PdfRenderer` 渲染**"必须在设备上验。设备验证不需要装 App —— 把本测试产出的 PDF 推到手机，
 * 用 `app_process` 跑一个一次性探针（`tools/pdf-render-probe/`）渲染第 1 页即可。
 *
 * 输入用 `tools/make-sample-pdf.ps1` 造的那份样本（宿主 GDI+ 画的真 JPEG、含 CJK 文字），
 * 本测试把它**按页拆出 JPEG 再用我们的写入器重新拼一遍** → 壳是 Kotlin 写的，像素是真的。
 *
 * 跑法：
 * ```
 * $env:STARFLOW_SAMPLE_PDF='tools/sample/manga-sample.pdf'
 * $env:STARFLOW_REBUILT_PDF='tools/sample/manga-sample-kotlin.pdf'
 * .\gradlew.bat --no-daemon :app:testDebugUnitTest --tests '*PdfJpegWriterRealJpegTest'
 * ```
 */
class PdfJpegWriterRealJpegTest {

    @Test
    fun rebuildSamplePdfWithOurWriter() {
        val source = System.getenv("STARFLOW_SAMPLE_PDF")
        val target = System.getenv("STARFLOW_REBUILT_PDF")
        assumeTrue(
            "未设置 STARFLOW_SAMPLE_PDF / STARFLOW_REBUILT_PDF → 跳过（设备探针用的工具，不是断言）",
            !source.isNullOrBlank() && !target.isNullOrBlank(),
        )
        val src = File(source!!)
        assumeTrue("样本 PDF 不存在：${src.absolutePath}", src.isFile)

        val jpegs = extractJpegs(src.readBytes())
        assertTrue("样本里一个 JPEG 都没提取到（结构变了？）", jpegs.size >= 3)

        val out = File(target!!)
        out.parentFile?.mkdirs()
        // A5@300dpi → 与样本的 MediaBox 同口径（1750×2479 px 对应 420.00×594.96 pt）
        val widthPx = 1750
        val heightPx = 2479
        val ptPerPx = 72f / 300f

        FileOutputStream(out).use { fos ->
            java.io.BufferedOutputStream(fos).use { bos ->
                val writer = PdfJpegWriter(bos)
                jpegs.forEach { jpeg ->
                    writer.addPage(jpeg, widthPx, heightPx, widthPx * ptPerPx, heightPx * ptPerPx)
                }
                writer.finish()
            }
        }
        assertEquals("页数应与提取到的 JPEG 数一致", jpegs.size, pageCountIn(out.readBytes()))
        assertTrue("重建后的 PDF 太小", out.length() > 100_000)
        println("rebuilt pdf (kotlin writer) → ${out.absolutePath} (${out.length()} bytes, ${jpegs.size} pages)")
    }

    /** 从写出的字节里读回 `/Count N`（顺带证明 kids/count 真的写进去了，不是只看文件大小）。 */
    private fun pageCountIn(bytes: ByteArray): Int {
        val text = String(bytes, Charsets.US_ASCII)
        val match = Regex("/Type /Pages /Kids \\[[^]]*] /Count (\\d+)").find(text) ?: return -1
        return match.groupValues[1].toInt()
    }

    /** 按 SOI(FFD8)…EOI(FFD9) 顺序切出所有 JPEG（JPEG 熵数据有字节填充，EOI 不会误判）。 */
    private fun extractJpegs(bytes: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var start = -1
        var i = 0
        while (i < bytes.size - 1) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            if (start < 0 && a == 0xFF && b == 0xD8) {
                start = i
                i += 2
                continue
            }
            if (start >= 0 && a == 0xFF && b == 0xD9) {
                ByteArrayOutputStream().use { buf ->
                    buf.write(bytes, start, i + 2 - start)
                    out.add(buf.toByteArray())
                }
                start = -1
                i += 2
                continue
            }
            i++
        }
        return out
    }
}
