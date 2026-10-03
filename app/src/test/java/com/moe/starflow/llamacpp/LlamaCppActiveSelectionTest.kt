package com.moe.starflow.llamacpp

import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * 「谁能被选中 / 同时只能选中一个」的回归守卫（Robolectric，真跑清单 + 真查磁盘）。
 *
 * 用户 2026-10 明确的两条要求：
 *  1. **未下载的模型不能被选中启用** —— 预设的两个预制模型在没下载时都是禁用状态；
 *  2. **不管导入/下载了多少模型，同一时刻只能选中一个**。
 *
 * 改之前的两处真实 bug（都在这里钉死）：
 *  - 全新安装时 `loadInternal` 兜底 `list.firstOrNull()?.id` → 把**根本没下载**的 1.25-bit
 *    预制模型标成「使用中」，引擎到真正翻译时才报「模型文件不存在」；
 *  - 删掉激活模型的下载文件（或删掉激活的导入模型）后，选中**不清** → 卡片一直显示「使用中」，
 *    而文件早就不在磁盘上了；`remove()` 还漏复位 `PREF_ACTIVE_IS_BUILTIN` 镜像。
 */
@RunWith(RobolectricTestRunner::class)
class LlamaCppActiveSelectionTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = CustomPreference.getInstance(app)

    @Before
    fun setUp() {
        // CustomPreference 是进程级单例（用例之间共用一份），先把选中相关的键清干净
        prefs.getSharedPreferences().edit().clear().commit()
        // 新 Application → store 里的清单缓存作废、重新载入（见 LlamaCppModelStore.init）
        LlamaCppModelStore.init(app)
        // ⚠️ 用例之间可能共享同一个临时目录 → 清掉上一轮留下的清单与占位模型文件，
        //    保证每个用例的起点都是「全新安装、一个模型都没下载」
        LlamaCppPaths.manifestFile().delete()
        File(LlamaCppPaths.baseDir(), "models").listFiles()?.forEach { it.delete() }
        LlamaCppPaths.modelsDir().listFiles()?.forEach { it.delete() }
        LlamaCppModelStore.reloadForTest()
        LlamaCppModelStore.ensureLoadedSync()
    }

    private fun builtin(id: String): LlamaCppModel =
        LlamaCppModelStore.byId(id) ?: error("清单里应有预制条目 $id")

    /** 造一个占位 gguf（本类只验「文件在不在」的判定，不读内容）。 */
    private fun spec(model: LlamaCppModel): LlamaCppModel = model.apply {
        absoluteFile.apply { parentFile?.mkdirs(); writeText("dummy") }
    }

    private fun imported(name: String): LlamaCppModel = spec(
        LlamaCppModel(
            id = "imported:$name",
            displayName = name,
            fileName = name,
            sizeBytes = 0L,
            md5 = null,
            source = LlamaCppModelSource.IMPORTED,
            dirName = LlamaCppPaths.DIR_NAME,
        )
    )

    /** 全清单里**有且只有** [expected] 这一个模型处于选中态。 */
    private fun assertOnlyActive(expected: String) {
        assertEquals("当前激活模型", expected, LlamaCppModelStore.activeId.value)
        assertEquals(
            "同一时刻只能有一个模型被选中",
            1,
            LlamaCppModelStore.models.value.count { it.id == LlamaCppModelStore.activeId.value },
        )
        assertEquals("即时快照 active() 也要一致", expected, LlamaCppModelStore.active()?.id)
    }

    // ───────────────────────── 要求 1：未下载的模型不能被选中 ─────────────────────────

    @Test
    fun `预制模型未下载时不能被选中`() {
        val h = builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID)
        val q = builtin(LlamaCppModelStore.BUILTIN_HYMT2_Q4KM_ID)
        assertTrue("两个预制模型的条目一直挂在清单里", LlamaCppModelStore.fileMissing(h))
        assertTrue(LlamaCppModelStore.fileMissing(q))
        assertFalse("未下载的模型应判为不可用", LlamaCppModelStore.isUsable(h))

        assertFalse("未下载的预制模型不能被激活", LlamaCppModelStore.setActive(h.id))
        assertFalse(LlamaCppModelStore.setActive(q.id))

        assertNull("没下载就应该是未选中状态", LlamaCppModelStore.activeId.value)
        assertNull(LlamaCppModelStore.active())
        assertEquals("prefs 里不许留下选中值", "", prefs.getString(LlamaCppModelStore.PREF_ACTIVE_ID, ""))
    }

    @Test
    fun `首次载入时两个预制模型都是未选中状态`() {
        // setUp 已经走完「首次载入」：清单只有两个预制条目、磁盘上一个模型文件都没有
        val builtins = LlamaCppModelStore.models.value.filter { it.source == LlamaCppModelSource.BUILTIN }
        assertEquals("两个预制条目都要在", 2, builtins.size)
        assertTrue(builtins.all { LlamaCppModelStore.fileMissing(it) })

        assertNull(
            "全新安装不能自动选中任何预制模型（原来会兜底选中第一个，卡片显示使用中但文件不存在）",
            LlamaCppModelStore.activeId.value,
        )
        assertFalse(LlamaCppModelStore.isHyMt2Active())
    }

    @Test
    fun `清单里没有的 id 不能激活`() {
        assertFalse(LlamaCppModelStore.setActive("imported:不存在.gguf"))
        assertNull(LlamaCppModelStore.activeId.value)
    }

    // ───────────────────────── 要求 2：同一时刻只能选中一个 ─────────────────────────

    @Test
    fun `下载与导入再多的模型也只有一个被选中`() {
        val h = builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID)
        val q = builtin(LlamaCppModelStore.BUILTIN_HYMT2_Q4KM_ID)
        spec(h)
        spec(q)
        val a = imported("a.gguf")
        val b = imported("b.gguf")
        LlamaCppModelStore.upsert(a)
        LlamaCppModelStore.upsert(b)

        assertEquals("4 个可用模型（2 预制 + 2 导入）", 4, LlamaCppModelStore.models.value.size)

        listOf(h, q, a, b).forEach { m ->
            assertTrue("已下载/已导入的模型应能激活：${m.id}", LlamaCppModelStore.setActive(m.id))
            assertOnlyActive(m.id)
        }
    }

    @Test
    fun `重复点击同一个模型不会变出第二个选中`() {
        val h = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID))
        assertTrue(LlamaCppModelStore.setActive(h.id))
        assertTrue("再点一次（已是当前）仍算成功，选中态不变", LlamaCppModelStore.setActive(h.id))
        assertOnlyActive(h.id)
        assertEquals(
            "全清单里点亮的行只能是 1 个",
            1,
            LlamaCppModelStore.models.value.count { it.id == LlamaCppModelStore.activeId.value },
        )
    }

    // ───────────────────────── 选中态的失效与清理 ─────────────────────────

    @Test
    fun `激活模型的文件被删除后自动取消选中`() {
        val h = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID))
        assertTrue(LlamaCppModelStore.setActive(h.id))

        assertTrue("文件还在时不该清", !LlamaCppModelStore.clearActiveIfMissing())
        assertTrue(h.absoluteFile.delete())

        assertTrue("文件没了 → 取消选中", LlamaCppModelStore.clearActiveIfMissing())
        assertNull(LlamaCppModelStore.activeId.value)
        assertEquals(
            "prefs 同步清空（下次启动不会拿一个不可用的 id 重试）",
            "",
            prefs.getString(LlamaCppModelStore.PREF_ACTIVE_ID, ""),
        )
        assertFalse("第二次调用不再重复清（否则会无限重绘）", LlamaCppModelStore.clearActiveIfMissing())
    }

    @Test
    fun `重新载入时不选中不可用的模型也不改选别的`() {
        val h = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID))
        val q = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_Q4KM_ID))
        assertTrue(LlamaCppModelStore.setActive(q.id))

        // 用户把选中的那个模型文件删了 → 模拟下次启动重新载入
        assertTrue(q.absoluteFile.delete())
        LlamaCppModelStore.reloadForTest()
        LlamaCppModelStore.ensureLoadedSync()

        assertNull("选中的模型不可用 → 未选择", LlamaCppModelStore.activeId.value)
        assertNotEquals("不许静默改选另一个可用模型（翻译结果会莫名其妙地变）", h.id, LlamaCppModelStore.activeId.value)
    }

    @Test
    fun `删掉激活的条目会一起复位内置镜像`() {
        // 镜像只在「引擎 = LlamaCpp 且选中内置 Hy-MT2」时才为 true
        prefs.setInt("Text_API", Constants.TextApi.AI.id)
        prefs.setInt("Text_AI", Constants.TextAI.HYMT2.id)

        val h = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID))
        assertTrue(LlamaCppModelStore.setActive(h.id))
        assertTrue("选中预制模型 → 镜像 true", LlamaCppModelStore.isHyMt2ActiveFromPrefs(prefs))

        LlamaCppModelStore.remove(h.id)
        assertNull(LlamaCppModelStore.activeId.value)
        assertFalse(
            "删掉激活模型必须连内置镜像一起复位（原来漏了它 → 只有 prefs 的调用点会以为还在跑 Hy-MT2）",
            LlamaCppModelStore.isHyMt2ActiveFromPrefs(prefs),
        )
        assertFalse(prefs.getBoolean(LlamaCppModelStore.PREF_ACTIVE_IS_BUILTIN, false))
    }

    @Test
    fun `删除非激活模型不影响当前选中`() {
        val h = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_ID))
        val q = spec(builtin(LlamaCppModelStore.BUILTIN_HYMT2_Q4KM_ID))
        assertTrue(LlamaCppModelStore.setActive(h.id))

        LlamaCppModelStore.remove(q.id)
        assertNotNull(LlamaCppModelStore.byId(h.id))
        assertOnlyActive(h.id)
    }
}
