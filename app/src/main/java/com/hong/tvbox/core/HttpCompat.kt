package com.hong.tvbox.core

import java.net.HttpURLConnection

/**
 * HTTP 头兼容工具（minSdk 21）。
 *
 * 背景：`HttpURLConnection.getContentLengthLong()` 是 **API 24 / Android 7.0** 才有的方法。
 * 在 Android 6（API 23）上直接调用会抛 `NoSuchMethodError` ——
 * 它继承自 `LinkageError`/`Error`，`catch (e: Exception)` 根本接不住，
 * 协程里也没人管，最终 `FATAL EXCEPTION: main` → 进程被杀 → 用户看到"闪退"。
 *
 * 更隐蔽的是：直接写 `conn.contentLengthLong` 时，R8 会把它改写/折叠成
 * `androidx.media3.common.util.f.b(conn)`（media3 里 `Util.Api24` 的包装），
 * 反汇编看起来完全不像自己代码的问题，排查成本极高。
 *
 * 因此这里**只用字符串头**（`getHeaderField`，API 1 起就有）来算总长度，
 * 既绕开 API 24，也比 `getContentLengthLong()` 更准：
 * 后者在 206 响应里只返回**本段**长度，而不是文件总长。
 */
object HttpCompat {

    /**
     * 解析响应体的总字节数（未知返回 -1）。
     *
     * @param conn        已 connect 并拿到 responseCode 的连接
     * @param startOffset 本次请求的起始偏移（断点续传时的 Range 起点，非续传传 0）
     * @param code        HTTP 状态码（200 / 206）
     */
    fun resolveContentLength(conn: HttpURLConnection, startOffset: Long, code: Int): Long {
        // 1) 断点续传：Content-Range: bytes 1024-2047/3000000 → 取 "/" 之后的总数
        conn.getHeaderField("Content-Range")
            ?.substringAfterLast('/', "")
            ?.trim()
            ?.toLongOrNull()
            ?.let { if (it > 0) return it }

        // 2) 普通 200：Content-Length 头（纯字符串 API，无版本限制）
        //    注意 206 时 Content-Length 只是**本段**长度，必须加上偏移才是文件总长
        conn.getHeaderField("Content-Length")
            ?.trim()
            ?.toLongOrNull()
            ?.let { if (it > 0) return it + if (code == 206) startOffset else 0L }

        // 3) 最后退回 int 版 getContentLength()（API 1 起就有，绝不会变成 API 24 方法）
        val n = conn.contentLength
        if (n > 0) return n.toLong() + if (code == 206) startOffset else 0L

        return -1L
    }
}
