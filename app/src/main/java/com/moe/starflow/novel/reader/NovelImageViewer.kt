package com.moe.starflow.novel.reader

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.moe.starflow.R
import com.moe.starflow.databinding.DialogNovelImageBinding
import com.moe.starflow.novel.translate.NovelImageTranslation
import com.moe.starflow.novel.translate.NovelImageVisual

/**
 * 插图的**全屏查看器**（在正文里**点一下**插图打开）。
 *
 * 用户口径 2026-10-04：「**点击图片应该要可以放大查看全图**（这个查看全图就复用漫画阅读器那边的逻辑，
 * 底部右下角也要有翻译，切换和删除按钮这些基础按钮）」。
 *
 * - 看图手势 = **漫画那套** [com.moe.starflow.ui.viewer.ZoomableImageView]（双指缩放 / 双击缩放 / 拖动平移）
 * - 右下角三颗基础按钮 = 与图上那排同一套语义（`NovelImageControls.buttonsFor`：没译文只有「翻译」）
 * - 显示的位图 = `NovelImageOverlay` 渲染出来的那一份（按当前三态：译文 / 识别原文 / 纯图），
 *   不是裸原图 —— 否则"放大看全图"会把译文藏起来，用户还以为翻译丢了
 *
 * ⚠️ **不在这里做翻译**：按钮只回调宿主（[Host]），宿主复用图上那排按钮**同一条**实现
 * （在途去重 / 落库 / 上屏 / 提示都只有一份）。这个类只负责"画"与"点"。
 * ⚠️ 宿主是 Activity，所以**必须**在 `onStop`/`onDestroy` 里 [dismiss] ——
 * Dialog 持有 Activity 的 window token，留着就是窗口泄漏。
 */
class NovelImageViewer(
    private val activity: Activity,
    private val paraIndex: Int,
    private val host: Host,
) {

    /** 宿主回调（实现在 `NovelReaderActivity` —— 与图上按钮同一条路）。 */
    interface Host {
        fun imageOf(paraIndex: Int): NovelImage?
        fun visualOf(paraIndex: Int): NovelImageVisual
        fun payloadOf(paraIndex: Int): NovelImageTranslation?
        fun onTranslateImage(paraIndex: Int)
        fun onToggleImageVisual(paraIndex: Int)
        fun onDeleteImageTranslation(paraIndex: Int)

        /** 查看器关掉（点图 / 返回键 / 退出阅读器）→ 宿主把持着的那份引用清掉。 */
        fun onViewerClosed()
    }

    private var dialog: Dialog? = null
    private var binding: DialogNovelImageBinding? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isShowing) return
        val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val b = DialogNovelImageBinding.inflate(activity.layoutInflater)
        d.setContentView(b.root)
        d.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // ⚠️ 收尾只挂在 `onDismiss` 上（一条出口）：点图关闭、返回键关闭、宿主 `dismiss()` 关闭
        // 三条路都会走到它 —— 分散写三处迟早漏一个，漏掉的那个就是"引用留着一个死窗口"。
        d.setOnDismissListener {
            dialog = null
            binding = null
            host.onViewerClosed()
        }
        dialog = d
        binding = b
        // 点一下（未放大时）= 关掉；放大状态下这一下留给拖动看图（ZoomableImageView 的既有语义）
        b.ivNovelImage.onSingleTapConfirmed = { _, _ -> dismiss() }
        b.btnNovelImageTranslate.setOnClickListener { host.onTranslateImage(paraIndex) }
        b.btnNovelImageToggle.setOnClickListener { host.onToggleImageVisual(paraIndex) }
        b.btnNovelImageDelete.setOnClickListener { host.onDeleteImageTranslation(paraIndex) }
        refresh()
        d.show()
    }

    /** 状态（三态 / 有没有译文）变了就重画：图标与按钮显隐都按**当前**状态显式赋值。 */
    fun refresh() {
        val b = binding ?: return
        val visual = host.visualOf(paraIndex)
        val hasPayload = host.payloadOf(paraIndex) != null
        b.btnNovelImageTranslate.setImageResource(
            // 翻译 / 重翻是同一个动作的两个时机（与图上那排按钮同一套图标约定）
            if (hasPayload) R.drawable.ic_refresh else R.drawable.ic_reader_translate,
        )
        b.btnNovelImageToggle.setImageResource(
            when (visual) {
                NovelImageVisual.TRANSLATED -> R.drawable.ic_display_translated
                NovelImageVisual.ORIGINAL -> R.drawable.ic_display_original
                NovelImageVisual.PLAIN -> R.drawable.ic_display_plain
            },
        )
        // 按钮集合与图上那排**同源**（`NovelImageControls.buttonsFor`）：没译文时只有「翻译」
        val buttons = NovelImageControls.buttonsFor(hasTranslation = hasPayload)
        b.btnNovelImageTranslate.visibility = visible(buttons.contains(NovelImageButton.TRANSLATE))
        b.btnNovelImageToggle.visibility = visible(buttons.contains(NovelImageButton.TOGGLE))
        b.btnNovelImageDelete.visibility = visible(buttons.contains(NovelImageButton.DELETE))
        renderBitmap(b)
    }

    private fun visible(on: Boolean): Int = if (on) View.VISIBLE else View.GONE

    /**
     * 把当前三态要画的那张位图放进 `ImageView`。
     *
     * ⚠️ 目标宽取**这张图在查看器里实际能占的宽度**（`fitCenter` 之后），不是"屏幕宽"：
     * 竖长图在横屏/宽屏上占不到整屏宽，按屏幕宽渲染纯属白占内存（覆盖层会进 LRU）。
     * ⚠️ 也**不是**正文列宽 —— 这里要的就是"全图"。
     */
    private fun renderBitmap(b: DialogNovelImageBinding) {
        val image = host.imageOf(paraIndex) ?: return
        val payload = host.payloadOf(paraIndex)
        val visual = host.visualOf(paraIndex)
        val dm = activity.resources.displayMetrics
        val fitW = fittedWidth(image, dm.widthPixels, dm.heightPixels)
        val bmp: Bitmap? = if (payload != null) {
            NovelImageOverlay.of(activity, image, payload, visual, fitW)
                ?: NovelImageStore.bitmapOf(image, fitW)
        } else {
            NovelImageStore.bitmapOf(image, fitW)
        }
        b.ivNovelImage.setImageDrawable(bmp?.let { BitmapDrawable(activity.resources, it) })
        // ⚠️ 不调 `resetZoom()`：`setImageDrawable` 已经把 `isInitialized` 复位，下一次布局会
        // `fitCenter()` 重新算矩阵（`applyFit` 里顺手把 `currentScale` 归 1）——
        // 再叠一个缩放动画反而会和这次重排打架。
    }

    /** `fitCenter` 之后这张图占多宽（纯算术；尺寸异常时退回屏幕宽）。 */
    private fun fittedWidth(image: NovelImage, screenW: Int, screenH: Int): Int {
        val iw = image.width
        val ih = image.height
        if (iw <= 0 || ih <= 0) return screenW.coerceAtLeast(1)
        val byHeight = screenH.toFloat() * iw / ih
        return minOf(screenW.toFloat(), byHeight).toInt().coerceAtLeast(1)
    }

    /** 关掉（幂等）：真正的收尾在 `onDismiss` 里（见 [show]）。 */
    fun dismiss() {
        val d = dialog ?: return
        dialog = null
        binding = null
        runCatching { if (d.isShowing) d.dismiss() }
    }
}
