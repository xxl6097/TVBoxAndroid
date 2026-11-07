package com.hong.tvbox

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.hong.tvbox.data.ConfigStore

/**
 * 全局点击反馈（声音 + 震动）。
 *
 * 用户按任意按钮时给一个「声音 + 震动」的即时反馈，让操作有实感。
 * 两个通道各自独立开关，偏好存本机：
 *   tapSound   0 = 关 / 1 = 开（默认开）
 *   tapVibrate 0 = 关 / 1 = 开（默认开）
 *
 * 声音用 SoundPool 播放 28ms 的 click.wav（低延迟短音效，不打断正在播的片），
 * 震动触发一次 20ms 短震。二者都 fire-and-forget，不阻塞 UI 线程。
 */
object Feedback {

    private var pool: SoundPool? = null
    private var soundId = -1
    private var loaded = false
    private var inited = false
    private var soundOn = true
    private var vibrateOn = true

    /** 启动时调用一次（TvBoxApp.onCreate） */
    fun init(context: Context) {
        if (inited) return
        inited = true
        refresh(context)
        loadSound(context)
    }

    private fun loadSound(context: Context) {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            pool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
            pool?.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            soundId = pool?.load(context, R.raw.click, 1) ?: -1
        } catch (e: Exception) {
            // 音效加载失败不影响震动反馈
        }
    }

    /** 开关变更后刷新内存缓存（设置页切换开关时调用） */
    fun refresh(context: Context) {
        val store = ConfigStore(context)
        soundOn = store.tapSound()
        vibrateOn = store.tapVibrate()
    }

    /** 点一下的反馈（任何按钮 onClick 开头调用） */
    fun tap(context: Context) {
        if (vibrateOn) {
            try {
                val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    mgr.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(20)
                }
            } catch (e: Exception) {
                // 个别设备无马达或权限受限
            }
        }
        if (soundOn && loaded && soundId >= 0) {
            try {
                pool?.play(soundId, 0.7f, 0.7f, 0, 0, 1f)
            } catch (e: Exception) {
                // 忽略
            }
        }
    }
}
