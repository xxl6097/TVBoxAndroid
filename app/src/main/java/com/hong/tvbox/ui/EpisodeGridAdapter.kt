package com.hong.tvbox.ui

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hong.tvbox.core.Episode
import com.hong.tvbox.core.TvUi
import android.graphics.Color

/**
 * 剧集网格 RecyclerView Adapter
 *
 * 问题：DetailActivity 使用 GridLayout，100+ 集加载时首帧延迟 2-3 秒，内存泄漏
 * 解决：改用 RecyclerView + GridLayoutManager，ViewHolder 复用，支持虚拟滚动
 *
 * 特性：
 *   - 已看剧集：暗色 + ✓ 标记
 *   - 当前播放集：黄色描边
 *   - ViewHolder 复用，大幅降低内存占用
 */
class EpisodeGridAdapter(
    private val episodes: List<Episode>,
    private var seenIndex: Int = -1,
    private var currentIndex: Int = -1,
    private val onEpisodeClick: (Episode, Int) -> Unit
) : RecyclerView.Adapter<EpisodeGridAdapter.EpisodeViewHolder>() {

    inner class EpisodeViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        private val poster = ImageView(container.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        private val nameView = TextView(container.context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
        }
        private val checkmark = TextView(container.context).apply {
            text = "✓"
            setTextColor(Color.WHITE)
            textSize = 10f
        }

        init {
            container.removeAllViews()
            container.addView(poster, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            container.addView(nameView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.START
            ))
            container.addView(checkmark, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ))
        }

        fun bind(episode: Episode, index: Int) {
            nameView.text = episode.name

            // 剧集背景色：当前播放=黄色描边，已看=暗色，未看=正常
            when {
                index == currentIndex -> {
                    container.setBackgroundColor(Color.TRANSPARENT)
                    container.setPadding(2, 2, 2, 2)
                    val drawable = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        setStroke(3, Color.YELLOW)
                    }
                    container.background = drawable
                }
                index < seenIndex -> {
                    container.setBackgroundColor(Color.argb(128, 0, 0, 0))
                    nameView.setTextColor(Color.GRAY)
                    checkmark.visibility = android.view.View.VISIBLE
                }
                else -> {
                    container.setBackgroundColor(0x33000000)
                    nameView.setTextColor(Color.WHITE)
                    checkmark.visibility = android.view.View.GONE
                }
            }

            container.setOnClickListener {
                onEpisodeClick(episode, index)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EpisodeViewHolder {
        val d = parent.resources.displayMetrics.density
        val container = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                (120 * d).toInt(),  // 120dp 宽
                (180 * d).toInt()   // 180dp 高
            )
            onTvFocus()  // TV 焦点样式
        }
        return EpisodeViewHolder(container)
    }

    override fun onBindViewHolder(holder: EpisodeViewHolder, position: Int) {
        holder.bind(episodes[position], position)
    }

    override fun getItemCount(): Int = episodes.size

    fun updateSeenIndex(index: Int) {
        seenIndex = index
        notifyItemRangeChanged(0, minOf(seenIndex + 1, episodes.size))
    }

    fun updateCurrentIndex(index: Int) {
        val oldIndex = currentIndex
        currentIndex = index
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        notifyItemChanged(index)
    }
}
