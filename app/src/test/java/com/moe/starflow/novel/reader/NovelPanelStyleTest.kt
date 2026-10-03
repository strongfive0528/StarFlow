package com.moe.starflow.novel.reader

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.moe.starflow.novel.translate.NovelQuota
import com.moe.starflow.novel.translate.NovelTranslateMode

/**
 * 阅读模式取值的迁移。
 *
 * 引入「上下翻页」时取值语义变过：`1` 从「连续滚动」变成了「上下翻页」、滚动挪到 `2`。
 * 不迁移的话，用户原来选的「滚动」会静默变成「上下翻页」—— 他会以为滚动模式坏了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NovelPanelStyleTest {

    private val prefs: SharedPreferences
        get() = RuntimeEnvironment.getApplication()
            .getSharedPreferences(NovelPanelStyle.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `旧值 1 是滚动，迁移后仍是滚动而不是上下翻页`() {
        // 旧版本只写过 0/1，且 1 表示滚动；没有版本标记 = 还没迁移过
        prefs.edit().putInt("novel_reader_mode", 1).commit()
        assertEquals(NovelPanelStyle.READER_SCROLL, NovelPanelStyle.readerMode(prefs))
    }

    /**
     * 双语模式的段间距要**放大**：一对「原文+译文」算一个段落块，
     * 块之间拉开才能看出"这行译文属于哪段原文"（用户要求）。
     */
    @Test
    fun `双语模式段间距被放大`() {
        val ctx: Context = RuntimeEnvironment.getApplication()
        NovelPanelStyle.setDisplayMode(prefs, NovelDisplayMode.TRANSLATED)
        val one = NovelPanelStyle.textStyle(ctx, prefs).paragraphSpacingPx
        NovelPanelStyle.setDisplayMode(prefs, NovelDisplayMode.BILINGUAL)
        val two = NovelPanelStyle.textStyle(ctx, prefs).paragraphSpacingPx

        assertEquals(one * NovelPanelStyle.BILINGUAL_PARA_SPACING_FACTOR, two, 0.01f)
        assertTrue("必须明显更大，否则等于没改", two > one * 1.5f)
    }

    /**
     * 默认行距 1.5× → 1.4× 的一次性迁移（用户要求"默认行间距缩小一点点"）。
     *
     * ⚠️ 只改常量对**已经用过的人无效**（prefs 里早存着旧默认值），所以要把
     * "还停在旧默认值"的人一起挪过去；自己调过行距的必须原样保留。
     */
    @Test
    fun `行距默认值缩小只挪没调过的人`() {
        prefs.edit().putInt("novel_line_spacing", 15).commit()   // 旧默认值 = 没动过
        assertEquals(14, NovelPanelStyle.lineSpacingStep(prefs))

        prefs.edit().clear().commit()
        prefs.edit().putInt("novel_line_spacing", 18).commit()   // 自己调过
        assertEquals(18, NovelPanelStyle.lineSpacingStep(prefs))
    }

    @Test
    fun `旧值 0 是分页，迁移后保持分页`() {
        prefs.edit().putInt("novel_reader_mode", 0).commit()
        assertEquals(NovelPanelStyle.READER_PAGED, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `迁移只做一次，之后写入的新值不被改写`() {
        prefs.edit().putInt("novel_reader_mode", 1).commit()
        assertEquals(NovelPanelStyle.READER_SCROLL, NovelPanelStyle.readerMode(prefs))
        // 迁移已落盘；此后用户选「上下翻页」必须原样保留
        NovelPanelStyle.setReaderMode(prefs, NovelPanelStyle.READER_VERTICAL)
        assertEquals(NovelPanelStyle.READER_VERTICAL, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `全新安装默认分页`() {
        assertEquals(NovelPanelStyle.READER_PAGED, NovelPanelStyle.readerMode(prefs))
    }

    @Test
    fun `越界值被夹到合法区间`() {
        NovelPanelStyle.setReaderMode(prefs, 99)
        assertEquals(NovelPanelStyle.READER_SCROLL, NovelPanelStyle.readerMode(prefs))
    }

    /** 动画取值同样扩过（3 = 仿真），旧值不该被夹掉。 */
    @Test
    fun `仿真动画取值可读回`() {
        NovelPanelStyle.setAnimation(prefs, NovelPanelStyle.ANIM_SIMULATION)
        assertEquals(NovelPanelStyle.ANIM_SIMULATION, NovelPanelStyle.animation(prefs))
    }

    // ===== 排版约束（用户报的「改字号/行距，段距的滑块跟着变」）=====

    /**
     * **改行距不许动段距**（回归守卫）。
     *
     * 从前段距跟着一条「段距必须大于行距」的动态下限走，而面板把 SeekBar 的 min/max 按
     * 字号/行距动态算 —— SeekBar 会把 progress 夹到新 min，于是用户一调行距，段距的滑块
     * 自己就跳走了。现在段距与字号/行距**完全无关**，滑块只受用户拖动影响。
     */
    @Test
    fun `改行距不动段距`() {
        prefs.edit().clear().commit()
        NovelPanelStyle.setFontSizeSp(prefs, 20f)
        NovelPanelStyle.setParagraphSpacingDp(prefs, 10)
        NovelPanelStyle.setLineSpacingStep(prefs, NovelPanelStyle.LINE_SPACING_MIN)

        NovelPanelStyle.setLineSpacingStep(prefs, NovelPanelStyle.LINE_SPACING_MAX)

        assertEquals("行距改了，段距必须原样不动", 10, NovelPanelStyle.paragraphSpacingDp(prefs))
    }

    /** 改字号同理：段距的值与滑块都不该动。 */
    @Test
    fun `改字号不动段距`() {
        prefs.edit().clear().commit()
        NovelPanelStyle.setParagraphSpacingDp(prefs, 30)
        NovelPanelStyle.setFontSizeSp(prefs, NovelPanelStyle.FONT_SIZE_MIN)

        NovelPanelStyle.setFontSizeSp(prefs, NovelPanelStyle.FONT_SIZE_MAX)

        assertEquals(30, NovelPanelStyle.paragraphSpacingDp(prefs))
    }

    /**
     * 段距只在**固定区间** `PARA_SPACING_ABS_MIN..PARA_SPACING_MAX` 之间夹 ——
     * 与字号、行距都无关（这也是「区间永不为空」，不会再出现 coerceIn 空区间抛异常的原因）。
     */
    @Test
    fun `段距被夹在固定区间且与字号行距无关`() {
        for (font in listOf(NovelPanelStyle.FONT_SIZE_MIN, 20f, NovelPanelStyle.FONT_SIZE_MAX)) {
            for (step in NovelPanelStyle.LINE_SPACING_MIN..NovelPanelStyle.LINE_SPACING_MAX) {
                prefs.edit().clear().commit()
                NovelPanelStyle.setFontSizeSp(prefs, font)
                NovelPanelStyle.setLineSpacingStep(prefs, step)

                NovelPanelStyle.setParagraphSpacingDp(prefs, 9999)
                assertEquals(
                    "字号 ${font}sp 行距 ${step / 10f}×：上限固定是 ${NovelPanelStyle.PARA_SPACING_MAX}",
                    NovelPanelStyle.PARA_SPACING_MAX, NovelPanelStyle.paragraphSpacingDp(prefs),
                )

                NovelPanelStyle.setParagraphSpacingDp(prefs, -5)
                assertEquals(
                    "下限固定是 ${NovelPanelStyle.PARA_SPACING_ABS_MIN}",
                    NovelPanelStyle.PARA_SPACING_ABS_MIN, NovelPanelStyle.paragraphSpacingDp(prefs),
                )
            }
        }
    }


    /**
     * 上下间距的**默认值必须相等**（用户明确要求），且要够避开上下浮层。
     *
     * ⚠️ 不相等的话，同一段文字在「上一页末尾 / 下一页开头」看起来会偏，用户第一眼就发现。
     */
    @Test
    fun `上下间距默认值相等且落在合法区间`() {
        val v = NovelPanelStyle.verticalPaddingDefaultDp()
        assertTrue(v in NovelPanelStyle.VERTICAL_PADDING_MIN..NovelPanelStyle.VERTICAL_PADDING_MAX)
        prefs.edit().clear().commit()
        assertEquals(
            "上下间距的默认值必须相等",
            NovelPanelStyle.topPaddingDp(prefs), NovelPanelStyle.bottomPaddingDp(prefs),
        )
    }

    /** 恢复默认：字号与**全部间距**一起复位（少复位一个，用户就会觉得"恢复默认没用"）。 */
    @Test
    fun `恢复默认会把字号与全部间距一起复位`() {
        NovelPanelStyle.setFontSizeSp(prefs, NovelPanelStyle.FONT_SIZE_MAX)
        NovelPanelStyle.setLineSpacingStep(prefs, NovelPanelStyle.LINE_SPACING_MAX)
        NovelPanelStyle.setPaddingDp(prefs, NovelPanelStyle.SIDE_PADDING_MAX)
        NovelPanelStyle.setTopPaddingDp(prefs, NovelPanelStyle.VERTICAL_PADDING_MAX)
        NovelPanelStyle.setBottomPaddingDp(prefs, NovelPanelStyle.VERTICAL_PADDING_MIN)

        NovelPanelStyle.resetTypography(prefs)

        assertEquals(NovelPanelStyle.FONT_SIZE_DEFAULT, NovelPanelStyle.fontSizeSp(prefs), 0.001f)
        assertEquals(NovelPanelStyle.LINE_SPACING_DEFAULT, NovelPanelStyle.lineSpacingStep(prefs))
        assertEquals(NovelPanelStyle.SIDE_PADDING_DEFAULT, NovelPanelStyle.paddingDp(prefs))
        assertEquals(NovelPanelStyle.verticalPaddingDefaultDp(), NovelPanelStyle.topPaddingDp(prefs))
        assertEquals(
            "恢复默认后上下间距必须仍然相等",
            NovelPanelStyle.topPaddingDp(prefs), NovelPanelStyle.bottomPaddingDp(prefs),
        )
    }

    // ===== 翻译：批段数 / 增量配额 / 模式（用户明确要求的口径） =====

    /** 每批段数：默认 **2**（2026-10-03 用户口径，原 3），范围 1–10。 */
    @Test
    fun `批段数默认 2 且范围 1 到 10`() {
        assertEquals(2, NovelPanelStyle.batchSize(prefs))
        NovelPanelStyle.setBatchSize(prefs, 99)
        assertEquals("上限必须是 10", NovelPanelStyle.BATCH_MAX, NovelPanelStyle.batchSize(prefs))
        NovelPanelStyle.setBatchSize(prefs, 0)
        assertEquals("下限必须是 1", NovelPanelStyle.BATCH_MIN, NovelPanelStyle.batchSize(prefs))
    }

    /**
     * **每批段数 3 → 2、单批预警 4096 → 2048 的一次性迁移**（用户口径 2026-10-03）。
     *
     * 与行距/并发同一套：**只挪"还停在旧默认值"的人**，自己拖过滑块的不动；
     * 版本键保证只跑一次（第二次读不会再改用户的值）。
     */
    @Test
    fun `旧默认值一次性挪到新默认值且不覆盖用户设置`() {
        // 还停在旧默认的：3 / 4096 → 2 / 2048
        prefs.edit()
            .putInt("novel_translate_batch", 3)
            .putInt("novel_translate_batch_warn", 4096)
            .commit()
        assertEquals(2, NovelPanelStyle.batchSize(prefs))
        assertEquals(2048, NovelPanelStyle.batchWarnThreshold(prefs))
        // 版本键已置位：之后再放回旧值也不会被"迁移"（用户显式设的 3 要留住）
        prefs.edit().putInt("novel_translate_batch", 3).commit()
        assertEquals("迁移只跑一次，不再动用户的值", 3, NovelPanelStyle.batchSize(prefs))

        // 全新的一份 prefs（没跑过迁移）里，用户自己设过的值不能被改
        val fresh = RuntimeEnvironment.getApplication()
            .getSharedPreferences("novel_panel_style_migrate_probe", Context.MODE_PRIVATE)
        fresh.edit()
            .putInt("novel_translate_batch", 7)
            .putInt("novel_translate_batch_warn", 8192)
            .commit()
        assertEquals(7, NovelPanelStyle.batchSize(fresh))
        assertEquals(8192, NovelPanelStyle.batchWarnThreshold(fresh))
    }

    /** 单批预警阈值：默认 **2048**，越界/非档位值一律收敛到档位。 */
    @Test
    fun `单批预警阈值默认 2048 且收敛到档位`() {
        assertEquals(2048, NovelPanelStyle.batchWarnThreshold(prefs))
        NovelPanelStyle.setBatchWarnThreshold(prefs, 999_999)
        assertEquals(
            com.moe.starflow.novel.translate.NovelBatchWarning.tiers.last(),
            NovelPanelStyle.batchWarnThreshold(prefs),
        )
    }

    /**
     * 增量配额：默认 **5**，范围 **2–10**。
     *
     * ⚠️ 单位是**批**不是章：旧键 `novel_translate_ahead` 存的是章数（默认 3），
     * 换成新键 `novel_translate_ahead_batches` 就是为了不让「3 章」被当成「3 批」读进来。
     */
    @Test
    fun `增量配额默认 5 且范围 2 到 10`() {
        assertEquals(5, NovelPanelStyle.aheadBatches(prefs))
        NovelPanelStyle.setAheadBatches(prefs, 99)
        assertEquals(NovelQuota.MAX, NovelPanelStyle.aheadBatches(prefs))
        NovelPanelStyle.setAheadBatches(prefs, 0)
        assertEquals(NovelQuota.MIN, NovelPanelStyle.aheadBatches(prefs))
    }

    /** 旧键留在盘上也不该被当成批数读进来。 */
    @Test
    fun `旧的章数键不影响新的批配额`() {
        prefs.edit().putInt("novel_translate_ahead", 9).commit()
        assertEquals("新键缺失时必须用默认 5，而不是旧键的 9", 5, NovelPanelStyle.aheadBatches(prefs))
    }

    /**
     * **启动延迟（防抖）默认 1200ms、范围 200–5000ms**（2026-10-04 用户口径修）。
     *
     * 用户真机反馈「防抖完全没用……根本没等」——旧的默认 500ms 夹在"一批要翻 4~8 秒"中间
     * 几乎看不出来。这里钉住三件事：新默认、新范围、以及"还停在旧默认 500 的人一次性挪过来"。
     */
    @Test
    fun `启动延迟默认 1200 毫秒且旧默认一次性迁移`() {
        assertEquals("DEBOUNCE_DEFAULT 必须是 1200", 1200, NovelPanelStyle.DEBOUNCE_DEFAULT)
        assertEquals(200, NovelPanelStyle.DEBOUNCE_MIN)
        assertEquals(5000, NovelPanelStyle.DEBOUNCE_MAX)
        // 空 prefs（没存过）→ 直接给新默认
        assertEquals(1200, NovelPanelStyle.debounceMs(prefs))
        // 越界夹取
        NovelPanelStyle.setDebounceMs(prefs, 99_999)
        assertEquals(NovelPanelStyle.DEBOUNCE_MAX, NovelPanelStyle.debounceMs(prefs))
        NovelPanelStyle.setDebounceMs(prefs, 0)
        assertEquals(NovelPanelStyle.DEBOUNCE_MIN, NovelPanelStyle.debounceMs(prefs))

        // 还停在旧默认 500 的人（迁移没跑过）→ 一次性挪到 1200
        val fresh = RuntimeEnvironment.getApplication()
            .getSharedPreferences("novel_panel_style_debounce_probe", Context.MODE_PRIVATE)
        fresh.edit().putInt("novel_translate_debounce", 500).commit()
        assertEquals(1200, NovelPanelStyle.debounceMs(fresh))
        // 迁移只跑一次：之后再放回 500（用户自己拖上去的）不再被改
        fresh.edit().putInt("novel_translate_debounce", 500).commit()
        assertEquals("迁移只跑一次，用户显式设的值要留住", 500, NovelPanelStyle.debounceMs(fresh))
    }

    /**
     * 保持段落完整：**默认关闭**（用户明确要求）—— 关掉时按间距把页面填满，
     * 段落可能被切断；打开才做整段保护。
     */
    @Test
    fun `保持段落完整默认关闭且可开关`() {
        assertEquals(false, NovelPanelStyle.keepParagraphsWhole(prefs))
        NovelPanelStyle.setKeepParagraphsWhole(prefs, true)
        assertEquals(true, NovelPanelStyle.keepParagraphsWhole(prefs))
        NovelPanelStyle.setKeepParagraphsWhole(prefs, false)
        assertEquals(false, NovelPanelStyle.keepParagraphsWhole(prefs))
    }

    /** 恢复默认要把这个开关也关回去（否则「恢复默认」后排版还是整段保护）。 */
    @Test
    fun `恢复默认会关掉段落完整`() {
        NovelPanelStyle.setKeepParagraphsWhole(prefs, true)
        NovelPanelStyle.resetTypography(prefs)
        assertEquals(false, NovelPanelStyle.keepParagraphsWhole(prefs))
    }

    /** 翻译模式对外是枚举、底层仍是 0/1/2 —— 老用户存的「自动」不能被读成手动。 */
    @Test
    fun `翻译模式存取与默认值`() {
        assertEquals(NovelTranslateMode.MANUAL, NovelPanelStyle.translateMode(prefs))
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.AHEAD)
        assertEquals(NovelTranslateMode.AHEAD, NovelPanelStyle.translateMode(prefs))
        NovelPanelStyle.setTranslateMode(prefs, NovelTranslateMode.AUTO)
        assertEquals(NovelTranslateMode.AUTO, NovelPanelStyle.translateMode(prefs))
        // 旧盘上的 int 值仍要能读出来（1=自动 2=增量）
        prefs.edit().putInt("novel_translate_mode", 2).commit()
        assertEquals(NovelTranslateMode.AHEAD, NovelPanelStyle.translateMode(prefs))
    }
}
