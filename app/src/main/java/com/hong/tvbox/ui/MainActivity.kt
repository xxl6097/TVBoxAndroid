package com.hong.tvbox.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hong.tvbox.Feedback
import com.hong.tvbox.R
import com.hong.tvbox.core.CmsClient
import com.hong.tvbox.core.Crawler
import com.hong.tvbox.core.History
import com.hong.tvbox.core.Site
import com.hong.tvbox.core.Vod
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 首页。
 *
 * 早期版本用 Leanback 的 BrowseSupportFragment，但它对 headers 状态很挑：
 * HEADERS_ENABLED 会留一条空白左栏、行标题与卡片不对齐；
 * HIDDEN / DISABLED 又完全不渲染内容（leanback 1.2.0-alpha04 实测）。
 * 与其和它较劲，不如自己排：纵向 ScrollView + 每行一个横向滚动条，
 * 焦点交给系统 FocusFinder，ScrollView 会自动把获得焦点的卡片滚进视野。
 *
 * 首启仍然先弹隐私政策，未同意不发起任何网络请求。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var historyStore: HistoryStore

    private lateinit var content: LinearLayout
    private lateinit var clockView: TextView
    private val clockTicker = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (this@MainActivity::clockView.isInitialized) {
                val d = java.util.Calendar.getInstance()
                clockView.text = String.format(
                    "%02d:%02d:%02d",
                    d.get(java.util.Calendar.HOUR_OF_DAY),
                    d.get(java.util.Calendar.MINUTE),
                    d.get(java.util.Calendar.SECOND)
                )
                clockTicker.postDelayed(this, 1000)
            }
        }
    }

    private var pendingSites = 0
    private var crawlRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        historyStore = HistoryStore(this)

        if (configStore.isPrivacyAgreed()) {
            buildHome()
        } else {
            showPrivacyDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        // 从详情/设置返回时刷新「继续观看」与抓取结果
        if (configStore.isPrivacyAgreed() && this::content.isInitialized) {
            renderDynamic()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        clockTicker.removeCallbacks(clockRunnable)
    }

    // ============ 骨架 ============

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
            setPadding(dp(24), dp(18), dp(24), dp(24))
        }

        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            // 容器自己不接收焦点，否则方向键只会滚动页面、焦点永远落不到卡片上
            isFocusable = false
            isFocusableInTouchMode = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(24))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        renderDynamic()

        // TV 上没有触摸，必须主动给第一个可聚焦控件一个初始焦点，
        // 否则方向键只会滚页面，看起来像"遥控器失灵"
        root.post {
            val first = findFirstFocusable(content)
            first?.requestFocus()
        }
    }

    /** 深度优先找第一个可聚焦控件 */
    private fun findFirstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            if (child is ViewGroup) {
                findFirstFocusable(child)?.let { return it }
            } else if (child.isFocusable) {
                return child
            }
        }
        return null
    }

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        // logo 48dp + 标题 24sp + 右侧时钟 24sp（对照鸿蒙首页 toolbar）
        bar.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, LinearLayout.LayoutParams(dp(48), dp(48)).also { it.marginEnd = dp(12) })

        bar.addView(TextView(this).apply {
            text = "鸿蒙影视"
            textSize = 24f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        clockView = TextView(this).apply {
            text = "00:00:00"
            textSize = 24f
            setTextColor(Color.WHITE)
        }
        bar.addView(clockView)
        clockTicker.removeCallbacks(clockRunnable)
        clockTicker.post(clockRunnable)

        return bar
    }

    private fun renderDynamic() {
        if (!this::content.isInitialized) return
        content.removeAllViews()

        addFunctionRow()
        addContinueRow()
        addFeedRow()
        loadSites()
    }

    // ============ 功能行 ============

    private fun addFunctionRow() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        listOf(
            "点播" to { VodBrowseActivity.start(this) },
            "直播" to { LiveActivity.start(this) },
            "直连" to { DirectActivity.start(this) },
            "网盘" to { WebdavActivity.start(this) },
            "搜索" to { startActivity(Intent(this, SearchActivity::class.java)) },
            "收藏" to { FavoritesActivity.start(this) },
            "历史" to { HistoryActivity.start(this) },
            "设置" to { SettingsActivity.start(this) }
        ).forEach { pair ->
            row.addView(funcCell(pair.first, pair.second))
        }

        content.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(16) })
    }

    /** 功能块：图标 34dp + 文字 17sp，1fr×8 等分，聚焦放大 1.1 */
    private fun funcCell(label: String, onClick: () -> Unit): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener {
                Feedback.tap(this@MainActivity)
                onClick()
            }
            onTvFocus(scale = 1.1f)
        }
        cell.addView(ImageView(this).apply {
            setImageResource(funcIcon(label))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        cell.addView(TextView(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            maxLines = 1
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(4) })

        cell.layoutParams = LinearLayout.LayoutParams(0, dp(74), 1f)
        return cell
    }

    private fun funcIcon(label: String): Int = when (label) {
        "直播" -> R.drawable.ic_live
        "搜索" -> R.drawable.ic_search
        "收藏" -> R.drawable.ic_collect
        "历史" -> R.drawable.ic_history
        "设置" -> R.drawable.ic_setting
        else -> R.drawable.ic_vod
    }

    // ============ 继续观看 ============

    private fun addContinueRow() {
        val history = historyStore.getAll()
        if (history.isEmpty()) return

        content.addView(sectionTitle("继续观看"))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        history.take(12).forEach { row.addView(historyCard(it)) }
        content.addView(hScroller(row))
    }

    private fun historyCard(h: History): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val cover = FrameLayout(this)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A272E"))
        }
        cover.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
        ))
        if (h.vodPic.isNotEmpty()) {
            Glide.with(this).load(h.vodPic).centerCrop().into(img)
        }

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (h.duration > 0) {
                ((h.position * 1000) / h.duration).toInt().coerceIn(0, 1000)
            } else 0
            visibility = if (h.duration > 0 && h.position > 0) View.VISIBLE else View.GONE
        }
        cover.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM
        ))
        card.addView(cover, LinearLayout.LayoutParams(dp(196), dp(112)))

        card.addView(elided(h.vodName, 13f, TvUi.TEXT).apply { setPadding(0, dp(6), 0, 0) })

        // 副标题：有进度就显示「看到 1:13 / 2:00:05」。
        // 网盘/直连记录的剧集名和片名相同，直接展示会重复成两行一样的字
        val ep = when {
            h.duration > 0 && h.position > 0 -> "看到 ${fmtTime(h.position)} / ${fmtTime(h.duration)}"
            h.episodeName.isNotEmpty() && h.episodeName != h.vodName -> h.episodeName
            else -> "第 ${h.episodeIndex + 1} 集"
        }
        card.addView(elided(ep, 11f, TvUi.DIM))

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginEnd = dp(10) }

        // 点「继续观看」直接续播，不绕详情页
        card.setOnClickListener {
            Feedback.tap(this@MainActivity)
            PlaybackResumer.resume(lifecycleScope, this, configStore, h) { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        card.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1.04f)
        return card
    }

    // ============ 最近更新 ============

    private fun addFeedRow() {
        val feed = configStore.getFeed()
        if (feed.isEmpty()) return

        content.addView(sectionTitle("最近更新 · ${feed.size} 部"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        feed.take(16).forEach { row.addView(vodCard(it)) }
        content.addView(hScroller(row))
    }

    // ============ 站源行 ============

    private fun loadSites() {
        val sites = configStore.getEnabledSiteList()
        if (sites.isEmpty()) {
            content.addView(guideBlock())
            return
        }

        pendingSites = sites.size
        val loading = sectionTitle("正在加载站源内容…")
        content.addView(loading)

        sites.forEach { site ->
            lifecycleScope.launch {
                try {
                    val cms = CmsClient(site)
                    cms.categories().take(3).forEach { cat ->
                        val page = cms.list(cat.id, 1)
                        if (page.items.isNotEmpty()) {
                            addSiteRow("${site.name} · ${cat.name}", page.items.take(16))
                        }
                    }
                } catch (e: Exception) {
                    // 单站失败不影响其它站
                } finally {
                    pendingSites--
                    if (pendingSites <= 0 && this@MainActivity::content.isInitialized) {
                        content.removeView(loading)
                        maybeCrawl(sites)
                    }
                }
            }
        }
    }

    private fun addSiteRow(title: String, items: List<Vod>) {
        if (!this::content.isInitialized) return
        content.addView(sectionTitle(title))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { row.addView(vodCard(it)) }
        content.addView(hScroller(row))
    }

    private fun vodCard(vod: Vod): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A272E"))
        }
        card.addView(img, LinearLayout.LayoutParams(dp(132), dp(186)))
        if (vod.pic.isNotEmpty()) {
            Glide.with(this).load(vod.pic).centerCrop().into(img)
        }

        card.addView(elided(vod.name, 13f, TvUi.TEXT).apply { setPadding(0, dp(6), 0, 0) })
        if (vod.remark.isNotEmpty()) {
            card.addView(elided(vod.remark, 11f, TvUi.DIM))
        }

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginEnd = dp(12) }

        card.setOnClickListener {
            Feedback.tap(this@MainActivity)
            DetailActivity.start(this, vod)
        }
        card.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1.05f)
        return card
    }

    // ============ 定时抓取 ============

    private fun maybeCrawl(sites: List<Site>) {
        if (crawlRunning) return
        val sixHours = 6 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - configStore.getLastCrawlAt() < sixHours) return

        crawlRunning = true
        lifecycleScope.launch {
            try {
                val r = Crawler.run(sites)
                configStore.saveFeed(r.list)
                configStore.setLastCrawlAt(r.at)
                configStore.saveCrawlSummary(
                    "抓取 ${r.sites} 个站源，获得 ${r.items} 条" +
                            if (r.dead.isEmpty()) "" else "，${r.dead.size} 个站源不可用"
                )
                if (r.list.isNotEmpty() && this@MainActivity::content.isInitialized) {
                    renderDynamic()
                }
            } catch (e: Exception) {
                // 下一轮再试
            } finally {
                crawlRunning = false
            }
        }
    }

    // ============ 小组件 ============

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(TvUi.SUB)
            setPadding(dp(2), dp(14), 0, dp(8))
        }
    }

    private fun elided(text: String, size: Float, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(140)
        }
    }

    private fun hScroller(row: LinearLayout): View {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun guideBlock(): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            setBackgroundColor(TvUi.CARD)
        }

        block.addView(TextView(this).apply {
            text = "欢迎使用鸿蒙影视 Android TV 版"
            textSize = 18f
            setTextColor(Color.WHITE)
        })

        listOf(
            "当前还没有导入配置，按下面两步即可开始：",
            "1. 右上角「设置」→ 粘贴订阅链接，或直接粘贴 TVBox 配置 JSON",
            "2. 导入后回到首页，站源内容会自动加载",
            "",
            "也可以点「设置 → 扫码录入」，手机连同一 Wi-Fi 扫码填写，免遥控器输入。"
        ).forEach { line ->
            block.addView(TextView(this).apply {
                text = line
                textSize = 15f
                setTextColor(TvUi.SUB)
                setLineSpacing(dp(5).toFloat(), 1f)
                setPadding(0, dp(6), 0, 0)
            })
        }
        return block
    }

    // ============ 隐私 ============

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("隐私政策")
            .setMessage(
                """
                本应用是本地影视索引工具，自身不提供、不存储任何影片内容。

                1. 应用只申请「网络访问」权限，用于读取你自行导入的站源/订阅配置；
                2. 你填写的站源地址、订阅链接、播放记录、收藏均只保存在本机，
                   不会上传到任何服务器；
                3. 点播内容来自第三方站点，其内容与隐私实践由该站点负责；
                4. 你可以随时在系统设置中清除本应用数据以删除全部本地记录。

                点击「同意并继续」表示你已阅读并同意上述说明。
                """.trimIndent()
            )
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                configStore.setPrivacyAgreed()
                buildHome()
            }
            .setNegativeButton("不同意") { _, _ -> finishAffinity() }
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    /** 毫秒 → mm:ss / h:mm:ss */
    private fun fmtTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
