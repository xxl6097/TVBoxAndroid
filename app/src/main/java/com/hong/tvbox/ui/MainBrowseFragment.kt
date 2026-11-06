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

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        configStore = ConfigStore(requireContext())
        favoriteStore = FavoriteStore(requireContext())
        historyStore = HistoryStore(requireContext())

        title = "鸿蒙影视"
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = Color.parseColor("#FFFFFF")
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

        // 功能入口行必须最先加：没有配置时用户正是靠这一行的「设置」去导入，
        // 之前把它放在配置判断之后，导致首启页只有一段说明文字、进不去设置，形成死锁
        addFunctionRow()

        if (config == null || config.sites.isEmpty()) {
            showConfigGuide()
            return
        }

        // 继续观看行（历史）
        addHistoryRow()

        // 每个启用的站源加载内容
        val sites = configStore.getEnabledSiteList()
        sites.forEach { site ->
            loadSiteContent(site)
        }

        if (sites.isEmpty()) {
            showNoSitesMessage()
        }
    }

    private fun addFunctionRow() {
        val funcs = listOf(
            FuncItem("点播", "vod"),
            FuncItem("直播", "live"),
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

        val adapter = ArrayObjectAdapter(VodCardPresenter())
        history.take(20).forEach { h ->
            val vod = Vod(
                id = h.vodId,
                name = h.vodName,
                pic = h.vodPic,
                remark = h.episodeName,
                siteKey = h.siteKey
            )
            adapter.add(vod)
        }

        val header = HeaderItem(1, "继续观看")
        rowsAdapter.add(ListRow(header, adapter))
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
            }
        }
    }

    private fun showConfigGuide() {
        val adapter = ArrayObjectAdapter(StringPresenter())
        adapter.add("欢迎使用鸿蒙影视 Android TV 版")
        adapter.add("")
        adapter.add("当前未导入配置，请按以下步骤操作：")
        adapter.add("1. 进入设置页面（上方「功能」行 → 设置）")
        adapter.add("2. 粘贴订阅链接或 TVBox 配置 JSON")
        adapter.add("3. 导入后自动加载站源内容")

        val header = HeaderItem(0, "使用指南")
        rowsAdapter.add(ListRow(header, adapter))
    }

    private fun showNoSitesMessage() {
        val adapter = ArrayObjectAdapter(StringPresenter())
        adapter.add("已导入配置，但没有可用的协议型站源")
        adapter.add("请在设置页面检查站源开关")

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
                        "search" -> startActivity(Intent(ctx, SearchActivity::class.java))
                        "favorites" -> FavoritesActivity.start(ctx)
                        "history" -> HistoryActivity.start(ctx)
                        "settings" -> SettingsActivity.start(ctx)
                    }
                }
                is Vod -> {
                    DetailActivity.start(requireContext(), item)
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
