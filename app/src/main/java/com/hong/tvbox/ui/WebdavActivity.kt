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
import com.hong.tvbox.core.Episode
import com.hong.tvbox.core.WebdavClient
import com.hong.tvbox.core.WebdavEntry
import com.hong.tvbox.core.WebdavSource
import com.hong.tvbox.data.ConfigStore
import kotlinx.coroutines.launch

/**
 * 网盘（WebDAV）- 对照鸿蒙 WebdavPage.ets
 *
 * 极空间/群晖/AList 都自带 WebDAV，填地址账号就能直接播自己的片子，
 * 比影视源稳定得多。这里只做「列目录 + 播放」这一条最小链路。
 */
class WebdavActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore

    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var gridContainer: GridLayout

    private var source: WebdavSource? = null
    /** 浏览路径栈（相对挂载根），空栈表示根目录 */
    private val paths = ArrayDeque<String>()

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, WebdavActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        buildUI()
        renderSources()
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
            textSize = 20f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(chip("添加源") { editSource(null) })
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

    // ============ 源列表 ============

    private fun renderSources() {
        source = null
        paths.clear()
        listContainer.removeAllViews()
        showListMode()
        titleView.text = "网盘"
        pathView.text = ""
        statusView.text = ""

        val sources = configStore.getWebdavSources()
        if (sources.isEmpty()) {
            statusView.text = "还没有网盘源。点「添加源」填 WebDAV 地址（极空间默认端口 5005）。"
            return
        }
        titleView.text = "网盘 · ${sources.size} 个源"

        sources.forEach { src ->
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
                browse("")
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
                val videos = entries.filter { !it.isDir && WebdavClient.isVideo(it.name) }
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
        val url = WebdavClient.urlFor(src, join(dir, e.name))
        PlayerActivity.start(
            context = this,
            vodId = "wd_$url",
            siteKey = "webdav",
            vodName = e.name,
            lineName = "网盘",
            allEpisodes = listOf(Episode(name = e.name, url = url)),
            episodeIndex = 0,
            isDirect = true,
            siteApi = "",
            isLive = true,   // 复用：不写观看历史
            authHeader = WebdavClient.authHeader(src).orEmpty()
        )
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
