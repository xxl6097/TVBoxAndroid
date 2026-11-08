package com.hong.tvbox.core

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RatingBar
import android.widget.TextView

/**
 * 评论管理与 UI 构建
 *
 * 问题：许多采集源返回评论，播放器 OSD 无法显示
 * 解决：提供评论 UI 构建接口，支持评分展示与滑动查看
 */
object CommentManager {

    /**
     * 从 vod_content 中提取评论（简易解析）
     *
     * 采集源通常返回形如 "简介\n\n用户评价：\nA: 很好看\nB: 不错\n..." 的格式
     * 这里做简单拆分，实际应用中应由源侧标准化返回
     *
     * @param content 内容字符串
     * @return 评论列表
     */
    fun parseComments(content: String): List<Comment> {
        // 简易实现：按换行符分割，每行尝试作为一条评论
        // 实际应用中应由采集源标准化返回 JSON
        val comments = mutableListOf<Comment>()

        val lines = content.split("\n").filter { it.isNotBlank() }
        lines.forEach { line ->
            val trimmed = line.trim()
            // 简单启发式：包含冒号的行视为「发表者: 评论内容」
            val colonIdx = trimmed.indexOf(':')
            if (colonIdx in 0 until trimmed.length - 1) {
                val author = trimmed.substring(0, colonIdx).trim()
                val text = trimmed.substring(colonIdx + 1).trim()
                if (author.isNotEmpty() && text.isNotEmpty()) {
                    comments.add(Comment(
                        author = author,
                        text = text,
                        rating = 0,  // 采集源通常无评分
                        time = "",
                        likes = 0
                    ))
                }
            }
        }

        return comments.take(10)  // 只留前 10 条评论
    }

    /**
     * 构建评论面板 UI
     *
     * @param context Android Context
     * @param comments 评论列表
     * @return 评论面板 FrameLayout
     */
    fun buildCommentPanel(context: Context, comments: List<Comment>): FrameLayout {
        val panel = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }

        // 标题
        val titleView = TextView(context).apply {
            text = "评论 (${comments.size})"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(20, 20, 20, 20)
        }
        panel.addView(titleView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.TOP
        ))

        // 评论列表（简单实现：纵向 TextView 堆叠）
        val commentsContainer = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                android.view.Gravity.TOP
            ).apply {
                topMargin = 80  // 标题下方
                bottomMargin = 60
            }
        }

        comments.forEachIndexed { idx, comment ->
            val commentView = buildCommentItemView(context, comment)
            commentsContainer.addView(commentView, android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                if (idx > 0) topMargin = 15
            })
        }

        panel.addView(commentsContainer)
        return panel
    }

    /**
     * 构建单条评论 UI
     */
    private fun buildCommentItemView(context: Context, comment: Comment): FrameLayout {
        val item = FrameLayout(context)
        val d = context.resources.displayMetrics.density

        // 作者与评分
        val headerView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val authorView = TextView(context).apply {
            text = comment.author
            setTextColor(Color.YELLOW)
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerView.addView(authorView)

        if (comment.rating > 0) {
            val ratingView = RatingBar(context).apply {
                rating = comment.rating.toFloat()
                numStars = 5
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (100 * d).toInt(),
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            headerView.addView(ratingView)
        }

        item.addView(headerView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // 评论内容
        val contentView = TextView(context).apply {
            text = comment.text
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (30 * d).toInt()
                leftMargin = (10 * d).toInt()
                rightMargin = (10 * d).toInt()
            }
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        }
        item.addView(contentView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = (40 * d).toInt()
        })

        return item
    }
}
