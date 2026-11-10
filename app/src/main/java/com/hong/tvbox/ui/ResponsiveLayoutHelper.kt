package com.hong.tvbox.ui

import android.content.Context

/**
 * 响应式布局助手：根据屏幕尺寸自适应界面
 *
 * Android 6 设备屏幕尺寸差异大（480x320 ~ 1080x1920），
 * 需要根据实际屏幕尺寸动态调整布局参数、字体大小、间距等。
 */
object ResponsiveLayoutHelper {

    enum class ScreenSize {
        /** 小屏幕：< 480x320（过时设备，罕见） */
        SMALL,
        /** 普通屏幕：480x800 */
        NORMAL,
        /** 大屏幕：720x1280 */
        LARGE,
        /** 超大屏幕：>= 1080x1920 */
        XLARGE
    }

    /**
     * 获取当前设备的屏幕尺寸分类
     */
    fun getScreenSize(context: Context): ScreenSize {
        val dm = context.resources.displayMetrics
        val minDim = minOf(dm.widthPixels, dm.heightPixels)
        val maxDim = maxOf(dm.widthPixels, dm.heightPixels)

        return when {
            minDim < 480 -> ScreenSize.SMALL
            minDim < 720 -> ScreenSize.NORMAL
            minDim < 1080 -> ScreenSize.LARGE
            else -> ScreenSize.XLARGE
        }
    }

    /**
     * 根据屏幕尺寸获取推荐的字体大小
     *
     * @param context 上下文
     * @param baseSizeSp 基础字体大小（sp）
     * @return 调整后的字体大小（sp）
     */
    fun getOptimalFontSize(context: Context, baseSizeSp: Float): Float {
        return when (getScreenSize(context)) {
            ScreenSize.SMALL -> baseSizeSp * 0.8f     // 缩小 20%
            ScreenSize.NORMAL -> baseSizeSp * 0.9f    // 缩小 10%
            ScreenSize.LARGE -> baseSizeSp             // 保持不变
            ScreenSize.XLARGE -> baseSizeSp * 1.1f    // 放大 10%
        }
    }

    /**
     * 根据屏幕尺寸获取推荐的控件间距
     *
     * @param context 上下文
     * @param basePaddingDp 基础间距（dp）
     * @return 调整后的间距（px）
     */
    fun getOptimalPadding(context: Context, basePaddingDp: Int): Int {
        val scale = when (getScreenSize(context)) {
            ScreenSize.SMALL -> 0.7f
            ScreenSize.NORMAL -> 0.85f
            ScreenSize.LARGE -> 1.0f
            ScreenSize.XLARGE -> 1.2f
        }
        val adjustedDp = (basePaddingDp * scale).toInt()
        return (adjustedDp * context.resources.displayMetrics.density).toInt()
    }

    /**
     * 根据屏幕尺寸获取推荐的卡片高度
     *
     * @param context 上下文
     * @param normalHeightDp 正常尺寸下的卡片高度（dp）
     * @return 调整后的高度（px）
     */
    fun getOptimalCardHeight(context: Context, normalHeightDp: Int): Int {
        val adjustedDp = when (getScreenSize(context)) {
            ScreenSize.SMALL -> (normalHeightDp * 0.75).toInt()  // 小屏幕紧凑模式
            ScreenSize.NORMAL -> (normalHeightDp * 0.85).toInt()
            ScreenSize.LARGE -> normalHeightDp
            ScreenSize.XLARGE -> (normalHeightDp * 1.15).toInt()
        }
        return (adjustedDp * context.resources.displayMetrics.density).toInt()
    }

    /**
     * 是否需要紧凑模式布局（用于小屏幕设备）
     *
     * 紧凑模式：隐藏非核心元素、减少间距、简化动画
     */
    fun isCompactMode(context: Context): Boolean {
        return getScreenSize(context) in arrayOf(ScreenSize.SMALL, ScreenSize.NORMAL)
    }

    /**
     * 小屏幕是否应该单列显示内容
     */
    fun isSingleColumn(context: Context): Boolean {
        val minDim = minOf(
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels
        )
        return minDim < 600
    }

    /**
     * 获取最小可点击区域大小（dp）
     * 根据屏幕尺寸，小屏幕可适当增加点击区域
     */
    fun getMinTouchSize(context: Context): Int {
        return when (getScreenSize(context)) {
            ScreenSize.SMALL -> 56   // 更大的点击区域
            ScreenSize.NORMAL -> 48
            ScreenSize.LARGE -> 48
            ScreenSize.XLARGE -> 48
        }
    }
}
