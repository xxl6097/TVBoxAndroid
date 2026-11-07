package com.hong.tvbox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直播源解析 + 频道目录 - 从鸿蒙 Live.ets 移植
 *
 * 支持两种最常见格式：
 *  - M3U: #EXTINF:-1 tvg-logo="..." group-title="央视",CCTV-1 + 下一行地址
 *  - TXT: 「央视,#genre#」做分组行，之后每行「频道名,地址」
 *
 * 分组顺序保持文件里的原始顺序（源作者通常把央视/卫视排前面），
 * 不做任何二次归类：源写什么就显示什么，认不出来的归「未分组」。
 */
object LiveClient {

    /**
     * 单条流探活。
     *
     * 不交给播放器判定的原因：ExoPlayer 对死链也要十几秒才报错，
     * 用户盯着黑屏等很久才知道这台挂了。这里只抓首包，几秒内给结论。
     * 返回 true = 能连上且吐的像是流，不代表一定能解码出画面。
     */
    suspend fun probe(url: String, timeoutMs: Int = 2500): Boolean {
        if (url.isEmpty()) return false
        // 本地文件（内嵌 m3u 落盘）：文件在就视为可用
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return File(url).exists()
        }
        return try {
            val head = HttpClient.peek(url, timeoutMs.toLong())
            if (head.isEmpty()) return false
            if (head == "#BIN" || head.contains("#EXTM3U") || head.contains("#EXTINF")) return true

            val t = head.trim().lowercase()
            // 源站失效最常见的手法：302 到一张错误页
            if (t.startsWith("<!doctype") || t.startsWith("<html") || t.contains("<body")) return false
            if (t.contains("error_account") || t.contains("pirated")) return false

            // 剩下是 TS 分片之类的二进制/纯文本，交给播放器判定
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 拉取并解析。失败抛异常，由调用方展示文案 */
    suspend fun load(url: String, timeoutMs: Long = 25000): List<ChannelGroup> {
        val txt = readSource(url, timeoutMs)
        if (txt.trim().isEmpty()) throw Exception("直播源返回空内容")

        val groups = parse(txt)
        if (groups.isEmpty()) throw Exception("没解析出频道，检查源格式是否为 m3u/txt")
        return groups
    }

    /**
     * 读取直播源内容：http/https 走网络，其余视为本地文件路径直接读。
     * 内嵌 data 格式的订阅直播源在导入时落盘成本地 m3u，这里直接读文件。
     */
    private suspend fun readSource(url: String, timeoutMs: Long): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            HttpClient.text(url, timeoutMs = timeoutMs)
        } else {
            withContext(Dispatchers.IO) {
                val f = File(url)
                if (f.exists()) f.readText() else throw Exception("本地直播源文件不存在")
            }
        }
    }

    fun parse(text: String): List<ChannelGroup> {
        val t = text.replace("\r\n", "\n").replace("\r", "\n")
        return if (t.contains("#EXTINF")) parseM3u(t) else parseTxt(t)
    }

    // ---------- 目录 ----------

    /**
     * 按源里原始分组名建目录，返回根节点（根不显示，只用它的 children）。
     * 「地方台/北京」会拆成两级，其余原样保留。
     */
    fun buildTree(groups: List<ChannelGroup>): LiveDir {
        val root = LiveDir(name = "全部")

        groups.forEach { g ->
            if (g.channels.isEmpty()) return@forEach
            val parts = splitName(g.name)
            if (parts.size >= 2) {
                val p = child(root, parts[0])
                val sub = child(p, tail(parts, 1))
                sub.channels.addAll(g.channels)
            } else {
                val d = child(root, g.name)
                d.channels.addAll(g.channels)
            }
        }

        countOf(root)
        return root
    }

    /** 取节点下全部频道（含所有子目录），按树序展开 */
    fun collect(node: LiveDir, out: MutableList<Channel>) {
        out.addAll(node.channels)
        node.children.forEach { collect(it, out) }
    }

    private fun countOf(node: LiveDir): Int {
        var c = node.channels.size
        node.children.forEach { c += countOf(it) }
        node.count = c
        return c
    }

    private fun child(parent: LiveDir, name: String): LiveDir {
        parent.children.firstOrNull { it.name == name }?.let { return it }
        val d = LiveDir(name = name)
        parent.children.add(d)
        return d
    }

    /** 部分源用 / | \ > 写层级，拆出来直接用，比猜关键词准 */
    private fun splitName(n: String): List<String> {
        val out = mutableListOf<String>()
        val seps = charArrayOf('/', '\\', '|', '｜', '>', '＞')
        var cur = StringBuilder()
        n.forEach { c ->
            if (c in seps) {
                if (cur.toString().trim().isNotEmpty()) out.add(cur.toString().trim())
                cur = StringBuilder()
            } else {
                cur.append(c)
            }
        }
        if (cur.toString().trim().isNotEmpty()) out.add(cur.toString().trim())
        return if (out.isEmpty()) listOf(n) else out
    }

    private fun tail(parts: List<String>, from: Int): String {
        val sb = StringBuilder()
        for (i in from until parts.size) {
            if (sb.isNotEmpty()) sb.append('/')
            sb.append(parts[i])
        }
        return sb.toString()
    }

    // ---------- M3U ----------

    private fun parseM3u(t: String): List<ChannelGroup> {
        val groups = mutableListOf<ChannelGroup>()
        var curName = ""
        var curLogo = ""
        var curGroup = ""

        t.split("\n").forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            if (line.startsWith("#EXTINF")) {
                curGroup = attr(line, "group-title").ifEmpty { "未分组" }
                curLogo = attr(line, "tvg-logo")
                val c = line.lastIndexOf(',')
                curName = if (c >= 0) line.substring(c + 1).trim() else ""
                if (curName.isEmpty()) curName = attr(line, "tvg-name")
                return@forEach
            }

            if (line.startsWith("#")) {
                if (line.startsWith("#EXTGRP:")) {
                    val g = line.substring(8).trim()
                    if (g.isNotEmpty()) curGroup = g
                }
                return@forEach
            }

            if (curName.isEmpty()) return@forEach
            push(groups, curGroup, curName, line, curLogo)
            curName = ""
            curLogo = ""
        }
        return groups
    }

    private fun attr(line: String, key: String): String {
        val i = line.indexOf("$key=\"")
        if (i < 0) return ""
        val s = i + key.length + 2
        val e = line.indexOf('"', s)
        return if (e < 0) "" else line.substring(s, e)
    }

    // ---------- TXT ----------

    private fun parseTxt(t: String): List<ChannelGroup> {
        val groups = mutableListOf<ChannelGroup>()
        var curGroup = "未分组"

        t.split("\n").forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            if (line.contains(",#genre#")) {
                val g = line.substring(0, line.indexOf(",#genre#")).trim()
                if (g.isNotEmpty()) curGroup = g
                return@forEach
            }

            val c = line.indexOf(',')
            if (c <= 0) return@forEach

            val name = line.substring(0, c).trim()
            val url = line.substring(c + 1).trim()
            if (name.isEmpty() || !isUrl(url)) return@forEach

            push(groups, curGroup, name, url, "")
        }
        return groups
    }

    // ---------- 公共 ----------

    /**
     * 只收 http/https。
     * 源里混着 rtmp/rtsp/udp，播放器播不了，留在列表里点一次失败一次，不如滤掉。
     */
    private fun isUrl(u: String): Boolean {
        return u.startsWith("http://") || u.startsWith("https://")
    }

    private fun push(
        groups: MutableList<ChannelGroup>,
        groupName: String,
        name: String,
        url: String,
        logo: String
    ) {
        val g = groups.firstOrNull { it.name == groupName } ?: ChannelGroup(name = groupName).also {
            groups.add(it)
        }

        // 同名同组视为同一台的多条线路：合并成一条，地址按顺序备着
        g.channels.firstOrNull { it.name == name }?.let { exist ->
            if (!exist.urls.contains(url)) exist.urls.add(url)
            return
        }

        g.channels.add(
            Channel(
                name = name,
                url = url,
                logo = logo,
                group = groupName,
                urls = mutableListOf(url)
            )
        )
    }
}
