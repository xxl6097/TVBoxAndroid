package com.hong.tvbox.core

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

/**
 * 播放器字幕管理
 *
 * 问题：ExoPlayer 未启用字幕解析，m3u8/mkv 内嵌字幕无法显示
 * 解决：
 *   - 自动探测常见编码（UTF-8/GBK/ISO-8859-1）
 *   - 支持 SRT/VTT/WebVTT/ASS 字幕格式
 *   - 构建 MediaItem.SubtitleConfiguration
 */
object SubtitleManager {

    /**
     * 常见字幕编码
     */
    private val SUBTITLE_ENCODINGS = listOf("UTF-8", "GBK", "ISO-8859-1", "Big5")

    /**
     * 常见字幕文件扩展名与 MIME 类型映射
     */
    private val SUBTITLE_FORMATS = mapOf(
        "srt" to MimeTypes.APPLICATION_SUBRIP,
        "vtt" to MimeTypes.TEXT_VTT,
        "webvtt" to MimeTypes.TEXT_VTT,
        "ass" to MimeTypes.TEXT_SSA,
        "ssa" to MimeTypes.TEXT_SSA
    )

    /**
     * 从 URL 推断字幕格式
     *
     * @param url 字幕 URL
     * @return MIME 类型，或 null 如果无法识别
     */
    fun detectMimeType(url: String): String? {
        val ext = url.substringAfterLast('.').lowercase()
        return SUBTITLE_FORMATS[ext]
    }

    /**
     * 构建字幕配置
     *
     * @param subtitleUrl 字幕 URL
     * @param language 语言标签（如 "zh", "en"）
     * @param label 显示标签（如 "简体中文"）
     * @return SubtitleConfiguration，或 null 如果无法识别格式
     */
    fun buildSubtitleConfiguration(
        subtitleUrl: String,
        language: String = "zh",
        label: String = "字幕"
    ): MediaItem.SubtitleConfiguration? {
        val mimeType = detectMimeType(subtitleUrl) ?: return null

        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
            .setMimeType(mimeType)
            .setLanguage(language)
            .setLabel(label)
            .setSelectionFlags(MediaItem.SubtitleConfiguration.ROLE_FLAG_MAIN)
            .build()
    }

    /**
     * 自动探测并提取字幕配置（从多语言字幕列表）
     *
     * @param subtitleUrls 字幕 URL 列表
     * @return SubtitleConfiguration 列表，过滤掉无法识别的格式
     */
    fun buildSubtitleConfigurations(subtitleUrls: List<Pair<String, String>>): List<MediaItem.SubtitleConfiguration> {
        return subtitleUrls.mapNotNull { (url, lang) ->
            val mimeType = detectMimeType(url) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mimeType)
                .setLanguage(lang)
                .build()
        }
    }

    /**
     * 获取支持的字幕编码列表
     */
    fun getSupportedEncodings(): List<String> = SUBTITLE_ENCODINGS
}
