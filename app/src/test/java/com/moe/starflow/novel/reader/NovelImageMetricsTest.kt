package com.moe.starflow.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插图尺寸口径的守卫（纯 JVM：只有算术，不碰 BitmapFactory / Canvas）。
 *
 * 这条公式是**分页与绘制共用的唯一一份**（见 [NovelImageMetrics]），错了就是"底部那行被裁"
 * 或"正文顶出框"—— 而本模块的单测是桩，真机几何只能靠"只有一份实现"来保证。
 */
class NovelImageMetricsTest {

    private fun img(w: Int, h: Int) = NovelImage(File("x.jpg"), w, h)

    /** 大图缩到正文列宽（铺满），高度按比例。 */
    @Test
    fun `宽图缩到列宽`() {
        val size = NovelImageMetrics.fit(img(2000, 1000), contentWidth = 900, maxHeightPx = 1600f)!!
        assertEquals(900f, size.width, 0.5f)
        assertEquals(450f, size.height, 0.5f)
    }

    /**
     * **只缩不放**。
     *
     * 电子书里 1×1 的"占位/间距"小图非常多（`<img src="dot.gif">`），允许放大的话
     * 它们会被撑成一整屏的方块，把整本书的排版撕碎。
     */
    /**
     * ⚠️ 用户口径 2026-10-04「显示的图片感觉都太小了」→ **允许放大**，但有两道闸：
     * ① 封顶 [NovelImageMetrics.MAX_UPSCALE] 倍；② 1×1 这种"占位/间距"小图绝不能撑满屏。
     */
    @Test
    fun `小图按上限放大而不是无脑撑满`() {
        // 300×200 的图：列宽 900 → 3 倍正好封顶，放成 900×600
        val mid = NovelImageMetrics.fit(img(300, 200), contentWidth = 900, maxHeightPx = 1600f)!!
        assertEquals(900f, mid.width, 0.5f)
        assertEquals(600f, mid.height, 0.5f)

        // 1×1 的占位图：只放大到 3 倍（不是 900 倍变一整屏色块）
        val dot = NovelImageMetrics.fit(img(1, 1), contentWidth = 900, maxHeightPx = 1600f)!!
        assertEquals(NovelImageMetrics.MAX_UPSCALE, dot.width, 0.5f)
        assertEquals(NovelImageMetrics.MAX_UPSCALE, dot.height, 0.5f)

        // 本来就是整列宽的图：不多放（3 倍封顶之外还有"列宽"这一道）
        val wide = NovelImageMetrics.fit(img(900, 400), contentWidth = 900, maxHeightPx = 1600f)!!
        assertEquals(900f, wide.width, 0.5f)
        assertEquals(400f, wide.height, 0.5f)
    }

    /**
     * 比一页还高的图**缩到刚好一页**（否则它永远放不进任何一页，分页会把它整段挤出页表）。
     * 缩高时宽度也要跟着缩，比例不能变形。
     */
    @Test
    fun `超高的图缩到页高`() {
        val size = NovelImageMetrics.fit(img(1000, 4000), contentWidth = 900, maxHeightPx = 1200f)!!
        assertEquals(1200f, size.height, 0.5f)
        assertEquals(300f, size.width, 0.5f)
        assertTrue("宽度不能超过正文列宽", size.width <= 900f)
        // 高宽比保持
        assertEquals(4f, size.height / size.width, 0.01f)
    }

    /** 滚动模式不限高（`maxHeightPx <= 0`）：整页插图有多高就多高，宽度仍是列宽。 */
    @Test
    fun `不限高时按列宽等比`() {
        val size = NovelImageMetrics.fit(img(1000, 4000), contentWidth = 900, maxHeightPx = 0f)!!
        assertEquals(900f, size.width, 0.5f)
        assertEquals(3600f, size.height, 0.5f)
    }

    /** 尺寸不可用 → null（调用方回落占位文本）；列宽 <= 0（布局未完成）同样不画。 */
    @Test
    fun `尺寸不可用时返回 null`() {
        assertNull(NovelImageMetrics.fit(img(0, 100), 900, 1200f))
        assertNull(NovelImageMetrics.fit(img(100, 0), 900, 1200f))
        assertNull(NovelImageMetrics.fit(img(100, 100), 0, 1200f))
    }

    /** 高度取整后不许是 0：0 高的段会被走法整段跳过（正文凭空少一段）。 */
    @Test
    fun `高度至少一个像素`() {
        val size = NovelImageMetrics.fit(img(10000, 3), contentWidth = 900, maxHeightPx = 1200f)!!
        assertEquals(1f, size.height, 0.001f)
    }
}
