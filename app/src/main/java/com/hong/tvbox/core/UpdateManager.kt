package com.hong.tvbox.core

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * GitHub 版本更新管理器
 * 
 * 功能:
 * - 检查 GitHub Releases 最新版本
 * - 比较版本号（语义化版本）
 * - 下载 APK 并安装
 * - 支持后台下载与通知
 * 
 * 用法:
 * ```kotlin
 * val updateManager = UpdateManager(context)
 * val updateInfo = updateManager.checkUpdate()
 * if (updateInfo.hasUpdate) {
 *     updateManager.downloadAndInstall(updateInfo)
 * }
 * ```
 */
class UpdateManager(private val context: Context) {
    
    companion object {
        private const val GITHUB_OWNER = "xxl6097"
        private const val GITHUB_REPO = "TVBoxAndroid"
        private const val GITHUB_API_BASE = "https://api.github.com"
        
        // 当前应用版本（从 BuildConfig 读取）
        private val CURRENT_VERSION = android.os.Build.VERSION.SDK_INT.toString() // 临时占位
    }
    
    /**
     * 版本信息数据类
     */
    data class UpdateInfo(
        val hasUpdate: Boolean = false,
        val latestVersion: String = "",
        val currentVersion: String = "",
        val releaseNotes: String = "",
        val downloadUrl: String = "",
        val fileSize: Long = 0,
        val publishedAt: String = ""
    )
    
    /**
     * 检查更新
     * 
     * @return UpdateInfo 包含是否有更新和版本详情
     */
    suspend fun checkUpdate(): UpdateInfo = withContext(Dispatchers.IO) {
        try {
            // 获取最新 Release 信息
            val apiUrl = "$GITHUB_API_BASE/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val response = HttpClient.text(apiUrl, timeoutMs = 15000)
            
            val json = JSONObject(response)
            val latestVersion = json.optString("tag_name", "").removePrefix("v")
            val releaseNotes = json.optString("body", "")
            val publishedAt = json.optString("published_at", "")
            
            // 查找 APK 下载链接
            val assets = json.optJSONArray("assets")
            var downloadUrl = ""
            var fileSize = 0L
            
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    
                    // 查找 release APK
                    if (name.endsWith(".apk") && name.contains("release", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        fileSize = asset.optLong("size", 0)
                        break
                    }
                }
                
                // 如果没找到 release APK，使用第一个 APK
                if (downloadUrl.isEmpty() && assets.length() > 0) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            fileSize = asset.optLong("size", 0)
                            break
                        }
                    }
                }
            }
            
            // 获取当前应用版本
            val currentVersion = getCurrentVersion()
            
            // 比较版本号
            val hasUpdate = compareVersions(latestVersion, currentVersion) > 0
            
            UpdateInfo(
                hasUpdate = hasUpdate,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                fileSize = fileSize,
                publishedAt = publishedAt
            )
        } catch (e: Exception) {
            Logger.d(msg = "检查更新失败: ${e.message}", t = e)
            UpdateInfo(hasUpdate = false)
        }
    }
    
    /**
     * 下载并安装更新
     * 
     * @param updateInfo 更新信息
     * @return 下载任务 ID
     */
    fun downloadAndInstall(updateInfo: UpdateInfo): Long {
        if (updateInfo.downloadUrl.isEmpty()) {
            throw IllegalArgumentException("下载链接为空")
        }
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        // 下载目标路径
        val fileName = "TVBoxAndroid_${updateInfo.latestVersion}.apk"
        val destination = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        
        // 如果文件已存在，先删除
        if (destination.exists()) {
            destination.delete()
        }
        
        // 创建下载请求
        val request = DownloadManager.Request(Uri.parse(updateInfo.downloadUrl)).apply {
            setTitle("夜帧影视更新")
            setDescription("正在下载 v${updateInfo.latestVersion}")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(destination))
            setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
        }
        
        // 启动下载
        val downloadId = downloadManager.enqueue(request)
        
        // 注册下载完成监听器
        registerDownloadCompleteReceiver(downloadId, destination)
        
        return downloadId
    }
    
    /**
     * 注册下载完成广播接收器
     */
    private fun registerDownloadCompleteReceiver(downloadId: Long, apkFile: File) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    // 下载完成，触发安装
                    installApk(apkFile)
                    
                    // 取消注册
                    try {
                        context.unregisterReceiver(this)
                    } catch (e: Exception) {
                        // 忽略重复取消注册的异常
                    }
                }
            }
        }
        
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(receiver, filter)
    }
    
    /**
     * 安装 APK
     */
    private fun installApk(apkFile: File) {
        if (!apkFile.exists()) {
            Logger.d(msg = "APK 文件不存在: ${apkFile.absolutePath}")
            return
        }
        
        val intent = Intent(Intent.ACTION_VIEW)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0+ 需要使用 FileProvider
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else {
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
        }
        
        context.startActivity(intent)
    }
    
    /**
     * 获取当前应用版本
     */
    private fun getCurrentVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }
    
    /**
     * 比较版本号（语义化版本）
     * 
     * @return 正数: v1 > v2, 0: v1 == v2, 负数: v1 < v2
     */
    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        
        val maxLength = maxOf(parts1.size, parts2.size)
        
        for (i in 0 until maxLength) {
            val p1 = parts1.getOrNull(i) ?: 0
            val p2 = parts2.getOrNull(i) ?: 0
            
            if (p1 != p2) {
                return p1 - p2
            }
        }
        
        return 0
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        }
    }
}
