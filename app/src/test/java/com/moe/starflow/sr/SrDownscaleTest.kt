package com.moe.starflow.sr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 大图超分前的**输入像素阈值压缩计划**：输入不超过有效上限时直送模型，超过时等比例缩小。
 *
 * ⚠️ 注意这条只约束**喂进引擎的那张压缩图**。产物原样落盘、不做任何额外缩放，
 * 与原图尺寸无关 —— 见 `SrDownscale` 类注释与
 * `SrReaderWiringTest.theUpscaledProductIsStoredAsIs`。
 *
 * ⚠️ Robolectric 的 Canvas **不真正栅格化**（见 `manga/CLAUDE.md`），所以这里只断言
 * **尺寸与所有权**，不断言像素内容 —— 尺寸恰恰是这块功能唯一需要正确的东西。
 */
@RunWith(RobolectricTestRunner::class)
class SrDownscaleTest {

    private fun bmp(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun px(w: Int, h: Int): Long = w.toLong() * h

    /** 输入像素压缩阈值：只调整送入模型的尺寸。 */
    @Test
    fun inputThresholdShrinksFeedWithoutDecidingOutputStorage() {
        // 960x1367（1.31MP）→ 按显式 1.0MP 阈值压缩；plan 只生成输入尺寸，不负责判断产物去留。
        val p = SrDownscale.plan(960, 1367, SrDownscale.DEFAULT_MAX_FEED_PIXELS, 1.0f)!!
        assertTrue(
            "喂进引擎的像素必须 ≤ 预算：${p.width}x${p.height}",
            p.width.toLong() * p.height <= 1_000_000L,
        )
        assertTrue("输入超过阈值时宽高都按比例压缩", p.width < 960 && p.height < 1367)
        // 本来就小、像素总量不超 → 不压（阈值不制造无意义的缩放）
        assertNull(
            "0.58MP 的页在 1.0MP 阈值下不该被压",
            SrDownscale.plan(632, 912, SrDownscale.DEFAULT_MAX_FEED_PIXELS, 1.0f),
        )
        // 0 = 不限 → 与旧行为完全一致
        assertEquals(
            "阈值=0 时只受模型硬输入上限约束",
            SrDownscale.plan(2000, 3000, SrDownscale.DEFAULT_MAX_FEED_PIXELS),
            SrDownscale.plan(2000, 3000, SrDownscale.DEFAULT_MAX_FEED_PIXELS, 0f),
        )
        // 大图照样**给方案**（不返回 null = 不跳过），只是喂得更小
        val big = SrDownscale.plan(2480, 3508, SrDownscale.DEFAULT_MAX_FEED_PIXELS, 1.0f)
        assertNotNull("大图不许跳过：必须给压缩方案", big)
    }

    @Test
    fun smallPageNeedsNoDownscale() {
        assertNull("0.58MP 输入低于有效上限时直接走原路",
            SrDownscale.plan(632, 912))
        assertNull("1.75MP 输入低于 2.5MP 模型上限时直接走原路", SrDownscale.plan(1080, 1620))
        assertNull("非法尺寸不压", SrDownscale.plan(0, 100))
        assertNull("非法尺寸不压（负）", SrDownscale.plan(-5, 100))
    }

    /**
     * 大图：只按有效输入像素上限压缩，不对短边单独设限。
     * 断言不变式而不是具体数字：产物像素 ≤ 上限、长宽比保住。
     */
    @Test
    fun bigPageIsShrunkToThePixelCapOnly() {
        val p = SrDownscale.plan(2000, 3000)!!
        assertTrue(
            "产物像素必须 ≤ 引擎上限：${p.width}x${p.height}",
            p.width.toLong() * p.height <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertEquals("长宽比要保住", 2000.0 / 3000.0, p.width.toDouble() / p.height, 0.01)
        val q = SrDownscale.plan(3000, 2000)!!
        assertTrue(
            "转置后同样 ≤ 上限：${q.width}x${q.height}",
            q.width.toLong() * q.height <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertEquals("长宽比要保住", 3000.0 / 2000.0, q.width.toDouble() / q.height, 0.01)
    }

    /** 长条图片同样按总像素数限制，不能只依据某一条边。 */
    @Test
    fun pixelCapStillAppliesWhenTheShortSideIsAlreadySmall() {
        val p = SrDownscale.plan(1080, 2592)!!          // 2.8MP > 2.5MP
        assertTrue(
            "只对齐短边是不够的：2.8MP 仍然超引擎上限",
            px(p.width, p.height) <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertTrue("长宽比要保住", p.width < 1080 && p.height < 2592)
    }

    /** Real-ESRGAN 是 4x，输入上限只有 0.625MP —— 同一个 plan 要按引擎上限给出不同的结果。 */
    @Test
    fun thePlanFollowsTheEngineSpecificCap() {
        val p = SrDownscale.plan(2000, 3000, 625_000L)!!
        assertTrue(
            "4x 引擎的 0.625MP 上限也要满足（否则 Real-ESRGAN 永远报图太大）",
            px(p.width, p.height) <= 625_000L,
        )
        val p2x = SrDownscale.plan(2000, 3000, 2_500_000L)!!
        assertTrue("4x 模型的较低输入上限会压得更小", p.width < p2x.width)
    }

    @Test
    fun extremeAspectIsStillBroughtUnderThePixelCap() {
        // 20000x300 的长宽比很极端，但总像素超过模型上限，因此仍按同一规则压缩。
        val p = SrDownscale.plan(20000, 300)!!
        assertTrue(
            "超过引擎上限就必须压",
            px(p.width, p.height) <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertTrue("长宽比要保住（不能被压成正方形）", p.width > p.height * 50)
    }

    /** **压缩永远是缩小**（k < 1）—— 这就是「压缩图不超过原图像素」的构造性保证。 */
    @Test
    fun planNeverUpscales() {
        for ((w, h) in listOf(2000 to 3000, 1080 to 2592, 20000 to 300, 4001 to 6000)) {
            val p = SrDownscale.plan(w, h) ?: continue
            assertTrue("$w x $h：压出来必须更小（原 ${px(w, h)} → 现 ${px(p.width, p.height)}）",
                px(p.width, p.height) < px(w, h))
            assertTrue("$w x $h：两个方向都不能变大", p.width <= w && p.height <= h)
        }
    }

    @Test
    fun applyProducesExactlyThePlannedSizeAndNeverReturnsTheSource() {
        val src = bmp(2000, 3000)
        val plan = SrDownscale.plan(2000, 3000)!!
        val out = SrDownscale.apply(src, plan)
        assertEquals(plan.width, out.width)
        assertEquals(plan.height, out.height)
        assertTrue("压过就必须是另一张图（调用方靠 !== 决定要不要回收源图）", out !== src)
        assertFalse("结果不能是被回收的", out.isRecycled)
    }

    /** 分步路径（倍率 > 2 时先减半）也要落到计划尺寸，且中间产物不能泄漏成"已回收的结果"。 */
    @Test
    fun applyHandlesAMultiStepDownscale() {
        val src = bmp(4000, 3000)
        val plan = SrDownscale.plan(4000, 3000)!!
        assertTrue(
            "产物必须 ≤ 上限：${plan.width}x${plan.height}",
            plan.width.toLong() * plan.height <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertTrue("倍率 > 2 → 确实走分步减半路径", 4000 > plan.width * 2)
        val out = SrDownscale.apply(src, plan)
        assertEquals(plan.width, out.width)
        assertEquals(plan.height, out.height)
        assertFalse(out.isRecycled)
    }
}
