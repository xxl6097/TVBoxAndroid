package com.hong.tvbox.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** 视频扩展名白名单（对齐鸿蒙 19 个） */
    private val VIDEO_EXT = listOf(
        "mp4", "mkv", "avi", "ts", "m2ts", "mts", "mov", "wmv", "flv",
        "rmvb", "rm", "m4v", "webm", "3gp", "mpg", "mpeg", "vob", "m2t", "f4v"
    )

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && VIDEO_EXT.contains(ext)
    }

    /**
     * 这个条目能不能播。
     *
     * 不能只看扩展名：真实极空间里有 2.2GB 的影片叫「异星战境 Atlas」（没有后缀），
     * 而几个叫 `xxx.mp4` 的其实是目录。服务端给了 Content-Type 就以它为准。
     */
    fun isPlayable(e: WebdavEntry): Boolean {
        if (e.isDir) return false
        return isVideo(e.name) ||
            e.contentType.startsWith("video/") ||
            e.contentType.startsWith("audio/")
    }

    /** 图片文件（用作海报/封面） */
    fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in listOf("jpg", "jpeg", "png", "webp", "bmp")
    }

    // ==================== 影视识别 ====================

    /** 一个文件名解析出的影视元信息 */
    private data class Parsed(
        var title: String = "",
        var season: Int = 0,
        var episode: Int = 0,
        var year: String = "",
        var isSeries: Boolean = false
    )

    /**
     * 解析文件名 → 影视元信息（SxxExx / 第xx集 / Exx / 末尾数字 / 年份 / 纯数字）。
     * 与鸿蒙 Webdav.parse 完全一致。
     */
    fun parseName(name: String): Parsed {
        val p = Parsed()
        var base = name
        val dot = base.lastIndexOf('.')
        if (dot > 0) {
            val ext = base.substring(dot + 1).lowercase()
            if (VIDEO_EXT.contains(ext)) base = base.substring(0, dot)
        }

        // 纯数字文件名（1.mp4 / 01.mp4 / 001.mp4）：集号=数字，片名由父目录名补
        val pure = Regex("^\\s*(\\d{1,4})\\s*$").find(base)
        if (pure != null) {
            p.episode = pure.groupValues[1].toInt()
            p.isSeries = true
            p.title = ""
            return p
        }

        val ym = Regex("((?:19|20)\\d{2})").find(base)
        if (ym != null) {
            p.year = ym.groupValues[1]
            base = base.replace(ym.groupValues[1], " ")
        }

        val sm = Regex("[Ss](\\d{1,2})\\s*[Ee](\\d{1,3})").find(base)
        if (sm != null) {
            p.season = sm.groupValues[1].toInt()
            p.episode = sm.groupValues[2].toInt()
            p.isSeries = true
            base = base.replace(sm.value, " ")
        } else {
            val cm = Regex("第\\s*(\\d{1,3})\\s*[集話话]").find(base)
            if (cm != null) {
                p.episode = cm.groupValues[1].toInt()
                p.isSeries = true
                base = base.replace(cm.value, " ")
            } else {
                val em = Regex("[Ee][Pp]?\\s*(\\d{1,3})\\b").find(base)
                if (em != null) {
                    p.episode = em.groupValues[1].toInt()
                    p.isSeries = true
                    base = base.replace(em.value, " ")
                } else {
                    val tail = Regex("(?:[._\\-\\s])(\\d{1,3})\\s*$").find(base)
                    if (tail != null) {
                        val n = tail.groupValues[1].toInt()
                        if (n in 1..999) {
                            p.episode = n
                            p.isSeries = true
                            base = base.substring(0, tail.range.first)
                        }
                    }
                }
            }
        }

        p.title = cleanTitle(base)
        return p
    }

    /** 清理分辨率/编码标签，分隔符换空格，得到干净片名 */
    fun cleanTitle(s: String): String {
        var t = s
        t = t.replace(Regex("1080[pi]|720[pi]|2160[pi]|480[pi]|576[pi]|4[Kk]|8[Kk]"), " ")
        t = t.replace(
            Regex("x26[45]|h26[45]|hevc|hdr10?|10bit|8bit|aac|dts|dd5\\.1|truehd|atmos|bluray|blu-ray|web-?dl|webrip|remux|proper|repack"),
            " "
        )
        t = t.replace(Regex("[._\\[\\]()（）\\-]+"), " ")
        t = t.replace(Regex("\\s{2,}"), " ").trim()
        return t
    }

    /** 清理目录名里的季/卷序号前缀，如「01 风起天南」→「风起天南」 */
    fun cleanDirName(name: String): String {
        return name.replace(Regex("^\\d{1,3}\\s*[-_ ]?\\s*"), "").trim()
    }

    fun stripExt(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 集名：SxxExx / 第 N 集 / 去扩展名 */
    fun epNameOf(ep: WebdavEp): String = when {
        ep.season > 0 && ep.episode > 0 ->
            "S" + ep.season.toString().padStart(2, '0') + "E" + ep.episode.toString().padStart(2, '0')
        ep.episode > 0 -> "第 ${ep.episode} 集"
        else -> stripExt(ep.name)
    }

    /**
     * 递归扫描目录树，识别影视并聚合（电影/剧集 + 季/集）。
     *
     * maxDepth 限制递归层数；最多扫 200 个目录；每批并发 6 个 PROPFIND。
     * onProgress 回调（已扫目录数, 已发现视频数）。
     */
    suspend fun scan(
        source: WebdavSource,
        maxDepth: Int = 6,
        onProgress: ((dirs: Int, files: Int) -> Unit)? = null
    ): List<WebdavVod> = withContext(Dispatchers.IO) {
        // (相对目录, 条目)
        val files = mutableListOf<Pair<String, WebdavEntry>>()
        val DIR_BUDGET = 200
        val BATCH = 6
        var current = listOf("")
        var depth = 0
        var scanned = 0

        while (current.isNotEmpty() && depth <= maxDepth && scanned < DIR_BUDGET) {
            val next = mutableListOf<String>()
            for (b in current.indices step BATCH) {
                val slice = current.subList(b, minOf(b + BATCH, current.size))
                val results = slice.map { dir ->
                    try {
                        dir to list(source, dir)
                    } catch (e: Exception) {
                        dir to emptyList<WebdavEntry>()
                    }
                }
                results.forEach { (dir, entries) ->
                    entries.forEach { e ->
                        if (e.isDir) {
                            next.add(if (dir.isEmpty()) e.name else "$dir/${e.name}")
                        } else if (isVideo(e.name) || e.contentType.startsWith("video/")) {
                            files.add(dir to e)
                        }
                    }
                }
            }
            scanned += current.size
            onProgress?.invoke(scanned, files.size)
            current = next
            depth++
        }

        // 聚合：剧集按 (目录+片名) 归组；电影/单集每条独立
        val vods = mutableListOf<WebdavVod>()
        val idx = HashMap<String, Int>()
        files.forEachIndexed { i, (dir, f) ->
            val p = parseName(f.name)
            var title = p.title
            if (title.isEmpty() && p.isSeries) {
                title = cleanDirName(dir.substringAfterLast('/', dir))
            }
            val key = if (p.isSeries) "$dir|$title" else "#$i"
            var pos = idx[key]
            if (pos == null) {
                val v = WebdavVod(
                    name = if (title.isNotEmpty()) title else stripExt(f.name),
                    kind = if (p.isSeries) "series" else "movie",
                    year = p.year,
                    dir = dir
                )
                vods.add(v)
                pos = vods.size - 1
                idx[key] = pos
            }
            val vod = vods[pos]
            vod.eps.add(
                WebdavEp(
                    name = f.name,
                    url = absoluteUrl(source, f.href),
                    href = f.href,
                    season = p.season,
                    episode = p.episode
                )
            )
        }

        // 剧集内部按 季 + 集 排序
        vods.forEach { v ->
            if (v.kind == "series") {
                v.eps.sortWith(compareBy({ it.season }, { it.episode }))
            }
        }
        vods
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

    /**
     * 从候选地址里挑一个真能返回媒体流的。
     *
     * 各家 WebDAV 对路径写法要求不一（有的文件要尾斜杠、有的不要），
     * 与其猜，不如花一次 1KB 请求试出来；都不行就退回第一个交给播放器报错。
     */
    suspend fun pickPlayable(
        source: WebdavSource,
        candidates: List<String>
    ): String = withContext(Dispatchers.IO) {
        val headers = mutableMapOf<String, String>()
        authHeader(source)?.let { headers["Authorization"] = it }

        val seen = mutableSetOf<String>()
        for (u in candidates) {
            if (u.isEmpty() || !seen.add(u)) continue
            if (HttpClient.looksPlayable(u, headers)) return@withContext u
        }
        candidates.firstOrNull() ?: ""
    }

    /**
     * 用服务端返回的 href 拼绝对地址。
     *
     * 为什么不自己拼「目录 + 文件名」：真实极空间的 href 不走规范 ——
     * 文件路径带尾斜杠（`/x/电影/xxx.mp4/`），个别目录反而不带。
     * 客户端按「目录/名字」拼出来的地址它会认不出来，直接返回首页 HTML（状态码还是 200），
     * 播放器就报 Source error。href 才是服务端认可的那份路径，照抄最稳。
     */
    fun absoluteUrl(source: WebdavSource, href: String): String {
        val path = if (href.startsWith("/")) href else "/$href"
        val authority = try {
            val u = java.net.URI(source.url)
            if (u.port > 0) "${u.scheme}://${u.host}:${u.port}"
            else "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            // URL 解析不出来就退回按源地址拼（保持旧行为）
            return urlFor(source, href.trimStart('/'))
        }
        return encodeUrl(authority + path)
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
        return encodeUrl(full.ifEmpty { base })
    }

    /**
     * 按 RFC 3986 编码 URL 的路径段（结构字符与非 ASCII 分开处理）。
     *
     * 为什么必须自己编：OkHttp 会自动把中文转义，所以列目录一直正常；
     * 但播放走的是 media3 的 DefaultHttpDataSource（底层 HttpURLConnection），
     * 它不会自动转义 —— 真实极空间上表现为「拿到非视频内容 → Source error」。
     */
    fun encodeUrl(url: String): String {
        val scheme = url.indexOf("://")
        if (scheme < 0) return url
        val pathStart = url.indexOf('/', scheme + 3)
        if (pathStart < 0) return url

        val head = url.substring(0, pathStart)
        var rest = url.substring(pathStart)
        var query = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = rest.substring(q)
            rest = rest.substring(0, q)
        }

        val encoded = rest.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) seg else encodeSegment(seg)
        }
        return head + encoded + query
    }

    /** 保留 URL 里合法的未保留字符；%XX 视为已编码不再二次转义 */
    private fun encodeSegment(seg: String): String {
        val sb = StringBuilder()
        val bytes = seg.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < bytes.size) {
            val c = bytes[i].toInt() and 0xFF
            val ch = c.toChar()
            val keep = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~' ||
                ch == '!' || ch == '$' || ch == '&' || ch == '\'' ||
                ch == '(' || ch == ')' || ch == '*' || ch == '+' ||
                ch == ',' || ch == ';' || ch == '=' || ch == ':' || ch == '@'
            if (keep) {
                sb.append(ch)
                i++
            } else if (ch == '%' && i + 2 < bytes.size &&
                isHex(bytes[i + 1].toInt().toChar()) && isHex(bytes[i + 2].toInt().toChar())
            ) {
                // 已经是 %XX，原样保留，避免双重编码
                sb.append('%').append(bytes[i + 1].toInt().toChar()).append(bytes[i + 2].toInt().toChar())
                i += 3
            } else {
                sb.append('%').append(String.format("%02X", c))
                i++
            }
        }
        return sb.toString()
    }

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * 解 href 里的百分号转义。
     *
     * 不能用 URLDecoder：它会把 '+' 变成空格，文件名里带加号就会取错路径。
     */
    fun decodeHref(raw: String): String {
        if (!raw.contains('%')) return raw
        val out = java.io.ByteArrayOutputStream()
        val bytes = raw.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < bytes.size) {
            val ch = bytes[i].toInt().toChar()
            if (ch == '%' && i + 2 < bytes.size &&
                isHex(bytes[i + 1].toInt().toChar()) && isHex(bytes[i + 2].toInt().toChar())
            ) {
                val hex = "${bytes[i + 1].toInt().toChar()}${bytes[i + 2].toInt().toChar()}"
                out.write(hex.toInt(16))
                i += 3
            } else {
                out.write(bytes[i].toInt())
                i++
            }
        }
        return try {
            out.toByteArray().toString(Charsets.UTF_8)
        } catch (e: Exception) {
            raw
        }
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
        // 拿相对路径去比永远不等，根目录就会把自己（dav）也列出来。
        // 注意 urlFor 出来后是百分号编码的，要先解码才能和 href（已解码）对比。
        val selfPath = try {
            decodeHref(java.net.URI(url).rawPath ?: java.net.URI(url).path ?: "")
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

        blocks.forEach { m ->
            val b = m.value

            val href = tag(b, "href")
            if (href.isEmpty()) return@forEach

            // 解 href 的百分号转义。不能用 URLDecoder（会把 '+' 变空格）
            val decoded = decodeHref(href)

            // 过滤自身条目（服务端会把当前目录自己也返回一条）
            val normalized = decoded.trimEnd('/')
            if (normalized == current || normalized.isEmpty()) return@forEach

            val name = normalized.substringAfterLast('/')
            if (name.isEmpty()) return@forEach

            // 目录判定（以服务端元数据为准，扩展名只作兜底）：
            //
            // 真实极空间的响应（2026-11 抓包）给了个反直觉的事实：
            //   带 .mp4 后缀的那些条目其实是**目录**（collection、无长度、href 带斜杠），
            //   真正能播的文件反而没有扩展名（如「异星战境 Atlas」，2.2GB、video/mp4）。
            // 所以不能用扩展名判断「是不是文件」，否则会把目录当影片播、把真文件过滤掉。
            val len = tag(b, "getcontentlength").toLongOrNull() ?: -1L
            val ct = tag(b, "getcontenttype").lowercase()
            val isCollection = b.contains(":collection") || b.contains("<collection")
            val isDir = when {
                // 明确给了音视频/图片类型 → 文件
                ct.startsWith("video/") || ct.startsWith("audio/") || ct.startsWith("image/") -> false
                // 有长度 → 文件
                len > 0 -> false
                // href 带斜杠或标了 collection → 目录
                decoded.endsWith("/") -> true
                isCollection -> true
                else -> false
            }

            val size = tag(b, "getcontentlength").toLongOrNull() ?: 0L
            val mtime = tag(b, "getlastmodified")

            out.add(
                WebdavEntry(
                    href = decoded,
                    name = name,
                    isDir = isDir,
                    size = size,
                    mtime = mtime,
                    contentType = ct
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
