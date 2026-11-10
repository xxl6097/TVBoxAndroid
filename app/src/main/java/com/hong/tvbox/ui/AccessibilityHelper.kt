package com.hong.tvbox.ui

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * 无障碍支持工具类 - 为 UI 元素添加 contentDescription + WCAG 2.0 AA 兼容性
 *
 * TalkBack（屏幕阅读器）和放大镜都依赖 contentDescription
 * 为所有交互元素添加描述，使应用对障碍人士友好
 *
 * WCAG 2.0 AA 标准：
 * - 文字对比度 >= 4.5:1（正常文字） 或 3:1（大文字 18sp+）
 * - 最小文字大小 >= 14sp
 * - 可点击元素最小尺寸 >= 48x48 dp
 */
object AccessibilityHelper {

    const val MIN_FONT_SIZE_SP = 14f
    const val MIN_TOUCH_TARGET_DP = 48
    const val MIN_CONTRAST_RATIO = 4.5f  // WCAG AA 正常文字标准
    const val MIN_CONTRAST_RATIO_LARGE = 3f  // WCAG AA 大文字标准（18sp+）

    // ====== 无障碍检查 ======

    /**
     * 确保文字大小符合无障碍标准（>= 14sp）
     */
    fun ensureMinTextSize(textView: TextView) {
        val currentSize = textView.textSize / textView.resources.displayMetrics.scaledDensity
        if (currentSize < MIN_FONT_SIZE_SP) {
            textView.textSize = MIN_FONT_SIZE_SP
        }
    }

    /**
     * 为可交互元素设置无障碍标签 + 最小点击区域
     */
    fun setupAccessibleElement(view: View, description: String) {
        view.contentDescription = description

        // 提高可点击元素的最小触摸面积（48x48 dp）
        if (view.isClickable) {
            val minSize = (MIN_TOUCH_TARGET_DP * view.resources.displayMetrics.density).toInt()
            if (view.minimumHeight < minSize) {
                view.minimumHeight = minSize
            }
            if (view.minimumWidth < minSize) {
                view.minimumWidth = minSize
            }
        }
    }

    /**
     * 验证颜色对比度（简化版）
     *
     * @param foreground 前景色（十六进制 ARGB）
     * @param background 背景色（十六进制 ARGB）
     * @return 是否满足 WCAG AA 标准（对比度 >= 4.5:1）
     */
    fun isContrastSufficient(foreground: Int, background: Int): Boolean {
        val fgLuminance = calculateRelativeLuminance(foreground)
        val bgLuminance = calculateRelativeLuminance(background)
        val lighter = maxOf(fgLuminance, bgLuminance)
        val darker = minOf(fgLuminance, bgLuminance)
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        return ratio >= MIN_CONTRAST_RATIO
    }

    /**
     * 计算相对亮度（WCAG 2.0 定义）
     */
    private fun calculateRelativeLuminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f

        val rLinear = if (r <= 0.03928f) r / 12.92f
        else Math.pow(((r + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        val gLinear = if (g <= 0.03928f) g / 12.92f
        else Math.pow(((g + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        val bLinear = if (b <= 0.03928f) b / 12.92f
        else Math.pow(((b + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        return 0.2126f * rLinear + 0.7152f * gLinear + 0.0722f * bLinear
    }

    // ====== Button 描述 ======

    fun setPlayButtonDesc(btn: Button) {
        btn.contentDescription = "播放 - 开始播放影视内容"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setPauseButtonDesc(btn: Button) {
        btn.contentDescription = "暂停 - 暂停当前播放"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setFavoriteButtonDesc(btn: Button, isFav: Boolean = false) {
        btn.contentDescription = if (isFav) "已收藏 - 点击取消收藏" else "收藏 - 点击添加到收藏夹"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setShareButtonDesc(btn: Button) {
        btn.contentDescription = "分享 - 复制影视信息到剪贴板"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setSearchButtonDesc(btn: Button) {
        btn.contentDescription = "搜索 - 打开搜索界面"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setSettingsButtonDesc(btn: Button) {
        btn.contentDescription = "设置 - 打开应用设置"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setHistoryButtonDesc(btn: Button) {
        btn.contentDescription = "历史记录 - 查看播放历史"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setNextEpisodeDesc(btn: Button) {
        btn.contentDescription = "下一集 - 播放下一集内容"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setPreviousEpisodeDesc(btn: Button) {
        btn.contentDescription = "上一集 - 播放上一集内容"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setRefreshButtonDesc(btn: Button) {
        btn.contentDescription = "刷新 - 重新加载内容"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setClearCacheButtonDesc(btn: Button) {
        btn.contentDescription = "清除缓存 - 删除已缓存的影视数据"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    fun setDownloadButtonDesc(btn: Button, isDownloading: Boolean = false) {
        btn.contentDescription = if (isDownloading) "下载中... - 正在下载" else "下载 - 下载到本地离线观看"
        setupAccessibleElement(btn, btn.contentDescription.toString())
    }

    // ====== ImageView 描述 ======

    fun setPosterDesc(img: ImageView, vodName: String = "") {
        img.contentDescription = if (vodName.isNotEmpty()) "影视海报：$vodName" else "影视海报"
    }

    fun setLogoDesc(img: ImageView, sourceName: String = "") {
        img.contentDescription = if (sourceName.isNotEmpty()) "站源图标：$sourceName" else "站源图标"
    }

    fun setChannelPosterDesc(img: ImageView, channelName: String = "") {
        img.contentDescription = if (channelName.isNotEmpty()) "直播频道封面：$channelName" else "直播频道封面"
    }

    fun setIconDesc(img: ImageView, iconName: String) {
        img.contentDescription = iconName
    }

    // ====== TextView 描述 ======

    fun setTitleDesc(tv: TextView, title: String = "") {
        tv.contentDescription = if (title.isNotEmpty()) "标题：$title" else "标题"
        ensureMinTextSize(tv)
    }

    fun setRatingDesc(tv: TextView, rating: Double = 0.0) {
        tv.contentDescription = if (rating > 0) "评分：${String.format("%.1f", rating)} 分" else "评分：暂无"
        ensureMinTextSize(tv)
    }

    fun setYearDesc(tv: TextView, year: String = "") {
        tv.contentDescription = if (year.isNotEmpty()) "年份：$year" else "年份：暂无"
        ensureMinTextSize(tv)
    }

    fun setAreaDesc(tv: TextView, area: String = "") {
        tv.contentDescription = if (area.isNotEmpty()) "地区：$area" else "地区：暂无"
        ensureMinTextSize(tv)
    }

    fun setStatusDesc(tv: TextView, status: String = "") {
        tv.contentDescription = if (status.isNotEmpty()) "状态：$status" else "状态：暂无"
        ensureMinTextSize(tv)
    }

    // ====== 动态更新 ======

    fun updatePlayStateDesc(view: View, isPlaying: Boolean) {
        view.contentDescription = if (isPlaying) "正在播放" else "已暂停"
    }

    fun updateLoadingDesc(view: View, isLoading: Boolean) {
        view.contentDescription = if (isLoading) "加载中，请等待..." else "加载完成"
    }

    fun updateErrorDesc(view: View, errorMsg: String) {
        view.contentDescription = "错误：$errorMsg"
    }
}
