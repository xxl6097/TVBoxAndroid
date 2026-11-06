package com.hong.tvbox.core

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Method

/**
 * type=3 爬虫站源。
 *
 * TVBox 生态里这类站源是一个 jar（dex 格式），按约定类名为 `csp_<siteKey>`，
 * 继承 `com.github.catvod.crawler.Spider`（见同名占位基类）。
 *
 * 这里全部走反射：不编译期依赖任何 jar，站源换版本不用重新打包 APK。
 * 所有调用都包了 try/catch —— 单个站源炸掉只应让这个站源不可用，
 * 不能让整个首页/搜索崩掉。
 */
object SpiderCms {

    private const val TAG = "SpiderCms"
    private const val DIR = "spiders"

    /** siteKey -> 已初始化的实例 */
    private val instances = mutableMapOf<String, Any>()
    /** siteKey -> 最近一次错误原因（设置页/日志排查用） */
    private val lastError = mutableMapOf<String, String>()

    fun errorOf(siteKey: String): String = lastError[siteKey] ?: ""

    fun clearCache() {
        instances.values.forEach { safeDestroy(it) }
        instances.clear()
        lastError.clear()
    }

    // ---------- 加载 ----------

    private suspend fun instance(context: Context, site: Site): Any? {
        instances[site.key]?.let { return it }

        val jar = ensureJar(context, site) ?: return null

        return try {
            val loader = dalvik.system.DexClassLoader(
                jar.absolutePath,
                context.codeCacheDir.absolutePath,
                null,
                context.classLoader
            )

            val cls = findClass(loader, site) ?: run {
                lastError[site.key] = "jar 里找不到 csp_${site.key} 类"
                return null
            }

            val obj = cls.getDeclaredConstructor().newInstance()
            invokeInit(obj, context, site.ext)
            instances[site.key] = obj
            obj
        } catch (e: Throwable) {
            lastError[site.key] = "加载失败：${e.javaClass.simpleName} ${e.message}"
            Log.w(TAG, "load ${site.key} failed", e)
            null
        }
    }

    private fun findClass(loader: ClassLoader, site: Site): Class<*>? {
        val candidates = buildList {
            add("csp_${site.key}")
            add(site.key)
            // 少数 jar 用点号路径写在 ext 里，这里不做解析，仅兜底尝试常见命名
            add("com.github.catvod.spider.${site.key.replaceFirstChar { it.uppercase() }}")
        }
        candidates.forEach { name ->
            try {
                return loader.loadClass(name)
            } catch (e: ClassNotFoundException) {
                // 继续下一个候选
            }
        }
        return null
    }

    private fun invokeInit(obj: Any, context: Context, ext: String) {
        try {
            val m: Method = obj.javaClass.getMethod("init", Context::class.java, String::class.java)
            m.invoke(obj, context, ext)
        } catch (e: Throwable) {
            // init 里抛异常很常见（比如 ext 里的接口挂了），仍可尝试后续调用
            Log.w(TAG, "init failed: ${e.message}")
        }
    }
    /** 下载并缓存 jar；已有非空文件直接复用 */
    private suspend fun ensureJar(context: Context, site: Site): File? {
        val url = site.api.trim()
        if (url.isEmpty() || !url.startsWith("http")) {
            lastError[site.key] = "爬虫站源缺少 jar 地址"
            return null
        }

        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = url.hashCode().toString() + "_" + site.key.replace(Regex("[^A-Za-z0-9_]"), "_")
        val file = File(dir, "$name.jar")

        if (file.exists() && file.length() > 1024) return file

        return try {
            val data = HttpClient.bytes(url, timeoutMs = 30000)
            if (data.size < 1024) {
                lastError[site.key] = "jar 下载内容过小（${data.size} 字节）"
                return null
            }
            file.writeBytes(data)
            file
        } catch (e: Exception) {
            lastError[site.key] = "jar 下载失败：${e.message}"
            null
        }
    }

    // ---------- 反射调用 ----------

    private fun callString(obj: Any, name: String, types: Array<Class<*>>, args: Array<Any?>): String {
        return try {
            val m = obj.javaClass.getMethod(name, *types)
            (m.invoke(obj, *args) as? String) ?: ""
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            throw IllegalStateException("${name} 调用失败：${cause.message}", cause)
        }
    }

    // ---------- 业务方法 ----------

    suspend fun categories(context: Context, site: Site): List<Category> = withContext(Dispatchers.IO) {
        val obj = instance(context, site) ?: return@withContext emptyList()
        try {
            val json = callString(obj, "homeContent", arrayOf(Boolean::class.javaPrimitiveType!!), arrayOf(false))
            val root = JsonParser.parseString(json).asJsonObject
            parseCategories(root)
        } catch (e: Exception) {
            lastError[site.key] = "获取分类失败：${e.message}"
            emptyList()
        }
    }

    private fun parseCategories(root: JsonObject): List<Category> {
        val out = mutableListOf<Category>()

        root.getAsJsonArray("class")?.forEach { el ->
            val o = el.asJsonObject
            val id = o.str("type_id")
            val name = o.str("type_name")
            val pid = o.str("type_pid")
            if (id.isNotEmpty() && name.isNotEmpty()) out.add(Category(id, name, pid))
        }

        // 部分 jar 返回的是 classes（复数）
        if (out.isEmpty()) {
            root.getAsJsonArray("classes")?.forEach { el ->
                val o = el.asJsonObject
                val id = o.str("type_id")
                val name = o.str("type_name")
                if (id.isNotEmpty() && name.isNotEmpty()) out.add(Category(id, name, ""))
            }
        }
        return out
    }

    suspend fun list(
        context: Context,
        site: Site,
        tid: String,
        page: Int,
        extra: Map<String, String> = emptyMap()
    ): VodPage = withContext(Dispatchers.IO) {
        val empty = VodPage(page = page, pageCount = 1)
        val obj = instance(context, site) ?: return@withContext empty

        try {
            val extend = HashMap<String, String>(extra)
            val json = callString(
                obj,
                "categoryContent",
                arrayOf(
                    String::class.java,
                    String::class.java,
                    Boolean::class.javaPrimitiveType!!,
                    HashMap::class.java
                ),
                arrayOf(tid, page.toString(), false, extend)
            )

            val root = JsonParser.parseString(json).asJsonObject
            val out = VodPage(
                page = root.get("page")?.asInt ?: page,
                pageCount = root.get("pagecount")?.asInt ?: 1,
                total = root.get("total")?.asInt ?: 0
            )
            root.getAsJsonArray("list")?.forEach { el ->
                out.items.add(toVod(el.asJsonObject, site))
            }
            out
        } catch (e: Exception) {
            lastError[site.key] = "分类列表失败：${e.message}"
            empty
        }
    }

    suspend fun detail(context: Context, site: Site, id: String): Vod = withContext(Dispatchers.IO) {
        val obj = instance(context, site) ?: return@withContext Vod(id = id, siteKey = site.key, siteName = site.name)

        try {
            val json = callString(
                obj,
                "detailContent",
                arrayOf(List::class.java),
                arrayOf(listOf(id))
            )
            val root = JsonParser.parseString(json).asJsonObject
            val arr = root.getAsJsonArray("list")
            if (arr == null || arr.size() == 0) {
                return@withContext Vod(id = id, siteKey = site.key, siteName = site.name)
            }
            toVod(arr[0].asJsonObject, site)
        } catch (e: Exception) {
            lastError[site.key] = "详情失败：${e.message}"
            Vod(id = id, siteKey = site.key, siteName = site.name)
        }
    }

    suspend fun search(context: Context, site: Site, keyword: String): List<Vod> = withContext(Dispatchers.IO) {
        val obj = instance(context, site) ?: return@withContext emptyList()

        try {
            val json = callString(
                obj,
                "searchContent",
                arrayOf(String::class.java, Boolean::class.javaPrimitiveType!!),
                arrayOf(keyword, false)
            )
            val root = JsonParser.parseString(json).asJsonObject
            val out = mutableListOf<Vod>()
            root.getAsJsonArray("list")?.forEach { el ->
                out.add(toVod(el.asJsonObject, site))
            }
            out
        } catch (e: Exception) {
            lastError[site.key] = "搜索失败：${e.message}"
            emptyList()
        }
    }

    /**
     * 取最终播放地址。
     * @param flag 线路名（vod_play_from 里的一段）
     * @param id   剧集 id（vod_play_url 里 $ 后面那段）
     */
    suspend fun player(
        context: Context,
        site: Site,
        flag: String,
        id: String
    ): String = withContext(Dispatchers.IO) {
        val obj = instance(context, site) ?: return@withContext ""
        try {
            val json = callString(
                obj,
                "playerContent",
                arrayOf(String::class.java, String::class.java, List::class.java),
                arrayOf(flag, id, emptyList<String>())
            )
            val root = JsonParser.parseString(json).asJsonObject
            root.get("url")?.asString ?: ""
        } catch (e: Exception) {
            lastError[site.key] = "取播放地址失败：${e.message}"
            ""
        }
    }

    // ---------- 映射 ----------

    private fun toVod(o: JsonObject, site: Site): Vod {
        return Vod(
            id = o.str("vod_id"),
            name = o.str("vod_name"),
            pic = o.str("vod_pic"),
            remark = o.str("vod_remarks"),
            year = o.str("vod_year"),
            area = o.str("vod_area"),
            actor = o.str("vod_actor"),
            director = o.str("vod_director"),
            content = o.str("vod_content"),
            from = o.str("vod_play_from"),
            playUrl = o.str("vod_play_url"),
            siteKey = site.key,
            siteName = site.name
        )
    }

    private fun JsonObject.str(key: String): String {
        val el = this.get(key) ?: return ""
        if (el.isJsonNull) return ""
        return try {
            el.asString
        } catch (e: Exception) {
            el.toString()
        }
    }

    private fun safeDestroy(obj: Any) {
        try {
            obj.javaClass.getMethod("destroy").invoke(obj)
        } catch (e: Throwable) {
            // 忽略
        }
    }
}
