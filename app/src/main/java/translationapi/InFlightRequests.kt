package translationapi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 翻译引擎的「在飞请求」账本 —— **每个请求一个自己的 Job**。
 *
 * ### 为什么必须有它（2026-10 真机事故，别再写回去）
 * 6 个引擎实现（OpenAI / 自定义文本 / 豆包 / 百度图片 / 自定义图片 / 腾讯图片）原先都在
 * `getTranslation` 开头写：
 *
 * ```kotlin
 * currentJob?.cancel()                 // 「发新请求就把上一个杀掉」
 * currentJob = coroutineScope.launch { ... client.newCall(request).execute() ... }
 * ```
 *
 * 单请求场景看不出问题（用户点什么就翻什么），但小说/漫画现在都是**一个引擎实例上并发 N 批**
 * （小说默认 5、漫画默认 3）：第 2 批一发出就把第 1 批的协程取消掉，而被取消的那个协程走
 * `catch (CancellationException) { throw e }` —— **它永远不回调**。调用方
 * （`NovelTranslationEngine.requestOnce` 的 `suspendCancellableCoroutine`）只认「回调」或
 * 「自己所属协程被取消」，于是那一批**永久挂起**：不入库、不报错、并发槽位不释放、
 * 状态永远停在「翻译中」。
 *
 * 现场（真机日志，2026-10-03）：5 个批同时发出，**只有最后发出的那个返回**，前面 4 个一直没有
 * Response，直到用户手动取消任务 → 用户看到的现象是
 * 「首批一直黄着不翻译、后面的反而先完成、浮层一直显示正在翻译、不翻页就卡死、译文永久丢失」。
 *
 * ### 语义
 * - **发新请求绝不牵连任何旧请求**：N 个批可以真的同时跑。
 * - [cancelAll] 取消**该实例上所有在飞请求**（服务停止 / 整个任务取消时用）。
 *   消费侧（小说 `NovelTranslationEngine` 等）**不要**用它做「取消某一次请求」——
 *   接口层没有请求句柄，一取消就是取消全部，会连带杀掉同实例上其它任务的在飞请求。
 */
class InFlightRequests {

    private val jobs = ConcurrentHashMap.newKeySet<Job>()

    /** 当前在飞请求数（单测/排查用）。 */
    val size: Int get() = jobs.size

    /**
     * 起一个请求协程并登记进账本；协程结束（成功/失败/取消）自动出账。
     * ⚠️ 出账必须走 `invokeOnCompletion`，不能在协程体里 `remove` —— 取消路径不保证执行到协程体末尾。
     */
    fun track(scope: CoroutineScope, block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(block = block)
        jobs += job
        job.invokeOnCompletion { jobs.remove(job) }
        return job
    }

    /** 取消全部在飞请求（中途取消的请求不会回调，调用方应已不再等它的结果）。 */
    fun cancelAll() {
        val snapshot = jobs.toList()
        jobs.clear()
        snapshot.forEach { it.cancel() }
    }
}
