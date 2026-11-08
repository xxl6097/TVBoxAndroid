package com.hong.tvbox.core

import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * ext 字段兼容两种形态：既可能是筛选参数 JSON 字符串，也可能是纯元信息对象
 * （如订阅里的 {"note":"...","library":162826}）。对象统一转成 JSON 字符串，
 * 让整个 Config 能正常反序列化，筛选解析侧会安全地返回空。
 */
class ExtAdapter : TypeAdapter<String>() {
    override fun write(out: JsonWriter, value: String?) {
        out.value(value ?: "")
    }

    override fun read(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.BEGIN_OBJECT, JsonToken.BEGIN_ARRAY ->
                JsonParser.parseReader(reader).toString()
            else -> {
                reader.skipValue()
                ""
            }
        }
    }
}

/**
 * 数据模型 - 从 ArkTS Model.ets 移植
 */

// ============ 站源配置 ============

data class Site(
    @SerializedName("key") var key: String = "",
    @SerializedName("name") var name: String = "",
    @SerializedName("type") var type: Int = 1,  // 0=XML, 1=JSON, 3=爬虫, 4=JSON(多层data)
    @SerializedName("api") var api: String = "",
    @SerializedName("searchable") var searchable: Int = 1,
    @SerializedName("quickSearch") var quickSearch: Int = 0,
    @SerializedName("ext") @JsonAdapter(ExtAdapter::class) var ext: String = "",
    var usable: Boolean = true,      // type=3 爬虫标记不可用
    var reason: String = ""
)

data class Config(
    @SerializedName("sites") var sites: List<Site> = emptyList(),
    @SerializedName("parses") var parses: List<Parse> = emptyList(),
    @SerializedName("lives") var lives: List<LiveSource> = emptyList()
)

data class Parse(
    @SerializedName("name") var name: String = "",
    @SerializedName("type") var type: Int = 1,  // 只支持 type=1 的 JSON 解析
    @SerializedName("url") var url: String = ""
)

data class LiveSource(
    @SerializedName("name") var name: String = "",
    @SerializedName("url") var url: String = "",
    /** 内嵌 m3u 数据（订阅里 lives 的一种格式：{name, channels, data}，data 直接是 m3u 行） */
    @SerializedName("data") var data: List<String> = emptyList()
)

// ============ 分类 ============

data class Category(
    var id: String = "",
    var name: String = "",
    var pid: String = ""  // 父分类 ID，空表示顶级
)

// ============ 影片 ============

data class Vod(
    var id: String = "",
    var name: String = "",
    var pic: String = "",
    var remark: String = "",
    var year: String = "",
    var area: String = "",
    var actor: String = "",
    var director: String = "",
    var content: String = "",
    var typeName: String = "",
    var from: String = "",          // 线路名，$$$ 分隔
    var playUrl: String = "",       // 播放地址，$$$ 分隔线路，# 分隔剧集
    var siteKey: String = "",
    var siteName: String = ""
)

data class VodPage(
    var page: Int = 1,
    var pageCount: Int = 1,
    var total: Int = 0,
    var items: MutableList<Vod> = mutableListOf()
)

// ============ 线路与剧集 ============

data class Episode(
    var name: String = "",
    var url: String = "",
    /** 备用线路：直播同名台常有多条地址，播挂了按这个顺序换线 */
    var urls: MutableList<String> = mutableListOf()
)

// ============ 直播 ============

/**
 * 一个直播频道。
 * urls 是同名同组的多条线路（CCTV1 常有三四条地址），播挂了要能换线。
 */
data class Channel(
    var name: String = "",
    var url: String = "",
    var logo: String = "",
    var group: String = "",
    var urls: MutableList<String> = mutableListOf()
)

data class ChannelGroup(
    var name: String = "",
    var channels: MutableList<Channel> = mutableListOf()
)

/**
 * 直播目录节点。
 *
 * 直播源本身只有一层分组，几百个台平铺很难找；这里按源里写的
 * 「地方台/北京」这类层级拆成树。每个节点既可有直属频道，也可有子节点。
 */
data class LiveDir(
    var name: String = "",
    var count: Int = 0,
    var children: MutableList<LiveDir> = mutableListOf(),
    var channels: MutableList<Channel> = mutableListOf()
)

// ============ WebDAV 网盘 ============

/** 一个 WebDAV 挂载源 */
data class WebdavSource(
    var id: String = "",
    var name: String = "",
    var url: String = "",       // 如 http://192.168.1.10:5244/dav
    var user: String = "",
    var pass: String = "",
    var root: String = ""       // 挂载根路径，空表示从根浏览
)

/** PROPFIND 解析出的一个条目 */
data class WebdavEntry(
    var href: String = "",      // 已 URL 解码的相对路径
    var name: String = "",
    var isDir: Boolean = false,
    var size: Long = 0,
    var mtime: String = "",
    var contentType: String = "" // 服务端给的 getcontenttype，形如 video/mp4
)

/** 识别出的一部影视（网盘影视识别结果） */
data class WebdavVod(
    var name: String = "",
    /** movie = 电影 / series = 剧集 */
    var kind: String = "movie",
    var year: String = "",
    var dir: String = "",
    var eps: MutableList<WebdavEp> = mutableListOf()
)

/** 识别出的一集 */
data class WebdavEp(
    var name: String = "",
    var url: String = "",
    var href: String = "",
    var season: Int = 0,
    var episode: Int = 0
)

/** 网盘续播记录（对齐鸿蒙 WdHistory） */
data class WdHistory(
    var srcId: String = "",
    var vodName: String = "",
    var epName: String = "",
    /** 已看到第几毫秒 */
    var pos: Long = 0,
    /** 这一集的总时长（毫秒），0 表示未知 */
    var dur: Long = 0,
    var updatedAt: Long = 0
)

data class Line(
    var name: String = "",
    var direct: Boolean = false,    // 是否可直连播放
    var eps: MutableList<Episode> = mutableListOf()
)

// ============ 筛选 ============

data class FilterValue(
    var n: String = "",
    var v: String = ""
)

data class FilterGroup(
    var key: String = "",
    var name: String = "",
    var values: MutableList<FilterValue> = mutableListOf()
)

// ============ JSON 响应结构 ============

data class JResp(
    @SerializedName("code") var code: Int? = null,
    @SerializedName("page") var page: Int? = null,
    @SerializedName("pagecount") var pagecount: Int? = null,
    @SerializedName("total") var total: Int? = null,
    @SerializedName("list") var list: List<JVod>? = null,
    @SerializedName("class") var catList: List<JClass>? = null,
    @SerializedName("data") var data: JResp? = null  // type=4 多包一层
)

data class JVod(
    @SerializedName("vod_id") var vod_id: Any? = null,
    @SerializedName("vod_name") var vod_name: String? = null,
    @SerializedName("vod_pic") var vod_pic: String? = null,
    @SerializedName("vod_remarks") var vod_remarks: String? = null,
    @SerializedName("vod_year") var vod_year: String? = null,
    @SerializedName("vod_area") var vod_area: String? = null,
    @SerializedName("vod_actor") var vod_actor: String? = null,
    @SerializedName("vod_director") var vod_director: String? = null,
    @SerializedName("vod_content") var vod_content: String? = null,
    @SerializedName("vod_blurb") var vod_blurb: String? = null,
    @SerializedName("type_name") var type_name: String? = null,
    @SerializedName("vod_play_from") var vod_play_from: String? = null,
    @SerializedName("vod_play_url") var vod_play_url: String? = null,
    @SerializedName("tid") var tid: Any? = null
)

data class JClass(
    @SerializedName("type_id") var type_id: Any? = null,
    @SerializedName("type_name") var type_name: String? = null,
    @SerializedName("type_pid") var type_pid: Int? = null
)

// ============ 历史记录 ============

data class History(
    var vodId: String = "",
    var siteKey: String = "",
    var vodName: String = "",
    var vodPic: String = "",
    var lineIndex: Int = 0,
    var episodeIndex: Int = 0,
    var episodeName: String = "",
    var position: Long = 0,      // 播放位置（毫秒）
    var duration: Long = 0,      // 总时长（毫秒）
    var updatedAt: Long = 0
)

// ============ 收藏 ============

data class Favorite(
    var vodId: String = "",
    var siteKey: String = "",
    var vodName: String = "",
    var vodPic: String = "",
    var createdAt: Long = 0
)
