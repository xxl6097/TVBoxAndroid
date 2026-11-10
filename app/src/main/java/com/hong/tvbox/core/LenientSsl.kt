package com.hong.tvbox.core

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 给裸 `HttpURLConnection` 用的宽松 SSL（信任全部证书）。
 *
 * 为什么需要：项目里有两套网络栈 ——
 *   · `HttpClient`（OkHttp）已经配了宽松 SSL
 *   · `UpdateManager` / `DownloadManager` 用的是 `URL.openConnection()`（HttpURLConnection）
 *     这一路**完全没配 SSL**
 *
 * 结果就是在 Android 6 这类老系统上，凡是证书链用到较新根证书的站点一律失败：
 *
 *   SSLHandshakeException: java.security.cert.CertPathValidatorException:
 *     Trust anchor for certification path not found.
 *
 * 实测小米盒子（Android 6.0.1 / API 23）升级时，8 个下载源里有 4 个直接是这个错，
 * 包括 github.com 直连。系统根证书库停在 2015 年，无法验证现在的证书链。
 *
 * 取舍：本应用的网络请求只涉及「用户自己配置的站源 / 订阅 / 公开的更新包」，
 * 不承载账号密码与支付，放宽校验换取老设备可用性是值得的。
 * 更新包另有一层 [ApkVerifier] 的包名 + 版本校验兜底。
 */
object LenientSsl {

    private const val TAG = "TVBoxSsl"

    private val trustAll: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /** 延迟初始化：SSLContext 创建有一定开销，且失败时要能兜住 */
    private val factory: SSLSocketFactory? by lazy {
        try {
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            }.socketFactory
        } catch (e: Exception) {
            Log.w(TAG, "初始化宽松 SSL 失败，回退系统默认", e)
            null
        }
    }

    private val verifier = HostnameVerifier { _, _ -> true }

    /** 对 HttpsURLConnection 应用宽松 SSL；http:// 连接原样返回 */
    fun apply(conn: HttpURLConnection) {
        if (conn !is HttpsURLConnection) return
        val f = factory
        if (f == null) return
        try {
            conn.sslSocketFactory = f
            conn.hostnameVerifier = verifier
        } catch (e: Exception) {
            Log.w(TAG, "应用宽松 SSL 失败", e)
        }
    }

    /** 打开连接并顺手配好 SSL，省得每个调用点都写一遍 */
    fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        apply(conn)
        return conn
    }
}
