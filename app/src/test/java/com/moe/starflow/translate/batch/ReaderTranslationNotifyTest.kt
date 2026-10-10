package com.moe.starflow.translate.batch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **翻译进度通知**的接线守卫（源码级 + 常量级，不需要设备）。
 *
 * 用户口径 2026-10-03：
 * - 「整章翻译没有看到通知系统的通知 —— 不仅要显示在卡片徽章上，**其他模式的翻译过程相关提示
 *   也必须显示在通知系统中**」→ 自动/增量/手动翻一批要有 [ReaderTranslationNotify]
 * - 通知通道**不能**是 `IMPORTANCE_LOW`（MIUI 上不亮图标、容易被折叠 → 用户"看不到通知"）
 *
 * ⚠️ 通知 id 是**手写常量**，撞了就是"两条通知互相覆盖"（`ActiveChapterJob.notificationId`
 * 那段注释里记着前车之鉴），所以这里把 id 空间钉死。
 */
class ReaderTranslationNotifyTest {

    private fun source(path: String): String {
        // 单测的工作目录是模块根（:app），源码在 src/main/java/...
        val f = File("src/main/java/$path")
        assertTrue("找不到源码 $path（工作目录=${File(".").absolutePath}）", f.exists())
        return f.readText()
    }

    /** 阅读器队列的通知 id 必须避开前台服务汇总（9998）与每章任务（10000+）。 */
    @Test
    fun `通知 id 不与管理层其他通知相撞`() {
        assertEquals(9997, ReaderTranslationNotify.NOTIFICATION_ID)
        assertFalse(
            "不能占用前台服务汇总通知 id",
            ReaderTranslationNotify.NOTIFICATION_ID == TranslationJobService.NOTIFICATION_ID_SERVICE,
        )
        assertTrue(
            "必须在每章任务（10000+）的区间之外",
            ReaderTranslationNotify.NOTIFICATION_ID < 10_000,
        )
        // 每章任务的 id 公式：10000 + base*2 + kind（见 ActiveChapterJob.notificationId）
        val chapterIdMin = 10_000 + 0L * 2 + 0
        assertTrue(
            "阅读器通知 id 不能落到每章任务的 id 区间里",
            ReaderTranslationNotify.NOTIFICATION_ID.toLong() < chapterIdMin,
        )
    }

    /** 通道必须是 DEFAULT 重要度 + **新 id**（LOW 通道一旦建好就改不了，只能换 id）。 */
    @Test
    fun `通知通道用默认重要度且不复用旧通道`() {
        val src = source("com/moe/starflow/translate/batch/TranslationNotifyChannel.kt")
        assertEquals("translation_progress", TranslationNotifyChannel.ID)
        assertEquals(
            "服务与阅读器通知必须共用同一条通道",
            TranslationNotifyChannel.ID, TranslationJobService.CHANNEL_ID,
        )
        assertTrue("旧通道要顺手删掉", src.contains("deleteNotificationChannel(LEGACY_ID)"))
        assertTrue(
            "重要度必须是 IMPORTANCE_DEFAULT（LOW 在 MIUI 上不亮图标 → 用户看不到）",
            src.contains("NotificationManager.IMPORTANCE_DEFAULT"),
        )
        assertFalse("不许把通道重要度写回 LOW", src.contains("NotificationManager.IMPORTANCE_LOW"))
    }

    /**
     * **通道创建必须在两条路上都发生**：Android 8+ 往不存在的通道发通知是**静默丢弃**，
     * 而阅读器队列那条通知**不经过前台服务** —— 用户只用自动/增量、从没点过「翻译本章」时，
     * 通道根本没人建，通知也就永远不出现（这是"看不到通知"的第三种漏法）。
     */
    @Test
    fun `两条通知路径都会确保通道存在`() {
        assertTrue(
            "阅读器队列通知必须先 ensure 通道",
            source("com/moe/starflow/translate/batch/ReaderTranslationNotify.kt")
                .contains("TranslationNotifyChannel.ensure(context)"),
        )
        assertTrue(
            "前台服务也必须 ensure 通道",
            source("com/moe/starflow/translate/batch/TranslationJobService.kt")
                .contains("TranslationNotifyChannel.ensure(this)"),
        )
    }

    /** 阅读器队列的进度必须真的接到通知栏上（挂观察 + 停队列时收掉）。 */
    @Test
    fun `阅读器队列把进度镜像到通知栏`() {
        val src = source("com/moe/starflow/novel/reader/NovelReaderActivity.kt")
        assertTrue(
            "队列状态一变就要更新通知（用户口径：翻译过程的提示必须进通知系统）",
            src.contains("updateReaderTranslationNotify(st)"),
        )
        assertTrue("离开/停队列要收掉通知", src.contains("ReaderTranslationNotify.clear(this)"))
        assertTrue(
            "只有真的在翻（TRANSLATING/WAITING_LOCK）才挂通知",
            src.contains("st.phase != NovelQueuePhase.TRANSLATING && st.phase != NovelQueuePhase.WAITING_LOCK"),
        )
    }
}
