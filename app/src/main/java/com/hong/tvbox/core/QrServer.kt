package com.hong.tvbox.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 极简 HTTP 服务 — 手机扫码录入订阅配置
 *
 * 支持的 mode：
 *   config     — 三框合一（订阅 + 直播源 + 点播配置 JSON）
 *   subscribe  — 仅订阅链接
 *   live       — 仅直播源地址
 *   vod_config — 仅点播配置（URL 或 JSON）
 *   sub_live   — 仅订阅直播源地址（追加到候选列表）
 *   play       — 单框直连播放
 *
 * 只监听局域网；随对话框关闭而停止。
 */
class QrServer(
    private val port: Int = 8788,
    private val mode: String = "config",
    private val onSubmit: (Map<String, String>) -> Unit
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    // ── 网络 ───────────────────────────────────────────────────────────────

    fun localIp(): String {
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172."))
                            return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun url(): String = "http://${localIp()}:$port"

    // ── 生命周期 ───────────────────────────────────────────────────────────

    fun start(): Boolean {
        if (running) return true
        return try {
            serverSocket = ServerSocket(port).apply { reuseAddress = true }
            running = true
            thread = Thread {
                while (running) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        handle(client)
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }.apply { isDaemon = true; name = "qr-server"; start() }
            true
        } catch (_: Exception) {
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        thread?.interrupt()
        thread = null
    }

    // ── 请求处理 ───────────────────────────────────────────────────────────

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            val requestLine = reader.readLine() ?: return
            val method = requestLine.split(" ").getOrElse(0) { "GET" }
            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:"))
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val out: OutputStream = socket.getOutputStream()
            if (method == "POST") {
                val body = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(body, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                val form = parseForm(String(body, 0, read))
                onSubmit(form)
                respond(out, successPage(form))
            } else {
                respond(out, formPage())
            }
        }
    }

    private fun parseForm(body: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        body.split("&").forEach { pair ->
            if (pair.isEmpty()) return@forEach
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val k = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
            val v = URLDecoder.decode(pair.substring(idx + 1), "UTF-8").trim()
            if (v.isNotEmpty()) map[k] = v
        }
        return map
    }

    private fun respond(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ── 页面生成 ───────────────────────────────────────────────────────────

    private val CSS = """
<style>
  *{box-sizing:border-box}
  body{font-family:-apple-system,system-ui,sans-serif;background:#141218;color:#fff;
    margin:0;padding:20px 18px 32px}
  h1{font-size:20px;margin:0 0 4px;display:flex;align-items:center;gap:8px}
  h1 span.icon{font-size:26px}
  p.tip{color:#b3ffffff;font-size:13px;margin:4px 0 22px;line-height:1.6}
  label{display:block;font-size:13px;color:#ffeb3b;margin:0 0 6px;font-weight:600}
  input,textarea{width:100%;background:#1c1b1f;border:1px solid #3a3a3f;
    border-radius:10px;color:#fff;padding:13px 14px;font-size:15px;outline:none;
    -webkit-appearance:none}
  input:focus,textarea:focus{border-color:#7b7bf0}
  textarea{min-height:120px;resize:vertical;line-height:1.5}
  .field{margin-bottom:18px}
  button{margin-top:4px;width:100%;padding:15px;border:0;border-radius:14px;
    background:#fff;color:#141218;font-size:16px;font-weight:700;
    -webkit-tap-highlight-color:transparent}
  button:active{opacity:0.85}
  .hint{color:#60606e;font-size:12px;margin-top:18px;line-height:1.7;text-align:center}
  .ok{text-align:center;padding-top:16px}
  .ok h1{justify-content:center;margin-bottom:12px}
  .ok p{color:#b3ffffff;font-size:14px;margin:4px 0}
  .ok a{color:#ffeb3b;text-decoration:none}
</style>""".trimIndent()

    private fun formPage(): String = when (mode) {
        "subscribe" -> singleFieldPage(
            icon = "🔗", title = "录入订阅链接",
            tip = "将 TVBox/豆瓣订阅链接粘贴到下方，点「保存到电视」，点播和直播同时更新。",
            fieldName = "subscribe", fieldLabel = "订阅链接",
            placeholder = "https://raw.githubusercontent.com/.../config.json",
            inputType = "url", multiline = false
        )
        "live" -> singleFieldPage(
            icon = "📺", title = "录入直播源地址",
            tip = "粘贴 m3u 或 txt 直播源链接，电视端立即切换。",
            fieldName = "live", fieldLabel = "直播源地址（m3u / txt）",
            placeholder = "https://.../iptv.m3u",
            inputType = "url", multiline = false
        )
        "vod_config" -> singleFieldPage(
            icon = "🎬", title = "录入点播配置",
            tip = "粘贴配置 JSON 或远程 URL，填入后自动导入站源。",
            fieldName = "config", fieldLabel = "配置 JSON 或 URL",
            placeholder = "{\"sites\":[...]}  或  https://.../config.json",
            inputType = "text", multiline = true
        )
        "sub_live" -> singleFieldPage(
            icon = "➕", title = "添加订阅直播源",
            tip = "粘贴直播订阅地址（m3u / txt），追加到候选列表，之后可随时切换。",
            fieldName = "sub_live", fieldLabel = "直播订阅地址",
            placeholder = "https://.../channel_list.m3u",
            inputType = "url", multiline = false
        )
        "play" -> playPage()
        else -> allFieldsPage()  // mode == "config"
    }

    // 单字段表单
    private fun singleFieldPage(
        icon: String, title: String, tip: String,
        fieldName: String, fieldLabel: String,
        placeholder: String, inputType: String, multiline: Boolean
    ): String {
        val inputHtml = if (multiline)
            """<textarea name="$fieldName" placeholder="$placeholder" autocomplete="off"></textarea>"""
        else
            """<input type="$inputType" name="$fieldName" placeholder="$placeholder" autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">"""
        return """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>夜帧影视 · $title</title>
$CSS
</head><body>
  <h1><span class="icon">$icon</span>$title</h1>
  <p class="tip">$tip</p>
  <form method="post" action="/">
    <div class="field">
      <label>$fieldLabel</label>
      $inputHtml
    </div>
    <button type="submit">保存到电视 →</button>
  </form>
  <p class="hint">数据只写入本机，不上传任何服务器。<br>关闭电视端二维码弹窗后本页失效。</p>
</body></html>"""
    }

    // 三框合一页面（mode=config）
    private fun allFieldsPage(): String = """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>夜帧影视 · 扫码录入</title>
$CSS
</head><body>
  <h1><span class="icon">📡</span>录入到电视</h1>
  <p class="tip">三项可选填其中一项或多项，点「保存到电视」立即生效。</p>
  <form method="post" action="/">
    <div class="field">
      <label>🔗 订阅链接（点播 + 直播一起更新）</label>
      <input type="url" name="subscribe" placeholder="https://.../config.json"
        autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">
    </div>
    <div class="field">
      <label>📺 直播源（m3u / txt，单独填也行）</label>
      <input type="url" name="live" placeholder="https://.../iptv.m3u"
        autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">
    </div>
    <div class="field">
      <label>🎬 点播配置 JSON 或 URL（可选）</label>
      <textarea name="config" placeholder='{"sites":[...]}  或  https://.../config.json'></textarea>
    </div>
    <button type="submit">保存到电视 →</button>
  </form>
  <p class="hint">数据只写入本机，不上传任何服务器。<br>关闭电视端二维码弹窗后本页失效。</p>
</body></html>"""

    // 直连播放页面
    private fun playPage(): String = """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>夜帧影视 · 直连播放</title>
$CSS
</head><body>
  <h1><span class="icon">▶️</span>直连播放</h1>
  <p class="tip">把 m3u8 / mp4 地址贴进来，点「播放」，电视端立即开播。</p>
  <form method="post" action="/">
    <div class="field">
      <label>视频地址</label>
      <input type="url" name="url" placeholder="https://.../movie.m3u8"
        autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">
    </div>
    <button type="submit">发送到电视 →</button>
  </form>
</body></html>"""

    private fun successPage(form: Map<String, String>): String {
        val saved = buildString {
            if (form.containsKey("subscribe")) append("<p>✅ 订阅链接已导入</p>")
            if (form.containsKey("live"))      append("<p>✅ 直播源已切换</p>")
            if (form.containsKey("config"))    append("<p>✅ 点播配置已导入</p>")
            if (form.containsKey("sub_live"))  append("<p>✅ 直播订阅已添加</p>")
            if (form.containsKey("url"))       append("<p>✅ 正在播放</p>")
        }
        val backLink = if (mode == "play") """<a href="/">再播一个</a>""" else """<a href="/">继续录入</a>"""
        return """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>已保存</title>
$CSS
</head><body class="ok">
  <h1><span class="icon">🎉</span>已保存到电视</h1>
  ${saved.ifEmpty { "<p style='color:#b3ffffff'>（没有填写内容）</p>" }}
  <p>$backLink</p>
</body></html>"""
    }
}
