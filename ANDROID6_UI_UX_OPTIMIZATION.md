# Android 6 界面交互体验优化方案

## 1. 核心优化方向

### 1.1 触摸反馈系统
**问题**：Android 6 设备上按钮点击反馈可能不明显
**解决**：
- 实现统一的触摸反馈机制（Ripple + 震动）
- 为所有可交互元素添加按下/松开状态
- 长按支持视觉反馈

### 1.2 屏幕适配
**问题**：Android 6 设备屏幕尺寸多样（480x320 ~ 1080x1920）
**解决**：
- 使用 dp/sp 单位而非固定像素
- 实现多分辨率布局
- 适配小屏幕（480x320）的紧凑模式

### 1.3 动画性能
**问题**：Android 6 设备性能较低，复杂动画可能卡顿
**解决**：
- 禁用或简化不必要的动画
- 使用 VSync 同步（硬件加速）
- 监控帧率，低端设备降级处理

### 1.4 无障碍
**问题**：系统默认字体较小，对比度不足
**解决**：
- 确保最小文字大小为 14sp
- 文字对比度 >= 4.5:1（WCAG AA 标准）
- 支持系统字体大小设置

## 2. 实现细节

### 2.1 新增 TouchFeedbackHelper

```kotlin
package com.hong.tvbox.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View

/**
 * 触摸反馈助手：统一处理按钮点击、长按的视觉 + 触觉反馈
 * 
 * Android 6+ 需要主动提供触摸反馈，以提升用户体验
 */
object TouchFeedbackHelper {
    
    private var vibrator: Vibrator? = null
    
    fun init(context: Context) {
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    
    /**
     * 为按钮添加触摸反馈
     * @param view 目标按钮
     * @param hapticEnabled 是否启用震动反馈（默认 true）
     */
    fun setupTouchFeedback(view: View, hapticEnabled: Boolean = true) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.7f  // 按下时变暗
                    if (hapticEnabled) {
                        performHapticFeedback(10)  // 10ms 短振动
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f   // 松开时恢复
                }
            }
            false  // 继续传递事件给 onClick
        }
    }
    
    /**
     * 执行触觉反馈
     * @param durationMs 振动时长（毫秒）
     */
    fun performHapticFeedback(durationMs: Long) {
        if (!hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(
                durationMs,
                VibrationEffect.DEFAULT_AMPLITUDE
            ))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(durationMs)
        }
    }
    
    private fun hasVibrator(): Boolean {
        return vibrator?.hasVibrator() ?: false
    }
}
```

### 2.2 新增 ResponsiveLayoutHelper

```kotlin
package com.hong.tvbox.ui

import android.content.Context
import android.util.DisplayMetrics

/**
 * 响应式布局助手：根据屏幕尺寸自适应界面
 * 
 * Android 6 设备屏幕尺寸差异大，需要动态调整布局参数
 */
object ResponsiveLayoutHelper {
    
    enum class ScreenSize {
        SMALL,      // < 480x320
        NORMAL,     // 480x800
        LARGE,      // 720x1280
        XLARGE      // >= 1080x1920
    }
    
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
     */
    fun getOptimalFontSize(context: Context, baseSize: Float): Float {
        return when (getScreenSize(context)) {
            ScreenSize.SMALL -> baseSize * 0.8f   // 缩小 20%
            ScreenSize.NORMAL -> baseSize * 0.9f  // 缩小 10%
            ScreenSize.LARGE -> baseSize
            ScreenSize.XLARGE -> baseSize * 1.1f  // 放大 10%
        }
    }
    
    /**
     * 根据屏幕尺寸获取推荐的控件间距
     */
    fun getOptimalPadding(context: Context, basePaddingDp: Int): Int {
        val scale = when (getScreenSize(context)) {
            ScreenSize.SMALL -> 0.7f
            ScreenSize.NORMAL -> 0.85f
            ScreenSize.LARGE -> 1.0f
            ScreenSize.XLARGE -> 1.2f
        }
        return (basePaddingDp * scale).toInt()
    }
    
    /**
     * 小屏幕是否需要紧凑模式
     */
    fun isCompactMode(context: Context): Boolean {
        return getScreenSize(context) == ScreenSize.SMALL
    }
}
```

### 2.3 新增 AccessibilityHelper

```kotlin
package com.hong.tvbox.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * 无障碍助手：确保应用满足 WCAG 2.0 AA 标准
 */
object AccessibilityHelper {
    
    const val MIN_FONT_SIZE_SP = 14f
    const val MIN_CONTRAST_RATIO = 4.5f  // WCAG AA 标准
    
    /**
     * 确保文字大小符合无障碍标准
     */
    fun ensureMinTextSize(textView: TextView) {
        val currentSize = textView.textSize / textView.resources.displayMetrics.scaledDensity
        if (currentSize < MIN_FONT_SIZE_SP) {
            textView.textSize = MIN_FONT_SIZE_SP
        }
    }
    
    /**
     * 为可交互元素设置无障碍标签
     */
    fun setupContentDescription(view: View, description: String) {
        view.contentDescription = description
        // 提高可点击元素的最小触摸面积（48x48 dp）
        if (view.isClickable) {
            val minSize = (48 * view.resources.displayMetrics.density).toInt()
            if (view.minimumHeight < minSize) {
                view.minimumHeight = minSize
            }
            if (view.minimumWidth < minSize) {
                view.minimumWidth = minSize
            }
        }
    }
    
    /**
     * 验证颜色对比度
     * @return 是否满足 WCAG AA 标准（对比度 >= 4.5:1）
     */
    fun isContrastSufficient(foreground: Int, background: Int): Boolean {
        val fgLuminance = calculateLuminance(foreground)
        val bgLuminance = calculateLuminance(background)
        val lighter = maxOf(fgLuminance, bgLuminance)
        val darker = minOf(fgLuminance, bgLuminance)
        return (lighter + 0.05f) / (darker + 0.05f) >= MIN_CONTRAST_RATIO
    }
    
    private fun calculateLuminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        
        val rLinear = if (r <= 0.03928f) r / 12.92f else Math.pow(((r + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        val gLinear = if (g <= 0.03928f) g / 12.92f else Math.pow(((g + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        val bLinear = if (b <= 0.03928f) b / 12.92f else Math.pow(((b + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        
        return 0.2126f * rLinear + 0.7152f * gLinear + 0.0722f * bLinear
    }
}
```

### 2.4 在 TvBoxApp 初始化

```kotlin
// 在 TvBoxApp.onCreate() 中添加：
override fun onCreate() {
    super.onCreate()
    
    // 初始化触摸反馈系统
    TouchFeedbackHelper.init(this)
    
    // 初始化日志系统等其他模块
    Logger.init(this)
}
```

### 2.5 在 MainActivity 应用

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configStore = ConfigStore(this)
    historyStore = HistoryStore(this)

    // 权限检查
    if (!PermissionHelper.hasStoragePermission(this)) {
        PermissionHelper.requestStoragePermission(this)
    }
    
    // 获取屏幕尺寸信息
    val screenSize = ResponsiveLayoutHelper.getScreenSize(this)
    if (ResponsiveLayoutHelper.isCompactMode(this)) {
        Logger.d("使用紧凑模式布局")
    }

    if (configStore.isPrivacyAgreed()) {
        buildHome()
    } else {
        showPrivacyDialog()
    }
}
```

## 3. 更新 dimens.xml 支持多分辨率

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- 基础间距 -->
    <dimen name="padding_small">8dp</dimen>
    <dimen name="padding_normal">16dp</dimen>
    <dimen name="padding_large">24dp</dimen>
    
    <!-- 字体大小 -->
    <dimen name="text_size_small">12sp</dimen>
    <dimen name="text_size_normal">14sp</dimen>
    <dimen name="text_size_large">16sp</dimen>
    <dimen name="text_size_xlarge">18sp</dimen>
    
    <!-- 最小可点击区域 -->
    <dimen name="min_touch_size">48dp</dimen>
    
    <!-- 卡片高度 -->
    <dimen name="card_height_normal">180dp</dimen>
    <dimen name="card_height_small">140dp</dimen>
</resources>
```

## 4. 状态栏兼容性

在 `themes.xml` 中添加 Android 6+ 特定的配置：

```xml
<!-- Android 6+ 状态栏配置 -->
<item name="android:statusBarColor" tools:targetApi="21">@android:color/black</item>
<item name="android:windowLightStatusBar" tools:targetApi="23">false</item>
<!-- 状态栏图标为浅色 -->
```

## 5. 测试检查清单

- [ ] 小屏幕设备（480x320）上按钮是否可点击（最小 48x48 dp）
- [ ] 文字大小是否 >= 14sp
- [ ] 文字与背景对比度是否 >= 4.5:1
- [ ] 触摸时是否有视觉反馈
- [ ] 震动反馈是否正常（Settings 未禁用时）
- [ ] 长按操作是否有反馈
- [ ] 屏幕旋转是否处理正确
- [ ] 系统字体大小调整后是否自适应

## 6. 性能指标目标

| 指标 | 目标 | 说明 |
|------|------|------|
| 首屏加载时间 | < 2s | 包括权限申请时间 |
| 平均帧率 | > 50 fps | 低端设备 |
| 内存占用 | < 150 MB | 正常使用状态 |
| 电池消耗 | 1% / 小时 | 后台播放 |
