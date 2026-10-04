package com.moe.starflow.sr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 大图超分前的**预处理**：把图压到"引擎吃得下 + 跑得快"的尺寸，再交给超分模型。
 *
 * ## 为什么（用户口径 2026-10）
 * 「对较大尺寸的图片进行超分，短边压缩对齐到 1080p 再超分……减轻超分模型的压力，
 * 同时可以快速通过超分模型放大尺寸」。一张 2000x3000 的页直接 2x 就是 12MP ——
 * 重档位要几十秒，超过像素上限的还会直接判失败。
 *
 * ## 只**压缩输入**，绝不缩产物
 * 本对象只管「喂进引擎之前」这一步。**超分产物原样落盘，不做任何额外缩放** ——
 * 用户口径：「压缩尺寸后给超分的图片，体积不能超过原来的像素和大小」说的是**输入**；
 * 而「超分后肯定比原图大啊」。产物的空间由**两道既有的闸**管住，不需要在这里再造一条：
 * 1. 引擎自己的输出上限（`NcnnSrEngine.maxOutputPixels` = 10MP，超了它直接不跑）；
 * 2. 超分缓存的**总容量**上限 + LRU 淘汰（[SrStore.manageCache]，默认 512MB）。
 *
 * ⚠️ **别再给产物加"不超过原图 N 倍"这类单页预算**（2026-10 试过一版，已删）：
 * 空间是**总容量**管的，不是单页尺寸管的；按单页倍数卡会得到自相矛盾的结果 ——
 * 小图（0.58MP → 2x 后 2.3MP = 4 倍）被砍掉一半，而大图（6MP → 压缩 → 7MP = 1.17 倍）
 * 反而一刀不砍。砍的全是最需要放大的低分辨率页。守卫：
 * `SrReaderWiringTest.theUpscaledProductIsStoredAsIs`。
 *
 * ## ⚠️ 只对齐短边是**不够的**（实现时实测出来的）
 * `NcnnSrEngine.maxInputPixels = 10MP / scale²`（2x → **2.5MP**，Real-ESRGAN 4x → 0.625MP），
 * 超了这个数引擎直接判「图太大」跳过。也就是说：
 * - 2000x3000 = **6MP 的页今天就已经翻不动了**（引擎上限 2.5MP），用户看到的是「图太大无法超分」；
 * - 而"短边 1080"并不保证够小：1080x2592（1:2.4 的长条）就是 2.8MP，仍然超限；
 *   Real-ESRGAN 的 0.625MP 更是连 1080x1080 都吃不下。
 *
 * 所以 [plan] 是**两个约束一起解**：先按用户口径把短边对齐 1080，再按引擎的
 * `maxInputPixels` 继续等比缩到能吃下为止。两个都不需要缩时返回 null（调用方走原路）。
 *
 * ## 压缩怎么做到"不损失画质"
 * 只在**内存里**用 `Canvas` + `FILTER_BITMAP_FLAG` 缩放，**不重新编码**（没有 JPEG 二次损失）；
 * 且 [apply] **分步减半**再收到目标 —— 一步 2.8 倍下采样会漏采样，在网点/线条上出现摩尔纹。
 */
object SrDownscale {

    private const val TAG = "SrDownscale"

    /**
     * 短边目标：**已废弃，保留常量只为文档与历史对照**（见下面 [plan] 的说明）。
     *
     * ⚠️ 2026-10 真机第二轮反馈后**不再使用**：用户导入的 Asterix PDF 页 raster 是 1646×2146，
     * 屏幕宽 1220 —— 按这条规则把输入压到短边 1080（**比屏幕还小**）再 2×，
     * 产物里真实信息只有 1080px，整页显示时**比原图（1646px）更软** ⇒ 用户看到的
     * 「超分完全没效果 / 始终是原图」。分辨率只要低于显示密度，超分就白做。
     */
    @Deprecated("短边 1080 会让'原图本来就比屏幕清晰'的页变得更糊，已不再使用；只受引擎上限约束")
    const val SHORT_SIDE_TARGET = 1080

    /**
     * 拿不到引擎上限时的兜底（= ncnn 2x 的真实上限）。
     *
     * 引擎取不到（没选模型 / 文件缺失）时不拿它当失败 —— 真正的原因稍后由
     * `SuperResolutionEngines.applySteps` 给出（那里才有"没模型 / 文件不在 / 加载失败"的细分）。
     */
    const val DEFAULT_MAX_FEED_PIXELS = 2_500_000L

    data class Plan(val width: Int, val height: Int)

    /**
     * 算出压缩目标。**只受引擎输入上限约束**（能按原尺寸喂就按原尺寸）；本来就不用缩 → null。
     *
     * ## ⚠️ 「短边一律对齐 1080」这条规则已废（2026-10，真机反馈驱动）
     * 用户口径最初是「短边压缩对齐到 1080p 再超分……减轻超分模型的压力」。但真机上它**必然看不出效果**：
     *
     * ```
     * 页 raster 1646×2146，屏幕宽 1220
     *   旧规则：输入压到短边 1080 → 2× 产物 2160×2816，但真实信息只有 1080px 宽
     *           → 整页显示时屏幕只能给 1220px，而产物内部信息(1080) < 原图(1646)
     *           → 超分图**比原图更软** → 用户："超分完全没效果，始终是原图"
     *   新规则：只压到引擎吃得下（2.5MP）→ 输入 1386×1807 → 产物 2772×3614（信息 1386px）
     *           → 不比原图差，放大看明显更锐
     * ```
     *
     * 判据很简单：**输入的分辨率只要低于显示密度，超分就是白做甚至倒退**。
     * 代价是单页推理时间随像素线性增加（本例 1.5MP → 2.5MP，约 1.6×）——用户要的是"看得见效果"。
     *
     * ⚠️ 返回的尺寸**恒小于入参**（`k < 1`）—— 这是"压缩图不超过原图像素"的构造性保证。
     *
     * @param maxFeedPixels 引擎能吃的最大输入像素数（`SuperResolutionEngine.maxInputPixels`）；
     *   ≤ 0 表示未知 → 用 [DEFAULT_MAX_FEED_PIXELS]。
     */
    fun plan(srcW: Int, srcH: Int, maxFeedPixels: Long = DEFAULT_MAX_FEED_PIXELS): Plan? {
        if (srcW <= 0 || srcH <= 0) return null
        val cap = if (maxFeedPixels > 0) maxFeedPixels else DEFAULT_MAX_FEED_PIXELS

        // ① 原尺寸就吃得下 → 不缩（普通漫画页 / 已经很大的页都走这条）
        val pixels = srcW.toDouble() * srcH
        var k = if (pixels > cap) sqrt(cap / pixels) else 1.0

        // ② 再保证喂进引擎的像素数不超上限（否则引擎直接判「图太大」，超分永远出不来）
        if (k >= 1.0) return null
        // ⚠️ 取整必须**向下**再兜一层：`roundToInt` 会把 1020.6 抬成 1021，对正好卡在
        //    上限边缘的尺寸就是"压完还差几百像素"→ 引擎照样判「图太大」，白压一场。
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
