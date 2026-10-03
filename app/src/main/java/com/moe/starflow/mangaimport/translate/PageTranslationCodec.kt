package com.moe.starflow.mangaimport.translate

import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.data.TranslationCacheUtils
import com.moe.starflow.manga.types.TranslatedBubble

/** 阅读器翻译记录字段 ↔ 气泡列表 的纯串行化/反串行化。 */
object PageTranslationCodec {

    /** "[N] 原文\n[2] …"（与 app 历史条目同格式，TranslationCacheUtils 可解析）。 */
    fun sourceText(bubbles: List<TranslatedBubble>): String =
        numbered(bubbles) { it.originalText }

    /** "[N] 译文\n[2] …"。 */
    fun translatedText(bubbles: List<TranslatedBubble>): String =
        numbered(bubbles) { it.translatedText }

    /** renderOverlay 需要的气泡矩形 JSON（坐标在页面位图空间）。 */
    fun bubbleRects(bubbles: List<TranslatedBubble>): String =
        TranslationCacheUtils.serializeBubbleRects(bubbles)

    /**
     * 从一行记录重建气泡（渲染译文图/原文图用）。记录无有效数据返回 null。
     *
     * ⚠️ **只 OCR、还没翻译的页（`translatedText == null`）也必须能重建**：
     * 它们要渲染的是**原文态**（把认出来的字盖回原位），只要原文 + 气泡坐标就够了。
     * 旧实现要求 `translatedText` 非空 → 这类页永远拿不到渲染图 →
     * 用户表现为「识别完切不回原文 / 重返阅读器切不回原文」（2026-10 报的二态切换失效）。
     * 译文缺失时按空串处理（[TranslationCacheUtils.rebuildBubblesFromCache] 本来就 `getOrElse` 兜底）。
     */
    fun fromRow(
        row: ImportedPageTranslation,
        defaultFontSize: Float,
        bgColor: Int,
    ): List<TranslatedBubble>? {
        if (row.sourceText.isNullOrBlank() || row.bubbleRects.isNullOrBlank()) return null
        val originals = TranslationCacheUtils.parseIndexedTextList(row.sourceText)
        if (originals.isEmpty()) return null
        val translations = TranslationCacheUtils.parseIndexedTextList(row.translatedText)
        return TranslationCacheUtils.rebuildBubblesFromCache(
            originals, translations, row.bubbleRects, defaultFontSize, bgColor,
        ).takeIf { it.isNotEmpty() }
    }

    private inline fun numbered(
        bubbles: List<TranslatedBubble>,
        text: (TranslatedBubble) -> String,
    ): String = bubbles.mapIndexed { i, b -> "[${i + 1}] ${text(b)}" }.joinToString("\n")
}