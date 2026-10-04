package com.moe.starflow.sr

/**
 * 「阅读器这一页该用**哪种底图**」+ 「渲染缓存 key 里的底图签名」—— **纯函数，可纯 JVM 单测**。
 *
 * 为什么要单独抽出来：这个判断散在渲染路径里必然出现「关了超分还在显示超分图」「换了模型
 * 屏幕上还是旧模型的图」这类**不崩不报错、只是显示不对**的静默问题。抽成纯函数之后
 * `SrDisplayBaseTest` 能把整张真值表钉死（与 [SuperResolutionEngines.resolveSteps] 同一套路）。
 *
 * ## 与 [SuperResolutionEngines.resolveSteps] 的关系
 * 两者**必须同源**：`resolveSteps` 决定"要不要跑增强"，本类决定"显示用哪张图"。
 * 同一条互斥规则（超分模型可用 → Anime4K 让位）在两边各写一份就会漂移。
 *
 * ## 签名为什么必须存在
 * 同一页在「原图 / 超分模型 / Anime4K」三种底图下渲出来的位图是**不同**的，
 * 而 `renderLru` 的 key 只有 `页号 + overlay 态`。签名进 key 之后：
 * - 超分做完了 → 签名从 `o` 变 `s:XXX` → 旧渲染自然失配，不会把糊底图的译图当新结果返回
 * - 用户换模型 → `s:A` 变 `s:B` → 自动重渲染（不会拿 A 的产物冒充 B）
 * - 用户切回原图 → 变回 `o`，且**磁盘文件不动**（用户口径：切换只影响显示）
 */
enum class SrBaseKind {
    /** 原图（超分关 / 用户把该页切回原图 / 该页还没有超分文件） */
    ORIGINAL,

    /** 已落盘的超分模型底图（2x） */
    SR_MODEL,

    /** Anime4K 同分辨率增强底图（1x） */
    ANIME4K,
}

object SrDisplayBase {

    /**
     * 该页该用哪种底图。
     *
     * @param srVisualOn 用户对该页的二态选择（true = 显示超分底图）。**默认 true**：
     *   用户口径「超分后翻译默认也显示在超分后的图片上」。
     * @param srModelUsable 选了模型**且**文件在（`SuperResolutionEngines.isSrModelUsable`）
     * @param storedSrFile 该页已有落盘的超分结果（`SrStore.exists`）
     *
     * ⚠️ 用户切回原图（`srVisualOn = false`）时，**连 Anime4K 也不跑** —— 那是"看原图"这个
     * 意图本身，再叠一层锐化就不是原图了。
     */
    fun resolveBaseKind(
        srVisualOn: Boolean,
        srEnabled: Boolean,
        srModelUsable: Boolean,
        anime4kEnabled: Boolean,
    ): SrBaseKind {
        if (!srVisualOn) return SrBaseKind.ORIGINAL
        // 与 resolveSteps 同一判据：超分模型可用时 Anime4K 让位（互斥）
        if (srEnabled && srModelUsable) return SrBaseKind.SR_MODEL
        return if (anime4kEnabled) SrBaseKind.ANIME4K else SrBaseKind.ORIGINAL
    }

    /**
     * 底图签名（进渲染缓存 key）。
     *
     * `"o"` 原图 / `"s:<模型名>"` 超分模型 / `"a:<Anime4K 档位 id>"` Anime4K。
     *
     * ⚠️ **`SR_MODEL` 但该页没有落盘文件时降级成 `"o"`**：表示"该页还没超分过"，
     * 此时渲染确实就是原图 —— 签名必须如实反映**这一页实际用的底图**，
     * 否则自动超分完成后旧渲染不会被作废。
     */
    fun baseSignature(
        kind: SrBaseKind,
        srModelName: String?,
        anime4kModeId: String?,
        storedSrFile: Boolean,
    ): String = when (kind) {
        SrBaseKind.ORIGINAL -> "o"
        SrBaseKind.SR_MODEL ->
            if (storedSrFile && !srModelName.isNullOrEmpty()) "s:$srModelName" else "o"
        SrBaseKind.ANIME4K ->
            if (!anime4kModeId.isNullOrEmpty()) "a:$anime4kModeId" else "o"
    }

    /**
     * 「超分产物能不能当**比原图更大**的显示底图」的**唯一判据**。
     *
     * 为什么必须有它：超分底图只有在**像素比原图多**时才有意义。`OverlayRenderer` 拿底图当
     * 输入、按 `baseScale = 底图宽 / 坐标空间宽` 反算坐标；底图不比原图宽时它只是"更少像素被放大"，
     * 画上去等于**降画质**，所以显示侧会丢弃它。
     *
     * ⚠️ **产出侧（`SrProcessor`）与显示侧（`reader/ReaderTranslationController.srBaseFor`）
     * 必须调同一个函数**。各写一份就会出现「产出侧存了、显示侧不用」的**幽灵状态**：
     * 超分记录写着「完成」、面板还能显示产物尺寸，而画面一直是原图。
     *
     * 算式（输入分辨率不能低于显示密度）：
     *
     * ```
     * 产物宽 = 2 × feed 宽，feed 由 SrDownscale.plan 决定（只受引擎输入上限 2.5MP 约束）
     *   A4 @300dpi  = 2480×3508（8.7MP）→ feed 1329×1880 → 产物 2658px > 2480px ✓ 可用
     *   A5 @300dpi  = 1746×2479（4.3MP）→ feed 1384×1805 → 产物 2768px > 1746px ✓ 可用
     *   3000 宽的大页（12.6MP）        → feed 1336×1870 → 产物 2672px ≤ 3000px ✗ 不可用
     * ```
     * （2026-10 修订前的"短边一律压到 1080"会让 feed 只有 1080 宽、产物 2160px：
     * A4 就必然 ✗ —— 那正是「记录写完成、画面一直原图」的来源；旧规则已废。）
     *
     * @param productWidth 超分产物的宽度（像素）
     * @param sourceWidth 该页**原图**的宽度（＝气泡坐标空间的宽）
     */
    fun isUsableBase(productWidth: Int, sourceWidth: Int): Boolean =
        sourceWidth > 0 && productWidth > 0 &&
            productWidth.toFloat() / sourceWidth.toFloat() > MIN_BASE_SCALE

    /**
     * 「比原图大」的判定阈值。
     *
     * 不是 `> 1f`：位图尺寸是整数，1px 的差（如 1746→1747）在视觉上等于没变，
     * 却会让「已超分」的结论为真、渲染缓存跟着换 key 白重渲一次。
     */
    const val MIN_BASE_SCALE = 1.001f
}
