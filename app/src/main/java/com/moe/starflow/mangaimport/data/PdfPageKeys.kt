package com.moe.starflow.mangaimport.data

/**
 * PDF 页的**合成存储 key**（纯函数，单测覆盖）。
 *
 * 为什么要它：`ReaderPageSource` 的页表是一串 key，而后续所有东西 —— 章节切分
 * （[MangaChapterSplitter]）、页序迁移（[MangaPageOrder]）、导出命名 —— 都建立在"这串 key 是什么"上。
 * PDF 没有"每页一个文件名"这种东西，所以 key 是我们自己造的：
 *
 * - **零填充 + 递增**：`page_0001.jpg`。数字补零后**自然排序 = 页号升序**，而
 *   [MangaChapterSplitter] 正是用自然排序排章内页序 —— 两边天然一致，不需要额外适配。
 * - 带 `.jpg` 只是为了"看着像个图片条目"（旧导出链路的 `ExportNaming.stemOf` 会去点找扩展名），
 *   不代表真有这么个文件。
 * - **不用 `String.format`**：某些 locale 会把数字换成别的记数系统 → key 变得不可比较；
 *   这里用 `padStart`，与 locale 无关。
 * - `index` 超过 9999 时补零自动变宽（`page_10000.jpg`），仍然保持"位数相同则字典序 = 数值序"、
 *   "位数不同则位数多的更大"这两个性质，自然排序照样正确。
 *
 * ⚠️ 别把它换成"用页内容算哈希/用它页文件名"：页序一旦依赖内容，PDF 的页序就变成不可复现的了
 *    （而译文/气泡坐标全按页号存）。
 */
object PdfPageKeys {

    private const val PREFIX = "page_"
    private const val SUFFIX = ".jpg"
    private const val PAD = 4

    /** 第 [index] 页（0 起）的 key。 */
    fun of(index: Int): String {
        val page = index.coerceAtLeast(0) + 1
        return PREFIX + page.toString().padStart(PAD, '0') + SUFFIX
    }

    /** 0..[count)-1 的全部 key（**按 page tree 顺序**，即权威页序）。 */
    fun all(count: Int): List<String> {
        if (count <= 0) return emptyList()
        return (0 until count).map { of(it) }
    }
}
