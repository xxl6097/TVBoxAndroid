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
import com.hong.tvbox.core.CmsClient
import com.hong.tvbox.core.History
import com.hong.tvbox.core.Vod
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 历史页 - 对照鸿蒙 HistoryPage.ets
 * 横条卡片：海报 + 片名 + 看到第几集 + 进度；点击即续播
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var historyStore: HistoryStore
    private lateinit var configStore: ConfigStore
    private lateinit var listContainer: LinearLayout
    private lateinit var statusView: TextView

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, HistoryActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyStore = HistoryStore(this)
        configStore = ConfigStore(this)
        buildUI()
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141218"))
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        val title = TextView(this).apply {
            text = "历史"
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // 二次确认清空
        var armed = false
        val clearBtn = TextView(this).apply {
            text = "清空历史"
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                if (!armed) {
                    armed = true
                    text = "确认清空？"
                    setTextColor(Color.parseColor("#FFB4AB"))
                } else {
                    historyStore.clear()
                    armed = false
                    text = "清空历史"
                    setTextColor(Color.parseColor("#B3FFFFFF"))
                    render()
                    Toast.makeText(this@HistoryActivity, "已清空历史", Toast.LENGTH_SHORT).show()
                }
            }
        }
        topBar.addView(clearBtn)
        root.addView(topBar)

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        val scroll = ScrollView(this)
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun render() {
        val list = historyStore.getAll()
        listContainer.removeAllViews()

        if (list.isEmpty()) {
            statusView.text = "还没有观看记录"
            return
        }
        statusView.text = "共 ${list.size} 条"

        list.forEach { h ->
            listContainer.addView(buildRow(h))
        }
    }

    private fun buildRow(h: History): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#1C1B1F"))
        }

        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.parseColor("#33000000"))
        }
        row.addView(img, LinearLayout.LayoutParams(dp(112), dp(64)))
        if (h.vodPic.isNotEmpty()) {
            Glide.with(this).load(h.vodPic).centerCrop().into(img)
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
        }

        val name = TextView(this).apply {
            text = h.vodName
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        info.addView(name)

        val epText = buildString {
            if (h.episodeName.isNotEmpty()) append(h.episodeName) else append("第 ${h.episodeIndex + 1} 集")
            if (h.duration > 0) {
                append("   看到 ")
                append(fmt(h.position))
                append(" / ")
                append(fmt(h.duration))
            }
        }
        val ep = TextView(this).apply {
            text = epText
            textSize = 13f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(0, dp(4), 0, 0)
        }
        info.addView(ep)

        // 进度条
        if (h.duration > 0 && h.position > 0) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = ((h.position * 1000) / h.duration).toInt().coerceIn(0, 1000)
            }
            info.addView(bar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)
            ).also { it.topMargin = dp(6) })
        }

        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val del = TextView(this).apply {
            text = "✕"
            textSize = 16f
            setTextColor(Color.parseColor("#80FFFFFF"))
            gravity = Gravity.CENTER
            setOnClickListener {
                historyStore.remove(h.vodId, h.siteKey)
                render()
            }
        }
        row.addView(del, LinearLayout.LayoutParams(dp(40), dp(40)))

        row.setOnClickListener { resume(h) }
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.bottomMargin = dp(8) }

        return row
    }

    /** 点击历史：拉一次详情拿到剧集地址，定位到上次那一集并 seek */
    private fun resume(h: History) {
        val site = configStore.getConfig()?.sites?.find { it.key == h.siteKey }
        if (site == null) {
            Toast.makeText(this, "该站源已不在配置里", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "正在准备续播…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val vod: Vod = CmsClient(site).detail(h.vodId)
                val lines = CmsClient.splitLines(vod.from, vod.playUrl)
                if (lines.isEmpty()) {
                    Toast.makeText(this@HistoryActivity, "该片没有可播放地址", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val lineIndex = h.lineIndex.coerceIn(0, lines.size - 1)
                val epIndex = h.episodeIndex.coerceIn(0, lines[lineIndex].eps.size - 1)

                PlayerActivity.start(
                    context = this@HistoryActivity,
                    vodId = h.vodId,
                    siteKey = h.siteKey,
                    vodName = h.vodName.ifEmpty { vod.name },
                    lineName = lines[lineIndex].name,
                    allEpisodes = lines[lineIndex].eps,
                    episodeIndex = epIndex,
                    isDirect = lines[lineIndex].direct,
                    siteApi = site.api,
                    startPosition = h.position
                )
            } catch (e: Exception) {
                Toast.makeText(this@HistoryActivity, "续播失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fmt(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%d:%02d".format(m, s)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
