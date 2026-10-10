package com.moe.starflow.sr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SrOptions` = 对齐 `HaoweiLi97/mihon_img_upscale` 的那一组超分设置
 * （`pref_realcugan_*`）。这里钉死三件事：
 *
 * 1. **默认值必须与参照项目一致**（tile 128 / 性能档 0 / 精度 FP16 / fp16 算术 **false** / 跳过 0）；
 * 2. **性能档 → tile 间休眠的映射**（0→0ms、1→5ms、2→15ms，抄他们的 when）；
 * 3. **指纹随任一选项变化**（他们的 `configHash`）—— 少一项就是"改了设置画面不变"。
 */
class SrOptionsTest {

    /** 纯内存的 SharedPreferences 假实现（project 里其余 prefs 测试同做法，不引 Robolectric） */
    private class FakePrefs : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = map
        override fun getString(k: String, d: String?) = map[k] as String? ?: d
        override fun getStringSet(k: String, d: MutableSet<String>?) = (map[k] as MutableSet<String>?) ?: d
        override fun getInt(k: String, d: Int) = map[k] as Int? ?: d
        override fun getLong(k: String, d: Long) = map[k] as Long? ?: d
        override fun getFloat(k: String, d: Float) = map[k] as Float? ?: d
        override fun getBoolean(k: String, d: Boolean) = map[k] as Boolean? ?: d
        override fun contains(k: String) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(k: String, v: String?) = apply { map[k] = v }
            override fun putStringSet(k: String, v: MutableSet<String>?) = apply { map[k] = v }
            override fun putInt(k: String, v: Int) = apply { map[k] = v }
            override fun putLong(k: String, v: Long) = apply { map[k] = v }
            override fun putFloat(k: String, v: Float) = apply { map[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { map[k] = v }
            override fun remove(k: String) = apply { map.remove(k) }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    /** 输入像素压缩阈值：默认 1.5MP、可存可取、必须进引擎指纹。 */
    @Test
    fun inputThresholdDefaultsAndAffectsEngineKey() {
        assertEquals("默认输入阈值 1.5MP", 1.5f, SrOptions().maxInputMegapixels, 0.001f)
        val prefs = org.robolectric.RuntimeEnvironment.getApplication()
            .getSharedPreferences("sr_opt_mp", android.content.Context.MODE_PRIVATE)
        SrOptions.write(prefs, SrOptions(maxInputMegapixels = 0.6f))
        assertEquals(0.6f, SrOptions.from(prefs).maxInputMegapixels, 0.001f)
        assertNotEquals(
            "输入阈值变化必须更新引擎指纹（它改变喂进去的图）",
            SrOptions().engineKey(),
            SrOptions(maxInputMegapixels = 0.6f).engineKey(),
        )
    }

    @Test
    fun defaultsMatchTheReferenceProject() {
        val o = SrOptions()
        assertEquals("tile 默认 128（他们的 realCuganTileSize）", 128, o.tileSize)
        assertEquals("性能档默认 0 = 90%", 0, o.performanceMode)
        assertEquals("精度默认 FP16", SrOptions.PRECISION_FP16, o.precision)
        assertFalse("fp16 算术默认 **关**（他们的 pref_realcugan_fp16_arithmetic 默认 false）", o.fp16Arithmetic)
    }

    @Test
    fun performanceModeMapsToTileSleepLikeTheReference() {
        assertEquals("档 0（90%）不休眠", 0, SrOptions(performanceMode = 0).tileSleepMs)
        assertEquals("档 1（50%）5ms", 5, SrOptions(performanceMode = 1).tileSleepMs)
        assertEquals("档 2（30%）15ms", 15, SrOptions(performanceMode = 2).tileSleepMs)
        assertEquals("越界档位按 0 处理，不许崩", 0, SrOptions(performanceMode = 9).tileSleepMs)
    }

    @Test
    fun prefsRoundTripAndClamping() {
        val prefs = FakePrefs()
        assertEquals("空 prefs = 全默认", SrOptions(), SrOptions.from(prefs))

        SrOptions.write(prefs, SrOptions(tileSize = 256,
            performanceMode = 2, precision = 1, fp16Arithmetic = true))
        val read = SrOptions.from(prefs)
        assertEquals(256, read.tileSize)
        assertEquals(2, read.performanceMode)
        assertEquals(1, read.precision)
        assertTrue(read.fp16Arithmetic)

        // 越界的档位要归一（prefs 是外部可写的，垃圾值不能让引擎拿到）
        prefs.edit().putInt(SrOptions.KEY_PERFORMANCE_MODE, 99)
            .putInt(SrOptions.KEY_PRECISION, 99).apply()
        val clamped = SrOptions.from(prefs)
        assertEquals(2, clamped.performanceMode)
        assertEquals(1, clamped.precision)
    }

    @Test
    fun engineKeyChangesWithEveryEngineLevelOption() {
        val base = SrOptions()
        assertNotEquals("tile 变了必须重建引擎", base.engineKey(), base.copy(tileSize = 64).engineKey())
        assertNotEquals("精度变了必须重建引擎", base.engineKey(), base.copy(precision = 1).engineKey())
        assertNotEquals("fp16 算术变了必须重建引擎", base.engineKey(), base.copy(fp16Arithmetic = true).engineKey())
        // 与引擎无关的项（跳过阈值 / 性能档）不该让引擎重建 —— 否则每改一次都要重载模型
        assertEquals("压缩阈值不该影响引擎指纹", base.engineKey(), base.copy(maxInputMegapixels = 0.6f).engineKey())
        assertEquals("性能档不该影响引擎指纹", base.engineKey(), base.copy(performanceMode = 2).engineKey())
    }

    @Test
    fun fingerprintCoversEverythingThatChangesTheProduct() {
        val base = SrOptions()
        val f = base.fingerprint("SR_REALCUGAN_CONSERVATIVE", 2, 0)
        assertNotEquals("换模型要变", f, base.fingerprint("SR_REALCUGAN_PRO_CONSERVATIVE", 2, 0))
        assertNotEquals("换倍率要变", f, base.fingerprint("SR_REALCUGAN_CONSERVATIVE", 3, 0))
        assertNotEquals("换降噪档要变", f, base.fingerprint("SR_REALCUGAN_CONSERVATIVE", 2, 3))
        assertNotEquals("换 tile 要变", f, base.copy(tileSize = 64).fingerprint("SR_REALCUGAN_CONSERVATIVE", 2, 0))
        assertNotEquals(
            "换压缩阈值要变",
            f,
            base.copy(maxInputMegapixels = 0.6f).fingerprint("SR_REALCUGAN_CONSERVATIVE", 2, 0),
        )
        assertEquals("同样的选项必须给同样的指纹（缓存命中靠它）", f, base.fingerprint("SR_REALCUGAN_CONSERVATIVE", 2, 0))
    }
}
