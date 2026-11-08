package com.hong.tvbox.core

import android.media.AudioManager
import android.media.audiofx.Equalizer

/**
 * 音效均衡器管理
 *
 * 问题：有播放速度但缺音效调节、均衡器
 * 解决：提供 EQ 预设（流行/摇滚/古典/金属）与自定义调节
 */
class AudioEffectManager(private val audioSessionId: Int) {

    private var equalizer: Equalizer? = null

    /**
     * EQ 预设定义
     *
     * 5 段均衡器：低音 / 中低 / 中 / 中高 / 高音
     * 单位：毫分贝 (1/100 分贝)
     */
    private val eqPresets = mapOf(
        "流行" to intArrayOf(500, 300, 0, -300, -500),      // 低高音加强
        "摇滚" to intArrayOf(800, 300, -500, 300, 800),     // 极端低高音
        "古典" to intArrayOf(0, -200, 0, 200, 500),         // 高音温和
        "金属" to intArrayOf(1000, 700, -800, 500, 1000),   // 极端加强
        "重低音" to intArrayOf(1200, 800, 0, -400, -600),   // 强调低音
        "高音增强" to intArrayOf(-400, -200, 0, 300, 800),  // 强调高音
        "中频突出" to intArrayOf(-300, 200, 400, 200, -300) // 人声突出
    )

    init {
        try {
            equalizer = Equalizer(0, audioSessionId)
            equalizer?.enabled = true
        } catch (e: Exception) {
            // 设备不支持 EQ
        }
    }

    /**
     * 应用 EQ 预设
     *
     * @param presetName 预设名称
     */
    fun applyPreset(presetName: String) {
        val gains = eqPresets[presetName] ?: return
        applyGains(gains)
    }

    /**
     * 应用自定义均衡器参数
     *
     * @param gains 5 段均衡器增益值（毫分贝）
     */
    fun applyGains(gains: IntArray) {
        if (gains.size != 5 || equalizer == null) return

        // 获取频段范围（通常固定为 5 段）
        val bandCount = equalizer!!.numberOfBands
        if (bandCount < 5) return

        gains.forEachIndexed { idx, gain ->
            try {
                equalizer!!.setBandLevel(idx.toShort(), gain.toShort())
            } catch (e: Exception) {
                // 忽略设置失败
            }
        }
    }

    /**
     * 重置均衡器（正常音质）
     */
    fun reset() {
        applyGains(intArrayOf(0, 0, 0, 0, 0))
    }

    /**
     * 获取所有可用预设
     */
    fun getAvailablePresets(): List<String> = eqPresets.keys.toList()

    /**
     * 设置均衡器启用/禁用
     */
    fun setEnabled(enabled: Boolean) {
        equalizer?.enabled = enabled
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            equalizer?.release()
        } catch (e: Exception) {
            // 忽略释放错误
        }
    }
}

/**
 * 播放速度调节辅助
 *
 * 在 PlayerActivity 中使用：
 *   player.setPlaybackParameters(PlaybackParameters(speed))
 */
data class PlaybackSpeedProfile(
    val name: String,
    val value: Float
) {
    companion object {
        val SPEEDS = listOf(
            PlaybackSpeedProfile("0.5x", 0.5f),
            PlaybackSpeedProfile("0.75x", 0.75f),
            PlaybackSpeedProfile("1.0x", 1.0f),
            PlaybackSpeedProfile("1.25x", 1.25f),
            PlaybackSpeedProfile("1.5x", 1.5f),
            PlaybackSpeedProfile("2.0x", 2.0f)
        )

        fun findByValue(value: Float): PlaybackSpeedProfile? =
            SPEEDS.firstOrNull { it.value == value }

        fun findByName(name: String): PlaybackSpeedProfile? =
            SPEEDS.firstOrNull { it.name == name }
    }
}
