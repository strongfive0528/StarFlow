package translationapi

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 在飞账本的行为守卫（2026-10 真机事故的直接回归测试）。
 *
 * 事故现场：引擎用单值 `currentJob` + 「发新请求 cancel 上一个」，小说/漫画同一实例并发 N 批时
 * **只有最后发出的那个批能回来**，其余批的回调永生不来 → 永久挂起（详见 [InFlightRequests] 注释）。
 */
class InFlightRequestsTest {

    @Test
    fun 并发登记的请求互不取消() = runTest {
        val ledger = InFlightRequests()
        var finished = 0
        repeat(5) {
            ledger.track(this) {
                delay(500)
                finished++
            }
        }
        assertEquals("发新请求不许动旧请求：5 个批必须同时在飞", 5, ledger.size)
        advanceUntilIdle()
        assertEquals("5 个批都必须跑完（这正是事故里丢掉的那 4 个）", 5, finished)
        assertEquals("跑完自动出账", 0, ledger.size)
    }

    @Test
    fun cancelAll取消全部在飞并且都不算完成() = runTest {
        val ledger = InFlightRequests()
        var finished = 0
        val jobs = (1..3).map {
            ledger.track(this) {
                delay(500)
                finished++
            }
        }
        ledger.cancelAll()
        assertEquals(0, ledger.size)
        assertTrue("cancelAll 必须真的取消掉每一个", jobs.all { it.isCancelled })
        advanceUntilIdle()
        assertEquals("被取消的请求不许再算完成", 0, finished)
    }

    @Test
    fun 异常退出的请求也会自动出账() = runTest {
        val ledger = InFlightRequests()
        // ⚠️ 必须自带 CoroutineExceptionHandler：`runTest` 会把子协程的未捕获异常当成**测试失败**，
        // 那样这条用例就测不到账本清理了（第一次就是这么红的）。
        val child = CoroutineScope(
            coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> }
        )
        ledger.track(child) { throw IllegalStateException("boom") }
        advanceUntilIdle()
        assertEquals("异常路径也必须出账，否则账本会永久占着槽位", 0, ledger.size)
        child.cancel()
    }
}
