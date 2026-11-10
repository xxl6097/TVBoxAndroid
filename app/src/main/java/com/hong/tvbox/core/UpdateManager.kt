package com.hong.tvbox.core

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hong.tvbox.ui.PermissionHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * GitHub Release 自动更新管理器
 *
 * 功能：
 * - GitHub Releases API 检查新版本
 * - 国内代理加速下载（多镜像自动切换）
 * - 实时下载进度回调
 * - FileProvider 安装（Android 7.0+）
 */
object UpdateManager {

    private const val TAG = "TVBoxUpdate"
    private const val GITHUB_OWNER = "xxl6097"
    private const val GITHUB_REPO = "TVBoxAndroid"
    private const val GITHUB_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    /**
     * GitHub 下载代理镜像列表（按优先级排序）
     * 格式：前缀 + 完整 GitHub URL → 代理后地址
     * 空字符串 = 直连
     */
    val PROXY_LIST = mutableListOf(
        "https://ghfast.top/",         // ghfast — 国内最稳定
        "https://gh-proxy.com/",        // gh-proxy.com
        "https://ghproxy.1888866.xyz/", // ghproxy.1888866
        "https://github.moeyy.xyz/",    // moeyy
        "https://ghproxy.cn/",         // ghproxy.cn
        "https://mirror.ghproxy.com/", // mirror.ghproxy
        "https://hub.gitmirror.com/",  // gitmirror
        "",                            // 直连（境外/翻墙用户）
    )

    /** 当前选用代理下标（默认 0 = ghfast） */
    var selectedProxyIndex: Int = 0

    // ─── 数据模型 ─────────────────────────────────────────────────────────────

    data class ReleaseInfo(
        @SerializedName("tag_name") val tagName: String = "",
        @SerializedName("body") val body: String = "",
        @SerializedName("published_at") val publishedAt: String = "",
        @SerializedName("assets") val assets: List<Asset> = emptyList()
    )

    data class Asset(
        @SerializedName("name") val name: String = "",
        @SerializedName("browser_download_url") val downloadUrl: String = "",
        @SerializedName("size") val size: Long = 0L
    )

    data class UpdateInfo(
        val hasUpdate: Boolean = false,
        val currentVersion: String = "",
        val latestVersion: String = "",
        val releaseNotes: String = "",
        val downloadUrl: String = "",  // 原始 GitHub URL，下载时自动叠加代理
        val fileSize: Long = 0L,
        val publishedAt: String = ""
    )

    // ─── 代理工具 ─────────────────────────────────────────────────────────────

    /** 为 GitHub URL 叠加代理前缀 */
    fun proxyUrl(rawUrl: String, proxyIndex: Int = selectedProxyIndex): String {
        val prefix = PROXY_LIST.getOrElse(proxyIndex) { "" }
        return if (prefix.isEmpty()) rawUrl else "$prefix$rawUrl"
    }

    /**
     * 探测最快代理（HEAD 请求，4s 超时），结果写入 selectedProxyIndex
     * @param testUrl 用来探测的完整 GitHub URL（通常用 APK 下载链接）
     */
    suspend fun findFastestProxy(testUrl: String, timeoutMs: Int = 4000): Int =
        withContext(Dispatchers.IO) {
            for ((i, proxy) in PROXY_LIST.withIndex()) {
                val url = if (proxy.isEmpty()) testUrl else "$proxy$testUrl"
                try {
                    // 必须走 LenientSsl：Android 6 上 github.com / ghproxy.cn 等
                    // 会因系统根证书过期报 Trust anchor not found
                    val conn = LenientSsl.open(url)
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.connect()
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code in 200..399) {
                        Log.d(TAG, "代理探测命中: $proxy (index=$i, code=$code)")
                        selectedProxyIndex = i
                        return@withContext i
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "代理探测失败: ${proxy.ifEmpty { "直连" }} — ${e.message}")
                }
            }
            // 全部失败，保持默认
            selectedProxyIndex
        }

    // ─── 版本检查 ──────────────────────────────────────────────────────────────

    suspend fun checkUpdate(context: Context): UpdateInfo = withContext(Dispatchers.IO) {
        val current = getAppVersion(context)
        Log.d(TAG, "检查更新: 当前版本=$current")

        val json = try {
            val conn = LenientSsl.open(GITHUB_API)
            conn.connectTimeout = 12000
            conn.readTimeout = 12000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "TVBox/$current")
            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().readText().also { conn.disconnect() }
        } catch (e: Exception) {
            throw Exception("无法连接到 GitHub API：${e.message}")
        }

        val release = Gson().fromJson(json, ReleaseInfo::class.java)
        val latest = release.tagName.trimStart('v', 'V')
        val hasUpdate = compareVersions(latest, current) > 0

        /**
         * 选包：**必须**优先匹配本版版本号。
         *
         * 曾经踩过的坑：Release 里残留了上一个版本命名的 APK
         * （v1.3.8 的 Release 里放着 TVBoxAndroid-v1.3.7-release.apk），
         * 而这里原来用 `firstOrNull` 取第一个 apk，结果下到了旧包 ——
         * 用户装完版本没变、下次又提示更新，甚至因为 versionCode 不匹配报解析错误。
         */
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        val apkAsset = apks.firstOrNull { it.name.contains("v$latest") && it.name.contains("release", true) }
            ?: apks.firstOrNull { it.name.contains("v$latest") }
            ?: apks.firstOrNull { it.name.contains("release", true) && !it.name.contains("debug", true) }

        if (hasUpdate && apkAsset == null) {
            // 有新版 tag 但没有对应的 APK：宁可明确报错，也不要拿旧包糊弄用户
            throw Exception(
                "v$latest 尚未上传对应的安装包" +
                        if (apks.isEmpty()) "（该 Release 没有任何 APK）"
                        else "（现有：${apks.joinToString("、") { it.name }}）"
            )
        }

        Log.d(TAG, "最新版=$latest 有更新=$hasUpdate APK=${apkAsset?.name} 共${apks.size}个包")

        UpdateInfo(
            hasUpdate = hasUpdate,
            currentVersion = current,
            latestVersion = latest,
            releaseNotes = release.body,
            downloadUrl = apkAsset?.downloadUrl ?: "",
            fileSize = apkAsset?.size ?: 0L,
            publishedAt = release.publishedAt
        )
    }

    // ─── 下载（带实时进度）────────────────────────────────────────────────────

    /**
     * 下载 APK，支持多代理自动切换 + 实时进度回调
     *
     * @param onProgress (percent 0-100, downloadedMB, totalMB)
     * @return 下载后的本地 File
     */
    suspend fun downloadApk(
        context: Context,
        info: UpdateInfo,
        destFile: File = defaultDestFile(context, info),
        onProgress: (percent: Int, downloadedMb: Float, totalMb: Float) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        // 只有写到「外部存储」才需要存储权限；写到应用私有目录（cacheDir/filesDir）不需要。
        // 默认落点已改为 cacheDir，因此正常情况下这里不会触发权限申请。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && isExternalPath(context, destFile)) {
            if (!PermissionHelper.hasStoragePermission(context)) {
                throw Exception("没有存储权限，无法写入下载目录。请允许存储权限后重试。")
            }
        }

        if (info.downloadUrl.isEmpty()) throw Exception("下载链接为空")

        /**
         * 本地已有完整且版本正确的包 → 直接复用。
         *
         * 两个好处：
         * 1. 用户点过一次失败再点，不用重新下 3MB
         * 2. 避免"完整文件 + Range 请求"必然触发的 HTTP 416
         */
        if (destFile.exists()) {
            val v = ApkVerifier.verify(context, destFile, expectPackage = context.packageName)
            val expect = info.latestVersion.trimStart('v', 'V')
            if (v.ok && v.versionName == expect) {
                Log.d(TAG, "本地已有完整安装包 v${v.versionName}，跳过下载")
                return@withContext destFile
            }
            Log.d(
                TAG,
                "本地文件不可复用（${v.reason.ifEmpty { "版本不符：${v.versionName} ≠ $expect" }}），删除后重新下载"
            )
            destFile.delete()
        }

        // 代理尝试顺序：先用已选代理，再逐一回退
        val proxyOrder = buildList {
            add(selectedProxyIndex)
            addAll(PROXY_LIST.indices.filter { it != selectedProxyIndex })
        }

        var lastError: Throwable? = null
        for (proxyIdx in proxyOrder) {
            val url = proxyUrl(info.downloadUrl, proxyIdx)
            val proxyName = PROXY_LIST[proxyIdx].ifEmpty { "直连" }
            Log.d(TAG, "下载尝试[$proxyIdx] $proxyName: $url")
            try {
                downloadWithProgress(url, destFile) { bytes, total ->
                    val mb = bytes / 1024f / 1024f
                    val totalMb = if (total > 0) total / 1024f / 1024f else info.fileSize / 1024f / 1024f
                    val pct = if (total > 0) (bytes * 100 / total).toInt() else
                        if (info.fileSize > 0) (bytes * 100 / info.fileSize).toInt() else 0
                    withContext(Dispatchers.Main) { onProgress(pct.coerceIn(0, 100), mb, totalMb) }
                }

                // ── 下载完必须做严格校验 ────────────────────────────
                // 只看文件头 4 字节是不够的：连接被掐断时 read() 会返回 -1，
                // 半个文件也能"通过"，用户点安装就是「解析包错误」。
                val vr = ApkVerifier.verify(
                    context = context,
                    file = destFile,
                    expectPackage = context.packageName,
                    minVersionCode = currentVersionCode(context)
                )
                if (!vr.ok) {
                    Log.w(TAG, "校验失败[${proxyName}]: ${vr.reason}")
                    // 删掉半成品，下一个代理会重新完整下载（不带 Range，避免续传拼坏）
                    destFile.delete()
                    throw Exception("安装包校验失败：${vr.reason}")
                }
                Log.d(TAG, "校验通过: ${vr.packageName} ${vr.versionName} (${vr.versionCode})")

                selectedProxyIndex = proxyIdx  // 记住本次成功的代理
                return@withContext destFile
            } catch (e: CancellationException) {
                // 协程取消必须原样抛出，不能当成"下载失败"继续试下一个代理
                throw e
            } catch (e: Throwable) {
                // 用 Throwable 而不是 Exception：NoSuchMethodError / OutOfMemoryError 这类
                // Error 不会被 Exception 接住，会直接冒到主线程变成闪退（v1.3.4 的真实事故）
                Log.w(TAG, "代理[$proxyIdx] 下载失败: ${e.javaClass.simpleName} ${e.message}")
                lastError = e
                if (destFile.exists()) destFile.delete()
            }
        }
        throw lastError ?: Exception("所有代理均下载失败")
    }

    /**
     * 单次下载尝试。
     *
     * 显式写 `: Unit`：函数内部在 416 时会递归调用自己，
     * 不写返回类型会让 Kotlin 的类型推断陷入循环
     * （"Type checking has run into a recursive problem"）。
     */
    private suspend fun downloadWithProgress(
        url: String,
        dest: File,
        allowResume: Boolean = true,
        onBytes: suspend (downloaded: Long, total: Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        // LenientSsl：Android 6 的根证书库停在 2015 年，github.com 直连与
        // ghproxy.cn / gh-proxy.com 都会报 Trust anchor for certification path not found
        val conn = LenientSsl.open(url)
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "TVBox/1.0")
        conn.instanceFollowRedirects = true

        // 断点续传：只在文件非空且看起来有效时启用（避免代理上次返回 HTML 错误页，本次续传出损坏 APK）
        val resumeFrom = if (allowResume && dest.exists() && dest.length() > 1024 && isApkValid(dest)) {
            conn.setRequestProperty("Range", "bytes=${dest.length()}-")
            dest.length()
        } else {
            dest.delete()
            0L
        }

        conn.connect()
        val code = conn.responseCode

        /**
         * HTTP 416 Range Not Satisfiable。
         *
         * 触发条件：本地残留文件长度 >= 远端文件长度，却还发了 Range 请求。
         * 实测（小米盒子日志）：上次已经下到完整包，再次点更新时
         *   `Range: bytes=3381185-` → 服务器直接 416，112ms 就返回。
         * 旧代码把它当成普通失败，导致每次都白跑一轮代理。
         *
         * 正确做法：清掉本地文件，**不带 Range** 重新完整下载一次。
         */
        if (code == 416) {
            conn.disconnect()
            Log.w(TAG, "HTTP 416（本地文件 >= 远端），清空后重新完整下载")
            dest.delete()
            if (allowResume) {
                return@withContext downloadWithProgress(url, dest, allowResume = false, onBytes = onBytes)
            }
            throw Exception("HTTP 416，服务器拒绝续传请求")
        }

        if (code != 200 && code != 206) {
            conn.disconnect()
            throw Exception("HTTP $code")
        }

        // 检查服务端返回的内容类型：代理有时会返回 HTML 错误页
        val contentType = conn.contentType ?: ""
        if (contentType.contains("text/html", ignoreCase = true)) {
            conn.disconnect()
            throw Exception("代理返回了 HTML 内容而非 APK（contentType=$contentType），可能代理服务异常")
        }

        // ⚠️ 这里**不能**用 conn.contentLengthLong：它是 API 24 方法，
        // Android 6 上抛 NoSuchMethodError(Error)，catch(Exception) 接不住 → 闪退。
        // HttpCompat 用字符串头解析，兼容 API 21 且 206 时拿到的是文件总长。
        val total = HttpCompat.resolveContentLength(conn, resumeFrom, code)

        val inp = conn.inputStream
        val out = FileOutputStream(dest, code == 206)
        val buf = ByteArray(65536)
        var downloaded = if (code == 206) resumeFrom else 0L
        var lastPct = -1

        try {
            while (true) {
                val read = inp.read(buf)
                if (read == -1) break
                out.write(buf, 0, read)
                downloaded += read
                val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    onBytes(downloaded, total)
                }
            }
            out.flush()
        } finally {
            out.close()
            inp.close()
            conn.disconnect()
        }

        // 下载完成后校验 APK 格式（APK 本质是 ZIP，文件头必须是 PK\x03\x04）
        if (!isApkValid(dest)) {
            dest.delete()
            throw Exception("下载的文件不是有效的 APK（文件头校验失败），可能代理返回了错误内容")
        }
    }

    /**
     * 简单校验 APK 文件是否有效：APK 是 ZIP 格式，文件头前 4 字节必须是 50 4B 03 04
     * 若代理返回了 HTML 错误页，文件头会是 3C 21 44 4F（<!DO...）
     */
    private fun isApkValid(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        } catch (_: Exception) {
            false
        }
    }

    // ─── 安装 ──────────────────────────────────────────────────────────────────

    /**
     * 安装 APK。
     *
     * 实际逻辑已抽到 [ApkInstaller]：FileProvider 优先 + 四级降级 + 全异常捕获。
     * 这里保留原签名，内部只做参数校验与结果提示，**绝不抛异常**（避免闪退）。
     */
    fun installApk(context: Context, apkFile: File) {
        val result = ApkInstaller.install(context, apkFile)
        if (!result.ok) {
            Log.e(TAG, "安装失败: ${result.message}\n${result.detail}")
            // 失败时不清空文件：用户可以重试，或改用「导出 APK」手动安装
        } else {
            Log.d(TAG, "安装器已拉起: ${result.message}")
        }
    }

    // ─── 工具方法 ──────────────────────────────────────────────────────────────

    fun getAppVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    /** 当前已安装的 versionCode —— 用来拦「下载到旧安装包」的情况 */
    private fun currentVersionCode(context: Context): Long = try {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    } catch (_: Exception) { 0L }

    fun compareVersions(a: String, b: String): Int {
        val pa = a.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    fun formatSize(bytes: Long): String = when {
        bytes <= 0 -> "-"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }

    fun formatDate(iso: String): String = iso.substringBefore("T").ifEmpty { iso }

    /** 判断目标文件是否位于外部存储（需要 WRITE_EXTERNAL_STORAGE） */
    private fun isExternalPath(context: Context, file: File): Boolean {
        val path = file.absolutePath
        return !path.startsWith(context.filesDir.absolutePath) &&
                !path.startsWith(context.cacheDir.absolutePath) &&
                !path.startsWith(context.noBackupFilesDir.absolutePath)
    }

    /**
     * 下载落点。
     *
     * 小米盒子 Android 6（MIUI TV）的 PackageInstaller 只支持 `file://` 安装，
     * 且 content:// 和 PackageInstaller Session 均被 MIUI 策略拒绝。
     * 因此 APK **必须**落到公共可读路径（如 `Environment.DIRECTORY_DOWNLOADS`），
     * 才能让 `file://` 路径让安装器读到。
     *
     * 若外部存储不可用（极少数情况），则降级到 cacheDir（届时 content:// 是唯一希望）。
     */
    fun defaultDestFile(context: Context, info: UpdateInfo): File {
        val fileName = "TVBoxAndroid-v${info.latestVersion}-release.apk"
        // 优先写到 Downloads 公共目录：file:// 安装器可以访问，不需要 FileProvider
        val dlDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        if (dlDir != null && (dlDir.exists() || dlDir.mkdirs())) {
            return File(dlDir, fileName)
        }
        // 降级到私有 cacheDir
        val dir = File(context.cacheDir, "apk")
        dir.mkdirs()
        return File(dir, fileName)
    }
}
