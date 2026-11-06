package com.hong.tvbox.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.hong.tvbox.core.Config
import com.hong.tvbox.core.Site

/**
 * 配置存储 - 从 ArkTS Store.ets 移植
 */
class ConfigStore(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_ENABLED_SITES = "enabled_sites"
        private const val KEY_ENABLED_CUSTOM = "enabled_custom"
        private const val KEY_SUBSCRIBE_URL = "subscribe_url"
        private const val KEY_LIVE_SOURCE_URL = "live_source_url"
        private const val KEY_LAST_UPDATE = "last_update"
    }
    
    /**
     * 保存完整配置 JSON
     */
    fun saveConfigJson(json: String) {
        prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
        prefs.edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
    }
    
    /**
     * 获取配置 JSON
     */
    fun getConfigJson(): String? {
        return prefs.getString(KEY_CONFIG_JSON, null)
    }
    
    /**
     * 解析并获取配置对象
     */
    fun getConfig(): Config? {
        val json = getConfigJson() ?: return null
        return try {
            gson.fromJson(json, Config::class.java)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 保存启用的站源 key 列表
     */
    fun saveEnabledSites(keys: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_ENABLED_SITES, keys)
            .putBoolean(KEY_ENABLED_CUSTOM, true)
            .apply()
    }
    
    /**
     * 获取启用的站源 key 列表
     */
    fun getEnabledSites(): Set<String> {
        return prefs.getStringSet(KEY_ENABLED_SITES, emptySet()) ?: emptySet()
    }

    /**
     * 用户是否手动调整过站源开关。
     *
     * 必须单独记一个标志位：只用「启用集合是否为空」判断的话，
     * 用户把站源**全部关掉**（集合为空）会被当成「没设置过 → 全部启用」，
     * 开关看起来完全失灵。有了这个标志，空集合 = 一个都不启用。
     */
    fun isEnabledCustomized(): Boolean {
        return prefs.getBoolean(KEY_ENABLED_CUSTOM, false)
    }

    /** 恢复默认：清除自定义标记，回到「自动启用所有可用站源」 */
    fun resetEnabledSites() {
        prefs.edit()
            .remove(KEY_ENABLED_SITES)
            .putBoolean(KEY_ENABLED_CUSTOM, false)
            .apply()
    }
    
    /**
     * 获取已启用的站源列表（只含可用的 type 0/1/4）
     * type=3 爬虫型站源在 Android 端可以用 DexClassLoader 支持，但当前未实现，
     * 暂时标记 usable=false，与鸿蒙版一致。
     */
    fun getEnabledSiteList(): List<Site> {
        val config = getConfig() ?: return emptyList()
        val enabled = getEnabledSites()
        val customized = isEnabledCustomized()

        // 先给 site 打标
        val sites = config.sites.map { site ->
            site.copy(
                usable = when (site.type) {
                    3 -> false  // 爬虫型，暂不支持
                    0, 1, 4 -> site.api.startsWith("http")
                    else -> false
                },
                reason = when {
                    site.type == 3 -> "需爬虫运行时（DexClassLoader），待实现"
                    site.type in listOf(0, 1, 4) && !site.api.startsWith("http") -> "接口地址不是 HTTP"
                    site.type !in listOf(0, 1, 3, 4) -> "暂不支持的类型 type=${site.type}"
                    else -> ""
                }
            )
        }

        return if (!customized) {
            // 用户没动过开关：默认启用所有可用站源
            sites.filter { it.usable }
        } else {
            // 用户动过：严格按集合来（空集合就是全关）
            sites.filter { it.usable && enabled.contains(it.key) }
        }
    }
    
    /**
     * 保存订阅地址
     */
    fun saveSubscribeUrl(url: String) {
        prefs.edit().putString(KEY_SUBSCRIBE_URL, url).apply()
    }
    
    /**
     * 获取订阅地址
     */
    fun getSubscribeUrl(): String? {
        return prefs.getString(KEY_SUBSCRIBE_URL, null)
    }
    
    /**
     * 保存直播源地址
     */
    fun saveLiveSourceUrl(url: String) {
        prefs.edit().putString(KEY_LIVE_SOURCE_URL, url).apply()
    }
    
    /**
     * 获取直播源地址
     */
    fun getLiveSourceUrl(): String? {
        return prefs.getString(KEY_LIVE_SOURCE_URL, null)
    }
    
    /**
     * 获取最后更新时间
     */
    fun getLastUpdateTime(): Long {
        return prefs.getLong(KEY_LAST_UPDATE, 0)
    }
    
    /**
     * 清空所有配置
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
