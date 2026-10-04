package com.moe.starflow.sr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「超过长边就不超分」的判据（对齐 TachiyomiX 模型包的 `maxProcessingResolution`）。
 *
 * 为什么值得单独钉死：这是**少数能一秒不花就省掉 2~6 秒/页**的开关，
 * 而它一旦判错方向就是"用户以为超分坏了"（要么该跳的不跳、白等；要么不该跳的跳了、画面没变化）。
 */
class SrSkipPolicyTest {

    @Test
    fun disabledMeansNeverSkip() {
        // 0 = 关闭：默认行为必须与加这个功能之前完全一致
        assertFalse(SrSkipPolicy.shouldSkip(4000, 6000, SrSkipPolicy.DISABLED))
        assertFalse(SrSkipPolicy.shouldSkip(1, 1, SrSkipPolicy.DISABLED))
    }

    @Test
    fun limitComparesAgainstTheLongSideOnly() {
        // 1600（TachiyomiX 模型包默认值）：A5@300dpi(1746×2479) 超了 → 跳
        assertTrue(SrSkipPolicy.shouldSkip(1746, 2479, 1600))
        // 竖排也一样（长边是高度）
        assertTrue(SrSkipPolicy.shouldSkip(1200, 1700, 1600))
        // 刚好等于阈值 → **不跳**（边界取"超过"才跳，别把 1600 自己判掉）
        assertFalse(SrSkipPolicy.shouldSkip(1600, 1200, 1600))
        assertFalse(SrSkipPolicy.shouldSkip(1200, 1600, 1600))
        // 常见低清漫画页（960×1367）远低于阈值 → 不跳
        assertFalse(SrSkipPolicy.shouldSkip(960, 1367, 1600))
    }

    @Test
    fun longSideAndDetailAreConsistent() {
        assertEquals(1500, SrSkipPolicy.longSide(1000, 1500))
        assertEquals(1000, SrSkipPolicy.longSide(1000, 800))
        // 尺寸非法时不抛异常（真正的"读不到图"由 PAGE_LOAD_FAILED 报）
        assertEquals(0, SrSkipPolicy.longSide(-5, -1))
        val d = SrSkipPolicy.detail(2480, 3508, 1600)
        assertTrue("细节里必须带实际尺寸与阈值：$d", d.contains("2480x3508") && d.contains("3508") && d.contains("1600"))
    }

    @Test
    fun presetsStartWithDisabledAndIncludeTheUpstreamDefault() {
        assertEquals("第一档必须是 0（不跳过），默认值是它", SrSkipPolicy.DISABLED, SrSkipPolicy.PRESETS.first())
        assertTrue("要含 TachiyomiX 的默认值 1600", 1600 in SrSkipPolicy.PRESETS.toList())
        assertTrue("阈值必须递增，设置页列表才讲得通", SrSkipPolicy.PRESETS.toList().zipWithNext().all { (a, b) -> a < b })
    }
}
