package com.moe.starflow.sr

/**
 * **「这一页要不要跑超分」的纯判据** —— 与 `HaoweiLi97/mihon_img_upscale`
 * 的 `TachiyomiImageDecoder` 判据**逐字同形**：
 *
 * ```kotlin
 * val exceedsSkipLimit =
 *     (skipMaxWidth > 0 && bitmap.width > skipMaxWidth) ||
 *         (skipMaxHeight > 0 && bitmap.height > skipMaxHeight)
 * ```
 *
 * ⇒ **逐轴判**（不是"长边"），任一轴超过且该轴阈值 > 0 就跳过；两个阈值都是 0 时永不跳过。
 * （2026-10 P1 我先按"长边"实现过一版，那时参照的是另一个 fork；现按本项目对齐。）
 *
 * ⚠️ **默认 0 = 不跳过**：这是"少干活"的开关，一旦默认打开，用户会看到「超分没反应」——
 * 必须由用户显式设阈值，并且**在动手之前**就给出具体原因（[detail]）。
 */
object SrSkipPolicy {

    /** 0 = 该轴不限制 */
    const val DISABLED = 0

    /** 设置页候选阈值（逐轴像素）；0 放第一档 = "不跳过（默认）" */
    val PRESETS = intArrayOf(DISABLED, 1200, 1600, 2000, 2560)

    /** 该页是否因超过阈值而**直接跳过**（不推理、不落盘） */
    fun shouldSkip(width: Int, height: Int, skipMaxWidth: Int, skipMaxHeight: Int): Boolean =
        (skipMaxWidth > DISABLED && width > skipMaxWidth) ||
            (skipMaxHeight > DISABLED && height > skipMaxHeight)

    /** 便捷重载：两个轴用同一个阈值 */
    fun shouldSkip(width: Int, height: Int, skipMax: Int): Boolean =
        shouldSkip(width, height, skipMax, skipMax)

    /**
     * 失败原因里那行 ASCII 细节（接口契约：给日志和"复制发给我"用，不做本地化）。
     * 带上**实际尺寸**与**生效阈值**，用户一眼能判断该调阈值还是该换书。
     */
    fun detail(width: Int, height: Int, skipMaxWidth: Int, skipMaxHeight: Int): String =
        "page ${width}x$height > skip limit ${skipMaxWidth}x$skipMaxHeight"
}
