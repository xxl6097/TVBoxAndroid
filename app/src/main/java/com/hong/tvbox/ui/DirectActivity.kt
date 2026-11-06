package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.TextUtils
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hong.tvbox.core.Episode
import com.hong.tvbox.data.ConfigStore

/**
 * 直连播放 - 对照鸿蒙 Direct.ets
 * 手头有一个 m3u8/mp4 地址时，不必配站源也能直接播。
 */
class DirectActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var urlInput: EditText
    private lateinit var historyList: LinearLayout
    private lateinit var statusView: TextView

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, DirectActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        buildUI()
        renderHistory()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(TvUi.BG)
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
        back.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
        topBar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        val title = TextView(this).apply {
            text = "直连播放"
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(topBar)

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(TvUi.DIM)
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlInput = EditText(this).apply {
            hint = "粘贴 m3u8 / mp4 / flv 地址"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(TvUi.DIM)
            setBackgroundColor(TvUi.CARD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            maxLines = 2
        }
        inputRow.addView(urlInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val playBtn = Button(this).apply {
            text = "播放"
            setOnClickListener { playCurrent() }
        }
        inputRow.addView(playBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).also { it.marginStart = dp(12) })
        root.addView(inputRow)

        root.addView(TextView(this).apply {
            text = "最近播放"
            textSize = 15f
            setTextColor(TvUi.SUB)
            setPadding(0, dp(20), 0, dp(8))
        })

        val scroll = ScrollView(this)
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(historyList)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun renderHistory() {
        historyList.removeAllViews()
        val list = configStore.getDirectList()

        if (list.isEmpty()) {
            statusView.text = "还没有直连记录。填入一个视频地址点「播放」即可。"
            return
        }
        statusView.text = "共 ${list.size} 条记录（点击即播）"

        list.forEach { url ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
            }

            val text = TextView(this).apply {
                text = url
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.setOnClickListener {
                urlInput.setText(url)
                playCurrent()
            }
            row.onTvFocus(normalColor = Color.TRANSPARENT, focusColor = TvUi.CARD_HI, scale = 1f)
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = dp(6) }

            historyList.addView(row)
        }
    }

    private fun playCurrent() {
        val url = urlInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请先粘贴视频地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "只支持 http/https 地址", Toast.LENGTH_SHORT).show()
            return
        }

        configStore.pushDirect(url)
        renderHistory()

        // isLive=true 复用「不写观看历史 + 单条线路」的行为，避免污染继续观看
        PlayerActivity.start(
            context = this,
            vodId = "direct_$url",
            siteKey = "direct",
            vodName = url.substringAfterLast('/'),
            lineName = "直连",
            allEpisodes = listOf(Episode(name = "直连", url = url)),
            episodeIndex = 0,
            isDirect = true,
            siteApi = "",
            isLive = true
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
