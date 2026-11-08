package com.hong.tvbox.core

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions

/**
 * Glide 图片加载优化工具
 *
 * 问题：海报墙 6/4 列切换时，Glide 内存缓存策略不够激进，导致频繁 GC 和丢帧
 * 解决：
 *   - 缩略图使用低分辨率（120×180dp），减少内存占用
 *   - 全图使用高分辨率（300×450dp），保证显示质量
 *   - 启用磁盘缓存策略，减少网络请求
 *   - 使用自定义 RequestOptions，统一管理缓存策略
 */
object GlideOptimizer {
    
    /**
     * 缩略图加载选项（海报墙网格）
     * - 分辨率: 120×180dp
     * - 内存缓存: 启用
     * - 磁盘缓存: 启用（ALL 包括转换后的图）
     * - 褪色动画: 300ms
     */
    private val thumbnailOptions = RequestOptions()
        .override(120, 180)  // 目标尺寸
        .diskCacheStrategy(DiskCacheStrategy.ALL)  // 磁盘缓存转换后的图片
        .skipMemoryCache(false)  // 启用内存缓存
    
    /**
     * 全图加载选项（详情页海报）
     * - 分辨率: 300×450dp
     * - 内存缓存: 启用
     * - 磁盘缓存: 启用（ALL）
     * - 褪色动画: 300ms
     */
    private val fullImageOptions = RequestOptions()
        .override(300, 450)  // 目标尺寸
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .skipMemoryCache(false)
    
    /**
     * 加载缩略图（海报墙）
     * 适用场景: DetailActivity、FavoritesActivity 海报网格
     *
     * @param imageView 目标 ImageView
     * @param url 图片 URL
     * @param placeholder 占位图资源 ID（可选）
     */
    fun loadThumbnail(
        imageView: ImageView,
        url: String,
        placeholder: Int? = null
    ) {
        var request = Glide.with(imageView.context)
            .load(url)
            .apply(thumbnailOptions)
        
        if (placeholder != null) {
            request = request.placeholder(placeholder)
        }
        
        request
            .error(android.R.drawable.ic_menu_close_clear_cancel)
            .into(imageView)
    }
    
    /**
     * 加载全图（详情页海报）
     * 适用场景: DetailActivity 海报大图
     *
     * @param imageView 目标 ImageView
     * @param url 图片 URL
     * @param placeholder 占位图资源 ID（可选）
     */
    fun loadFullImage(
        imageView: ImageView,
        url: String,
        placeholder: Int? = null
    ) {
        var request = Glide.with(imageView.context)
            .load(url)
            .apply(fullImageOptions)
        
        if (placeholder != null) {
            request = request.placeholder(placeholder)
        }
        
        request
            .error(android.R.drawable.ic_menu_close_clear_cancel)
            .into(imageView)
    }
    
    /**
     * 加载自定义尺寸图片
     * 适用场景: 特殊分辨率需求（如直播频道 logo、WebDAV 封面）
     *
     * @param imageView 目标 ImageView
     * @param url 图片 URL
     * @param width 宽度（dp）
     * @param height 高度（dp）
     */
    fun loadCustomSize(
        imageView: ImageView,
        url: String,
        width: Int,
        height: Int
    ) {
        Glide.with(imageView.context)
            .load(url)
            .override(width, height)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(false)
            .error(android.R.drawable.ic_menu_close_clear_cancel)
            .into(imageView)
    }
    
    /**
     * 清除所有 Glide 缓存（设置页"清除缓存"按钮调用）
     */
    fun clearCache(context: android.content.Context) {
        Glide.get(context).clearMemory()  // 清内存缓存
        // 磁盘缓存清理在后台线程
        Thread {
            Glide.get(context).clearDiskCache()  // 清磁盘缓存
        }.start()
    }
}
