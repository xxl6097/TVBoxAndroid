package com.hong.tvbox

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃兜底。
 *
 * 电视盒子出问题时用户拿不到 logcat，只说「一打开就退」。
 * 这里把崩溃堆栈写到 filesDir/crash.log，设置页可以查看并复制出来，
 * 排障效率比让用户描述现象高得多。
 */
class TvBoxApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler(this)
    }

    companion object {
        private const val FILE_NAME = "crash.log"

        fun crashFile(context: Context): File = File(context.filesDir, FILE_NAME)

        fun readCrash(context: Context): String {
            val f = crashFile(context)
            return if (f.exists()) f.readText() else ""
        }

        fun clearCrash(context: Context) {
            crashFile(context).delete()
        }

        private fun installCrashHandler(context: Context) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))

                    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
                    val header = buildString {
                        append("时间: ").append(time).append('\n')
                        append("设备: ").append(Build.MANUFACTURER).append(' ')
                        append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE).append('\n')
                        append("线程: ").append(thread.name).append('\n')
                        append("----\n")
                    }
                    crashFile(context).writeText(header + sw.toString())
                } catch (e: Exception) {
                    // 写日志本身失败就算了，别掩盖原始崩溃
                }

                previous?.uncaughtException(thread, throwable)
            }
        }
    }
}
