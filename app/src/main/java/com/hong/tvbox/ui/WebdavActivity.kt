package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hong.tvbox.core.History
import com.hong.tvbox.core.Episode
import com.hong.tvbox.core.QRCodeGenerator
import com.hong.tvbox.core.WebdavClient
import com.hong.tvbox.core.WebdavEntry
import com.hong.tvbox.core.WebdavEp
import com.hong.tvbox.core.WebdavPairServer
import com.hong.tvbox.core.WebdavSource
import com.hong.tvbox.core.WebdavVod
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 网盘（WebDAV）- 对照鸿蒙 WebdavPage.ets
 *
 * 极空间/群晖/AList 都自带 WebDAV，填地址账号就能直接播自己的片子，
 * 比影视源稳定得多。这里只做「列目录 + 播放」这一条最小链路。
 */
class WebdavActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var historyStore: HistoryStore

    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var gridContainer: GridLayout

    private var source: WebdavSource? = null
    /** 浏览路径栈（相对挂载根），空栈表示根目录 */
    private val paths = ArrayDeque<String>()

    /** 扫码配对服务（显示二维码时启动，关闭对话框时停止） */
    private var pairServer: WebdavPairServer? = null
    private var pairServerJob: Job? = null
    private var pairPollJob: Job? = null

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WebdavActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        historyStore = HistoryStore(this)
        buildUI()
        // 渲染交给 onResume，避免进页面时重复构建两次
    }

    override fun onResume() {
        super.onResume()
        // 看完片返回时刷新「最近观看」里的进度
        if (this::historyStore.isInitialized && source == null) renderSources()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
            setPadding(dp(24), dp(20), dp(24), dp(16))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { goBack() }
        }
        back.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        topBar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        titleView = TextView(this).apply {
            text = "网盘"
            textSize = 22f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(chip("扫码添加") { startQRPair() })
        topBar.addView(chip("手动添加") { editSource(null) })
        topBar.addView(chip("识别影视") { startScan() })
        topBar.addView(chip("切换源") { renderSources() })
        root.addView(topBar)

        pathView = TextView(this).apply {
            textSize = 14f
            setTextColor(TvUi.DIM)
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(pathView)

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(TvUi.SUB)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        val scroll = ScrollView(this)

        // 两个容器叠在一起：源列表用行，浏览目录用海报网格
        val body = FrameLayout(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gridContainer = GridLayout(this).apply {
            columnCount = 4
            visibility = View.GONE
        }
        body.addView(listContainer)
        body.addView(gridContainer)

        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun showListMode() {
        listContainer.visibility = View.VISIBLE
        gridContainer.visibility = View.GONE
    }

    private fun showGridMode() {
        listContainer.visibility = View.GONE
        gridContainer.visibility = View.VISIBLE
    }

    private fun chip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(36)).also { it.marginStart = dp(10) }
            onTvFocus(scale = 1f)
        }
    }

    private fun goBack() {
        if (source != null && paths.isNotEmpty()) {
            paths.removeLast()
            val path = paths.lastOrNull() ?: ""
            browse(path)
        } else if (source != null) {
            source = null
            paths.clear()
            renderSources()
        } else {
            finish()
        }
    }

    // ============ 影视识别 ============

    private var scanVods: List<WebdavVod> = emptyList()

    private fun startScan() {
        val src = source ?: run {
            Toast.makeText(this, "请先进入一个网盘源", Toast.LENGTH_SHORT).show()
            return
        }
        statusView.text = "正在扫描影视资源…"
        lifecycleScope.launch {
            try {
                val vods = WebdavClient.scan(src, 6) { dirs, files ->
                    statusView.text = "扫描中… 已扫 $dirs 个目录，发现 $files 个视频"
                }
                scanVods = vods
                // 保存缓存，下次进入直接读取（对齐鸿蒙 Store.saveWebdavScan）
                if (vods.isNotEmpty()) {
                    configStore.saveWebdavScanCache(src.id, vods)
                    statusView.text = "识别到 ${vods.size} 部，已保存缓存"
                }
                renderScanResult(vods)
            } catch (e: Exception) {
                statusView.text = "扫描失败：${e.message}"
            }
        }
    }

    private fun renderScanResult(vods: List<WebdavVod>) {
        listContainer.removeAllViews()
        showListMode()
        titleView.text = "网盘 · 识别结果"
        pathView.text = ""
        statusView.text = if (vods.isEmpty()) "没识别出影视，点「切换源」回目录浏览" else "识别到 ${vods.size} 部（已缓存，下次进入自动加载）"

        // 顶部操作行：浏览目录 / 重新扫描 / 清除缓存
        val src = source
        if (src != null) {
            val opRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(2), dp(4), 0, dp(12))
            }
            opRow.addView(smallChip("浏览目录") { browse("") })
            opRow.addView(smallChip("重新扫描") { startScan() })
            opRow.addView(smallChip("清除缓存") {
                configStore.clearWebdavScanCache(src.id)
                scanVods = emptyList()
                Toast.makeText(this, "缓存已清除", Toast.LENGTH_SHORT).show()
                browse("")
            })
            listContainer.addView(opRow)
        }

        vods.forEach { vod ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(this).apply {
                text = vod.name
                textSize = 16f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            info.addView(TextView(this).apply {
                text = vodMeta(vod)
                textSize = 12f
                setTextColor(TvUi.DIM)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(smallChip("播放") { playVod(vod) })
            row.setOnClickListener { playVod(vod) }
            row.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(4) }
            listContainer.addView(row)
        }
    }

    private fun vodMeta(vod: WebdavVod): String {
        val sb = StringBuilder(if (vod.kind == "series") "剧集 · " else "电影 · ")
        if (vod.year.isNotEmpty()) sb.append(vod.year).append(" · ")
        sb.append(vod.eps.size).append(" 集")
        return sb.toString()
    }

    private fun playVod(vod: WebdavVod) {
        if (vod.eps.size == 1) {
            playEp(vod, 0)
        } else {
            openEpDialog(vod)
        }
    }

    private fun openEpDialog(vod: WebdavVod) {
        val names = vod.eps.map { WebdavClient.epNameOf(it) }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(vod.name)
            .setItems(names) { _, which -> playEp(vod, which) }
            .show()
    }

    private fun playEp(vod: WebdavVod, idx: Int) {
        val src = source ?: return
        val eps = vod.eps.map { Episode(name = WebdavClient.epNameOf(it), url = it.url) }
        if (eps.isEmpty()) return
        val i = idx.coerceIn(0, eps.size - 1)
        val ep = vod.eps[i]

        // 保存 WdHistory 续播记录（对齐鸿蒙 WebdavPage.ets 播放前保存位置记录）
        configStore.saveWdHistory(
            com.hong.tvbox.core.WdHistory(
                srcId = src.id,
                vodName = vod.name,
                epName = WebdavClient.epNameOf(ep),
                pos = 0,
                dur = 0,
                updatedAt = System.currentTimeMillis()
            )
        )

        PlayerActivity.start(
            context = this,
            vodId = ep.url,
            siteKey = "webdav:${src.id}",
            vodName = vod.name,
            lineName = "网盘",
            allEpisodes = eps,
            episodeIndex = i,
            isDirect = true,
            siteApi = "",
            authHeader = WebdavClient.authHeader(src).orEmpty(),
            vodPic = ""
        )
    }

    // ============ 源列表 ============

    private fun renderSources() {
        source = null
        paths.clear()
        listContainer.removeAllViews()
        showListMode()
        titleView.text = "网盘"
        pathView.text = ""
        statusView.text = ""

        // 先放「最近观看」：从网盘看过的片子能一键接着看
        addRecentRow()

        val sources = configStore.getWebdavSources()
        if (sources.isEmpty()) {
            statusView.text = "还没有网盘源。点「扫码添加」用手机扫码填写（推荐），或「手动添加」自行输入。极空间默认端口 5005。"
            return
        }
        titleView.text = "网盘 · ${sources.size} 个源"

        sources.forEach { src: WebdavSource ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            info.addView(TextView(this).apply {
                text = src.name.ifEmpty { src.url }
                textSize = 16f
                setTextColor(Color.WHITE)
            })
            info.addView(TextView(this).apply {
                text = src.url + if (src.root.isNotEmpty()) src.root else ""
                textSize = 12f
                setTextColor(TvUi.DIM)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(smallChip("编辑") { editSource(src) })
            row.addView(smallChip("删除") {
                configStore.removeWebdavSource(src.id)
                renderSources()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            })

            row.setOnClickListener {
                this.source = src
                paths.clear()
                // 先读缓存，有缓存直接展示识别结果；无缓存再浏览目录（对齐鸿蒙 webdavScanCache）
                val cached = configStore.getWebdavScanCache(src.id)
                if (cached != null && cached.isNotEmpty()) {
                    scanVods = cached
                    titleView.text = src.name.ifEmpty { "网盘" }
                    pathView.text = "上次扫描缓存"
                    renderScanResult(cached)
                } else {
                    browse("")
                }
            }
            row.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }
            listContainer.addView(row)
        }
    }

    private fun smallChip(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(TvUi.SUB)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
            onTvFocus(scale = 1f)
        }
    }

    // ============ 最近观看 ============

    /** 从 WdHistory + HistoryStore 合并网盘观看记录（WdHistory 保证识别后才播放也有记录，HistoryStore 有进度） */
    private fun addRecentRow() {
        val wdHist = configStore.getWdHistory()
        // HistoryStore 有播放进度（position/duration），WdHistory 只有「看过的片子」列表
        val histMap = historyStore.getAll()
            .filter { it.siteKey.startsWith("webdav:") }
            .associateBy { it.vodName }

        // WdHistory 为权威列表，按 updatedAt 降序
        val items = wdHist.sortedByDescending { it.updatedAt }.take(12)
        if (items.isEmpty()) {
            // fallback：只有 HistoryStore 有网盘记录时也展示（兼容旧数据）
            val oldRecent = historyStore.getAll().filter { it.siteKey.startsWith("webdav:") }
            if (oldRecent.isEmpty()) return
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            oldRecent.take(10).forEach { row.addView(recentCard(it)) }
            addRecentScrollView(row)
            return
        }

        listContainer.addView(TextView(this).apply {
            text = "最近观看"
            textSize = 15f
            setTextColor(TvUi.SUB)
            setPadding(dp(2), 0, 0, dp(10))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        items.forEach { wd ->
            // 找对应的 HistoryStore 记录（有进度信息），找不到就用 WdHistory 本身
            val hist = histMap[wd.vodName]
            if (hist != null) {
                row.addView(recentCard(hist))
            } else {
                row.addView(wdRecentCard(wd))
            }
        }
        addRecentScrollView(row)
    }

    private fun addRecentScrollView(row: LinearLayout) {
        listContainer.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(row)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(18) })
    }

    /** WdHistory 卡片（无进度信息时展示「已观看」标记） */
    private fun wdRecentCard(wd: com.hong.tvbox.core.WdHistory): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        val cover = FrameLayout(this)
        cover.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A272E"))
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
        ))
        cover.addView(TextView(this).apply {
            text = "🎬"
            textSize = 28f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
        ))
        card.addView(cover, LinearLayout.LayoutParams(dp(196), dp(112)))

        card.addView(TextView(this).apply {
            text = wd.vodName
            textSize = 13f
            setTextColor(TvUi.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = dp(196)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = wd.epName
            textSize = 11f
            setTextColor(TvUi.DIM)
        })

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginEnd = dp(10) }

        card.setOnClickListener {
            // 从缓存找这部片子，找到就播放
            playWdHistory(wd)
        }
        card.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1.04f)
        return card
    }

    /** 从 WdHistory 点进续播：在缓存里找对应片子，定位到上次看的集 */
    private fun playWdHistory(wd: com.hong.tvbox.core.WdHistory) {
        val src = configStore.getWebdavSources().find { it.id == wd.srcId } ?: run {
            Toast.makeText(this, "源已不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val cached = configStore.getWebdavScanCache(src.id) ?: run {
            Toast.makeText(this, "缓存已失效，请重新扫描", Toast.LENGTH_SHORT).show()
            // 缓存没了，进入源目录浏览
            this.source = src
            paths.clear()
            browse("")
            return
        }
        val vod = cached.find { it.name == wd.vodName } ?: run {
            Toast.makeText(this, "未找到「${wd.vodName}」", Toast.LENGTH_SHORT).show()
            return
        }
        // 找到上次看的集
        val epIdx = vod.eps.indexOfFirst { WebdavClient.epNameOf(it) == wd.epName }
        if (epIdx >= 0) {
            this.source = src
            playEp(vod, epIdx)
        } else {
            this.source = src
            playVod(vod)
        }
    }

    private fun recentCard(h: History): View {
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

        // 网盘文件没有海报，放个影片角标，比空灰块好看
        cover.addView(TextView(this).apply {
            text = "🎬"
            textSize = 28f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(112)
        ))

        if (h.vodPic.isNotEmpty()) {
            com.bumptech.glide.Glide.with(this).load(h.vodPic).centerCrop().into(img)
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

        card.addView(TextView(this).apply {
            text = h.vodName
            textSize = 13f
            setTextColor(TvUi.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            maxWidth = dp(196)
            setPadding(0, dp(6), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = if (h.duration > 0) "看到 ${fmt(h.position)} / ${fmt(h.duration)}" else "未开始"
            textSize = 11f
            setTextColor(TvUi.DIM)
        })

        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginEnd = dp(10) }

        card.setOnClickListener {
            PlaybackResumer.resume(lifecycleScope, this, configStore, h) { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        card.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1.04f)
        return card
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        // 一部电影两小时，写成 120:05 很难读，超过一小时就用 h:mm:ss
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ============ 浏览 ============

    private fun browse(path: String) {
        val src = source ?: return
        titleView.text = src.name.ifEmpty { "网盘" }
        pathView.text = if (path.isEmpty()) "/" else "/$path"
        statusView.text = "正在列目录…"
        showGridMode()
        gridContainer.removeAllViews()

        lifecycleScope.launch {
            try {
                val entries = WebdavClient.list(src, path)
                val dirs = entries.filter { it.isDir }
                val videos = entries.filter { WebdavClient.isPlayable(it) }
                val images = entries.filter { !it.isDir && WebdavClient.isImage(it.name) }

                // 封面索引：同名图片优先（xxx.mp4 ↔ xxx.jpg）
                val covers = mutableMapOf<String, String>()
                images.forEach { img ->
                    covers[img.name.substringBeforeLast('.')] = img.name
                }
                val folderCover = images.firstOrNull {
                    val base = it.name.substringBeforeLast('.').lowercase()
                    base == "folder" || base == "poster" || base == "cover" || base == "fanart"
                }?.name

                statusView.text = if (entries.isEmpty()) {
                    "这个目录是空的"
                } else {
                    "${dirs.size} 个文件夹 · ${videos.size} 个视频" +
                            if (images.isNotEmpty()) " · ${images.size} 张图作封面" else ""
                }

                var pos = 0
                dirs.forEach { e ->
                    gridContainer.addView(
                        card(src, path, e, folderCover, pos++) {
                            paths.addLast(path)
                            browse(join(path, e.name))
                        }
                    )
                }

                videos.forEach { e ->
                    val cover = covers[e.name.substringBeforeLast('.')]
                        ?: if (videos.size == 1) folderCover else null
                    gridContainer.addView(
                        card(src, path, e, cover, pos++) { play(src, path, e) }
                    )
                }
            } catch (e: Exception) {
                statusView.text = "列目录失败：${e.message}"
            }
        }
    }

    /**
     * 海报卡片：有同名图片就显示封面，没有就退化成「文件夹/影片」色块。
     * WebDAV 本身没有元数据，靠同名图 + folder.jpg 约定已是能做到的最好程度。
     */
    private fun card(
        src: WebdavSource,
        dirPath: String,
        e: WebdavEntry,
        coverName: String?,
        pos: Int,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#2A272E"))
        }

        val coverBox = FrameLayout(this)
        coverBox.addView(img, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(160)
        ))

        var loaded = false
        if (coverName != null) {
            val url = WebdavClient.urlFor(src, join(dirPath, coverName))
            val auth = WebdavClient.authHeader(src)
            val model = if (auth != null) {
                com.bumptech.glide.load.model.GlideUrl(
                    url,
                    com.bumptech.glide.load.model.LazyHeaders.Builder()
                        .addHeader("Authorization", auth)
                        .build()
                )
            } else url

            com.bumptech.glide.Glide.with(this).load(model).centerCrop().into(img)
            loaded = true
        }

        if (!loaded) {
            // 没有封面就叠一个类型角标，别留一块纯黑
            coverBox.addView(TextView(this).apply {
                text = if (e.isDir) "📁" else "🎬"
                textSize = 30f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(160)
            ))
        }

        card.addView(coverBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(160)
        ))

        card.addView(TextView(this).apply {
            text = e.name
            textSize = 13f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6), 0, 0)
        })

        if (!e.isDir) {
            card.addView(TextView(this).apply {
                text = WebdavClient.humanSize(e.size)
                textSize = 11f
                setTextColor(TvUi.DIM)
            })
        }

        val cellW = (resources.displayMetrics.widthPixels - dp(48)) / 4
        val rowSpec = GridLayout.spec(pos / 4, 1)
        val colSpec = GridLayout.spec(pos % 4, 1)
        card.layoutParams = GridLayout.LayoutParams(rowSpec, colSpec).also {
            it.width = cellW - dp(12)
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            it.setMargins(dp(6), dp(6), dp(6), dp(8))
        }

        card.setOnClickListener { onClick() }
        card.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1.04f)
        return card
    }

    private fun join(path: String, name: String): String {
        return if (path.isEmpty()) name else "$path/$name"
    }

    private fun entryRow(e: WebdavEntry, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val icon = TextView(this).apply {
            text = if (e.isDir) "📁" else "🎬"
            textSize = 18f
            setPadding(0, 0, dp(10), 0)
        }
        row.addView(icon)

        val name = TextView(this).apply {
            text = e.name
            textSize = 15f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (!e.isDir) {
            row.addView(TextView(this).apply {
                text = WebdavClient.humanSize(e.size)
                textSize = 12f
                setTextColor(TvUi.DIM)
            })
        }

        row.setOnClickListener { onClick() }
        row.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(4) }
        return row
    }

    private fun play(src: WebdavSource, dir: String, e: WebdavEntry) {
        // 候选地址：href 是服务端给的那份路径（极空间对文件也带尾斜杠），
        // 自己按「目录/文件名」拼出来的写法也留一份兜底。
        val candidates = buildList {
            if (e.href.isNotEmpty()) {
                val fromHref = WebdavClient.absoluteUrl(src, e.href)
                add(fromHref)
                add(fromHref.trimEnd('/'))
            }
            add(WebdavClient.urlFor(src, join(dir, e.name)))
        }

        statusView.text = "正在连接…"

        lifecycleScope.launch {
            // 先花 1KB 试出哪种写法真能吐媒体流，避免起播后才报 Source error
            val url = WebdavClient.pickPlayable(src, candidates)
            probePlayback(src, url)
            statusView.text = ""

            PlayerActivity.start(
                context = this@WebdavActivity,
                // 用绝对地址当唯一键：续播时不必再回网盘找文件
                vodId = url,
                // 带上源 id，续播时据此取账号密码拼 Authorization
                siteKey = "webdav:${src.id}",
                vodName = e.name,
                lineName = "网盘",
                allEpisodes = listOf(Episode(name = e.name, url = url)),
                episodeIndex = 0,
                isDirect = true,
                siteApi = "",
                authHeader = WebdavClient.authHeader(src).orEmpty()
            )
        }
    }

    /**
     * 播放前探针。
     *
     * 播放器报「Source error」时，光看异常分不清是 401/404 还是服务器返回了网页。
     * 这里用和播放器相同的 URL 取 2KB，把状态码、Content-Type、开头内容写进
     * `files/wd_probe.txt`（adb pull 即可），排查不用再猜。
     */
    private fun probePlayback(src: WebdavSource, url: String) {
        val headers = mutableMapOf<String, String>()
        WebdavClient.authHeader(src)?.let { headers["Authorization"] = it }

        lifecycleScope.launch {
            // 同时探两种写法：带尾斜杠 / 不带。极空间的 href 带斜杠，
            // 但 get 是否接受带斜杠要实测，两个结果都记下来。
            val withSlash = if (url.endsWith("/")) url else "$url/"
            val noSlash = url.trimEnd('/')

            val a = com.hong.tvbox.core.HttpClient.diagnose(noSlash, headers, 8000)
            val b = com.hong.tvbox.core.HttpClient.diagnose(withSlash, headers, 8000)
            val c = com.hong.tvbox.core.HttpClient.diagnose(noSlash, null, 8000)

            android.util.Log.i("TVBoxWD", "probe noSlash=$a")
            android.util.Log.i("TVBoxWD", "probe withSlash=$b")

            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                java.io.File(dir, "wd_probe.txt").writeText(
                    buildString {
                        appendLine("auth=${if (headers.isEmpty()) "none" else "basic"}")
                        appendLine("no_slash = $noSlash")
                        appendLine("   $a")
                        appendLine("with_slash = $withSlash")
                        appendLine("   $b")
                        appendLine("no_auth = $noSlash")
                        appendLine("   $c")
                    }
                )
            }
        }
    }

    // ============ 扫码添加 ============

    /**
     * 显示二维码，手机扫码后在手机上填写 WebDAV 信息，TV 端自动接收保存。
     *
     * TV 上遥控器输地址/账号密码太难用了。这个功能把填写动作转移到手机上：
     * 1. TV 内部起一个微型 HTTP 服务
     * 2. 把配对地址编成二维码显示在 TV 上
     * 3. 手机扫一扫，浏览器打开表单，填完提交
     * 4. TV 轮询收到结果，自动保存并刷新列表
     */
    private fun startQRPair() {
        // 如果已有服务在跑，先停掉
        stopPairServer()

        val server = WebdavPairServer(this)
        pairServer = server

        // 检查能否拿到本机 IP
        val pairUrl = server.pairUrl()
        if (pairUrl == null) {
            Toast.makeText(this, "无法获取 Wi-Fi IP，请确认已连接 Wi-Fi", Toast.LENGTH_LONG).show()
            return
        }

        // 生成二维码
        val qrBmp = QRCodeGenerator.generate(pairUrl, 480)

        // 构建对话框布局
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val qrView = ImageView(this).apply {
            setImageBitmap(qrBmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        container.addView(qrView, LinearLayout.LayoutParams(dp(280), dp(280)).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
        })

        container.addView(TextView(this).apply {
            text = "用手机扫一扫\n在手机上填写 WebDAV 信息"
            textSize = 14f
            setTextColor(TvUi.SUB)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(4))
        })

        container.addView(TextView(this).apply {
            text = pairUrl
            textSize = 11f
            setTextColor(TvUi.DIM)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })

        // 状态提示（等待中...）
        val statusText = TextView(this).apply {
            text = "⏳ 等待手机端提交..."
            textSize = 13f
            setTextColor(TvUi.SELECTED)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        container.addView(statusText)

        val dialog = AlertDialog.Builder(this)
            .setTitle("扫码添加 WebDAV 源")
            .setView(container)
            .setPositiveButton("完成") { _, _ -> }
            .setNegativeButton("取消") { _, _ -> }
            .setOnCancelListener { stopPairServer() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 用户手动确认完成（如果有结果会自动保存，这里只是关闭弹窗）
                dialog.dismiss()
            }
        }
        dialog.show()

        // 启动 HTTP 服务
        pairServerJob = lifecycleScope.launch {
            server.start()
        }

        // 轮询检查是否有结果
        pairPollJob = lifecycleScope.launch {
            while (isActive && pairServer != null) {
                delay(800)
                val result = server.takePending()
                if (result != null) {
                    // 收到了！自动保存
                    val src = WebdavSource(
                        id = "",
                        name = result.name.ifEmpty { guessSourceName(result.url) },
                        url = result.url,
                        user = result.user,
                        pass = result.pass,
                        root = result.root
                    )
                    configStore.saveWebdavSource(src)
                    statusText.text = "✅ 已保存「${src.name}」！"
                    statusText.setTextColor(0xFF4CAF50.toInt())

                    delay(1500)
                    dialog.dismiss()
                    stopPairServer()
                    renderSources()
                    break
                }
            }
        }
    }

    private fun stopPairServer() {
        pairPollJob?.cancel()
        pairPollJob = null
        pairServerJob?.cancel()
        pairServerJob = null
        pairServer?.stop()
        pairServer = null
    }

    /** 从 URL 猜测源名（如 http://192.168.1.2:5005 → "192.168.1.2"） */
    private fun guessSourceName(url: String): String {
        return try {
            val noProto = url.substringAfter("://")
            val host = noProto.substringBefore('/').substringBefore(':')
            host
        } catch (_: Exception) {
            url
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPairServer()
    }

    // ============ 编辑源 ============
    private fun editSource(existing: WebdavSource?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(0))
        }

        fun field(hint: String, value: String, password: Boolean = false): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                textSize = 15f
                setTextColor(Color.WHITE)
                setHintTextColor(TvUi.DIM)
                if (password) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                box.addView(this)
            }
        }

        val nameInput = field("名称，如 极空间", existing?.name ?: "")
        val urlInput = field("地址，如 http://abber.cn:5005", existing?.url ?: "")
        val userInput = field("用户名", existing?.user ?: "")
        val passInput = field("密码", existing?.pass ?: "", password = true)
        val rootInput = field("根路径（可空），如 /影视", existing?.root ?: "")

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "添加 WebDAV 源" else "编辑源")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, "地址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val src = WebdavSource(
                    id = existing?.id ?: "",
                    name = nameInput.text.toString().trim(),
                    url = url.trimEnd('/'),
                    user = userInput.text.toString().trim(),
                    pass = passInput.text.toString(),
                    root = rootInput.text.toString().trim()
                )
                configStore.saveWebdavSource(src)
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                renderSources()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
