package com.hong.tvbox.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import com.hong.tvbox.core.GlideOptimizer

/**
 * TV 焦点样式。
 *
 * 遥控器场景下没有鼠标悬停，用户唯一的反馈就是"焦点框在哪"。
 * 之前卡片/胶囊只是普通可点 View，拿到焦点后外观完全不变，
 * 方向键按下去屏幕上没有任何变化，等于无法操作。
 *
 * 取值沿用 FongMi/TV leanback 资源：
 *   常态底 black_20 (#33000000)，焦点底 black_40 (#66000000)，
 *   选中文字 yellow_500 (#FFEB3B)，焦点缩放 1.1×（ZOOM_FACTOR_SMALL）。
 */
object TvUi {
    const val CARD = 0x33000000
    const val CARD_HI = 0x66000000
    const val BG = 0xFF141218.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xB3FFFFFF.toInt()
    const val DIM = 0x80FFFFFF.toInt()
    const val SELECTED = 0xFFFFEB3B.toInt()
    const val WARN = 0xFFFFB4AB.toInt()
}

/**
 * 给任意 View 加上“焦点高亮 + 轻微放大”。
 *
 * @param normalColor 未聚焦时的底色
 * @param focusColor  聚焦时的底色
 * @param scale       聚焦缩放倍数（列表卡片 1.06，胶囊按钮保持 1.0 以免相邻元素重叠）
 */
fun View.onTvFocus(
    normalColor: Int = TvUi.CARD,
    focusColor: Int = TvUi.CARD_HI,
    scale: Float = 1.06f
) {
    isFocusable = true
    // 不要设 isFocusableInTouchMode：触摸模式下第一次点击只会把焦点移过去而不触发点击，
    // 手机上表现为"要点两下"。TV 走非触摸模式，方向键聚焦不受影响。

    val d = resources.displayMetrics.density

    // 只用底色深浅区分焦点在电视上几乎看不出来（隔着 3 米），
    // 必须加一圈高对比描边，用户才知道遥控器现在指着谁
    fun bg(color: Int, focused: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10f * d
        setColor(color)
        if (focused) setStroke((2.5f * d).toInt(), TvUi.SELECTED)
    }

    background = bg(normalColor, false)

    setOnFocusChangeListener { v, hasFocus ->
        v.background = bg(if (hasFocus) focusColor else normalColor, hasFocus)
        if (scale != 1f) {
            v.animate()
                .scaleX(if (hasFocus) scale else 1f)
                .scaleY(if (hasFocus) scale else 1f)
                .setDuration(120)
                .start()
        }
    }

    // 部分盒子把遥控器当鼠标设备上报 hover（MotionEvent），一并接住。
    // 注意 setOnHoverListener 的回调签名是 (View, MotionEvent) -> Boolean，不是布尔入参。
    setOnHoverListener { v, event ->
        if (!v.hasFocus()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> v.background = bg(focusColor, true)
                MotionEvent.ACTION_HOVER_EXIT -> v.background = bg(normalColor, false)
            }
        }
        false
    }
}

/**
 * 输入框统一外观。
 *
 * 原来 EditText 背景是全透明，在深色页面上只能看到 hint 文字，
 * 用户根本不知道哪里能点、能输入多长。给一层圆角深底 + 细描边。
 */
fun EditText.tvInputStyle() {
    val d = resources.displayMetrics.density
    background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 10f * d
        setColor(0xFF1C1B1F.toInt())
        setStroke((1f * d).toInt(), 0x40FFFFFF)
    }
    setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
}

/**
 * 页面初始焦点设置：首个可聚焦子 View 获取焦点（遥控器进入页面后立即可用方向键）。
 */
fun android.view.ViewGroup.requestFirstChildFocus() {
    post {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isFocusable && child.visibility == android.view.View.VISIBLE) {
                child.requestFocus()
                return@post
            }
            if (child is android.view.ViewGroup) {
                child.requestFirstChildFocus()
                if (child.hasFocus()) return@post
            }
        }
    }
}

/**
 * ImageView 扩展函数：加载缩略图（海报墙）
 * 使用 GlideOptimizer 的优化策略
 */
fun ImageView.loadThumbnail(url: String, placeholder: Int? = null) {
    GlideOptimizer.loadThumbnail(this, url, placeholder)
}

/**
 * ImageView 扩展函数：加载全图（详情页海报）
 */
fun ImageView.loadFullImage(url: String, placeholder: Int? = null) {
    GlideOptimizer.loadFullImage(this, url, placeholder)
}

/**
 * ImageView 扩展函数：加载自定义尺寸图片
 */
fun ImageView.loadCustomSize(url: String, width: Int, height: Int) {
    GlideOptimizer.loadCustomSize(this, url, width, height)
}

