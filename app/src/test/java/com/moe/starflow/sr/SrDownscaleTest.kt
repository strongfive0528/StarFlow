package com.moe.starflow.sr

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 大图超分前的**压缩计划**（2026-10，第二轮真机反馈后修订）。
 *
 * 用户最初口径：
 * > 「对较大尺寸的图片进行超分，短边压缩对齐到 1080p 再超分……减轻超分模型的压力，
 * >  同时可以快速通过超分模型放大尺寸」
 *
 * ⚠️ **第二轮真机反馈后作废了「短边一律 1080」这条**（用户：「超分完全没效果，我看到的始终是原图」）：
 * 他的 PDF 页 raster 是 1646×2146、屏幕宽 1220，压到短边 1080（**比屏幕还小**）再 2×，
 * 产物里的真实信息只有 1080px ⇒ 整页显示时**比原图更软**。现在的规则是
 * **只受引擎输入上限约束**：能按原尺寸喂就按原尺寸，超了才等比缩到吃得下。
 *
 * ⚠️ 注意这条只约束**喂进引擎的那张压缩图**。**产物原样落盘、不做任何额外缩放**
 * （「超分后肯定比原图大啊」）—— 见 `SrDownscale` 类注释与
 * `SrReaderWiringTest.theUpscaledProductIsStoredAsIs`。
 *
 * ⚠️ Robolectric 的 Canvas **不真正栅格化**（见 `manga/CLAUDE.md`），所以这里只断言
 * **尺寸与所有权**，不断言像素内容 —— 尺寸恰恰是这块功能唯一需要正确的东西。
 */
@RunWith(RobolectricTestRunner::class)
class SrDownscaleTest {

    private fun bmp(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun px(w: Int, h: Int): Long = w.toLong() * h

    @Test
    fun smallPageNeedsNoDownscale() {
        assertNull("短边 912、0.58MP → 吃得下，走原路（普通漫画页零行为变化）",
            SrDownscale.plan(632, 912))
        assertNull("1.75MP 也吃得下（引擎输入上限 2.5MP）", SrDownscale.plan(1080, 1620))
        assertNull("非法尺寸不压", SrDownscale.plan(0, 100))
        assertNull("非法尺寸不压（负）", SrDownscale.plan(-5, 100))
    }

    /**
     * ⚠️ 这条是**实测出来的设计依据**：`NcnnSrEngine.maxInputPixels = 10MP / scale²`（2x → 2.5MP）。
     * 2000x3000 = 6MP **今天就已经超限**，引擎直接判「图太大」——用户看到的"图太大无法超分"就是它。
     *
     * ⚠️ 期望值在 2026-10 第二轮修订过：以前是「短边对齐 1080 → 1080×1620」，
     * 现在是「**只缩到引擎吃得下** → 1291×1936」。别再按 1080 写回去 ——
     * 那正是「超分看不出效果」的根因（输入分辨率低于显示密度 = 白做）。
     */
    @Test
    fun aPageThatWouldBlowTheEngineCapGetsCompressed() {
        val p = SrDownscale.plan(2000, 3000)!!
        assertEquals("只按引擎上限等比缩（不再压到短边 1080）", 1290, p.width)
        assertEquals("长边等比", 1936, p.height)
        assertTrue(
            "压缩后必须落进引擎的 2.5MP 输入上限，否则超分照样出不来",
            px(p.width, p.height) <= SrDownscale.DEFAULT_MAX_FEED_PIXELS,
        )
        assertTrue(
            "压缩图**必须比原图小**（用户口径：给超分的压缩图不超过原来的像素）",
            px(p.width, p.height) < px(2000, 3000),
        )
        assertTrue(
            "⚠️ 而且**尽量少缩**：输入像素要贴住上限（旧 1080 规则只喂 1.52MP，白扔掉 40% 信息）",
            px(p.width, p.height) >= SrDownscale.DEFAULT_MAX_FEED_PIXELS * 99 / 100,
        )
        // 横过来：结果对称
        val q = SrDownscale.plan(3000, 2000)!!
        assertEquals(1936, q.width)
        assertEquals(1290, q.height)
    }

    /** 短边本来就 ≤ 1080、但像素数仍然超上限的长条：**要按上限继续缩**。 */
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
        assertTrue("比 2x 引擎压得更狠", p.width < 1080)
    }

    @Test
    fun extremeAspectIsStillBroughtUnderThePixelCap() {
        // 20000x300 的短边只有 300（不触发"短边 1080"那一条），但 6MP 超了引擎的 2.5MP 上限 ——
        // 所以**仍然要压**，只是压的依据换成像素上限。压出来还是 66:1 的长条，但吃得下。
        val p = SrDownscale.plan(20000, 300)!!
        assertTrue(
            "超过引擎上限就必须压（哪怕短边很小）",
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
        // 12MP → 贴住 2.5MP 上限（不再是旧规则的下采样到短边 1080）
        assertEquals(1825, plan.width)
        assertEquals(1369, plan.height)
        val out = SrDownscale.apply(src, plan)
        assertEquals(plan.width, out.width)
        assertEquals(plan.height, out.height)
        assertFalse(out.isRecycled)
    }
}
