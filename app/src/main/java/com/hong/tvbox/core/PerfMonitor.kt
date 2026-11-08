package com.hong.tvbox.core

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Debug
import android.view.Choreographer
import kotlin.math.max

/**
 * 性能监控器
 * 
 * 实时监控：FPS / 内存占用 / CPU 使用率 / 网络流量
 */
object PerfMonitor {
    
    private var context: Context? = null
    private var cpuStartTime = 0L
    private var cpuStartTick = 0L
    private var fpsCounter = 0
    private var lastFpsTime = 0L
    private var lastCpuUsage = 0f
    
    data class PerfStats(
        val fps: Int = 0,
        val memoryMb: Long = 0,
        val memoryPercent: Float = 0f,
        val cpuUsage: Float = 0f,
        val uploadBytes: Long = 0,
        val downloadBytes: Long = 0
    )
    
    fun init(ctx: Context) {
        context = ctx.applicationContext
    }
    
    fun getStats(): PerfStats {
        return PerfStats(
            fps = getFps(),
            memoryMb = getMemoryMb(),
            memoryPercent = getMemoryPercent(),
            cpuUsage = getCpuUsage(),
            uploadBytes = getNetworkUpload(),
            downloadBytes = getNetworkDownload()
        )
    }
    
    // ====== FPS 监控 ======
    
    private var currentFps = 0
    
    fun startFpsMonitoring() {
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                fpsCounter++
                val currentTime = System.currentTimeMillis()
                
                if (currentTime - lastFpsTime >= 1000) {
                    currentFps = fpsCounter
                    fpsCounter = 0
                    lastFpsTime = currentTime
                }
                
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }
    
    fun getFps(): Int = currentFps
    
    // ====== 内存监控 ======
    
    fun getMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        return usedMemory
    }
    
    fun getMemoryPercent(): Float {
        val ctx = context ?: return 0f
        val activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val totalMemory = memInfo.totalMem / 1024 / 1024
        val availableMemory = memInfo.availMem / 1024 / 1024
        val usedMemory = totalMemory - availableMemory
        
        return (usedMemory.toFloat() / totalMemory * 100).coerceIn(0f, 100f)
    }
    
    fun getNativeHeapMb(): Long {
        return Debug.getNativeHeap().summaryTotalMem / 1024 / 1024
    }
    
    fun getJavaHeapMb(): Long {
        return (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024
    }
    
    // ====== CPU 监控 ======
    
    fun getCpuUsage(): Float {
        try {
            val statFile = java.io.File("/proc/stat")
            if (!statFile.exists()) return 0f
            
            val lines = statFile.readLines()
            if (lines.isEmpty()) return 0f
            
            val tokens = lines[0].split(Regex("\\s+"))
            if (tokens.size < 5) return 0f
            
            val userTime = tokens[1].toLongOrNull() ?: return 0f
            val systemTime = tokens[3].toLongOrNull() ?: return 0f
            val idleTime = tokens[4].toLongOrNull() ?: return 0f
            
            val totalTime = userTime + systemTime + idleTime
            val workTime = userTime + systemTime
            
            val cpuUsage = if (totalTime > 0) {
                (workTime.toFloat() / totalTime * 100)
            } else {
                0f
            }
            
            lastCpuUsage = cpuUsage.coerceIn(0f, 100f)
            return lastCpuUsage
        } catch (e: Exception) {
            return lastCpuUsage
        }
    }
    
    // ====== 网络监控 ======
    
    fun getNetworkUpload(): Long {
        return try {
            TrafficStats.getTotalTxBytes() / 1024  // KB
        } catch (e: Exception) {
            0L
        }
    }
    
    fun getNetworkDownload(): Long {
        return try {
            TrafficStats.getTotalRxBytes() / 1024  // KB
        } catch (e: Exception) {
            0L
        }
    }
    
    // ====== 扩展信息 ======
    
    fun getThreadCount(): Int {
        return Thread.activeCount()
    }
    
    fun getUptime(): Long {
        return System.currentTimeMillis() / 1000
    }
    
    data class DetailedStats(
        val timestamp: Long,
        val uptime: Long,
        val fps: Int,
        val memoryHeapMb: Long,
        val memoryNativeMb: Long,
        val memoryPercent: Float,
        val cpuUsage: Float,
        val threadCount: Int,
        val uploadKb: Long,
        val downloadKb: Long
    )
    
    fun getDetailedStats(): DetailedStats {
        return DetailedStats(
            timestamp = System.currentTimeMillis(),
            uptime = getUptime(),
            fps = getFps(),
            memoryHeapMb = getJavaHeapMb(),
            memoryNativeMb = getNativeHeapMb(),
            memoryPercent = getMemoryPercent(),
            cpuUsage = getCpuUsage(),
            threadCount = getThreadCount(),
            uploadKb = getNetworkUpload(),
            downloadKb = getNetworkDownload()
        )
    }
    
    fun formatStats(stats: DetailedStats): String {
        return """
            ===== 性能监控数据 =====
            时间: ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(stats.timestamp))}
            运行时长: ${stats.uptime}s
            
            帧率: ${stats.fps} FPS
            Java 堆: ${stats.memoryHeapMb} MB
            Native: ${stats.memoryNativeMb} MB
            内存占用率: ${String.format("%.1f", stats.memoryPercent)}%
            
            CPU 使用率: ${String.format("%.1f", stats.cpuUsage)}%
            线程数: ${stats.threadCount}
            
            上行: ${stats.uploadKb} KB
            下行: ${stats.downloadKb} KB
            ========================
        """.trimIndent()
    }
}
