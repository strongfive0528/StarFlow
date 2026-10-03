package com.moe.starflow.utils

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * **阅读器弹窗配色守卫 —— 只看真实颜色**（Robolectric，无需真机）。
 *
 * 背景（用户 2026-10-03）：「浅色模式下，删除按钮的提示弹出文本也和背景完全重叠看不到」。
 * 根因是 `tintCustomView` 的旧规则：按"暗色→翻亮 / 亮色→翻暗"**机械翻转**所有中性色，
 * 于是浅色模式下的正文 `#333333`（本来就是深字，压在白底上清清楚楚）被判成"暗色"→
 * 刷成 `DIVIDER_LIGHT`（`0x11000000`，alpha 只有 6.7%）→ **标题/正文直接消失**。
 *
 * 现在规则改成**只在"看不清"时才翻，且只翻成该模式的主文字色**。这组测试把它钉死：
 * 1. 看得清的颜色**一个都不许动**；
 * 2. 看不清的必须翻成该模式主文字色，且**必须是不透明的**（绝不能是分割线那种近透明色）；
 * 3. 强调色（蓝按钮 / 红删除）在两种模式下都保留（翻掉就丢了语义）。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderDialogsTintTest {

    private fun tv(color: Int): TextView {
        val ctx = Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()
        return TextView(ctx).apply { setTextColor(color) }
    }

    private fun luminance(c: Int): Float {
        val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun assertReadable(t: TextView, dark: Boolean, why: String) {
        val c = t.currentTextColor
        assertTrue("$why：文字色必须**不透明**（alpha=0x${Integer.toHexString(Color.alpha(c))}）", Color.alpha(c) >= 0x80)
        if (dark) assertTrue("$why：深色底上必须是浅字（现在是 0x${Integer.toHexString(c)}）", luminance(c) >= 0.5f)
        else assertTrue("$why：浅色底上必须是深字（现在是 0x${Integer.toHexString(c)}）", luminance(c) < 0.5f)
    }

    @Test
    fun `浅色模式下本来就是深字的正文不许被动`() {
        val t = tv(0xFF333333.toInt())
        ReaderDialogs.tintCustomView(t, dark = false)
        assertEquals(
            "⚠️ 这条是 2026-10-03 的真 bug：浅色模式下 #333333 曾被翻成 DIVIDER_LIGHT（近透明）→ " +
                "弹窗正文和背景完全重叠、看不见。看得清的颜色必须原样保留",
            0xFF333333.toInt(),
            t.currentTextColor,
        )
    }

    @Test
    fun `深色模式下本来就是浅字的正文不许被动`() {
        val t = tv(0xFFF0F0F0.toInt())
        ReaderDialogs.tintCustomView(t, dark = true)
        assertEquals(0xFFF0F0F0.toInt(), t.currentTextColor)
    }

    @Test
    fun `浅色底上的浅字必须翻成深字`() {
        // 最典型的错配：应用/系统深色（字是浅色）但阅读背景是浅色（底是白的）
        val t = tv(0xFFFFFFFF.toInt())
        ReaderDialogs.tintCustomView(t, dark = false)
        assertReadable(t, dark = false, why = "白底白字必须被纠正")
        assertEquals(ReaderDialogs.LABEL_LIGHT, t.currentTextColor)
    }

    @Test
    fun `深色底上的深字必须翻成浅字`() {
        val t = tv(0xFF333333.toInt())
        ReaderDialogs.tintCustomView(t, dark = true)
        assertReadable(t, dark = true, why = "深底深字必须被纠正")
        assertEquals(ReaderDialogs.LABEL_DARK, t.currentTextColor)
    }

    @Test
    fun `强调色在两种模式下都必须保留`() {
        listOf(0xFF55AEEA.toInt(), 0xFFCC5555.toInt(), 0xFF34C759.toInt()).forEach { accent ->
            listOf(true, false).forEach { dark ->
                val t = tv(accent)
                ReaderDialogs.tintCustomView(t, dark)
                assertEquals(
                    "强调色 0x${Integer.toHexString(accent)}（dark=$dark）不许被翻掉：语义色一翻，" +
                        "按钮/删除的「红/蓝」就没了",
                    accent,
                    t.currentTextColor,
                )
            }
        }
    }

    @Test
    fun `tint 必须幂等`() {
        // show() 会先对整个弹窗跑一遍，调用方再对自己那个 view 跑一遍 —— 第二次不许再变色
        val t = tv(0xFFFFFFFF.toInt())
        ReaderDialogs.tintCustomView(t, dark = false)
        val once = t.currentTextColor
        ReaderDialogs.tintCustomView(t, dark = false)
        assertEquals("重复调用不许继续改色", once, t.currentTextColor)
    }

    @Test
    fun `分隔线按模式翻转（仍然允许用近透明色）`() {
        val divider = View(tv(0xFF333333.toInt()).context).apply {
            layoutParams = ViewGroup.LayoutParams(100, 2)
            layout(0, 0, 100, 2)   // tintCustomView 按 **height** 判分隔线，必须先布局
            setBackgroundColor(0xFFF0F0F0.toInt())
        }
        ReaderDialogs.tintCustomView(divider, dark = true)
        assertEquals(ReaderDialogs.DIVIDER_DARK, (divider.background as ColorDrawable).color)
        ReaderDialogs.tintCustomView(divider, dark = false)
        assertEquals(ReaderDialogs.DIVIDER_LIGHT, (divider.background as ColorDrawable).color)
    }

    @Test
    fun `容器会递归到子视图`() {
        val ctx = tv(0xFF333333.toInt()).context
        val box = FrameLayout(ctx)
        val child = TextView(ctx).apply { setTextColor(0xFFFFFFFF.toInt()) }
        box.addView(child)
        ReaderDialogs.tintCustomView(box, dark = false)
        assertReadable(child, dark = false, why = "容器里的浅字也要被纠正")
    }
}
