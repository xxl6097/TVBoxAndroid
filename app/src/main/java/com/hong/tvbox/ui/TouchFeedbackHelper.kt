package com.hong.tvbox.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.view.View

/**
 * 触摸反馈助手：统一处理按钮点击、长按的视觉 + 触觉反馈
 *
 * Android 6+ 设备需要主动提供触摸反馈以提升用户体验。
 * 本类提供统一的按钮点击反馈、长按反馈、震动等机制。
 */
object TouchFeedbackHelper {

    private var vibrator: Vibrator? = null
    private var isHapticEnabled = true

    fun init(context: Context) {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        isHapticEnabled = vibrator?.hasVibrator() ?: false
    }

    /**
     * 为按钮添加触摸反馈（视觉 + 触觉）
     *
     * @param view 目标按钮
     * @param hapticEnabled 是否启用震动反馈（默认 true）
     */
    fun setupTouchFeedback(view: View, hapticEnabled: Boolean = true) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 按下时变暗
                    v.alpha = 0.7f
                    if (hapticEnabled && isHapticEnabled) {
                        performHapticFeedback(10)  // 10ms 短振动
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // 松开时恢复
                    v.alpha = 1f
                }
            }
            false  // 继续传递事件给 onClick
        }
    }

    /**
     * 执行触觉反馈
     *
     * @param durationMs 振动时长（毫秒）
     * @param amplitude 振动强度（0-255，仅 Android 8+ 支持）
     */
    fun performHapticFeedback(durationMs: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        if (!isHapticEnabled || vibrator == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, amplitude)
                )
            } catch (e: Exception) {
                // 忽略振动权限错误
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                vibrator?.vibrate(durationMs)
            } catch (e: Exception) {
                // 忽略振动权限错误
            }
        }
    }

    /**
     * 执行长按反馈（更强的振动）
     */
    fun performLongPressFeedback() {
        performHapticFeedback(25)  // 25ms 更长的振动
    }

    /**
     * 禁用/启用震动
     */
    fun setHapticEnabled(enabled: Boolean) {
        isHapticEnabled = enabled && vibrator?.hasVibrator() ?: false
    }
}
