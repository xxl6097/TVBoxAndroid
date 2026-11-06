package com.hong.tvbox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 定时抓取 - 从鸿蒙 Crawler.ets 简化移植
 *
 * 作用：不用每次进首页都等各站现拉，后台每隔几小时跑一轮：
 *   1) 抓每个站源第一个分类的最新列表
 *   2) 顺手判断站源是否还活着
 * 结果落盘给首页「最近更新」用。
 */
object Crawler {

    data class Result(
        val sites: Int = 0,
        val items: Int = 0,
        val dead: List<String> = emptyList(),
        val at: Long = System.currentTimeMillis(),
        val list: List<Vod> = emptyList()
    )

    /**
     * 跑一轮抓取。
     *
     * @param sites 已启用的站源
     * @param perSite 每个站源最多收几条
     * @param maxSites 最多抓几个站源（站源多时避免一轮跑太久）
     */
    suspend fun run(
        sites: List<Site>,
        perSite: Int = 6,
        maxSites: Int = 12
    ): Result = withContext(Dispatchers.IO) {
        if (sites.isEmpty()) return@withContext Result()

        val targets = sites.take(maxSites)
        val dead = mutableListOf<String>()
        val items = mutableListOf<Vod>()

        // 分批并发，单站失败只记该死站，不影响整体
        targets.chunked(4).forEach { batch ->
            val results = coroutineScope {
                batch.map { site ->
                    async(Dispatchers.IO) { site to fetchOne(site, perSite) }
                }.awaitAll()
            }

            results.forEach { pair ->
                val site = pair.first
                val list = pair.second
                if (list == null) {
                    dead.add(site.key)
                } else {
                    items.addAll(list)
                }
            }
        }

        Result(
            sites = targets.size,
            items = items.size,
            dead = dead,
            at = System.currentTimeMillis(),
            list = items
        )
    }

    /** 抓一个站源的最新列表；返回 null 表示该站这轮不可用 */
    private suspend fun fetchOne(site: Site, perSite: Int): List<Vod>? {
        return try {
            val cms = CmsClient(site)
            val cats = cms.categories()
            if (cats.isEmpty()) return null

            // 优先抓「全部」再退到第一个分类
            val target = cats.firstOrNull { it.id.isEmpty() } ?: cats.first()
            val page = cms.list(target.id, 1)

            page.items.take(perSite).map { v ->
                v.copy(
                    siteKey = site.key,
                    siteName = site.name
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}
