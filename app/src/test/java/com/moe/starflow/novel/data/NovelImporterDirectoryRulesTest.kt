package com.moe.starflow.novel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件夹导入的**挑选规则与路径消毒**（纯 JVM）。
 *
 * 两条都是"错了也不崩、只是数据悄悄不对"的类型：
 * - **挑选规则**错 → 该成章的 html 不被认（用户看到「未找到可阅读的文本章节」），
 *   或者夹里的杂物被当成章节
 * - **路径消毒**错 → provider 给一条 `../../x.jpg` 就能把写入目标挪出 `novel_import/<id>/`
 *   （覆盖别的书、封面、日志）。与 `NovelImporter.safeSegment` 同一套威胁模型。
 */
class NovelImporterDirectoryRulesTest {

    // ===== 扩展名分类（文件夹导入的挑选规则，与 NovelImporter.DIR_*_EXTS 一一对应）=====

    @Test
    fun `文本条目认出 txt epub 与 html 系列`() {
        for (name in listOf("a.txt", "b.EPUB", "ch1.html", "ch2.HTM", "c.xhtml")) {
            assertTrue("$name 应该是可读文本条目", NovelImporter.isDirTextEntry(name))
        }
        for (name in listOf("a.jpg", "cover.png", "book.zip", "noext", null)) {
            assertFalse("$name 不该被当成文本条目", NovelImporter.isDirTextEntry(name))
        }
    }

    /** html 是"要不要连图片一起拷"的开关（txt/epub 不需要外部图片）。 */
    @Test
    fun `只有 html 系列被认成 html 条目`() {
        assertTrue(NovelImporter.isDirHtmlEntry("ch1.html"))
        assertTrue(NovelImporter.isDirHtmlEntry("ch1.XHTML"))
        assertTrue(NovelImporter.isDirHtmlEntry("ch1.htm"))
        assertFalse(NovelImporter.isDirHtmlEntry("ch1.txt"))
        assertFalse(NovelImporter.isDirHtmlEntry("ch1.epub"))
        assertFalse(NovelImporter.isDirHtmlEntry(null))
    }

    @Test
    fun `图片条目认常见位图与 svg`() {
        for (name in listOf("a.jpg", "a.JPEG", "a.png", "a.gif", "a.webp", "a.bmp", "a.avif", "a.heic", "a.svg")) {
            assertTrue("$name 应该被拷", NovelImporter.isDirImageEntry(name))
        }
        for (name in listOf("a.txt", "a.html", "a.db", "a.json", "noext", null)) {
            assertFalse("$name 不该被拷", NovelImporter.isDirImageEntry(name))
        }
    }

    // ===== 相对路径消毒（安全边界）=====

    @Test
    fun `正常的多层相对路径原样通过`() {
        assertEquals("images/a.jpg", NovelImporter.sanitizeRelPath("images/a.jpg"))
        assertEquals("a/b/c.png", NovelImporter.sanitizeRelPath("a/b/c.png"))
        // 前导 `./`、重复斜杠、反斜杠、首尾空白都收敛掉
        assertEquals("a/b.png", NovelImporter.sanitizeRelPath("./a//b.png"))
        assertEquals("a/b.png", NovelImporter.sanitizeRelPath("a\\b.png"))
        assertEquals("images/a.jpg", NovelImporter.sanitizeRelPath("  images/a.jpg  "))
    }

    /** ⚠️ 最关键的一条：`..` 一律拒绝（放行就能写到书目录之外）。 */
    @Test
    fun `上级目录一律拒绝`() {
        assertNull(NovelImporter.sanitizeRelPath("../evil.jpg"))
        assertNull(NovelImporter.sanitizeRelPath("images/../../evil.jpg"))
        assertNull(NovelImporter.sanitizeRelPath("a/.."))
    }

    /** 盘符 / SAF 的 `primary:` 前缀 / 控制字符 / 超长，全部拒绝。 */
    @Test
    fun `带冒号控制字符或超长的路径拒绝`() {
        assertNull(NovelImporter.sanitizeRelPath("primary:Books/a.jpg"))
        assertNull(NovelImporter.sanitizeRelPath("C:/Windows/a.jpg"))
        assertNull(NovelImporter.sanitizeRelPath("a/\u0000b.jpg"))
        assertNull(NovelImporter.sanitizeRelPath("a/" + "x".repeat(200) + ".jpg"))
        assertNull(NovelImporter.sanitizeRelPath("x".repeat(500)))
    }

    @Test
    fun `空路径与只有分隔符的路径拒绝`() {
        assertNull(NovelImporter.sanitizeRelPath(null))
        assertNull(NovelImporter.sanitizeRelPath(""))
        assertNull(NovelImporter.sanitizeRelPath("   "))
        assertNull(NovelImporter.sanitizeRelPath("/"))
        assertNull(NovelImporter.sanitizeRelPath("./"))
    }

    /** 层级过深也拒绝（正常小说夹不会有那么深的结构）。 */
    @Test
    fun `层级过深拒绝`() {
        assertNull(NovelImporter.sanitizeRelPath("a/b/c/d/e/f/g/h/i/j.jpg"))
        assertEquals("a/b/c/d/e/f.jpg", NovelImporter.sanitizeRelPath("a/b/c/d/e/f.jpg"))
    }
}
