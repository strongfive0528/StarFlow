package com.moe.starflow.sr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 大图超分前的**输入预处理**：按用户输入像素阈值和模型硬上限等比缩小，再交给模型。
 * 用户阈值默认 1.5MP；模型硬上限随模型倍率变化（例如 2x 模型常见上限 2.5MP，4x 模型可能是 0.625MP）。
 *
 * 输入像素数不超过有效阈值时原图直接送入模型；超过时按像素总量等比例缩小。
 * 有效阈值是用户阈值与模型硬上限中的较小值。产物不因尺寸小于原图而被拒绝，也不在这里额外缩放。
 * 缓存容量由 [SrStore.manageCache] 的总容量与 LRU 管理。
 *
 * ## 压缩怎么做到"不损失画质"
 * 只在**内存里**用 `Canvas` + `FILTER_BITMAP_FLAG` 缩放，**不重新编码**（没有 JPEG 二次损失）；
 * 且 [apply] **分步减半**再收到目标 —— 一步 2.8 倍下采样会漏采样，在网点/线条上出现摩尔纹。
 */
object SrDownscale {

    private const val TAG = "SrDownscale"

    /**
     * 输入像素压缩阈值的默认值（MP）。
     * 大于此值的输入会等比例缩小到阈值内；小于此值直接送模型。0 = 不限用户阈值。
     */
    const val DEFAULT_MAX_INPUT_MP = 1.5f

    /**
     * 拿不到引擎上限时的兜底（= ncnn 2x 的真实上限）。
     *
     * 引擎取不到（没选模型 / 文件缺失）时不拿它当失败 —— 真正的原因稍后由
     * `SuperResolutionEngines.applySteps` 给出（那里才有"没模型 / 文件不在 / 加载失败"的细分）。
     */
    const val DEFAULT_MAX_FEED_PIXELS = 2_500_000L

    data class Plan(val width: Int, val height: Int)

    /**
     * 算出压缩目标。**用户像素预算和模型硬上限都要满足**；本来就不用缩 → null（调用方走原路）。
     *
     * ⚠️ 返回的尺寸**恒小于入参**（`k < 1`）—— 这是"压缩图不超过原图像素"的构造性保证。
     *
     * @param maxFeedPixels 引擎能吃的最大输入像素数（`SuperResolutionEngine.maxInputPixels`）；
     *   ≤ 0 表示未知 → 用 [DEFAULT_MAX_FEED_PIXELS]。
     */
    fun plan(
        srcW: Int,
        srcH: Int,
        maxFeedPixels: Long = DEFAULT_MAX_FEED_PIXELS,
        /**
         * **用户可调的输入压缩阈值**（MP）。源图低于阈值时直接送模型；高于阈值时按像素总量等比例缩小。
         * ≤0 = 不限用户阈值（仍受引擎硬上限约束）。
         *
         * ⚠️ 它只**压输入**（省推理）；结果能否保存由 [SrProcessor] 按产物与原图的尺寸另行判断。
         */
        maxInputMegapixels: Float = 0f,
    ): Plan? {
        if (srcW <= 0 || srcH <= 0) return null
        val engineCap = if (maxFeedPixels > 0) maxFeedPixels else DEFAULT_MAX_FEED_PIXELS
        val userCap = if (maxInputMegapixels > 0f) (maxInputMegapixels * 1_000_000f).toLong() else Long.MAX_VALUE
        val cap = minOf(engineCap, userCap)

        // 唯一的压缩条件：输入像素数超过有效阈值（= min(引擎硬上限, 用户阈值)）。
        // 未超过时 k 保持 1，返回 null，调用方将原图直接送入模型。
        var k = 1.0
        val pixels0 = srcW.toDouble() * srcH
        if (pixels0 > cap) k = sqrt(cap / pixels0)

        if (k >= 1.0) return null
        // ⚠️ 取整必须向下再兜一层：模型硬上限是精确像素契约，压缩后仍超限就会白跑一场。
        //    上限是引擎的硬契约（`>` 即拒绝），所以这里必须**保证 ≤**，不能"大约"。
        var w = max(1, kotlin.math.floor(srcW * k).toInt())
        var h = max(1, kotlin.math.floor(srcH * k).toInt())
        while (w.toLong() * h > cap && (w > 1 || h > 1)) {
            if (w > 1) w--
            if (h > 1) h--
        }
        return Plan(w, h)
    }

    /**
     * 缩放到 [plan]。**分步**：每步最多缩一半，避免一次大倍率下采样的漏采样。
     *
     * @return 缩放结果；**可能就是 `src` 本身**（尺寸已经一致时）→ 调用方要判 `!==` 再决定回收。
     *   中间产物由本函数内部回收，不会泄漏。
     */
    fun apply(src: Bitmap, plan: Plan): Bitmap {
        var cur = src
        var owned: Bitmap? = null
        while (cur.width > plan.width * 2 && cur.height > plan.height * 2) {
            val next = scaleTo(cur, max(1, cur.width / 2), max(1, cur.height / 2)) ?: break
            owned?.recycle()
            owned = next
            cur = next
        }
        if (cur.width == plan.width && cur.height == plan.height) return cur
        val out = scaleTo(cur, plan.width, plan.height) ?: cur
        // 只有"这一步产出了新图"时才回收中间产物；`out === owned` 说明缩放在这一步失败了
        if (out !== owned) owned?.recycle()
        return out
    }

    /** 单步缩放（高质量过滤）。失败返回 null —— 超分是"锦上添花"，绝不因此抛给上层。 */
    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap? = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { dst ->
            Canvas(dst).drawBitmap(
                src, null, Rect(0, 0, w, h),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
        }
    } catch (e: Throwable) {
        LogCollector.e(TAG, "缩放失败 ${src.width}x${src.height} → ${w}x$h", e)
        null
    }
}
