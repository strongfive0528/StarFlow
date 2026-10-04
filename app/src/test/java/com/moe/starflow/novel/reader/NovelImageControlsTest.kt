package com.moe.starflow.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插图编辑模式按钮条的**几何与命中**守卫（纯算术，无需 Robolectric）。
 *
 * 分页模式与滚动模式各画各的，但位置与命中必须同源（[NovelImageControls]）——
 * 各算一套的表现是"看着有按钮、点上去没反应"，或者更糟："点翻译却删了译文"。
 *
 * ⚠️ 用户口径 2026-10-04 修过两条，别改回去：
 * - 「长按出现**翻译**按钮，点击翻译才 OCR 翻译」→ 还没翻过的图**只有一颗「翻译」按钮**
 *   （拆出"重新翻译"那颗会让第一次进来的人点到一颗什么都不做的按钮）
 * - 「按钮小一点、加点透明」→ 26dp / 底色 alpha 0x99（本测试钉尺寸，alpha 在源码常量里）
 */
class NovelImageControlsTest {

    private val density = 2f

    /** 还没翻过：**只有「翻译」一颗**（用户口径：点了才会 OCR+翻译）。 */
    @Test
    fun `没译文时只有翻译一颗按钮`() {
        assertEquals(listOf(NovelImageButton.TRANSLATE), NovelImageControls.buttonsFor(hasTranslation = false))
        val items = NovelImageControls.layout(600f, 800f, density, NovelImageControls.buttonsFor(false))
        assertEquals(1, items.size)
        assertEquals(NovelImageButton.TRANSLATE, items[0].button)
    }

    /** 翻过了：`[重翻] [三态] [删除]`，删除在最右（离前两颗远一点，少误触）。 */
    @Test
    fun `有译文时三颗按钮且删除在最右`() {
        assertEquals(
            listOf(NovelImageButton.TRANSLATE, NovelImageButton.TOGGLE, NovelImageButton.DELETE),
            NovelImageControls.buttonsFor(hasTranslation = true),
        )
    }

    /** 三颗按钮贴在图片右下角**内侧**。 */
    @Test
    fun `按钮贴在图片右下角内侧`() {
        val items = NovelImageControls.layout(right = 600f, bottom = 800f, density = density)
        val inset = NovelImageControls.INSET_DP * density
        val last = items.last()
        assertEquals("最右那颗要贴右边界（留 inset）", 600f - inset, last.right, 0.01f)
        for (it in items) {
            assertTrue("按钮必须完全落在图片内（不能压到正文）", it.left >= 0f && it.right <= 600f)
            assertTrue(it.top >= 0f && it.bottom <= 800f)
        }
        // 三颗等宽等高、间距一致、按顺序从左到右
        assertEquals(items[0].size, items[1].size, 0.01f)
        assertTrue(items[0].right < items[1].left)
        assertTrue(items[1].right < items[2].left)
    }

    /** 按钮**比原来小**（用户口径「小一点」）：26dp，而不是 34dp。 */
    @Test
    fun `按钮尺寸是 26dp`() {
        assertEquals(26f, NovelImageControls.BUTTON_DP, 0.01f)
        val items = NovelImageControls.layout(600f, 800f, density)
        assertEquals(26f * density, items[0].size, 0.01f)
    }

    /** 命中测试：点在按钮内拿到对应那颗，点在别处是 null（= "点了图片其它位置"）。 */
    @Test
    fun `命中测试只认按钮矩形`() {
        val items = NovelImageControls.layout(600f, 800f, density)
        assertEquals(
            NovelImageButton.TRANSLATE,
            NovelImageControls.hit(items, items[0].centerX, items[0].centerY),
        )
        assertEquals(
            NovelImageButton.DELETE,
            NovelImageControls.hit(items, items[2].centerX, items[2].centerY),
        )
        assertNull("图片左上角不是按钮", NovelImageControls.hit(items, 10f, 10f))
        assertNull("按钮条左边一点也不是", NovelImageControls.hit(items, items[0].left - 5f, items[0].centerY))
    }

    /** 很窄的插图：按钮条整体缩到图宽以内（不能画到图外压住正文）。 */
    @Test
    fun `窄图里按钮条被夹在图片内`() {
        val items = NovelImageControls.layout(right = 60f, bottom = 400f, density = density)
        assertTrue(items.isNotEmpty())
        for (it in items) {
            assertTrue("左边界越界: ${it.left}", it.left >= 0f)
            assertTrue("右边界越界: ${it.right}", it.right <= 60f)
        }
        assertTrue(items[0].right <= items[1].left + 0.01f)
    }

    /** 按钮条只占右下角一小块，不会把整张图盖住。 */
    @Test
    fun `按钮条不高过一颗按钮`() {
        val items = NovelImageControls.layout(600f, 800f, density)
        val h = items[0].bottom - items[0].top
        assertTrue("按钮高度应远小于图高", h < 800f / 4f)
        assertTrue(items.all { kotlin.math.abs((it.bottom - it.top) - h) < 0.01f })
    }
}
