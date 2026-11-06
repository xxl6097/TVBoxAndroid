package com.hong.tvbox.ui

import android.graphics.Color
import android.view.MotionEvent
import android.view.View

/**
 * TV 焦点样式。
 *
 * 遥控器场景下没有鼠标悬停，用户唯一的反馈就是“焦点框在哪”。
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
    isFocusableInTouchMode = true
    setBackgroundColor(normalColor)

    setOnFocusChangeListener { v, hasFocus ->
        v.setBackgroundColor(if (hasFocus) focusColor else normalColor)
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
                MotionEvent.ACTION_HOVER_ENTER -> v.setBackgroundColor(focusColor)
                MotionEvent.ACTION_HOVER_EXIT -> v.setBackgroundColor(normalColor)
            }
        }
        false
    }
}
