package com.hong.tvbox.core

import android.util.Base64
import java.net.URLDecoder

/**
 * WebDAV 客户端 - 从鸿蒙 Webdav.ets 移植
 *
 * 只用到最小一组能力：PROPFIND 列目录 + 生成带 Basic 认证的播放地址。
 * 极空间/群晖/AList 的 WebDAV 都吃这套。
 *
 * 解析要点：不同服务端用的命名空间前缀不一样（D: / d: / lp1: / 无前缀），
 * 所以正则不能写死前缀，只匹配标签名本身。
 */
object WebdavClient {

    /** 视频扩展名白名单，和鸿蒙版保持一致 */
    private val VIDEO_EXT = listOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "ts", "m2ts",
        "webm", "rmvb", "rm", "mpg", "mpeg", "m4v", "iso"
    )

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && VIDEO_EXT.contains(ext)
    }

    /** Basic 认证头；没有账号密码时返回 null */
    fun authHeader(source: WebdavSource): String? {
        if (source.user.isEmpty() && source.pass.isEmpty()) return null
        val raw = "${source.user}:${source.pass}"
        val encoded = Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    /** 规范化的挂载根路径，保证以 / 开头且不以 / 结尾 */
    fun basePath(source: WebdavSource): String {
        var root = source.root.trim()
        if (root.isEmpty() || root == "/") return ""
        if (!root.startsWith("/")) root = "/$root"
        return root.trimEnd('/')
    }

    /** 拼出某个路径的完整 URL（path 为空表示挂载根） */
    fun urlFor(source: WebdavSource, path: String): String {
        val base = source.url.trimEnd('/')
        val root = basePath(source)
        val tail = path.trim()
        val full = when {
            tail.isEmpty() -> base + root
            tail.startsWith("/") -> base + root + tail
            else -> base + root + "/" + tail
        }
        return full.ifEmpty { base }
    }

    /**
     * 列目录（Depth: 1）。
     * 返回的条目已过滤掉自身、并按「目录在前、名称排序」整理。
     */
    suspend fun list(source: WebdavSource, path: String): List<WebdavEntry> {
        val url = urlFor(source, path).let { if (it.endsWith("/")) it else "$it/" }

        val headers = mutableMapOf<String, String>()
        authHeader(source)?.let { headers["Authorization"] = it }

        val xml = HttpClient.propfind(url, headers, 15000)
        return parse(xml, path)
    }

    /** 解析 207 Multi-Status 响应 */
    fun parse(xml: String, currentPath: String): List<WebdavEntry> {
        val out = mutableListOf<WebdavEntry>()
        val current = currentPath.trimEnd('/')

        val blocks = Regex("<[a-zA-Z0-9]*:?response[\\s>][\\s\\S]*?</[a-zA-Z0-9]*:?response>")
            .findAll(xml)

        blocks.forEach { m ->
            val b = m.value

            val href = tag(b, "href")
            if (href.isEmpty()) return@forEach

            val decoded = try {
                URLDecoder.decode(href, "UTF-8")
            } catch (e: Exception) {
                href
            }

            // 过滤自身条目（服务端会把当前目录自己也返回一条）
            val normalized = decoded.trimEnd('/')
            if (normalized == current || normalized.isEmpty()) return@forEach

            val isDir = b.contains(":collection") || b.contains("<collection")
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isEmpty()) return@forEach

            val size = tag(b, "getcontentlength").toLongOrNull() ?: 0L
            val mtime = tag(b, "getlastmodified")

            out.add(
                WebdavEntry(
                    href = decoded,
                    name = name,
                    isDir = isDir,
                    size = size,
                    mtime = mtime
                )
            )
        }

        return out
            .sortedWith(compareByDescending<WebdavEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    /** 取任意命名空间前缀下的标签文本 */
    private fun tag(block: String, name: String): String {
        val re = Regex("<[a-zA-Z0-9]*:?$name[^>]*>([\\s\\S]*?)</[a-zA-Z0-9]*:?$name>")
        return re.find(block)?.groupValues?.get(1)?.trim() ?: ""
    }

    /** 人类可读的大小 */
    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format("%.1f GB", gb)
            mb >= 1 -> String.format("%.0f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }
}
