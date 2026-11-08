package com.hong.tvbox.core

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 广告/推广链接管理
 *
 * 问题：采集源返回推广链接在 vod_remarks，无法点击
 * 解决：检测并提示用户打开外部链接
 */
object AdManager {

    private val URL_REGEX = Regex("""https?://[^\s]+|scheme://[^\s]+""")
    private val SCHEME_REGEX = Regex("""(https?|custom):\/\/""")

    /**
     * 从文本中提取所有链接
     *
     * @param text 文本内容
     * @return 链接列表
     */
    fun extractLinks(text: String): List<String> {
        return URL_REGEX.findAll(text)
            .map { it.value }
            .distinct()
            .toList()
    }

    /**
     * 判断是否为外部推广链接
     *
     * @param url URL 字符串
     * @return 是否为外部链接（而非本应用内链接）
     */
    fun isExternalLink(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://") ||
                (url.contains("://") && !url.startsWith("tvbox://"))
    }

    /**
     * 打开外部链接
     *
     * @param context Android Context
     * @param url 链接地址
     */
    fun openLink(context: Context, url: String) {
        try {
            val intent = when {
                url.startsWith("http://") || url.startsWith("https://") -> {
                    // HTTP/HTTPS 链接：用浏览器打开
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                }
                url.contains("://") -> {
                    // 其他 scheme：尝试用对应应用打开
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                }
                else -> return
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            // 无法打开链接，安静失败
        }
    }

    /**
     * 格式化链接显示文本
     *
     * @param url 链接
     * @return 截短后的显示文本
     */
    fun formatLinkDisplay(url: String, maxLen: Int = 30): String {
        return if (url.length > maxLen) {
            url.substring(0, maxLen - 3) + "..."
        } else {
            url
        }
    }

    /**
     * 检测是否为已知的恶意/钓鱼链接
     *
     * @param url 链接
     * @return 是否可疑
     */
    fun isSuspiciousLink(url: String): Boolean {
        val suspicious = listOf(
            "phishing",
            "malware",
            "fake",
            "scam",
            "virus"
        )

        return suspicious.any { url.lowercase().contains(it) }
    }
}
