package com.moe.starflow.sr

import android.content.SharedPreferences

/**
 * 一次超分的**全部可调项** —— 逐项对齐 `HaoweiLi97/mihon_img_upscale`
 * （`ui/reader/setting/ReaderPreferences.kt` 的 `pref_realcugan_*` + `data/coil/TachiyomiImageDecoder.kt`
 * 的 skip / prescale / configHash 逻辑）。
 *
 * | 本类字段 | 他们的对应项 | 他们的默认值 |
 * |---|---|---|
 * | [skipMaxWidth] / [skipMaxHeight] | `realCuganSkipMaxSizeWidth/Height` | **0 = 不跳过** |
 * | [tileSize] | `realCuganTileSize`（最小 32） | **128** |
 * | [performanceMode] | `realCuganPerformanceMode` | **0 = 90%（不休眠）** |
 * | [precision] | `realCuganPrecision`（0 FP16 / 1 FP32 / 2 INT8 / 3 BF16） | **0 = FP16** |
 * | [fp16Arithmetic] | `realCuganFp16Arithmetic` | **false**（可勾选） |
 *
 * ⚠️ **INT8 / BF16 我们刻意不提供**（[PRECISION_FP16] / [PRECISION_FP32] 两档）：
 * nihui 这套权重是 fp32 且不带量化 scale，硬开 int8 算术在 ncnn 里没有意义（他们能开是因为
 * 他们的模型/后端带量化参数）。BF16 只在极少数桌面 GPU 上有算术核，移动端等于 FP32。
 *
 * ⚠️ **配置指纹（[fingerprint]）是缓存键的一部分**：他们的 `configHash` 就是这个用途 ——
 * 任何一项变了，旧产物/旧引擎都必须作废，否则会出现"改了设置画面不变"。
 */
data class SrOptions(
    /** 超过就直接不超分（逐轴判，与他们的 `exceedsSkipLimit` 完全同形）；0 = 不限制 */
    val skipMaxWidth: Int = DEFAULT_SKIP_MAX,
    val skipMaxHeight: Int = DEFAULT_SKIP_MAX,
    /** 分块尺寸；他们的默认 128，最小 32（我们透传给原生 `tilesize`） */
    val tileSize: Int = DEFAULT_TILE_SIZE,
    /** 0 = 90%（块间不休眠）、1 = 50%（5ms）、2 = 30%（15ms） */
    val performanceMode: Int = DEFAULT_PERFORMANCE_MODE,
    /** 0 = FP16、1 = FP32（他们那两个之外的两档见类注释，我们不提供） */
    val precision: Int = DEFAULT_PRECISION,
    /** 算力精度开关（FP16 算术）；默认 **false**，对齐他们的模型包/设置默认 */
    val fp16Arithmetic: Boolean = DEFAULT_FP16_ARITHMETIC,
    /**
     * 预加载页数（当前页 ±N）—— 对齐他们的 `pref_realcugan_preload_size`（默认 **3**）。
     * 走 [SrEnhanceQueue.enqueueAround]，**只在没有 OCR 需求时**才启动（见阅读器接线）。
     */
    val preloadPages: Int = DEFAULT_PRELOAD_PAGES,
) {

    /** 他们的 `tileSleepMs`：0 → 0ms、1 → 5ms、2 → 15ms（块间休眠，用速度换前台流畅） */
    val tileSleepMs: Int
        get() = when (performanceMode) {
            1 -> 5
            2 -> 15
            else -> 0
        }

    /**
     * 只用于 ncnn 引擎的**重建判据**（tile/precision/fp16 都是 net 级别、`load()` 时固化，
     * 改了必须重建实例；与他们的 `currentConfigHash` 同一职责）。
     */
    fun engineKey(): String = "t${tileSize.coerceAtLeast(MIN_TILE_SIZE)}|p$precision|f${if (fp16Arithmetic) 1 else 0}"

    /**
     * 完整指纹（含模型/倍率/降噪）：进缓存键。
     * ⚠️ 新增任何影响产物的选项**必须**加进来，否则就是"改了设置画面不变"。
     */
    fun fingerprint(modelKey: String?, scale: Int, noise: Int): String =
        "${modelKey ?: "-"}|s$scale|n$noise|${engineKey()}|skip${skipMaxWidth}x$skipMaxHeight"

    companion object {
        /** 0 = 不跳过（与他们的 `skip_max_size_*` 默认一致） */
        const val DEFAULT_SKIP_MAX = 0

        /** 他们的 `realCuganTileSize` 默认值 */
        const val DEFAULT_TILE_SIZE = 128

        /** 他们的原生侧也会 `.coerceAtLeast(32)` */
        const val MIN_TILE_SIZE = 32

        const val DEFAULT_PERFORMANCE_MODE = 0
        const val DEFAULT_PRECISION = 0
        const val DEFAULT_FP16_ARITHMETIC = false

        /** 他们的 `pref_realcugan_preload_size` 默认值 */
        const val DEFAULT_PRELOAD_PAGES = 3

        const val PRECISION_FP16 = 0
        const val PRECISION_FP32 = 1

        const val KEY_SKIP_MAX_WIDTH = "sr_opt_skip_max_width"
        const val KEY_SKIP_MAX_HEIGHT = "sr_opt_skip_max_height"
        const val KEY_TILE_SIZE = "sr_opt_tile_size"
        const val KEY_PERFORMANCE_MODE = "sr_opt_performance_mode"
        const val KEY_PRECISION = "sr_opt_precision"
        const val KEY_FP16_ARITHMETIC = "sr_opt_fp16_arithmetic"
        const val KEY_PRELOAD_PAGES = "sr_opt_preload_pages"

        /**
         * 从 prefs 读（**唯一读入口**）—— 三处调用点（单页 / 章节批量 / 预加载）必须同源，
         * 否则又会出现"有的路径跳、有的不跳"。
         */
        fun from(prefs: SharedPreferences): SrOptions = SrOptions(
            skipMaxWidth = prefs.getInt(KEY_SKIP_MAX_WIDTH, DEFAULT_SKIP_MAX),
            skipMaxHeight = prefs.getInt(KEY_SKIP_MAX_HEIGHT, DEFAULT_SKIP_MAX),
            tileSize = prefs.getInt(KEY_TILE_SIZE, DEFAULT_TILE_SIZE),
            performanceMode = prefs.getInt(KEY_PERFORMANCE_MODE, DEFAULT_PERFORMANCE_MODE)
                .coerceIn(0, 2),
            precision = prefs.getInt(KEY_PRECISION, DEFAULT_PRECISION).coerceIn(0, 1),
            fp16Arithmetic = prefs.getBoolean(KEY_FP16_ARITHMETIC, DEFAULT_FP16_ARITHMETIC),
            preloadPages = prefs.getInt(KEY_PRELOAD_PAGES, DEFAULT_PRELOAD_PAGES).coerceIn(0, 8),
        )

        fun write(prefs: SharedPreferences, options: SrOptions) {
            prefs.edit()
                .putInt(KEY_SKIP_MAX_WIDTH, options.skipMaxWidth)
                .putInt(KEY_SKIP_MAX_HEIGHT, options.skipMaxHeight)
                .putInt(KEY_TILE_SIZE, options.tileSize)
                .putInt(KEY_PERFORMANCE_MODE, options.performanceMode)
                .putInt(KEY_PRECISION, options.precision)
                .putBoolean(KEY_FP16_ARITHMETIC, options.fp16Arithmetic)
                .putInt(KEY_PRELOAD_PAGES, options.preloadPages)
                .apply()
        }
    }
}
