package com.hong.tvbox.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hong.tvbox.core.Favorite

/**
 * 收藏管理 - 从 ArkTS Store.ets 移植
 */
class FavoriteStore(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("favorite", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    companion object {
        private const val KEY_FAVORITES = "favorite_list"
    }
    
    /**
     * 添加收藏
     */
    fun add(favorite: Favorite) {
        val list = getAll().toMutableList()
        
        // 如果已存在，不重复添加
        if (list.any { it.vodId == favorite.vodId && it.siteKey == favorite.siteKey }) {
            return
        }
        
        favorite.createdAt = System.currentTimeMillis()
        list.add(0, favorite)
        
        save(list)
    }
    
    /**
     * 移除收藏
     */
    fun remove(vodId: String, siteKey: String) {
        val list = getAll().toMutableList()
        list.removeAll { it.vodId == vodId && it.siteKey == siteKey }
        save(list)
    }
    
    /**
     * 检查是否已收藏
     */
    fun isFavorite(vodId: String, siteKey: String): Boolean {
        return getAll().any { it.vodId == vodId && it.siteKey == siteKey }
    }
    
    /**
     * 获取所有收藏
     */
    fun getAll(): List<Favorite> {
        val json = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Favorite>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * 清空所有收藏
     */
    fun clear() {
        prefs.edit().remove(KEY_FAVORITES).apply()
    }
    
    private fun save(list: List<Favorite>) {
        val json = gson.toJson(list)
        prefs.edit().putString(KEY_FAVORITES, json).apply()
    }
}
