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
            maxLines = 2
        }
        urlInput.tvInputStyle()
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

            // 两行：标题（解析文件名）+ 完整地址灰字
            val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            textBox.addView(TextView(this).apply {
                text = titleOf(url)
                textSize = 15f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textBox.addView(TextView(this).apply {
                text = url
                textSize = 12f
                setTextColor(TvUi.DIM)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(textBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val play = TextView(this).apply {
                text = "播放"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
            }
            row.addView(play)

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
            Toast.makeText(this, "先填一个地址", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = url.startsWith("http://") || url.startsWith("https://") ||
            url.startsWith("file://") || url.startsWith("/data/")
        if (!ok) {
            Toast.makeText(this, "只支持 http / https / 本地文件路径，rtmp、rtsp 系统解码器播不了", Toast.LENGTH_SHORT).show()
            return
        }

        configStore.pushDirect(url)
        renderHistory()

        // skipHistory：不写观看历史，避免污染「继续观看」；按钮仍显示「上/下一集」
        PlayerActivity.start(
            context = this,
            vodId = "direct_$url",
            siteKey = "direct",
            vodName = titleOf(url),
            lineName = "直连",
            allEpisodes = listOf(Episode(name = "直连", url = url)),
            episodeIndex = 0,
            isDirect = true,
            siteApi = "",
            skipHistory = true
        )
    }

    /** 标题解析：截 query → 取最后一段 → 去扩展名 → URL 解码 */
    private fun titleOf(u: String): String {
        var s = u.substringBefore('?')
        s = s.substringAfterLast('/')
        s = s.substringBeforeLast('.', s)
        return try {
            java.net.URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
