package com.moe.starflow.sr

/**
 * **「这一页值不值得跑超分」的纯判据** —— 对齐 TachiyomiX 模型包的 `maxProcessingResolution`。
 *
 * 起因（2026-10 用户追问「他们的超分很快，为什么到我们这里这么慢」）：
 * 那个项目给每个模型包声明了 `maxProcessingResolution`（默认 **1600×1600**），
 * **超过就直接不处理**；我们这边不论多大的页都要先压到短边 1080 再推理，
 * 于是 300 DPI 的 PDF 页（A4 = 2480×3508）白花 2~6 秒 —— 而那种尺寸的页本来就
 * 「超分产物不会比原图更清晰」（见 [SrDisplayBase.isUsableBase] 那条算式）。
 *
 * ⚠️ 默认 **0 = 不跳过**（保持现有行为）。理由：这是"少干活"的开关，一旦默认打开，
 * 用户会看到「超分显示完成但没变化 / 直接说已跳过」——正是 2026-10 修过的那类幽灵状态。
 * 必须由用户显式选择阈值，并且**在动手之前**就给出具体原因（[detail]）。
 *
 * ⚠️ 判据只看**长边**：与他们的 `{width, height}` 双阈值在常见漫画/PDF 页（长宽比 < 2）上等价，
 * 但只有一条规则、好解释、好测。
 */
object SrSkipPolicy {

    /** 0 = 关闭（不因分辨率跳过） */
    const val DISABLED = 0

    /**
     * 设置页给用户挑的阈值（长边像素）。1600 是 TachiyomiX 模型包的默认值，作为第二档。
     * 0 放第一档 = "不跳过（默认）"。
     */
    val PRESETS = intArrayOf(DISABLED, 1200, 1600, 2000, 2560)

    /** 该页是否因超过阈值而**直接跳过**（不推理、不落盘） */
    fun shouldSkip(width: Int, height: Int, maxLongSide: Int): Boolean =
        maxLongSide > DISABLED && longSide(width, height) > maxLongSide

    /** 长边像素（宽高非法时按 0 处理，调用方另有 [SrFailReason.PAGE_LOAD_FAILED] 兜底） */
    fun longSide(width: Int, height: Int): Int = maxOf(width, height).coerceAtLeast(0)

    /**
     * 失败原因里那行 ASCII 细节（接口契约：给日志和"复制发给我"用，不做本地化）。
     * 带上**实际长边**与**阈值**，用户一眼能判断该换阈值还是该换模型。
     */
    fun detail(width: Int, height: Int, maxLongSide: Int): String =
        "page ${width}x$height (long side ${longSide(width, height)}) > limit $maxLongSide"
}
