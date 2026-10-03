package com.moe.starflow.mangaimport.translate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **「已有识别结果 → 跳过 OCR 直接翻译」这条路径必须把提示补齐**（源码级守卫）。
 *
 * 用户 2026-10-03：「只识别未翻译点击翻译目前没有提示跳过 ocr，也没有翻译中的提示」。
 *
 * 根因：`runTranslate` 里那条复用分支把工作整个交给 [`ReaderTranslationController.translatePhase`]，
 * 而那个函数**故意全程静默**（它服务章节批量/增量，进度由任务面板与通知栏统一显示），
 * 而且它 `return` 之前**连结果都不报** —— 于是：
 * 1. 用户不知道 OCR 被跳过了（点下去屏幕上什么都不动）；
 * 2. 顶部没有「翻译中…」进度芯片；
 * 3. 翻完之后常驻的进度芯片**永远挂在屏幕上**（没有任何成功/失败来替换它）。
 *
 * 这条守卫按源码住死三件事：① 复用分支必须发 `TRANSLATING` + `reader_translate_skip_ocr`；
 * ② 结束时必须收尾（`SUCCESS`，失败时 `FAILED`）；③ 文案必须**双语齐全**
 * （只加一边 → `assembleRelease` 的 `MissingTranslation` 直接红）。
 */
class ReaderOcrReuseNoticeTest {

    private val controller = File(
        "src/main/java/com/moe/starflow/mangaimport/translate/ReaderTranslationController.kt"
    ).readText()

    /** 复用分支（`STATE_OCR`/`STATE_FAILED` 且能拿到缓存的 PreparedPage）那一段。 */
    private val reuseBranch: String = run {
        val i = controller.indexOf("if (stateOf(page) == ImportedPageTranslation.STATE_OCR ||")
        assertTrue("找不到「复用已有 OCR」的分支（是不是被重构掉了？）", i > 0)
        controller.substring(i, (i + 1800).coerceAtMost(controller.length))
    }

    @Test
    fun `复用已识别页时必须先报「跳过 OCR · 翻译中」`() {
        assertTrue(
            "复用分支必须发 TRANSLATING 阶段（否则顶部没有任何进度提示）",
            reuseBranch.contains("ReaderTranslatePhase.TRANSLATING"),
        )
        assertTrue(
            "文案必须是 reader_translate_skip_ocr（同时说清「跳过 OCR」与「正在翻译」）",
            reuseBranch.contains("R.string.reader_translate_skip_ocr"),
        )
    }

    @Test
    fun `复用已识别页翻完必须收尾（否则「翻译中」芯片永久常驻）`() {
        assertTrue(
            "成功必须发 SUCCESS（Activity 靠它 dismiss 掉常驻进度芯片）",
            reuseBranch.contains("ReaderTranslatePhase.SUCCESS"),
        )
        assertTrue(
            "失败必须发 FAILED，且**只在真的记了失败时**报（双击取消是取消、不是失败）",
            reuseBranch.contains("ImportedPageTranslation.STATE_FAILED") &&
                reuseBranch.contains("ReaderTranslatePhase.FAILED"),
        )
    }

    @Test
    fun `新增文案必须双语齐全`() {
        val en = File("src/main/res/values/strings.xml").readText()
        val zh = File("src/main/res/values-zh/strings.xml").readText()
        listOf("reader_translate_skip_ocr").forEach { key ->
            assertTrue("values/strings.xml 缺 $key", en.contains("name=\"$key\""))
            assertTrue("values-zh/strings.xml 缺 $key", zh.contains("name=\"$key\""))
        }
    }
}
