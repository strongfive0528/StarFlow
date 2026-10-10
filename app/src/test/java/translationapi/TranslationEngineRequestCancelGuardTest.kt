package translationapi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **引擎并发守卫**（源码级）：引擎实现里绝不允许再出现「发新请求就把上一个杀掉」。
 *
 * ### 为什么要用源码断言
 * 这条接线一旦被写回单值 `currentJob` + `currentJob?.cancel()`，**单请求场景完全正常**
 * （游戏/文本页点什么翻什么），只有「同一实例并发 N 批」的小说/漫画章节任务才炸；
 * 炸法还是**静默**的：被取消的协程走 `catch (CancellationException) { throw e }` 不回调，
 * 调用方永久挂起（不入库、不报错、状态停在「翻译中」）。
 * 真机日志（2026-10-03）里表现为「首批一直黄着不翻译、后面的批反而先完成」。
 *
 * 行为侧的真实守卫是 [InFlightRequestsTest]；这里只锁「接线没被改回去」。
 */
class TranslationEngineRequestCancelGuardTest {

    /** 6 个「一个实例上并发 N 个请求」的实现（文本 3 + 图片 3）。 */
    private val impls = listOf(
        "openaitranslation/OpenAITranslation.kt",
        "customtranslation/CustomTranslationText.kt",
        "doubaotranslation/DoubaoTranslation.kt",
        "baidutranslation/BaiduTranslationImage.kt",
        "customtranslation/CustomTranslationImage.kt",
        "tencentcloud/TencentTranslationImage.kt",
    )

    private fun source(rel: String): String {
        val f = File("src/main/java/translationapi/$rel")
        assertTrue("找不到引擎源码：${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun 每个实现都用在飞账本且不许再出现单值_currentJob() {
        impls.forEach { rel ->
            val text = source(rel)
            assertTrue(
                "$rel 必须用 inFlight.track(...) 发起请求（每个请求一个自己的 Job）",
                text.contains("inFlight.track(coroutineScope) {"),
            )
            assertTrue(
                "$rel 的 cancelTranslation() 必须走 inFlight.cancelAll()",
                text.contains("inFlight.cancelAll()"),
            )
            assertFalse(
                "$rel 里不许再出现 currentJob —— 「发新请求取消上一个」会让并发批永久挂起（2026-10 事故）",
                text.contains("currentJob"),
            )
        }
    }

    @Test
    fun 整个引擎包都不许有取消上一个请求的写法() {
        val root = File("src/main/java/translationapi")
        assertTrue("找不到引擎包：${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "InFlightRequests.kt" }
            .filter { it.readText().contains("currentJob?.cancel()") }
            .map { it.name }
            .toList()
        assertTrue(
            "这些引擎又把「新请求取消上一个」写回来了：$offenders（详见 InFlightRequests 注释）",
            offenders.isEmpty(),
        )
    }
}
