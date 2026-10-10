package com.moe.starflow.novel.reader

import com.moe.starflow.manga.types.TextDirection
import com.moe.starflow.novel.translate.NovelImageTextBlock
import com.moe.starflow.novel.translate.NovelImageTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 载荷 → 漫画渲染器输入（`TranslatedBubble`）的映射守卫。
 *
 * 这是"三态"的最后一环：译文态画 `translatedText`，识别原文态画 `originalText`
 * （`useOriginalText = true` 时渲染器只认后者）。映射错了的表现是
 * 「切到原文态还是译文」或「竖排插图被按横排画」—— 都只能在真机上肉眼看出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelImageOverlayTest {

    private val payload = NovelImageTranslation(
        sourceLang = "ja",
        targetLang = "zh",
        ocrEngine = "ppocrv6",
        blocks = listOf(
            NovelImageTextBlock(10, 20, 110, 60, "こんにちは", "你好", vertical = false, fontSize = 18f),
            NovelImageTextBlock(200, 30, 260, 300, "第一話", "第一话", vertical = true, fontSize = 24f),
        ),
    )

    /** 译文态：矩形/字号/竖排都来自载荷，文字用译文。 */
    @Test
    fun `译文态映射矩形字号与竖排`() {
        val bubbles = NovelImageOverlay.bubblesOf(payload, useOriginal = false)
        assertEquals(2, bubbles.size)
        assertEquals(10, bubbles[0].rect.left)
        assertEquals(20, bubbles[0].rect.top)
        assertEquals(110, bubbles[0].rect.right)
        assertEquals(60, bubbles[0].rect.bottom)
        assertEquals(18f, bubbles[0].fontSize, 0.01f)
        assertEquals("你好", bubbles[0].translatedText)
        assertEquals("こんにちは", bubbles[0].originalText)
        assertEquals("竖排块要按竖排画", TextDirection.VERTICAL_RL, bubbles[1].direction)
        assertEquals("横排块不能画成竖排", TextDirection.HORIZONTAL, bubbles[0].direction)
    }

    /**
     * **原文态画的是 OCR 原文**（`useOriginalText = true` 时渲染器只认 `originalText`，
     * 这里把 `translatedText` 也一并换成原文，语义与漫画 `renderBubbles` 一致）。
     */
    @Test
    fun `原文态用识别出的原文`() {
        val bubbles = NovelImageOverlay.bubblesOf(payload, useOriginal = true)
        assertEquals("こんにちは", bubbles[0].translatedText)
        assertEquals("第一話", bubbles[1].translatedText)
        assertTrue("矩形不能因为切态而变", bubbles[1].rect.top == 30)
    }

    // ===== 覆盖层的超采样倍率（真机反馈：小尺寸插图的译文糊）=====
    //
    // 用户口径 2026-10-04：「渲染回图片的译文非常不清晰，图片尺寸小，但是你的翻译文本没必要
    // 也为了贴合原图搞得那么模糊」。根因：插图允许放大到 3×，而覆盖层仍按**底图**分辨率
    // 光栅化，拉上去的字形是插值出来的像素。修法与漫画那条一致（`manga/CLAUDE.md`
    // 「低分辨率漫画译文模糊」）：让文字在"已被放大的坐标系"里落笔。

    /** 底图够大（画的时候是缩小）→ 不放大，倍率 1（老行为、零内存增量）。 */
    @Test
    fun `底图够大时不超采样`() {
        assertEquals(1f, NovelImageOverlay.renderScaleFor(drawnWidth = 900, baseWidth = 900), 0.001f)
        assertEquals(1f, NovelImageOverlay.renderScaleFor(drawnWidth = 800, baseWidth = 1400), 0.001f)
    }

    /** 小图被放大显示 → 按比例超采样（文字按最终尺寸光栅化，才不糊）。 */
    @Test
    fun `小图被放大时按比例超采样`() {
        assertEquals(1.5f, NovelImageOverlay.renderScaleFor(drawnWidth = 600, baseWidth = 400), 0.001f)
    }

    /** 封顶 2×（×2 = 4 倍像素，而输出还进 48MB 的 LRU）。 */
    @Test
    fun `超采样封顶两倍`() {
        assertEquals(2f, NovelImageOverlay.renderScaleFor(drawnWidth = 1200, baseWidth = 400), 0.001f)
        assertEquals(2f, NovelImageOverlay.renderScaleFor(drawnWidth = 1080, baseWidth = 1), 0.001f)
    }

    /** 底图宽取不到（0）也不能除零/炸掉。 */
    @Test
    fun `底图宽为 0 时兜底`() {
        assertEquals(2f, NovelImageOverlay.renderScaleFor(drawnWidth = 900, baseWidth = 0), 0.001f)
        assertEquals(1f, NovelImageOverlay.renderScaleFor(drawnWidth = 0, baseWidth = 900), 0.001f)
    }
}
