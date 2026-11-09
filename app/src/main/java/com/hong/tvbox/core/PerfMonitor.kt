package com.hong.tvbox.core

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.view.Choreographer

/**
 * 性能监控（FPS / 内存 / CPU / 网络）
 */
class PerfMonitor(private val context: Context) {
    private var lastFrameTime = 0L
    private var frameCount = 0
    private var fps = 0

    private val choreographerCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            val now = System.currentTimeMillis()
            if (lastFrameTime == 0L) lastFrameTime = now
            val elapsed = now - lastFrameTime
            if (elapsed >= 1000) {
                fps = (frameCount * 1000 / elapsed).toInt()
                frameCount = 0
                lastFrameTime = now
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun startFpsMonitor() {
        Choreographer.getInstance().postFrameCallback(choreographerCallback)
    }

    fun getFps(): Int = fps

    fun getMemoryMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
    }

    fun getNativeMemoryMb(): Long {
        // 用 Runtime 估算，避免依赖 Debug 类
        return Runtime.getRuntime().totalMemory() / 1024 / 1024
    }

    fun getTotalMemoryMb(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem / 1024 / 1024
    }

    fun getNetworkRxBytes(): Long = TrafficStats.getUidRxBytes(Process.myUid())
    fun getNetworkTxBytes(): Long = TrafficStats.getUidTxBytes(Process.myUid())

    fun getSummary(): String {
        return "FPS=${getFps()} MEM=${getMemoryMb()}MB NATIVE=${getNativeMemoryMb()}MB"
    }
}
