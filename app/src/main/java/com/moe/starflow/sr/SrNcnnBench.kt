package com.moe.starflow.sr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.moe.starflow.sr.ncnn.SrNcnnNative
import com.moe.starflow.utils.LogCollector
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * **ncnn 超分模型真机基准**（开发者工具，**不是产品路径**）。
 *
 * 与 [SrBenchmark]（ONNX/AnimeJaNai 那套）并列：那个跑 `files/sr/` 下的 `.onnx`，
 * 这个跑 `files/sr/` 下的 `.param` + `.bin`（ncnn 族，即产品实际用的那一套）。
 *
 * ## 怎么跑（debug 包有效；正式包 `BuildConfig.DEBUG` 直接 return）
 * ```
 * adb shell am broadcast -a com.moe.starflow.SR_BENCH --ez ncnn true
 * adb shell am broadcast -a com.moe.starflow.SR_BENCH --ez ncnn true --es model nose
 * adb shell am broadcast -a com.moe.starflow.SR_BENCH --ez ncnn true --ei tile 128 --ez fp16a true
 * adb shell cat /storage/emulated/0/Android/data/com.moe.starflow/files/sr_ncnn_benchmark.txt
 * ```
 *
 * ## 为什么直接调 `SrNcnnNative` 而不是 `NcnnSrEngine`
 * 为了把**三段耗时拆开**：`NcnnSrEngine.upscale()` 一次调用里混着
 * ① Bitmap→紧凑 RGB8 拆包 ② native `process()`（真正的推理）③ 紧凑 RGB8→Bitmap 回填。
 * 混在一起只有一个总数，出了"慢"又要靠猜。这里三段各自计时，但**参数与产品完全一致**
 * （同一份清单、同一个 `create()`/`process()` 签名、同一个 tile/精度）。
 *
 * ## 两套参数（`mode`）
 * | mode | 含义 |
 * |---|---|
 * | `theirs`（默认） | 尽可能贴参照项目 `mihon_img_upscale` 的设置：tile **128**、fp16 算术 **off**、通用族 padding 按它的 `w2xExModel`（W2xEX/sudo/AnimeJaNai = **10**、SPAN = **24**） |
 * | `ours` | 按我们自己清单里的 `prepad`（W2xEX = 16 等），其余同上 |
 *
 * ⚠️ 单位口径：报告里 `ms/MP` 按**输出**百万像素算，方便不同模型/不同倍率横向比。
 */
object SrNcnnBench {

    private const val TAG = "SrNcnnBench"
    private const val WARMUP = 1
    private const val RUNS = 3

    /** 4x 模型在同样输入下输出是 2x 的 4 倍 → 先把输入压到输出 ≤ ~10MP，避免基准本身 OOM。 */
    private const val MAX_OUTPUT_PIXELS = 10_000_000L

    private data class Entry(
        val key: String,
        val family: Int,
        val familyName: String,
        val scale: Int,
        val noise: Int,
        val prepad: Int,
        val paramName: String,
        val binName: String,
        val blobIn: String,
        val blobOut: String,
        val bgr: Boolean,
    )

    fun run(
        context: Context,
        filter: String? = null,
        tile: Int = 128,
        fp16a: Boolean = false,
        mode: String = "theirs",
        threads: Int = srThreads(),
        /** true = 把每一档的产物 PNG 存到 files/sr_bench_out/（偏移/接缝实测用） */
        saveOutput: Boolean = false,
    ) {
        val app = context.applicationContext
        Thread {
            val out = File(app.getExternalFilesDir(null), "sr_ncnn_benchmark.txt")
            fun emit(s: String) {
                LogCollector.i(TAG, s)
                runCatching { out.appendText(s + "\n") }
            }
            val started = System.currentTimeMillis()
            try {
                val srDir = File(app.getExternalFilesDir(null), "sr")
                SrBenchInput.load(app)
                val src0 = SrBenchInput.bitmap()
                emit("===== ncnn 基准 ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} =====")
                emit("设备=${android.os.Build.MODEL}  核数=${Runtime.getRuntime().availableProcessors()}  " +
                    "Vulkan=${runCatching { SrNcnnNative.gpuName() }.getOrDefault("?")}" +
                    "(${runCatching { SrNcnnNative.gpuCount() }.getOrDefault(0)})")
                emit("参数: tile=$tile  fp16算术=$fp16a  mode=$mode  线程=$threads  预热$WARMUP+计时$RUNS(取最小)")
                if (src0 == null) {
                    emit("测试图缺失（files/sr_bench_input.png|webp|jpg）→ 本次跳过")
                    emit("===== 结束 =====")
                    return@Thread
                }
                emit("测试图=${SrBenchInput.sourceLabel}  送入 ${src0.width}x${src0.height}")

                val entries = loadManifest(app)
                if (entries.isEmpty()) {
                    emit("清单解析为空 → 本次跳过")
                    emit("===== 结束 =====")
                    return@Thread
                }
                var tested = 0
                for (e in entries) {
                    if (filter != null && !e.key.contains(filter, ignoreCase = true)) continue
                    val param = File(srDir, e.paramName)
                    val bin = File(srDir, e.binName)
                    if (!param.isFile || !bin.isFile) {
                        emit("── ${e.key}: 跳过（模型未下载）")
                        continue
                    }
                    tested++
                    emit("")
                    emit("── ${e.key}  ${e.familyName} ${e.scale}x  prepad=${if (mode == "ours") e.prepad else theirPadding(e)}")
                    emit(measure(e, srDir, src0, tile, fp16a, mode, threads, saveOutput, app))
                    // 每个模型之间留口气：连续满负荷会把 SoC 打降频，后面几个模型的数字就不公平了
                    runCatching { Thread.sleep(300) }
                }
                emit("")
                emit("共测 $tested 个模型，用时 ${(System.currentTimeMillis() - started) / 1000}s")
                emit("===== 结束 =====")
            } catch (t: Throwable) {
                emit("整轮失败: ${t.javaClass.simpleName}: ${t.message}")
            }
        }.apply { name = "sr-ncnn-bench"; isDaemon = true }.start()
    }

    /** @return 一行结论：装载/拆包/推理/回填/输出/ms每次 */
    private fun measure(
        e: Entry,
        srDir: File,
        src0: Bitmap,
        tile: Int,
        fp16a: Boolean,
        mode: String,
        threads: Int,
        saveOutput: Boolean,
        app: android.content.Context,
    ): String {
        val prepad = if (mode == "ours") e.prepad else theirPadding(e)
        // 4x：把源图压到输出 ≤ MAX_OUTPUT_PIXELS，否则基准自己就先 OOM 了
        var src = src0
        val cap = MAX_OUTPUT_PIXELS / (e.scale.toLong() * e.scale.toLong())
        if (src0.width.toLong() * src0.height.toLong() > cap) {
            val k = kotlin.math.sqrt(cap.toDouble() / (src0.width.toLong() * src0.height.toLong()))
            val w = (src0.width * k).toInt().coerceAtLeast(16)
            val h = (src0.height * k).toInt().coerceAtLeast(16)
            src = Bitmap.createScaledBitmap(src0, w, h, true)
        }
        val w = src.width
        val ht = src.height
        val ow = w * e.scale
        val oh = ht * e.scale

        val tLoad = SystemClock.elapsedRealtime()
        val handle = SrNcnnNative.create(
            e.family,
            File(srDir, e.paramName).absolutePath,
            File(srDir, e.binName).absolutePath,
            0, threads, fp16a, e.blobIn, e.blobOut, e.bgr,
        )
        val loadMs = SystemClock.elapsedRealtime() - tLoad
        if (handle == 0L) {
            if (src !== src0) src.recycle()
            return "  引擎创建失败（param/bin 不匹配该引擎族？）"
        }
        try {
            // ① 拆包（Bitmap ARGB_8888 → 紧凑 RGB8）
            val inBuf = ByteBuffer.allocateDirect(w * ht * 3).order(ByteOrder.nativeOrder())
            val tPack = SystemClock.elapsedRealtime()
            packRgb(src, inBuf)
            val packMs = SystemClock.elapsedRealtime() - tPack

            val outBuf = ByteBuffer.allocateDirect(ow * oh * 3).order(ByteOrder.nativeOrder())

            // ② 推理：预热 + 计时
            val times = ArrayList<Long>(RUNS)
            var warm = 0L
            var ok = true
            for (i in 0..RUNS) {
                val s = SystemClock.elapsedRealtime()
                ok = SrNcnnNative.process(handle, inBuf, outBuf, w, ht, e.scale, e.noise, prepad, tile)
                val cost = SystemClock.elapsedRealtime() - s
                if (!ok) break
                if (i == 0) warm = cost else times += cost
            }
            if (!ok) return "  推理失败（native process 返回 false）"

            // ③ 回填（紧凑 RGB8 → Bitmap）
            val tBack = SystemClock.elapsedRealtime()
            val bmp = unpackRgb(outBuf, ow, oh)
            val backMs = SystemClock.elapsedRealtime() - tBack

            val min = times.minOrNull() ?: 0L
            val med = times.sorted().let { it[it.size / 2] }
            val mp = ow.toDouble() * oh / 1_000_000.0
            // 产物存盘（只有显式要求时才做；用于本地做"产物 vs 原图"的位移/接缝分析）
            if (saveOutput && bmp != null) {
                runCatching {
                    val dir = File(app.getExternalFilesDir(null), "sr_bench_out")
                        .apply { mkdirs() }
                    File(dir, "${e.key}_prepad${prepad}_tile$tile.png").outputStream().use { os ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                    }
                }.onFailure { LogCollector.w(TAG, "产物存盘失败: ${e.key}", it) }
            }
            val stats = stats(bmp)
            bmp?.recycle()

            val mn = stats?.first ?: -1
            val mx = stats?.second ?: -1
            val flag = when {
                stats == null -> "输出读取失败"
                mx == 0 -> "⚠全黑/全零"
                mn == mx -> "⚠纯色常量（引擎分支选错了？）"
                else -> "OK"
            }
            return "  装载=${loadMs}ms  拆包=${packMs}ms  推理 min=${min}ms med=${med}ms (预热${warm}ms, ${times.joinToString("/")})  " +
                "回填=${backMs}ms  输出=${ow}x$oh (${"%.1f".format(mp)}MP, ${"%.0f".format(min / mp)}ms/MP)  " +
                "像素[min=$mn,max=$mx]  $flag"
        } finally {
            SrNcnnNative.release(handle)
            if (src !== src0) src.recycle()
        }
    }

    // ===== Bitmap <-> 紧凑 RGB8（与 NcnnSrEngine.upscale 同一套循环，逐行处理） =====

    private fun packRgb(src: Bitmap, buf: ByteBuffer) {
        val w = src.width
        val h = src.height
        val row = IntArray(w)
        var o = 0
        for (y in 0 until h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                buf.put(o, (c shr 16).toByte())
                buf.put(o + 1, (c shr 8).toByte())
                buf.put(o + 2, c.toByte())
                o += 3
            }
        }
    }

    private fun unpackRgb(buf: ByteBuffer, w: Int, h: Int): Bitmap? = runCatching {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        var q = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val r = buf.get(q).toInt() and 0xFF
                val g = buf.get(q + 1).toInt() and 0xFF
                val b = buf.get(q + 2).toInt() and 0xFF
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                q += 3
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        bmp
    }.getOrNull()

    /** @return (min, max) 抽样极值；全黑 = (0,0)、纯色常量 = min == max */
    private fun stats(bmp: Bitmap?): Pair<Int, Int>? {
        if (bmp == null) return null
        var mn = 255
        var mx = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                // ⚠️ 括号必须写全：`and` 是中缀函数，优先级**低于** `+`，
                //    写成 `(c shr 16) and 0xFF + (c shr 8) and 0xFF` 会解析成 `a and (0xFF + b)`
                val l = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
                if (l < mn) mn = l
                if (l > mx) mx = l
                x += 37
            }
            y += 37
        }
        return mn to mx
    }

    // ===== 清单 =====

    private fun loadManifest(app: Context): List<Entry> {
        val json = runCatching {
            app.assets.open("models/downloadinfo.json").use { it.readBytes().decodeToString() }
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONObject(json).getJSONArray("models") }.getOrNull() ?: return emptyList()
        val list = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fam = o.optString("family", "")
            val familyInt = when (fam) {
                "waifu2x" -> SrNcnnNative.FAMILY_WAIFU2X
                "srmd" -> SrNcnnNative.FAMILY_SRMD
                "realcugan" -> SrNcnnNative.FAMILY_REALCUGAN
                "realesrgan" -> SrNcnnNative.FAMILY_REALESRGAN
                "generic" -> SrNcnnNative.FAMILY_GENERIC
                else -> continue
            }
            val files = o.optJSONArray("files") ?: continue
            var param = ""
            var bin = ""
            for (j in 0 until files.length()) {
                val f = files.optJSONObject(j) ?: continue
                val n = f.optString("file_name", "")
                when {
                    n.endsWith(".param") -> param = n
                    n.endsWith(".bin") -> bin = n
                }
            }
            if (param.isEmpty() || bin.isEmpty()) continue
            list += Entry(
                key = o.optString("model_key", "?"),
                family = familyInt,
                familyName = fam,
                scale = o.optInt("scale", 2),
                noise = o.optInt("noise", 0),
                prepad = o.optInt("prepad", 0),
                paramName = param,
                binName = bin,
                blobIn = o.optString("blobIn", ""),
                blobOut = o.optString("blobOut", ""),
                bgr = o.optBoolean("bgr", false),
            )
        }
        return list
    }

    /**
     * 参照项目 `Waifu2x.w2xExModel(...)` 的 padding（它的通用族默认 **10**，SPAN 显式 **24**）。
     * 其余族（waifu2x/srmd/realcugan/realesrgan）按我们清单里的值 —— 那是 nihui 各工具的口径。
     */
    private fun theirPadding(e: Entry): Int = when {
        e.key.contains("SPAN") -> 24
        e.key.contains("W2XEX") || e.key.contains("SUDO") -> 10
        else -> e.prepad
    }
}
