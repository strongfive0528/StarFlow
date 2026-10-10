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
 *
 * ## 两种"停"的语义（2026-10 真机反馈「超分进程无法关闭」后加的，别混）
 * | 方法 | 待办 | 在途那一页 | 之后还能入队吗 |
 * |---|---|---|---|
 * | [cancelAll] | 清空 | **不打断**（跑完为止） | ✅ 能（换模型/改选项后要能接着排） |
 * | [stop] | 清空 | **一并取消** | ❌ 不能，直到 [resume] |
 *
 * 为什么 [stop] 必须连在途那一页一起取消：超分单页可以是**几十秒**的活（Real-ESRGAN 4x + 大图），
 * 只清待办的话，用户"关掉超分 / 打开面板 / 退出阅读器"之后仍会有一整页推理在跑，
 * 界面上那条「正在超分第 N 页…」也一直不消失 —— 这正是用户报的"关不掉"。
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

    /** ⚠️ 它的**监视器**同时保护 `generation` 与 `workerAlive`（下称"那把锁"）—— 只有一把锁。 */
    private val pending = LinkedHashMap<Int, Request>()
    private val seqGen = AtomicLong(0)

    @Volatile
    private var targetPage: Int = 0

    @Volatile
    private var generation: Int = 0

    /** true = 已 [stop]，拒绝一切新入队（见类注释的两种"停"）。 */
    @Volatile
    private var stopped: Boolean = false

    @Volatile
    private var worker: Job? = null

    /** worker 协程是否"在岗"（含正在跑 `runPage` 期间）。只由那把锁保护。 */
    private var workerAlive: Boolean = false

    /** 正在跑的那一页（-1 = 空闲）。用于面板/按钮判断"忙不忙"。 */
    @Volatile
    var runningPage: Int = -1
        private set

    /** 当前"用户在看"的页 —— 排序的锚点，也决定谁是高优先级 */
    val target: Int get() = targetPage

    /** 已 [stop]（闸门关着）→ 调用方不必再排页。 */
    val isStopped: Boolean get() = stopped

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
     * @return true = 本次真的新排入（用于日志/测试）；[isStopped] 期间恒 false
     */
    fun enqueue(page: Int, highPriority: Boolean = false): Boolean {
        val added = synchronized(pending) {
            if (page < 0 || stopped) return false
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

    /** 丢掉全部待办并让在途请求作废，但**仍然接受新入队**（换模型/改选项后用）。 */
    fun cancelAll() {
        synchronized(pending) {
            pending.clear()
            generation++
        }
    }

    /**
     * 停表：丢掉待办 + **取消在途那一页** + 拒绝后续入队，直到 [resume]。
     *
     * 调用点全部是"用户已经不想要它继续跑"的时刻：关掉超分开关、打开面板、切到后台、
     * 退出阅读器（见控制器 `refreshSrQueue`）。
     */
    fun stop() {
        val inFlight = synchronized(pending) {
            pending.clear()
            generation++
            stopped = true
            worker.also { worker = null }
        }
        // 在途那一页的协程一并取消：它下次挂起点就会退出（native 推理本身打断不了，
        // 但那之后不会落盘收尾、也不会再上屏）。收尾清理在调用方的 `runPage` 里（写库 + 收芯片）。
        inFlight?.cancel()
    }

    /** 重新开闸（只放开"接受入队"，不自动排页 —— 排什么由调用方决定）。 */
    fun resume() {
        synchronized(pending) { stopped = false }
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
    fun orderedPages(): List<Int> = synchronized(pending) { sortedLocked().map { it.page } }

    /**
     * 排序（**必须持那把锁调**）：高优先级 → 距锚点近 → 先入先出。
     * 距离用**当前锚点**，所以翻页后下一次取任务自然就跟着走（不需要重建队列）。
     */
    private fun sortedLocked(): List<Request> = pending.values.sortedWith(
        compareByDescending<Request> { it.highPriority }
            .thenBy { kotlin.math.abs(it.page - targetPage) }
            .thenBy { it.seq }
    )

    private fun ensureWorker() {
        synchronized(pending) {
            if (workerAlive) return
            workerAlive = true
        }
        worker = scope.launch {
            try {
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
            } finally {
                // ⚠️ **收尾必须与 `enqueue` 在同一把锁里**：先把 workerAlive 放下，再看队列里是不是
                //    又进了活。少了这一步就是经典的"丢唤醒" —— `enqueue` 看到旧 worker 的 `isActive`
                //    还是 true 就放心返回，而它其实已经退出循环 → 那一页永远躺在队列里没人做
                //    （用户看到的是"超分排上了却一直不动"）。
                val leftover = synchronized(pending) {
                    workerAlive = false
                    worker = null
                    !stopped && pending.isNotEmpty()
                }
                if (leftover) ensureWorker()
            }
        }
    }

    /** 取下一个：跳过已被 [cancelAll] / [stop] 作废的（generation 不同） */
    private fun takeNext(): Request? = synchronized(pending) {
        for (c in sortedLocked()) {
            pending.remove(c.page)
            if (c.generation == generation) return@synchronized c
        }
        null
    }
}
