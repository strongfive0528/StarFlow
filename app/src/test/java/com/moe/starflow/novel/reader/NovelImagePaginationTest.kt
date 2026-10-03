package com.moe.starflow.novel.reader

import com.moe.starflow.novel.translate.NovelParagraph
import com.moe.starflow.novel.translate.NovelParagraphType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **插图参与分页**的守卫（Robolectric：`paginate` 要建 `StaticLayout`）。
 *
 * ⚠️ Robolectric 的文本引擎是**桩**：不按宽度换行，每段恒 1 行、行高 = 字号 × 行距倍率。
 * 所以这里只钉"插图占多少高、什么时候换页"这类与文本引擎无关的事 —— 恰好正是插图的全部逻辑
 * （图片高度不来自文本引擎，来自 [NovelImageMetrics]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelImagePaginationTest {

    /** 列宽 100（左右边距 0）、页高 300；Robolectric 下每段文本恒 1 行 = 40 × 1.5 = 60px。 */
    private val style = NovelTextStyle(
        fontSizePx = 40f,
        lineSpacingMultiplier = 1.5f,
        paragraphSpacingPx = 20f,
        paddingPx = 0f,
        topPaddingPx = 0f,
        bottomPaddingPx = 0f,
    )

    private fun paras() = listOf(
        NovelParagraph(0, NovelParagraphType.TEXT, "第一段正文"),
        NovelParagraph(1, NovelParagraphType.IMAGE, "📷 [图片]"),
        NovelParagraph(2, NovelParagraphType.TEXT, "第三段正文"),
    )

    private fun image(w: Int, h: Int) = NovelImage(File("img.png"), w, h)

    /**
     * 一页放不下的插图**整张挪到下一页**，而且那一页只有它 —— 这是"分页按图片真实高度记账"
     * 的直接证据（按占位文本算的话它只有一行，会跟正文挤在一起）。
     */
    @Test
    fun `放不下的插图整张换页`() {
        // 100×400 的图按列宽 100 缩放后是 75×300 = 整整一页
        val pages = NovelPaginator.paginate(paras(), style, 100, 300, null, null, mapOf(1 to image(100, 400)))
        assertEquals("第一段 / 插图 / 第三段 → 三页", 3, pages.size)
        assertEquals(listOf(0), pages[0].segments.map { it.paraIndex })
        assertEquals("插图独占一页", listOf(1), pages[1].segments.map { it.paraIndex })
        assertEquals(listOf(2), pages[2].segments.map { it.paraIndex })
    }

    /** 放得下时照旧同页（插图不能平白把每一页都撑开）。 */
    @Test
    fun `小插图与正文同页`() {
        // 100×100 的图不放大，原样 100 高：60 + 20 + 100 + 20 + 60 = 260 ≤ 300
        val pages = NovelPaginator.paginate(paras(), style, 100, 300, null, null, mapOf(1 to image(100, 100)))
        assertEquals("三段应该都在同一页", 1, pages.size)
        assertEquals(listOf(0, 1, 2), pages[0].segments.map { it.paraIndex })
    }

    /**
     * 没有图片数据（读不到 / 老格式）时**回落成占位文本**：段落照旧在页表里，
     * 不能因为"没有图"就把这一段整段吞掉（正文一个字都不能丢）。
     */
    @Test
    fun `没有图片数据时回落占位文本`() {
        val pages = NovelPaginator.paginate(paras(), style, 100, 300, null, null, emptyMap())
        val all = pages.flatMap { it.segments }.map { it.paraIndex }
        assertTrue("图片段不能从页表里消失", all.contains(1))
        assertEquals(listOf(0, 1, 2), all)
    }

    /** 插图段的字符区间必须落在它的占位文本里（与正文段同一条硬约束）。 */
    @Test
    fun `插图段的字符区间不越界`() {
        val pages = NovelPaginator.paginate(paras(), style, 100, 300, null, null, mapOf(1 to image(100, 400)))
        for (page in pages) {
            for (seg in page.segments) {
                val len = paras()[seg.paraIndex].originalText.length
                assertTrue(
                    "段 ${seg.paraIndex} 区间 [${seg.charStart},${seg.charEnd}) 越界（长度 $len）",
                    seg.charStart in 0..len && seg.charEnd in seg.charStart..len,
                )
            }
        }
    }
}
