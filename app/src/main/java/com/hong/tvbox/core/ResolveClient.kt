package com.hong.tvbox.core

/**
 * 播放地址解析 - 完整移植自 Resolve.ets
 *
 * 采集源给出的地址通常是直链（m3u8/mp4），少数是视频站网页地址，
 * 需要走「解析接口」把网页地址换成真实播放地址。
 * 实现 TVBox parses 里 type=1 的那类：
 *   GET {rule.url}{encodeURIComponent(playUrl)} → {"url":"http://...m3u8"}
 */
data class Resolved(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val via: String,
    val ok: Boolean
)

object ResolveClient {

    /**
     * 尝试把网页地址补成 m3u8 直链。
     * 很多采集站第一组线路给网页地址，真流是同路径 + /index.m3u8
     */
    private fun fixPlayable(url: String): String {
        if (url.isEmpty() || CmsClient.isDirect(url)) return url
        if (!url.contains("://")) return url
        val noQuery = url.split("?")[0].trimEnd('/')
        val last = noQuery.substringAfterLast('/')
        return if (last.isNotEmpty() && !last.contains('.')) {
            "$noQuery/index.m3u8"
        } else {
            url
        }
    }

    /**
     * 解析播放地址。
     * 优先级：直链 → 补全 m3u8 → 轮询解析接口（最多 8 个）
     */
    suspend fun resolve(raw: String, parses: List<Parse>): Resolved {
        if (raw.isEmpty()) {
            return Resolved(raw, via = "空地址", ok = false)
        }

        // 直链：直接返回
        if (CmsClient.isDirect(raw)) {
            return Resolved(raw, via = "直链", ok = true)
        }

        // 尝试补全 m3u8
        val guess = fixPlayable(raw)
        if (guess != raw) {
            try {
                val head = HttpClient.peek(guess, 5000)
                if (head.contains("#EXTM3U")) {
                    return Resolved(guess, via = "补全 m3u8", ok = true)
                }
            } catch (e: Exception) {
                // 猜错了继续走解析接口
            }
        }

        // 轮询解析接口（最多 8 个）
        val limit = minOf(parses.size, 8)
        for (i in 0 until limit) {
            val rule = parses[i]
            // 先原样再编码，解析接口对参数编码接受度不一致
            val candidates = listOf(
                rule.url + raw,
                rule.url + java.net.URLEncoder.encode(raw, "UTF-8")
            )
            for (candidate in candidates) {
                try {
                    val txt = HttpClient.text(candidate, timeoutMs = 6000)
                    val json = parseResolveResp(txt)
                    val url = json["url"]?.toString()?.trim() ?: ""
                    if (url.startsWith("http")) {
                        @Suppress("UNCHECKED_CAST")
                        val headers = json["header"] as? Map<String, String> ?: emptyMap()
                        val finalUrl = fixPlayable(url)
                        return Resolved(
                            url = finalUrl,
                            headers = headers,
                            via = rule.name,
                            ok = true
                        )
                    }
                } catch (e: Exception) {
                    // 换下一个解析器
                }
            }
        }

        return Resolved(raw, via = "未解析（可能需要专用解析器）", ok = false)
    }

    private fun parseResolveResp(txt: String): Map<String, Any> {
        val cleaned = CmsClient.cleanJsonStatic(txt)
        return try {
            val gson = com.google.gson.Gson()
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(cleaned, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
