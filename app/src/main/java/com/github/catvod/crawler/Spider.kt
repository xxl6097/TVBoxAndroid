package com.github.catvod.crawler

import android.content.Context

/**
 * 爬虫站源基类（占位实现）。
 *
 * type=3 的站源是一个 jar，里面的类继承 `com.github.catvod.crawler.Spider`。
 * Android 上我们没有官方 SDK 依赖，所以在这里放一个**同名同签名**的基类：
 * jar 加载时父类就能解析到，子类覆写我们通过反射调用。
 *
 * 注意：方法签名必须和官方 Spider 完全一致（含 boolean 基本类型、HashMap），
 * 否则 jar 里的 @Override 会对不上，加载即失败。
 * 这些方法不会被直接调用，全部走 SpiderCms 的反射。
 */
open class Spider {

    open fun init(context: Context?, extend: String?) {
    }

    open fun homeContent(filter: Boolean): String = ""

    open fun homeVideoContent(): String = ""

    open fun categoryContent(
        tid: String?,
        pg: String?,
        filter: Boolean,
        extend: HashMap<String, String>?
    ): String = ""

    open fun detailContent(ids: List<String>?): String = ""

    open fun searchContent(key: String?, quick: Boolean): String = ""

    open fun searchContent(key: String?, quick: Boolean, pg: String?): String = ""

    open fun playerContent(flag: String?, id: String?, vipFlags: List<String>?): String = ""

    open fun isVideoFormat(url: String?): Boolean = false

    open fun manualVideoCheck(): Boolean = false

    open fun destroy() {
    }
}

/** 官方包里同样存在的空实现，部分 jar 会引用到 */
open class SpiderNull : Spider()
