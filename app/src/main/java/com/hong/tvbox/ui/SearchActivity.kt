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

    private var searchGen = 0
    private var isSearching = false

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SearchActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        buildUI()
        showHistory()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141218"))
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
            setBackgroundColor(Color.parseColor("#33000000"))
            setPadding(dp(22), dp(12), dp(52), dp(12))
            setOnEditorActionListener { _, _, _ -> doSearch(); true }
        }
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
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
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

            var searched = 0
            var found = 0

            sites.chunked(6).forEach { batch ->
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
            statusView.text = if (found > 0) "共命中 $found 个站源" else "没有搜到结果"
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
        }
        val siteName = TextView(this).apply {
            text = site.name
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        headerRow.addView(siteName)
        val countText = TextView(this).apply {
            text = "${items.size} 个结果"
            textSize = 13f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(dp(12), 0, 0, 0)
        }
        headerRow.addView(countText)
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
        if (kws.isEmpty()) return

        historyLabel.visibility = View.VISIBLE
        historyContainer.visibility = View.VISIBLE
        historyContainer.removeAllViews()

        // 清空按钮
        val clearRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val clearBtn = TextView(this).apply {
            text = "清空记录"
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                clearKeywords()
                showHistory()
                Toast.makeText(this@SearchActivity, "已清空搜索记录", Toast.LENGTH_SHORT).show()
            }
        }
        clearRow.addView(clearBtn)
        historyContainer.addView(clearRow)

        // 关键词列表
        val kwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val kwContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        kws.forEach { kw ->
            val chip = TextView(this).apply {
                text = kw
                textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#33000000"))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    searchEditText.setText(kw)
                    doSearch()
                }
            }
            kwContainer.addView(chip, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).also { it.marginEnd = dp(8) })
        }
        val kwScroll = HorizontalScrollView(this)
        kwScroll.addView(kwContainer)
        historyContainer.addView(kwScroll)
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
