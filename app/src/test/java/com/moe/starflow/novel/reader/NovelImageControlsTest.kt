package com.moe.starflow.novel.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插图编辑模式的**选中效果 + 按钮条几何/命中**守卫（纯算术，无需 Robolectric）。
 *
 * 分页模式与滚动模式各画各的，但位置与命中必须同源（[NovelImageControls]）——
 * 各算一套的表现是"看着有按钮、点上去没反应"，或者更糟："点翻译却删了译文"。
 *
 * ⚠️ 用户口径 2026-10-04 修过这几条，别改回去：
 * - 「长按出现**翻译**按钮，点击翻译才 OCR 翻译」→ 还没翻过的图**只有一颗「翻译」按钮**
 * - 「按钮小一点、加点透明」→ 26dp / 底色 alpha 0x99
 * - 「选中图片要有对应的选中效果」→ 外扩 3dp 的选中框 + 半透明填充
 * - 「要考虑到多种图片尺寸，翻译按钮可能因为图片尺寸太小没法放在图片上面」
 *   → **图太小就把按钮放到图外**（图下方/上方），绝不把按钮缩到看不清
 */
class NovelImageControlsTest {

    private val density = 2f

    /** 画布/正文框：宽 600、高 900（与设备无关的合成坐标）。 */
    private fun layout(
        imgL: Float,
        imgT: Float,
        imgR: Float,
        imgB: Float,
        buttons: List<NovelImageButton> = NovelImageControls.buttonsFor(true),
    ) = NovelImageControls.layout(
        imageLeft = imgL, imageTop = imgT, imageRight = imgR, imageBottom = imgB,
        density = density, buttons = buttons,
        boundsLeft = 0f, boundsTop = 0f, boundsRight = 600f, boundsBottom = 900f,
    )

    /** 还没翻过：**只有「翻译」一颗**（用户口径：点了才会 OCR+翻译）。 */
    @Test
    fun `没译文时只有翻译一颗按钮`() {
        assertEquals(listOf(NovelImageButton.TRANSLATE), NovelImageControls.buttonsFor(hasTranslation = false))
        val items = layout(100f, 100f, 500f, 700f, NovelImageControls.buttonsFor(false))
        assertEquals(1, items.size)
        assertEquals(NovelImageButton.TRANSLATE, items[0].button)
    }

    /** 翻过了：`[重翻] [三态] [删除]`，删除在最右（离前两颗远一点，少误触）。 */
    @Test
    fun `有译文时三颗按钮且删除在最右`() {
        val items = layout(100f, 100f, 500f, 700f)
        assertEquals(
            listOf(NovelImageButton.TRANSLATE, NovelImageButton.TOGGLE, NovelImageButton.DELETE),
            items.map { it.button },
        )
        assertTrue(items[0].right < items[1].left)
        assertTrue(items[1].right < items[2].left)
    }

    /** 大图：按钮贴在图片右下角**内侧**。 */
    @Test
    fun `大图时按钮贴在图片右下角内侧`() {
        val items = layout(100f, 100f, 500f, 700f)
        val inset = NovelImageControls.INSET_DP * density
        assertEquals("最右那颗贴图片右边界（留 inset）", 500f - inset, items.last().right, 0.01f)
        assertEquals("底边也贴（留 inset）", 700f - inset, items.last().bottom, 0.01f)
        for (it in items) {
            assertTrue("按钮要落在图片内", it.left >= 100f && it.right <= 500f && it.bottom <= 700f)
        }
    }

    /**
     * **图太小 → 按钮落到图片外面**（用户口径：「翻译按钮可能因为图片尺寸太小没法放在图片上面」）。
     *
     * ⚠️ 关键是**不缩按钮**：缩到十几 dp 就点不准了。这条同时钉住"不缩"与"在图外"。
     */
    @Test
    fun `图太小的时候按钮落到图外且不缩小`() {
        // 40×40 的占位小图（电子书里满地都是）
        val items = layout(280f, 300f, 320f, 340f)
        assertEquals(3, items.size)
        val size = NovelImageControls.BUTTON_DP * density
        for (it in items) assertEquals("按钮尺寸不因图小而改变", size, it.size, 0.01f)
        assertTrue("整条必须落在图片下方（图内塞不下）", items.all { it.top >= 340f })
        assertTrue("也不能盖住图片本身", items.all { it.top > 340f - 0.01f })
        // 与图片右边缘对齐
        assertEquals("与图片右边缘对齐", 320f, items.last().right, 0.01f)
    }

    /** 图片贴着正文框底部（下方没空间）→ 按钮改放图片**上方**；两边都没有就夹回框内。 */
    @Test
    fun `下方没空间时按钮放到图片上方`() {
        val items = layout(280f, 820f, 320f, 880f)
        assertTrue("要落到图片上方", items.all { it.bottom <= 820f })
        assertTrue("同时必须在正文框内", items.all { it.top >= 0f && it.bottom <= 900f })
    }

    /** 无论怎么摆，**永远在正文框内**（不能画到屏幕外，否则用户点不到）。 */
    @Test
    fun `按钮永远夹在正文框内`() {
        val cases = listOf(
            // 左上角的小图、右下角的小图、全屏大图、极窄竖条
            floatArrayOf(0f, 0f, 20f, 20f),
            floatArrayOf(580f, 880f, 600f, 900f),
            floatArrayOf(0f, 0f, 600f, 900f),
            floatArrayOf(295f, 100f, 305f, 800f),
        )
        for (c in cases) {
            val items = layout(c[0], c[1], c[2], c[3])
            for (it in items) {
                assertTrue("左越界 ${it.left} ($c)", it.left >= 0f)
                assertTrue("上越界 ${it.top} ($c)", it.top >= 0f)
                assertTrue("右越界 ${it.right} ($c)", it.right <= 600f)
                assertTrue("下越界 ${it.bottom} ($c)", it.bottom <= 900f)
            }
        }
    }

    /** 命中测试：点在按钮内拿到对应那颗，点在别处是 null。 */
    @Test
    fun `命中测试只认按钮矩形`() {
        val items = layout(100f, 100f, 500f, 700f)
        for (it in items) {
            assertEquals(it.button, NovelImageControls.hit(items, it.centerX, it.centerY))
        }
        assertNull("图片左上角不是按钮", NovelImageControls.hit(items, 120f, 120f))
        assertNull("按钮条左边一点也不是", NovelImageControls.hit(items, items[0].left - 5f, items[0].centerY))
    }

    /** **小图的按钮也要能点**（放到图外之后命中区跟着走）。 */
    @Test
    fun `图外按钮同样可命中`() {
        val items = layout(280f, 300f, 320f, 340f)
        assertEquals(
            NovelImageButton.TRANSLATE,
            NovelImageControls.hit(items, items[0].centerX, items[0].centerY),
        )
        assertEquals(
            NovelImageButton.DELETE,
            NovelImageControls.hit(items, items[2].centerX, items[2].centerY),
        )
    }

    /** 按钮尺寸 **26dp**（用户口径「小一点」），且不随图大小变化。 */
    @Test
    fun `按钮尺寸是 26dp`() {
        assertEquals(26f, NovelImageControls.BUTTON_DP, 0.01f)
        val items = layout(100f, 100f, 500f, 700f)
        assertEquals(26f * density, items[0].size, 0.01f)
    }

    /** 选中框：在图片矩形基础上**向外扩 3dp**（图片会盖住填充，所以靠描边表达选中）。 */
    @Test
    fun `选中框外扩 3dp`() {
        val rect = NovelImageControls.selectionRect(100f, 200f, 300f, 400f, density)
        val pad = NovelImageControls.SELECTION_INFLATE_DP * density
        assertEquals(100f - pad, rect.left, 0.01f)
        assertEquals(200f - pad, rect.top, 0.01f)
        assertEquals(300f + pad, rect.right, 0.01f)
        assertEquals(400f + pad, rect.bottom, 0.01f)
    }

    /** 极窄的图也不许把按钮撑到正文框之外（正文框比按钮条还窄时才整体缩）。 */
    @Test
    fun `正文框比按钮条还窄时才整体缩`() {
        val items = NovelImageControls.layout(
            imageLeft = 0f, imageTop = 0f, imageRight = 40f, imageBottom = 40f,
            density = density, buttons = NovelImageControls.buttonsFor(true),
            boundsLeft = 0f, boundsTop = 0f, boundsRight = 100f, boundsBottom = 900f,
        )
        val total = items.last().right - items.first().left
        assertTrue("整条要缩进正文框内（100px）", total <= 100f)
        assertTrue("但仍然依次排开", items[0].right <= items[1].left + 0.01f)
    }
}
