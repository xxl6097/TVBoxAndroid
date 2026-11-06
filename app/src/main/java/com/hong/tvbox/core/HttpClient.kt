package com.hong.tvbox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端 - 从 ArkTS Http.ets 移植
 *
 * 注意：所有方法都在 Dispatchers.IO 上执行。
 * OkHttp 的 execute() 是阻塞调用，如果直接跑在 lifecycleScope.launch（主线程）里，
 * 会抛 NetworkOnMainThreadException —— 直播源加载、网盘列目录、播放地址解析
 * 都是从 UI 作用域直接调用这里的，所以线程切换必须在这一层做掉。
 */
object HttpClient {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun clientWith(timeoutMs: Long): OkHttpClient {
        if (timeoutMs == 12000L) return client
        return client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /** GET 请求，返回文本响应 */
    suspend fun text(
        url: String,
        headers: Map<String, String>? = null,
        timeoutMs: Long = 12000
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")

        headers?.forEach { (k, v) -> builder.header(k, v) }

        try {
            clientWith(timeoutMs).newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    in 200..299 -> response.body?.string() ?: ""
                    403 -> throw IOException("HTTP 403，被站点拒绝（可能需要 Referer 或已限制地区）")
                    404 -> throw IOException("HTTP 404，接口不存在（站源可能已失效）")
                    429 -> throw IOException("HTTP 429，请求过于频繁被限流")
                    else -> throw IOException("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            throw IOException("网络请求失败：${e.message}", e)
        }
    }

    /**
     * 只抓首包（前 4KB），判断流地址「活不活」。
     * @return 空串表示 4xx/5xx，异常表示连不上
     */
    suspend fun peek(url: String, timeoutMs: Long = 3000): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-4095")
            .build()

        try {
            clientWith(timeoutMs).newCall(request).execute().use { response ->
                if (response.code >= 400) return@withContext ""

                val body = response.body?.string() ?: ""
                if (body.length > 4096) body.substring(0, 4096) else body
            }
        } catch (e: Exception) {
            throw IOException("连接失败：${e.message}", e)
        }
    }

    /** 下载二进制（爬虫 jar 之类） */
    suspend fun bytes(
        url: String,
        headers: Map<String, String>? = null,
        timeoutMs: Long = 30000
    ): ByteArray = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)

        headers?.forEach { (k, v) -> builder.header(k, v) }

        try {
            clientWith(timeoutMs).newCall(builder.build()).execute().use { response ->
                if (response.code !in 200..299) {
                    throw IOException("下载失败 HTTP ${response.code}")
                }
                response.body?.bytes() ?: ByteArray(0)
            }
        } catch (e: IOException) {
            throw IOException("下载失败：${e.message}", e)
        }
    }

    /** WebDAV PROPFIND（OkHttp 支持任意方法名） */
    suspend fun propfind(
        url: String,
        headers: Map<String, String>? = null,
        timeoutMs: Long = 15000
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("User-Agent", UA)
            .header("Depth", "1")

        headers?.forEach { (k, v) -> builder.header(k, v) }

        try {
            clientWith(timeoutMs).newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    in 200..299 -> response.body?.string() ?: ""
                    401 -> throw IOException("WebDAV HTTP 401，用户名或密码错误")
                    403 -> throw IOException("WebDAV HTTP 403，无权限访问该路径")
                    404 -> throw IOException("WebDAV HTTP 404，路径不存在")
                    else -> throw IOException("WebDAV HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("WebDAV HTTP") == true) throw e
            throw IOException("WebDAV 请求失败：${e.message}", e)
        }
    }
}
