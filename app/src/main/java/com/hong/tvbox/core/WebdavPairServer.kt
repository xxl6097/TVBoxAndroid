package com.hong.tvbox.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference

/**
 * TV 端内嵌 HTTP 配对服务
 *
 * ## 流程
 * 1. TV 启动此服务，监听一个随机端口（8788～8798 范围自动找空闲）
 * 2. 把 `http://<TV本机IP>:<port>/pair` 编成二维码显示给用户
 * 3. 手机扫码，浏览器打开一个表单页（纯 HTML，内嵌 CSS，适配手机屏）
 * 4. 用户填写 WebDAV 地址/账号密码，点「保存」提交 POST 到 /submit
 * 5. 服务接收 POST，解析出 WebdavSource，放入 [pending]
 * 6. 调用方轮询 [takePending] 即可拿到结果，拿到后立即停服务
 *
 * ## 安全
 * - 只监听 0.0.0.0（同 Wi-Fi 下的设备才能访问）
 * - Token 机制：URL 带一次性 token（4 位随机数字），防局域网误提交
 * - 最多接受 1 次 POST，之后立即拒绝
 */
class WebdavPairServer(private val context: Context) {

    private val TAG = "WebdavPairServer"

    data class PairResult(
        val name: String,
        val url: String,
        val user: String,
        val pass: String,
        val root: String
    )

    private var serverSocket: ServerSocket? = null
    private val pending = AtomicReference<PairResult?>(null)
    private var done = false
    private val token = "%04d".format((0..9999).random())

    /** 监听的端口（0 = 未启动） */
    var port: Int = 0
        private set

    /** 获取 TV 在当前 Wi-Fi 下的 IPv4 地址 */
    fun getLocalIp(): String? {
        // 方式1: WifiManager（真机 Wi-Fi 环境）
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return "%d.%d.%d.%d".format(ip and 0xFF, (ip shr 8) and 0xFF, (ip shr 16) and 0xFF, (ip shr 24) and 0xFF)
            }
        } catch (_: Exception) {}
        // 方式2: NetworkInterface 遍历（模拟器/以太网/热点场景）
        try {
            val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.") || ip.startsWith("127."))
                            return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** 配对页 URL（编到二维码里） */
    fun pairUrl(): String? {
        val ip = getLocalIp() ?: return null
        return "http://$ip:$port/pair?t=$token"
    }

    /**
     * 同步绑定端口（在调用 pairUrl() 之前必须先调用此方法）
     * @return true=绑定成功
     */
    fun bindPort(): Boolean {
        if (serverSocket != null) return true  // 已绑定
        try {
            var ss: ServerSocket? = null
            for (p in 8788..8798) {
                try {
                    ss = ServerSocket(p)
                    break
                } catch (_: Exception) {}
            }
            if (ss == null) {
                Log.e(TAG, "找不到可用端口 8788~8798")
                return false
            }
            serverSocket = ss
            port = ss.localPort
            Log.d(TAG, "PairServer 端口绑定成功 port=$port token=$token")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "端口绑定失败: ${e.message}")
            return false
        }
    }

    /**
     * 启动 HTTP 服务（挂起函数，在 IO 线程运行，直到收到一次有效 POST 或 [stop] 被调用）
     * 调用前必须先 [bindPort]
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        val ss = serverSocket ?: return@withContext
        if (port == 0) return@withContext

        try {
            while (!done) {
                val client = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                handleClient(client)
            }
        } finally {
            ss.runCatching { close() }
        }
    }

    /** 停止服务（可从主线程调用） */
    fun stop() {
        done = true
        serverSocket?.runCatching { close() }
    }

    /** 取出已接收的配对结果，取出后置为 null */
    fun takePending(): PairResult? = pending.getAndSet(null)

    // ────────────────────────────────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 8000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = PrintWriter(client.getOutputStream(), true)

            // 读取请求行
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val rawPath = parts.getOrElse(1) { "/" }

            Log.d(TAG, "$method $rawPath")

            // 解析路径和 query
            val (path, query) = rawPath.split("?").let {
                it[0] to (it.getOrNull(1) ?: "")
            }
            val params = parseQuery(query)

            // 读取 headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                val idx = line!!.indexOf(':')
                if (idx > 0) {
                    headers[line!!.substring(0, idx).trim().lowercase()] = line!!.substring(idx + 1).trim()
                }
            }

            // 读取 POST body
            var body = ""
            if (method == "POST") {
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val buf = CharArray(contentLength.coerceAtMost(8192))
                    val read = reader.read(buf, 0, buf.size)
                    body = String(buf, 0, read)
                }
            }

            when {
                path == "/pair" -> servePairPage(out, params)
                path == "/submit" && method == "POST" -> handleSubmit(out, body, params)
                path == "/success" -> serveSuccessPage(out)
                else -> serve404(out)
            }

        } catch (e: Exception) {
            Log.w(TAG, "handleClient error: ${e.message}")
        } finally {
            client.runCatching { close() }
        }
    }

    /** GET /pair?t=xxxx — 返回填写表单的 HTML 页 */
    private fun servePairPage(out: PrintWriter, params: Map<String, String>) {
        if (params["t"] != token) {
            serveError(out, "Token 无效")
            return
        }
        val html = buildPairHtml(token)
        out.print("HTTP/1.1 200 OK\r\n")
        out.print("Content-Type: text/html; charset=UTF-8\r\n")
        out.print("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
        out.print("Connection: close\r\n")
        out.print("\r\n")
        out.print(html)
        out.flush()
    }

    /** POST /submit — 接收表单数据 */
    private fun handleSubmit(out: PrintWriter, body: String, params: Map<String, String>) {
        if (done) {
            serveError(out, "已接收，请关闭页面")
            return
        }

        val formData = parseQuery(body)

        // token 验证（在 POST body 里）
        val t = formData["t"] ?: params["t"]
        if (t != token) {
            serveError(out, "Token 无效")
            return
        }

        val url = formData["url"]?.trim() ?: ""
        if (url.isEmpty() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            serveError(out, "地址无效，必须以 http:// 或 https:// 开头")
            return
        }

        val result = PairResult(
            name = formData["name"]?.trim() ?: "",
            url = url.trimEnd('/'),
            user = formData["user"]?.trim() ?: "",
            pass = formData["pass"] ?: "",
            root = formData["root"]?.trim() ?: ""
        )

        Log.d(TAG, "收到配对结果: name=${result.name} url=${result.url}")
        pending.set(result)
        done = true  // 只接受一次

        // 重定向到成功页
        out.print("HTTP/1.1 303 See Other\r\n")
        out.print("Location: /success\r\n")
        out.print("Connection: close\r\n")
        out.print("\r\n")
        out.flush()

        // 延迟停服，让浏览器能成功获取 /success 页面
        Thread { Thread.sleep(2000); stop() }.start()
    }

    private fun serveSuccessPage(out: PrintWriter) {
        val html = """<!DOCTYPE html>
<html lang="zh"><head><meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>配对成功</title>
<style>
  body { background:#0a0a1a; color:#fff; font-family:system-ui;
         display:flex; flex-direction:column; align-items:center;
         justify-content:center; height:100vh; margin:0; }
  .icon { font-size:72px; margin-bottom:16px; }
  h1 { font-size:24px; margin:0 0 8px; }
  p { color:#aaa; font-size:14px; }
</style>
</head><body>
<div class="icon">✅</div>
<h1>配对成功！</h1>
<p>TV 端已收到，请回到电视确认。</p>
</body></html>"""
        out.print("HTTP/1.1 200 OK\r\n")
        out.print("Content-Type: text/html; charset=UTF-8\r\n")
        out.print("Content-Length: ${html.toByteArray(Charsets.UTF_8).size}\r\n")
        out.print("Connection: close\r\n")
        out.print("\r\n")
        out.print(html)
        out.flush()
    }

    private fun serveError(out: PrintWriter, msg: String) {
        val html = "<html><body style='background:#111;color:#f55;font-family:sans-serif;padding:32px'><h2>❌ $msg</h2></body></html>"
        out.print("HTTP/1.1 400 Bad Request\r\n")
        out.print("Content-Type: text/html; charset=UTF-8\r\n")
        out.print("Content-Length: ${html.toByteArray().size}\r\n")
        out.print("Connection: close\r\n")
        out.print("\r\n")
        out.print(html)
        out.flush()
    }

    private fun serve404(out: PrintWriter) {
        out.print("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n")
        out.flush()
    }

    /** 解析 URL query string 或 POST body（application/x-www-form-urlencoded） */
    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else {
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                key to value
            }
        }.toMap()
    }

    // ────────────────────────────────────────────────────────────────────────
    // HTML 模板 — 手机端填写 WebDAV 信息的表单页
    // ────────────────────────────────────────────────────────────────────────

    private fun buildPairHtml(tok: String) = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<title>添加 WebDAV 源</title>
<style>
  *, *::before, *::after { box-sizing: border-box; }
  body {
    background: #0d0d1a;
    color: #e8e8f0;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    margin: 0; padding: 0;
    min-height: 100vh;
  }
  .container {
    max-width: 440px; margin: 0 auto; padding: 24px 20px 40px;
  }
  .header {
    text-align: center; padding: 24px 0 20px;
  }
  .header .icon { font-size: 40px; margin-bottom: 8px; }
  .header h1 { font-size: 20px; margin: 0 0 4px; color: #fff; }
  .header p { font-size: 13px; color: #888; margin: 0; }

  .card {
    background: #171730;
    border-radius: 14px;
    padding: 20px 16px;
    margin-bottom: 16px;
  }
  .card-title {
    font-size: 12px; font-weight: 600; letter-spacing: .06em;
    color: #6b6b9a; text-transform: uppercase; margin-bottom: 14px;
  }

  .field { margin-bottom: 14px; }
  .field:last-child { margin-bottom: 0; }
  label {
    display: block; font-size: 13px; color: #aaa; margin-bottom: 6px;
  }
  input[type=text], input[type=password], input[type=url] {
    width: 100%; background: #0d0d1a;
    border: 1.5px solid #2a2a4a; border-radius: 8px;
    color: #fff; font-size: 15px;
    padding: 11px 13px; outline: none;
    -webkit-appearance: none; appearance: none;
    transition: border-color .15s;
  }
  input:focus { border-color: #5566ff; }
  input::placeholder { color: #44446a; }

  .hint { font-size: 11px; color: #55556a; margin-top: 5px; }

  .btn {
    display: block; width: 100%; padding: 15px;
    background: linear-gradient(135deg, #4455ff, #7744cc);
    color: #fff; font-size: 16px; font-weight: 600;
    border: none; border-radius: 12px;
    cursor: pointer; margin-top: 20px;
    -webkit-tap-highlight-color: transparent;
    transition: opacity .15s;
  }
  .btn:active { opacity: .85; }

  .tips {
    background: #141428; border-radius: 10px; padding: 14px 16px;
    margin-top: 16px; font-size: 12px; color: #666; line-height: 1.8;
  }
  .tips b { color: #888; }
</style>
</head>
<body>
<div class="container">
  <div class="header">
    <div class="icon">☁️</div>
    <h1>添加 WebDAV 源</h1>
    <p>填写后点保存，TV 端自动接收</p>
  </div>

  <form method="POST" action="/submit">
    <input type="hidden" name="t" value="$tok">

    <div class="card">
      <div class="card-title">基本信息</div>
      <div class="field">
        <label>名称（可选）</label>
        <input type="text" name="name" placeholder="如：家里的极空间" autocomplete="off">
      </div>
      <div class="field">
        <label>WebDAV 地址 *</label>
        <input type="url" name="url" placeholder="http://192.168.1.2:5005" required
               autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">
        <div class="hint">极空间默认 :5005 · 群晖 :5005 · AList :5244/dav</div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">账号密码</div>
      <div class="field">
        <label>用户名</label>
        <input type="text" name="user" placeholder="留空表示无需认证"
               autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">
      </div>
      <div class="field">
        <label>密码</label>
        <input type="password" name="pass" placeholder="留空表示无需认证" autocomplete="new-password">
      </div>
    </div>

    <div class="card">
      <div class="card-title">高级选项</div>
      <div class="field">
        <label>根路径（可选）</label>
        <input type="text" name="root" placeholder="如 /影视  /Videos"
               autocomplete="off" autocorrect="off" autocapitalize="none" spellcheck="false">
        <div class="hint">只想挂载某个子目录时填写，留空从根目录浏览</div>
      </div>
    </div>

    <button class="btn" type="submit">📺 保存到 TV</button>
  </form>

  <div class="tips">
    <b>常见配置参考</b><br>
    极空间：http://&lt;NAS IP&gt;:5005<br>
    群晖 DSM：http://&lt;NAS IP&gt;:5006<br>
    AList：http://&lt;IP&gt;:5244/dav<br>
    <br>
    <b>提示：</b>如果不在同一个 Wi-Fi 下，填外网地址
  </div>
</div>
</body>
</html>"""
}
