package com.hong.tvbox.core

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * 苹果CMS（采集源）协议客户端 - 从 ArkTS Cms.ets 移植
 * 
 * 只实现协议型站源：
 *   type 0 —— XML 采集接口
 *   type 1 —— JSON 采集接口
 *   type 4 —— 返回体多包一层 data 的 JSON 接口
 */
class CmsClient(private val site: Site) {
    
    private var extra: String = ""
    
    companion object {
        private const val HIT_MAX = 30
        private const val HIT_CHARS = 3000000
        private const val TTL_CAT = 300000L      // 5 分钟
        private const val TTL_LIST = 90000L      // 90 秒
        private const val TTL_DETAIL = 600000L   // 10 分钟
        
        private val hits = ConcurrentHashMap<String, CmHit>()
        private val gson = Gson()
        
        fun clearCache() {
            hits.clear()
        }

        /**
         * 配置 JSON 容错 - 供外部调用
         * 去 BOM、去注释行、把保留字 class 改名为 catList
         */
        fun cleanJsonStatic(txt: String): String {
            return cleanJson(txt)
        }
        
        private fun hit(url: String, ttl: Long): String {
            val now = System.currentTimeMillis()
            hits[url]?.let {
                if (now - it.at <= ttl) {
                    return it.body
                }
                hits.remove(url)
            }
            return ""
        }
        
        private fun keep(url: String, body: String) {
            if (body.isEmpty()) return
            
            hits[url] = CmHit(url, body, System.currentTimeMillis())
            
            // 限制条数
            if (hits.size > HIT_MAX) {
                hits.entries.sortedBy { it.value.at }.take(hits.size - HIT_MAX).forEach {
                    hits.remove(it.key)
                }
            }
            
            // 限制总字数
            var total = hits.values.sumOf { it.body.length }
            if (total > HIT_CHARS) {
                hits.entries.sortedBy { it.value.at }.forEach {
                    if (total > HIT_CHARS && hits.size > 1) {
                        total -= it.value.body.length
                        hits.remove(it.key)
                    }
                }
            }
        }
        
        private suspend fun fetch(url: String, ttl: Long, timeoutMs: Long): String {
            val has = hit(url, ttl)
            if (has.isNotEmpty()) {
                println("cache HIT  ${url.substringAfter('?').take(48)}")
                return has
            }
            
            val t0 = System.currentTimeMillis()
            val txt = HttpClient.text(url, timeoutMs = timeoutMs)
            println("cache MISS ${url.substringAfter('?').take(48)}  ${System.currentTimeMillis() - t0} ms")
            
            keep(url, txt)
            return txt
        }
        
        // ============ XML 解析 ============
        
        private fun looksXml(txt: String): Boolean = txt.trimStart().startsWith('<')
        
        private fun node(block: String, tag: String): String {
            val regex = Regex("<$tag>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
            return regex.find(block)?.groupValues?.get(1)?.let { text(it) } ?: ""
        }
        
        private fun text(s: String): String {
            var r = s
            val cdataStart = r.indexOf("<![CDATA[")
            if (cdataStart >= 0) {
                val cdataEnd = r.indexOf("]]>", cdataStart)
                if (cdataEnd > 0) {
                    r = r.substring(cdataStart + 9, cdataEnd)
                }
            }
            return plain(r)
        }
        
        private fun plain(s: String): String {
            var r = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            r = r.replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            r = r.replace(Regex("<[^>]+>"), "")
            r = r.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&")
            r = r.replace(Regex("\n{3,}"), "\n\n")
            return r.trim()
        }
        
        fun splitLines(from: String, playUrl: String): List<Line> {
            if (playUrl.isEmpty()) return emptyList()
            
            val fArr = if (from.isNotEmpty()) from.split("$$$") else emptyList()
            val uArr = playUrl.split("$$$")
            
            return uArr.mapIndexed { i, urlStr ->
                val line = Line()
                line.name = if (i < fArr.size && fArr[i].isNotEmpty()) fArr[i] else "线路 ${i + 1}"
                
                val segs = urlStr.split("#")
                segs.forEach { seg ->
                    val trimmed = seg.trim()
                    if (trimmed.isNotEmpty()) {
                        val ep = Episode()
                        val dollarPos = trimmed.indexOf('$')
                        if (dollarPos > 0) {
                            ep.name = trimmed.substring(0, dollarPos).trim()
                            ep.url = trimmed.substring(dollarPos + 1).trim()
                        } else {
                            ep.name = "第 ${line.eps.size + 1} 集"
                            ep.url = trimmed
                        }
                        if (ep.url.isNotEmpty() && ep.url.contains("://")) {
                            line.eps.add(ep)
                        }
                    }
                }
                
                // 判断是否可直连
                val directCount = line.eps.count { isDirect(it.url) }
                line.direct = line.eps.isNotEmpty() && directCount * 2 >= line.eps.size
                
                line
            }.filter { it.eps.isNotEmpty() }
        }
        
        fun isDirect(url: String): Boolean {
            val u = url.lowercase()
            return u.contains(".m3u8") || u.contains(".mp4") || u.contains(".flv") ||
                   u.contains(".mkv") || u.contains(".ts") || u.contains(".m4v")
        }
        
        fun cleanJson(txt: String): String {
            var t = txt
            if (t.isNotEmpty() && t[0] == '\uFEFF') {
                t = t.substring(1)
            }
            
            val lines = t.split('\n')
            val keep = lines.filter { !it.trimStart().startsWith("//") }
            
            return keep.joinToString("\n").replace(Regex("\"class\"\\s*:"), "\"catList\":")
        }
        
        private fun parse(txt: String): JResp {
            val t = cleanJson(txt)
            if (t.trim().isEmpty()) throw Exception("接口返回空内容")
            
            return try {
                gson.fromJson(t, JResp::class.java)
            } catch (e: Exception) {
                throw Exception("接口返回的不是合法 JSON（站源可能已失效）")
            }
        }
        
        private fun root(j: JResp): JResp = j.data ?: j
    }
    
    fun setExtra(q: String) {
        extra = q
    }
    
    // ============ 公共入口 ============
    
    suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
        val txt = fetch(u("ac=list"), TTL_CAT, 15000)
        
        if (site.type == 0 || looksXml(txt)) {
            val cats = catFromXml(txt).toMutableList()
            if (cats.isNotEmpty()) {
                cats.add(0, Category("", "全部"))
            } else {
                val derived = catsFromVideos(txt).toMutableList()
                if (derived.isNotEmpty()) {
                    derived.add(0, Category("", "全部"))
                }
                return@withContext derived
            }
            return@withContext cats
        }
        
        val root = root(parse(txt))
        val out = mutableListOf<Category>()
        
        root.catList?.forEach { jc ->
            val id = jc.type_id?.toString() ?: ""
            val name = jc.type_name?.trim() ?: ""
            val pid = if ((jc.type_pid ?: 0) > 0) jc.type_pid.toString() else ""
            
            if (id.isNotEmpty() && name.isNotEmpty()) {
                out.add(Category(id, name, pid))
            }
        }
        
        val jList = root.list
        if (out.isEmpty() && jList != null) {
            val seen = mutableSetOf<String>()
            jList.forEach { jv ->
                val tid = jv.tid?.toString() ?: ""
                val tn = jv.type_name?.trim() ?: ""
                if (tid.isNotEmpty() && tn.isNotEmpty() && !seen.contains(tid)) {
                    seen.add(tid)
                    out.add(Category(tid, tn))
                }
            }
        }
        
        if (out.isNotEmpty()) {
            out.add(0, Category("", "全部"))
        }
        
        out
    }
    
    suspend fun list(tid: String, page: Int): VodPage = withContext(Dispatchers.IO) {
        val q = "ac=videolist&t=${URLEncoder.encode(tid, "UTF-8")}&pg=$page$extra"
        val txt = fetch(u(q), TTL_LIST, 18000)
        
        if (site.type == 0 || looksXml(txt)) {
            return@withContext listFromXml(txt, page)
        }
        
        val root = root(parse(txt))
        val pg = VodPage(
            page = root.page ?: page,
            pageCount = root.pagecount ?: 1,
            total = root.total ?: 0
        )
        
        root.list?.forEach { jv ->
            pg.items.add(toVod(jv))
        }
        
        pg
    }
    
    /**
     * 父分类 + 子分类合并取第一页。
     *
     * 采集站的父分类（如「电视剧」）通常没有直属影片：直接请求 t=1 返回空列表，
     * 界面就会显示「这个分类下没有内容」，但下面明明排着 欧美剧/韩剧/日剧，
     * 用户会以为软件坏了。这里把父 + 子一起请求、按 vod id 去重合并。
     *
     * 只取第 1 页且不翻页：聚合结果本来就是各类最新的混排，
     * 继续翻页的语义说不清（翻谁的页），翻页留给具体子分类。
     */
    suspend fun listMerged(ids: List<String>, page: Int): VodPage = withContext(Dispatchers.IO) {
        val out = VodPage(page = 1, pageCount = 1)
        if (ids.isEmpty()) return@withContext out

        // 逐个取，失败即跳过（某个子分类挂了不该拖垮整页）
        val pages = ids.map { id ->
            try {
                list(id, page)
            } catch (e: Exception) {
                VodPage()
            }
        }

        val seen = mutableSetOf<String>()
        pages.forEach { pg ->
            pg.items.forEach { vod ->
                if (vod.id.isNotEmpty() && seen.add(vod.id)) {
                    out.items.add(vod)
                }
            }
        }
        out.total = out.items.size
        out
    }

    /**
     * 从站点 ext 里取出该分类的筛选组。
     * ext 形如 {"class":[...],"filters":{"1":[{"key":"area","name":"地区","value":[{"n":"全部","v":""}]}]}}
     * 没有 ext、不是 JSON、或该分类没配筛选时返回空数组。
     */
    fun filtersOf(tid: String): List<FilterGroup> {
        val out = mutableListOf<FilterGroup>()
        val txt = site.ext.trim()
        if (txt.isEmpty() || txt[0] != '{') return out

        val j: Map<String, Any> = try {
            gson.fromJson(txt, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            return out
        }

        @Suppress("UNCHECKED_CAST")
        val filters = j["filters"] as? Map<String, Any> ?: return out

        @Suppress("UNCHECKED_CAST")
        val groups = filters[tid] as? List<Any> ?: return out

        groups.forEach { item ->
            @Suppress("UNCHECKED_CAST")
            val g = item as? Map<String, Any> ?: return@forEach
            val key = (g["key"] as? String) ?: ""
            val name = (g["name"] as? String) ?: key

            @Suppress("UNCHECKED_CAST")
            val values = g["value"] as? List<Any>

            val fg = FilterGroup(key = key, name = name)
            values?.forEach { v ->
                @Suppress("UNCHECKED_CAST")
                val vm = v as? Map<String, Any> ?: return@forEach
                val n = (vm["n"] as? String) ?: ""
                val value = (vm["v"] as? String) ?: ""
                if (n.isNotEmpty()) {
                    fg.values.add(FilterValue(n = n, v = value))
                }
            }

            if (fg.key.isNotEmpty() && fg.values.isNotEmpty()) {
                out.add(fg)
            }
        }

        return out
    }

    suspend fun detail(id: String): Vod = withContext(Dispatchers.IO) {
        val q = "ac=detail&ids=${URLEncoder.encode(id, "UTF-8")}"
        val txt = fetch(u(q), TTL_DETAIL, 18000)
        
        if (site.type == 0 || looksXml(txt)) {
            return@withContext detailFromXml(txt, id)
        }
        
        val root = root(parse(txt))
        root.list?.firstOrNull()?.let { return@withContext toVod(it) }
        
        throw Exception("接口没有返回详情数据")
    }
    
    suspend fun search(kw: String): List<Vod> = withContext(Dispatchers.IO) {
        val e = URLEncoder.encode(kw, "UTF-8")
        var items = try {
            val txt = HttpClient.text(u("ac=videolist&wd=$e"), timeoutMs = 12000)
            pickList(txt)
        } catch (ex: Exception) {
            emptyList()
        }
        
        if (items.isEmpty()) {
            val txt2 = HttpClient.text(u("wd=$e"), timeoutMs = 12000)
            items = pickList(txt2)
        }
        
        items
    }
    
    // ============ 内部方法 ============
    
    private fun u(params: String): String {
        val a = site.api
        return when {
            a.endsWith('?') -> a + params
            a.contains('?') -> "$a&$params"
            else -> "$a?$params"
        }
    }
    
    private fun toVod(j: JVod): Vod {
        return Vod(
            id = j.vod_id?.toString() ?: "",
            name = j.vod_name?.trim() ?: "",
            pic = j.vod_pic?.trim() ?: "",
            remark = j.vod_remarks?.trim() ?: "",
            year = j.vod_year?.trim() ?: "",
            area = j.vod_area?.trim() ?: "",
            actor = j.vod_actor?.trim() ?: "",
            director = j.vod_director?.trim() ?: "",
            content = plain((j.vod_content ?: j.vod_blurb ?: "").trim()),
            typeName = j.type_name?.trim() ?: "",
            from = j.vod_play_from?.trim() ?: "",
            playUrl = j.vod_play_url?.trim() ?: "",
            siteKey = site.key,
            siteName = site.name
        )
    }
    
    private fun pickList(txt: String): List<Vod> {
        if (site.type == 0 || looksXml(txt)) {
            return listFromXml(txt, 1).items
        }
        
        val root = root(parse(txt))
        return root.list?.map { toVod(it) } ?: emptyList()
    }
    
    private fun catsFromVideos(xml: String): List<Category> {
        val out = mutableListOf<Category>()
        val seen = mutableSetOf<String>()
        val blocks = Regex("<video>[\\s\\S]*?</video>", RegexOption.IGNORE_CASE).findAll(xml)
        
        blocks.forEach { match ->
            val tid = node(match.value, "tid")
            val tn = node(match.value, "type")
            if (tid.isNotEmpty() && tn.isNotEmpty() && !seen.contains(tid)) {
                seen.add(tid)
                out.add(Category(tid, tn))
            }
        }
        
        return out
    }
    
    private fun catFromXml(xml: String): List<Category> {
        val out = mutableListOf<Category>()
        val regex = Regex("<ty\\b[^>]*\\bid=\"(\\d+)\"[^>]*>([\\s\\S]*?)</ty>", RegexOption.IGNORE_CASE)
        
        regex.findAll(xml).forEach { match ->
            val id = match.groupValues[1]
            val name = plain(match.groupValues[2])
            if (name.isNotEmpty()) {
                out.add(Category(id, name))
            }
        }
        
        return out
    }
    
    private fun listFromXml(xml: String, page: Int): VodPage {
        val pg = VodPage(page = page)
        
        val headMatch = Regex("<list\\b[^>]*>", RegexOption.IGNORE_CASE).find(xml)
        headMatch?.value?.let { head ->
            Regex("\\bpage=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toIntOrNull()?.let { pg.page = it }
            Regex("\\bpagecount=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toIntOrNull()?.let { pg.pageCount = it }
            Regex("\\brecordcount=\"(\\d+)\"", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toIntOrNull()?.let { pg.total = it }
        }
        
        val blocks = Regex("<video>[\\s\\S]*?</video>", RegexOption.IGNORE_CASE).findAll(xml)
        blocks.forEach { match ->
            val b = match.value
            val v = Vod(
                id = node(b, "id"),
                name = node(b, "name"),
                pic = node(b, "pic"),
                remark = node(b, "note").ifEmpty { node(b, "dt") },
                typeName = node(b, "type"),
                siteKey = site.key,
                siteName = site.name
            )
            if (v.name.isNotEmpty()) {
                pg.items.add(v)
            }
        }
        
        return pg
    }
    
    private fun detailFromXml(xml: String, id: String): Vod {
        val blocks = Regex("<video>[\\s\\S]*?</video>", RegexOption.IGNORE_CASE).findAll(xml).toList()
        if (blocks.isEmpty()) throw Exception("接口没有返回详情数据")
        
        val b = blocks[0].value
        val v = Vod(
            id = node(b, "id").ifEmpty { id },
            name = node(b, "name"),
            pic = node(b, "pic"),
            remark = node(b, "note"),
            typeName = node(b, "type"),
            year = node(b, "year"),
            area = node(b, "area"),
            actor = node(b, "actor"),
            director = node(b, "director"),
            content = node(b, "des"),
            siteKey = site.key,
            siteName = site.name
        )
        
        // 解析线路
        val flags = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val ddRegex = Regex("<dd\\b[^>]*\\bflag=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</dd>", RegexOption.IGNORE_CASE)
        
        ddRegex.findAll(b).forEach { match ->
            flags.add(match.groupValues[1])
            urls.add(text(match.groupValues[2]))
        }
        
        if (flags.isEmpty()) {
            val ddRegex2 = Regex("<dd\\b[^>]*>([\\s\\S]*?)</dd>", RegexOption.IGNORE_CASE)
            var n = 1
            ddRegex2.findAll(b).forEach { match ->
                flags.add("线路$n")
                urls.add(text(match.groupValues[1]))
                n++
            }
        }
        
        v.from = flags.joinToString("$$$")
        v.playUrl = urls.joinToString("$$$")
        
        return v
    }
}

private data class CmHit(
    val key: String,
    val body: String,
    val at: Long
)
