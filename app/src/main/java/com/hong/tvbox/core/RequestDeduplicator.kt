package com.hong.tvbox.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网络请求去重器（协程版，不依赖 Java 8 CompletableFuture）
 * 相同 key 在 ttlMs 内只发起一次真实请求，重复调用复用缓存。
 */
class RequestDeduplicator {
    private data class CacheEntry<T>(val value: T, val time: Long)
    private val cache = mutableMapOf<String, CacheEntry<*>>()
    private val mutex = Mutex()

    @Suppress("UNCHECKED_CAST")
    suspend fun <T> dedup(key: String, ttlMs: Long = 3000L, fetcher: suspend () -> T): T {
        mutex.withLock {
            val entry = cache[key] as? CacheEntry<T>
            if (entry != null && System.currentTimeMillis() - entry.time < ttlMs) {
                return entry.value
            }
        }
        val value = fetcher()
        mutex.withLock { cache[key] = CacheEntry(value, System.currentTimeMillis()) }
        return value
    }

    fun clear() = cache.clear()
}
