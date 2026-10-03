package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * PDF 页的**像素空间口径**（见 [PdfRasterPolicy]）。
 *
 * 这个测试守的不是"某个数字好看"，而是一条硬约束：**同一页 pt 尺寸永远映射到同一个像素尺寸**。
 * 气泡坐标、OCR 结果、超分输入输出、导出页尺寸全都活在这个空间里 —— 口径一变，
 * 老译文的框就会画在别处，而且看起来像随机错位（极难排查、用户也说不清）。
 */
class PdfRasterPolicyTest {

    /** A5 页（148×210mm ≈ 420×595pt）。 */
    private val a5 = intArrayOf(420, 595)

    /** B5(JIS) 页（182×257mm ≈ 516×728pt）—— 日漫最常见的开本。 */
    private val b5 = intArrayOf(516, 728)

    @Test
    fun `300dpiIsTheLockedBaseline`() {
        // ⚠️ 改这个数字 = 改所有 PDF 页的像素空间 → 已存的译文气泡坐标全部失配。
        //    真要改，必须同时提供页序/坐标迁移（现在没有），所以这里直接钉死。
        assertEquals(300, PdfRasterPolicy.RASTER_DPI)
    }

    @Test
    fun `a5PageRendersAt300dpi`() {
        val size = PdfRasterPolicy.canonicalSize(a5[0], a5[1])
        assertEquals(1750, size[0])   // 420pt / 72 * 300
        assertEquals(2479, size[1])   // 595pt / 72 * 300 = 2479.17 → 2479
    }

    @Test
    fun `b5PageRendersAt300dpi`() {
        val size = PdfRasterPolicy.canonicalSize(b5[0], b5[1])
        assertEquals(2150, size[0])   // 516 / 72 * 300
        assertEquals(3033, size[1])   // 728 / 72 * 300 = 3033.33 → 3033
    }

    @Test
    fun `theSamePageAlwaysGetsTheSameSize`() {
        // 同一次会话内、跨调用、跨构造（"重新打开阅读器"）都必须一致 —— 这是坐标能对上的前提
        repeat(5) {
            assertEquals(
                PdfRasterPolicy.canonicalSize(a5[0], a5[1]).toList(),
                PdfRasterPolicy.canonicalSize(a5[0], a5[1]).toList(),
            )
        }
        // 便捷重载与主入口同源
        assertEquals(
            PdfRasterPolicy.canonicalSize(a5[0], a5[1]).toList(),
            PdfRasterPolicy.canonicalSizeOf(a5).toList(),
        )
    }

    @Test
    fun `aspectRatioIsPreserved`() {
        val size = PdfRasterPolicy.canonicalSize(b5[0], b5[1])
        val ptRatio = b5[0].toDouble() / b5[1]
        val pxRatio = size[0].toDouble() / size[1]
        assertTrue("宽高比必须保持（pt=$ptRatio px=$pxRatio）", abs(ptRatio - pxRatio) < 0.01)
    }

    @Test
    fun `hugePagesAreCappedSoTheyCannotBlowUpMemory`() {
        // 海报级页面（2000×3000pt ≈ 70×106cm）：不设上限就是 12.5 万像素长边、上百 MB
        val size = PdfRasterPolicy.canonicalSize(2000, 3000)
        assertTrue("长边必须被压到 ${PdfRasterPolicy.MAX_LONG_SIDE} 以内（实际 ${size[0]}x${size[1]}）",
            max(size[0], size[1]) <= PdfRasterPolicy.MAX_LONG_SIDE)
        assertTrue("总像素必须被压到 ${PdfRasterPolicy.MAX_PIXELS} 以内（实际 ${PdfRasterPolicy.pixelCount(size)}）",
            PdfRasterPolicy.pixelCount(size) <= PdfRasterPolicy.MAX_PIXELS)
        assertTrue("压完不能小到没法看（${size[0]}x${size[1]}）", size[0] > 100 && size[1] > 100)
    }

    @Test
    fun `tinyPagesAreUpscaledToTheMinimumLongSide`() {
        // 异常小的页面（10pt ≈ 3.5mm）也要给 OCR 一个最低可用分辨率
        val size = PdfRasterPolicy.canonicalSize(10, 10)
        assertEquals(PdfRasterPolicy.MIN_LONG_SIDE, max(size[0], size[1]))
    }

    @Test
    fun `degenerateInputNeverProducesZeroOrNegativePixels`() {
        // pt 尺寸理论上 >0，但损坏文件/异常解析可能给出 0 或负数 —— 绝不能生成 0 像素位图
        listOf(intArrayOf(0, 0), intArrayOf(-5, 100), intArrayOf(0, -1)).forEach { bad ->
            val size = PdfRasterPolicy.canonicalSize(bad[0], bad[1])
            assertTrue("入参 ${bad.toList()} → ${size.toList()}", size[0] >= 1 && size[1] >= 1)
        }
    }

    @Test
    fun `downscaleDividesBySampleAndNeverCollapses`() {
        assertEquals(listOf(500, 700), PdfRasterPolicy.downscale(intArrayOf(2000, 2800), 4).toList())
        // sample <= 1 = 原样（缩略图/Webtoon 的采样率就是"1,2,4,8…"）
        assertEquals(listOf(2000, 2800), PdfRasterPolicy.downscale(intArrayOf(2000, 2800), 1).toList())
        assertEquals(listOf(2000, 2800), PdfRasterPolicy.downscale(intArrayOf(2000, 2800), 0).toList())
        // 极端采样也不能得到 0（Bitmap.createBitmap(0, h) 直接抛异常）
        assertEquals(listOf(1, 1), PdfRasterPolicy.downscale(intArrayOf(3, 3), 64).toList())
    }

    @Test
    fun `downscaleResultStaysInsideTheCaps`() {
        // Webtoon 路径会先算权威尺寸再除采样率 —— 结果必须仍在内存上限之内
        val canonical = PdfRasterPolicy.canonicalSize(2000, 3000)
        val sampled = PdfRasterPolicy.downscale(canonical, 2)
        assertTrue(PdfRasterPolicy.pixelCount(sampled) < PdfRasterPolicy.pixelCount(canonical))
        assertTrue(PdfRasterPolicy.pixelCount(sampled) <= PdfRasterPolicy.MAX_PIXELS)
    }

    /**
     * **缓存预算必须装得下最大的那一页**。
     *
     * ⚠️ 这条是硬的、不是"调优"：`LruCache` 对"单条比 maxSize 还大"的条目会在 `put` 之后
     * 立刻把自己挤出去 → **缓存恒不命中**，同一页在一次"显示 + OCR + 超分 + 导出"里被重渲
     * 2~4 次（每次 300~600ms），而且每次都去抢渲染锁。A4（2480×3508）就要 34.8MB ——
     * 之前写 32MB 时，最常见的 A4/Letter 恰好落在这个坑里。
     */
    @Test
    fun `theCacheBudgetCanHoldTheLargestPage`() {
        val largestPageBytes = PdfRasterPolicy.MAX_PIXELS.toLong() * 4
        assertTrue(
            "缓存预算 ${PdfRasterPolicy.CANONICAL_CACHE_BUDGET_BYTES / 1024 / 1024}MB " +
                "小于单页上限 ${largestPageBytes / 1024 / 1024}MB → LruCache 会放进去就被挤出（缓存形同虚设）",
            PdfRasterPolicy.CANONICAL_CACHE_BUDGET_BYTES >= largestPageBytes,
        )
        // 单独再钉一版最常见的 A4（2480×3508 = 34.8MB）：它是"下限的下限"
        val a4 = PdfRasterPolicy.canonicalSize(595, 842)
        assertTrue(
            "A4 页 ${PdfRasterPolicy.pixelCount(a4) * 4 / 1024 / 1024}MB 也必须在预算之内",
            PdfRasterPolicy.CANONICAL_CACHE_BUDGET_BYTES >= PdfRasterPolicy.pixelCount(a4).toLong() * 4,
        )
    }

    @Test
    fun `sampleForFollowsTheSharedReaderRule`() {
        // 普通页：按目标宽降到 2 的幂
        assertEquals(2, PdfRasterPolicy.sampleFor(2000, 3000, 1080))
        // 已经够小 → 不缩
        assertEquals(1, PdfRasterPolicy.sampleFor(1000, 1400, 1080))
        // 超高条（Webtoon 长条）：即便宽度够，也要继续缩到 12:1 与 12M 像素以内
        assertTrue(PdfRasterPolicy.sampleFor(1080, 30_000, 1080) > 1)
        // 退化入参不能死循环/除零
        assertEquals(1, PdfRasterPolicy.sampleFor(0, 0, 0))
    }

    @Test
    fun `sampledSizeKeepsAspectAndStaysInsideEveryCap`() {
        val canonical = PdfRasterPolicy.canonicalSize(516, 728)          // B5
        val sampled = PdfRasterPolicy.sampledSize(canonical, 1080)
        assertTrue("宽度不能超过目标宽", sampled[0] <= 1080)
        assertTrue("长条页高宽比上限 ~12:1", sampled[1] <= 1080 * 12)
        assertTrue("总像素仍在上限内", PdfRasterPolicy.pixelCount(sampled) <= PdfRasterPolicy.MAX_PIXELS)
        assertTrue("尺寸至少 1px", sampled[0] >= 1 && sampled[1] >= 1)
        val ptRatio = 516.0 / 728
        val pxRatio = sampled[0].toDouble() / sampled[1]
        assertTrue("宽高比要保持（pt=$ptRatio px=$pxRatio）", abs(ptRatio - pxRatio) < 0.02)
    }
}
