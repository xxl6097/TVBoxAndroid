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

    /** 图片文件（用作海报/封面） */
    fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp")
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

        // 排障用：把最近一次 PROPFIND 响应落到应用外部目录，
        // 各家 WebDAV 实现的字段差异很大（极空间对文件也带斜杠和 collection），
        // 出问题时 adb pull 出来看一眼就能定位，比猜快得多。
        runCatching {
            val dir = com.hong.tvbox.TvBoxApp.appContext.getExternalFilesDir(null)
            if (dir != null) {
                java.io.File(dir, "propfind_last.xml").writeText(xml)
            }
        }

        // 自身条目要按**绝对路径**比对：href 是 "/dav/电影/"，而我们手上的 path 是相对路径 "电影"，
        // 拿相对路径去比永远不等，根目录就会把自己（dav）也列出来
        val selfPath = try {
            java.net.URI(url).path ?: ""
        } catch (e: Exception) {
            ""
        }
        return parse(xml, selfPath)
    }

    /** 解析 207 Multi-Status 响应 */
    fun parse(xml: String, selfPath: String): List<WebdavEntry> {
        val out = mutableListOf<WebdavEntry>()
        val current = selfPath.trimEnd('/')

        val blocks = Regex("<[a-zA-Z0-9]*:?response[\\s>][\\s\\S]*?</[a-zA-Z0-9]*:?response>")
            .findAll(xml)
            .toList()

        // 这份响应是否遵循「目录 href 带斜杠」的规范：遵循则用它判断目录，最可靠
        val slashStyleHref = blocks.any { m ->
            tag(m.value, "href").endsWith("/")
        }

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

            // 目录判定优先看 href 是否以 "/" 结尾（RFC 4918 要求 collection 的 href 带斜杠）。
            // 只靠 <collection/> 标签会误判：部分服务端（如极空间）对文件也带这个标签，
            // 结果 mp4 全被当成文件夹、点进去是空目录。
            // 只有整份响应都没有斜杠风格的 href 时，才退回标签判断。
            // 目录判定顺序（真实服务端各不相同，必须按可靠性从高到低）：
            // 1) 有非零 getcontentlength → 一定是文件。极空间对所有 href 都加尾斜杠，
            //    只看斜杠会把 mp4 全判成文件夹，点进去是空目录
            // 2) href 带尾斜杠 → 目录（RFC 4918 规范）
            // 3) 兜底才看 resourcetype 的 collection 标签
            val lenStr = tag(b, "getcontentlength")
            val len = lenStr.toLongOrNull() ?: -1L
            val isDir = when {
                len > 0 -> false
                slashStyleHref -> decoded.endsWith("/")
                else -> b.contains(":collection") || b.contains("<collection")
            }

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
