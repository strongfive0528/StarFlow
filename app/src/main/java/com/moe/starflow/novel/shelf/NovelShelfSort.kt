package com.moe.starflow.novel.shelf

import com.moe.starflow.mangaimport.ui.DisplaySortOrder
import com.moe.starflow.novel.data.ImportedNovel

internal object NovelShelfSort {
    fun sort(items: List<ImportedNovel>, order: DisplaySortOrder): List<ImportedNovel> = when (order) {
        DisplaySortOrder.TITLE -> items.sortedBy { it.title.lowercase() }
        DisplaySortOrder.ADDED -> items.sortedByDescending { it.addedAt }
        DisplaySortOrder.RECENTLY_READ -> items.sortedWith(
            compareByDescending<ImportedNovel> { it.lastReadAt }
                .thenByDescending { it.addedAt }
                .thenBy { it.title.lowercase() },
        )
    }
}
