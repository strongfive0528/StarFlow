package com.moe.starflow.ui.history

import android.content.Context
import com.moe.starflow.R
import com.moe.starflow.data.HistoryEntity

/**
 * 历史记录的**标题**（用户口径 2026-10）：
 * - **只 OCR 的记录**：写清楚是哪个 **OCR 模型**，并标注「未翻译」
 * - **已翻译的记录**：写清楚是哪个 **翻译模型**
 *
 * 两个名字都取自记录自己的 `translatorName`（**不现读设置**）：历史要回答的是
 * 「这一页当时是谁认的字 / 谁翻的」，用户后来换了模型不该改写历史。
 *
 * ⚠️ 记录里的 `translatorName` 是复合串（MangaFloatingService 那条路会是
 * `OpenAITranslation(gpt-4o) | PP-OCRv6+PPOcrV6 | 分批✓ 自由文字✗ box=… `），
 * 所以这里做两段归一：① 只取 ` | ` 前第一段；② `Api(model)` 只留 `model`。
 */
object HistoryTitle {

    private val TRANSLATOR_DISPLAY_NAMES = mapOf(
        "OpenAITranslation" to R.string.nt_openai,
        "BingTranslation" to R.string.nt_bing,
        "NLLBTranslation" to R.string.nt_nllb,
        "NiuTransTranslation" to R.string.nt_niu,
        "VolcTranslation" to R.string.nt_volc,
        "DeepLTranslation" to R.string.nt_deepl,
        "BaiduTranslation" to R.string.nt_baidu,
        "TencentCloudTranslation" to R.string.nt_tencent,
        "AzureTranslation" to R.string.nt_azure,
    )

    /** 译名走资源（中英各一份），避免历史页在英文模式下显示「火山/小牛/百度/腾讯」。 */
    fun translatorModelName(context: Context, translatorName: String?): String {
        val raw = translatorName?.trim().orEmpty()
        if (raw.isEmpty()) return context.getString(R.string.history_title_model_unknown)
        val apiPart = raw.split(" | ").first().trim()
        val match = Regex("^(\\w+?)\\((.+)\\)$").find(apiPart)
        if (match != null) return match.groupValues[2]      // 只显示模型名，不要 API 前缀
        return TRANSLATOR_DISPLAY_NAMES[apiPart]?.let { context.getString(it) } ?: apiPart
    }

    /**
     * OCR 模型名：直接用记录里存的值（抓屏漫画存的是 `det+ocr`，游戏存的是 OCR 引擎组标签）。
     *
     * 旧记录（2026-10 之前）只存了字面量 `"OCR"` / 空 → 退回通用徽章文案，
     * **不拿当前设置去冒充**（那是"编造历史"）。
     */
    fun ocrModelName(context: Context, translatorName: String?): String {
        val raw = translatorName?.trim().orEmpty()
        return if (raw.isEmpty() || raw == "OCR") context.getString(R.string.history_ocr_badge) else raw
    }

    /** 列表项标题（两处适配器共用，别再各拼一份）。 */
    fun of(context: Context, translatorName: String?, processingState: Int): String =
        if (processingState == HistoryEntity.PROCESS_OCR) {
            context.getString(R.string.history_title_ocr_model, ocrModelName(context, translatorName))
        } else {
            context.getString(R.string.history_title_translator_model, translatorModelName(context, translatorName))
        }
}
