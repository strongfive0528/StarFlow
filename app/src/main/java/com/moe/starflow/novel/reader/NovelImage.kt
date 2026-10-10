package com.moe.starflow.novel.reader

import java.io.File

/**
 * 一张已经解出来的插图。
 *
 * @param file 磁盘上的图片文件（epub/zip 里的条目在章节加载时已解出来，落盘去重，见
 *   `NovelChapterRepository.imagesOf`）。绘制时按需解码，不在这里持有 Bitmap ——
 *   一章十几张插图全驻留内存是几十 MB 的事。
 * @param width/[height] **原始像素尺寸**（`BitmapFactory` 的 bounds，未解码整张图）。
 *   分页要按它算"这张图占多高"，所以必须在加载时就量好：**分页（IO 线程）与绘制（主线程）
 *   必须用同一个数**，否则会出现「分页说放得下、画出来顶出框」。
 */
data class NovelImage(val file: File, val width: Int, val height: Int)

/**
 * 插图的**尺寸口径**：全项目只有这一份。
 *
 * 三处要用同一个尺寸：分页（[NovelPaginator]）、分页模式绘制（[NovelPageView]）、
 * 滚动模式绘制（[NovelScrollAdapter]）。各写一遍的话，某处差几个像素就是底部那行被裁掉、
 * 或者正文顶出框（本模块的单测是桩，这类几何错误盖不住，只能靠"只有一份实现"来保证）。
 *
 * 规则三条：
 * 1. **等比缩放到正文列宽内**（保持长宽比，绝不拉伸变形）
 * 2. **优先"占满正文列宽"**：用户口径 2026-10-04「显示的图片感觉都太小了」→ 允许放大，
 *    但封顶 [MAX_UPSCALE] 倍。⚠️ 为什么不能无上限放大：电子书里 1×1 / 2×2 的"占位、间距"
 *    小图非常多，无上限放大就是**一整屏的色块**（老实现因此写成"只缩不放"，代价是真插图
 *    明明可以更大却贴着原始像素显示）。
 * 3. **绝不裁图**：再高的图也按调用方给的高度上限（一页正文高度）缩到放得下 ——
 *    "图片被切开/被遮挡"是用户明确不允许的；宁可小一点，也不能少一块。
 */
object NovelImageMetrics {

    /** 放大的上限倍率（见类注释第 2 条）。 */
    const val MAX_UPSCALE = 3f

    /** 绘制尺寸（px）。 */
    data class Size(val width: Float, val height: Float)

    /**
     * 绘制尺寸。尺寸不可用（宽高 ≤ 0 / 列宽 ≤ 0）返回 null = **这一段没有可画的图**，
     * 调用方回落成占位文本（少一张图不能把正文也丢了）。
     *
     * @param maxHeightPx 高度上限（分页 = 一页正文高度）；`<= 0` 或非有限值 = 不限高（滚动模式）
     */
    fun fit(image: NovelImage, contentWidth: Int, maxHeightPx: Float): Size? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || contentWidth <= 0) return null
        val limit = if (maxHeightPx.isFinite() && maxHeightPx > 0f) maxHeightPx else Float.MAX_VALUE
        // 放大有上限；缩小不限（再大的图也得塞进列宽/一页高）
        val widthScale = (contentWidth.toFloat() / w).coerceAtMost(MAX_UPSCALE)
        val scale = minOf(widthScale, limit / h)
        if (scale <= 0f || !scale.isFinite()) return null
        return Size((w * scale).coerceAtLeast(1f), (h * scale).coerceAtLeast(1f))
    }

    /** 只要高度（分页记账用）；不可用时 0（调用方据此回落文本）。 */
    fun heightPx(image: NovelImage, contentWidth: Int, maxHeightPx: Float): Float =
        fit(image, contentWidth, maxHeightPx)?.height ?: 0f
}
