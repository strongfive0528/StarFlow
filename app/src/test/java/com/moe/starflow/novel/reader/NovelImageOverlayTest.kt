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
}
