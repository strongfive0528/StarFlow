package com.moe.starflow.sr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度队列的顺序与去重 —— 复刻 `HaoweiLi97/mihon_img_upscale` 的 `ImageEnhancer`：
 * **高优先级（当前页/手动点的那页）→ 距锚点近 → 先入先出**。
 *
 * ⚠️ 只测**纯逻辑**（入队/去重/排序/取消/失效），不跑协程：worker 是薄壳，
 * 真正要锁死的是"先做哪一页"——它一旦错，用户看到的就是"翻过去半天不清晰、后台却在算别的页"。
 */
class SrEnhanceQueueTest {

    /**
     * ⚠️ `autoStart = false`：只排不跑 —— 否则调度器会当场把队列抽干，
     * 「先做哪一页」的顺序就无从断言（生产恒为 true，见 [SrEnhanceQueue] 构造参数）。
     */
    private fun queue() = SrEnhanceQueue(
        scope = CoroutineScope(Dispatchers.Unconfined),
        runPage = { /* 不真跑 */ },
        autoStart = false,
    )

    @Test
    fun targetPageComesFirstThenNearestThenFifo() {
        val q = queue()
        q.setTarget(10)
        q.enqueue(20)   // 预加载：距离 10
        q.enqueue(8)    // 距离 2
        q.enqueue(12)   // 距离 2（后入 → 排在 8 后面）
        q.enqueue(10)   // 高优先级（== 锚点）
        assertEquals(listOf(10, 8, 12, 20), q.orderedPages())
    }

    @Test
    fun explicitHighPriorityBeatsDistance() {
        val q = queue()
        q.setTarget(10)
        q.enqueue(9)                       // 最近的预加载
        q.enqueue(50, highPriority = true) // 用户手动点的那页
        assertEquals("手动点的那页必须插到队首", 50, q.orderedPages().first())
    }

    @Test
    fun switchingTargetReordersWithoutRebuilding() {
        val q = queue()
        q.setTarget(10)
        listOf(8, 9, 11, 12).forEach { q.enqueue(it) }
        assertEquals(listOf(9, 11, 8, 12), q.orderedPages())
        // 翻到第 12 页：锚点一变，顺序自然跟着走（不需要清空重建）
        q.reprioritizeAround(12)
        assertEquals(listOf(12, 11, 9, 8), q.orderedPages())
    }

    @Test
    fun duplicateRequestsAreMergedAndCanBePromoted() {
        val q = queue()
        q.setTarget(1)
        assertTrue("首次入队应真的排入", q.enqueue(5))
        assertFalse("同页重复入队合并，不再排一次", q.enqueue(5))
        assertEquals(1, q.size())
        q.setTarget(5)                       // 翻到第 5 页 → 它变高优先级
        assertEquals(5, q.orderedPages().first())
        assertEquals("合并后仍然只有一条", 1, q.size())
    }

    @Test
    fun enqueueAroundPreloadsBothDirectionsNearestFirst() {
        val q = queue()
        q.setTarget(10)
        q.enqueueAround(10, radius = 3)
        assertEquals("前 3 后 3，共 6 页，由近到远", listOf(9, 11, 8, 12, 7, 13), q.orderedPages())
        assertEquals(6, q.size())
        // radius = 0 → 不预加载（用户关掉预加载）
        val q2 = queue()
        q2.enqueueAround(10, radius = 0)
        assertEquals(0, q2.size())
    }

    @Test
    fun cancelAllInvalidatesEverythingAndDropsPending() {
        val q = queue()
        q.setTarget(3)
        q.enqueue(3); q.enqueue(4); q.enqueue(2)
        assertEquals(3, q.size())
        q.cancelAll()
        assertEquals("待办必须清空（换书/退出/改选项）", 0, q.size())
        assertTrue("清空后应当空闲", q.isIdle())
    }

    @Test
    fun cancelSinglePageOnlyRemovesThatPage() {
        val q = queue()
        q.setTarget(0)
        q.enqueue(1); q.enqueue(2)
        assertTrue(q.cancel(1))
        assertFalse("重复取消返回 false", q.cancel(1))
        assertEquals(listOf(2), q.orderedPages())
        assertTrue(q.hasRequest(2))
        assertFalse(q.hasRequest(1))
    }

    @Test
    fun negativePageIsRejected() {
        val q = queue()
        assertFalse("页号非法不该进队列（防越界）", q.enqueue(-1))
        assertEquals(0, q.size())
    }
}
