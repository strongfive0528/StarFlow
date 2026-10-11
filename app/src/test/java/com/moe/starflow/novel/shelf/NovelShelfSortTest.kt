package com.moe.starflow.novel.shelf

import com.moe.starflow.mangaimport.ui.DisplaySortOrder
import com.moe.starflow.novel.data.ImportedNovel
import com.moe.starflow.novel.model.NovelFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelShelfSortTest {
    private fun novel(id: Long, lastReadAt: Long, addedAt: Long = id) = ImportedNovel(
        id = id,
        title = "Book $id",
        localRoot = "/tmp/$id",
        format = NovelFormat.TXT,
        chapterCount = 1,
        addedAt = addedAt,
        lastReadAt = lastReadAt,
    )

    @Test
    fun `recently read sorts by last read time and places unread books last`() {
        val items = listOf(
            novel(id = 1, lastReadAt = 0, addedAt = 100),
            novel(id = 2, lastReadAt = 300),
            novel(id = 3, lastReadAt = 200),
            novel(id = 4, lastReadAt = 0, addedAt = 200),
        )

        assertEquals(
            listOf(2L, 3L, 4L, 1L),
            NovelShelfSort.sort(items, DisplaySortOrder.RECENTLY_READ).map { it.id },
        )
    }

    @Test
    fun `recently read ties are stable by added time`() {
        val items = listOf(novel(id = 1, lastReadAt = 50, addedAt = 10), novel(id = 2, lastReadAt = 50, addedAt = 20))

        assertEquals(
            listOf(2L, 1L),
            NovelShelfSort.sort(items, DisplaySortOrder.RECENTLY_READ).map { it.id },
        )
    }
}
