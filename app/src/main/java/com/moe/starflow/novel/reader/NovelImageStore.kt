package com.moe.starflow.novel.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.moe.starflow.utils.LogCollector
import java.io.File
import kotlin.math.min

/**
 * 插图的**解码 + 位图缓存**。
 *
 * ### 为什么是懒解码 + LRU
 * 一章可能有十几张插图，而屏幕上同时只看得见一两张。全解码驻留内存就是几十 MB（一张全宽
 * 1080×1500 的 ARGB 位图 = 6.5MB），而阅读器还缓存着前后各一章的正文。这里按需解码、
 * 只留最近用过的几张（按 `byteCount` 计上限），翻页回来时命中缓存就够了。
 *
 * ### 为什么在 `onDraw` 里同步解码是可接受的
 * 单张全宽 JPEG 按目标宽度采样解码约 30~80ms，且**每张图每个缓存周期只发生一次**；
 * 之后翻页都是命中。放到后台线程再回调的话，页面会先画成空白、几百毫秒后突然出现一张图，
 * 反而更像"卡了"。真正的重解码（>200ms）只出现在超大图上，`inSampleSize` 已经把它压住了。
 *
 * ⚠️ 解码尺寸按**正文列宽**采样，不是原图尺寸：原图 4000 宽的书按 1080 采样，内存与耗时
 * 都降到 1/14。
 */
internal object NovelImageStore {

    private const val TAG = "NovelImageStore"

    /** 位图缓存上限。一屏最多一两张全宽插图，24MB 能放三四张，再多纯属白占内存。 */
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

    /** 解码宽度上限：手机正文列宽也就 1080 上下，更宽纯属浪费。 */
    private const val MAX_DECODE_WIDTH = 1080

    /** 解码高度上限：长条图（1×100000 那种）靠这一条兜住，否则一次解码就是几百 MB。 */
    private const val MAX_DECODE_HEIGHT = 2400

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 取一段插图解码后的位图（缓存未命中就现解；解不出来返回 null = 这一页不画图）。 */
    fun bitmapOf(image: NovelImage, targetWidth: Int): Bitmap? {
        // 键用**文件路径**：仓库按内容哈希落盘，同一张图被多章引用时天然共用一份缓存
        val key = image.file.absolutePath
        cache.get(key)?.let { if (!it.isRecycled) return it }
        val bmp = decode(image.file, targetWidth) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** 书本被删 / 换书时清掉（位图属于旧书，留着只是占内存）。 */
    fun clear() = cache.evictAll()

    /**
     * 插图绘制画笔：**开双线性过滤**。
     *
     * ⚠️ 必须开：插图基本都要**缩小**（原图 1200 宽画到 900），不开过滤的话抽点采样会出现
     * 明显的锯齿/摩尔纹 —— 在照片和网点图上尤其难看。
     */
    val paint = android.graphics.Paint(
        android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG
    )

    /** 目标矩形（复用，避免每帧新建对象）。绘制是单线程的，共用一份安全。 */
    private val rect = android.graphics.RectF()

    /**
     * 把**已经把译文画上去的那张位图**按 [size] 画到 ([x], [y])（插图覆盖层用）。
     *
     * 与 [draw] 的区别：这里画的是**已经渲染好的覆盖层**（`NovelImageOverlay` 的产物），
     * 不再经过解码缓存 —— 它本身就是缓存出来的成品。
     */
    fun drawBitmap(
        canvas: android.graphics.Canvas,
        bmp: Bitmap,
        x: Float,
        y: Float,
        size: NovelImageMetrics.Size,
    ) {
        rect.set(x, y, x + size.width, y + size.height)
        canvas.drawBitmap(bmp, null, rect, paint)
    }

    /**
     * 在 ([x], [y]) 处按 [size] 画一张插图；解不出来（文件没了 / 损坏）返回 false。
     *
     * 两种模式共用（分页的 [NovelPageView] 与滚动的 [NovelScrollAdapter]）：宽度统一由
     * [NovelImageMetrics] 定，这里只负责"把位图按那个矩形画上去"。
     */
    fun draw(
        canvas: android.graphics.Canvas,
        image: NovelImage,
        x: Float,
        y: Float,
        size: NovelImageMetrics.Size,
    ): Boolean {
        val bmp = bitmapOf(image, size.width.toInt()) ?: return false
        rect.set(x, y, x + size.width, y + size.height)
        canvas.drawBitmap(bmp, null, rect, paint)
        return true
    }

    private fun decode(file: File, targetWidth: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth)
                },
            )
        }
    } catch (e: OutOfMemoryError) {
        // 超大图解码失败（真机上的整本漫画页那种）不该把阅读器一起带走
        cache.evictAll()
        LogCollector.w(TAG, "插图解码内存不足，已清空缓存: ${file.name}")
        null
    } catch (e: Exception) {
        LogCollector.w(TAG, "插图解码失败: ${file.name}", e)
        null
    }

    /**
     * 2 的幂采样率：解出来的宽不小于目标宽（省内存又不糊），并额外夹住高度上限。
     *
     * ⚠️ `BitmapFactory` 只认 2 的幂（其它值会被就近向下取整到 2 的幂），所以自己算成 2 的幂，
     * 免得"我设了 3、它按 2 解"这种莫名其妙的内存差。
     */
    private fun sampleSizeFor(width: Int, height: Int, targetWidth: Int): Int {
        val target = min(if (targetWidth > 0) targetWidth else MAX_DECODE_WIDTH, MAX_DECODE_WIDTH)
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= 1) sample *= 2
        while (height / sample > MAX_DECODE_HEIGHT) sample *= 2
        return sample
    }
}
