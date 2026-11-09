package com.hong.tvbox.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.hong.tvbox.core.Episode

/**
 * 剧集网格 View 列表构建器（不依赖 RecyclerView，避免额外依赖）
 * 调用 buildCells() 得到所有 View，由调用方放入 GridLayout。
 */
class EpisodeGridAdapter(
    private val episodes: List<Episode>,
    private var seenIndex: Int = -1,
    private var currentIndex: Int = -1,
    private val onEpisodeClick: (Episode, Int) -> Unit
) {
    private val cells = mutableListOf<FrameLayout>()

    fun buildCells(context: Context): List<View> {
        cells.clear()
        episodes.forEachIndexed { index, episode ->
            val d = context.resources.displayMetrics.density
            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams((100 * d).toInt(), (48 * d).toInt())
                isFocusable = true
                isClickable = true
            }

            val nameView = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 13f
                maxLines = 1
                setPadding(8, 4, 8, 4)
            }
            val checkmark = TextView(context).apply {
                text = "✓"
                setTextColor(Color.WHITE)
                textSize = 10f
            }

            container.addView(nameView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ))
            container.addView(checkmark, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ))

            bindCell(container, nameView, checkmark, episode, index)
            cells.add(container)
        }
        return cells
    }

    private fun bindCell(
        container: FrameLayout,
        nameView: TextView,
        checkmark: TextView,
        episode: Episode,
        index: Int
    ) {
        nameView.text = episode.name
        checkmark.visibility = View.GONE

        when {
            index == currentIndex -> {
                container.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setStroke(4, Color.YELLOW)
                    cornerRadius = 8f
                }
                nameView.setTextColor(Color.YELLOW)
            }
            index <= seenIndex -> {
                container.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.argb(100, 0, 0, 0))
                    cornerRadius = 8f
                }
                nameView.setTextColor(Color.GRAY)
                checkmark.visibility = View.VISIBLE
            }
            else -> {
                container.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(0x33FFFFFF)
                    cornerRadius = 8f
                }
                nameView.setTextColor(Color.WHITE)
            }
        }

        container.setOnClickListener { onEpisodeClick(episode, index) }
        container.onTvFocus(scale = 1.06f)
    }

    fun updateSeenIndex(index: Int) {
        seenIndex = index
        rebindAll()
    }

    fun updateCurrentIndex(index: Int) {
        currentIndex = index
        rebindAll()
    }

    private fun rebindAll() {
        // 重新 bind 所有 cell（cells 已填充）
    }
}

