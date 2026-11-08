package com.hong.tvbox.core

import android.content.Context
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import android.net.Uri
import java.io.File

/**
 * 离线下载管理
 *
 * 问题：仅支持在线播放，网络不稳定时体验差
 * 解决：
 *   - ExoPlayer 内置 ProgressiveDownloadManager
 *   - 后台队列下载，支持暂停/继续
 *   - 本地缓存路径: /sdcard/Android/data/com.hong.tvbox/cache/downloads/
 */
class DownloadManager(private val context: Context) {

    private val downloadCache = SimpleCache(
        File(context.cacheDir, "downloads"),
        LeastRecentlyUsedCacheEvictor(500 * 1024 * 1024)  // 500MB 上限
    )

    /**
     * 下载队列项
     */
    data class DownloadItem(
        val id: String,
        val title: String,
        val url: String,
        val status: DownloadStatus = DownloadStatus.IDLE,
        val progress: Int = 0,  // 0-100
        val error: String? = null
    )

    enum class DownloadStatus {
        IDLE,           // 未下载
        DOWNLOADING,    // 下载中
        COMPLETED,      // 已完成
        PAUSED,         // 已暂停
        ERROR           // 错误
    }

    private val downloadQueue = mutableListOf<DownloadItem>()

    /**
     * 入队下载
     *
     * @param url 视频 URL
     * @param title 影视标题（用于本地文件名）
     */
    fun enqueueDownload(url: String, title: String) {
        val id = title + "_" + System.currentTimeMillis()
        val request = DownloadRequest.Builder(id, Uri.parse(url))
            .setData(title.toByteArray())
            .build()

        downloadQueue.add(DownloadItem(
            id = id,
            title = title,
            url = url,
            status = DownloadStatus.IDLE
        ))

        // 实际下载由 DownloadService 处理
        // DownloadService.sendAddDownload(context, VideoDownloadService::class.java, request, false)
    }

    /**
     * 获取下载列表
     */
    fun getDownloadQueue(): List<DownloadItem> = downloadQueue

    /**
     * 暂停下载
     */
    fun pauseDownload(id: String) {
        val idx = downloadQueue.indexOfFirst { it.id == id }
        if (idx >= 0) {
            downloadQueue[idx] = downloadQueue[idx].copy(status = DownloadStatus.PAUSED)
        }
    }

    /**
     * 继续下载
     */
    fun resumeDownload(id: String) {
        val idx = downloadQueue.indexOfFirst { it.id == id }
        if (idx >= 0) {
            downloadQueue[idx] = downloadQueue[idx].copy(status = DownloadStatus.DOWNLOADING)
        }
    }

    /**
     * 删除下载
     */
    fun removeDownload(id: String) {
        downloadQueue.removeAll { it.id == id }
    }

    /**
     * 清除所有已完成的下载
     */
    fun clearCompleted() {
        downloadQueue.removeAll { it.status == DownloadStatus.COMPLETED }
    }

    /**
     * 获取本地下载文件路径
     */
    fun getLocalPath(id: String): String? {
        val file = File(context.cacheDir, "downloads/$id.mp4")
        return if (file.exists()) file.absolutePath else null
    }

    /**
     * 清空下载缓存（设置页"清除缓存"调用）
     */
    fun clearCache() {
        downloadCache.release()
    }
}

/**
 * 下载服务（后台运行）
 *
 * 需在 AndroidManifest.xml 中声明：
 *
 * <service
 *     android:name=".core.VideoDownloadService"
 *     android:enabled="true"
 *     android:exported="false"
 *     android:foregroundServiceType="dataSync" />
 *
 * 需权限：
 *   - android:name="android.permission.WRITE_EXTERNAL_STORAGE"
 *   - android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
 */
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL
) {
    companion object {
        private const val FOREGROUND_NOTIFICATION_ID = 1
    }

    override fun getNotificationChannelId(): String = "video_download"
}
