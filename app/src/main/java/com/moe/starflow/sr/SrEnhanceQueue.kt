package com.moe.starflow.sr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 超分**调度队列** —— 复刻 `HaoweiLi97/mihon_img_upscale` 的 `ImageEnhancer` 语义
 * （`app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ImageEnhancer.kt`）：
 *
 * | 它的机制 | 这里 |
 * |---|---|
 * | `EnhanceRequest(priority, generation, seq)` + 队列 | [Request] + [pending] |
 * | 排序：优先级 desc → 距当前页 asc → seq asc | [orderedPages]（**纯函数**，好测） |
 * | `requestKey` 去重（同页重复请求合并） | [enqueue] 按页号合并，保留更高优先级 |
 * | `generation` + `cancelAll` + 配置变更失效 | [cancelAll] / [invalidate] |
 * | `reprioritizeAround(center)` | [reprioritizeAround] |
 * | 预加载（`preload_size`，默认 3） | [enqueueAround]（只排"还没超分"的页） |
 * | `preemptActiveRequestIfBehindTarget` | **刻意不做**：我们的推理是 ncnn 同步调用，中途打断要重跑整页，
 *   得不偿失；只保证"下一个一定先做目标页" |
 *
 * ⚠️ **它不管"要不要超分"**（开关/跳过阈值/OCR 让路/落盘/记录都在 [SrProcessor] 与控制器里）——
 * 这里只回答一个问题：**先做哪一页**。因此可以纯 JVM 单测。
 */
class SrEnhanceQueue(
    private val scope: CoroutineScope,
    /** 真正干活的一页（由控制器注入：加载原图 → runSr → 落盘）。失败自行吞掉，队列不关心。 */
    private val runPage: suspend (Int) -> Unit,
    /**
     * 是否在入队时自动起 worker。生产恒为 true；
     * **单测传 false** —— 否则 Unconfined/Test 调度器会当场把队列抽干，
     * 「先做哪一页」这套顺序逻辑就没法断言了。
     */
    private val autoStart: Boolean = true,
) {

    /** 一次待办。`generation` 用于整体失效（换模型/换书/改选项/清空）。 */
    data class Request(
        val page: Int,
        /** true = 当前页（或用户手动点的那页）；false = 预加载 */
        val highPriority: Boolean,
        val generation: Int,
        val seq: Long,
    )

    private val pending = LinkedHashMap<Int, Request>()
    private val seqGen = AtomicLong(0)

    @Volatile
    private var targetPage: Int = 0

    @Volatile
    private var generation: Int = 0

    @Volatile
    private var worker: Job? = null

    /** 正在跑的那一页（-1 = 空闲）。用于面板/按钮判断"忙不忙"。 */
    @Volatile
    var runningPage: Int = -1
        private set

    /** 当前"用户在看"的页 —— 排序的锚点，也决定谁是高优先级 */
    val target: Int get() = targetPage

    /** 换页：更新锚点，并把该页提升为高优先级（它可能已经在预加载队列里） */
    fun setTarget(page: Int) {
        targetPage = page
        synchronized(pending) {
            val old = pending[page] ?: return
            if (!old.highPriority) pending[page] = old.copy(highPriority = true)
        }
    }

    /**
     * 排一页。**同页重复请求合并**：已在高优先级就不再降级（与它的 `existingRequest` 分支同一意图）。
     * @return true = 本次真的新排入（用于日志/测试）
     */
    fun enqueue(page: Int, highPriority: Boolean = false): Boolean {
        val added = synchronized(pending) {
            if (page < 0) return false
            val old = pending[page]
            if (old != null) {
                if (highPriority && !old.highPriority) pending[page] = old.copy(highPriority = true)
                return false
            }
            pending[page] = Request(page, highPriority || page == targetPage, generation, seqGen.incrementAndGet())
            true
        }
        // ⚠️ 必须在锁**外**启动 worker：调度器一旦是 Unconfined，worker 会在锁内同步跑起来
        if (added && autoStart) ensureWorker()
        return added
    }

    /**
     * 预加载：[center] 前后各 [radius] 页，**由近到远**排入（它们的 seq 就是这个顺序，
     * 排序时距离相同则先入先出）。已在队列/已在跑的页由调用方用 `skip` 过滤掉。
     */
    fun enqueueAround(center: Int, radius: Int, highPriority: Boolean = false) {
        if (radius <= 0) return
        for (d in 1..radius) {
            enqueue(center - d, highPriority)
            enqueue(center + d, highPriority)
        }
    }

    /**
     * 把锚点周围重排优先级：距离越近越优先（它的 `reprioritizeAround`）。
     * 已经跑着的那页不打断。
     */
    fun reprioritizeAround(center: Int) {
        targetPage = center
    }

    fun cancel(page: Int) = synchronized(pending) { pending.remove(page) != null }

    /** 丢掉全部待办并让在途请求作废（换书/退出/换模型/改选项） */
    fun cancelAll() {
        synchronized(pending) {
            pending.clear()
            generation++
        }
    }

    fun hasRequest(page: Int): Boolean = synchronized(pending) { pending.containsKey(page) }

    fun isIdle(): Boolean = synchronized(pending) { pending.isEmpty() } && runningPage < 0

    /** 队列里还剩几页（不含正在跑的那页） */
    fun size(): Int = synchronized(pending) { pending.size }

    /**
     * 队列的**执行顺序**（纯函数，测试直接断言它）：
     * 高优先级 → 距锚点近 → 先入先出。
     * ⚠️ 距离用**当前锚点**，所以翻页后下一次取任务自然就跟着走（不需要重建队列）。
     */
    fun orderedPages(): List<Int> = synchronized(pending) {
        pending.values
            .sortedWith(
                compareByDescending<Request> { it.highPriority }
                    .thenBy { kotlin.math.abs(it.page - targetPage) }
                    .thenBy { it.seq }
            )
            .map { it.page }
    }

    private fun ensureWorker() {
        val cur = worker
        if (cur != null && cur.isActive) return
        worker = scope.launch {
            while (isActive) {
                val req = takeNext() ?: break
                runningPage = req.page
                try {
                    runPage(req.page)
                } catch (t: Throwable) {
                    // 单页失败不影响队列（失败原因由控制器/SrOutcome 负责呈现）
                    if (t is kotlinx.coroutines.CancellationException) throw t
                } finally {
                    runningPage = -1
                }
            }
        }
    }

    /** 取下一个：跳过已被 [cancelAll] 作废的（generation 不同） */
    private fun takeNext(): Request? = synchronized(pending) {
        val candidates = pending.values.sortedWith(
            compareByDescending<Request> { it.highPriority }
                .thenBy { kotlin.math.abs(it.page - targetPage) }
                .thenBy { it.seq }
        )
        for (c in candidates) {
            pending.remove(c.page)
            if (c.generation == generation) return@synchronized c
        }
        null
    }
}
