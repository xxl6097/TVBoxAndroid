package com.hong.tvbox.ui

import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.Window
import kotlin.math.abs

/**
 * 播放器手势控制
 *
 * 问题：仅支持遥控器，手机/平板用户操作不便
 * 解决：
 *   - 快进/快退：左右滑动 (±10s)
 *   - 亮度调节：上下滑动屏幕左半侧
 *   - 音量调节：上下滑动屏幕右半侧
 */
class PlayerGestureController(
    private val context: Context,
    private val window: Window,
    private val onSeek: (Long) -> Unit,
    private val onBrightnessChange: (Float) -> Unit = {},
    private val onVolumeChange: (Int) -> Unit = {}
) : GestureDetector.SimpleOnGestureListener() {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val gestureDetector = GestureDetector(context, this)

    /**
     * 分发触摸事件到手势检测器
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent?,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (e1 == null || e2 == null) return false

        val x1 = e1.x
        val screenWidth = if (window.decorView.width > 0) window.decorView.width else 1080
        val screenHeight = if (window.decorView.height > 0) window.decorView.height else 1920

        val absDX = abs(distanceX)
        val absDY = abs(distanceY)

        // 判断滑动方向
        return when {
            // 左右滑动：快进/快退（±10 秒）
            absDX > absDY && absDX > 50 -> {
                val seekTime = if (distanceX > 0) -10000L else 10000L  // 毫秒
                onSeek(seekTime)
                true
            }

            // 上下滑动：亮度（左半屏）或音量（右半屏）
            absDY > absDX && absDY > 50 -> {
                if (x1 < screenWidth / 2) {
                    // 左半屏：调节亮度
                    adjustBrightness(distanceY, screenHeight)
                } else {
                    // 右半屏：调节音量
                    adjustVolume(distanceY)
                }
                true
            }

            else -> false
        }
    }

    /**
     * 调节亮度
     *
     * @param distance 滑动距离（正=向下滑，亮度降低；负=向上滑，亮度升高）
     * @param screenHeight 屏幕高度，用于计算亮度变化幅度
     */
    private fun adjustBrightness(distance: Float, screenHeight: Int) {
        val currentBrightness = window.attributes.screenBrightness
        val delta = (distance / screenHeight) * 0.5f  // 最多改变 50%
        val newBrightness = (currentBrightness + delta).coerceIn(0f, 1f)

        window.attributes = window.attributes.apply {
            screenBrightness = newBrightness
        }
        onBrightnessChange(newBrightness)
    }

    /**
     * 调节音量
     *
     * @param distance 滑动距离（正=向下滑，音量降低；负=向上滑，音量升高）
     */
    private fun adjustVolume(distance: Float) {
        val delta = if (distance > 0) -1 else 1
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val newVolume = (currentVolume + delta).coerceIn(0, maxVolume)

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, AudioManager.FLAG_SHOW_UI)
        onVolumeChange(newVolume)
    }

    /**
     * 长按手势检测（可扩展，如长按快进）
     */
    override fun onLongPress(e: MotionEvent?) {
        // TODO: 长按快进实现
    }

    /**
     * 双击手势检测（可用于播放/暂停切换）
     */
    override fun onDoubleTap(e: MotionEvent?): Boolean {
        // TODO: 双击播放/暂停实现
        return true
    }
}
