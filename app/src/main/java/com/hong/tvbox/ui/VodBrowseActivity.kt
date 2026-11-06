package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hong.tvbox.core.*
import com.hong.tvbox.data.ConfigStore
import kotlinx.coroutines.launch

/**
 * 点播页 - 对照鸿蒙 VodPage.ets
 *
 * 结构：顶栏（返回 / 站点名 / 换站 / 刷新）+ 分类胶囊行 + 海报网格 + 翻页
 */
class VodBrowseActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore

    private lateinit var titleView: TextView
    private lateinit var catRow: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var gridLayout: GridLayout
    private lateinit var pageInfo: TextView
    private lateinit var prevBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var statusView: TextView
    private lateinit var scroll: ScrollView

    private var site: Site? = null
    private var categories = listOf<Category>()
    private var currentCat: Category? = null
    private var currentPage = 1
    private var pageCount = 1
    private var loading = false

    /** 当前分类可用的筛选组（站点 ext 里的 filters） */
    private var filterGroups = listOf<FilterGroup>()
    /** 已选筛选条件：key -> value（value 为空表示「全部」） */
    private val filterState = mutableMapOf<String, String>()

    /** 拼成 &area=大陆&year=2025 这样的尾部查询串 */
    private fun buildExtra(): String {
        if (filterState.isEmpty()) return ""
        val sb = StringBuilder()
        filterState.forEach { (k, v) ->
            if (v.isNotEmpty()) {
                sb.append('&').append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
            }
        }
        return sb.toString()
    }

    companion object {
        private const val EXTRA_SITE_KEY = "site_key"

        fun start(context: Context, siteKey: String? = null) {
            val intent = Intent(context, VodBrowseActivity::class.java)
            if (!siteKey.isNullOrEmpty()) intent.putExtra(EXTRA_SITE_KEY, siteKey)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        buildUI()

        val key = intent.getStringExtra(EXTRA_SITE_KEY)
        val sites = configStore.getEnabledSiteList()
        site = sites.find { it.key == key } ?: sites.firstOrNull()

        if (site == null) {
            showEmpty("还没有可用站源，请先在「设置」里导入订阅或配置")
            return
        }
        loadCategories()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141218"))
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }

        // 顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        titleView = TextView(this).apply {
            text = "点播"
            textSize = 20f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(actionChip("换站") { showSitePicker() })
        topBar.addView(actionChip("刷新") { refresh() })
        root.addView(topBar)

        // 分类行
        catRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(6))
        }
        val catScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        catScroll.addView(catRow)
        root.addView(catScroll)

        // 筛选行（站点 ext 里配了 filters 才有内容）
        filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(6))
        }
        val filterScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filterScroll.addView(filterRow)
        root.addView(filterScroll)

        // 状态栏
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(statusView)

        // 海报网格
        scroll = ScrollView(this)
        gridLayout = GridLayout(this).apply {
            columnCount = 6
            setPadding(0, dp(6), 0, dp(6))
        }
        scroll.addView(gridLayout)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // 翻页
        val pageBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        prevBtn = Button(this).apply {
            text = "上一页"
            setOnClickListener { gotoPage(currentPage - 1) }
        }
        pageBar.addView(prevBtn)
        pageInfo = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(dp(16), 0, dp(16), 0)
        }
        pageBar.addView(pageInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        nextBtn = Button(this).apply {
            text = "下一页"
            setOnClickListener { gotoPage(currentPage + 1) }
        }
        pageBar.addView(nextBtn)
        root.addView(pageBar)

        setContentView(root)
    }

    private fun actionChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(36)).also { it.marginStart = dp(10) }
            onTvFocus(scale = 1f)
        }
    }

    private fun showEmpty(msg: String) {
        statusView.text = msg
        gridLayout.removeAllViews()
        pageInfo.text = ""
    }

    private fun refresh() {
        CmsClient.clearCache()
        loadCategories()
    }

    private fun showSitePicker() {
        val sites = configStore.getEnabledSiteList()
        if (sites.isEmpty()) {
            Toast.makeText(this, "没有可用站源", Toast.LENGTH_SHORT).show()
            return
        }
        val names = sites.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择站源")
            .setItems(names) { _, which ->
                site = sites[which]
                currentCat = null
                currentPage = 1
                CmsClient.clearCache()
                loadCategories()
            }
            .show()
    }

    private fun loadCategories() {
        val s = site ?: return
        titleView.text = s.name
        statusView.text = "正在加载分类…"
        gridLayout.removeAllViews()
        catRow.removeAllViews()

        lifecycleScope.launch {
            try {
                val cms = CmsClient(s)
                categories = cms.categories()
                statusView.text = ""
                buildCategoryChips()
                val first = categories.firstOrNull()
                if (first != null) {
                    currentCat = first
                    filterState.clear()
                    applyFilters(cms, first)
                    loadPage(1)
                } else {
                    showEmpty("该站源没有返回分类")
                }
            } catch (e: Exception) {
                showEmpty("分类加载失败：${e.message}")
            }
        }
    }

    private fun buildCategoryChips() {
        catRow.removeAllViews()
        categories.forEach { cat ->
            val chip = TextView(this).apply {
                text = cat.name
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    if (currentCat?.id != cat.id) {
                        currentCat = cat
                        filterState.clear()
                        buildCategoryChips()
                        applyFilters(CmsClient(site ?: return@setOnClickListener), cat)
                        loadPage(1)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)
                ).also { it.marginEnd = dp(8) }
            }
            styleChip(chip, currentCat?.id == cat.id)
            // 遥控器焦点高亮（胶囊按钮不放大，避免与相邻胶囊重叠）
            chip.onTvFocus(
                normalColor = if (currentCat?.id == cat.id) TvUi.CARD_HI else TvUi.CARD,
                focusColor = 0x66FFFFFF,
                scale = 1f
            )
            catRow.addView(chip)
        }
    }

    private fun styleChip(chip: TextView, selected: Boolean) {
        if (selected) {
            chip.setTextColor(Color.parseColor("#FFEB3B"))
            chip.setBackgroundColor(Color.parseColor("#66000000"))
        } else {
            chip.setTextColor(Color.WHITE)
            chip.setBackgroundColor(Color.parseColor("#33000000"))
        }
    }

    /** 取当前分类的筛选组并渲染（没有 filters 就隐藏整行） */
    private fun applyFilters(cms: CmsClient, cat: Category) {
        filterGroups = cms.filtersOf(cat.id)
        renderFilterRow()
    }

    private fun renderFilterRow() {
        filterRow.removeAllViews()
        if (filterGroups.isEmpty()) {
            filterRow.visibility = View.GONE
            return
        }
        filterRow.visibility = View.VISIBLE

        filterGroups.forEach { g ->
            val label = TextView(this).apply {
                text = "${g.name}："
                textSize = 14f
                setTextColor(TvUi.DIM)
                setPadding(dp(6), 0, dp(4), 0)
                gravity = Gravity.CENTER_VERTICAL
            }
            filterRow.addView(label)

            // 「全部」= 清掉该 key 的条件。
            // 站点数据里通常本身就带一个 {"n":"全部","v":""}，有的话不再重复插入
            val hasAllInData = g.values.any { it.v.isEmpty() }
            if (!hasAllInData) {
                filterRow.addView(filterChip("全部", filterState[g.key].isNullOrEmpty()) {
                    filterState.remove(g.key)
                    renderFilterRow()
                    loadPage(1)
                })
            }

            g.values.forEach { v ->
                val selected = filterState[g.key] == v.v && v.v.isNotEmpty()
                filterRow.addView(filterChip(v.n, selected) {
                    filterState[g.key] = v.v
                    renderFilterRow()
                    loadPage(1)
                })
            }
        }
    }

    private fun filterChip(label: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(if (selected) TvUi.SELECTED else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)
            ).also { it.marginEnd = dp(6) }
            setOnClickListener { onClick() }
            onTvFocus(
                normalColor = if (selected) TvUi.CARD_HI else TvUi.CARD,
                focusColor = 0x66FFFFFF,
                scale = 1f
            )
        }
    }

    private fun gotoPage(p: Int) {
        if (p < 1 || p > pageCount || loading || p == currentPage) return
        loadPage(p)
    }

    private fun loadPage(page: Int) {
        val s = site ?: return
        val cat = currentCat ?: return
        if (loading) return
        loading = true
        statusView.text = "加载中…"

        lifecycleScope.launch {
            try {
                val cms = CmsClient(s)
                cms.setExtra(buildExtra())

                // 父分类通常没有直属影片：若它下面挂着子分类，就把父 + 子一起拉取合并，
                // 否则点「电视剧」会看到空列表（鸿蒙版同样处理）
                val children = categories.filter { it.pid.isNotEmpty() && it.pid == cat.id }
                val result: VodPage
                if (children.isNotEmpty()) {
                    result = cms.listMerged(listOf(cat.id) + children.map { it.id }, 1)
                    // 聚合只取第 1 页，关掉翻页避免语义混乱
                    currentPage = 1
                    pageCount = 1
                } else {
                    result = cms.list(cat.id, page)
                    currentPage = result.page
                    pageCount = if (result.pageCount > 0) result.pageCount else 1
                }

                renderGrid(result.items)

                statusView.text = when {
                    result.items.isNotEmpty() && children.isNotEmpty() ->
                        "「${cat.name}」聚合自 ${children.size + 1} 个分类"
                    result.items.isEmpty() && children.isNotEmpty() ->
                        "该分类及其 ${children.size} 个子分类都没有内容"
                    result.items.isEmpty() -> "该分类下没有内容"
                    else -> ""
                }

                pageInfo.text = if (children.isNotEmpty()) "聚合视图" else "第 $currentPage / $pageCount 页"
                prevBtn.isEnabled = children.isEmpty() && currentPage > 1
                nextBtn.isEnabled = children.isEmpty() && currentPage < pageCount
                scroll.scrollTo(0, 0)
            } catch (e: Exception) {
                statusView.text = "列表加载失败：${e.message}"
            } finally {
                loading = false
            }
        }
    }

    private fun renderGrid(items: List<Vod>) {
        gridLayout.removeAllViews()
        gridLayout.columnCount = 6

        val cellW = (resources.displayMetrics.widthPixels - dp(48)) / 6
        items.forEachIndexed { pos, vod ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }

            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#33000000"))
            }
            card.addView(img, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(196)
            ))
            if (vod.pic.isNotEmpty()) {
                Glide.with(this).load(vod.pic).centerCrop().into(img)
            }

            val name = TextView(this).apply {
                text = vod.name
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(name)

            if (vod.remark.isNotEmpty()) {
                val remark = TextView(this).apply {
                    text = vod.remark
                    textSize = 11f
                    setTextColor(Color.parseColor("#80FFFFFF"))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                card.addView(remark)
            }

            val rowSpec = GridLayout.spec(pos / 6, 1)
            val colSpec = GridLayout.spec(pos % 6, 1)
            card.layoutParams = GridLayout.LayoutParams(rowSpec, colSpec).also {
                it.width = cellW - dp(8)
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                it.setMargins(dp(4), dp(4), dp(4), dp(8))
            }

            card.setOnClickListener { DetailActivity.start(this, vod) }
            card.onTvFocus(
                normalColor = Color.TRANSPARENT,
                focusColor = TvUi.CARD_HI,
                scale = 1.04f
            )
            gridLayout.addView(card)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
