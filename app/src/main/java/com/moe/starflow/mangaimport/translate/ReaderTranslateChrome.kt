package com.moe.starflow.mangaimport.translate

import com.moe.starflow.data.ImportedPageTranslation

/**
 * 阅读器右下角翻译浮层组「这一页该出现哪几个按钮」的**纯函数**。
 *
 * 抽出来的理由：这段规则最近一直在变（用户口径 2026-10），而它散在 Activity 里时只能靠
 * 真机点出来 —— 抽成纯函数之后 `ReaderTranslateChromeTest` 把整张真值表钉住。
 *
 * ## 用户口径（2026-10，别改错）
 * > 「只 OCR 模式的翻译按钮应该变成 OCR 图标，OCR 完成显示重新 OCR + 2 态切换 + 删除三个按钮；
 * >  返回翻译模式，以 OCR 的页面显示翻译按钮 + 2 态切换 + 删除；
 * >  OCR 和翻译都完成了显示重新翻译 + 3 态切换 + 删除」
 * > 「执行重新 OCR 要提示译文也会清除」（提示在 Activity，这里只决定按钮）
 *
 * | 情形 | 主按钮 | 切换 | 删除 | 感叹号 |
 * |---|---|---|---|---|
 * | 仅 OCR 模式 · 无 OCR | `OCR` | — | — | 失败时 |
 * | 仅 OCR 模式 · 有 OCR（含"有 OCR 也有旧译文"） | `RE_OCR` | 有译文=3 态 / 无译文=2 态 | ✓ | 失败时 |
 * | 翻译模式 · 无 OCR | `TRANSLATE` | — | — | 失败时 |
 * | 翻译模式 · 有 OCR 无译文 | `TRANSLATE` | 2 态 | ✓ | 失败时 |
 * | 翻译模式 · 已翻译 | `RETRANSLATE` | 3 态 | ✓ | 失败时 |
 *
 * ⚠️ **"有 OCR"看的是载荷**（`sourceText` + `bubbleRects` 都在），不是状态值：
 * 翻译失败的行状态是 `FAILED`，但它的 OCR 载荷还在（`fail()` 刻意保留），
 * 那种页按用户口径要显示「翻译按钮 + 2 态切换 + 删除」，而不是只剩一个感叹号。
 *
 * ⚠️ **在途（`TRANSLATING`）不给切换/删除**：那几秒里删除会把正在写的译文行抽掉，
 * 而切换按钮指向的"当前态"下一帧就被渲染覆盖，点它等于没反应（看着像坏了）。
 */
object ReaderTranslateChrome {

    /** 主按钮语义 —— Activity 据此选图标（`ic_reader_ocr` / `ic_reader_translate` / `ic_refresh`）与确认弹窗。 */
    enum class Primary {
        /** 识别这一页（仅 OCR 模式，还没识别过）。 */
        OCR,

        /** 重新识别这一页（仅 OCR 模式，已有识别结果）。 */
        RE_OCR,

        /** 翻译这一页。 */
        TRANSLATE,

        /** 重新翻译这一页（已有译文）。 */
        RETRANSLATE,
    }

    data class Buttons(
        val primary: Primary,
        /** 是否显示切换按钮（有内容可切）。 */
        val toggle: Boolean,
        /** 切换按钮循环的态数：3 = 译文/原文/纯原图，2 = 原文/纯原图。 */
        val toggleStates: Int,
        /** 是否显示删除按钮（删除这一页的记录）。 */
        val delete: Boolean,
        /** 是否显示失败感叹号（点它看失败原因）。 */
        val warn: Boolean,
    )

    /**
     * @param state 该页记录状态（[ImportedPageTranslation.STATE_IDLE] 等）
     * @param hasOcr 该页有没有 OCR 载荷（原文 + 气泡坐标）
     * @param ocrOnly 当前全局执行模式是不是「仅 OCR」
     */
    fun buttonsFor(state: Int, hasOcr: Boolean, ocrOnly: Boolean): Buttons {
        val translated = state == ImportedPageTranslation.STATE_SUCCESS
        val inFlight = state == ImportedPageTranslation.STATE_TRANSLATING
        val warn = state == ImportedPageTranslation.STATE_FAILED
        // 有内容可看 = 有译文或有识别结果；在途时两边都不算（见类注释）
        val viewable = !inFlight && (translated || hasOcr)
        val primary = when {
            ocrOnly && hasOcr -> Primary.RE_OCR
            ocrOnly -> Primary.OCR
            translated -> Primary.RETRANSLATE
            else -> Primary.TRANSLATE
        }
        return Buttons(
            primary = primary,
            toggle = viewable,
            toggleStates = if (translated) 3 else 2,
            delete = viewable,
            warn = warn,
        )
    }
}
