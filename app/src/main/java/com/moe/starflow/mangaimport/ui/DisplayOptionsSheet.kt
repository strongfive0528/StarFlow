package com.moe.starflow.mangaimport.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.SeekBar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.moe.starflow.R

enum class DisplaySortOrder { TITLE, ADDED, RECENTLY_READ }

/**
 * 显示选项底部弹窗：4 种列表模式 + 网格尺寸 + 排序。
 * 各选项切换即生效（无需点「应用」），通过回调实时持久化并刷新书架。
 */
class DisplayOptionsSheet(
    private val currentMode: DisplayMode,
    private val currentGridSize: Int,
    private val currentSortOrder: DisplaySortOrder,
    private val supportsRecentlyRead: Boolean = false,
    private val onApply: (DisplayMode, Int, DisplaySortOrder) -> Unit,
) : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        // 圆角对齐 app 弹窗主题（dialog_background 样式）
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(R.drawable.bg_bottom_sheet)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.sheet_display_options, container, false)

        val modeView = view.findViewById<RadioGroup>(R.id.rg_mode)
        val sizeView = view.findViewById<SeekBar>(R.id.seek_grid_size)
        val sortView = view.findViewById<RadioGroup>(R.id.rg_sort)
        val recentReadView = view.findViewById<View>(R.id.rb_sort_recent_read)
        recentReadView.visibility = if (supportsRecentlyRead) View.VISIBLE else View.GONE

        fun modeToId(mode: DisplayMode): Int = when (mode) {
            DisplayMode.DETAILED_LIST -> R.id.rb_detailed
            DisplayMode.GRID -> R.id.rb_grid
        }

        modeView.check(modeToId(currentMode))
        sizeView.progress = currentGridSize
        fun sortOrderFor(id: Int): DisplaySortOrder = when (id) {
            R.id.rb_sort_added -> DisplaySortOrder.ADDED
            R.id.rb_sort_recent_read -> DisplaySortOrder.RECENTLY_READ
            else -> DisplaySortOrder.TITLE
        }

        val initialSort = currentSortOrder.takeIf {
            supportsRecentlyRead || it != DisplaySortOrder.RECENTLY_READ
        } ?: DisplaySortOrder.TITLE
        sortView.check(
            when (initialSort) {
                DisplaySortOrder.ADDED -> R.id.rb_sort_added
                DisplaySortOrder.RECENTLY_READ -> R.id.rb_sort_recent_read
                DisplaySortOrder.TITLE -> R.id.rb_sort_title
            }
        )

        // 各选项切换即生效
        var selectedMode = currentMode
        modeView.setOnCheckedChangeListener { _, checkedId ->
            selectedMode = when (checkedId) {
                R.id.rb_detailed -> DisplayMode.DETAILED_LIST
                else -> DisplayMode.GRID
            }
            onApply(selectedMode, sizeView.progress, sortOrderFor(sortView.checkedRadioButtonId))
        }

        var gridSize = currentGridSize
        sizeView.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gridSize = progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}

                // 拖动结束时自动应用（避免拖动中反复刷新）
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    onApply(selectedMode, gridSize, sortOrderFor(sortView.checkedRadioButtonId))
                }
            }
        )

        sortView.setOnCheckedChangeListener { _, checkedId ->
            onApply(selectedMode, sizeView.progress, sortOrderFor(checkedId))
        }

        return view
    }

    companion object {
        const val TAG = "DisplayOptionsSheet"
    }
}
