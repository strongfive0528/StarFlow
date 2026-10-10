package com.moe.starflow.sr

import android.content.Context
import com.moe.starflow.download.ModelKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `sr_active_model_key` 的档位迁移守卫。
 *
 * 历史：档位改过两轮（upconv_7 精简、照片族下架又回归），**2026-10-10 整套换成
 * 参照项目 `mihon_img_upscale` 的 11 档**（旧档位全部下架）。所以现在的规矩是：
 *
 * 1. 用户 prefs 里存着**已下架**的旧档位名 → [SrModelManager.getActiveKey] 必须**就近迁移**
 *    到语义最接近的新档位并回写，而不是当作"未选择"（否则超分开着却没有模型，用户以为功能坏了）；
 * 2. **仍在架上的档位名绝不能被重定向**（"选了 A 却跑 B"是最难查的静默错误）；
 * 3. 完全不认识的值仍然是「未选择」（走得通 [SrOutcome] 的 no-model 分支）。
 */
@RunWith(RobolectricTestRunner::class)
class SrModelKeyMigrationTest {

    private val prefs get() =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("sr_migration_test", Context.MODE_PRIVATE)

    private fun store(raw: String) {
        prefs.edit().putString(SrModelManager.PREF_ACTIVE_KEY, raw).commit()
    }

    @Test
    fun activeKeyRoundTrips() {
        SrModelManager.setActive(prefs, ModelKey.SR_REALCUGAN_SE)
        assertEquals(ModelKey.SR_REALCUGAN_SE, SrModelManager.getActiveKey(prefs))
    }

    /** 旧 upconv_7 动漫档 → 现行的 Waifu2x Fast（同一族、同一套权重）。 */
    @Test
    fun legacyUpconv7MigratesToWaifu2xFast() {
        for (legacy in listOf("SR_W2X_UP7_ANIME_M1", "SR_W2X_UP7_ANIME_N2", "SR_W2X_UP7_ANIME_N3")) {
            store(legacy)
            assertEquals("$legacy 必须迁到 Waifu2x Fast", ModelKey.SR_WAIFU2X_FAST, SrModelManager.getActiveKey(prefs))
            assertEquals(
                "迁移结果要回写，别每次读都再迁一遍",
                ModelKey.SR_WAIFU2X_FAST.name,
                prefs.getString(SrModelManager.PREF_ACTIVE_KEY, null),
            )
        }
    }

    /** 旧照片族（已整族下架）→ Waifu2x Fast（就近的同族权重）。 */
    @Test
    fun legacyUpconvPhotoMigratesToWaifu2xFast() {
        for (legacy in listOf("SR_W2X_UP7_PHOTO_M1", "SR_W2X_UP7_PHOTO_N2", "SR_W2X_UP7_PHOTO_N3")) {
            store(legacy)
            assertEquals(ModelKey.SR_WAIFU2X_FAST, SrModelManager.getActiveKey(prefs))
        }
    }

    /** 旧 Real-CUGAN 各档 → 新的 SE / Pro。 */
    @Test
    fun legacyRealCuganMigratesToSeOrPro() {
        store("SR_REALCUGAN_NODENOISE")
        assertEquals(ModelKey.SR_REALCUGAN_SE, SrModelManager.getActiveKey(prefs))
        store("SR_REALCUGAN_PRO_CONSERVATIVE")
        assertEquals(ModelKey.SR_REALCUGAN_PRO, SrModelManager.getActiveKey(prefs))
        store("SR_REALCUGAN_NOSE_2X")
        assertEquals(ModelKey.SR_REALCUGAN_NOSE, SrModelManager.getActiveKey(prefs))
    }

    /** 旧的 SRMD / Real-ESRGAN 6B → 新集里最接近的档位。 */
    @Test
    fun legacySrmdAndEsrganMigrate() {
        store("SR_SRMD_X2")
        assertEquals(ModelKey.SR_WAIFU2X_CUNET, SrModelManager.getActiveKey(prefs))
        store("SR_REALESRGAN_ANIME6B")
        assertEquals(ModelKey.SR_REALESRGAN, SrModelManager.getActiveKey(prefs))
    }

    /** 完全不认识的值仍然是「未选择」。 */
    @Test
    fun unknownKeyStaysNull() {
        store("NOT_A_MODEL_KEY")
        assertNull(SrModelManager.getActiveKey(prefs))
    }

    /** 在架档位 = 参照项目那 11 档，顺序与 `downloadinfo.json` 一致。 */
    @Test
    fun activeCatalogIsTheReferenceProjectSet() {
        assertEquals(
            listOf(
                ModelKey.SR_REALCUGAN_SE, ModelKey.SR_REALCUGAN_PRO, ModelKey.SR_REALCUGAN_NOSE,
                ModelKey.SR_WAIFU2X_CUNET, ModelKey.SR_WAIFU2X_FAST, ModelKey.SR_REALESRGAN,
                ModelKey.SR_W2XEX_UNIVERSAL_FAST, ModelKey.SR_W2XEX_OMNI_MINI_V2, ModelKey.SR_W2XEX_PHOTO_SMALL,
                ModelKey.SR_SUDO_ULTRA_COMPACT, ModelKey.SR_SPAN_NOMOSUNI,
            ),
            SrModelManager.allKeys,
        )
    }

    /** 每一档都必须有显示名（漏了名字在设置页/下载通知里就是空标题）。 */
    @Test
    fun everyKeyHasDisplayName() {
        for (key in SrModelManager.allKeys) {
            assertNotEquals("$key 缺显示名", 0, SrModelManager.nameResOf(key))
        }
    }
}
