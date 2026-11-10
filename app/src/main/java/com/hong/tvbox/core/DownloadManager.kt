package com.hong.tvbox.core

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 简易下载管理器（原生 HttpURLConnection，无需 ExoPlayer DownloadService）
 * 支持断点续传、进度回调、取消。
 */
object DownloadManager {
    private const val TAG = "TVBoxDownload"

    data class Task(
        val id: String,
        val url: String,
        val title: String,
        var progress: Int = 0,
        var status: Status = Status.PENDING,
        var localPath: String = "",
        var errorMsg: String = ""
    )
    enum class Status { PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

    private val tasks = mutableListOf<Task>()

    suspend fun enqueue(
        context: Context,
        url: String,
        title: String,
        onProgress: (Int) -> Unit = {},
        onDone: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val id = url.hashCode().toString()
        val existing = tasks.find { it.id == id }
        if (existing?.status == Status.DOWNLOADING || existing?.status == Status.COMPLETED) return@withContext

        val task = Task(id = id, url = url, title = title)
        tasks.removeAll { it.id == id }
        tasks.add(task)
        task.status = Status.DOWNLOADING

        try {
            val destFile = getDestFile(context, title, url)
            // LenientSsl：Android 6 根证书库陈旧，老 CA 的站点会直接 SSL 握手失败
            val conn = LenientSsl.open(url)
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "TVBox/1.2.0")
            if (destFile.exists() && destFile.length() > 0)
                conn.setRequestProperty("Range", "bytes=${destFile.length()}-")
            conn.connect()
            val code = conn.responseCode
            // 416：本地文件已 >= 远端大小（上次可能已下完），清空重来
            if (code == 416) {
                conn.disconnect()
                destFile.delete()
                task.status = Status.FAILED
                task.errorMsg = "本地文件已完整（HTTP 416）"
                withContext(Dispatchers.Main) { onError("该文件看起来已经下载完整了") }
                return@withContext
            }
            if (code != 200 && code != 206) throw Exception("HTTP $code")
            // ⚠️ 不能用 conn.contentLengthLong（API 24）：
            // Android 6 上抛 NoSuchMethodError(Error)，catch(Exception) 接不住 → 闪退
            val resumeFrom = if (code == 206) destFile.length() else 0L
            val total = HttpCompat.resolveContentLength(conn, resumeFrom, code)
            val inp = conn.inputStream
            val out = FileOutputStream(destFile, code == 206)
            val buf = ByteArray(65536)
            var downloaded = resumeFrom
            var read: Int
            while (inp.read(buf).also { read = it } != -1) {
                if (task.status == Status.CANCELLED) { out.close(); inp.close(); return@withContext }
                out.write(buf, 0, read); downloaded += read
                if (total > 0) {
                    val pct = (downloaded * 100 / total).toInt()
                    if (pct != task.progress) {
                        task.progress = pct
                        withContext(Dispatchers.Main) { onProgress(pct) }
                    }
                }
            }
            out.flush(); out.close(); inp.close()
            task.status = Status.COMPLETED; task.localPath = destFile.absolutePath
            withContext(Dispatchers.Main) { onDone(destFile.absolutePath) }
        } catch (e: CancellationException) {
            task.status = Status.CANCELLED
            throw e
        } catch (e: Throwable) {
            // Throwable：Error（如 NoSuchMethodError）也必须兜住，否则冒到主线程就是闪退
            task.status = Status.FAILED; task.errorMsg = e.message ?: e.javaClass.simpleName
            Log.e(TAG, "下载失败: ${e.javaClass.simpleName} ${e.message}", e)
            withContext(Dispatchers.Main) { onError(e.message ?: "下载失败（${e.javaClass.simpleName}）") }
        }
    }

    fun cancel(url: String) { tasks.find { it.url == url }?.status = Status.CANCELLED }
    fun getTask(url: String): Task? = tasks.find { it.url == url }
    fun getAllTasks(): List<Task> = tasks.toList()
    fun clearCompleted() = tasks.removeAll { it.status == Status.COMPLETED }
    fun deleteFile(url: String): Boolean {
        val t = tasks.find { it.url == url } ?: return false
        val ok = File(t.localPath).delete(); tasks.remove(t); return ok
    }
    fun getCacheSize(context: Context): Long =
        getDownloadDir(context).walkTopDown().sumOf { if (it.isFile) it.length() else 0L }
    fun clearAll(context: Context) { getDownloadDir(context).deleteRecursively(); tasks.clear() }

    private fun getDestFile(context: Context, title: String, url: String): File {
        val ext = url.substringAfterLast('.').lowercase()
            .let { if (it.length <= 4 && it.matches(Regex("[a-z0-9]+"))) ".$it" else ".mp4" }
        val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val dir = getDownloadDir(context); dir.mkdirs()
        return File(dir, "$safe$ext")
    }
    private fun getDownloadDir(context: Context): File {
        val ext = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return if (ext != null && ext.canWrite()) File(ext, "TVBox") else File(context.cacheDir, "downloads")
    }
}
