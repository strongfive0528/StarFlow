package com.moe.starflow.mangaimport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PDF 页 key 的**纯函数**规则 + 它与章节系统的配合（见 [PdfPageKeys]）。
 *
 * 为什么值得单独钉：PDF 没有"每页一个文件名"，key 是我们自己造的，而**整条读路径都建立在它上面** ——
 * 章节切分（`MangaChapterSplitter` 会按自然排序重排页序）、页序迁移、导出命名、译文行按页号存。
 * key 一旦不满足"自然排序 = 页号升序"，表现就是"点第 3 章跳到别人的页 / 译文挂错页"，
 * 而且很难联想到是 key 的锅。
 */
class PdfPageKeysTest {

    @Test
    fun `keysAreZeroPaddedSoNaturalOrderEqualsPageOrder`() {
        assertEquals("page_0001.jpg", PdfPageKeys.of(0))
        assertEquals("page_0002.jpg", PdfPageKeys.of(1))
        assertEquals("page_0010.jpg", PdfPageKeys.of(9))
        // 越界防护：负数按第 1 页处理（调用方本不该传，但绝不能产出 "page_0000.jpg"）
        assertEquals("page_0001.jpg", PdfPageKeys.of(-5))
    }

    @Test
    fun `paddingWidensPastFourDigitsWithoutBreakingOrder`() {
        assertEquals("page_9999.jpg", PdfPageKeys.of(9998))
        assertEquals("page_10000.jpg", PdfPageKeys.of(9999))
        // 补零变宽后仍要"位数相同看字典序、位数多者更大" → 自然排序结果 = 输入顺序
        val wide = PdfPageKeys.all(10_005)
        assertEquals(wide, ArchivedMangaReader.sortNaturally(wide))
    }

    @Test
    fun `allReturnsNothingForNonPositiveCount`() {
        assertEquals(emptyList<String>(), PdfPageKeys.all(0))
        assertEquals(emptyList<String>(), PdfPageKeys.all(-3))
    }

    @Test
    fun `allIsInPageTreeOrder`() {
        assertEquals(listOf("page_0001.jpg", "page_0002.jpg", "page_0003.jpg"), PdfPageKeys.all(3))
    }

    /**
     * **与章节系统配合**：PDF 的 key 表必须切成"恰好一章、章号 1、章内页数 = 总页数、
     * 页序不被重排"。否则 PDF 书会出现"第0章/多章"或页序错乱。
     */
    @Test
    fun `pdfKeysSplitIntoExactlyOneChapterInTheSameOrder`() {
        val keys = PdfPageKeys.all(240)
        val split = MangaChapterSplitter.split(keys)

        assertEquals("PDF 必须恰好一章", 1, split.chapters.size)
        assertEquals("单章编号必须是 1（不是第0章）", 1, split.chapters[0].number)
        assertEquals("章标题留空 → 界面显示「第1章」", "", split.chapters[0].title)
        assertEquals(240, split.chapters[0].pageCount)
        assertEquals(0, split.chapters[0].startPage)
        assertEquals("权威页序必须与 page tree 顺序一致", keys, split.keys)
    }

    /** 同上，但用跨过补零宽度的页数（10005 页）再验一次"顺序不被自然排序改掉"。 */
    @Test
    fun `hugePdfAlsoSplitsIntoSingleOrderedChapter`() {
        val keys = PdfPageKeys.all(10_005)
        val split = MangaChapterSplitter.split(keys)
        assertEquals(1, split.chapters.size)
        assertEquals(10_005, split.chapters[0].pageCount)
        assertEquals(keys, split.keys)
    }

    /**
     * **PDF 永远不需要页序迁移**。
     *
     * `MangaPageOrder.legacyToNewPlan` 是给"章节系统上线前按完整路径排序"的老书用的置换。
     * PDF 的 key **没有目录**（全是 `page_0001.jpg` 这种平名）→ 切分器顺序 == 自然排序顺序，
     * rawKeys 与权威页序完全相同 → 必须得到 null（不迁移）。
     * 一旦哪天有人把 PDF 的 key 生成改成带目录/带序号以外的东西，译文与气泡就会整体挂错页，
     * 而这个用例会立刻变红。
     */
    @Test
    fun `pdfPageKeysNeverNeedMigration`() {
        listOf(1, 3, 240).forEach { count ->
            val keys = PdfPageKeys.all(count)
            assertEquals(
                "切分器不得重排 PDF 页序（count=$count）",
                keys,
                MangaChapterSplitter.split(keys).keys,
            )
            assertNull(
                "PDF 的 rawKeys 与权威页序必须完全相同（count=$count）→ 不该产生任何置换",
                MangaPageOrder.legacyToNewPlan(keys, keys),
            )
        }
    }

    /**
     * 反证：**判据本身是有效的** —— 真正需要迁移的形态（最外层散图 + 子目录，散图会被排到最前）
     * 必须算得出置换。少了这条，上面那个 `assertNull` 可能只是因为函数恒返回 null 而"假绿"。
     */
    @Test
    fun `theMigrationRuleStillDetectsARealReorder`() {
        val raw = listOf("z.jpg", "ch1/a.jpg")
        val splitKeys = MangaChapterSplitter.split(raw).keys
        assertEquals(
            "根散图应被排到子目录章之前（这正是老书需要迁移的原因）",
            listOf("z.jpg", "ch1/a.jpg"),
            splitKeys,
        )
        assertNotNull(
            "这种形态必须算得出置换，否则 PDF 那条 assertNull 说明不了任何事",
            MangaPageOrder.legacyToNewPlan(raw, splitKeys),
        )
    }
}
