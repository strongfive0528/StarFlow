package com.moe.starflow.novel.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 插图**编辑模式**的接线守卫（源码级，纯 JVM）。
 *
 * ⚠️ 为什么用源码断言：这几条的失效方式全是**静默**的 —— 长按又会自己开跑、退出编辑模式
 * 顺手把在途翻译取消掉、按钮没收干净。不崩不报错，单测也照过，只有人肉点一遍才发现。
 * 与 `SrRecordSystemWiringTest` / `NovelReaderTranslateRulesTest` 同一手法。
 *
 * 用户口径（2026-10-04）：
 * 1. 「长按出现翻译按钮，**点击翻译才会 OCR 翻译**」
 * 2. 「翻译过程中也可以快速返回退出图片编辑模式，**翻译不会因为退出编辑模式而暂停**，
 *    退出阅读器会自动暂停」
 */
class NovelImageEditWiringTest {

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    private val activity by lazy { read("src/main/java/com/moe/starflow/novel/reader/NovelReaderActivity.kt") }

    /** 截出某个函数体（从签名到下一个 `\n    private fun` / `\n    fun` / `\n    internal fun`）。 */
    private fun bodyOf(signature: String): String {
        val start = activity.indexOf(signature)
        assertTrue("找不到函数：$signature", start >= 0)
        val rest = activity.substring(start + signature.length)
        val next = listOf("\n    private fun ", "\n    internal fun ", "\n    fun ", "\n    override fun ")
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        return rest.substring(0, next)
    }

    /** 长按插图**只进编辑模式**：不许直接把翻译开出去（用户口径 1）。 */
    @Test
    fun `长按插图不开翻`() {
        val body = bodyOf("private fun onLongPressImage(")
        assertFalse("长按不许直接开翻（要等用户点那颗「翻译」按钮）", body.contains("translateImageNow("))
        assertTrue("长按要把这张图设为编辑目标", body.contains("focusedImagePara = paraIndex"))
    }

    /** 编辑模式里点「翻译」按钮才真的开翻。 */
    @Test
    fun `编辑模式里点翻译按钮开翻`() {
        val body = bodyOf("private fun handleTap(")
        assertTrue(
            "按钮路由里必须有 TRANSLATE → translateImageNow",
            body.contains("NovelImageButton.TRANSLATE -> translateImageNow("),
        )
    }

    /**
     * **退出编辑模式不许打断在途翻译**（用户口径 2）：
     * 点别处 / 翻页这两条只清聚焦，绝不 `imageJob?.cancel()`。
     */
    @Test
    fun `退出编辑模式不取消在途翻译`() {
        val tap = bodyOf("private fun handleTap(")
        val turn = bodyOf("private fun turnPage(")
        for ((name, body) in listOf("handleTap" to tap, "turnPage" to turn)) {
            assertTrue("$name 里要清聚焦（按钮跟着收掉）", body.contains("focusedImagePara = null"))
            assertFalse("$name 不许取消在途翻译（那样退出编辑模式就把翻译停了）", body.contains("imageJob?.cancel()"))
        }
    }

    /** **退出阅读器自动停**：`onStop` 必须取消在途的图片翻译。 */
    @Test
    fun `退出阅读器会取消在途图片翻译`() {
        val body = bodyOf("override fun onStop(")
        assertTrue("onStop 要取消在途图片翻译", body.contains("imageJob?.cancel()"))
    }

    /** 按钮条只在**有聚焦图**时才画/才命中（"退出编辑模式隐藏相关按钮"的兜底判据）。 */
    @Test
    fun `没聚焦时不画也不命中按钮`() {
        val pageView = read("src/main/java/com/moe/starflow/novel/reader/NovelPageView.kt")
        val controls = pageView.substring(pageView.indexOf("internal fun controlsOf()"))
            .take(400)
        assertTrue("拿不到聚焦图就直接返回 null", controls.contains("val focus = focusedImage ?: return null"))
        assertTrue("画之前也要判聚焦", pageView.contains("if (focused) {"))
    }
}
