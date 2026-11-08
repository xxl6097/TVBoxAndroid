package com.hong.tvbox.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * 统一日志系统
 * 
 * 特性：
 * - Debug 模式输出到 Logcat + 本地文件
 * - Release 模式输出到本地文件（7 天轮转）
 * - 链路追踪 ID（requestId），便于跨模块日志关联
 * - 内存队列异步写入，不阻塞主线程
 * - 支持日志级别过滤
 */
object Logger {
    private const val TAG = "TVBox"
    private const val MAX_FILE_SIZE = 2 * 1024 * 1024  // 2MB
    private const val MAX_RETENTION_DAYS = 7
    private const val FLUSH_INTERVAL_MS = 2000L
    
    private var context: Context? = null
    private var logDir: File? = null
    private var isDebug = false
    private var minLevel = LogLevel.DEBUG
    
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()
    private val lastFlushTime = AtomicLong(0)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private val flushThread = Thread {
        while (true) {
            try {
                Thread.sleep(FLUSH_INTERVAL_MS)
                flush()
            } catch (e: InterruptedException) {
                break
            }
        }
    }.apply { isDaemon = true; name = "Logger-Flush" }
    
    enum class LogLevel(val priority: Int) {
        VERBOSE(2), DEBUG(3), INFO(4), WARN(5), ERROR(6)
    }
    
    private data class LogEntry(
        val level: LogLevel,
        val tag: String,
        val msg: String,
        val throwable: Throwable?,
        val requestId: String?,
        val timestamp: Long
    )
    
    fun init(ctx: Context, debug: Boolean = false) {
        context = ctx.applicationContext
        isDebug = debug
        logDir = File(ctx.filesDir, "logs").apply { mkdirs() }
        
        // 清理过期日志
        cleanOldLogs()
        
        // 启动异步 flush 线程
        if (!flushThread.isAlive) flushThread.start()
    }
    
    fun setMinLevel(level: LogLevel) {
        minLevel = level
    }
    
    // ====== 公共 API ======
    
    fun v(tag: String = TAG, msg: String, t: Throwable? = null) {
        log(LogLevel.VERBOSE, tag, msg, t)
    }
    
    fun d(tag: String = TAG, msg: String, t: Throwable? = null) {
        log(LogLevel.DEBUG, tag, msg, t)
    }
    
    fun i(tag: String = TAG, msg: String, t: Throwable? = null) {
        log(LogLevel.INFO, tag, msg, t)
    }
    
    fun w(tag: String = TAG, msg: String, t: Throwable? = null) {
        log(LogLevel.WARN, tag, msg, t)
    }
    
    fun e(tag: String = TAG, msg: String, t: Throwable? = null) {
        log(LogLevel.ERROR, tag, msg, t)
    }
    
    // ====== 链路追踪 ======
    
    private val threadRequestId = ThreadLocal<String>()
    
    fun beginRequest(requestId: String? = null): String {
        val id = requestId ?: generateRequestId()
        threadRequestId.set(id)
        return id
    }
    
    fun endRequest() {
        threadRequestId.remove()
    }
    
    fun currentRequestId(): String? = threadRequestId.get()
    
    private fun generateRequestId(): String {
        return System.currentTimeMillis().toString(36) + "-" + Thread.currentThread().id.toString(36)
    }
    
    // ====== 内部实现 ======
    
    private fun log(level: LogLevel, tag: String, msg: String, t: Throwable?) {
        if (level.priority < minLevel.priority) return
        
        val entry = LogEntry(
            level = level,
            tag = tag,
            msg = msg,
            throwable = t,
            requestId = threadRequestId.get(),
            timestamp = System.currentTimeMillis()
        )
        
        // Logcat 输出
        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, msg, t)
            LogLevel.DEBUG -> Log.d(tag, msg, t)
            LogLevel.INFO -> Log.i(tag, msg, t)
            LogLevel.WARN -> Log.w(tag, msg, t)
            LogLevel.ERROR -> Log.e(tag, msg, t)
        }
        
        // 入队异步写文件
        logQueue.offer(entry)
        
        // 触发 flush
        val now = System.currentTimeMillis()
        if (now - lastFlushTime.get() > FLUSH_INTERVAL_MS) {
            lastFlushTime.set(now)
            flush()
        }
    }
    
    @Synchronized
    private fun flush() {
        if (logQueue.isEmpty()) return
        
        val dir = logDir ?: return
        val dateStr = fileDateFormat.format(Date())
        val logFile = File(dir, "$dateStr.log")
        
        try {
            logFile.bufferedWriter().use { writer ->
                while (logQueue.isNotEmpty()) {
                    val entry = logQueue.poll() ?: break
                    val timeStr = dateFormat.format(Date(entry.timestamp))
                    val levelStr = entry.level.name.first()
                    val reqStr = entry.requestId?.let { " [$it]" } ?: ""
                    writer.write("$timeStr $levelStr/${entry.tag}$reqStr: ${entry.msg}\n")
                    
                    if (entry.throwable != null) {
                        writer.write(stackTraceString(entry.throwable))
                        writer.write("\n")
                    }
                }
                writer.flush()
            }
            
            // 文件过大时轮转
            if (logFile.length() > MAX_FILE_SIZE) {
                rotateLog(logFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to flush logs", e)
        }
    }
    
    private fun rotateLog(file: File) {
        val rotated = File(file.parentFile, file.name + ".old")
        rotated.delete()
        file.renameTo(rotated)
    }
    
    private fun cleanOldLogs() {
        val dir = logDir ?: return
        val cutoff = System.currentTimeMillis() - MAX_RETENTION_DAYS * 24 * 60 * 60 * 1000
        
        dir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }
    
    private fun stackTraceString(t: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        t.printStackTrace(pw)
        return sw.toString()
    }
    
    // ====== 日志查询 API ======
    
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }
    
    fun readLogFile(file: File, maxLines: Int = 500): String {
        if (!file.exists()) return ""
        return file.readLines().takeLast(maxLines).joinToString("\n")
    }
    
    fun clearAllLogs() {
        val dir = logDir ?: return
        dir.listFiles()?.forEach { it.delete() }
    }
}
