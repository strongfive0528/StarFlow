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
 * 三处要用同一个高度：分页（[NovelPaginator]）、分页模式绘制（[NovelPageView]）、
 * 滚动模式绘制（[NovelScrollAdapter]）。各写一遍的话，某处差几个像素就是底部那行被裁掉、
 * 或者正文顶出框（本模块的单测是桩，这类几何错误盖不住，只能靠"只有一份实现"来保证）。
 *
 * 规则两条：
 * 1. **等比缩放到正文列宽内**（保持长宽比，绝不拉伸变形）
 * 2. **只缩不放**（`scale <= 1`）：放大小图只会得到一张糊图；而电子书里 1×1 的
 *    "占位/间距"小图非常多，允许放大的话它们会被撑成一整屏的方块
 *
 * 另外按调用方给的高度上限（一页正文高度）再夹一次：比一页还高的图缩到刚好一页，
 * 否则它永远放不进任何一页（分页会把它整段挤出去）。
 */
object NovelImageMetrics {

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
        val scale = minOf(1f, contentWidth.toFloat() / w, limit / h)
        if (scale <= 0f || !scale.isFinite()) return null
        return Size((w * scale).coerceAtLeast(1f), (h * scale).coerceAtLeast(1f))
    }

    /** 只要高度（分页记账用）；不可用时 0（调用方据此回落文本）。 */
    fun heightPx(image: NovelImage, contentWidth: Int, maxHeightPx: Float): Float =
        fit(image, contentWidth, maxHeightPx)?.height ?: 0f
}
