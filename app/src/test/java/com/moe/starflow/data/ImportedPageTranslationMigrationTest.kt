package com.moe.starflow.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 译文逐页表（`imported_page_translation`）的 **v20 → v21 迁移**：新增 `ocrModel` 列。
 *
 * 为什么必须单独守住这一条：`fallbackToDestructiveMigration` 是开着的 —— 迁移写错/漏写时
 * **不报错**，而是把升级用户的整库删掉（译文、历史全没）。而这属于"全新安装完全遇不到"的
 * 那类问题，只能靠真建库跑一遍迁移来兜。
 *
 * 两条断言：
 * 1. **迁移后的列集与 Room 由 [ImportedPageTranslation] 生成的 schema 逐列一致**
 *    （名字/类型/NOT NULL/主键），且没有任何 DEFAULT —— Kotlin 的 `= null` 只是语言层默认值。
 * 2. **老数据不许丢**：v20 的行迁移后原样存在，新列读出来是 NULL（旧行的模型名已无从考证，
 *    绝不能用"当前设置"糊上去）。
 */
@RunWith(RobolectricTestRunner::class)
class ImportedPageTranslationMigrationTest {

    private var db: TranslationHistoryDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    private fun openDb() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TranslationHistoryDatabase::class.java
        ).build()
    }

    /** v20 时点的表结构（= 现在的实体**去掉 `ocrModel`**，Room 建表语句的原文照抄）。 */
    private fun createV20Table(scratch: SupportSQLiteDatabase) {
        scratch.execSQL(
            "CREATE TABLE IF NOT EXISTS `imported_page_translation` (" +
                "`mangaId` INTEGER NOT NULL, `pageIndex` INTEGER NOT NULL, `state` INTEGER NOT NULL, " +
                "`sourceText` TEXT, `translatedText` TEXT, `bubbleRects` TEXT, `failCode` TEXT, " +
                "`failMessage` TEXT, `updatedAtMs` INTEGER NOT NULL, `mangaKey` TEXT, " +
                "`translatorName` TEXT, `sourceLang` TEXT, `targetLang` TEXT, " +
                "PRIMARY KEY(`mangaId`, `pageIndex`))"
        )
    }

    /** 空库 + 只跑迁移语句的另一个库（与 `ImportedPageSrDaoTest` 同一套路）。 */
    private fun scratchDb(): SupportSQLiteDatabase {
        val ctx = RuntimeEnvironment.getApplication()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ctx)
                .name(null)                      // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        return helper.writableDatabase
    }

    private data class Col(val name: String, val type: String, val notNull: Boolean, val pk: Int)

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): List<Col> {
        val out = ArrayList<Col>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                out += Col(
                    name = c.getString(c.getColumnIndexOrThrow("name")),
                    type = c.getString(c.getColumnIndexOrThrow("type")).uppercase(),
                    notNull = c.getInt(c.getColumnIndexOrThrow("notnull")) == 1,
                    pk = c.getInt(c.getColumnIndexOrThrow("pk")),
                )
            }
        }
        return out.sortedBy { it.name }
    }

    @Test
    fun v20ToV21AddsOcrModelAndKeepsTheRows() {
        val scratch = scratchDb()
        createV20Table(scratch)
        scratch.execSQL(
            "INSERT INTO imported_page_translation " +
                "(mangaId, pageIndex, state, sourceText, updatedAtMs, mangaKey, translatorName, sourceLang, targetLang) " +
                "VALUES (7, 3, 4, '[1] こんにちは', 1234, 'book|99', NULL, 'ja', 'zh')"
        )

        TranslationHistoryDatabase.MIGRATION_20_21.migrate(scratch)

        scratch.query(
            "SELECT mangaId, pageIndex, state, sourceText, ocrModel, translatorName FROM imported_page_translation"
        ).use { c ->
            assertTrue("迁移后老行必须还在（迁移写错 = 整库被删）", c.moveToFirst())
            assertEquals(7L, c.getLong(0))
            assertEquals(3, c.getInt(1))
            assertEquals(ImportedPageTranslation.STATE_OCR, c.getInt(2))
            assertEquals("[1] こんにちは", c.getString(3))
            assertNull("旧行的模型名无从考证 → 必须是 NULL，不许拿当前设置糊上去", c.getString(4))
            assertNull(c.getString(5))
        }
    }

    @Test
    fun v21ColumnsMatchTheEntityExactly() {
        openDb()
        // Room 由 ImportedPageTranslation 生成的真实 schema
        val actual = columnsOf(db!!.openHelper.writableDatabase, "imported_page_translation")

        // 从 v20 表跑一次迁移后的样子
        val scratch = scratchDb()
        createV20Table(scratch)
        TranslationHistoryDatabase.MIGRATION_20_21.migrate(scratch)
        val migrated = columnsOf(scratch, "imported_page_translation")

        assertEquals(
            "迁移后的表与实体 schema 不一致 —— 升级用户一打开库就会抛 IllegalStateException" +
                "（全新安装完全遇不到）。逐列对比：",
            actual, migrated,
        )
        assertTrue("ocrModel 列必须在", actual.any { it.name == "ocrModel" })
        assertEquals("列数也要对上", actual.size, actual.map { it.name }.distinct().size)
        assertNull(
            "不许出现 DEFAULT（Kotlin 默认值是语言层的，Room 建表语句里没有 DEFAULT 子句）",
            run {
                var dflt: String? = null
                scratch.query("PRAGMA table_info(`imported_page_translation`)").use { c ->
                    while (c.moveToNext() && dflt == null) {
                        val v = c.getString(c.getColumnIndexOrThrow("dflt_value"))
                        if (v != null) dflt = v
                    }
                }
                dflt
            },
        )
    }

    /** 新列真的能存能读（否则详情面板永远显示不出模型名，而且不报错）。 */
    @Test
    fun ocrModelRoundTrips() = runBlocking {
        openDb()
        val dao = db!!.importedPageTranslationDao()
        dao.upsert(
            ImportedPageTranslation(
                mangaId = 1L, pageIndex = 0, state = ImportedPageTranslation.STATE_OCR,
                sourceText = "[1] 原文", bubbleRects = "[]", ocrModel = "PP-OCRv5+PPOcrV5",
                updatedAtMs = 1L, mangaKey = "book|1",
            )
        )
        assertEquals("PP-OCRv5+PPOcrV5", dao.get(1L, 0)?.ocrModel)
    }
}
