package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hong.tvbox.Feedback
import com.hong.tvbox.core.CmsClient
import com.hong.tvbox.core.HttpClient
import com.hong.tvbox.core.Site
import com.hong.tvbox.core.WebdavSource
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 设置页 —— 布局复刻鸿蒙 SettingPage.ets 的 TV 双栏：
 * 左侧导航栏（播放源 / 内容更新 / 网盘 / 站源 / 数据 / 关于），
 * 右侧内容区分组卡片（tvOS 风格的「分组标题 + 行式条目」）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var historyStore: HistoryStore

    // ===== 视觉常量（对齐鸿蒙 S 类）=====
    private val CARD_R = 14f
    private val ROW_H = 52
    private val PAD_L = 15
    private val PAD_R = 18
    private val DIV_INSET = 18
    private val DIV_COLOR = 0x1FFFFFFF
    private val GROUP_TXT = 0x8CFFFFFF.toInt()
    private val ROW_HI = 0x33FFFFFF.toInt()
    private val BAR_W = 3
    private val SURFACE = 0xFF1C1B1F.toInt()
    private val CARD_INPUT = 0xFF2A2930.toInt()

    // ===== 导航状态 =====
    private lateinit var contentContainer: LinearLayout
    private var currentNav = "source"
    private data class NavRef(val row: View, val bar: View, val txt: TextView)
    private val navRefs = mutableMapOf<String, NavRef>()

    // ===== 站源 =====
    private var showAllSites = true
    private lateinit var sitesContainer: LinearLayout
    private val speedResults = mutableListOf<Pair<Site, Long>>()

    // ===== 订阅直播源折叠 =====
    private var showPresets = false

    private var qrServer: com.hong.tvbox.core.QrServer? = null
    private var qrDialog: androidx.appcompat.app.AlertDialog? = null

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        historyStore = HistoryStore(this)
        buildUI()
    }

    // ==================== 布局骨架 ====================

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
        }

        // 顶栏：返回 + 标题 + 站源统计
        root.addView(buildTitleBar())

        // 主体：左导航 + 分隔线 + 右内容
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val navDefs = listOf(
            "source" to "播放源", "update" to "内容更新", "webdav" to "网盘",
            "sites" to "站源", "data" to "数据", "about" to "关于"
        )
        navDefs.forEach { (key, label) -> nav.addView(navItem(label, key)) }
        body.addView(nav, LinearLayout.LayoutParams(dp(224), ViewGroup.LayoutParams.MATCH_PARENT))

        body.addView(View(this).apply {
            setBackgroundColor(0x14FFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
        })

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }
        val scroll = ScrollView(this)
        scroll.addView(contentContainer)
        body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        switchNav("source")
    }

    private fun buildTitleBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(4))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(TvUi.SUB)
            setOnClickListener { finish() }
        }
        back.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = ROW_HI, scale = 1f)
        bar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        bar.addView(TextView(this).apply {
            text = "设置"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })

        bar.addView(TextView(this).apply {
            val total = configStore.getConfig()?.sites?.size ?: 0
            val usable = configStore.getConfig()?.sites?.count { it.usable } ?: 0
            text = if (total > 0) "站源 $usable / $total" else ""
            textSize = 14f
            setTextColor(TvUi.DIM)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
        })
        return bar
    }

    // ==================== 左侧导航 ====================

    private fun navItem(label: String, key: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            minimumHeight = dp(52)
            isFocusable = true
            isClickable = true
        }
        val bar = View(this).apply {
            background = roundedBar(Color.TRANSPARENT)
        }
        row.addView(bar, LinearLayout.LayoutParams(dp(BAR_W), dp(22)))

        val txt = TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(TvUi.SUB)
        }
        row.addView(txt, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(14)
        })

        navRefs[key] = NavRef(row, bar, txt)

        fun paint() {
            val sel = currentNav == key
            row.setBackgroundColor(if (sel) ROW_HI else Color.TRANSPARENT)
            bar.background = roundedBar(if (sel) Color.WHITE else Color.TRANSPARENT)
            txt.setTextColor(if (sel) Color.WHITE else TvUi.SUB)
            txt.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        paint()

        row.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) switchNav(key) }
        row.setOnClickListener { switchNav(key) }
        return row
    }

    private fun switchNav(key: String) {
        currentNav = key
        navRefs.forEach { (k, ref) ->
            val sel = k == key
            ref.row.setBackgroundColor(if (sel) ROW_HI else Color.TRANSPARENT)
            ref.bar.background = roundedBar(if (sel) Color.WHITE else Color.TRANSPARENT)
            ref.txt.setTextColor(if (sel) Color.WHITE else TvUi.SUB)
            ref.txt.typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        contentContainer.removeAllViews()
        when (key) {
            "source" -> contentContainer.addView(sectionSource())
            "update" -> contentContainer.addView(sectionUpdate())
            "webdav" -> contentContainer.addView(sectionWebdav())
            "sites" -> contentContainer.addView(sectionSites())
            "data" -> contentContainer.addView(sectionData())
            "about" -> contentContainer.addView(sectionAbout())
        }
    }

    // ==================== 分组：播放源 ====================

    private fun sectionSource(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupLabel("播放源"))
        val card = cardGroup()
        val subUrl = configStore.getSubscribeUrl() ?: ""
        card.addView(sRow("订阅", if (subUrl.isNotEmpty()) "已订阅" else "未订阅", arrow = true) {
            showInputDialog("订阅", "粘贴订阅链接或 TVBox JSON（点播 + 直播一起更新）", subUrl) { importSubscription(it) }
        })
        card.addView(sDiv())
        card.addView(sRow("点播配置", cfgState(), arrow = true) {
            showInputDialog("点播配置", "粘贴配置 JSON 或 URL", configStore.getConfigJson() ?: "") { importConfig(it) }
        })
        card.addView(sDiv())
        card.addView(sRow("直播源", currentLiveName(), arrow = true) {
            showInputDialog("直播源", "粘贴 m3u / txt 直播源地址", configStore.getLiveSourceUrl() ?: "") { saveLiveSource(it) }
        })
        card.addView(sDiv())
        card.addView(sRow("扫码录入", "手机扫码填地址", arrow = true) { showQrEntry() })

        // 订阅直播源候选列表（可展开）
        val presets = configStore.getSubLives()
        card.addView(sDiv())
        card.addView(sRow(
            "订阅直播源",
            if (presets.isNotEmpty()) "${presets.size} 个 · ${if (showPresets) "收起" else "展开"}" else "未订阅",
            arrow = true
        ) { showPresets = !showPresets; switchNav("source") })
        if (showPresets && presets.isNotEmpty()) {
            card.addView(sDiv())
            presets.forEach { p ->
                val hot = configStore.getLiveSourceUrl() == p.url
                card.addView(sChild(p.name, p.url, if (hot) "✓ 使用中" else "使用", hot) {
                    configStore.saveLiveSourceUrl(p.url)
                    switchNav("source")
                })
            }
        }
        col.addView(card)
        return col
    }

    private fun cfgState(): String {
        val sites = configStore.getConfig()?.sites ?: emptyList()
        return if (sites.isNotEmpty()) "已导入" else "未配置"
    }

    private fun currentLiveName(): String {
        val cur = configStore.getLiveSourceUrl() ?: return "未选择"
        val sub = configStore.getSubLives().firstOrNull { it.url == cur }
        return if (sub != null) sub.name else if (cur.isNotEmpty()) "自定义地址" else "未选择"
    }

    // ==================== 分组：内容更新 ====================

    private fun sectionUpdate(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupLabel("内容更新"))
        val card = cardGroup()
        card.addView(sRow("上次更新", crawlShort()))
        card.addView(sDiv())
        card.addView(sRow("立即更新", "逐站抓取最新影片", arrow = true) { runCrawlNow() })
        col.addView(card)
        return col
    }

    private fun crawlShort(): String {
        val s = configStore.getCrawlSummary()
        if (s.isEmpty()) return "还没有抓取过"
        return s.replace(" 更新完成", "").replace("个站源有响应", "站源")
            .replace("收到 ", "").replace("个无响应", "失效")
    }

    // ==================== 分组：网盘 ====================

    private fun sectionWebdav(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupLabel("网盘"))
        val card = cardGroup()
        val sources = configStore.getWebdavSources()
        card.addView(sRow("添加挂载", "${sources.size} 个已挂载", arrow = true) { showWdEdit(null) })
        if (sources.isEmpty()) {
            card.addView(sDiv())
            card.addView(subText("挂载 WebDAV 网盘（极空间 / 群晖 / AList 等）后，可在首页「网盘」里浏览并播放。"))
        } else {
            sources.forEach { src ->
                card.addView(sDiv())
                card.addView(sRow(src.name.ifEmpty { "未命名" }, src.url, arrow = true) { showWdEdit(src) })
            }
        }
        card.addView(sDiv())
        card.addView(sRow("打开网盘", "浏览已挂载的目录", arrow = true) { WebdavActivity.start(this) })
        col.addView(card)
        return col
    }

    // ==================== 分组：站源 ====================

    private fun sectionSites(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupLabel("站源"))
        val card = cardGroup()
        val sites = configStore.getConfig()?.sites ?: emptyList()
        card.addView(sRow("站源列表", "${sites.size} 个 · 已启用 ${enabledCount()}", arrow = true) {
            showAllSites = !showAllSites
            // 刷新站源列表区
            refreshSitesUI()
        })
        card.addView(sDiv())
        card.addView(sRow("测速排行", "按响应速度排序", arrow = true) { startSpeedTest() })
        card.addView(sDiv())
        card.addView(sRow("启用最快的 5 个", "", arrow = true) { enableBest5() })
        card.addView(sDiv())
        card.addView(sRow("全部启用", "", arrow = true) { enableAll() })
        card.addView(sDiv())
        card.addView(sRow("站源筛选", if (showAllSites) "全部" else "仅可用", arrow = true) {
            showAllSites = !showAllSites
            switchNav("sites")
        })

        // 站源明细列表
        card.addView(sDiv())
        sitesContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(sitesContainer)
        refreshSitesUI()
        col.addView(card)
        return col
    }

    private fun enabledCount(): Int {
        val enabled = configStore.getEnabledSites()
        val customized = configStore.isEnabledCustomized()
        val all = configStore.getConfig()?.sites ?: return 0
        return if (!customized) all.count { it.usable }
        else all.count { it.usable && enabled.contains(it.key) }
    }

    private fun refreshSitesUI() {
        if (!this::sitesContainer.isInitialized) return
        sitesContainer.removeAllViews()
        val all = configStore.getConfig()?.sites ?: return
        val sites = if (showAllSites) all else all.filter { it.usable }
        if (sites.isEmpty()) {
            sitesContainer.addView(subText("还没有导入配置。点上方的「点播配置」录入。"))
            return
        }
        val enabledKeys = configStore.getEnabledSites().toMutableSet()
        val customized = configStore.isEnabledCustomized()
        sites.forEach { site ->
            sitesContainer.addView(siteRow(site, !customized || enabledKeys.contains(site.key)) { on ->
                if (on) enabledKeys.add(site.key) else enabledKeys.remove(site.key)
                configStore.saveEnabledSites(enabledKeys)
            })
        }
    }

    private fun siteRow(site: Site, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), dp(8), dp(PAD_R), dp(8))
            minimumHeight = dp(58)
        }
        row.addView(View(this).apply {
            background = roundedBar(Color.TRANSPARENT)
        }, LinearLayout.LayoutParams(dp(BAR_W), dp(18)))

        val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameCol.addView(TextView(this).apply {
            text = site.name
            textSize = 15f
            setTextColor(if (site.usable) Color.WHITE else TvUi.DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        nameCol.addView(TextView(this).apply {
            text = if (site.usable) (if (site.type == 0) "XML 采集接口" else "JSON 采集接口") else site.reason
            textSize = 12f
            setTextColor(if (site.usable) TvUi.DIM else TvUi.WARN)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        row.addView(nameCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })

        if (site.usable) {
            row.addView(Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, isOn -> onToggle(isOn) }
            })
        } else {
            row.addView(TextView(this).apply {
                text = "不支持"
                textSize = 12f
                setTextColor(TvUi.WARN)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4).toFloat()
                    setStroke(dp(1), TvUi.WARN)
                }
            })
        }
        return row
    }

    // ==================== 分组：数据 ====================

    private fun sectionData(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupLabel("数据"))
        val card = cardGroup()
        card.addView(sRow("清除播放记录", "", warn = true) {
            historyStore.clear()
            Toast.makeText(this, "已清除播放记录", Toast.LENGTH_SHORT).show()
        })
        col.addView(card)
        return col
    }

    // ==================== 分组：关于 ====================

    private fun sectionAbout(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(groupLabel("关于"))
        val card = cardGroup()
        card.addView(sRow("点击声音", if (configStore.tapSound()) "开" else "关") {
            val next = !configStore.tapSound()
            configStore.setTapSound(next)
            Feedback.refresh(this)
            switchNav("about")
        })
        card.addView(sDiv())
        card.addView(sRow("点击震动", if (configStore.tapVibrate()) "开" else "关") {
            val next = !configStore.tapVibrate()
            configStore.setTapVibrate(next)
            Feedback.refresh(this)
            switchNav("about")
        })
        card.addView(sDiv())
        card.addView(sRow("片头跳过", skipLabel(configStore.introSkipSec())) { pickSkip("片头跳过") { configStore.setIntroSkipSec(it); switchNav("about") } })
        card.addView(sDiv())
        card.addView(sRow("片尾跳过", skipLabel(configStore.outroSkipSec())) { pickSkip("片尾跳过") { configStore.setOutroSkipSec(it); switchNav("about") } })
        card.addView(sDiv())
        card.addView(sRow("版本", "v${versionName()}"))
        card.addView(sDiv())
        card.addView(sRow("隐私政策", "查看", arrow = true) { showPrivacyDialog() })

        val crash = com.hong.tvbox.TvBoxApp.readCrash(this)
        if (crash.isNotEmpty()) {
            card.addView(sDiv())
            card.addView(sRow("上次崩溃日志", "查看", arrow = true) { showCrashLog(crash) })
        }
        col.addView(card)
        return col
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    private val skipOptions = listOf(0, 15, 30, 60, 90)
    private fun skipLabel(sec: Int): String = if (sec <= 0) "关" else "$sec 秒"
    private fun pickSkip(title: String, onPick: (Int) -> Unit) {
        val labels = skipOptions.map { skipLabel(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels) { _, which -> onPick(skipOptions[which]) }
            .show()
    }

    // ==================== 行式条目 ====================

    /** 焦点竖条 */
    private fun roundedBar(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(BAR_W).toFloat() / 2
        setColor(color)
    }

    /** tvOS 风格行：竖条 + 标题 + 当前值 + 箭头 */
    private fun sRow(
        label: String,
        value: String = "",
        arrow: Boolean = false,
        warn: Boolean = false,
        onClick: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(PAD_L), 0, dp(PAD_R), 0)
            minimumHeight = dp(ROW_H)
            isFocusable = true
            isClickable = true
        }
        val bar = View(this).apply { background = roundedBar(Color.TRANSPARENT) }
        row.addView(bar, LinearLayout.LayoutParams(dp(BAR_W), dp(20)))

        val title = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(if (warn) TvUi.WARN else Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(12)
        })

        var valueView: TextView? = null
        if (value.isNotEmpty()) {
            valueView = TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(if (warn) TvUi.DIM else TvUi.SUB)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(400)
            }
            row.addView(valueView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
            })
        }

        var arrowView: TextView? = null
        if (arrow) {
            arrowView = TextView(this).apply {
                text = "›"
                textSize = 20f
                setTextColor(TvUi.DIM)
            }
            row.addView(arrowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }

        fun paint(focused: Boolean) {
            row.setBackgroundColor(if (focused) ROW_HI else Color.TRANSPARENT)
            bar.background = roundedBar(if (focused) (if (warn) TvUi.WARN else Color.WHITE) else Color.TRANSPARENT)
            valueView?.setTextColor(if (focused) Color.WHITE else (if (warn) TvUi.DIM else TvUi.SUB))
            arrowView?.setTextColor(if (focused) Color.WHITE else TvUi.DIM)
        }
        row.setOnFocusChangeListener { _, hasFocus -> paint(hasFocus) }
        row.setOnHoverListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_HOVER_ENTER -> if (!row.hasFocus()) paint(true)
                android.view.MotionEvent.ACTION_HOVER_EXIT -> if (!row.hasFocus()) paint(false)
            }
            false
        }
        row.setOnClickListener { onClick?.invoke() }
        return row
    }

    /** 展开的子列表项：缩进 + 小一号 */
    private fun sChild(label: String, meta: String, value: String, hot: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(30), 0, dp(PAD_R), 0)
            minimumHeight = dp(42)
            isFocusable = true
            isClickable = true
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(if (hot) TvUi.SELECTED else Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(TextView(this).apply {
            text = meta
            textSize = 13f
            setTextColor(TvUi.DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(if (hot) TvUi.SELECTED else TvUi.SUB)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        row.setOnFocusChangeListener { _, hasFocus -> row.setBackgroundColor(if (hasFocus) ROW_HI else Color.TRANSPARENT) }
        row.setOnClickListener { onClick() }
        return row
    }

    // ==================== 分组标题 / 卡片 / 分割线 ====================

    private fun groupLabel(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 12.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(GROUP_TXT)
        letterSpacing = 0.08f
        setPadding(dp(18), 0, 0, dp(7))
    }

    private fun cardGroup(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(SURFACE)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(CARD_R.toInt()).toFloat()
            setColor(SURFACE)
        }
    }

    private fun sDiv(): View = View(this).apply {
        setBackgroundColor(DIV_COLOR)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).also { it.marginStart = dp(DIV_INSET); it.marginEnd = dp(DIV_INSET) }
    }

    private fun subText(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(TvUi.DIM)
        setLineSpacing(dp(3).toFloat(), 1f)
        setPadding(dp(30), dp(14), dp(PAD_R), dp(18))
    }

    // ==================== 输入弹窗 ====================

    private fun showInputDialog(title: String, hint: String, initial: String, onSubmit: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(initial)
            hint = hint
            textSize = 15f
            setTextColor(Color.WHITE)
            setHintTextColor(TvUi.DIM)
            maxLines = 3
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(CARD_INPUT)
                setStroke(dp(1), 0x40FFFFFF)
            }
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }
        wrap.addView(edit)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("确定") { _, _ -> onSubmit(edit.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 功能逻辑 ====================

    private fun importSubscription(input: String) {
        val v = input.trim()
        if (v.isEmpty()) {
            Toast.makeText(this, "请输入订阅链接或 JSON", Toast.LENGTH_SHORT).show(); return
        }
        lifecycleScope.launch {
            try {
                val text = if (v.startsWith("http://") || v.startsWith("https://")) {
                    configStore.saveSubscribeUrl(v)
                    HttpClient.text(v, timeoutMs = 20000)
                } else v
                val siteCount = configStore.loadConfig(text)
                configStore.resetEnabledSites()
                CmsClient.clearCache()
                absorbLives()
                Toast.makeText(this@SettingsActivity, "订阅导入成功，共 $siteCount 个站源", Toast.LENGTH_SHORT).show()
                switchNav("source")
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun importConfig(input: String) {
        val v = input.trim()
        if (v.isEmpty()) {
            Toast.makeText(this, "请输入配置地址或 JSON", Toast.LENGTH_SHORT).show(); return
        }
        lifecycleScope.launch {
            try {
                val text = if (v.startsWith("http://") || v.startsWith("https://")) {
                    HttpClient.text(v, timeoutMs = 20000)
                } else v
                val siteCount = configStore.loadConfig(text)
                configStore.resetEnabledSites()
                CmsClient.clearCache()
                absorbLives()
                Toast.makeText(this@SettingsActivity, "配置导入成功，共 $siteCount 个站源", Toast.LENGTH_SHORT).show()
                switchNav("source")
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveLiveSource(input: String) {
        val url = input.trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入直播源地址", Toast.LENGTH_SHORT).show(); return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "直播源必须是 http/https 地址", Toast.LENGTH_SHORT).show(); return
        }
        configStore.saveLiveSourceUrl(url)
        Toast.makeText(this, "直播源已保存", Toast.LENGTH_SHORT).show()
        switchNav("source")
    }

    private fun absorbLives() {
        val lives = configStore.getConfig()?.lives ?: return
        val valid = lives.filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        if (valid.isEmpty()) return
        configStore.saveSubLives(valid)
        val cur = configStore.getLiveSourceUrl()
        if (cur.isNullOrEmpty() || valid.none { it.url == cur }) {
            configStore.saveLiveSourceUrl(valid.first().url)
        }
    }

    private fun startSpeedTest() {
        val sites = configStore.getEnabledSiteList()
        if (sites.isEmpty()) {
            Toast.makeText(this, "没有可用站源", Toast.LENGTH_SHORT).show(); return
        }
        speedResults.clear()
        Toast.makeText(this, "开始测速 ${sites.size} 个站源…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            sites.forEach { site ->
                val t0 = System.currentTimeMillis()
                val ok = try {
                    HttpClient.text(
                        site.api + (if (site.api.contains('?')) "&" else "?") + "ac=list",
                        timeoutMs = 5000
                    )
                    true
                } catch (e: Exception) {
                    false
                }
                speedResults.add(Pair(site, if (ok) System.currentTimeMillis() - t0 else Long.MAX_VALUE))
            }
            speedResults.sortBy { it.second }
            refreshSpeedResultsUI()
        }
    }

    private fun refreshSpeedResultsUI() {
        if (!this::sitesContainer.isInitialized) return
        sitesContainer.removeAllViews()
        speedResults.forEachIndexed { index, (site, ms) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(30), dp(8), dp(PAD_R), dp(8))
            }
            row.addView(TextView(this).apply {
                text = "${index + 1}."
                textSize = 15f
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(TextView(this).apply {
                text = site.name
                textSize = 15f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = if (ms == Long.MAX_VALUE) "不通" else "$ms ms"
                textSize = 14f
                setTextColor(when {
                    ms == Long.MAX_VALUE -> TvUi.WARN
                    ms < 1000 -> Color.parseColor("#00FF88")
                    ms < 3000 -> TvUi.SELECTED
                    else -> TvUi.WARN
                })
            })
            sitesContainer.addView(row)
        }
    }

    private fun enableBest5() {
        val best5 = speedResults.take(5).filter { it.second != Long.MAX_VALUE }.map { it.first.key }.toSet()
        if (best5.isEmpty()) {
            Toast.makeText(this, "还没有测速结果，先点「测速排行」", Toast.LENGTH_SHORT).show(); return
        }
        configStore.saveEnabledSites(best5)
        switchNav("sites")
        Toast.makeText(this, "已启用最快 ${best5.size} 个站源", Toast.LENGTH_SHORT).show()
    }

    private fun enableAll() {
        configStore.resetEnabledSites()
        switchNav("sites")
        Toast.makeText(this, "已恢复全部启用", Toast.LENGTH_SHORT).show()
    }

    private fun runCrawlNow() {
        val sites = configStore.getEnabledSiteList()
        if (sites.isEmpty()) {
            Toast.makeText(this, "没有可用站源", Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(this, "开始抓取 ${sites.size} 个站源…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val r = com.hong.tvbox.core.Crawler.run(sites)
                configStore.saveFeed(r.list)
                configStore.setLastCrawlAt(r.at)
                val summary = "抓取 ${r.sites} 个站源，获得 ${r.items} 条" +
                        if (r.dead.isEmpty()) "" else "，${r.dead.size} 个站源不可用"
                configStore.saveCrawlSummary(summary)
                Toast.makeText(this@SettingsActivity, summary, Toast.LENGTH_SHORT).show()
                switchNav("update")
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, "抓取失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 扫码录入 ====================

    private fun showQrEntry() {
        val server = qrServer ?: com.hong.tvbox.core.QrServer(8788) { form ->
            runOnUiThread { applyQrForm(form) }
        }.also { qrServer = it }

        if (!server.start()) {
            Toast.makeText(this, "端口 8788 被占用，无法启动扫码服务", Toast.LENGTH_LONG).show()
            return
        }

        val url = server.url()
        val qr = com.hong.tvbox.core.QrCode.encode(url, 520)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val img = ImageView(this).apply {
            setImageBitmap(qr)
            if (qr == null) setBackgroundColor(Color.WHITE)
        }
        box.addView(img, LinearLayout.LayoutParams(dp(260), dp(260)))
        box.addView(TextView(this).apply {
            text = url
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        })
        box.addView(TextView(this).apply {
            text = "手机与电视连同一 Wi-Fi，扫码后填写订阅/直播源/配置，提交即生效。\n关闭本窗口后服务立即停止。"
            textSize = 13f
            setTextColor(TvUi.SUB)
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        qrDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("扫码录入")
            .setView(box)
            .setPositiveButton("关闭") { _, _ -> }
            .setOnDismissListener { server.stop() }
            .show()
    }

    private fun applyQrForm(form: Map<String, String>) {
        val subscribe = form["subscribe"].orEmpty()
        val live = form["live"].orEmpty()
        val config = form["config"].orEmpty()
        if (subscribe.isNotEmpty()) importSubscription(subscribe)
        if (live.isNotEmpty()) saveLiveSource(live)
        if (config.isNotEmpty()) importConfig(config)
        if (subscribe.isEmpty() && live.isEmpty() && config.isEmpty()) {
            Toast.makeText(this, "手机端没有填写内容", Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== 网盘源编辑 ====================

    private fun showWdEdit(src: WebdavSource?) {
        val isNew = src == null
        val name = EditText(this).apply { setText(src?.name ?: ""); hint = "名称（可留空，默认取域名）" }
        val url = EditText(this).apply { setText(src?.url ?: ""); hint = "服务器地址，如 https://dav.example.com/dav" }
        val user = EditText(this).apply { setText(src?.user ?: ""); hint = "用户名（匿名可留空）" }
        val pass = EditText(this).apply {
            setText(src?.pass ?: "")
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val root = EditText(this).apply { setText(src?.root ?: ""); hint = "挂载根路径，如 /影视（可留空 = 根目录）" }

        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
        }
        listOf(name, url, user, pass, root).forEach { e ->
            e.textSize = 15f
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(TvUi.DIM)
            e.setSingleLine(true)
            e.setPadding(dp(14), dp(10), dp(14), dp(10))
            e.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(CARD_INPUT)
                setStroke(dp(1), 0x40FFFFFF)
            }
            wrap.addView(e, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(if (isNew) "添加挂载" else "编辑挂载")
            .setView(wrap)
            .setNegativeButton("取消", null)
        if (!isNew) {
            builder.setNeutralButton("删除") { _, _ ->
                configStore.removeWebdavSource(src.id)
                switchNav("webdav")
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setPositiveButton("保存") { _, _ ->
            val u = url.text.toString().trim()
            if (u.isEmpty() || !(u.startsWith("http://") || u.startsWith("https://"))) {
                Toast.makeText(this, "请填正确的服务器地址", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            configStore.saveWebdavSource(
                WebdavSource(
                    id = src?.id ?: "",
                    name = name.text.toString().trim().ifEmpty { u.substringAfter("://").substringBefore("/") },
                    url = u,
                    user = user.text.toString().trim(),
                    pass = pass.text.toString(),
                    root = root.text.toString().trim()
                )
            )
            switchNav("webdav")
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        }.show()
    }

    // ==================== 隐私 / 崩溃日志 ====================

    private fun showPrivacyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("隐私政策")
            .setMessage(
                """
                本应用没有注册、登录、手机号、邮箱等账号体系，也没有自己的服务器。
                你的源地址、收藏、观看历史、播放进度、画质偏好全部只保存在本机，卸载即清除。

                联网只做这些事：请求你自己填写的点播配置地址与直播源地址，读取其中列出的
                站点接口，并按地址播放视频流；点「测速排行」时向这些地址发一次探测请求。
                这些请求由你的设备直接发往第三方，不经过开发者。

                权限只用三项：联网、读取网络状态、震动。不申请位置、通讯录、短信、相机、
                麦克风、相册、设备标识符等任何权限。

                本应用不包含广告与统计 SDK，无推送、无埋点、无崩溃上报。

                本应用不提供、不存储、不分发任何影视内容，仅为播放器与源管理工具。
                内容全部来自你自行填写的第三方接口，请确保来源合法。
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun showCrashLog(log: String) {
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = log
            textSize = 12f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            typeface = Typeface.MONOSPACE
        }
        scroll.addView(tv)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("上次崩溃日志")
            .setView(scroll)
            .setPositiveButton("关闭") { _, _ -> }
            .setNegativeButton("清除") { _, _ ->
                com.hong.tvbox.TvBoxApp.clearCrash(this)
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        qrDialog?.dismiss()
        qrServer?.stop()
        qrServer = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}

// 扩展 ConfigStore：支持完整的配置加载
fun ConfigStore.loadConfig(json: String): Int {
    saveConfigJson(json)
    return getConfig()?.sites?.size ?: 0
}
