package com.moe.starflow.novel.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 图片译文载荷的编解码守卫。
 *
 * ⚠️ 要 Robolectric：`org.json` 在纯 JVM 单测里是**桩**（方法与真实 Android 不一致）。
 *
 * 这份载荷是"把译文画回图上"的唯一来源（矩形 + OCR 原文 + 译文），
 * 编解码错了的表现是"图上的译文跑到别处/整张图不显示覆盖层"——都是静默的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelImagePayloadCodecTest {

    private fun payload() = NovelImageTranslation(
        sourceLang = "ja",
        targetLang = "zh",
        ocrEngine = "ppocrv6",
        blocks = listOf(
            NovelImageTextBlock(10, 20, 110, 60, "こんにちは", "你好", vertical = false, fontSize = 18f),
            NovelImageTextBlock(200, 30, 260, 300, "第一話", "第一话", vertical = true, fontSize = 24f),
        ),
    )

    @Test
    fun `编解码往返一致`() {
        val inPayload = payload()
        val out = NovelImagePayloadCodec.decode(NovelImagePayloadCodec.encode(inPayload))
        assertEquals(inPayload, out)
    }

    /** 拼接出来的两列（导出/进度/「已翻」判据都吃它们）。 */
    @Test
    fun `拼接原文与译文按块顺序换行`() {
        val p = payload()
        assertEquals("こんにちは\n第一話", p.sourceText)
        assertEquals("你好\n第一话", p.translatedText)
    }

    /** 历史数据 / 半截写入 / 别的字段蹭脏：一律返回 null，让调用方回落成"这张图没翻过"。 */
    @Test
    fun `坏载荷返回 null 而不是抛异常`() {
        assertNull(NovelImagePayloadCodec.decode(null))
        assertNull(NovelImagePayloadCodec.decode(""))
        assertNull(NovelImagePayloadCodec.decode("不是 json"))
        assertNull(NovelImagePayloadCodec.decode("{}"))
        assertNull(NovelImagePayloadCodec.decode("""{"blocks":[]}"""))
        assertNull(NovelImagePayloadCodec.decode("""{"blocks":[{"l":1}]}"""))
    }

    /** 没有文字的块（OCR 噪声）不算块；全都空 = 这张图没有可用的译文。 */
    @Test
    fun `空文字块被丢弃`() {
        val json = """{"sl":"ja","tl":"zh","eng":"x","blocks":[
            {"l":0,"t":0,"r":10,"b":10,"o":"","x":""},
            {"l":1,"t":1,"r":20,"b":20,"o":"ありがとう","x":"谢谢"}
        ]}"""
        val decoded = NovelImagePayloadCodec.decode(json)!!
        assertEquals(1, decoded.blocks.size)
        assertEquals("谢谢", decoded.blocks[0].translated)
    }

    /** 三态循环顺序：译文 → 识别原文 → 原图 → 译文。 */
    @Test
    fun `三态循环`() {
        assertEquals(NovelImageVisual.ORIGINAL, NovelImageVisual.TRANSLATED.next())
        assertEquals(NovelImageVisual.PLAIN, NovelImageVisual.ORIGINAL.next())
        assertEquals(NovelImageVisual.TRANSLATED, NovelImageVisual.PLAIN.next())
    }
}
