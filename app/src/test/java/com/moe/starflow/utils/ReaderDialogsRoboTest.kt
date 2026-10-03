package com.moe.starflow.utils

import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `ReaderDialogs` 的**真机行为**守卫（Robolectric：真建弹窗、真 show）。
 *
 * 这两条对应线上崩过的两次（都是"为了配色把上下文换了"造成的）：
 *
 * | 崩法 | 原因 | 本测试怎么拦 |
 * |---|---|---|
 * | `IllegalStateException: You need to use a Theme.AppCompat theme` | 用 `createConfigurationContext()` 的裸 Context 建弹窗 → 丢掉 AppCompat 主题 | [深色浅色都能真的把弹窗显示出来] |
 * | `WindowManager$BadTokenException: token null is not valid` | 配置上下文**不挂在 Activity 窗口上** → 弹窗没有 window token | [主题化上下文必须以原 Activity 为 base] |
 *
 * ⚠️ Robolectric 的 WindowManager 比较宽松（不一定复现 token 崩溃），所以 token 那条用
 * **"baseContext 必须是原上下文"** 来断言 —— 这正是 token 的来源，且无法绕过。
 */
@RunWith(RobolectricTestRunner::class)
class ReaderDialogsRoboTest {

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).setup().get()

    @Test
    fun `深色浅色都能真的把弹窗显示出来`() {
        val a = activity()
        listOf(true, false).forEach { dark ->
            val dlg = ReaderDialogs.show(a, dark) { setMessage("color check") }
            assertNotNull("弹窗必须建出来（$dark）", dlg)
            assertTrue("弹窗必须真的显示（$dark）—— 抛异常说明主题/上下文被换坏了", dlg.isShowing)
            dlg.dismiss()
        }
    }

    @Test
    fun `主题化上下文必须以原 Activity 为 base`() {
        val a = activity()
        listOf(true, false).forEach { dark ->
            val themed = ReaderDialogs.context(a, dark)
            assertTrue("应当是包装上下文（ContextWrapper）", themed is ContextWrapper)
            assertSame(
                "base 必须是**原上下文**：用 createConfigurationContext() 的结果当 base 会丢 window token " +
                    "→ 弹窗 show 时 BadTokenException（线上崩过）",
                a,
                (themed as ContextWrapper).baseContext,
            )
        }
    }

    /**
     * **弹窗自己的文字色必须跟 `dark`（阅读背景），不能跟系统/应用主题**。
     *
     * 用户 2026-10-03：「浅色模式下，删除按钮的提示弹出文本也和背景完全重叠看不到」——
     * 底是我们按 `dark` 画的（`bg_dialog_white`），字却是主题给的；应用/系统深色时主题给的是
     * **浅色字**，压在浅色底上就是白底白字。所以这里在 **night 限定符**下要一个**浅色**弹窗，
     * 断言正文是深色。
     */
    @Test
    @Config(qualifiers = "night")
    fun `浅色阅读背景下的弹窗正文必须是深色`() {
        val a = activity()
        val dlg = ReaderDialogs.show(a, dark = false) { setMessage(MESSAGE) }
        val msg = findText(dlg.window!!.decorView, MESSAGE)
        assertNotNull("必须在弹窗里找到正文（主题/布局变了？）", msg)
        val c = msg!!.currentTextColor
        assertTrue(
            "应用深色 + 阅读背景浅色时正文曾被刷成浅色（白底白字）——现在是 0x${Integer.toHexString(c)}",
            luminance(c) < 0.5f,
        )
        dlg.dismiss()
    }

    @Test
    @Config(qualifiers = "night")
    fun `深色阅读背景下的弹窗正文必须是浅色`() {
        val a = activity()
        val dlg = ReaderDialogs.show(a, dark = true) { setMessage(MESSAGE) }
        val msg = findText(dlg.window!!.decorView, MESSAGE)
        assertNotNull("必须在弹窗里找到正文", msg)
        val c = msg!!.currentTextColor
        assertTrue(
            "深色底上正文必须看得见（现在是 0x${Integer.toHexString(c)}）",
            luminance(c) >= 0.5f,
        )
        dlg.dismiss()
    }

    private fun findText(root: View, text: String): TextView? {
        if (root is TextView && root.text?.toString() == text) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findText(root.getChildAt(i), text)?.let { return it }
        }
        return null
    }

    private fun luminance(c: Int): Float {
        val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private companion object {
        const val MESSAGE = "contrast check"
    }
}
