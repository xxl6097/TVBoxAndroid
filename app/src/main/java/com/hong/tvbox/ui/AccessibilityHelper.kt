package com.hong.tvbox.ui

import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView

/**
 * 无障碍支持工具类 - 为 UI 元素添加 contentDescription
 * 
 * TalkBack（屏幕阅读器）和放大镜都依赖 contentDescription
 * 为所有交互元素添加描述，使应用对障碍人士友好
 */
object AccessibilityHelper {
    
    // ====== Button 描述 ======
    
    fun setPlayButtonDesc(btn: Button) {
        btn.contentDescription = "播放 - 开始播放影视内容"
    }
    
    fun setPauseButtonDesc(btn: Button) {
        btn.contentDescription = "暂停 - 暂停当前播放"
    }
    
    fun setFavoriteButtonDesc(btn: Button, isFav: Boolean = false) {
        btn.contentDescription = if (isFav) "已收藏 - 点击取消收藏" else "收藏 - 点击添加到收藏夹"
    }
    
    fun setShareButtonDesc(btn: Button) {
        btn.contentDescription = "分享 - 复制影视信息到剪贴板"
    }
    
    fun setSearchButtonDesc(btn: Button) {
        btn.contentDescription = "搜索 - 打开搜索界面"
    }
    
    fun setSettingsButtonDesc(btn: Button) {
        btn.contentDescription = "设置 - 打开应用设置"
    }
    
    fun setHistoryButtonDesc(btn: Button) {
        btn.contentDescription = "历史记录 - 查看播放历史"
    }
    
    fun setNextEpisodeDesc(btn: Button) {
        btn.contentDescription = "下一集 - 播放下一集内容"
    }
    
    fun setPreviousEpisodeDesc(btn: Button) {
        btn.contentDescription = "上一集 - 播放上一集内容"
    }
    
    fun setRefreshButtonDesc(btn: Button) {
        btn.contentDescription = "刷新 - 重新加载内容"
    }
    
    fun setClearCacheButtonDesc(btn: Button) {
        btn.contentDescription = "清除缓存 - 删除已缓存的影视数据"
    }
    
    fun setDownloadButtonDesc(btn: Button, isDownloading: Boolean = false) {
        btn.contentDescription = if (isDownloading) "下载中... - 正在下载" else "下载 - 下载到本地离线观看"
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
    }
    
    fun setRatingDesc(tv: TextView, rating: Double = 0.0) {
        tv.contentDescription = if (rating > 0) "评分：${String.format("%.1f", rating)} 分" else "评分：暂无"
    }
    
    fun setYearDesc(tv: TextView, year: String = "") {
        tv.contentDescription = if (year.isNotEmpty()) "年份：$year" else "年份：暂无"
    }
    
    fun setAreaDesc(tv: TextView, area: String = "") {
        tv.contentDescription = if (area.isNotEmpty()) "地区：$area" else "地区：暂无"
    }
    
    fun setStatusDesc(tv: TextView, status: String = "") {
        tv.contentDescription = if (status.isNotEmpty()) "状态：$status" else "状态：暂无"
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
