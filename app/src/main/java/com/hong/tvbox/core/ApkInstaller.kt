package com.hong.tvbox.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装器（兼容 Android 4.4 ~ 14，重点照顾 Android 6 电视盒子）。
 *
 * 为什么单独抽出来：
 * 旧实现在 Android 6（API 23）上走 `Uri.fromFile()` + `FLAG_ACTIVITY_NEW_TASK`，
 * 而 APK 下载在 `Android/data/<pkg>/files/Download/` 这种应用私有目录里。
 * 小米盒子（MIUI TV）的系统安装器是独立进程，受 SELinux 与私有目录限制读不到该文件，
 * 轻则"解析包错误/应用未安装"，重则把异常抛回调用方 →
 * 而 `startActivity` 外面没有任何 try-catch，进程直接被杀 = 用户看到的"闪退"。
 *
 * 本类的策略：
 * 1. **优先 FileProvider**（`content://` + 读权限），API 21+ 全部支持，
 *    不依赖安装器能否读到私有路径 —— 由我们的 ContentProvider 代为读文件；
 * 2. 文件统一归置到 `cacheDir` 下（没有任何存储权限要求，FileProvider 必定可暴露）；
 * 3. 四级降级：content+INSTALL_PACKAGE → content+VIEW → file+INSTALL_PACKAGE → file+VIEW；
 * 4. 每一步都 catch 所有异常，绝不把异常交回主线程 —— 最差也是弹一句提示，不闪退；
 * 5. Android 6 补「未知来源」全局开关检查（O 之前是全局开关，不是按应用授权）。
 */
object ApkInstaller {

    private const val TAG = "TVBoxInstall"
    const val MIME_APK = "application/vnd.android.package-archive"

    /** 安装结果：失败时 detail 是给用户看的诊断信息（也方便电视上截图反馈） */
    data class InstallResult(
        val ok: Boolean,
        val message: String = "",
        val detail: String = ""
    )

    /** 单次安装尝试（名称 + 执行体） */
    private class Attempt(val name: String, val run: () -> Unit)

    /**
     * 执行安装。
     *
     * 注意：内部已做完整异常捕获，调用方**不会**收到异常；
     * 失败时返回 `ok=false`，请把 message/detail 展示给用户。
     */
    fun install(context: Context, apkFile: File): InstallResult {
        Log.d(TAG, "install: ${apkFile.absolutePath} exists=${apkFile.exists()} size=${apkFile.length()}")

        // ── 1. 基础校验 ───────────────────────────────────────────────
        if (!apkFile.exists()) {
            return InstallResult(false, "安装包不存在", apkFile.absolutePath)
        }
        if (apkFile.length() < 4096) {
            return InstallResult(false, "安装包不完整（${apkFile.length()} 字节）", apkFile.absolutePath)
        }
        // 用 ApkVerifier 做严格校验：zip 完整 + 能解析出包名版本。
        // 只查文件头 4 字节查不出"下载被截断"，那种包交给系统安装器就是「解析包错误」。
        val vr = ApkVerifier.verify(context, apkFile, expectPackage = context.packageName)
        if (!vr.ok) {
            Log.e(TAG, "安装包校验失败: ${vr.reason}")
            return InstallResult(
                false,
                "安装包不可用：${vr.reason}\n建议重新下载。",
                apkFile.absolutePath
            )
        }

        // ── 2. 归置到 FileProvider 可暴露的位置 ────────────────────────
        val file = try {
            ensureExposable(context, apkFile)
        } catch (e: Exception) {
            Log.w(TAG, "归置文件失败，沿用原路径", e)
            apkFile
        }

        // ── 3. 五级降级尝试 ───────────────────────────────────────────
        val errors = mutableListOf<String>()

        val attempts = listOf(
            // 优先级 1：file:// 公共路径（Downloads）— 小米盒子必须走这条
            Attempt("file+VIEW") { launchWithFile(context, file, false) },
            Attempt("file+INSTALL_PACKAGE") { launchWithFile(context, file, true) },
            // 优先级 2：content:// + FileProvider — 其他 ROM 可用
            Attempt("content+VIEW") { launchWithContent(context, file, false) },
            Attempt("content+INSTALL_PACKAGE") { launchWithContent(context, file, true) },
            // 优先级 3：PackageInstaller Session — Android 8+ 备用，MIUI TV 不支持
            Attempt("PackageInstaller 会话") { installViaSession(context, file) }
        )

        for (a in attempts) {
            try {
                a.run()
                Log.d(TAG, "安装器已拉起：${a.name}")
                return InstallResult(true, a.name)
            } catch (e: Exception) {
                // 关键：这里必须吃掉异常，否则就是闪退
                val msg = "${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
                errors.add("${a.name} → $msg")
                Log.w(TAG, "安装方式失败 [${a.name}]", e)
            }
        }

        // ── 4. 全部失败：给出可操作的诊断 ────────────────────────────
        val unknownSourceOff = !canInstallFromUnknownSource(context)
        val head = if (unknownSourceOff) {
            "系统未允许安装未知来源应用。请到「设置 → 安全 → 安装未知来源应用」打开后重试。"
        } else {
            "系统安装器拒绝了本次安装请求。"
        }
        return InstallResult(false, head, errors.joinToString("\n"))
    }

    // ─── 拉起方式 ─────────────────────────────────────────────────────

    /** FileProvider（content://）方式：Android 4.4+ 全支持 */
    private fun launchWithContent(context: Context, file: File, useInstallAction: Boolean) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        Log.d(TAG, "content uri = $uri")

        val action = if (useInstallAction) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW
        val intent = Intent(action).apply {
            setDataAndType(uri, MIME_APK)
            // 必须显式授权，否则安装器通过 provider 读不到文件（Android 6 上也一样）
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分 ROM 需要把 URI 粘到 ClipData 才会把授权传给下游
            clipData = android.content.ClipData.newRawUri("apk", uri)
        }
        startSafely(context, intent)
    }

    /** file:// 方式：老 ROM 兜底（MIUI 老固件有时只认这个） */
    @Suppress("DEPRECATION")
    private fun launchWithFile(context: Context, file: File, useInstallAction: Boolean) {
        val uri = Uri.fromFile(file)
        Log.d(TAG, "file uri = $uri")

        val action = if (useInstallAction) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW
        val intent = Intent(action).apply {
            setDataAndType(uri, MIME_APK)
        }
        startSafely(context, intent)
    }

    /**
     * 系统 PackageInstaller 会话安装（API 21+，最强兜底）。
     *
     * 直接把 APK 字节流写进系统安装会话，由 system_server 解析并安装，
     * 完全不依赖设备上是否存在「应用安装器」这个 Activity。
     * 小米盒子这类精简固件上是唯一稳定可用的路径。
     */
    private fun installViaSession(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setSize(file.length())
        }

        val sessionId = installer.createSession(params)
        Log.d(TAG, "PackageInstaller sessionId=$sessionId")

        val session = installer.openSession(sessionId)
            ?: throw IllegalStateException("openSession 返回 null")

        try {
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { input ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                }
                session.fsync(out)
            }

            val resultIntent = Intent(context, ApkInstallReceiver::class.java)
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = android.app.PendingIntent.getBroadcast(
                context, sessionId, resultIntent, piFlags
            )
            session.commit(pending.intentSender)
            Log.d(TAG, "PackageInstaller 会话已提交")
        } finally {
            try { session.close() } catch (_: Exception) { }
        }
    }

    /**
     * 统一 startActivity。
     *
     * - context 是 Activity 时**不加** FLAG_ACTIVITY_NEW_TASK：
     *   加了会让当前页面进后台，MIUI TV 内存紧张时会直接回收 → 看起来像闪退。
     * - context 是 Application 等非 Activity 时才必须加，否则会抛
     *   AndroidRuntimeException("Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag")。
     */
    private fun startSafely(context: Context, intent: Intent) {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ─── 文件归置 ─────────────────────────────────────────────────────

    /**
     * 确保 APK 落在安装器读得到的位置。
     *
     * ⚠️ v1.3.16 踩坑教训：小米盒子（MIUI TV）的 PackageInstaller 只认 `file://`，
     * 而它（uid 10003）读不到我们的私有 cacheDir（uid 10041）。
     * 之前这里**无条件**把 APK 复制回 cache 再装，等于把 v1.3.12 的坑又踩了一遍
     * （下到公共目录 → 复制回私有 → 安装器 Permission denied →「检测安装包异常」）。
     *
     * 正确逻辑：
     * - 文件已在公共存储（/storage/emulated/0/...、/sdcard/...）→ **直接返回原文件**，
     *   PackageInstaller 的 gid 含 sdcard_rw，公共目录直接可读（本机实测通过）；
     * - 仅当文件在私有目录且 content:// 也不可用时，才退到 cache 副本 + content://。
     */
    private fun ensureExposable(context: Context, apkFile: File): File {
        val path = apkFile.absolutePath
        // 公共存储（含外部 SD）：安装器可读，绝不要再复制回私有目录
        if (path.startsWith("/storage/emulated/") || path.startsWith("/sdcard/") ||
            path.startsWith(android.os.Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard/")
        ) {
            Log.d(TAG, "APK 已在公共存储，直接安装原文件: $path")
            return apkFile
        }
        // 已经在 cacheDir 下则直接返回（走 content:// 路径）
        if (path.startsWith(context.cacheDir.absolutePath)) {
            return apkFile
        }
        // 其他位置（如内部 filesDir）：复制到 cache 供 FileProvider 暴露
        val cacheRoot = File(context.cacheDir, "apk")
        cacheRoot.mkdirs()
        val dest = File(cacheRoot, apkFile.name)
        try {
            if (dest.exists()) dest.delete()
            apkFile.copyTo(dest, overwrite = true)
            Log.d(TAG, "已复制到 cache: ${dest.absolutePath} (${dest.length()} bytes)")
            return dest
        } catch (e: Exception) {
            Log.w(TAG, "复制到 cache 失败，沿用原文件", e)
            return apkFile
        }
    }

    // ─── 未知来源 / 权限 ──────────────────────────────────────────────

    /**
     * 是否允许安装未知来源应用。
     * - Android 8.0+：按应用授权（canRequestPackageInstalls）
     * - Android 4.2~7.x：全局开关（Settings.Secure.INSTALL_NON_MARKET_APPS）
     */
    fun canInstallFromUnknownSource(context: Context): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                context.packageManager.canRequestPackageInstalls()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 0
                ) == 1

            else -> true
        }
    } catch (e: Exception) {
        // 个别 ROM 不认这个 key，别因此阻断安装，交给实际 startActivity 去试
        Log.w(TAG, "读取未知来源开关失败", e)
        true
    }

    /**
     * 跳转到「安装未知来源应用」设置页。
     * 小米盒子的安全设置页在 MIUI 上路径不一样，逐个试。
     */
    fun openInstallSettings(context: Context): Boolean {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            } else {
                // MIUI 特有：应用权限编辑页
                add(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                })
                add(Intent(Settings.ACTION_SECURITY_SETTINGS))
                add(Intent("android.settings.SECURITY_SETTINGS"))
            }
            add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }

        for (intent in candidates) {
            try {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.d(TAG, "已跳转设置: ${intent.action}")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "跳转失败 ${intent.action}: ${e.message}")
            }
        }
        return false
    }

    // ─── 工具 ─────────────────────────────────────────────────────────

    /** APK 本质是 ZIP，文件头必须是 PK\x03\x04 */
    fun isValidApk(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        } catch (e: Exception) {
            false
        }
    }
}
