package com.hong.tvbox.core

import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端 - 从 ArkTS Http.ets 移植
 * 提供 GET 请求 + 自动重定向 + 中文错误提示
 */
object HttpClient {
    private const val UA = "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    
    /**
     * GET 请求，返回文本响应
     * @param url 请求地址
     * @param headers 额外请求头
     * @param timeoutMs 超时毫秒（可选）
     */
    suspend fun text(url: String, headers: Map<String, String>? = null, timeoutMs: Long = 12000): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
        
        headers?.forEach { (k, v) -> builder.header(k, v) }
        
        val customClient = if (timeoutMs != 12000L) {
            client.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
        } else {
            client
        }
        
        return try {
            val response = customClient.newCall(builder.build()).execute()
            
            when (response.code) {
                in 200..299 -> response.body?.string() ?: ""
                403 -> throw IOException("HTTP 403，被站点拒绝（可能需要 Referer 或已限制地区）")
                404 -> throw IOException("HTTP 404，接口不存在（站源可能已失效）")
                429 -> throw IOException("HTTP 429，请求过于频繁被限流")
                else -> throw IOException("HTTP ${response.code}")
            }
        } catch (e: IOException) {
            throw IOException("网络请求失败：${e.message}", e)
        }
    }
    
    /**
     * 只抓首包（前 4KB），用来判断流地址「活不活」和「快不快」
     * @return 空串表示 4xx/5xx，异常表示连不上
     */
    suspend fun peek(url: String, timeoutMs: Long = 3000): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Range", "bytes=0-4095")
            .build()
        
        val customClient = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        
        return try {
            val response = customClient.newCall(request).execute()
            if (response.code >= 400) return ""
            
            val body = response.body?.string() ?: ""
            if (body.length > 4096) body.substring(0, 4096) else body
        } catch (e: Exception) {
            throw IOException("连接失败：${e.message}", e)
        }
    }
    
    /**
     * WebDAV PROPFIND 请求
     */
    suspend fun propfind(url: String, headers: Map<String, String>? = null, timeoutMs: Long = 15000): String {
        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("User-Agent", UA)
            .header("Depth", "1")
        
        headers?.forEach { (k, v) -> builder.header(k, v) }
        
        val customClient = client.newBuilder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        
        return try {
            val response = customClient.newCall(builder.build()).execute()
            
            when (response.code) {
                in 200..299 -> response.body?.string() ?: ""
                401 -> throw IOException("WebDAV HTTP 401，用户名或密码错误")
                403 -> throw IOException("WebDAV HTTP 403，无权限访问该路径")
                404 -> throw IOException("WebDAV HTTP 404，路径不存在")
                else -> throw IOException("WebDAV HTTP ${response.code}")
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("WebDAV HTTP") == true) throw e
            throw IOException("WebDAV 请求失败：${e.message}", e)
        }
    }
}
