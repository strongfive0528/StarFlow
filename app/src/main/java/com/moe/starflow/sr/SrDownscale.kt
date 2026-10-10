package com.moe.starflow.sr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.moe.starflow.utils.LogCollector
import kotlin.math.max
import kotlin.math.sqrt

/** 仅按用户 MP 阈值缩小输入；分步减半后用双线性过滤到目标尺寸。 */
object SrDownscale {
    private const val TAG = "SrDownscale"
    const val DEFAULT_MAX_INPUT_MP = 1.5f
    // Compatibility constant for callers; no hidden model cap.
    const val DEFAULT_MAX_FEED_PIXELS = Long.MAX_VALUE
    data class Plan(val width: Int, val height: Int)
    @Suppress("UNUSED_PARAMETER")
    fun plan(srcW: Int,srcH: Int,maxFeedPixels: Long=Long.MAX_VALUE,
        maxInputMegapixels: Float=DEFAULT_MAX_INPUT_MP): Plan? {
        if(srcW<=0 || srcH<=0 || maxInputMegapixels<=0f)return null
        val cap=(maxInputMegapixels.toDouble()*1_000_000).toLong().coerceAtLeast(1)
        val pixels=srcW.toDouble()*srcH
        if(pixels<=cap)return null
        val k=sqrt(cap/pixels)
        var w=max(1,kotlin.math.floor(srcW*k).toInt())
        var h=max(1,kotlin.math.floor(srcH*k).toInt())
        while(w.toLong()*h>cap && (w>1 || h>1)) {if(w>1)w--;if(h>1)h--}
        return Plan(w,h)
    }
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

    /** 内存中使用双线性过滤，不重新编码原图。 */
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
