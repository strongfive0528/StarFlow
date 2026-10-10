package com.moe.starflow.sr

import android.content.Context
import android.content.SharedPreferences
import com.moe.starflow.download.ModelDownloadRepository
import com.moe.starflow.download.ModelInfo
import com.moe.starflow.download.ModelKey
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** A chapter and each queued page retain the configuration selected at submission. */
data class SrRequest(val model: ModelKey?, val info: ModelInfo?, val options: SrOptions) {
    val weightRevision: String get() = info?.files?.joinToString("+") { it.checksum }.orEmpty()
    val fingerprint: String get() = options.fingerprint(model?.name, info?.srScale ?: 2, info?.srNoise ?: 0) + "|weights$weightRevision"
    fun describe(): JSONObject = JSONObject().put("model", model?.name ?: "-")
        .put("weights", weightRevision).put("tileRequested", options.effectiveTile)
        .put("precisionRequested", if(options.precision == 0) "FP16" else "FP32")
        .put("fp16ArithmeticRequested", options.fp16Arithmetic)
        .put("performanceMode", options.performanceMode).put("tileSleepMs", options.tileSleepMs)
        .put("thresholdMP", options.maxInputMegapixels).put("scale", info?.srScale ?: 2)
    companion object {
        val nextRunId = AtomicLong()
        fun capture(context: Context, prefs: SharedPreferences): SrRequest {
            val key = SrModelManager.getActiveKey(prefs)
            return SrRequest(key, key?.let { ModelDownloadRepository.getInstance(context).getModelInfo(it) }, SrOptions.from(prefs))
        }
    }
}
