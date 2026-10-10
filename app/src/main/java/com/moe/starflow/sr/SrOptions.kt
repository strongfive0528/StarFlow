package com.moe.starflow.sr

import android.content.SharedPreferences

/** User options. Performance affects scheduling; all other options can affect pixels. */
data class SrOptions(
    val tileSize: Int = DEFAULT_TILE_SIZE,
    val performanceMode: Int = DEFAULT_PERFORMANCE_MODE,
    val precision: Int = DEFAULT_PRECISION,
    val fp16Arithmetic: Boolean = DEFAULT_FP16_ARITHMETIC,
    val maxInputMegapixels: Float = DEFAULT_MAX_INPUT_MP,
) {
    val tileSleepMs: Int get() = when (performanceMode) { 1 -> 5; 2 -> 15; else -> 0 }
    val effectiveTile: Int get() = if (tileSize <= 0) 0 else tileSize.coerceAtLeast(MIN_TILE_SIZE)
    fun engineKey(): String = "t$effectiveTile|p$precision|f${if (fp16Arithmetic) 1 else 0}"
    fun fingerprint(modelKey: String?, scale: Int, noise: Int): String =
        "${modelKey ?: "-"}|s$scale|n$noise|${engineKey()}|mp$maxInputMegapixels"
    companion object {
        const val DEFAULT_TILE_SIZE = 128
        const val MIN_TILE_SIZE = 32
        const val DEFAULT_PERFORMANCE_MODE = 0
        const val DEFAULT_PRECISION = 0
        const val DEFAULT_FP16_ARITHMETIC = false
        const val DEFAULT_MAX_INPUT_MP = 1.5f
        const val PRECISION_FP16 = 0
        const val PRECISION_FP32 = 1
        const val KEY_TILE_SIZE = "sr_opt_tile_size"
        const val KEY_PERFORMANCE_MODE = "sr_opt_performance_mode"
        const val KEY_PRECISION = "sr_opt_precision"
        const val KEY_FP16_ARITHMETIC = "sr_opt_fp16_arithmetic"
        const val KEY_MAX_INPUT_MP = "sr_opt_max_input_mp"
        fun from(prefs: SharedPreferences) = SrOptions(
            tileSize = prefs.getInt(KEY_TILE_SIZE, DEFAULT_TILE_SIZE).let { if (it <= 0) 0 else it.coerceIn(32, 4096) },
            performanceMode = prefs.getInt(KEY_PERFORMANCE_MODE, DEFAULT_PERFORMANCE_MODE).coerceIn(0, 2),
            precision = prefs.getInt(KEY_PRECISION, DEFAULT_PRECISION).coerceIn(0, 1),
            fp16Arithmetic = prefs.getBoolean(KEY_FP16_ARITHMETIC, DEFAULT_FP16_ARITHMETIC),
            maxInputMegapixels = prefs.getFloat(KEY_MAX_INPUT_MP, DEFAULT_MAX_INPUT_MP).let { if (it.isFinite()) it.coerceIn(0f, 8f) else DEFAULT_MAX_INPUT_MP },
        )
        fun write(prefs: SharedPreferences, o: SrOptions) {
            prefs.edit().putInt(KEY_TILE_SIZE, o.tileSize)
                .putInt(KEY_PERFORMANCE_MODE, o.performanceMode).putInt(KEY_PRECISION, o.precision)
                .putBoolean(KEY_FP16_ARITHMETIC, o.fp16Arithmetic).putFloat(KEY_MAX_INPUT_MP, o.maxInputMegapixels)
                .remove("sr_opt_preload_pages").remove("sr_opt_skip_max_width").remove("sr_opt_skip_max_height").apply()
        }
    }
}
