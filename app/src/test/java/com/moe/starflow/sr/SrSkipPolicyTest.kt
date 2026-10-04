package com.moe.starflow.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这一页要不要跳过超分」的判据 —— 与参照项目
 * `HaoweiLi97/mihon_img_upscale` 的 `TachiyomiImageDecoder` **逐字同形**：
 *
 * ```kotlin
 * (skipMaxWidth > 0 && bitmap.width > skipMaxWidth) ||
 *     (skipMaxHeight > 0 && bitmap.height > skipMaxHeight)
 * ```
 *
 * ⚠️ 是**逐轴**（不是"长边"）：`2000×900` 在 `skipMaxWidth=1600` 下要跳，
 * 但在 `skipMaxWidth=0 / skipMaxHeight=1600` 下**不能跳**（这条差异 2026-10 踩过）。
 */
class SrSkipPolicyTest {

    @Test
    fun disabledMeansNeverSkip() {
        // 0 = 该轴不限制；两轴都为 0 → 永不跳过（默认行为必须与加这个功能之前一致）
        assertFalse(SrSkipPolicy.shouldSkip(4000, 6000, 0, 0))
        assertFalse(SrSkipPolicy.shouldSkip(1, 1, 0, 0))
        assertFalse(SrSkipPolicy.shouldSkip(4000, 6000, SrSkipPolicy.DISABLED))
    }

    @Test
    fun eachAxisIsCheckedIndependently() {
        // 宽超、高不超 → 跳（逐轴 OR）
        assertTrue(SrSkipPolicy.shouldSkip(2000, 900, 1600, 1600))
        // 高超、宽不超 → 跳
        assertTrue(SrSkipPolicy.shouldSkip(900, 2000, 1600, 1600))
        // 只限宽：2000 宽要跳、900 宽不跳，且高度再大也不跳
        assertTrue(SrSkipPolicy.shouldSkip(2000, 9999, 1600, 0))
        assertFalse(SrSkipPolicy.shouldSkip(900, 9999, 1600, 0))
        // 只限高：同理
        assertTrue(SrSkipPolicy.shouldSkip(9999, 2000, 0, 1600))
        assertFalse(SrSkipPolicy.shouldSkip(9999, 900, 0, 1600))
    }

    @Test
    fun boundaryIsExclusive() {
        // 等于阈值**不跳**（他们的判据是严格大于）
        assertFalse(SrSkipPolicy.shouldSkip(1600, 1600, 1600, 1600))
        assertTrue(SrSkipPolicy.shouldSkip(1601, 1600, 1600, 1600))
    }

    @Test
    fun typicalPagesBehaveAsExpected() {
        // 低清漫画页（960×1367）任何阈值下都不该被跳过
        assertFalse(SrSkipPolicy.shouldSkip(960, 1367, 1600, 1600))
        // A5@300dpi（1746×2479）在 1600 阈值下跳
        assertTrue(SrSkipPolicy.shouldSkip(1746, 2479, 1600, 1600))
        // A4@300dpi（2480×3508）在 2560 阈值下仍跳、在 0 下不跳
        assertTrue(SrSkipPolicy.shouldSkip(2480, 3508, 2560, 2560))
        assertFalse(SrSkipPolicy.shouldSkip(2480, 3508, 0, 0))
    }

    @Test
    fun detailCarriesActualSizeAndLimit() {
        val d = SrSkipPolicy.detail(2480, 3508, 1600, 1600)
        assertTrue("细节里必须带实际尺寸与阈值：$d", d.contains("2480x3508") && d.contains("1600x1600"))
    }

    @Test
    fun presetsStartWithDisabledAndIncludeTheReferenceDefault() {
        assertEquals("第一档必须是 0（不跳过），默认值是它", SrSkipPolicy.DISABLED, SrSkipPolicy.PRESETS.first())
        assertTrue("要含参照项目设置页里的 1600", 1600 in SrSkipPolicy.PRESETS.toList())
        assertTrue("阈值必须递增", SrSkipPolicy.PRESETS.toList().zipWithNext().all { (a, b) -> a < b })
    }
}
