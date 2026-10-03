package com.moe.starflow.mangaimport.data

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PDF 页栅格化的**唯一分辨率口径**（纯函数，JVM 单测覆盖）。
 *
 * ## 为什么必须"唯一"
 * 气泡坐标、原文/译文、超分输入输出、导出，全都活在**同一个像素空间**里。
 * PDF 与图片/zip 最大的不同是：图片有一个"原生尺寸"（文件里写死的），而 PDF 页只有
 * **点（pt）尺寸**，像素尺寸是**我们自己选的**。所以一旦两处算法不一致，
 * 结果就是"OCR 认出来的框画在别的地方" —— 而且看起来像随机错位，极难排查。
 *
 * ⇒ 任何需要"这一页多少像素"的地方（`ReaderPageSource.originalWidth/Height`、
 *   `loadFull`、`loadWebtoon`、`loadThumb`、导出）**都必须调这里**，不许各自乘系数。
 *
 * ## 口径
 * - 基准 **300 DPI**（`RASTER_DPI`）：扫描漫画的常见分辨率，也是 OCR 的舒适区
 *   （A5 页 ≈ 1748×2480，B5 页 ≈ 2150×3035）
 * - 长边上限 [MAX_LONG_SIDE]、总像素上限 [MAX_PIXELS]：防超大页 OOM（与
 *   `ReaderPageSource.computeSample` 的 12M 像素上限同口径）
 * - 长边下限 [MIN_LONG_SIDE]：页面 pt 很小（异常文件）时给 OCR 一个最低可用分辨率
 * - 同一页 pt 尺寸 → **永远同一个像素尺寸**（可重入、可复现，与设备无关）
 */
object PdfRasterPolicy {

    /** 栅格化基准 DPI。改它 = 改所有 PDF 页的像素空间（老译文的坐标会全部失配）。 */
    const val RASTER_DPI: Int = 300

    private const val PT_PER_INCH: Double = 72.0

    /** 单页长边像素上限（与 [MAX_PIXELS] 一起防 OOM）。 */
    const val MAX_LONG_SIDE: Int = 4096

    /** 单页总像素上限（≈48MB ARGB），与阅读器采样解码同一口径。 */
    const val MAX_PIXELS: Int = 12_000_000

    /** 长边像素下限：异常小的页面也要保证 OCR 有字可认。 */
    const val MIN_LONG_SIDE: Int = 600

    /**
     * 权威尺寸位图的缓存预算。
     *
     * ⚠️ **必须 ≥ 单页最大字节数**（[MAX_PIXELS] × 4B = 48MB），否则 `LruCache` 会出现
     * "刚放进去就被自己挤出来"——A4 页 2480×3508 = **34.8MB**，用 32MB 的预算就等于**缓存恒不命中**，
     * 同一页在一次"显示 + OCR + 超分 + 导出"里被重渲 2~4 次（每次 300~600ms），而且每次都要抢渲染锁。
     * 取 64MB：覆盖上限页（48MB）+ 一张 A5（17MB），与既有预算同量级
     * （webtoon 采样缓存 60MB、译文渲染缓存 100MB）。
     * 守卫：`PdfRasterPolicyTest.theCacheBudgetCanHoldTheLargestPage`。
     */
    const val CANONICAL_CACHE_BUDGET_BYTES: Int = 64 * 1024 * 1024

    /**
     * 这一页的**权威像素尺寸**（`[宽, 高]`，ARGB_8888）。
     *
     * @param pageWidthPt `PdfRenderer.Page.width`（点，1/72 英寸）
     * @param pageHeightPt `PdfRenderer.Page.height`（点）
     */
    fun canonicalSize(pageWidthPt: Int, pageHeightPt: Int): IntArray {
        val w = pageWidthPt.coerceAtLeast(1).toDouble()
        val h = pageHeightPt.coerceAtLeast(1).toDouble()
        val longPt = maxOf(w, h)
        var scale = RASTER_DPI / PT_PER_INCH
        // ① 长边上限
        if (longPt * scale > MAX_LONG_SIDE) scale = MAX_LONG_SIDE / longPt
        // ② 总像素上限（在①之后再压，绝不放宽①）
        val pixels = (w * scale) * (h * scale)
        if (pixels > MAX_PIXELS) scale *= sqrt(MAX_PIXELS / pixels)
        // ③ 长边下限（只在页面小得离谱时才放大）
        if (longPt * scale < MIN_LONG_SIDE) scale = MIN_LONG_SIDE / longPt
        return intArrayOf(
            (w * scale).roundToInt().coerceAtLeast(1),
            (h * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** [canonicalSize] 的便捷重载：入参是 `[宽pt, 高pt]`。 */
    fun canonicalSizeOf(pageSizePt: IntArray): IntArray =
        canonicalSize(pageSizePt.getOrElse(0) { 0 }, pageSizePt.getOrElse(1) { 0 })

    /**
     * 按整数采样率缩小（缩略图 / Webtoon 采样图用）。
     *
     * ⚠️ 采样率与 `ReaderPageSource.computeSample` **同一套**：那里按"目标宽 + 12:1 高宽比 +
     * 12M 像素"算出 sample，这里只负责把权威尺寸除以它，别再引入第二套采样规则。
     */
    fun downscale(canonical: IntArray, sample: Int): IntArray {
        val s = sample.coerceAtLeast(1)
        val w = canonical.getOrElse(0) { 1 }.coerceAtLeast(1)
        val h = canonical.getOrElse(1) { 1 }.coerceAtLeast(1)
        return intArrayOf((w / s).coerceAtLeast(1), (h / s).coerceAtLeast(1))
    }

    /**
     * **采样率**：按目标宽度适配 + 限制超高页（~12:1）+ 限制总像素（防 OOM 卡死）。
     *
     * ⚠️ 这是**唯一**的采样判据：图片路径（`ReaderPageSource.computeSample`）与 PDF 路径都调它 ——
     * 两边各写一份的话，"同一页在原图/Webtoon 下算出不同尺寸"就会让气泡坐标缩放系数对不上。
     */
    fun sampleFor(width: Int, height: Int, targetWidth: Int): Int {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val target = targetWidth.coerceAtLeast(1)
        val maxHeight = target * 12          // 高宽比上限 ~12:1
        val maxArea = 12_000_000            // 单幅像素上限 ~48MB
        var sample = 1
        while (true) {
            val sw = w / sample
            val sh = h / sample
            if (sw <= target && sh <= maxHeight && sw * sh <= maxArea) break
            sample *= 2
        }
        return sample
    }

    /** Webtoon 采样图的最终尺寸：权威尺寸 ÷ [sampleFor] 的采样率。 */
    fun sampledSize(canonical: IntArray, targetWidth: Int): IntArray =
        downscale(canonical, sampleFor(canonical.getOrElse(0) { 1 }, canonical.getOrElse(1) { 1 }, targetWidth))

    fun pixelCount(size: IntArray): Int =
        size.getOrElse(0) { 0 }.coerceAtLeast(0) * size.getOrElse(1) { 0 }.coerceAtLeast(0)
}
