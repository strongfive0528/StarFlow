package com.moe.starflow.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.moe.starflow.R

/**
 * **阅读器（漫画 + 小说）所有弹窗的唯一主题入口。**
 *
 * ## 为什么必须是"强制 night 模式的上下文"，而不是"换底 + 刷字色"
 *
 * 阅读器的深浅跟的是**阅读背景**（`reader_background`），**不是系统主题**。
 * 早先的做法是「`window.setBackgroundDrawableResource(bg_dialog_dark)` + 把所有 TextView 刷成浅色」——
 * **它就是错的**：`AlertDialog` 的**面板底是主题给的**（AppCompat/Material 的 `alertDialogTheme`），
 * 画在窗口底**之上**把窗口底整个盖住；系统浅色时面板是白的，而文字被刷成了浅色
 * → **白底白字，完全看不见**（用户 2026-09-28 报的就是这个：
 * 「背景白色文字你也搞成白色我怎么看？？我不希望再出现色彩主题搭配的显示问题！」）。
 *
 * 所以现在：
 * 1. `context(context, dark)` 用 **Configuration 覆盖**把 `uiMode` 钉成 NIGHT_YES / NIGHT_NO，
 *    再用它建弹窗 → 面板、标题、正文、按钮、分割线**全部由框架按该模式给色**，
 *    不存在"底和字来自两个来源"的可能；
 * 2. 窗口底再贴一层本项目的圆角底（`bg_dialog_dark` / `bg_dialog_white`）保持与章节目录弹窗一致；
 * 3. **但主题覆盖不是万无一失的**（真机上出现过"底跟了阅读背景、字还是系统主题的浅色" →
 *    白底白字），所以 [show] 里还会对**整个弹窗**跑一遍 [tintCustomView] 兜底：
 *    只翻"在当前底上看不清"的中性色（浅底浅字 → 深字；深底深字 → 浅字），
 *    **强调色/错误色（#55AEEA / #CC5555…）原样保留**（红字在深色底上本来就读得清，
 *    翻成灰反而丢了语义）。自定义内容视图（`dialog_reader_download` / `dialog_page_preview`
 *    这类 XML 里写死颜色的）同样过 [tintCustomView]。
 *
 * ⚠️ 新增弹窗**一律**走 [show]（或 [context] + [style]）；新增自定义内容视图**必须**过 [tintCustomView]。
 * 回归守卫：`ReaderThemeGuardTest`（接线）+ `ReaderDialogsTintTest`（**颜色对比度**：
 * 浅底浅字必须被翻深、且**绝不许翻成近透明的分割线色**）。
 */
object ReaderDialogs {

    private const val TAG = "ReaderDialogs"

    /** 深色模式下的主文字 / 次要文字 / 分割线（与面板 `applyPanelTheme` 同一套值）。 */
    const val LABEL_DARK = 0xFFE2E2E4.toInt()
    const val SUB_DARK = 0xFF9A9A9F.toInt()
    const val DIVIDER_DARK = 0x1AFFFFFF
    const val LABEL_LIGHT = 0xFF333333.toInt()
    const val SUB_LIGHT = 0xFF888888.toInt()
    const val DIVIDER_LIGHT = 0x11000000

    /**
     * 造一个「**以原上下文为 base**、主题照旧、强制 night 模式」的上下文给弹窗用。
     *
     * ## ⚠️ 两条血泪（都是线上崩过的，别再"优化"）
     *
     * 1. **不能 `createConfigurationContext()` 出来的裸 Context**：它拿不到 Activity 注入的 AppCompat 主题
     *    → `AlertDialog.show()` 抛 `You need to use a Theme.AppCompat theme`。
     * 2. **也不能把 `createConfigurationContext()` 的结果当 base**（哪怕再套 `ContextThemeWrapper`）：
     *    那个 Context **不挂在 Activity 的窗口上**，弹窗拿不到 window token
     *    → `WindowManager$BadTokenException: token null is not valid`（顶部胶囊那次崩溃）。
     *
     * ⇒ 唯一正确做法：**base 必须是原上下文（Activity）**，night 只能通过
     * [androidx.appcompat.view.ContextThemeWrapper.applyOverrideConfiguration] 加 ——
     * 它只改 `Resources/Configuration`，**不动 `getSystemService`**，所以窗口 token 保住、主题也保住。
     *
     * 拿不到主题资源时**直接返回原上下文**（`AppCompatActivity` 自己的 context，AppCompat 一定认）。
     */
    fun context(context: Context, dark: Boolean): Context {
        val themeRes = themeResIdOf(context)
        if (themeRes == 0) return context
        val wrapper = androidx.appcompat.view.ContextThemeWrapper(context, themeRes)
        val cfg = Configuration(context.resources.configuration)
        val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        // ⚠️ 必须立刻 apply（在 wrapper 的 resources 被使用之前）；失败就退回原上下文
        return runCatching {
            wrapper.applyOverrideConfiguration(cfg)
            wrapper as Context
        }.getOrDefault(context)
    }

    /**
     * 取当前上下文对应的**主题资源 id**：Activity 在 Manifest 里声明的优先，其次 Application 的。
     *
     * ⚠️ 不能只取 `applicationInfo.theme`：Activity 完全可以在 Manifest 里声明另一个主题
     * （本项目阅读器就是这么干的），用错主题同样会让 AppCompat 认不出来。
     */
    private fun themeResIdOf(context: Context): Int {
        val fromActivity = (context as? android.app.Activity)?.let { act ->
            runCatching {
                act.packageManager.getActivityInfo(act.componentName, 0).theme
            }.getOrNull()
        } ?: 0
        if (fromActivity != 0) return fromActivity
        return runCatching { context.applicationInfo.theme }.getOrDefault(0)
    }

    /**
     * 窗口底（圆角底，与章节目录弹窗一致）。
     *
     * ⚠️ 参数是 `android.app.Dialog` 而不是 appcompat 的 `AlertDialog`：
     * 项目里两种弹窗都有（`NovelTocDialog` 用的是 framework 的 `android.app.AlertDialog`，
     * 它并不继承 appcompat 那个），放宽到 `Dialog` 两边都能用。
     */
    fun style(dialog: android.app.Dialog, dark: Boolean) {
        dialog.window?.setBackgroundDrawableResource(
            if (dark) R.drawable.bg_dialog_dark else R.drawable.bg_dialog_white,
        )
    }

    /**
     * 建一个**已经按阅读背景配好主题**的弹窗并显示。
     *
     * ⚠️ **带兜底**：主题/配置这套万一在某机型上出问题（主题认不出、token 拿不到），
     * 一律退回**原上下文**再建一次 —— 宁可这次配色不跟随阅读背景，也**绝不能让用户看到闪退**。
     * 兜底触发会记 E 级日志（排查时能看到是哪一步失败）。
     *
     * 用法与 `AlertDialog.Builder` 一致（链式设置标题/正文/按钮）。
     * 自定义内容视图用 `setView(...)` 传进来后，调用方再对那个 view 调 [tintCustomView]
     * （本函数已经对**整个弹窗**跑过一遍 [tintCustomView]，调用方那次是幂等的双保险）。
     *
     * ## ⚠️ 为什么还要 `tintCustomView(decorView)` 兜一遍（2026-10-03）
     *
     * 弹窗的**底**和**字**来自两个不同的来源：
     * - 底：`style()` 把 window background 换成 `bg_dialog_white/dark` —— 跟的是**阅读背景**（`dark`）
     * - 字：标题/正文/按钮 —— 跟的是**主题**（系统或应用日夜，靠 [context] 的 uiMode 覆盖去对）
     *
     * 两个来源一旦不一致（最典型：**应用/系统深色 + 阅读背景浅色**）就是**白底白字，完全看不见**
     * ——用户 2026-10-03 报的「浅色模式下，删除按钮的提示弹出文本和背景完全重叠看不到」就是它。
     * 现在两处都由同一个 `dark` 决定：`tintCustomView` 只翻"在这个底上看不清"的中性色，
     * 强调色（按钮蓝 #55AEEA）原样保留，所以不会再出现"底色跟阅读背景、字色跟系统主题"的错配。
     */
    fun show(
        context: Context,
        dark: Boolean,
        configure: AlertDialog.Builder.() -> Unit,
    ): AlertDialog {
        val themed = context(context, dark)
        return try {
            AlertDialog.Builder(themed).apply(configure).create().also {
                it.show()
                style(it, dark)
                tintCustomView(it.window?.decorView, dark)
            }
        } catch (e: Exception) {
            LogCollector.e(TAG, "阅读器弹窗构建失败，退回原上下文重试（本次配色不跟阅读背景）", e)
            AlertDialog.Builder(context).apply(configure).create().also {
                it.show()
                style(it, dark)
                tintCustomView(it.window?.decorView, dark)
            }
        }
    }

    /**
     * 自定义内容视图 / 弹窗视图：把**在当前底上看不清**的中性色翻成该模式的主文字色。
     *
     * - **中性色**（饱和度低：黑/灰/白）→ 只在"会对不上"时翻：
     *   浅色底上的浅字（`lum ≥ 0.5`）→ [LABEL_LIGHT]；深色底上的深字（`lum < 0.5`）→ [LABEL_DARK]。
     *   **本来就看得清的颜色一个都不动**（浅底深字、深底浅字原样留着）。
     * - **强调色/错误色**（饱和度高：#55AEEA 蓝、#CC5555 红、#34C759 绿）→ **保留**，
     *   它们在两种底色上都可读，翻掉反而丢了语义（"删除"不再红）
     * - 只有 1~3dp 高的分隔线（背景色）同样按中性色规则翻（**普通 `View` 那条分支 2026-10-03 才补上**：
     *   以前 `when` 只认 TextView/ViewGroup，XML 里的 1dp 分隔线**从来没被翻过**）
     *
     * ## ⚠️ 两条血泪（别再"简化"回去）
     *
     * 1. **文字绝不能翻成 `DIVIDER_*`**：那是给 1dp 分割线用的**近透明**色
     *    （`DIVIDER_LIGHT = 0x11000000`，alpha 只有 6.7%）。旧实现按"暗色→亮/亮色→暗"机械翻转，
     *    浅色模式下把正文的 `#333333` 判成"暗色"→ 刷成 `DIVIDER_LIGHT` →
     *    `dialog_reader_download` / `dialog_page_preview` 里所有标题与正文**直接消失**
     *    （用户 2026-10-03：「浅色模式下…文字和背景完全重叠看不到」）。现在只有"看不清"才翻，
     *    且只翻成主文字色。
     * 2. **必须能重复调用**（`show()` 会先对整窗跑一遍、调用方再对自己那个 view 跑一遍）：
     *    本函数对已经正确的颜色是空操作，所以幂等。
     */
    fun tintCustomView(root: View?, dark: Boolean) {
        if (root == null) return
        when (root) {
            is TextView -> {
                val c = currentTextColor(root)
                if (isNeutral(c)) {
                    // "看不清" = 字色与底色同侧：浅底浅字 / 深底深字
                    val unreadable = if (dark) luminance(c) < 0.5f else luminance(c) >= 0.5f
                    if (unreadable) root.setTextColor(if (dark) LABEL_DARK else LABEL_LIGHT)
                }
            }
            is ViewGroup -> {
                tintDivider(root, dark)
                for (i in 0 until root.childCount) tintCustomView(root.getChildAt(i), dark)
            }
            // ⚠️ **普通 View 就是 XML 里那些 1dp 分隔线**（`<View android:background="#F0F0F0"/>`）。
            //    以前这里没有 else 分支 —— 分隔线永远走不到翻转逻辑（`when` 只认 TextView/ViewGroup），
            //    深色弹窗里那条 `#F0F0F0` 亮线一直是亮的（注释写着会翻、实际从来没翻过）。
            else -> tintDivider(root, dark)
        }
    }

    /** 1~3dp 高的纯色 View = 分隔线：浅底用近透明深色、深底用近透明亮色。 */
    private fun tintDivider(v: View, dark: Boolean) {
        val d = v.resources.displayMetrics.density
        if (v.height !in 1..(3 * d).toInt()) return
        val bg = (v.background as? android.graphics.drawable.ColorDrawable)?.color ?: return
        if (!isNeutral(bg)) return
        v.setBackgroundColor(if (dark) DIVIDER_DARK else DIVIDER_LIGHT)
    }

    /** 取 TextView 当前文字色（拿不到就当主文字色）。 */
    private fun currentTextColor(tv: TextView): Int = runCatching { tv.currentTextColor }.getOrDefault(LABEL_LIGHT)

    /** 是否"中性色"（低饱和）：黑白灰属于它，蓝/红/绿不属于。 */
    private fun isNeutral(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        if (max == 0) return true
        return (max - min).toFloat() / max < 0.25f
    }

    /** 相对亮度（0=黑 1=白）。 */
    private fun luminance(color: Int): Float {
        val r = Color.red(color) / 255f; val g = Color.green(color) / 255f; val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }
}
