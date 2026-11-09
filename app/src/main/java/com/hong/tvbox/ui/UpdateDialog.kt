package com.hong.tvbox.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import com.hong.tvbox.core.UpdateManager

/**
 * 更新对话框
 * 
 * 显示版本更新信息，并提供下载安装按钮
 */
class UpdateDialog(
    private val context: Context,
    private val updateInfo: UpdateManager.UpdateInfo
) {
    
    private var dialog: AlertDialog? = null
    private val updateManager = UpdateManager(context)
    
    /**
     * 显示更新对话框
     */
    fun show() {
        // 构建对话框布局
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        
        // 标题
        val titleView = TextView(context).apply {
            text = "发现新版本 v${updateInfo.latestVersion}"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        container.addView(titleView)
        
        // 当前版本 vs 最新版本
        val versionView = TextView(context).apply {
            text = "当前版本: v${updateInfo.currentVersion}\n最新版本: v${updateInfo.latestVersion}"
            textSize = 14f
            setTextColor(0xB3FFFFFF.toInt())
            setPadding(0, 0, 0, 16)
        }
        container.addView(versionView)
        
        // 文件大小和发布时间
        if (updateInfo.fileSize > 0) {
            val sizeView = TextView(context).apply {
                text = "文件大小: ${updateManager.formatFileSize(updateInfo.fileSize)}"
                textSize = 13f
                setTextColor(0x80FFFFFF.toInt())
                setPadding(0, 0, 0, 8)
            }
            container.addView(sizeView)
        }
        
        if (updateInfo.publishedAt.isNotEmpty()) {
            val dateView = TextView(context).apply {
                text = "发布时间: ${formatDate(updateInfo.publishedAt)}"
                textSize = 13f
                setTextColor(0x80FFFFFF.toInt())
                setPadding(0, 0, 0, 16)
            }
            container.addView(dateView)
        }
        
        // 更新说明（Release Notes）
        if (updateInfo.releaseNotes.isNotEmpty()) {
            val notesTitle = TextView(context).apply {
                text = "更新内容:"
                textSize = 15f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 0, 0, 8)
            }
            container.addView(notesTitle)
            
            val notesView = TextView(context).apply {
                text = formatReleaseNotes(updateInfo.releaseNotes)
                textSize = 13f
                setTextColor(0xB3FFFFFF.toInt())
                maxLines = 10
                setPadding(16, 0, 0, 0)
            }
            container.addView(notesView)
        }
        
        // 创建对话框
        dialog = AlertDialog.Builder(context)
            .setView(container)
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall()
            }
            .setNegativeButton("稍后再说", null)
            .setCancelable(true)
            .create()
        
        dialog?.show()
    }
    
    /**
     * 下载并安装更新
     */
    private fun downloadAndInstall() {
        try {
            val downloadId = updateManager.downloadAndInstall(updateInfo)
            
            // 显示下载提示
            AlertDialog.Builder(context)
                .setTitle("开始下载")
                .setMessage("正在后台下载更新，完成后会自动弹出安装提示。\n\n您可以在通知栏查看下载进度。")
                .setPositiveButton("确定", null)
                .show()
            
        } catch (e: Exception) {
            AlertDialog.Builder(context)
                .setTitle("下载失败")
                .setMessage("无法下载更新: ${e.message}")
                .setPositiveButton("确定", null)
                .show()
        }
    }
    
    /**
     * 格式化 Release Notes（Markdown → 纯文本）
     */
    private fun formatReleaseNotes(markdown: String): String {
        return markdown
            .replace(Regex("#+\\s"), "• ") // 标题转为列表
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1") // 移除加粗
            .replace(Regex("\\*(.+?)\\*"), "$1") // 移除斜体
            .replace(Regex("- "), "  • ") // 列表项
            .take(500) // 限制长度
            .trim()
    }
    
    /**
     * 格式化日期（ISO 8601 → 可读格式）
     */
    private fun formatDate(isoDate: String): String {
        return try {
            // 简单转换：2026-11-09T05:47:00Z → 2026-11-09
            isoDate.substringBefore("T")
        } catch (e: Exception) {
            isoDate
        }
    }
    
    /**
     * 关闭对话框
     */
    fun dismiss() {
        dialog?.dismiss()
    }
}
