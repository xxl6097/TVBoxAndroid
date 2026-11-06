package com.hong.tvbox.ui

import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.hong.tvbox.core.History

/**
 * 「继续观看」卡片 - 带进度条的横版卡。
 *
 * 用 Leanback 的 ImageCardView 塞不下进度条（它内部布局是固定的），
 * 所以这里自建一个卡片：海报 + 片名 + 看到第几集 + 进度条。
 * 宽度固定 320dp，和 TV 上 16:9 缩略图的比例接近。
 */
class HistoryCardPresenter : Presenter() {

    private val cardWidth = 320
    private val cardHeight = 236

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(dp(cardWidth), dp(cardHeight))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val imageBox = FrameLayout(ctx)
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#33000000"))
            id = android.R.id.icon
        }
        imageBox.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(168)
        ))

        // 右下角进度条，压在图上
        val progress = ProgressBar(
            ctx, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            id = android.R.id.progress
            progressDrawable?.setAlpha(230)
        }
        imageBox.addView(progress, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM
        ))

        card.addView(imageBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(168)
        ))

        val title = TextView(ctx).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
            id = android.R.id.title
        }
        card.addView(title)

        val sub = TextView(ctx).apply {
            textSize = 12f
            setTextColor(TvUi.DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            id = android.R.id.text1
        }
        card.addView(sub)

        card.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1.04f)

        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val h = item as? History ?: return
        val card = viewHolder.view as LinearLayout

        val img = card.findViewById<ImageView>(android.R.id.icon)
        if (h.vodPic.isNotEmpty()) {
            Glide.with(card.context).load(h.vodPic).centerCrop().into(img)
        } else {
            img.setImageDrawable(null)
        }

        card.findViewById<TextView>(android.R.id.title).text = h.vodName

        val ep = if (h.episodeName.isNotEmpty()) h.episodeName else "第 ${h.episodeIndex + 1} 集"
        val progressText = if (h.duration > 0) {
            "$ep · ${fmt(h.position)} / ${fmt(h.duration)}"
        } else ep
        card.findViewById<TextView>(android.R.id.text1).text = progressText

        val progress = card.findViewById<ProgressBar>(android.R.id.progress)
        if (h.duration > 0 && h.position > 0) {
            progress.visibility = android.view.View.VISIBLE
            progress.progress = ((h.position * 1000) / h.duration).toInt().coerceIn(0, 1000)
        } else {
            progress.visibility = android.view.View.GONE
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        // 卡片里的图由 Glide 管理，无需手动回收
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
