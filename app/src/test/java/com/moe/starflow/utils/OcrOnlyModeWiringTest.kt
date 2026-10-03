package com.moe.starflow.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **「仅 OCR」执行模式的接线守卫**（源码级）。
 *
 * 为什么需要它：用户 2026-10 报的 bug 是「只 OCR 模式下，截屏漫画翻译还是会 OCR 完就翻译」。
 * 根因不是某个判断写错，而是**顺序**——分批渲染管线（分批 OCR **+ 分批翻译**）跑在
 * 「仅 OCR 分支」**前面**，而管线自己根本不知道执行模式。这类漏网**不崩不报错**：
 * 界面上一切正常、日志也正常，只有用户的 API 额度在悄悄减少。
 *
 * `TranslationExecutionMode` 是全局开关，凡是"先 OCR 再翻译"的入口都必须自己看它一眼。
 * 本测试把**当前所有入口**逐个钉在源码上：谁把这段接线删了/挪了，这里立刻红。
 *
 * ⚠️ 这些类都需要 Context / Service 才能构造，"漏一处"又恰好是静默的 —— 只能盯源码
 * （与 `ChapterTranslationCancelGuardTest` / `ReaderNoticeOutletTest` 同一手法）。
 */
class OcrOnlyModeWiringTest {

    private fun src(rel: String): String {
        val f = File(rel)
        assertTrue("找不到源码：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    /** 用起止标记切一段代码（不要按行数硬编码窗口）。 */
    private fun slice(text: String, start: String, end: String): String {
        val i = text.indexOf(start)
        assertTrue("找不到起点：$start", i >= 0)
        val j = text.indexOf(end, i + start.length)
        return text.substring(i, if (j > i) j else text.length)
    }

    private fun count(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    // ───────────── ① 截屏漫画（本次 bug 的现场）─────────────

    @Test
    fun mangaScreenshotBatchPathHonoursOcrOnlyMode() {
        val cfg = src("src/main/java/com/moe/starflow/manga/pipeline/BatchPipelineConfig.kt")
        assertTrue(
            "配置必须带 ocrOnly 快照 —— 管线不能靠调用方分流（截屏漫画那条路就漏了）",
            cfg.contains("val ocrOnly: Boolean"),
        )

        val pipe = src("src/main/java/com/moe/starflow/manga/pipeline/IncrementalBatchPipeline.kt")
        val runBody = slice(
            pipe,
            "suspend fun run(bitmap: Bitmap): BatchOutcome {",
            "return if (isRTDetrMangaOcr)",
        )
        assertTrue("分批管线必须自己挡掉仅 OCR 模式", runBody.contains("if (config.ocrOnly)"))
        assertTrue("挡掉的方式是回退普通流程（后面还有仅 OCR 分支）",
            runBody.contains("BatchOutcome.NotApplicable"))

        val svc = src("src/main/java/com/moe/starflow/manga/MangaFloatingService.kt")
        assertTrue("截屏漫画必须把执行模式透给管线",
            svc.contains("ocrOnly = TranslationExecutionMode.isOcrOnly(this)"))
        assertTrue("截屏漫画的仅 OCR 分支必须还在（回退后走的就是它）",
            svc.contains("R.string.execution_ocr_only_done"))
        assertTrue("仅 OCR 的缓存命中也要显示原文态（这个模式的语义就是不翻译）",
            svc.contains("if (ocrOnly) TranslationCacheManager.OverlayMode.ORIGINAL"))
    }

    // ───────────── ② 阅读器内嵌翻译（4 条路径）─────────────

    @Test
    fun readerOcrOnlyHasItsOwnNoticePathAndAllFourPathsUseIt() {
        val cm = src("src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt")
        assertTrue("仅 OCR 必须有自己的完整路径（否则点下去毫无提示）",
            cm.contains("private suspend fun runOcrOnly("))
        assertTrue("手动模式必须走 runOcrOnly", cm.contains("runOcrOnly(page, fromQueue)"))
        assertTrue("自动队列必须走 runOcrOnly", cm.contains("runOcrOnly(target, fromQueue = true)"))
        assertTrue("增量窗口必须走 runOcrOnly", cm.contains("runOcrOnly(page, fromQueue = true)"))
        assertTrue("章节批量走 translatePhase 的仅 OCR 早退",
            cm.contains("result = if (rendered) \"OCR\" else \"OCR 空\""))
        assertTrue("进行中文案要有 OCR 口径", cm.contains("R.string.reader_translate_detecting_ocr"))
        assertTrue("收尾文案要有 OCR 完成", cm.contains("R.string.reader_translate_done_ocr"))
        assertTrue("章节路径的管线配置也要带执行模式（防止新调用点又把分批翻译打开）",
            cm.contains("ocrOnly = isOcrOnlyMode()"))
    }

    // ───────────── ③ 游戏截屏（结果浮层重翻 + 历史长按重翻）─────────────

    @Test
    fun gameScreenshotRetranslateEntriesAreBlockedInOcrOnlyMode() {
        val fbs = src("src/main/java/com/moe/starflow/translate/FloatingBallService.kt")
        assertEquals(
            "两条「重新翻译」入口（结果浮层按钮 / 历史长按）都必须拦住并提示",
            2,
            count(fbs, "R.string.execution_ocr_only_translate_blocked"),
        )
        assertTrue("仅 OCR 模式下要把结果浮层的重翻按钮收起来（跨模式残留 = 一点就调翻译）",
            fbs.contains("translationResultView.hideCacheIndicator()"))
        assertTrue("进行中提示在仅 OCR 模式下要是「文字识别中…」",
            fbs.contains("if (TranslationExecutionMode.isOcrOnly(this)) R.string.text_recognizing"))
    }

    // ───────────── ④ 历史查看器的「重新翻译」─────────────

    @Test
    fun viewerRetranslateDegradesToReOcr() {
        val viewer = src("src/main/java/com/moe/starflow/ui/viewer/MangaViewerActivity.kt")
        assertTrue("查看器必须自己看执行模式",
            viewer.contains("TranslationExecutionMode.isOcrOnly(this@MangaViewerActivity)"))
        assertTrue("仅 OCR 模式下走「只重新识别」的那条路",
            viewer.contains("reOcrInPlace(entry, bubbles"))
        assertTrue("只重新识别要把处理状态标成 OCR（历史列表据此显示徽章）",
            viewer.contains("processingState = HistoryEntity.PROCESS_OCR"))
        assertTrue("只重新识别的结果提示不能是「重新翻译完成」",
            viewer.contains("R.string.execution_ocr_only_done else R.string.toast_retranslate_done"))
    }

    // ───────────── ⑤ 通知栏 / 记录里的模型名 ─────────────

    @Test
    fun notificationAndRecordWordingFollowTheMode() {
        val svc = src("src/main/java/com/moe/starflow/translate/batch/TranslationJobService.kt")
        assertTrue("章节通知栏文案要分执行模式",
            svc.contains("R.string.chapter_translate_notify_progress_ocr"))
        assertTrue("通知栏开关（汇总通知）也要分执行模式",
            svc.contains("R.string.chapter_translate_notify_summary_ocr"))

        val utils = src("src/main/java/com/moe/starflow/manga/TranslateUtils.kt")
        assertTrue("仅 OCR 的记录只报 OCR 引擎名（不能写成「OpenAITranslation(…) | PP-OCRv5」）",
            utils.contains("fun ocrEngineDisplayName("))
    }

    // ───────────── ⑥ 自动翻译：仅 OCR 模式一律不许启动 ─────────────

    @Test
    fun autoTranslateIsRefusedInOcrOnlyMode() {
        for (rel in listOf(
            "src/main/java/com/moe/starflow/translate/FloatingBallService.kt",
            "src/main/java/com/moe/starflow/manga/MangaFloatingService.kt",
        )) {
            val text = src(rel)
            val body = slice(text, "private fun startAutoTranslate()", "\n    private fun ")
            assertTrue("$rel：自动翻译必须在入口就拒绝仅 OCR 模式",
                body.contains("TranslationExecutionMode.isOcrOnly(this)"))
            assertTrue("$rel：拒绝时要给提示，不能静默", body.contains("execution_ocr_only_auto_disabled"))
        }
    }
}
