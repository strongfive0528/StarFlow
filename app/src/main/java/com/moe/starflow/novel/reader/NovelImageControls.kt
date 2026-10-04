package com.moe.starflow.novel.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable

/** 插图右下角那一排按钮（三态切换 / 重新翻译 / 删除译文）。 */
enum class NovelImageButton { TOGGLE, RETRANSLATE, DELETE }

/**
 * **插图按钮条**的几何与命中测试（纯算术，可普通单测）。
 *
 * 用户口径（2026-10）：「图片右下角支持三态切换、重新翻译、删除等功能」——
 * 所以按钮**画在图上**（贴图片绘制矩形的右下角内侧），而不是另开一个底部面板：
 * 一张插图可能只占半屏，用户在图上长按之后就地在图上操作，不用再抬头找浮层。
 *
 * ⚠️ 几何收敛在这里一份：分页模式（[NovelPageView]）与滚动模式
 * （[NovelScrollAdapter.ParagraphView]）各画各的，但**位置与命中必须同源** ——
 * 各算一套的话，"看着有按钮、点上去没反应"或"点在三态上却删了译文"就是这么来的。
 */
object NovelImageControls {

    /** 单个按钮的边长（dp）。 */
    const val BUTTON_DP = 34f

    /** 按钮之间、以及与图片右下角的间距（dp）。 */
    const val GAP_DP = 5f
    const val INSET_DP = 8f

    /** 按钮的绘制位置（屏幕/画布坐标，左上右下）。 */
    data class Item(
        val button: NovelImageButton,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val size: Float get() = right - left
    }

    /**
     * 按钮条的位置：贴 [right]/[bottom]（插图绘制矩形的右下角）**内侧**排一行，
     * 顺序固定 `[三态] [重翻] [删除]`（破坏性操作放最右，离前两颗远一点，少误触）。
     *
     * ⚠️ 条带整体夹在图片内：图很窄（竖长条插图）时按比例缩到图宽以内，
     * 免得按钮画到图外压住正文。
     */
    fun layout(right: Float, bottom: Float, density: Float): List<Item> {
        val size = BUTTON_DP * density
        val gap = GAP_DP * density
        val inset = INSET_DP * density
        val order = listOf(NovelImageButton.TOGGLE, NovelImageButton.RETRANSLATE, NovelImageButton.DELETE)
        var width = order.size * size + (order.size - 1) * gap
        // ⚠️ 图片比按钮条还窄 → **整体等比缩小**，绝不能设"最小尺寸"下限：
        // 一设下限就会在极窄的图（占位小图/竖长条）上撑出图外，压住正文那一列
        // （守卫 `NovelImageControlsTest.窄图里按钮条被夹在图片内` 就是这条）。
        // 缩到再小也仍然"在图内"，而看不清的极小图本来也不值得点。
        val avail = (right - inset * 2).coerceAtLeast(1f)
        val scale = if (width > avail) avail / width else 1f
        val s = size * scale
        val g = gap * scale
        width = order.size * s + (order.size - 1) * g
        val left0 = right - inset - width
        val top = (bottom - inset - s).coerceAtLeast(0f)
        return order.mapIndexed { i, b ->
            val l = left0 + i * (s + g)
            Item(b, l, top, l + s, top + s)
        }
    }

    /** 命中了哪颗按钮（没命中 = null：调用方按"点了图片其他位置"处理）。 */
    fun hit(items: List<Item>, x: Float, y: Float): NovelImageButton? =
        items.firstOrNull { it.contains(x, y) }?.button

    /**
     * 画按钮条的底：每颗按钮一个**圆角方块**（半透明深色，与右下角翻译浮层组同一观感）。
     * 图标由调用方按 [Item] 的矩形自己画（各模式手里的 Drawable 来源不同）。
     */
    fun drawBackdrop(canvas: Canvas, items: List<Item>, density: Float) {
        val bg = backdropPaint
        bg.color = BACKDROP_COLOR
        val r = 8f * density
        for (it in items) canvas.drawRoundRect(it.left, it.top, it.right, it.bottom, r, r, bg)
    }

    /** 把 [icon] 画进 [item] 的矩形（居中、留 6dp 内边距）。 */
    fun drawIcon(canvas: Canvas, item: Item, icon: Drawable?, density: Float) {
        if (icon == null) return
        val pad = 6f * density
        val side = (item.size - pad * 2).coerceAtLeast(1f)
        val cx = item.centerX
        val cy = item.centerY
        icon.setBounds(
            (cx - side / 2f).toInt(),
            (cy - side / 2f).toInt(),
            (cx + side / 2f).toInt(),
            (cy + side / 2f).toInt(),
        )
        icon.draw(canvas)
    }

    /** 半透明深色底（与 `bg_reader_translate_group` 的观感一致）。 */
    private const val BACKDROP_COLOR = 0xCC1E1E22.toInt()

    /** ⚠️ `by lazy`：纯几何单测在普通 JVM 里跑，类加载时碰 `Paint` 会 "not mocked"。 */
    private val backdropPaint: Paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
}
