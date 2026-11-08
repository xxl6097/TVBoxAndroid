package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hong.tvbox.core.CmsClient
import com.hong.tvbox.core.Pinyin
import com.hong.tvbox.core.Site
import com.hong.tvbox.core.Vod
import com.hong.tvbox.data.ConfigStore
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 搜索页 - 完整移植自 Search.ets
 * 实现：多站源并发搜索（每批6个，最多18个）、搜索历史、按站源分组结果
 */
class SearchActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var searchEditText: EditText
    private lateinit var searchButton: Button
    private lateinit var clearTextBtn: TextView
    private lateinit var statusView: TextView
    private lateinit var resultsContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var historyContainer: LinearLayout
    private lateinit var historyLabel: TextView
    private lateinit var suggestLabel: TextView
    private lateinit var suggestContainer: LinearLayout

    private var searchGen = 0
    private var isSearching = false

    companion object {
        fun start(context: Context, siteKey: String? = null) {
            val intent = Intent(context, SearchActivity::class.java)
            if (!siteKey.isNullOrEmpty()) intent.putExtra(EXTRA_SITE_KEY, siteKey)
            context.startActivity(intent)
        }
        private const val EXTRA_SITE_KEY = "site_key"
    }

    private var prefillSiteKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        prefillSiteKey = intent.getStringExtra(EXTRA_SITE_KEY)
        buildUI()
        showHistory()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // 顶栏：返回 + 搜索框 + 搜索按钮
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        searchEditText = EditText(this).apply {
            hint = "输入片名，回车搜索"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(dp(22), dp(12), dp(52), dp(12))
            setOnEditorActionListener { _, _, _ -> doSearch(); true }
        }
        searchEditText.tvInputStyle()
        topBar.addView(searchEditText, LinearLayout.LayoutParams(0, dp(52), 1f))

        clearTextBtn = TextView(this).apply {
            text = "×"
            textSize = 24f
            setTextColor(Color.parseColor("#80FFFFFF"))
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener {
                searchEditText.setText("")
                searchEditText.requestFocus()
            }
        }
        topBar.addView(clearTextBtn, LinearLayout.LayoutParams(dp(44), dp(44)))

        searchButton = Button(this).apply {
            text = "搜索"
            setOnClickListener { doSearch() }
        }
        topBar.addView(searchButton, LinearLayout.LayoutParams(dp(80), dp(52)).also { it.marginStart = dp(14) })
        root.addView(topBar)

        // 状态栏
        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(dp(56), dp(8), dp(56), dp(8))
        }
        root.addView(statusView)

        // 搜索历史区
        historyLabel = TextView(this).apply {
            text = "最近搜过"
            textSize = 18f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            visibility = View.GONE
        }
        root.addView(historyLabel)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(historyContainer)

        // 猜你想搜：拼音首字母 / T9 数字联想
        suggestLabel = TextView(this).apply {
            text = "猜你想搜"
            textSize = 18f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            visibility = View.GONE
        }
        root.addView(suggestLabel)

        suggestContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(suggestContainer)

        // 结果区
        scrollView = ScrollView(this)
        resultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(resultsContainer)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                clearTextBtn.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                refreshSuggestions(s?.toString().orEmpty())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // ========== 猜你想搜（拼音首字母 / T9 数字联想） ==========

    /** 候选池：搜索历史 + 首页 feed 片名，去重 */
    private fun buildPool(): List<String> {
        val seen = LinkedHashSet<String>()
        configStore.getKeywords().forEach { seen.add(it) }
        configStore.getFeed().forEach { seen.add(it.name) }
        return seen.toList()
    }

    private fun refreshSuggestions(input: String) {
        // 搜索中/有结果时不打扰
        if (isSearching) return
        val pool = buildPool()
        val kw = input.trim()

        if (kw.isEmpty()) {
            suggestLabel.visibility = View.GONE
            suggestContainer.visibility = View.GONE
            return
        }

        val matched = mutableListOf<Pair<String, String>>()
        for (name in pool) {
            if (matched.size >= 12) break
            val hint = hintLabel(name, kw)
            if (hint != null) matched.add(name to hint)
        }
        if (matched.isEmpty()) {
            suggestLabel.visibility = View.GONE
            suggestContainer.visibility = View.GONE
            return
        }

        suggestLabel.visibility = View.VISIBLE
        suggestContainer.visibility = View.VISIBLE
        suggestContainer.removeAllViews()
        matched.forEach { (name, hint) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    searchEditText.setText(name)
                    searchEditText.setSelection(name.length)
                    doSearch()
                }
                onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
            }
            row.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = hint
                textSize = 13f
                setTextColor(Color.parseColor("#FFEB3B"))
                setPadding(dp(10), 0, dp(4), 0)
            })
            suggestContainer.addView(row)
        }
    }

    /**
     * 判断 name 是否命中输入，命中返回提示（缩写或 T9 数字），否则返回 null。
     * 命中优先级：中文包含 → 拼音缩写前缀 → T9 数字前缀。
     */
    private fun hintLabel(name: String, input: String): String? {
        val kw = input.lowercase()
        if (name.contains(input)) return input
        if (name.contains(kw)) return kw

        val abbrs = Pinyin.abbrsOf(name)
        for (a in abbrs) {
            if (a.startsWith(kw)) return a
        }
        // T9：纯数字输入
        if (input.all { it.isDigit() }) {
            for (a in abbrs) {
                val t9 = Pinyin.abbrToT9(a)
                if (t9.startsWith(input)) return t9
            }
        }
        return null
    }

    /** 从搜索结果集合推断内容类型标签 */
    private fun inferTypeTag(items: List<Vod>): String {
        if (items.isEmpty()) return ""
        val types = items.mapNotNull { it.typeName.ifEmpty { null } }
        return types.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: ""
    }

    private fun doSearch() {
        val keyword = searchEditText.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show()
            return
        }

        // 收起键盘
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(searchEditText.windowToken, 0)

        // 记录搜索历史
        saveKeyword(keyword)

        val myGen = ++searchGen
        isSearching = true
        resultsContainer.removeAllViews()
        historyLabel.visibility = View.GONE
        historyContainer.visibility = View.GONE
        statusView.text = "搜索中…"
        searchButton.isEnabled = false

        lifecycleScope.launch {
            val sites = configStore.getEnabledSiteList().filter { it.searchable == 1 }.take(18)
            if (sites.isEmpty()) {
                isSearching = false
                statusView.text = "没有启用任何可搜索的站源"
                searchButton.isEnabled = true
                return@launch
            }

            // 如果从点播页带来预填站源，把它排到第一批首个（优先出结果）
            val ordered = if (prefillSiteKey != null) {
                val prefill = sites.find { it.key == prefillSiteKey }
                if (prefill != null) listOf(prefill) + sites.filter { it.key != prefillSiteKey } else sites
            } else sites

            var searched = 0
            var found = 0

            ordered.chunked(6).forEach { batch ->
                if (myGen != searchGen) return@launch

                // 每个站源一个任务，索引与 batch 对齐（不能用 sites.indexOf，跨批次会错位）
                val tasks = batch.map { site ->
                    async {
                        try {
                            CmsClient(site).search(keyword)
                        } catch (e: Exception) {
                            emptyList<Vod>()
                        }
                    }
                }

                tasks.forEachIndexed { i, task ->
                    val list = task.await()
                    if (list.isNotEmpty()) {
                        found++
                        addResultGroup(batch[i], list)
                    }
                }

                searched += batch.size
                statusView.text = "已搜索 $searched / ${sites.size} 个站源"
            }

            isSearching = false
            searchButton.isEnabled = true
            statusView.text = if (found > 0) {
                "共命中 $found 个站源"
            } else {
                "没有搜到结果，试试更短的片名，或换一个字再搜"
            }
        }
    }

    private fun addResultGroup(site: Site, items: List<Vod>) {
        val groupLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }

        // 站源名 + 结果数
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val siteName = TextView(this).apply {
            text = site.name
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(siteName)
        val countText = TextView(this).apply {
            text = " · ${items.size} 个结果"
            textSize = 13f
            setTextColor(Color.parseColor("#80FFFFFF"))
        }
        headerRow.addView(countText)
        // 类型标签（电影 / 剧集 / 综艺等，从结果推断）
        val typeTag = inferTypeTag(items)
        if (typeTag.isNotEmpty()) {
            headerRow.addView(TextView(this).apply {
                text = typeTag
                textSize = 11f
                setTextColor(Color.parseColor("#FFEB3B"))
                setBackgroundColor(Color.parseColor("#33FFEB3B"))
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginStart = dp(8) })
        }
        groupLayout.addView(headerRow)

        // 横向滑动结果列表
        val scroll = HorizontalScrollView(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }

        items.forEach { vod ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }

            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#33000000"))
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, (resources.displayMetrics.density * 4 + 0.5f).toInt().toFloat())
                    }
                }
            }
            card.addView(img, LinearLayout.LayoutParams(dp(104), dp(146)))

            if (vod.pic.isNotEmpty()) {
                Glide.with(this).load(vod.pic).centerCrop().into(img)
            }

            val nameView = TextView(this).apply {
                text = vod.name
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(nameView, LinearLayout.LayoutParams(dp(104), LinearLayout.LayoutParams.WRAP_CONTENT))

            card.setOnClickListener { DetailActivity.start(this, vod) }
            card.onTvFocus(
                normalColor = Color.TRANSPARENT,
                focusColor = TvUi.CARD_HI,
                scale = 1.05f
            )
            row.addView(card)
        }

        scroll.addView(row)
        groupLayout.addView(scroll)
        resultsContainer.addView(groupLayout)
    }

    // ========== 搜索历史 ==========

    private fun showHistory() {
        val kws = getKeywords()
        if (kws.isEmpty()) {
            // 没有历史时显示热门推荐词
            showHotKeywords()
            return
        }

        historyLabel.visibility = View.VISIBLE
        historyContainer.visibility = View.VISIBLE
        historyContainer.removeAllViews()

        // 标题行：最近搜过 + 清空按钮
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(historyLabel.also {
            it.text = "最近搜过"
            it.textSize = 16f
            it.setTextColor(Color.WHITE)
            it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
            it.setPadding(0, 0, 0, 0)
            it.visibility = View.VISIBLE
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val clearBtn = TextView(this).apply {
            text = "清空"
            textSize = 13f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.parseColor("#22FFFFFF"))
            setOnClickListener {
                clearKeywords()
                showHistory()
                Toast.makeText(this@SearchActivity, "已清空搜索记录", Toast.LENGTH_SHORT).show()
            }
        }
        historyContainer.addView(headerRow)
        historyContainer.addView(clearBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6); it.bottomMargin = dp(10) })

        // 关键词胶囊
        val kwContainer = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        kws.forEach { kw ->
            val chip = TextView(this).apply {
                text = kw
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#44FFFFFF"))
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    searchEditText.setText(kw)
                    doSearch()
                }
                onTvFocus(normalColor = Color.parseColor("#44FFFFFF"), focusColor = Color.parseColor("#88FFFFFF"), scale = 1.05f)
            }
            kwContainer.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
            ).also { it.marginEnd = dp(8) })
        }
        val kwScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        kwScroll.addView(kwContainer)
        historyContainer.addView(kwScroll)
        historyContainer.addView(View(this).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = dp(12) })

        // 底部加热门推荐
        showHotKeywords()
    }

    private val hotKeywords = listOf("电影", "电视剧", "综艺", "动漫", "纪录片", "短剧", "4K", "国产", "日韩")

    private fun showHotKeywords() {
        if (!this::historyContainer.isInitialized) return
        val hotLabel = TextView(this).apply {
            text = "热门搜索"
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(14), 0, dp(8))
        }
        historyContainer.addView(hotLabel)
        historyContainer.visibility = View.VISIBLE

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hotKeywords.forEach { kw ->
            val chip = TextView(this).apply {
                text = kw
                textSize = 13f
                setTextColor(Color.parseColor("#FFEB3B"))
                setBackgroundColor(Color.parseColor("#33FFEB3B"))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    searchEditText.setText(kw)
                    doSearch()
                }
                onTvFocus(normalColor = Color.parseColor("#33FFEB3B"), focusColor = Color.parseColor("#66FFEB3B"), scale = 1.05f)
            }
            row.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)
            ).also { it.marginEnd = dp(8) })
        }
        val s = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        s.addView(row)
        historyContainer.addView(s)
    }

    // 搜索历史统一放 ConfigStore，和配置/历史/收藏同一处，避免多套 prefs 分散

    private fun getKeywords(): List<String> = configStore.getKeywords()

    private fun saveKeyword(kw: String) {
        configStore.pushKeyword(kw)
    }

    private fun clearKeywords() {
        configStore.clearKeywords()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
