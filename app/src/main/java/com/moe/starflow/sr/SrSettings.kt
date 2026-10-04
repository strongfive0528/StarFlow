package com.moe.starflow.sr

import android.content.SharedPreferences

/**
 * 超分（SR）的**开关状态唯一来源**。
 *
 * ⚠️ **超分只服务阅读器**（2026-10 用户口径）：截图 / 录屏的翻译链路**不做超分** ——
 * 那两条链路（`MangaFloatingService` / `FloatingBallService`）已恢复成「裁剪后直接喂 OCR」，
 * 个性化里也不再有任何超分开关。所以这里**只剩一个开关**。
 *
 * 曾经有过「总开关 + 游戏模式 + 漫画模式」（AND 关系），已整体删除；对应的 prefs 键
 * （`sr_enabled` / `sr_enabled_game` / `sr_enabled_comic`）不再读取，老用户残留的值无害。
 *
 * | prefs 键 | 作用范围 |
 * |---|---|
 * | [KEY_READER_ENABLED] | **阅读器专用**：只影响漫画阅读器（先超分再识别 + 每页手动超分） |
 *
 * 模型是否"选好了/下载了"由 [SrModelManager] 负责 —— 这里只管开关。
 */
object SrSettings {

    /** 阅读器超分开关 */
    const val KEY_READER_ENABLED = "sr_reader_enabled"

    /**
     * **「翻译时自动超分」**子开关（2026-10 用户口径）。
     *
     * - **开**（默认）：点翻译 → **先原图 OCR** → OCR 完成后启动超分（与翻译请求并行；
     *   本地引擎则串行）
     * - **关**：翻译**不自动超分**，用户点每页按钮手动超分（该按钮同时充当"原图⇄超分底图"二态切换）
     *
     * ⚠️ 名字刻意不叫「OCR 同时超分」：超分发生在 OCR **之后**，叫"同时"会让人以为它参与识别
     * （v2 起 OCR 永远吃原图，超分纯粹是**显示增强**）。
     */
    const val KEY_AUTO = "sr_reader_auto"

    /**
     * **「超过这个长边就不超分」**（0 = 关闭；默认关闭）—— 对齐 TachiyomiX 模型包的
     * `maxProcessingResolution`（他们默认 1600）。
     *
     * 用途：大尺寸页（A4@300dpi=2480、B5@300dpi=2150）本来就不该跑 —— 产物不会比原图更清晰
     * （[SrDisplayBase.isUsableBase] 已能判出来，但那是**跑完之后**才判），
     * 在这里提前跳过能省掉 2~6 秒/页的推理。判据收在 [SrSkipPolicy]。
     */
    const val KEY_MAX_LONG_SIDE = "sr_max_long_side"

    /** 默认 **关**（0）：默认跳过会让用户看到"超分没反应"，必须他显式选阈值 */
    const val DEFAULT_MAX_LONG_SIDE = SrSkipPolicy.DISABLED

    /** 默认 **关**：超分只对真正低分辨率的图源有意义，且更耗电更慢，必须用户显式开启 */
    const val DEFAULT_READER_ENABLED = false

    /** 「翻译时自动超分」默认 **开**（用户开启了超分总开关，就是想要它生效） */
    const val DEFAULT_AUTO = true

    fun isEnabledForReader(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_READER_ENABLED, DEFAULT_READER_ENABLED)

    /** 阅读器开关的写入端（阅读器面板的 Switch 调它）。写入后记得让引擎释放缓存 */
    fun setReaderEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READER_ENABLED, enabled).apply()
    }

    /** 「翻译时自动超分」是否开启（**必须先满足 [isEnabledForReader]**） */
    fun isAutoEnabledForReader(prefs: SharedPreferences): Boolean =
        isEnabledForReader(prefs) && prefs.getBoolean(KEY_AUTO, DEFAULT_AUTO)

    fun setAutoEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    /**
     * 跳过阈值（长边像素；0 = 不跳过）。**只从 prefs 读，判据不在调用点各写一份** ——
     * 三处调用点（单页 / 章节批量 / 阅读器预热）必须同源，否则又会出现"有的路径跳、有的不跳"。
     */
    fun maxLongSideForSkip(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_MAX_LONG_SIDE, DEFAULT_MAX_LONG_SIDE).coerceAtLeast(SrSkipPolicy.DISABLED)

    fun setMaxLongSideForSkip(prefs: SharedPreferences, longSide: Int) {
        prefs.edit().putInt(KEY_MAX_LONG_SIDE, longSide.coerceAtLeast(SrSkipPolicy.DISABLED)).apply()
    }
}
