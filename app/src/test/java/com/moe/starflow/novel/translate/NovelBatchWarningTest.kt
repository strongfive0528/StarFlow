package com.moe.starflow.novel.translate

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单批超长预警的**判据守卫**（纯 JVM）。
 *
 * 钉死四件事：
 * 1. 档位与默认值（默认 **2048**、**默认值两侧都留得出格子**、**单射**且默认值落在格点上）——
 *    漫画调试面板曾因为"位置 ↔ 取值"写了两套互逆公式而出现「默认值不在格点上」；
 *    而"默认值顶在最左端"是另一头：滑块只能往一个方向拖，另一半需求在界面上走不通
 *    （用户口径 2026-10-04：「滑动范围要根据当前默认值调整到合理的范围」）
 * 2. 判据是 **`estimate > threshold`**（等于不算超）；阈值 <= 0 = 关闭
 * 3. 「同一轮只打扰一次」：用户拒过一次之后，同轮后续超长批不再弹窗
 */
class NovelBatchWarningTest {

    @Test
    fun `档位围绕默认值铺开且默认值落在格点上`() {
        assertEquals("默认阈值 = 2048（用户口径 2026-10-03）", 2048, NovelBatchWarning.DEFAULT_THRESHOLD)
        assertEquals("档位必须严格递增", NovelBatchWarning.tiers.sorted(), NovelBatchWarning.tiers)
        assertEquals("档位不能重复（单射）", NovelBatchWarning.tiers.size, NovelBatchWarning.tiers.toSet().size)
        assertEquals(
            "默认值必须是一个档位，否则滑块与判据对不上",
            NovelBatchWarning.DEFAULT_THRESHOLD,
            NovelBatchWarning.thresholdAt(NovelBatchWarning.defaultIndex),
        )
        assertTrue(
            "默认值左右都要有格子（否则滑块只能往一个方向拖，另一半需求在界面上走不通）",
            NovelBatchWarning.defaultIndex > 0 &&
                NovelBatchWarning.defaultIndex < NovelBatchWarning.maxIndex(),
        )
        assertTrue("最小档位要有意义（再小就逢批必弹）", NovelBatchWarning.tiers.first() <= 1024)
        assertTrue("最大档位不能大到永远触发不了", NovelBatchWarning.tiers.last() <= 16384)
        assertEquals(
            "旧默认值必须与当前默认值不同（NovelPanelStyle.migrateBatchDefaults 的判据）",
            false,
            NovelBatchWarning.LEGACY_DEFAULT_THRESHOLD == NovelBatchWarning.DEFAULT_THRESHOLD,
        )
    }

    /** 滑块位置 ↔ 阈值互为逆（含两端夹取）。 */
    @Test
    fun `位置与阈值互逆`() {
        for (i in 0..NovelBatchWarning.maxIndex()) {
            assertEquals(i, NovelBatchWarning.indexOf(NovelBatchWarning.thresholdAt(i)))
        }
        assertEquals(NovelBatchWarning.tiers.first(), NovelBatchWarning.thresholdAt(-5))
        assertEquals(NovelBatchWarning.tiers.last(), NovelBatchWarning.thresholdAt(99))
    }

    /** 不在档位上的值（老版本手改过 / 旧档位 32768）收敛到最近的档位。 */
    @Test
    fun `非档位值收敛到最近档位`() {
        assertEquals(2048, NovelBatchWarning.normalize(1900))
        assertEquals(4096, NovelBatchWarning.normalize(3900))
        assertEquals(4096, NovelBatchWarning.normalize(4096))
        assertEquals(8192, NovelBatchWarning.normalize(7000))
        assertEquals("512 比 1024 更近", 512, NovelBatchWarning.normalize(600))
        assertEquals("旧档位 32768 收敛到新上限", NovelBatchWarning.tiers.last(), NovelBatchWarning.normalize(32768))
        assertEquals(NovelBatchWarning.tiers.last(), NovelBatchWarning.normalize(Int.MAX_VALUE))
    }

    /** 判据是**严格大于**：正好等于阈值不弹（用户设的就是"超过才提示"）。 */
    @Test
    fun `超过阈值才预警`() {
        assertTrue(NovelBatchWarning.shouldWarn(4097, 4096))
        assertFalse(NovelBatchWarning.shouldWarn(4096, 4096))
        assertFalse(NovelBatchWarning.shouldWarn(10, 4096))
        assertFalse("阈值 <= 0 = 关闭预警", NovelBatchWarning.shouldWarn(999999, 0))
        assertFalse(NovelBatchWarning.shouldWarn(999999, -1))
    }

    /**
     * 估算是**粗估 token**：CJK ≈ 1/字、其它 ≈ 1/4 字符、空白不计，多段按换行拼接。
     * 与真正发出去的请求同口径 —— 段与段之间只有换行，没有前缀。
     */
    @Test
    fun `合并文本的 token 估算口径`() {
        assertEquals(4, NovelBatchWarning.estimateOf(listOf("四个汉字")))
        assertEquals(2, NovelBatchWarning.estimateOf(listOf("abcdefgh")))
        assertEquals(0, NovelBatchWarning.estimateOf(listOf("   \n  ")))
        // 多段拼接后一起估（各段分别估再求和也一样，因为空白不计）
        val single = NovelBatchWarning.estimateOf(listOf("一二三", "abcd"))
        val sum = NovelBatchWarning.estimateOf(listOf("一二三")) + NovelBatchWarning.estimateOf(listOf("abcd"))
        assertEquals(sum, single)
    }

    /** 用户拒过一次 → 同轮后续超长批**不再弹窗**（直接跳过）；[NovelOversizeConfirmer.reset] 后恢复。 */
    @Test
    fun `同一轮只打扰一次`() = runBlocking {
        var asked = 0
        val c = NovelOversizeConfirmer { _, _ ->
            asked++
            false
        }

        assertFalse(c.confirm(9000, 4096))
        assertFalse("第二次不再弹窗", c.confirm(9000, 4096))
        assertEquals("只问了一次", 1, asked)
        assertTrue("已经拒绝过", c.hasDeclined())

        c.reset()
        assertFalse(c.hasDeclined())
        assertFalse(c.confirm(9000, 4096))
        assertEquals("重置后允许再问", 2, asked)
    }

    /** 用户同意过一次之后，同一轮后面照样会弹（同意不改变后续行为）。 */
    @Test
    fun `同意不影响后续批次`() = runBlocking {
        var asked = 0
        val c = NovelOversizeConfirmer { _, _ ->
            asked++
            true
        }

        assertTrue(c.confirm(9000, 4096))
        assertTrue(c.confirm(9000, 4096))
        assertEquals(2, asked)
        assertFalse(c.hasDeclined())
    }
}
