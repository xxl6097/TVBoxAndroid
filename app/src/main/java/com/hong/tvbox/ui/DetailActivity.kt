package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.hong.tvbox.core.*
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.FavoriteStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 详情页 - 完整移植自 Detail.ets
 * 实现：海报 + 元信息 + 简介 + 线路切换 + 剧集网格 + 收藏 + 播放
 */
class DetailActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var favoriteStore: FavoriteStore
    private lateinit var historyStore: HistoryStore

    private var vod = Vod()
    private var lines = listOf<Line>()
    private var currentLineIndex = 0
    private var seenEpisodeIndex = -1
    private var isFavorite = false

    // UI 组件
    private lateinit var scrollView: ScrollView
    private lateinit var loadingView: ProgressBar
    private lateinit var errorView: TextView
    private lateinit var backBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var favBtn: TextView
    private lateinit var posterView: ImageView
    private lateinit var siteNameView: TextView
    private lateinit var metaView: TextView
    private lateinit var contentView: TextView
    private lateinit var toggleIntroBtn: TextView
    private lateinit var linesContainer: LinearLayout
    private lateinit var episodesLabel: TextView
    private lateinit var episodesHeader: LinearLayout
    private lateinit var orderBtn: TextView
    private lateinit var episodesGrid: GridLayout
    private lateinit var bodyRowView: LinearLayout

    private var fullIntro = false
    /** 剧集是否倒序显示（从新到旧） */
    private var epDesc = false

    companion object {
        const val EXTRA_VOD_ID = "vod_id"
        const val EXTRA_SITE_KEY = "site_key"
        const val EXTRA_SITE_NAME = "site_name"

        fun start(context: Context, vod: Vod) {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra(EXTRA_VOD_ID, vod.id)
                putExtra(EXTRA_SITE_KEY, vod.siteKey)
                putExtra(EXTRA_SITE_NAME, vod.siteName)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configStore = ConfigStore(this)
        favoriteStore = FavoriteStore(this)
        historyStore = HistoryStore(this)

        val vodId = intent.getStringExtra(EXTRA_VOD_ID) ?: return
        val siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: return
        val siteName = intent.getStringExtra(EXTRA_SITE_NAME) ?: ""

        buildUI()
        loadDetail(vodId, siteKey, siteName)
    }

    private fun buildUI() {
        val root = ScrollView(this)
        root.setBackgroundColor(Color.parseColor("#141218"))
        scrollView = root

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // 顶部栏：返回 + 标题 + 收藏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        backBtn = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        backBtn.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))

        titleView = TextView(this).apply {
            text = "详情"
            textSize = 22f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        favBtn = TextView(this).apply {
            text = "☆ 收藏"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.parseColor("#33000000"))
            setOnClickListener { toggleFavorite() }
        }
        favBtn.onTvFocus(scale = 1f)
        topBar.addView(favBtn)
        container.addView(topBar)

        // 加载中
        loadingView = ProgressBar(this).apply {
            visibility = View.VISIBLE
        }
        container.addView(loadingView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.gravity = android.view.Gravity.CENTER; it.topMargin = dp(40) })

        // 错误提示
        errorView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.parseColor("#FFB4AB"))
            visibility = View.GONE
        }
        container.addView(errorView)

        // 主体内容区
        val bodyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        bodyRowView = bodyRow

        // 左：海报
        val posterCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        posterView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#33000000"))
        }
        posterCol.addView(posterView, LinearLayout.LayoutParams(dp(200), dp(282)))
        siteNameView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(8), 0, 0)
        }
        posterCol.addView(siteNameView)
        bodyRow.addView(posterCol, LinearLayout.LayoutParams(dp(232), LinearLayout.LayoutParams.WRAP_CONTENT))

        // 右：元信息 + 简介 + 线路
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, 0, 0)
        }
        metaView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#B3FFFFFF"))
        }
        infoCol.addView(metaView)

        contentView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(10), 0, 0)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoCol.addView(contentView)

        toggleIntroBtn = TextView(this).apply {
            text = "展开全部"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
            setOnClickListener {
                fullIntro = !fullIntro
                contentView.maxLines = if (fullIntro) Int.MAX_VALUE else 3
                text = if (fullIntro) "收起" else "展开全部"
                Toast.makeText(this@DetailActivity,
                    if (fullIntro) "简介已展开" else "简介已收起", Toast.LENGTH_SHORT).show()
            }
        }
        toggleIntroBtn.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        infoCol.addView(toggleIntroBtn)

        // 线路选择
        linesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
            gravity = android.view.Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        val linesLabel = TextView(this).apply {
            text = "线路"
            textSize = 16f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, 0, dp(12), 0)
        }
        linesContainer.addView(linesLabel)
        infoCol.addView(linesContainer)

        bodyRow.addView(infoCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(bodyRow)

        // 剧集区
        episodesLabel = TextView(this).apply {
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(20), 0, dp(10))
            visibility = View.GONE
        }
        // 剧集标题行：标题 + 正序/倒序切换（追更的人通常只想点最新一集）
        val epHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(10))
            visibility = View.GONE
        }
        episodesHeader = epHeader
        epHeader.addView(episodesLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        orderBtn = TextView(this).apply {
            text = "正序"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            onTvFocus(scale = 1f)
            setOnClickListener {
                epDesc = !epDesc
                text = if (epDesc) "倒序" else "正序"
                rebuildEpisodesUI()
            }
        }
        epHeader.addView(orderBtn)
        container.addView(epHeader)

        episodesGrid = GridLayout(this).apply {
            visibility = View.GONE
            columnCount = 8
        }
        container.addView(episodesGrid)

        root.addView(container)
        setContentView(root)
    }

    private fun loadDetail(vodId: String, siteKey: String, siteName: String) {
        val config = configStore.getConfig()
        val site = config?.sites?.find { it.key == siteKey }
            ?: run {
                showError("站源不存在")
                return
            }

        lifecycleScope.launch {
            try {
                val cms = CmsClient(site)
                val fullVod = cms.detail(vodId)
                if (fullVod.siteName.isEmpty()) {
                    fullVod.copy(siteName = siteName).also { vod = it }
                } else {
                    vod = fullVod
                }

                // 拆分线路
                lines = CmsClient.splitLines(fullVod.from, fullVod.playUrl)

                // 默认选直连线路
                currentLineIndex = lines.indexOfFirst { it.direct }.takeIf { it >= 0 } ?: 0

                // 读取观看历史
                val history = historyStore.get(vodId, siteKey)
                seenEpisodeIndex = history?.episodeIndex ?: -1

                // 读取收藏状态
                isFavorite = favoriteStore.isFavorite(vodId, siteKey)

                showContent()
            } catch (e: Exception) {
                showError(e.message ?: "加载失败")
            }
        }
    }

    private fun showContent() {
        loadingView.visibility = View.GONE

        titleView.text = vod.name
        favBtn.text = if (isFavorite) "★ 已收藏" else "☆ 收藏"
        favBtn.setTextColor(if (isFavorite) Color.parseColor("#FFEB3B") else Color.WHITE)

        // 海报
        if (vod.pic.isNotEmpty()) {
            Glide.with(this).load(vod.pic).centerCrop().into(posterView)
        }
        siteNameView.text = vod.siteName

        // 元信息行
        val parts = mutableListOf<String>()
        if (vod.typeName.isNotEmpty()) parts.add(vod.typeName)
        if (vod.year.isNotEmpty()) parts.add(vod.year)
        if (vod.area.isNotEmpty()) parts.add(vod.area)
        if (vod.remark.isNotEmpty()) parts.add(vod.remark)
        if (vod.actor.isNotEmpty()) parts.add("主演：${vod.actor}")
        metaView.text = if (parts.isNotEmpty()) parts.joinToString("  ·  ") else "暂无资料"

        // 简介
        val content = vod.content.ifEmpty { "暂无简介" }
        contentView.text = content
        if (content.length > 60) {
            toggleIntroBtn.visibility = View.VISIBLE
        }

        // 线路
        if (lines.size > 1) {
            linesContainer.visibility = View.VISIBLE
            rebuildLinesUI()
        }

        // 剧集
        if (lines.isNotEmpty()) {
            val label = "剧集  共 ${lines[currentLineIndex].eps.size} 集" +
                    (if (seenEpisodeIndex >= 0) "  上次看到第 ${seenEpisodeIndex + 1} 集" else "")
            episodesLabel.text = label
            episodesLabel.visibility = View.VISIBLE
            episodesHeader.visibility = View.VISIBLE
            rebuildEpisodesUI()

            // 上次看到第 N 集时，进来直接把剧集区滚进视野，不用自己找
            if (seenEpisodeIndex >= 0) {
                scrollView.post { scrollView.smoothScrollTo(0, episodesHeader.top) }
            }
        }

        // 显示主体
        bodyRowView.visibility = View.VISIBLE
    }

    private fun rebuildLinesUI() {
        // 移除旧的线路按钮（保留 label）
        while (linesContainer.childCount > 1) {
            linesContainer.removeViewAt(1)
        }

        lines.forEachIndexed { i, line ->
            val btn = TextView(this).apply {
                text = line.name + (if (!line.direct) " ·需解析" else "")
                textSize = 16f
                setTextColor(
                    when {
                        i == currentLineIndex -> Color.parseColor("#FFEB3B")
                        line.direct -> Color.WHITE
                        else -> Color.parseColor("#FFB4AB")
                    }
                )
                setBackgroundColor(
                    if (i == currentLineIndex) Color.parseColor("#66000000")
                    else Color.parseColor("#33000000")
                )
                setPadding(dp(12), 0, dp(12), 0)
                height = dp(40)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                val p = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(40)
                ).also { it.marginEnd = dp(10) }
                layoutParams = p
                setOnClickListener {
                    currentLineIndex = i
                    rebuildLinesUI()
                    rebuildEpisodesUI()
                    Toast.makeText(this@DetailActivity, "已切到线路：${line.name}", Toast.LENGTH_SHORT).show()
                }
                onTvFocus(
                    normalColor = if (i == currentLineIndex) TvUi.CARD_HI else TvUi.CARD,
                    focusColor = 0x66FFFFFF,
                    scale = 1f
                )
            }
            linesContainer.addView(btn)
        }
    }

    private fun rebuildEpisodesUI() {
        episodesGrid.removeAllViews()
        if (lines.isEmpty() || currentLineIndex >= lines.size) return

        val eps = lines[currentLineIndex].eps
        episodesGrid.columnCount = 8
        episodesGrid.visibility = View.VISIBLE

        // 倒序时按真实下标倒着渲染，点击仍用真实下标，保证续播/历史记录不错位
        val order = if (epDesc) eps.indices.reversed().toList() else eps.indices.toList()

        order.forEachIndexed { pos, i ->
            val ep = eps[i]
            val isSeen = i == seenEpisodeIndex
            val btn = TextView(this).apply {
                text = ep.name
                textSize = 15f
                setTextColor(if (isSeen) Color.parseColor("#FFEB3B") else Color.WHITE)
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END

                // 显式指定列号：只写 UNDEFINED 时，剧集数少于列数会被平均拉伸成整行
                val spec = GridLayout.spec(pos % 8, 1, 1f)
                layoutParams = GridLayout.LayoutParams(spec, spec).also {
                    it.width = 0
                    it.height = dp(46)
                    it.setMargins(dp(5), dp(5), dp(5), dp(5))
                }

                // 剧集格子不放大（挨太近会互相压住），只换底色
                onTvFocus(
                    normalColor = if (isSeen) TvUi.CARD_HI else TvUi.CARD,
                    focusColor = 0x66FFFFFF,
                    scale = 1f
                )

                setOnClickListener {
                    play(ep, i)
                }
            }
            episodesGrid.addView(btn)
        }
    }

    private fun toggleFavorite() {
        val vodId = intent.getStringExtra(EXTRA_VOD_ID) ?: return
        val siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: return

        if (isFavorite) {
            favoriteStore.remove(vodId, siteKey)
            isFavorite = false
            Toast.makeText(this, "已取消收藏", Toast.LENGTH_SHORT).show()
        } else {
            favoriteStore.add(Favorite(
                vodId = vodId,
                siteKey = siteKey,
                vodName = vod.name,
                vodPic = vod.pic
            ))
            isFavorite = true
            Toast.makeText(this, "已加入收藏", Toast.LENGTH_SHORT).show()
        }

        favBtn.text = if (isFavorite) "★ 已收藏" else "☆ 收藏"
        favBtn.setTextColor(if (isFavorite) Color.parseColor("#FFEB3B") else Color.WHITE)
    }

    private fun play(ep: Episode, index: Int) {
        val vodId = intent.getStringExtra(EXTRA_VOD_ID) ?: return
        val siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: return

        if (lines.isEmpty()) return
        val line = lines[currentLineIndex]

        // 记录历史
        historyStore.addOrUpdate(History(
            vodId = vodId,
            siteKey = siteKey,
            vodName = vod.name,
            vodPic = vod.pic,
            lineIndex = currentLineIndex,
            episodeIndex = index,
            episodeName = ep.name,
            position = 0,
            duration = 0
        ))

        // 启动播放器
        PlayerActivity.start(
            context = this,
            vodId = vodId,
            siteKey = siteKey,
            vodName = vod.name,
            lineName = line.name,
            allEpisodes = line.eps,
            episodeIndex = index,
            isDirect = line.direct,
            siteApi = configStore.getConfig()?.sites?.find { it.key == siteKey }?.api ?: ""
        )
    }

    private fun showError(msg: String) {
        loadingView.visibility = View.GONE
        errorView.text = msg
        errorView.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
