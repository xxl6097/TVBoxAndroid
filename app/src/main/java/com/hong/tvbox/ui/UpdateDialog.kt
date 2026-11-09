package com.hong.tvbox.ui

import android.app.AlertDialog
import android.content.Context
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.hong.tvbox.core.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 版本更新对话框
 *
 * 功能：
 * - 显示版本信息、更新说明、文件大小
 * - 实时下载进度条（百分比 + MB）
 * - 支持 GitHub 代理加速
 * - 下载完成后自动触发安装
 */
class UpdateDialog(
    private val context: Context,
    private val updateInfo: UpdateManager.UpdateInfo
) {

    private var dialog: AlertDialog? = null
    private var downloadDialog: AlertDialog? = null

    companion object {
        private fun dp(context: Context, value: Int): Int {
            return (value * context.resources.displayMetrics.density + 0.5f).toInt()
        }
    }

    /**
     * 显示更新对话框（版本信息 + 更新内容）
     */
    fun show() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 16))
        }

        // 标题：发现新版本 vX.X.X
        container.addView(TextView(context).apply {
            text = "发现新版本 v${updateInfo.latestVersion}"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 12))
        })

        // 当前版本 vs 最新版本
        container.addView(TextView(context).apply {
            text = "当前: v${updateInfo.currentVersion}  →  最新: v${updateInfo.latestVersion}"
            textSize = 13f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 8))
        })

        // 文件大小 & 发布时间
        val metaText = buildString {
            if (updateInfo.fileSize > 0) append("大小: ${UpdateManager.formatSize(updateInfo.fileSize)}")
            if (updateInfo.publishedAt.isNotEmpty()) {
                if (isNotEmpty()) append("  |  ")
                append("发布: ${UpdateManager.formatDate(updateInfo.publishedAt)}")
            }
        }
        if (metaText.isNotEmpty()) {
            container.addView(TextView(context).apply {
                text = metaText
                textSize = 12f
                setTextColor(0x80FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(context, 16))
            })
        }

        // 更新内容（可滚动）
        if (updateInfo.releaseNotes.isNotEmpty()) {
            container.addView(TextView(context).apply {
                text = "更新内容："
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, dp(context, 6))
            })

            container.addView(TextView(context).apply {
                text = formatReleaseNotes(updateInfo.releaseNotes)
                textSize = 12f
                setTextColor(0xB3FFFFFF.toInt())
                movementMethod = ScrollingMovementMethod()  // 启用滚动
                maxLines = 12
                setPadding(dp(context, 8), 0, dp(context, 8), 0)
                isVerticalScrollBarEnabled = true
            })
        }

        // 创建对话框
        dialog = AlertDialog.Builder(context)
            .setView(container)
            .setPositiveButton("立即更新") { _, _ ->
                startDownload()
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(true)
            .create()

        dialog?.show()
    }

    /**
     * 开始下载（带实时进度）
     */
    private fun startDownload() {
        if (context !is LifecycleOwner) {
            showSimpleDownloadDialog()
            return
        }

        // 创建下载进度对话框
        val progressContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 20))
        }

        // 标题
        val titleView = TextView(context).apply {
            text = "正在下载更新"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 16))
        }
        progressContainer.addView(titleView)

        // 进度条
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 8)
            ).apply { setMargins(0, 0, 0, dp(context, 12)) }
            max = 100
            progress = 0
        }
        progressContainer.addView(progressBar)

        // 进度文字（百分比 + MB）
        val progressText = TextView(context).apply {
            text = "0% (0.0 MB / ${String.format("%.1f", updateInfo.fileSize / 1024f / 1024f)} MB)"
            textSize = 12f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        progressContainer.addView(progressText)

        // 代理提示
        val proxyHint = TextView(context).apply {
            val proxyName = UpdateManager.PROXY_LIST.getOrNull(UpdateManager.selectedProxyIndex)
                ?.takeIf { it.isNotEmpty() }
                ?.let { it.removeSuffix("/").substringAfterLast("/").ifEmpty { it } }
                ?: "直连"
            text = "使用代理: $proxyName"
            textSize = 11f
            setTextColor(0x80FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 8), 0, 0)
        }
        progressContainer.addView(proxyHint)

        downloadDialog = AlertDialog.Builder(context)
            .setView(progressContainer)
            .setCancelable(false)
            .create()

        downloadDialog?.show()

        // 启动下载协程
        context.lifecycleScope.launch {
            try {
                val destFile = UpdateManager.defaultDestFile(context, updateInfo)

                val apkFile = UpdateManager.downloadApk(
                    context = context,
                    info = updateInfo,
                    destFile = destFile
                ) { percent, downloadedMb, totalMb ->
                    // 实时更新进度（主线程）
                    progressBar.progress = percent
                    progressText.text = "$percent% (${String.format("%.1f", downloadedMb)} MB / ${String.format("%.1f", totalMb)} MB)"
                }

                downloadDialog?.dismiss()

                // 下载完成，弹出安装确认
                showInstallConfirm(apkFile)

            } catch (e: Exception) {
                downloadDialog?.dismiss()
                AlertDialog.Builder(context)
                    .setTitle("下载失败")
                    .setMessage("无法下载更新:\n${e.message}\n\n请检查网络后重试。")
                    .setPositiveButton("确定", null)
                    .show()
            }
        }
    }

    /**
     * 简易下载提示（不支持 LifecycleOwner 时的降级方案）
     */
    private fun showSimpleDownloadDialog() {
        AlertDialog.Builder(context)
            .setTitle("开始下载")
            .setMessage("正在后台下载更新，请稍候...\n\n（当前环境不支持实时进度显示）")
            .setPositiveButton("确定", null)
            .show()
    }

    /**
     * 显示安装确认对话框
     */
    private fun showInstallConfirm(apkFile: File) {
        val msg = buildString {
            append("APK 已下载完成，点「立即安装」进行安装。\n\n")
            append("文件: ${apkFile.name}\n\n")
            append("⚠️  如果点安装后没有反应，请在系统设置中开启\n「安装未知应用」权限后重试。")
        }
        AlertDialog.Builder(context)
            .setTitle("下载完成")
            .setMessage(msg)
            .setPositiveButton("立即安装") { _, _ ->
                UpdateManager.installApk(context, apkFile)
            }
            .setNegativeButton("稍后安装", null)
            .setCancelable(true)
            .show()
    }

    /**
     * 格式化 Release Notes（Markdown → 纯文本，保留结构）
     */
    private fun formatReleaseNotes(markdown: String): String {
        return markdown
            .replace(Regex("^###?\\s+(.+)$", RegexOption.MULTILINE), "◆ $1")  // 标题
            .replace(Regex("^##\\s+(.+)$", RegexOption.MULTILINE), "━ $1 ━")  // 大标题
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // 移除加粗
            .replace(Regex("\\*(.+?)\\*"), "$1")        // 移除斜体
            .replace(Regex("^-\\s+", RegexOption.MULTILINE), "  • ")  // 列表项
            .replace(Regex("^\\*\\s+", RegexOption.MULTILINE), "  • ")
            .replace(Regex("`(.+?)`"), "$1")  // 移除行内代码
            .take(600)  // 限制长度
            .trim()
    }

    /**
     * 关闭对话框
     */
    fun dismiss() {
        dialog?.dismiss()
        downloadDialog?.dismiss()
    }
}
