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
 * 极简 HTTP 服务 - 对照鸿蒙 QrServer.ets
 *
 * 只为「手机扫码录订阅」这一个场景服务：
 *   GET  /      → 返回一个三框表单（订阅 / 配置 / 直播源）
 *   POST /      → 解析表单，回调给页面落盘，返回成功页
 *
 * 只监听局域网，随页面关闭而停止；不做鉴权（页面打开期间才可用，
 * 且只写本机配置，不接受任意文件操作）。
 */
class QrServer(
    private val port: Int = 8788,
    private val onSubmit: (Map<String, String>) -> Unit
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    /** 本机在局域网里的 IPv4 地址，取不到时返回 127.0.0.1 */
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
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 忽略，退到 127.0.0.1
        }
        return "127.0.0.1"
    }

    fun url(): String = "http://${localIp()}:$port"

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
                    } catch (e: Exception) {
                        if (!running) break
                    }
                }
            }.apply {
                isDaemon = true
                name = "qr-server"
                start()
            }
            true
        } catch (e: Exception) {
            running = false
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // 忽略
        }
        serverSocket = null
        thread?.interrupt()
        thread = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
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
                respond(out, "已保存", successPage(form))
            } else {
                respond(out, "录入", formPage())
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

    private fun respond(out: OutputStream, title: String, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun formPage(): String = """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>鸿蒙影视 · 扫码录入</title>
<style>
  body{font-family:-apple-system,system-ui,sans-serif;background:#141218;color:#fff;margin:0;padding:20px}
  h1{font-size:20px;margin:0 0 6px}
  p.tip{color:#b3ffffff;font-size:13px;margin:0 0 18px}
  label{display:block;font-size:14px;color:#ffeb3b;margin:18px 0 6px}
  input,textarea{width:100%;box-sizing:border-box;background:#1c1b1f;border:1px solid #333;
    border-radius:10px;color:#fff;padding:12px;font-size:15px}
  textarea{min-height:96px}
  button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:12px;
    background:#fff;color:#141218;font-size:16px;font-weight:600}
  .hint{color:#80ffffff;font-size:12px;margin-top:10px;line-height:1.6}
</style></head>
<body>
  <h1>录入到电视</h1>
  <p class="tip">手机上填好点「保存到电视」，电视端立即生效。</p>
  <form method="post" action="/">
    <label>订阅链接（点播 + 直播一起更新）</label>
    <input name="subscribe" placeholder="https://.../subscribe.json">

    <label>直播源（m3u / txt，可单独填）</label>
    <input name="live" placeholder="https://.../iptv.m3u">

    <label>点播配置 JSON（可选，直接粘贴）</label>
    <textarea name="config" placeholder='{"sites":[...]}'></textarea>

    <button type="submit">保存到电视</button>
  </form>
  <p class="hint">只写本机配置，不上传任何服务器。离开设置页后本页面立即失效。</p>
</body></html>
""".trimIndent()

    private fun successPage(form: Map<String, String>): String {
        val saved = buildString {
            if (form.containsKey("subscribe")) append("<li>订阅链接</li>")
            if (form.containsKey("live")) append("<li>直播源</li>")
            if (form.containsKey("config")) append("<li>点播配置</li>")
        }
        return """
<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>已保存</title>
<style>
  body{font-family:-apple-system,system-ui,sans-serif;background:#141218;color:#fff;
    margin:0;padding:32px;text-align:center}
  h1{font-size:22px}
  ul{display:inline-block;text-align:left;color:#b3ffffff;line-height:1.9}
  a{color:#ffeb3b}
</style></head>
<body>
  <h1>✅ 已保存到电视</h1>
  <ul>${saved.ifEmpty { "<li>（没有填写内容）</li>" }}</ul>
  <p><a href="/">继续录入</a></p>
</body></html>
""".trimIndent()
    }
}
