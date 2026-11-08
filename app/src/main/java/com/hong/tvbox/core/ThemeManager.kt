package com.hong.tvbox.core

import android.content.Context
import android.graphics.Color

/**
 * 主题管理器 - 支持深色/浅色模式切换
 * 
 * 存储用户选择的主题偏好（跟随系统/深色/浅色）
 * 根据偏好动态返回颜色值
 */
object ThemeManager {
    private const val PREF_NAME = "theme"
    private const val KEY_THEME_MODE = "mode"
    private const val KEY_CUSTOM_PRIMARY = "primary_color"
    
    // 主题模式枚举
    const val MODE_SYSTEM = 0    // 跟随系统
    const val MODE_DARK = 1      // 强制深色
    const val MODE_LIGHT = 2     // 强制浅色
    
    private lateinit var context: Context
    private var isDarkMode = true
    
    fun init(ctx: Context) {
        context = ctx
        isDarkMode = loadThemeMode() != MODE_LIGHT
    }
    
    fun setThemeMode(mode: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply()
        
        // 重新计算 isDarkMode
        isDarkMode = when (mode) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            MODE_SYSTEM -> isSystemDarkMode()
            else -> true
        }
    }
    
    fun loadThemeMode(): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THEME_MODE, MODE_SYSTEM)
    }
    
    private fun isSystemDarkMode(): Boolean {
        val config = context.resources.configuration
        return (config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == 
               android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
    
    // ====== 颜色值查询 ======
    
    fun bgPrimary(): Int = if (isDarkMode) 0xFF1a1a2e.toInt() else 0xFFF5F5F5.toInt()
    fun bgSecondary(): Int = if (isDarkMode) 0xFF16213e.toInt() else 0xFFEAEAEA.toInt()
    fun bgTertiary(): Int = if (isDarkMode) 0xFF0F3460.toInt() else 0xFFE0E0E0.toInt()
    
    fun textPrimary(): Int = if (isDarkMode) Color.WHITE else Color.BLACK
    fun textSecondary(): Int = if (isDarkMode) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
    fun textDim(): Int = if (isDarkMode) 0x80FFFFFF.toInt() else 0x80000000.toInt()
    
    fun accentColor(): Int = 0xFFFFEB3B.toInt()  // 黄色始终保持
    fun warnColor(): Int = 0xFFFFB4AB.toInt()    // 警告色始终保持
    
    fun cardBackground(): Int = if (isDarkMode) 0x33000000 else 0x1A000000
    fun cardBackgroundFocused(): Int = if (isDarkMode) 0x66000000 else 0x33000000
    
    fun dividerColor(): Int = if (isDarkMode) 0x1F000000 else 0x1F000000
    
    fun overlayColor(): Int = if (isDarkMode) 0xCC000000.toInt() else 0xCC000000.toInt()
}
