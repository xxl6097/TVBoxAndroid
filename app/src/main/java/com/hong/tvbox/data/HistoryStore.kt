package com.hong.tvbox.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hong.tvbox.core.History

/**
 * 历史记录存储 - 从 ArkTS Store.ets 移植
 */
class HistoryStore(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_HISTORY = "history_list"
        private const val MAX_HISTORY = 100
    }
    
    /**
     * 添加或更新历史记录
     */
    fun addOrUpdate(history: History) {
        val list = getAll().toMutableList()
        
        // 移除相同视频的旧记录
        list.removeAll { it.vodId == history.vodId && it.siteKey == history.siteKey }
        
        // 添加到最前面
        history.updatedAt = System.currentTimeMillis()
        list.add(0, history)
        
        // 限制数量
        if (list.size > MAX_HISTORY) {
            list.subList(MAX_HISTORY, list.size).clear()
        }
        
        save(list)
    }
    
    /**
     * 获取某个视频的历史记录
     */
    fun get(vodId: String, siteKey: String): History? {
        return getAll().firstOrNull { it.vodId == vodId && it.siteKey == siteKey }
    }
    
    /**
     * 获取所有历史记录
     */
    fun getAll(): List<History> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<History>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 删除某条历史记录
     */
    fun remove(vodId: String, siteKey: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.vodId == vodId && it.siteKey == siteKey }
        save(list)
    }
    
    /**
     * 清空所有历史记录
     */
    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }
    
    private fun save(list: List<History>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}
