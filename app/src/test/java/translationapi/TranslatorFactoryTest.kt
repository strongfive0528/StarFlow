package translationapi

import com.moe.starflow.utils.Constants
import com.moe.starflow.utils.CustomPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.moe.starflow.llamacpp.LlamaCppModelStore
import com.moe.starflow.llamacpp.LlamaCppTranslation

@RunWith(RobolectricTestRunner::class)
class TranslatorFactoryTest {

    /** CustomPreference 是默认 prefs 的单例封装；Robolectric 每个测试方法有独立 Application，互不污染 */
    private fun prefs(): CustomPreference {
        val cp = CustomPreference.getInstance(RuntimeEnvironment.getApplication())
        cp.getSharedPreferences().edit().clear().commit()
        return cp
    }

    @Test
    fun textAiLlamaCpp_returnsLocalEngine() {
        val p = prefs()
        p.getSharedPreferences().edit().putInt("Text_API", Constants.TextApi.AI.id)
                .putInt("Text_AI", Constants.TextAI.HYMT2.id).commit()
        // ⚠️ 未下载的模型**不能被选中**（预制模型默认是禁用状态，2026-10 用户要求）→
        //    先造一个占位 gguf 并显式激活，工厂才有模型可建（它只构造实例，真正的加载是懒加载）
        val app = RuntimeEnvironment.getApplication()
        LlamaCppModelStore.init(app)
        LlamaCppModelStore.ensureLoadedSync()
        val model = LlamaCppModelStore.builtinHymt2() ?: error("清单里应有预制 Hy-MT2 条目")
        model.absoluteFile.apply { parentFile?.mkdirs(); writeText("dummy") }
        assertTrue("已下载的预制模型应能激活", LlamaCppModelStore.setActive(model.id))

        val t = TranslatorFactory.create(app, p, TranslatorFactory.Mode.TEXT)
        // 本地 GGUF 引擎（原 Hy-MT2 分支）：工厂走共享热实例，构造函数不加载模型（懒加载）
        assertTrue(t is LlamaCppTranslation)
        assertTrue(TranslatorFactory.isLocal(t!!))
    }

    // ⚠️ 无 textAiNllb 测试：NLLBTranslation 构造会初始化 ONNX Runtime（native .so），
    // Robolectric 单元测试环境无法加载 → 构造抛异常 → create 返回 null。
    // 该分支的引擎创建逻辑在真机验证（HyMT2/Bing 分支已由单测覆盖 prefs 分支选择）。

    @Test
    fun textApiBing_returnsNotLocal() {
        val p = prefs()
        p.getSharedPreferences().edit().putInt("Text_API", Constants.TextApi.BING.id).commit()
        val t = TranslatorFactory.create(RuntimeEnvironment.getApplication(), p, TranslatorFactory.Mode.TEXT)
        assertNotNull(t)
        assertFalse(TranslatorFactory.isLocal(t!!))
    }

    @Test
    fun unknownApi_returnsNull() {
        val p = prefs()
        p.getSharedPreferences().edit().putInt("Text_API", 999).commit()
        assertNull(TranslatorFactory.create(RuntimeEnvironment.getApplication(), p, TranslatorFactory.Mode.TEXT))
    }
}
