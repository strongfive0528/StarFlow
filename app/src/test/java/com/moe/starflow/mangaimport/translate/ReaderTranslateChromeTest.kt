package com.moe.starflow.mangaimport.translate

import com.moe.starflow.data.ImportedPageTranslation
import com.moe.starflow.mangaimport.translate.ReaderTranslateChrome.Primary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右下角按钮组的**真值表**（用户口径 2026-10）。
 *
 * 三条容易做错的：
 * 1. **仅 OCR 模式的主按钮恒为 OCR 口径**（无 OCR=OCR、有 OCR=重新 OCR），
 *    哪怕这一页还留着旧译文 —— 那个模式下点下去永远只重新识别。
 * 2. **翻译失败但有 OCR 载荷的页不是"只剩感叹号"**：要给翻译按钮 + 2 态切换 + 删除
 *    （`fail()` 刻意保留 OCR 载荷，用户就是靠这个重试/查看识别文本的）。
 * 3. **在途（TRANSLATING）不给切换/删除**：那几秒里删了会把正在写的行抽掉。
 */
class ReaderTranslateChromeTest {

    private val idle = ImportedPageTranslation.STATE_IDLE
    private val translating = ImportedPageTranslation.STATE_TRANSLATING
    private val success = ImportedPageTranslation.STATE_SUCCESS
    private val failed = ImportedPageTranslation.STATE_FAILED
    private val ocr = ImportedPageTranslation.STATE_OCR

    private fun tr(state: Int, hasOcr: Boolean) = ReaderTranslateChrome.buttonsFor(state, hasOcr, ocrOnly = false)
    private fun oc(state: Int, hasOcr: Boolean) = ReaderTranslateChrome.buttonsFor(state, hasOcr, ocrOnly = true)

    // ───────── 翻译模式 ─────────

    @Test
    fun translateMode_emptyPage_showsTranslateOnly() {
        val b = tr(idle, hasOcr = false)
        assertEquals(Primary.TRANSLATE, b.primary)
        assertFalse(b.toggle)
        assertFalse(b.delete)
        assertFalse("没失败就不该有感叹号", b.warn)
    }

    /** 用户口径：「返回翻译模式，以 OCR 的页面显示翻译按钮，2 态切换和删除按钮」。 */
    @Test
    fun translateMode_ocrDone_showsTranslateWithTwoStateToggleAndDelete() {
        val b = tr(ocr, hasOcr = true)
        assertEquals(Primary.TRANSLATE, b.primary)
        assertTrue(b.toggle)
        assertEquals("没有译文 → 只有 原图/原文 两态", 2, b.toggleStates)
        assertTrue(b.delete)
    }

    /** 用户口径：「OCR 和翻译都完成了显示重新翻译，3 态切换和删除」。 */
    @Test
    fun translateMode_translated_showsRetranslateWithThreeStateToggleAndDelete() {
        val b = tr(success, hasOcr = true)
        assertEquals(Primary.RETRANSLATE, b.primary)
        assertTrue(b.toggle)
        assertEquals(3, b.toggleStates)
        assertTrue(b.delete)
    }

    /**
     * 翻译失败但识别成功：**回退成「OCR 完成」那一套按钮**（用户口径 2026-10：
     * 「翻译失败但是 OCR 完成就回退成 OCR 完成状态」）—— 主按钮仍能再翻一次，
     * 感叹号留着看原因。
     */
    @Test
    fun translateMode_failedButOcrDone_stillOffersTranslateToggleDeleteAndWarn() {
        val b = tr(failed, hasOcr = true)
        assertEquals(Primary.TRANSLATE, b.primary)
        assertTrue(b.toggle)
        assertEquals(2, b.toggleStates)
        assertTrue(b.delete)
        assertTrue(b.warn)
    }

    /** 连识别都没成功（模型缺失/空页）→ 只有翻译按钮 + 感叹号，没有可删的载荷。 */
    @Test
    fun translateMode_failedWithoutOcr_showsTranslateAndWarnOnly() {
        val b = tr(failed, hasOcr = false)
        assertEquals(Primary.TRANSLATE, b.primary)
        assertFalse(b.toggle)
        assertFalse(b.delete)
        assertTrue(b.warn)
    }

    // ───────── 仅 OCR 模式 ─────────

    /** 用户口径：「只 OCR 模式的翻译按钮应该变成 OCR 图标」。 */
    @Test
    fun ocrOnlyMode_freshPage_showsOcrButton() {
        val b = oc(idle, hasOcr = false)
        assertEquals(Primary.OCR, b.primary)
        assertFalse(b.toggle)
        assertFalse(b.delete)
    }

    /** 用户口径：「OCR 完成显示重新 OCR，2 态切换和删除三个按钮」。 */
    @Test
    fun ocrOnlyMode_ocrDone_showsReOcrWithTwoStateToggleAndDelete() {
        val b = oc(ocr, hasOcr = true)
        assertEquals(Primary.RE_OCR, b.primary)
        assertTrue(b.toggle)
        assertEquals(2, b.toggleStates)
        assertTrue(b.delete)
    }

    /** 仅 OCR 模式下的失败页：主按钮还是 OCR（这一页还没识别出东西）。 */
    @Test
    fun ocrOnlyMode_failedWithoutOcr_showsOcrAndWarn() {
        val b = oc(failed, hasOcr = false)
        assertEquals(Primary.OCR, b.primary)
        assertTrue(b.warn)
        assertFalse(b.delete)
    }

    /**
     * 用户口径选定的那条：**仅 OCR 模式下即使这一页有旧译文，主按钮也是「重新 OCR」**
     * （点它会连带清掉译文，Activity 会先弹确认），切换按钮按译文有无给 3 态。
     */
    @Test
    fun ocrOnlyMode_pageWithOldTranslation_showsReOcrAndKeepsThreeStateToggle() {
        val b = oc(success, hasOcr = true)
        assertEquals(Primary.RE_OCR, b.primary)
        assertTrue(b.toggle)
        assertEquals("有译文就能切译文/原文/原图", 3, b.toggleStates)
        assertTrue(b.delete)
    }

    // ───────── 在途 ─────────

    @Test
    fun translatingPage_hidesToggleAndDelete() {
        for (hasOcr in listOf(true, false)) {
            for (ocrOnly in listOf(true, false)) {
                val b = ReaderTranslateChrome.buttonsFor(translating, hasOcr, ocrOnly)
                assertFalse("在途不给切换（hasOcr=$hasOcr ocrOnly=$ocrOnly）", b.toggle)
                assertFalse("在途不给删除（hasOcr=$hasOcr ocrOnly=$ocrOnly）", b.delete)
            }
        }
    }
}
