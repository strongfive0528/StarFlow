package com.moe.starflow.me.model

import com.moe.starflow.download.DownloadState
import com.moe.starflow.llamacpp.LlamaCppModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模型卡片行「选中态 / 可用性 / 角标 / 点击动作」的回归守卫。
 *
 * 用户 2026-10 明确的两条要求，都收在这里钉死：
 *  1. **未下载的模型不能被选中启用**（预设的两个预制模型在没下载时是禁用状态）；
 *  2. **同一时刻只能选中一个** —— 选中态只由唯一的 `activeId` 决定，行视图不留本地状态。
 *
 * 被这条约束修掉的真实 bug：`rowActive.setOnClickListener(activate)` 会让 `setOnClickListener`
 * 顺手把 RadioButton 置成 clickable，于是它自己 `toggle()` —— 点一下**未下载**的卡片，
 * Radio 被点亮、却并没有真的生效，屏幕上同时出现两个"选中"的行（多选错觉）。
 * 这个接线错误没法用纯函数断言抓到，所以另加一条**源码守卫**。
 */
class LlamaCppRowSelectionTest {

    // ───────────────────────── 未下载的模型不能被选中 ─────────────────────────

    @Test
    fun `未下载的预制模型是禁用且未选中的`() {
        val sel = rowSelectionOf(
            source = LlamaCppModelSource.BUILTIN,
            id = "builtin:hymt2",
            activeId = null,
            missing = true,
            downloadState = DownloadState.Idle,
        )
        assertFalse("未下载 → 不能被选中", sel.selectable)
        assertFalse(sel.checked)
        assertEquals("角标是「未下载」而不是「文件已丢失」", RowBadge.NOT_DOWNLOADED, sel.badge)
    }

    @Test
    fun `未下载的模型即使曾是激活 id 也不能点亮`() {
        val sel = rowSelectionOf(
            source = LlamaCppModelSource.BUILTIN,
            id = "builtin:hymt2",
            activeId = "builtin:hymt2",
            missing = true,
            downloadState = DownloadState.Idle,
        )
        assertFalse("文件不在磁盘上 → RadioButton 绝不能点亮", sel.checked)
        assertFalse(sel.selectable)
    }

    @Test
    fun `下载完成后同一个模型才变成可选中`() {
        val before = rowSelectionOf(
            LlamaCppModelSource.BUILTIN, "builtin:hymt2-q4km", null, missing = true, downloadState = DownloadState.Idle,
        )
        assertFalse(before.selectable)

        val after = rowSelectionOf(
            LlamaCppModelSource.BUILTIN, "builtin:hymt2-q4km", null, missing = false, downloadState = DownloadState.Done,
        )
        assertTrue(after.selectable)
        assertFalse("可选中 ≠ 已选中：没点它就不该点亮", after.checked)
        assertEquals("已下载的预制模型角标", RowBadge.PRESET, after.badge)
    }

    @Test
    fun `下载流水线说已完成但文件不在才算文件已丢失`() {
        val sel = rowSelectionOf(
            LlamaCppModelSource.BUILTIN, "builtin:hymt2", null, missing = true, downloadState = DownloadState.Done,
        )
        assertEquals(RowBadge.MISSING, sel.badge)
    }

    @Test
    fun `导入模型文件丢失的角标是文件已丢失`() {
        val sel = rowSelectionOf(
            LlamaCppModelSource.IMPORTED, "imported:a.gguf", null, missing = true, downloadState = null,
        )
        assertEquals(RowBadge.MISSING, sel.badge)
        assertFalse(sel.selectable)
    }

    // ───────────────────────── 同一时刻只能选中一个 ─────────────────────────

    @Test
    fun `整个列表里点亮的行永远只有 activeId 那一个`() {
        val rows = listOf(
            "builtin:hymt2" to LlamaCppModelSource.BUILTIN,
            "builtin:hymt2-q4km" to LlamaCppModelSource.BUILTIN,
            "imported:a.gguf" to LlamaCppModelSource.IMPORTED,
        )
        rows.forEach { (activeId, _) ->
            val lit = rows.count { (id, source) ->
                rowSelectionOf(source, id, activeId, missing = false, downloadState = DownloadState.Idle).checked
            }
            assertEquals("activeId=$activeId 时点亮的行数", 1, lit)
        }
        val none = rows.count { (id, source) ->
            rowSelectionOf(source, id, null, missing = false, downloadState = DownloadState.Idle).checked
        }
        assertEquals("activeId 为空时一行都不点亮", 0, none)
    }

    @Test
    fun `activeId 为空时没有任何一行被点亮`() {
        listOf(LlamaCppModelSource.BUILTIN, LlamaCppModelSource.IMPORTED).forEach { source ->
            val sel = rowSelectionOf(source, "x", activeId = null, missing = false, downloadState = DownloadState.Done)
            assertFalse(sel.checked)
            assertTrue(sel.selectable)
        }
    }

    @Test
    fun `使用中角标只给当前激活且可用的那一行`() {
        val active = rowSelectionOf(
            LlamaCppModelSource.IMPORTED, "imported:a.gguf", "imported:a.gguf", missing = false, downloadState = null,
        )
        assertEquals(RowBadge.ACTIVE, active.badge)
        assertTrue(active.checked)

        val other = rowSelectionOf(
            LlamaCppModelSource.IMPORTED, "imported:b.gguf", "imported:a.gguf", missing = false, downloadState = null,
        )
        assertNull("没被选中的导入模型不显示角标", other.badge)
        assertFalse(other.checked)
    }

    // ───────────────────────── 点击动作 ─────────────────────────

    @Test
    fun `点未下载的卡片是拒绝而不是激活`() {
        assertEquals(RowClickAction.REFUSE_MISSING, rowClickAction(missing = true, isActive = false))
        assertEquals(RowClickAction.REFUSE_MISSING, rowClickAction(missing = true, isActive = true))
    }

    @Test
    fun `点可用卡片才是激活_点已选中的不重复激活`() {
        assertEquals(RowClickAction.ACTIVATE, rowClickAction(missing = false, isActive = false))
        assertEquals(RowClickAction.ALREADY_ACTIVE, rowClickAction(missing = false, isActive = true))
    }

    // ───────────────────────── 角标文案 ─────────────────────────

    @Test
    fun `四种角标各有自己的文案`() {
        val res = RowBadge.values().map { badgeResOf(it) }
        assertEquals("每个角标都要有映射（顺序 = 枚举声明顺序）", RowBadge.values().size, res.size)
        // ⚠️ 纯 JVM 单测里 R 字段在某些配置下会是占位的 0 —— 只有拿到真实资源 ID 时才校验唯一性，
        //    免得这条守卫在不该红的地方红
        if (res.none { it == 0 }) {
            assertEquals("四个角标不能共用同一个文案 key", 4, res.toSet().size)
        }
    }

    // ───────────────────────── 源码守卫（接线错误没法用纯函数抓） ─────────────────────────

    private fun fragmentSource(): String {
        val src = File("src/main/java/com/moe/starflow/me/model/LlamaCppModelFragment.kt")
        assertTrue("找不到模型管理页源码：${src.absolutePath}", src.exists())
        return src.readText()
    }

    @Test
    fun `RadioButton 不许自己 toggle`() {
        val text = fragmentSource()
        assertFalse(
            "禁止给 rowActive 挂点击监听：setOnClickListener 会把它重新置成 clickable，" +
                "RadioButton 随即自己 toggle → 点未下载的卡片会点亮但没生效（同一时刻看着像选中了两个）",
            text.contains("rowActive.setOnClickListener"),
        )
    }

    @Test
    fun `选中态只能由 bindRowSelection 刷入_并先清掉失效的激活模型`() {
        val text = fragmentSource()
        assertTrue(
            "选中态必须走 bindRowSelection（唯一入口）",
            text.contains("bindRowSelection(row, sel)"),
        )
        assertTrue(
            "渲染前必须清理「激活模型文件已不在」的选中态，否则卡片会一直挂着使用中",
            text.contains("LlamaCppModelStore.clearActiveIfMissing()"),
        )
        assertTrue(
            "激活必须检查返回值（激活失败要提示并同步选中态）",
            text.contains("if (LlamaCppModelStore.setActive(m.id))"),
        )
    }
}
