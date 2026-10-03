package com.moe.starflow.mangaimport.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 顶部「章节页码胶囊」与提示浮层的**上下关系**。
 *
 * ```
 *   34dp  [←]          [⋮]      ← 返回 / 菜单在两侧，与居中的胶囊不抢横向空间
 *   38dp  ┌ 第3章 · 5/22 ┐      ← 胶囊（约 26dp 高 → 下沿 ~64dp）
 *  ~66dp  ▓ 正在超分第 5 页… ▓   ← 提示条**固定**在这条线上
 *         ▓ 翻译中 · P5 ▓
 * ```
 *
 * ## 用户口径（2026-10-03 定稿，「先回答问题」那一轮之后）
 *
 * > 「整个提示系统顶部就固定在当前计算过尺寸的位置，**不管是阅读器还是截屏位置固定在这个位置**
 * >  （没必要非要在屏幕最顶部）」
 *
 * 于是：
 * 1. **全应用一个位置**：游戏 / 截屏 / 阅读器 / 小说共用同一个顶距常量
 *    （`TranslationStatusOverlay.READER_TOP_OFFSET_DP`，以前截图那条链路另写了一个 24dp）；
 * 2. **只算一次**：阅读器 `onStart` + 胶囊高度变化时推一次实测值，此后一直用它 ——
 *    **不再每条提示都重新量**（那是把通知系统复杂化）；
 * 3. **退出不复位**：阅读器 `onStop` 不再 `setTopScreenY(null)`（复位 = 又分成两个位置）；
 * 4. 点屏幕中央收起上下 UI 时提示**不跟着挪**（顶距固定）。
 *
 * ⚠️ 为什么必须钉死「必须落在胶囊下面」：提示浮层是 `TYPE_APPLICATION_OVERLAY` **系统窗口**，
 * 会**吞掉落在它矩形内的触摸** —— 提示条压住胶囊那一段，胶囊就点不动，
 * 而「点胶囊开章节目录」正是用户报「不知道怎么没有了」的那条路。
 *
 * ⚠️ 单位必须是**屏幕坐标**（`getLocationOnScreen`）：浮层窗口可能被 WMS 按系统栏内缩
 * （真机 `Frames: parent=[0,138][1220,2660]`，窗口 `y=0` 对应屏幕 138px），
 * 而阅读器窗口是整屏的（`frame=[0,0][1220,2712]`）。混用会让提示条整体偏一条系统栏（≈42dp）。
 */
class ReaderTopPillInsetTest {

    companion object {
        /** 胶囊的高度（13sp 文字 + 上下各 5dp padding）。 */
        private const val PILL_HEIGHT_DP = 26

        /** 胶囊距屏幕顶的最小距离（用户口径：「不要紧贴屏幕顶部」）。 */
        private const val MIN_PILL_TOP_DP = 20

        private val MANGA = "src/main/res/layout/activity_manga_reader.xml"
        private val NOVEL = "src/main/res/layout/activity_novel_reader.xml"
        private val MANGA_ACT = "src/main/java/com/moe/starflow/mangaimport/reader/MangaReaderActivity.kt"
        private val NOVEL_ACT = "src/main/java/com/moe/starflow/novel/reader/NovelReaderActivity.kt"
        private val OVERLAY = "src/main/java/com/moe/starflow/translate/TranslationStatusOverlay.kt"

        const val PILL_ID_MANGA = "android:id=\"@+id/tv_page_indicator\""
        const val PILL_ID_NOVEL = "android:id=\"@+id/top_pill\""
    }

    private fun read(rel: String): String {
        val f = File(rel)
        assertTrue("找不到文件：${f.absolutePath}", f.exists())
        return f.readText().replace("\r\n", "\n")
    }

    /**
     * 取以 [anchor] 开头的那个元素的 `layout_marginTop`。
     * 右边界用「下一个 `android:id=`」—— marginTop 本来就在元素前几个属性里，稳。
     */
    private fun marginTopOf(rel: String, anchor: String): Int {
        val text = read(rel)
        val at = text.indexOf(anchor)
        assertTrue("$rel 里找不到 $anchor", at > 0)
        val end = text.indexOf("android:id=", at + anchor.length).let { if (it > at) it else text.length }
        val m = Regex("android:layout_marginTop=\"(\\d+)dp\"").find(text.substring(at, end))
        assertTrue("$anchor 上找不到 layout_marginTop（元素结构变了？）", m != null)
        return m!!.groupValues[1].toInt()
    }

    /** 浮层里那个「全应用统一的提示顶距」常量。 */
    private fun readerNoticeTopDp(): Int {
        val m = Regex("const val READER_TOP_OFFSET_DP = (\\d+)").find(read(OVERLAY))
        assertTrue("TranslationStatusOverlay 里找不到 READER_TOP_OFFSET_DP", m != null)
        return m!!.groupValues[1].toInt()
    }

    /**
     * 静态守卫：**兜底估算值不能压住胶囊**。
     *
     * ⚠️ 只检查"估算 ≥ 胶囊下沿"。**别在这里要求"留多少余量"** —— 真正的位置是
     * `pill.bottom + 2dp` 实测推的（见 [bothReadersPushTheNoticeStripBelowThePill]），
     * 估算值只在胶囊还没布局的那几毫秒用得上。
     */
    private fun assertPillIsAboveTheNotices(where: String, topDp: Int) {
        val pillBottom = topDp + PILL_HEIGHT_DP
        val noticeTop = readerNoticeTopDp()
        assertTrue(
            "$where 的胶囊 marginTop=${topDp}dp（下沿约 ${pillBottom}dp）会压到布局前的兜底提示位" +
                "（${noticeTop}dp）—— 胶囊下沿必须 ≤ 兜底值",
            pillBottom <= noticeTop,
        )
        assertTrue(
            "$where 的胶囊不该紧贴屏幕顶（用户口径：不要紧贴顶部）",
            topDp >= MIN_PILL_TOP_DP,
        )
    }

    @Test
    fun mangaTopPillSitsAboveTheNoticeOverlay() {
        assertPillIsAboveTheNotices(
            "漫画阅读器 activity_manga_reader.xml",
            marginTopOf(MANGA, PILL_ID_MANGA),
        )
    }

    @Test
    fun novelTopPillSitsAboveTheNoticeOverlay() {
        assertPillIsAboveTheNotices(
            "小说阅读器 activity_novel_reader.xml",
            marginTopOf(NOVEL, PILL_ID_NOVEL),
        )
    }

    /** 两个阅读器共用同一个浮层，顶距必须一致 —— 不一致就说明只改了一边。 */
    @Test
    fun bothReadersUseTheSameTopInset() {
        assertEquals(
            "两个阅读器的胶囊顶距必须一致（同一个提示浮层，只改一边等于没修）",
            marginTopOf(MANGA, PILL_ID_MANGA),
            marginTopOf(NOVEL, PILL_ID_NOVEL),
        )
    }

    /**
     * **两个阅读器都要真的算一次提示条顶距**（按实测的胶囊下沿，屏幕坐标）。
     *
     * ⚠️ 光改布局没用：浮层是**进程级单例**、默认值就是那个固定常量，阅读器不算这一次
     * 就会在章名换行（胶囊撑成两行）时压住胶囊。
     */
    @Test
    fun bothReadersPushTheNoticeStripBelowThePill() {
        for ((name, rel) in listOf(
            "漫画阅读器" to MANGA_ACT,
            "小说阅读器" to NOVEL_ACT,
        )) {
            val src = read(rel)
            assertTrue("$name 的 onStart 要推提示条位置", src.contains("pushNoticeTopBelowPill()"))
            assertTrue(
                "$name 必须按**实测的胶囊下沿**推（不猜固定 dp：猜的在大字号下压住胶囊、" +
                    "在小字号下留缝）",
                src.contains("pill.height"),
            )
            assertTrue(
                "$name 必须用 getLocationOnScreen() 取**屏幕坐标**：视图的 pill.bottom 相对父布局，" +
                    "而浮层窗口坐标可能被系统栏内缩过，混用会让提示条整体偏一条系统栏（约 42dp）",
                src.contains("getLocationOnScreen"),
            )
            assertTrue(
                "$name 的胶囊下沿必须是「屏幕 y + 高度」，不是视图的 `pill.bottom`",
                src.contains("loc[1] + pill.height"),
            )
            assertTrue(
                "$name 要监听胶囊高度变化（章名换行会把胶囊撑高）",
                src.contains("addOnLayoutChangeListener"),
            )
        }
        // 浮层侧：覆盖值是**屏幕坐标**，且自带窗口内缩校准
        val overlay = read(OVERLAY)
        assertTrue("浮层要提供屏幕坐标的覆盖入口", overlay.contains("fun setTopScreenY(screenY: Int?)"))
        assertTrue("要有布局前的兜底估算", overlay.contains("const val READER_TOP_OFFSET_DP ="))
        assertTrue(
            "浮层必须把屏幕坐标换算成窗口坐标（减去内缩量），不能直接当 LayoutParams.y 用",
            overlay.contains("want - windowTopOffsetPx"),
        )
        assertTrue(
            "内缩量只能**量**出来（WindowInsets 在沉浸全屏下报 0，而 parent frame 仍是 138）；" +
                "浮层窗口虽然加了 FLAG_LAYOUT_IN_SCREEN，这一层仍要留作兜底",
            overlay.contains("private fun calibrateTopOffset(") && overlay.contains("lastRawTopPx"),
        )
    }

    /**
     * **顶距只算一次，之后全应用固定用它**（用户口径 2026-10-03：
     * 「整个提示系统顶部就固定在当前计算过尺寸的位置，不管是阅读器还是截屏位置固定在这个位置」）。
     *
     * 钉三件事：
     * 1. **不许每条提示都重新量**（那是把通知系统复杂化）—— `notifyUser` 只管发提示；
     * 2. 阅读器**退出时不许复位**（复位 = 又回到"阅读器一个位置、截屏一个位置"）；
     * 3. 兜底默认值只有**一个常量**（`READER_TOP_OFFSET_DP`），游戏/截屏那条链路也用同一值
     *    —— 以前那边另写了一个 24dp（贴在屏幕最顶部）。
     */
    @Test
    fun theNoticeTopIsComputedOnceAndSharedByEveryScreen() {
        val overlay = read(OVERLAY)
        assertTrue(
            "顶距默认值必须只有一处（游戏/截屏/阅读器同一个值）；" +
                "以前截图链路另有一个 24dp 常量，两条链路就会落在两个位置",
            !overlay.contains("private const val TOP_OFFSET_DP = 24") &&
                overlay.contains("desiredTopScreenY ?: (READER_TOP_OFFSET_DP"),
        )
        for ((name, rel) in listOf(
            "漫画阅读器" to MANGA_ACT,
            "小说阅读器" to NOVEL_ACT,
        )) {
            val src = read(rel)
            assertTrue(
                "$name 退出时**不许**复位顶距（复位会让阅读器与截屏又分成两个位置）",
                !src.contains("setTopScreenY(null)"),
            )
            val notifyAt = src.indexOf("notifyUser(")
            if (notifyAt > 0) {
                assertTrue(
                    "$name 的提示出口不该每条提示都重新量顶距（用户口径：固定下来，别复杂化）",
                    !src.substring(notifyAt, (notifyAt + 1200).coerceAtMost(src.length))
                        .contains("pushNoticeTopBelowPill()"),
                )
            }
        }
    }

    /**
     * 两个阅读器都只在**胶囊没量到尺寸**时用固定兜底值；不做"重试量尺寸"，也不因胶囊被隐藏而挪位置。
     *
     * ⚠️ 之前那版为了"每条提示都贴紧胶囊"加了可见性判断 + 有限次重试，被用户当场否掉
     * （「为什么要量尺寸？这完全就是复杂化整个通知系统」）—— 别再往回加。
     */
    @Test
    fun bothReadersUseOnlyTheFixedTopWithoutPerNoticeWork() {
        for ((name, rel) in listOf(
            "漫画阅读器" to MANGA_ACT,
            "小说阅读器" to NOVEL_ACT,
        )) {
            val src = read(rel)
            assertTrue(
                "$name 未布局时要用**全局固定值**顶上",
                src.contains("READER_TOP_OFFSET_DP"),
            )
            assertTrue(
                "$name 不该再有「重试量尺寸」那套（已按用户口径删掉）",
                !src.contains("MAX_PILL_MEASURE_RETRY"),
            )
            assertTrue(
                "$name 不该因胶囊被隐藏（点中央收起 UI）而挪动提示位置 —— 顶距是固定的",
                !src.contains("pill.visibility != "),
            )
        }
    }

    /**
     * 转屏/分屏要把顶距**重推一次**：胶囊几何没变，但浮层窗口的系统栏内缩量会变，
     * 而浮层只有在 `setTopScreenY` 被调用时才有机会重新校准（`calibrateTopOffset`）。
     */
    @Test
    fun bothReadersRefreshTheNoticeTopOnRotation() {
        for ((name, rel) in listOf(
            "漫画阅读器" to MANGA_ACT,
            "小说阅读器" to NOVEL_ACT,
        )) {
            val src = read(rel)
            val at = src.indexOf("override fun onConfigurationChanged(")
            assertTrue("$name 找不到 onConfigurationChanged", at > 0)
            assertTrue(
                "$name 转屏后要重推顶距（内缩量会变、胶囊高度可能一点不变，不推就没机会校准）",
                src.substring(at, (at + 900).coerceAtMost(src.length))
                    .contains("pushNoticeTopBelowPill()"),
            )
        }
    }

    /**
     * 老 API `setTopOffsetPx`（窗口坐标）必须彻底消失。
     *
     * ⚠️ 留着它等于留着一条"同一个字段两种坐标系"的路 —— 新调用点抄谁全凭运气，
     * 而这两者只差 42dp、肉眼一眼看不出错，只会表现成"提示条有点偏低"。改名是为了让
     * 混淆在编译期就过不去。
     */
    @Test
    fun theWindowCoordinateApiIsGone() {
        for (rel in listOf(MANGA_ACT, NOVEL_ACT, OVERLAY)) {
            assertTrue(
                "$rel 里还留着 setTopOffsetPx（窗口坐标口径）—— 会被当成屏幕坐标误用",
                !read(rel).contains("setTopOffsetPx"),
            )
        }
    }
}
