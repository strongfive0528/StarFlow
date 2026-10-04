package com.moe.starflow.novel.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable

/**
 * 插图编辑模式里那一排按钮。
 *
 * ⚠️ **没有单独的「重新翻译」按钮**：翻译与重翻是同一个动作的两个时机
 * （还没翻过 → 翻译；翻过了 → 重翻），合成一颗按钮后图标随状态变即可 ——
 * 拆成两颗会让"第一次进编辑模式"时出现一颗**点了什么都不会发生**的按钮。
 */
enum class NovelImageButton {
    /** 翻译 / 重新翻译（图标按"有没有译文"变）。 */
    TRANSLATE,

    /** 三态切换（译文 / 识别原文 / 原图）——**只在有译文时才出现**。 */
    TOGGLE,

    /** 删除这张图的译文（回落原图）——**只在有译文时才出现**。 */
    DELETE,
}

/**
 * **插图按钮条**的几何与命中测试（纯算术，可普通单测）。
 *
 * 用户口径（2026-10）：
 * - 「长按图片 → **出现翻译按钮**，点击翻译才会 OCR 翻译」（长按**不**自动开翻）
 * - 「图片右下角的按钮**小一点、加点透明**」
 * - 「**退出编辑模式隐藏**相关按钮」
 * 所以按钮**画在图上**（贴图片绘制矩形的右下角内侧），而不是另开一个底部面板。
 *
 * ⚠️ 几何收敛在这里一份：分页模式（[NovelPageView]）与滚动模式
 * （[NovelScrollAdapter.ParagraphView]）各画各的，但**位置与命中必须同源** ——
 * 各算一套的话，"看着有按钮、点上去没反应"或"点在三态上却删了译文"就是这么来的。
 */
object NovelImageControls {

    /**
     * 单个按钮的边长（dp）。
     *
     * 用户口径「小一点」：34dp → **26dp**（约 4.6mm，仍在可点范围内，但不至于压住插图内容）。
     */
    const val BUTTON_DP = 26f

    /** 按钮之间、以及与图片右下角的间距（dp）。 */
    const val GAP_DP = 4f
    const val INSET_DP = 6f

    /** 图标在按钮里的内边距（dp）：26dp 的按钮留 5dp，图标 16dp。 */
    private const val ICON_PAD_DP = 5f

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
     * 当前该显示哪几颗按钮（**顺序 = 从左到右**）。
     *
     * - 还没翻过：只有「翻译」
     * - 翻过了：`[重翻] [三态] [删除]` —— 破坏性操作放最右，离前两颗远一点，少误触
     */
    fun buttonsFor(hasTranslation: Boolean): List<NovelImageButton> =
        if (hasTranslation) {
            listOf(NovelImageButton.TRANSLATE, NovelImageButton.TOGGLE, NovelImageButton.DELETE)
        } else {
            listOf(NovelImageButton.TRANSLATE)
        }

    /**
     * 按钮条的位置：贴 [right]/[bottom]（插图绘制矩形的右下角）**内侧**排一行。
     *
     * ⚠️ 条带整体夹在图片内：图很窄（竖长条插图）时按比例缩到图宽以内，
     * 免得按钮画到图外压住正文。
     */
    fun layout(
        right: Float,
        bottom: Float,
        density: Float,
        buttons: List<NovelImageButton> = buttonsFor(hasTranslation = true),
    ): List<Item> {
        if (buttons.isEmpty()) return emptyList()
        val size = BUTTON_DP * density
        val gap = GAP_DP * density
        val inset = INSET_DP * density
        var width = buttons.size * size + (buttons.size - 1) * gap
        // ⚠️ 图片比按钮条还窄 → **整体等比缩小**，绝不能设"最小尺寸"下限：
        // 一设下限就会在极窄的图（占位小图/竖长条）上撑出图外，压住正文那一列
        // （守卫 `NovelImageControlsTest.窄图里按钮条被夹在图片内` 就是这条）。
        val avail = (right - inset * 2).coerceAtLeast(1f)
        val scale = if (width > avail) avail / width else 1f
        val s = size * scale
        val g = gap * scale
        width = buttons.size * s + (buttons.size - 1) * g
        val left0 = right - inset - width
        val top = (bottom - inset - s).coerceAtLeast(0f)
        return buttons.mapIndexed { i, b ->
            val l = left0 + i * (s + g)
            Item(b, l, top, l + s, top + s)
        }
    }

    /** 命中了哪颗按钮（没命中 = null：调用方按"点了图片其他位置"处理）。 */
    fun hit(items: List<Item>, x: Float, y: Float): NovelImageButton? =
        items.firstOrNull { it.contains(x, y) }?.button

    /**
     * 画按钮条的底：每颗按钮一个**圆角方块**。
     *
     * 用户口径「加点透明」：底色 alpha 从 `0xCC`(80%) 降到 **`0x99`(60%)** ——
     * 压在插图上看得出按钮在哪，又不至于把图上的内容盖死。
     */
    fun drawBackdrop(canvas: Canvas, items: List<Item>, density: Float) {
        val bg = backdropPaint
        bg.color = BACKDROP_COLOR
        val r = 7f * density
        for (it in items) canvas.drawRoundRect(it.left, it.top, it.right, it.bottom, r, r, bg)
    }

    /** 把 [icon] 画进 [item] 的矩形（居中、留 [ICON_PAD_DP] 内边距）。 */
    fun drawIcon(canvas: Canvas, item: Item, icon: Drawable?, density: Float) {
        if (icon == null) return
        val pad = ICON_PAD_DP * density
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

    /** 半透明深色底（与 `bg_reader_translate_group` 同一观感，但更透一点：60%）。 */
    private const val BACKDROP_COLOR = 0x991E1E22.toInt()

    /** ⚠️ `by lazy`：纯几何单测在普通 JVM 里跑，类加载时碰 `Paint` 会 "not mocked"。 */
    private val backdropPaint: Paint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
}
