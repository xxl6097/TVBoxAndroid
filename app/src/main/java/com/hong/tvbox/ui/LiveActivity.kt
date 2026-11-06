package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hong.tvbox.core.Channel
import com.hong.tvbox.core.Episode
import com.hong.tvbox.core.LiveClient
import com.hong.tvbox.core.LiveDir
import com.hong.tvbox.data.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 直播页 - 对照鸿蒙 LivePage.ets
 *
 * 左侧分组树（按源里写的原名字，不改写不归类），右侧频道网格。
 * 「检测」对当前分组逐个探活（上限 40），失效台标灰加删除线。
 */
class LiveActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore

    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var groupList: LinearLayout
    private lateinit var gridLayout: GridLayout
    private lateinit var scroll: ScrollView

    private var root: LiveDir? = null
    private var current: LiveDir? = null
    private val stack = ArrayDeque<LiveDir>()
    private val dead = mutableSetOf<String>()
    private var probing = false

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LiveActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        buildUI()
    }

    /** 每次回到本页都重新读一次源地址：用户可能刚在设置里换了直播源 */
    override fun onResume() {
        super.onResume()
        if (::configStore.isInitialized) loadSource()
    }

    private fun buildUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
            setPadding(dp(24), dp(20), dp(24), dp(16))
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
        back.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        topBar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        titleView = TextView(this).apply {
            text = "直播"
            textSize = 20f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(actionChip("检测") { probeCurrentGroup() })
        topBar.addView(actionChip("换源") { showSourcePicker() })
        topBar.addView(actionChip("刷新") { loadSource() })
        rootLayout.addView(topBar)

        pathView = TextView(this).apply {
            textSize = 14f
            setTextColor(TvUi.DIM)
            setPadding(0, dp(8), 0, dp(4))
        }
        rootLayout.addView(pathView)

        // 主体：左分组 + 右频道
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val leftScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#1C1B1F"))
        }
        groupList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        leftScroll.addView(groupList)
        body.addView(leftScroll, LinearLayout.LayoutParams(dp(240), ViewGroup.LayoutParams.MATCH_PARENT))

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, 0, 0)
        }
        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(TvUi.SUB)
            setPadding(0, 0, 0, dp(6))
        }
        rightCol.addView(statusView)

        scroll = ScrollView(this)
        gridLayout = GridLayout(this).apply { columnCount = 5 }
        scroll.addView(gridLayout)
        rightCol.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        body.addView(rightCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        rootLayout.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(rootLayout)
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

    // ============ 数据 ============

    private fun loadSource() {
        val url = configStore.getLiveSourceUrl()
        if (url.isNullOrEmpty()) {
            titleView.text = "直播"
            statusView.text = "还没有直播源。到「设置 → 直播源」填一个 m3u/txt 地址，" +
                    "或导入带 lives 的订阅"
            groupList.removeAllViews()
            gridLayout.removeAllViews()
            return
        }

        val name = configStore.getSubLiveName(url)
        titleView.text = if (name.isNotEmpty()) "直播 · $name" else "直播"
        statusView.text = "正在加载直播源…"
        groupList.removeAllViews()
        gridLayout.removeAllViews()
        dead.clear()

        lifecycleScope.launch {
            try {
                val groups = LiveClient.load(url)
                val tree = LiveClient.buildTree(groups)
                root = tree
                current = tree
                stack.clear()

                // 恢复上次停留的分组：常看 CCTV 的人不必每次都从头点
                restorePath(tree)

                renderGroups()
                renderChannels()
            } catch (e: Exception) {
                statusView.text = "直播源加载失败：${e.message}"
            }
        }
    }

    /** 按保存的路径逐级下钻；中途对不上就停在能到达的那一级 */
    private fun restorePath(tree: LiveDir) {
        val saved = configStore.getLastLivePath()
        if (saved.isEmpty()) return

        var node = tree
        saved.forEach { name ->
            val next = node.children.firstOrNull { it.name == name } ?: return
            stack.addLast(node)
            node = next
        }
        current = node
    }

    private fun rememberPath() {
        val parts = mutableListOf<String>()
        stack.forEach { if (it.name != "全部") parts.add(it.name) }
        current?.let { if (it.name != "全部") parts.add(it.name) }
        configStore.saveLastLivePath(parts)
    }

    private fun renderGroups() {
        groupList.removeAllViews()
        val node = current ?: return

        // 返回上级
        if (stack.isNotEmpty()) {
            groupList.addView(groupItem("‹ 上级", "", false) {
                current = stack.removeLast()
                renderGroups()
                renderChannels()
                updatePath()
            })
        }

        // 当前节点的全部频道
        groupList.addView(groupItem("全部频道", "${node.count}", node.children.isEmpty()) {
            renderChannels()
            updatePath()
        })

        node.children.forEach { child ->
            groupList.addView(groupItem(child.name, "${child.count}", false) {
                stack.addLast(node)
                current = child
                renderGroups()
                renderChannels()
                updatePath()
            })
        }

        updatePath()
    }

    private fun groupItem(title: String, badge: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val name = TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(if (selected) TvUi.SELECTED else Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (badge.isNotEmpty()) {
            val b = TextView(this).apply {
                text = badge
                textSize = 12f
                setTextColor(TvUi.DIM)
                setPadding(dp(6), 0, 0, 0)
            }
            row.addView(b)
        }

        row.setOnClickListener { onClick() }
        row.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(2) }
        return row
    }

    private fun updatePath() {
        val node = current ?: return
        val parts = mutableListOf<String>()
        stack.forEach { parts.add(it.name) }
        parts.add(node.name)
        val path = parts.filter { it.isNotEmpty() && it != "全部" }.joinToString(" › ")
        pathView.text = if (path.isEmpty()) "全部频道 · ${node.count} 台" else "$path · ${node.count} 台"

        // 每次导航顺手记一下，下次进来直接回到这里
        rememberPath()
    }

    private fun renderChannels() {
        val node = current ?: return
        val channels = mutableListOf<Channel>()
        LiveClient.collect(node, channels)
        gridLayout.removeAllViews()

        if (channels.isEmpty()) {
            statusView.text = "该分组下没有频道"
            return
        }
        statusView.text = "共 ${channels.size} 台" + if (dead.isEmpty()) "" else "，已标记 ${dead.size} 台失效"

        channels.forEach { ch ->
            gridLayout.addView(channelCard(ch))
        }
        scroll.scrollTo(0, 0)
    }

    private fun channelCard(ch: Channel): View {
        val isDead = dead.contains(ch.name)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            gravity = Gravity.CENTER
        }

        val name = TextView(this).apply {
            text = ch.name
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(if (isDead) TvUi.DIM else Color.WHITE)
            if (isDead) {
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
        }
        card.addView(name)

        if (ch.urls.size > 1 && !isDead) {
            val sub = TextView(this).apply {
                text = "${ch.urls.size} 条线路"
                textSize = 11f
                setTextColor(TvUi.DIM)
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(sub)
        }

        val spec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
        card.layoutParams = GridLayout.LayoutParams(spec, spec).also {
            it.width = 0
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.setMargins(dp(4), dp(4), dp(4), dp(4))
        }

        card.setOnClickListener { play(ch) }
        card.onTvFocus(
            normalColor = Color.TRANSPARENT,
            focusColor = TvUi.CARD_HI,
            scale = 1.04f
        )
        return card
    }

    private fun play(ch: Channel) {
        val urls = if (ch.urls.isEmpty()) mutableListOf(ch.url) else ch.urls
        val eps = urls.mapIndexed { i, u -> Episode("线路${i + 1}", u) }

        PlayerActivity.start(
            context = this,
            vodId = "live_${ch.name}",
            siteKey = "live",
            vodName = ch.name,
            lineName = "直播",
            allEpisodes = eps,
            episodeIndex = 0,
            isDirect = true,
            siteApi = "",
            isLive = true
        )
    }

    // ============ 探活 ============

    private fun probeCurrentGroup() {
        if (probing) {
            Toast.makeText(this, "正在检测中…", Toast.LENGTH_SHORT).show()
            return
        }
        val node = current ?: return
        val channels = mutableListOf<Channel>()
        LiveClient.collect(node, channels)
        if (channels.isEmpty()) {
            Toast.makeText(this, "该分组下没有频道", Toast.LENGTH_SHORT).show()
            return
        }

        val targets = channels.take(40)
        probing = true
        dead.clear()
        Toast.makeText(this, "开始检测 ${targets.size} 台，约 ${targets.size / 2} 秒", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            var done = 0
            targets.chunked(6).forEach { batch ->
                val results = coroutineScope {
                    batch.map { ch ->
                        async(Dispatchers.IO) { ch to LiveClient.probe(ch.url) }
                    }.awaitAll()
                }
                results.forEach { pair ->
                    val ch = pair.first
                    val ok = pair.second
                    if (!ok) dead.add(ch.name)
                    done++
                }
                statusView.text = "检测中… $done / ${targets.size}"
            }

            probing = false
            renderChannels()
            Toast.makeText(
                this@LiveActivity,
                if (dead.isEmpty()) "全部可连" else "检测完成，${dead.size} 台可能失效",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ============ 换源 ============

    private fun showSourcePicker() {
        val lives = configStore.getSubLives()
        val currentUrl = configStore.getLiveSourceUrl()

        if (lives.isEmpty()) {
            Toast.makeText(this, "订阅里没有直播源，请到设置页手动填写", Toast.LENGTH_SHORT).show()
            return
        }

        val names = lives.map { if (it.url == currentUrl) "✓ ${it.name}" else it.name }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("选择直播源")
            .setItems(names) { _, which ->
                val picked = lives[which]
                configStore.saveLiveSourceUrl(picked.url)
                Toast.makeText(this, "已切换：${picked.name}", Toast.LENGTH_SHORT).show()
                loadSource()
            }
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
