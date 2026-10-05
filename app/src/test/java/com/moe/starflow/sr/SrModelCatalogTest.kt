package com.moe.starflow.sr

import com.moe.starflow.download.ModelKey
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 超分模型清单的**跨来源一致性**守卫（`tools/sr-research/verify_sr_wiring.py` 那份自检的替补：
 * 该脚本在部分检出里并不存在，这里把它最要命的几条不变式钉进单测）。
 *
 * 四条不变式，全部是"漏改不报错、只是静默跑错/白下载"的那类：
 * 1. **枚举 == JSON == allKeys**：JSON 里多一条（用户看得见、代码不认）或 allKeys 多一条
 *    （界面有行、清单没条目 → 点下载直接报「清单里没有这个模型的下载条目」）都要红。
 * 2. **所有 SR 模型的 file_name 在同一个 `files/sr/` 目录里必须互不重名** ——
 *    waifu2x 的 cunet 与 upconv_7 上游**同名**，Real-CUGAN SE 与 Pro 的 `up2x-*.param` 也**同名**；
 *    清单里不改名就会互相覆盖（历史上已经栽过两次）。
 * 3. 每个条目必须是完整的 ncnn 双文件（`.param` + `.bin`）、有 family/scale/prepad、
 *    且 checksum 是 32 位十六进制（下载校验走 MD5，不按 file_size）。
 * 4. Real-CUGAN **Pro** 三档必须真的存在、且与 SE 三档**不是同一批文件**（防止"加了 key 却指向 SE"）。
 *
 * ⚠️ 必须跑 Robolectric：要读 assets 里的 downloadinfo.json，且 Android 的 `org.json` 在纯 JVM 单测里是桩。
 */
@RunWith(RobolectricTestRunner::class)
class SrModelCatalogTest {

    private fun entries(): Map<String, JSONObject> {
        val text = RuntimeEnvironment.getApplication().assets
            .open("models/downloadinfo.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("models")
        return (0 until arr.length())
            .map { arr.getJSONObject(it) }
            .filter { it.getString("model_key").startsWith("SR_") }
            .associateBy { it.getString("model_key") }
    }

    private val srKeys get() = SrModelManager.allKeys

    @Test
    fun catalogAndEnumerationAgreeBothWays() {
        val byKey = entries()
        for (key in srKeys) {
            assertTrue(
                "allKeys 里的 $key 在 downloadinfo.json 里没有条目（点下载会报「清单里没有这个模型」）",
                byKey.containsKey(key.name),
            )
        }
        for (name in byKey.keys) {
            assertTrue(
                "downloadinfo.json 里的 $name 不在 SrModelManager.allKeys 里（模型管理页看不到它）",
                srKeys.any { it.name == name },
            )
        }
    }

    @Test
    fun superResolutionFileNamesAreUnique() {
        val seen = mutableMapOf<String, String>()
        for ((key, entry) in entries()) {
            val files = entry.getJSONArray("files")
            for (i in 0 until files.length()) {
                val name = files.getJSONObject(i).getString("file_name")
                val other = seen.put(name, key)
                assertTrue(
                    "文件重名：$name 同时属于 $other 与 $key —— 它们下载到同一个 files/sr/ 目录，会互相覆盖",
                    other == null,
                )
            }
        }
    }

    @Test
    fun everyEntryIsACompleteNcnnModel() {
        for ((key, entry) in entries()) {
            val family = entry.optString("family", "")
            assertTrue(
                "$key 的 family 必须是 ncnn 四族之一，实际是 '$family'",
                family in setOf("waifu2x", "srmd", "realcugan", "realesrgan", "generic"),
            )
            assertTrue("$key 缺 scale", entry.optInt("scale", 0) in setOf(2, 3, 4))
            assertTrue("$key 缺 prepad", entry.optInt("prepad", 0) > 0)
            if (family == "generic") {
                // 通用 ncnn 模型（P4 加的入口）：blob 名必须显式给出 —— 默认的 Input1/Eltwise4 是
                // waifu2x 家族的，通用模型（SPAN/sudo/W2xEX…）不写就必然加载/推理失败。
                assertTrue("$key 是 generic 却没写 blobIn", entry.optString("blobIn", "").isNotEmpty())
                assertTrue("$key 是 generic 却没写 blobOut", entry.optString("blobOut", "").isNotEmpty())
            }
            assertTrue("$key 缺 browser_url", entry.optString("browser_url", "").startsWith("https://"))
            // P1（TachiyomiX 同款选项）：算力精度必须逐条写明，缺字段虽然解析时默认 true，
            // 但"清单里看不见"就等于没人知道这档跑的是 FP16 还是 FP32 —— 显式声明。
            assertTrue("$key 缺 fp16Arithmetic（清单必须显式声明算力精度）", entry.has("fp16Arithmetic"))
            assertTrue(
                "$key 的 fp16Arithmetic 必须是布尔（写成 \"true\" 字符串会在解析时静默变默认值）",
                entry.get("fp16Arithmetic") is Boolean,
            )

            val files = entry.getJSONArray("files")
            assertEquals("$key 必须是 .param + .bin 两个文件", 2, files.length())
            val names = (0 until files.length()).map { files.getJSONObject(it).getString("file_name") }
            assertTrue("$key 缺 .param", names.any { it.endsWith(".param") })
            assertTrue("$key 缺 .bin", names.any { it.endsWith(".bin") })
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val md5 = f.optString("checksum", "")
                assertTrue(
                    "$key/${f.optString("file_name")} 的 checksum 不是 32 位十六进制（下载校验会永远失败）：$md5",
                    md5.length == 32 && md5.all { it in "0123456789abcdef" },
                )
                assertTrue("$key/${f.optString("file_name")} 的 file_size 必须为正", f.optLong("file_size", 0) > 0)
                assertTrue(
                    "$key/${f.optString("file_name")} 的下载地址必须是 https",
                    f.optString("download_url", "").startsWith("https://"),
                )
            }
        }
    }

    /**
     * Real-CUGAN **Pro** 三档（2026-10 从 TachiyomiX 模型包对照引入）。
     *
     * 这里把 **PC 端实测过的字节数与 MD5** 钉死：三档的 .param 是同一个架构文件
     * （`5c178928…`），只有 .bin 不同。改清单时若对不上，下载校验会把文件判成损坏并反复重下。
     */
    @Test
    fun realCuganProTiersAreRealAndDistinctFromSe() {
        val byKey = entries()
        val proKeys = listOf(
            ModelKey.SR_REALCUGAN_PRO_NODENOISE,
            ModelKey.SR_REALCUGAN_PRO_CONSERVATIVE,
            ModelKey.SR_REALCUGAN_PRO_DENOISE3X,
        )
        val expectedBinMd5 = mapOf(
            ModelKey.SR_REALCUGAN_PRO_NODENOISE to "04ce2955f98775e57e664e06c2b4d773",
            ModelKey.SR_REALCUGAN_PRO_CONSERVATIVE to "1f6824820c04bd61a8ddc9c0b4ef4f68",
            ModelKey.SR_REALCUGAN_PRO_DENOISE3X to "de65a912b80fca965f7f33656e49b41b",
        )
        val seFiles = mutableSetOf<String>()
        for (key in listOf(
            ModelKey.SR_REALCUGAN_NODENOISE,
            ModelKey.SR_REALCUGAN_CONSERVATIVE,
            ModelKey.SR_REALCUGAN_DENOISE3X,
        )) {
            val files = byKey.getValue(key.name).getJSONArray("files")
            for (i in 0 until files.length()) seFiles += files.getJSONObject(i).getString("file_name")
        }

        for (key in proKeys) {
            assertTrue("$key 必须在 allKeys 里", key in srKeys)
            val entry = byKey.getValue(key.name)
            assertEquals("$key 必须还是 realcugan 族（同族同接口，只换权重）", "realcugan", entry.getString("family"))
            assertEquals("$key 必须是 2x", 2, entry.getInt("scale"))
            assertEquals("$key 的 prepad 与 SE 一致（18）", 18, entry.getInt("prepad"))

            val files = entry.getJSONArray("files")
            val names = (0 until files.length()).map { files.getJSONObject(it).getString("file_name") }
            for (n in names) {
                assertFalse("$key 的 $n 与 Real-CUGAN SE 的文件同名（会互相覆盖）", n in seFiles)
            }
            val bin = (0 until files.length())
                .map { files.getJSONObject(it) }
                .first { it.getString("file_name").endsWith(".bin") }
            assertEquals("$key 的权重 md5 与 PC 端实测值不符", expectedBinMd5.getValue(key), bin.getString("checksum"))
        }
    }
}
