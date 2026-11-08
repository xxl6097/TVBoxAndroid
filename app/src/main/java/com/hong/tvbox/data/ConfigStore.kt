package com.hong.tvbox.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.hong.tvbox.core.Config
import com.hong.tvbox.core.LiveSource
import com.hong.tvbox.core.Site
import com.hong.tvbox.core.Vod
import com.hong.tvbox.core.WebdavSource
import com.hong.tvbox.core.WebdavVod
import com.hong.tvbox.core.WdHistory

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
        private const val KEY_SUB_LIVES = "sub_lives"
        private const val KEY_DIRECT_LIST = "direct_list"
        private const val KEY_PRIVACY_AGREED = "privacy_agreed"
        private const val KEY_FEED = "feed"
        private const val KEY_CRAWL_AT = "crawl_at"
        private const val KEY_CRAWL_SUMMARY = "crawl_summary"
        private const val KEY_KEYWORDS = "search_keywords"
        private const val KEY_WEBDAV_LIST = "webdav_list"
        private const val KEY_WEBDAV_SCAN_PREFIX = "webdav_scan_"    // + srcId
        private const val KEY_WD_HISTORY = "webdav_history"
        private const val KEY_LIVE_PATH = "live_path"
        private const val KEY_LAST_UPDATE = "last_update"
        private const val KEY_TAP_SOUND = "tap_sound"
        private const val KEY_TAP_VIBRATE = "tap_vibrate"
        private const val KEY_Q_PREF = "q_pref"
        private const val KEY_INTRO_SKIP = "intro_skip"
        private const val KEY_OUTRO_SKIP = "outro_skip"
        private const val KEY_GUIDE_DONE = "guide_done"
        private const val KEY_PLAY_SPEED = "play_speed"
        private const val KEY_AUTO_NEXT = "auto_next"
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
                    // type=3 是爬虫 jar，能下载就能用（运行时见 SpiderCms）
                    0, 1, 3, 4 -> site.api.startsWith("http")
                    else -> false
                },
                reason = when {
                    site.type == 3 && !site.api.startsWith("http") -> "爬虫站源缺少 jar 地址"
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
     * 订阅下发的直播源列表（多个候选，设置页/直播页可切换）。
     * 存的是 [{name,url}] 的 JSON。
     */
    fun getSubLives(): List<LiveSource> {
        val json = prefs.getString(KEY_SUB_LIVES, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<LiveSource>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveSubLives(list: List<LiveSource>) {
        prefs.edit().putString(KEY_SUB_LIVES, gson.toJson(list)).apply()
    }

    /** 订阅里带的直播源名（设置页展示用） */
    fun getSubLiveName(url: String): String {
        return getSubLives().firstOrNull { it.url == url }?.name ?: ""
    }

    // ---------- 点击反馈 / 画质 / 跳过 / 引导 ----------

    fun tapSound(): Boolean = prefs.getInt(KEY_TAP_SOUND, 1) != 0
    fun setTapSound(on: Boolean) = prefs.edit().putInt(KEY_TAP_SOUND, if (on) 1 else 0).apply()

    fun tapVibrate(): Boolean = prefs.getInt(KEY_TAP_VIBRATE, 1) != 0
    fun setTapVibrate(on: Boolean) = prefs.edit().putInt(KEY_TAP_VIBRATE, if (on) 1 else 0).apply()

    /** 画质偏好：0=自动，>0=高度上限(1080/720/480)，9999=原画 */
    fun qPref(): Int = prefs.getInt(KEY_Q_PREF, 0)
    fun setQPref(v: Int) = prefs.edit().putInt(KEY_Q_PREF, v).apply()

    /** 片头跳过秒数，0=不跳过 */
    fun introSkipSec(): Int = prefs.getInt(KEY_INTRO_SKIP, 0)
    fun setIntroSkipSec(v: Int) = prefs.edit().putInt(KEY_INTRO_SKIP, v).apply()

    /** 片尾跳过秒数，0=不跳过 */
    fun outroSkipSec(): Int = prefs.getInt(KEY_OUTRO_SKIP, 0)
    fun setOutroSkipSec(v: Int) = prefs.edit().putInt(KEY_OUTRO_SKIP, v).apply()

    /** 首启引导是否已看过 */
    fun guideDone(): Boolean = prefs.getBoolean(KEY_GUIDE_DONE, false)
    fun setGuideDone() = prefs.edit().putBoolean(KEY_GUIDE_DONE, true).apply()

    /** 播放速度偏好（下标，对应 PlayerActivity.speedNames） */
    fun playSpeedPref(): Int = prefs.getInt(KEY_PLAY_SPEED, 0)
    fun setPlaySpeedPref(idx: Int) = prefs.edit().putInt(KEY_PLAY_SPEED, idx).apply()

    /** 是否自动播放下一集 */
    fun autoPlayNext(): Boolean = prefs.getBoolean(KEY_AUTO_NEXT, true)
    fun setAutoPlayNext(v: Boolean) = prefs.edit().putBoolean(KEY_AUTO_NEXT, v).apply()

    // ---------- 直播分组记忆 ----------
    /** 上次停留的直播分组路径（用不可见字符分隔，分组名里可能有 / ） */
    fun getLastLivePath(): List<String> {
        val raw = prefs.getString(KEY_LIVE_PATH, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split("\u0001").filter { it.isNotEmpty() }
    }

    fun saveLastLivePath(parts: List<String>) {
        prefs.edit().putString(KEY_LIVE_PATH, parts.joinToString("\u0001")).apply()
    }

    // ---------- 直连播放历史 ----------

    fun getDirectList(): List<String> {
        val json = prefs.getString(KEY_DIRECT_LIST, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 记一条直连地址，去重、最近用的排最前，最多留 12 条 */
    fun pushDirect(url: String) {
        val old = getDirectList()
        val out = mutableListOf(url)
        old.forEach {
            if (it != url && out.size < 12) out.add(it)
        }
        prefs.edit().putString(KEY_DIRECT_LIST, gson.toJson(out)).apply()
    }

    // ---------- 首启隐私同意 ----------

    /**
     * 隐私政策是否已同意。
     * 必须在任何联网动作之前判断：应用市场要求首启先展示政策、用户点同意后才允许联网。
     */
    fun isPrivacyAgreed(): Boolean = prefs.getBoolean(KEY_PRIVACY_AGREED, false)

    fun setPrivacyAgreed() {
        prefs.edit().putBoolean(KEY_PRIVACY_AGREED, true).apply()
    }

    // ---------- 定时抓取 ----------

    /** 上一轮抓取到的「最近更新」条目 */
    fun getFeed(): List<Vod> {
        val json = prefs.getString(KEY_FEED, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<Vod>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFeed(list: List<Vod>) {
        prefs.edit().putString(KEY_FEED, gson.toJson(list)).apply()
    }

    fun getLastCrawlAt(): Long = prefs.getLong(KEY_CRAWL_AT, 0L)

    fun setLastCrawlAt(t: Long) {
        prefs.edit().putLong(KEY_CRAWL_AT, t).apply()
    }

    /** 上一轮抓取摘要，设置页展示用 */
    fun getCrawlSummary(): String = prefs.getString(KEY_CRAWL_SUMMARY, "") ?: ""

    fun saveCrawlSummary(s: String) {
        prefs.edit().putString(KEY_CRAWL_SUMMARY, s).apply()
    }

    // ---------- 搜索历史 ----------

    fun getKeywords(): List<String> {
        val raw = prefs.getString(KEY_KEYWORDS, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 记一条关键词：去重、最近用的排最前，最多 8 条 */
    fun pushKeyword(kw: String): List<String> {
        val k = kw.trim()
        if (k.isEmpty()) return getKeywords()

        val out = mutableListOf(k)
        getKeywords().forEach {
            if (it != k && out.size < 8) out.add(it)
        }
        prefs.edit().putString(KEY_KEYWORDS, out.joinToString("\n")).apply()
        return out
    }

    fun clearKeywords() {
        prefs.edit().remove(KEY_KEYWORDS).apply()
    }

    // ---------- WebDAV 网盘 ----------

    fun getWebdavSources(): List<WebdavSource> {
        val json = prefs.getString(KEY_WEBDAV_LIST, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<WebdavSource>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 新增或按 id 覆盖保存一个 WebDAV 源 */
    fun saveWebdavSource(src: WebdavSource) {
        val list = getWebdavSources().toMutableList()
        val id = src.id.ifEmpty { "wd_${System.currentTimeMillis()}" }
        val fixed = src.copy(id = id)

        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = fixed else list.add(0, fixed)

        prefs.edit().putString(KEY_WEBDAV_LIST, gson.toJson(list)).apply()
    }

    fun removeWebdavSource(id: String) {
        val list = getWebdavSources().filter { it.id != id }
        prefs.edit().putString(KEY_WEBDAV_LIST, gson.toJson(list)).apply()
    }

    // ---------- 网盘扫描缓存（对齐鸿蒙 Store.webdavScanCache / saveWebdavScan）----------

    /** 读取网盘源的扫描缓存 */
    fun getWebdavScanCache(srcId: String): List<WebdavVod>? {
        val key = KEY_WEBDAV_SCAN_PREFIX + srcId
        val json = prefs.getString(key, null) ?: return null
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<WebdavVod>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            null
        }
    }

    /** 保存网盘源的扫描结果 */
    fun saveWebdavScanCache(srcId: String, vods: List<WebdavVod>) {
        val key = KEY_WEBDAV_SCAN_PREFIX + srcId
        prefs.edit().putString(key, gson.toJson(vods)).apply()
    }

    /** 清除网盘源的扫描缓存 */
    fun clearWebdavScanCache(srcId: String) {
        val key = KEY_WEBDAV_SCAN_PREFIX + srcId
        prefs.edit().remove(key).apply()
    }

    // ---------- 网盘续播记录（对齐鸿蒙 WdHistory）----------

    fun getWdHistory(): List<WdHistory> {
        val json = prefs.getString(KEY_WD_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<WdHistory>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 新增或更新网盘续播记录（按 srcId + vodName + epName 唯一） */
    fun saveWdHistory(hist: WdHistory) {
        val list = getWdHistory().toMutableList()
        val idx = list.indexOfFirst {
            it.srcId == hist.srcId && it.vodName == hist.vodName && it.epName == hist.epName
        }
        val updated = hist.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) list[idx] = updated else list.add(0, updated)

        // 最多保留 50 条
        val out = list.take(50)
        prefs.edit().putString(KEY_WD_HISTORY, gson.toJson(out)).apply()
    }

    fun clearWdHistory() {
        prefs.edit().remove(KEY_WD_HISTORY).apply()
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
