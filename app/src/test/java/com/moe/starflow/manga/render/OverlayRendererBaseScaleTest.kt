package com.moe.starflow.manga.render

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `OverlayRenderer.renderOverlay(baseScale = …)` 的输出尺寸契约（超分底图的地基）。
 *
 * ⚠️ **只能断言尺寸，不能断言像素**：Robolectric 的 Canvas **既不兑现 `canvas.scale()`，
 * 也不兑现 `drawBitmap(src, srcRect, dstRect, paint)` 的目标矩形**（见 `manga/CLAUDE.md`）。
 * 但尺寸恰好就是这条契约的全部内容 —— 底图 2x 时"输出有多大"决定了坐标映射对不对。
 *
 * 三行真值（`spaceW = original.width / baseScale`，`outW = spaceW * renderScale`）：
 *
 * | 底图 | baseScale | renderScale | 输出 | 含义 |
 * |---|---|---|---|---|
 * | 1x 原图 | 1 | 2 | 2×源图 | 今天的行为（文字超采样） |
 * | **2x 超分图** | **2** | **2** | **= 底图本身** | **超分底图 1:1 零重采样** |
 * | 2x 超分图 | 2 | 1 | = 源图 | 超分图降回源尺寸渲染 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OverlayRendererBaseScaleTest {

    private fun src(w: Int = 400, h: Int = 600): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun originalBase_renderScale2_isTwiceSource() {
        val out = OverlayRenderer.renderOverlay(
            original = src(), regions = emptyList(), renderScale = 2f, baseScale = 1f
        )
        assertEquals(800, out.width)
        assertEquals(1200, out.height)
    }

    @Test
    fun srBase_renderScale2_isExactlyTheBaseItself() {
        // 底图是精确 2x（800x1200），renderScale 也是 2 → 输出必须**正好等于底图尺寸**，
        // 也就是底图 1:1 落上去、零重采样。这条破了 = 超分图会被二次重采样（白掉画质）。
        val out = OverlayRenderer.renderOverlay(
            original = src(800, 1200), regions = emptyList(), renderScale = 2f, baseScale = 2f
        )
        assertEquals(800, out.width)
        assertEquals(1200, out.height)
    }

    @Test
    fun srBase_renderScale1_isSourceSize() {
        val out = OverlayRenderer.renderOverlay(
            original = src(800, 1200), regions = emptyList(), renderScale = 1f, baseScale = 2f
        )
        assertEquals(400, out.width)
        assertEquals(600, out.height)
    }

    @Test
    fun baseScaleDefaultsToIdentity_noRegression() {
        val a = OverlayRenderer.renderOverlay(original = src(), regions = emptyList(), renderScale = 1f)
        assertEquals(400, a.width)
        assertEquals(600, a.height)
    }

    // ---------- 画布几何（纯函数，锁住"叠加层会被放大多少"） ----------

    /**
     * ⚠️ **`fillBase == false` 绝不等于"不用缩放叠加层"**。
     *
     * 超分底图那一路（`baseScale == renderScale == 2`）`outW` 恰好等于底图宽 → 走"直接 copy 底图"
     * 分支，但叠加层仍然必须按 `scale = 2` 放大。早先的实现把 `canvas.scale()` 放进了
     * `if (需要铺底图)` 里 → 这一路漏掉缩放：底图铺满整张，译文却缩在左上角 1/4、位置全错。
     * Robolectric 不兑现 `canvas.scale`，所以这条只能靠几何数据钉住。
     */
    @Test
    fun srBase_scaleIsStillAppliedEvenThoughBaseIsNotRedrawn() {
        val g = solveOverlayCanvas(bitmapW = 800, bitmapH = 1200, baseScale = 2f, renderScale = 2f)
        assertEquals("坐标空间 = 底图 / baseScale", 400, g.spaceW)
        assertEquals(600, g.spaceH)
        assertEquals("输出 = 坐标空间 × renderScale", 800, g.outW)
        assertEquals(1200, g.outH)
        assertFalse("输出正好等于底图 → 不需要重画底图（1:1 零重采样）", g.fillBase)
        assertEquals("但叠加层**仍要**放大 2 倍", 2f, g.scale, 0.0001f)
    }

    @Test
    fun originalBase_needsBaseRedrawAndScale() {
        val g = solveOverlayCanvas(bitmapW = 400, bitmapH = 600, baseScale = 1f, renderScale = 2f)
        assertEquals(400, g.spaceW)
        assertEquals(800, g.outW)
        assertTrue("底图比输出小 → 必须按目标矩形铺满", g.fillBase)
        assertEquals(2f, g.scale, 0.0001f)
    }

    @Test
    fun identityCase_neitherRedrawNorScale() {
        val g = solveOverlayCanvas(bitmapW = 400, bitmapH = 600, baseScale = 1f, renderScale = 1f)
        assertEquals(400, g.outW)
        assertEquals(600, g.outH)
        assertFalse(g.fillBase)
        assertEquals("倍率 1 = 恒等变换", 1f, g.scale, 0.0001f)
    }

    @Test
    fun renderScaleIsClampedToMax() {
        // ×2 是 4 倍像素，防 OOM —— 传 5 也只能到 2
        val g = solveOverlayCanvas(bitmapW = 400, bitmapH = 600, baseScale = 1f, renderScale = 5f)
        assertEquals(2f, g.scale, 0.0001f)
        assertEquals(800, g.outW)
    }

    @Test
    fun srBaseWithRenderScale1_shrinksToSourceSize() {
        val g = solveOverlayCanvas(bitmapW = 800, bitmapH = 1200, baseScale = 2f, renderScale = 1f)
        assertEquals(400, g.outW)
        assertEquals(600, g.outH)
        assertTrue("2x 底图要缩到源尺寸 → 得重画", g.fillBase)
        assertEquals(1f, g.scale, 0.0001f)
    }

    // ---------- 上屏倍率必须 ≥ 底图倍率（2026-10 真机：超分有记录但两态切换毫无变化） ----------

    /**
     * **这就是那个真机 bug 的算式**（PDF 页 1650×2146、超分底图 2160×2808、屏宽 1220）：
     *
     * ```
     * 显示倍率 = 1220/1650 → 夹到 1
     * 若照它渲染：outW = 坐标空间 1650 × 1 = 1650 < 底图宽 2160 → fillBase=true
     *            → 2160 的超分底图被 drawBitmap **重采样压回 1650** → 超分像素全丢 → 看起来没变
     * 抬到 baseScale(1.309) 后：outW = 2160 == 底图宽 → fillBase=false → 1:1 零重采样
     * ```
     */
    @Test
    fun srBase_mustRaiseTheRenderScale_otherwiseTheBaseIsResampledAway() {
        val baseScale = 2160f / 1650f
        val display = 1f // 1220px 屏 / 1650px 页 → 夹到 1

        val before = solveOverlayCanvas(bitmapW = 2160, bitmapH = 2808, baseScale = baseScale, renderScale = display)
        assertEquals("旧行为：输出只有源图那么大", 1650, before.outW)
        assertTrue("旧行为要把超分底图压回去（等于白超）", before.fillBase)

        val fixed = readerRenderScale(display, baseScale)
        assertEquals("倍率必须抬到底图倍率", baseScale, fixed, 0.0001f)
        val after = solveOverlayCanvas(bitmapW = 2160, bitmapH = 2808, baseScale = baseScale, renderScale = fixed)
        assertEquals("输出正好等于底图宽", 2160, after.outW)
        assertEquals(2808, after.outH)
        assertFalse("底图 1:1 落上去、零重采样", after.fillBase)
    }

    @Test
    fun readerRenderScale_keepsDisplayScaleWithoutBase_andClampsBothEnds() {
        // 没有超分底图 → 与原行为完全一致（低分辨率页仍按显示倍率超采样文字）
        assertEquals(1.35f, readerRenderScale(1.35f, 1f), 0.0001f)
        // 4x 底图 → 夹到上限（×2 已是 4 倍像素，防 OOM）
        assertEquals(2f, readerRenderScale(1f, 4f), 0.0001f)
        assertEquals(1f, readerRenderScale(1f, 1f), 0.0001f)
        assertEquals("下界恒为 1（不缩）", 1f, readerRenderScale(0.5f, 1f), 0.0001f)
        assertEquals("显示倍率比底图倍率大时取显示倍率", 1.8f, readerRenderScale(1.8f, 1.2f), 0.0001f)
    }
}
