package com.hong.tvbox.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hong.tvbox.core.*
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.FavoriteStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 主浏览页 - 移植自 Home.ets
 * 结构：顶栏 + 功能行（点播/搜索/收藏/历史/设置）+ 站源内容行
 */
class MainBrowseFragment : BrowseSupportFragment() {

    private lateinit var configStore: ConfigStore
    private lateinit var favoriteStore: FavoriteStore
    private lateinit var historyStore: HistoryStore
    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    /** 防重入：抓取进行中再次 onResume 不要并发跑第二轮 */
    private var crawlRunning = false
    /** 「加载中」占位行的位置，-1 表示没有（Leanback 这个版本没有可用的 ProgressBarManager） */
    private var loadingRowIndex = -1
    private var pendingSites = 0

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        configStore = ConfigStore(requireContext())
        favoriteStore = FavoriteStore(requireContext())
        historyStore = HistoryStore(requireContext())

        title = "鸿蒙影视"
        // 左侧 headers 区必须保留（HIDDEN 会让整个内容区不渲染），
        // 但它的底色由 brandColor 决定：之前设成白色，整条竖栏是白的、
        // 白色标题也看不见。改成和背景同色，视觉上就"消失"了。
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = Color.parseColor("#141218")
        searchAffordanceColor = Color.parseColor("#FFEB3B")
        adapter = rowsAdapter

        // 搜索图标点击
        setOnSearchClickedListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        onItemViewClickedListener = ItemViewClickedListener()
    }

    /**
     * 每次回到首页都重建内容行。
     *
     * 从「设置」导入配置/订阅后返回，如果不重建，首页还是首启那段引导文字，
     * 用户会以为导入没生效。放在 onResume 而不是 onActivityCreated，
     * 顺带覆盖了「看完片返回，继续观看进度要更新」的场景。
     */
    override fun onResume() {
        super.onResume()
        rowsAdapter.clear()
        loadContent()
    }

    private fun loadContent() {
        val config = configStore.getConfig()

        // 行列表刚被清空，占位行索引也要一起复位
        loadingRowIndex = -1

        // 功能入口行必须最先加：没有配置时用户正是靠这一行的「设置」去导入，
        // 之前把它放在配置判断之后，导致首启页只有一段说明文字、进不去设置，形成死锁
        addFunctionRow()

        if (config == null || config.sites.isEmpty()) {
            showConfigGuide()
            return
        }

        // 继续观看行（历史）
        addHistoryRow()

        // 最近更新行（上一轮定时抓取的结果，没有就跳过）
        addFeedRow()

        // 每个启用的站源加载内容
        val sites = configStore.getEnabledSiteList()
        if (sites.isNotEmpty()) {
            pendingSites = sites.size
            showLoadingRow()
        }
        sites.forEach { site ->
            loadSiteContent(site)
        }

        if (sites.isEmpty()) {
            showNoSitesMessage()
        }

        // 超过 6 小时没抓过，就在后台跑一轮（不阻塞界面）
        maybeCrawl(sites)
    }

    /** 「最近更新」：定时抓取攒下来的条目，点开进详情 */
    private fun addFeedRow() {
        val feed = configStore.getFeed()
        if (feed.isEmpty()) return

        val adapter = ArrayObjectAdapter(VodCardPresenter())
        feed.take(20).forEach { adapter.add(it) }

        val header = HeaderItem(rowsAdapter.size().toLong(), "最近更新 · ${feed.size} 部")
        rowsAdapter.add(ListRow(header, adapter))
    }

    /**
     * 定时抓取：每 6 小时一轮，抓各站最新片单并探测站源存活。
     * 抓完立刻重建首页行，用户不用退出重进也能看到「最近更新」。
     */
    private fun maybeCrawl(sites: List<Site>) {
        if (sites.isEmpty() || crawlRunning) return

        val last = configStore.getLastCrawlAt()
        val sixHours = 6 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - last < sixHours) return

        crawlRunning = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val r = Crawler.run(sites)
                configStore.saveFeed(r.list)
                configStore.setLastCrawlAt(r.at)
                configStore.saveCrawlSummary(
                    "抓取 ${r.sites} 个站源，获得 ${r.items} 条" +
                            if (r.dead.isEmpty()) "" else "，${r.dead.size} 个站源不可用"
                )

                if (isAdded && r.list.isNotEmpty()) {
                    rowsAdapter.clear()
                    loadContent()
                }
            } catch (e: Exception) {
                // 抓取失败不影响浏览，下一轮再试
            } finally {
                crawlRunning = false
            }
        }
    }

    private fun addFunctionRow() {
        val funcs = listOf(
            FuncItem("点播", "vod"),
            FuncItem("直播", "live"),
            FuncItem("直连", "direct"),
            FuncItem("网盘", "webdav"),
            FuncItem("搜索", "search"),
            FuncItem("收藏", "favorites"),
            FuncItem("历史", "history"),
            FuncItem("设置", "settings")
        )

        val adapter = ArrayObjectAdapter(FuncPresenter())
        funcs.forEach { adapter.add(it) }

        val header = HeaderItem(0, "功能")
        rowsAdapter.add(ListRow(header, adapter))
    }

    private fun addHistoryRow() {
        val history = historyStore.getAll()
        if (history.isEmpty()) return

        // 用带进度条的卡片：能一眼看出「上次看到哪」，比纯海报更有信息量
        val adapter = ArrayObjectAdapter(HistoryCardPresenter())
        history.take(20).forEach { adapter.add(it) }

        val header = HeaderItem(1, "继续观看")
        rowsAdapter.add(ListRow(header, adapter))
    }

    /**
     * 加载占位行。
     *
     * 站源多的时候首屏要等 3~5 秒，之前这段时间只有一行「功能」，
     * 看起来像加载失败。这里插一行提示，等所有站源回来再摘掉。
     */
    private fun showLoadingRow() {
        if (loadingRowIndex >= 0) return
        val adapter = ArrayObjectAdapter(StringPresenter())
        adapter.add("正在加载站源内容…")

        loadingRowIndex = rowsAdapter.size()
        val header = HeaderItem(loadingRowIndex.toLong(), "加载中")
        rowsAdapter.add(ListRow(header, adapter))
    }

    private fun hideLoadingRow() {
        val idx = loadingRowIndex
        loadingRowIndex = -1
        if (idx >= 0 && idx < rowsAdapter.size()) {
            rowsAdapter.removeItems(idx, 1)
        }
    }

    private fun loadSiteContent(site: Site) {
        lifecycleScope.launch {
            try {
                val cms = CmsClient(site)
                val categories = cms.categories()

                categories.take(3).forEach { cat ->
                    val page = cms.list(cat.id, 1)
                    if (page.items.isNotEmpty()) {
                        val adapter = ArrayObjectAdapter(VodCardPresenter())
                        page.items.take(20).forEach { vod ->
                            adapter.add(vod)
                        }
                        val header = HeaderItem(rowsAdapter.size().toLong(), "${site.name} · ${cat.name}")
                        rowsAdapter.add(ListRow(header, adapter))
                    }
                }
            } catch (e: Exception) {
                // 站源加载失败，跳过
            } finally {
                // 最后一个站源回来就收起加载行（含失败的情况，否则会一直显示"加载中"）
                pendingSites--
                if (pendingSites <= 0) {
                    pendingSites = 0
                    hideLoadingRow()
                }
            }
        }
    }

    /**
     * 首启引导。
     *
     * 必须用「单个可换行段落」渲染：之前把每句话当成一个 ListRow 项，
     * 而 ListRow 是横向排布的，结果几段话挤成一行还互相裁切。
     */
    private fun showConfigGuide() {
        val text = """
            欢迎使用鸿蒙影视 Android TV 版

            当前还没有导入配置，按下面三步即可开始：
            1. 上方「功能」行 → 设置
            2. 粘贴订阅链接，或直接粘贴 TVBox 配置 JSON
            3. 导入后回到首页，站源内容会自动加载

            也可以点「扫码录入」，用手机在同 Wi-Fi 下扫码填写，免去遥控器输入。
        """.trimIndent()

        val adapter = ArrayObjectAdapter(GuidePresenter())
        adapter.add(text)

        val header = HeaderItem(0, "使用指南")
        rowsAdapter.add(ListRow(header, adapter))
    }

    private fun showNoSitesMessage() {
        val adapter = ArrayObjectAdapter(GuidePresenter())
        adapter.add("已导入配置，但没有可用的协议型站源。\n请到「设置 → 站源管理」检查开关，或重新导入订阅。")

        val header = HeaderItem(rowsAdapter.size().toLong(), "提示")
        rowsAdapter.add(ListRow(header, adapter))
    }

    private inner class ItemViewClickedListener : OnItemViewClickedListener {
        override fun onItemClicked(
            itemViewHolder: Presenter.ViewHolder?,
            item: Any?,
            rowViewHolder: RowPresenter.ViewHolder?,
            row: Row?
        ) {
            when (item) {
                is FuncItem -> {
                    val ctx = requireContext()
                    when (item.action) {
                        "vod" -> VodBrowseActivity.start(ctx)
                        "live" -> LiveActivity.start(ctx)
                        "direct" -> DirectActivity.start(ctx)
                        "webdav" -> WebdavActivity.start(ctx)
                        "search" -> startActivity(Intent(ctx, SearchActivity::class.java))
                        "favorites" -> FavoritesActivity.start(ctx)
                        "history" -> HistoryActivity.start(ctx)
                        "settings" -> SettingsActivity.start(ctx)
                    }
                }
                is Vod -> {
                    DetailActivity.start(requireContext(), item)
                }
                is History -> {
                    // 点「继续观看」直接续播，不绕详情页
                    PlaybackResumer.resume(
                        scope = viewLifecycleOwner.lifecycleScope,
                        context = requireContext(),
                        configStore = configStore,
                        h = item
                    ) { msg ->
                        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

// ========== 功能入口项 ==========

data class FuncItem(
    val label: String,
    val action: String
)

class FuncPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = TextView(parent.context).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(dp(parent.context, 24), dp(parent.context, 20), dp(parent.context, 24), dp(parent.context, 20))
            setBackgroundColor(Color.parseColor("#33000000"))
            width = dp(parent.context, 160)
            height = dp(parent.context, 100)
            gravity = android.view.Gravity.CENTER
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = (item as? FuncItem)?.label ?: ""
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

// ========== 视频卡片 Presenter ==========

class VodCardPresenter : Presenter() {

    companion object {
        private const val CARD_WIDTH = 200
        private const val CARD_HEIGHT = 280
    }

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
            setBackgroundColor(Color.parseColor("#33000000"))
        }
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val vod = item as? Vod ?: return
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = vod.name
        cardView.contentText = if (vod.remark.isNotEmpty()) vod.remark else vod.siteName

        // 使用 Glide 加载海报
        if (vod.pic.isNotEmpty()) {
            try {
                val target = cardView.mainImageView
                if (target != null) {
                    Glide.with(cardView.context)
                        .load(vod.pic)
                        .centerCrop()
                        .into(target)
                }
            } catch (e: Exception) {
                // 图片加载失败，显示占位
            }
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.badgeImage = null
        cardView.mainImage = null
    }
}

// ========== 字符串 Presenter ==========

class StringPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = TextView(parent.context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(dp(parent.context, 16), dp(parent.context, 8), dp(parent.context, 16), dp(parent.context, 8))
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = item?.toString() ?: ""
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}

private fun dp(context: android.content.Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * 段落型 Presenter：一块固定宽度、可换行的文本。
 * 用于首启引导这类多行说明 —— ListRow 是横向排布的，
 * 把每行文字拆成多个 item 会挤成一排还互相裁切。
 */
class GuidePresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val ctx = parent.context
        val tv = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFDDDDDD.toInt())
            setLineSpacing(dp(ctx, 6).toFloat(), 1f)
            setPadding(dp(ctx, 16), dp(ctx, 8), dp(ctx, 16), dp(ctx, 8))
            maxLines = 20
            layoutParams = ViewGroup.LayoutParams(
                dp(ctx, 820),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        (viewHolder.view as TextView).text = item as? String ?: ""
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {}
}
