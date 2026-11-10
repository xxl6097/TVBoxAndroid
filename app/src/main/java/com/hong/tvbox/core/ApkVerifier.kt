package com.hong.tvbox.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * APK 安装前校验。
 *
 * 为什么需要：旧实现只检查文件头 4 字节是不是 `PK\x03\x04`，
 * 这只能排除"代理返回 HTML 错误页"，**排除不了文件被截断**。
 * 下载过程中连接中断、代理提前关闭 chunked 流时，`read()` 可能直接返回 -1（而不是抛异常），
 * 于是一个只下到一半的文件也"校验通过"，用户点安装就得到
 * 「解析包错误 / There is a problem parsing the package」。
 *
 * 这里做三层校验，逐层收紧：
 *   1. `ZipFile` 能打开 —— 说明 zip 中央目录完整（截断的包在这里就会失败）
 *   2. 能读到 `AndroidManifest.xml` 条目 —— 说明是 Android 包而不是随便一个 zip
 *   3. `PackageManager.getPackageArchiveInfo` 能解析出包名/版本 —— 最权威，等同于系统安装器的解析结果
 *
 * 另外拦一类坑：代理/CDN 缓存串了，返回了**别的应用**的 APK。
 * 这类包能通过 1、2，但包名对不上，安装时必然失败。
 */
object ApkVerifier {

    private const val TAG = "TVBoxVerify"

    data class Result(
        val ok: Boolean,
        val reason: String = "",
        val packageName: String = "",
        val versionName: String = "",
        val versionCode: Long = 0L
    )

    /**
     * 校验 APK。
     *
     * @param expectPackage   期望的包名（传 null 表示不校验）
     * @param minVersionCode  要求的最低 versionCode（0 表示不校验）；用于确认下到的确实是"更新版"
     */
    fun verify(
        context: Context,
        file: File,
        expectPackage: String? = null,
        minVersionCode: Long = 0L
    ): Result {
        if (!file.exists()) return Result(false, "文件不存在")
        if (file.length() < 32 * 1024) {
            return Result(false, "文件太小（${file.length()} 字节），下载可能中断")
        }

        // ── 1. zip 结构完整（截断的包在这里就会抛异常）──────────────
        try {
            ZipFile(file).use { zip ->
                // ── 2. 必须是 Android 包 ────────────────────────────
                if (zip.getEntry("AndroidManifest.xml") == null) {
                    return Result(false, "不是 Android 安装包（缺少 AndroidManifest.xml）")
                }
                // 顺带确认真能读出内容，而不只是目录里有名字
                zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.read() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "zip 校验失败: ${e.javaClass.simpleName} ${e.message}")
            return Result(false, "安装包不完整或已损坏（${e.javaClass.simpleName}）")
        }

        // ── 3. 交给系统解析（与安装器同源）─────────────────────────
        val info = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getPackageArchiveInfo 异常", e)
            null
        }

        if (info == null) {
            return Result(false, "系统无法解析该安装包（可能签名/格式不兼容本机）")
        }

        val pkg = info.packageName ?: ""
        val vName = info.versionName ?: ""
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

        if (expectPackage != null && pkg != expectPackage) {
            return Result(
                false,
                "包名不符：期望 $expectPackage，实际 $pkg（可能下载到了别的应用）",
                pkg, vName, vCode
            )
        }
        if (minVersionCode > 0 && vCode < minVersionCode) {
            return Result(
                false,
                "版本过低：当前 versionCode=$vCode，要求 ≥$minVersionCode（下到了旧安装包）",
                pkg, vName, vCode
            )
        }

        Log.d(TAG, "校验通过: $pkg $vName ($vCode) ${file.length()} bytes")
        return Result(true, "", pkg, vName, vCode)
    }

    /** 只要一个布尔结果时用这个 */
    fun isValid(context: Context, file: File, expectPackage: String? = null): Boolean =
        verify(context, file, expectPackage).ok
}
