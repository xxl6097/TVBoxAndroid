package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hong.tvbox.core.*
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 播放器页面 - 完整移植自 Player.ets
 * 实现：ExoPlayer + 播放地址解析 + 控制条 + 进度条 + 切集 + 历史
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var historyStore: HistoryStore
    private lateinit var configStore: ConfigStore

    // 播放参数
    private lateinit var vodId: String
    private lateinit var siteKey: String
    private lateinit var vodName: String
    private lateinit var lineName: String
    private lateinit var allEpisodes: List<Episode>
    private var currentEpisodeIndex = 0
    private var isDirectLine = false
    private lateinit var siteApi: String

    // 播放状态
    private var isPlaying = false
    private var isPrepared = false
    private var isResolving = true
    private var isBuffering = false
    private var showOsd = true
    private var resolvedUrl = ""
    private var resolvedVia = ""
    private var resolvedHeaders: Map<String, String> = emptyMap()

    // UI 控件
    private lateinit var loadingView: View
    private lateinit var errorView: View
    private lateinit var errorText: TextView
    private lateinit var osdView: View
    private lateinit var osdTitle: TextView
    private lateinit var osdProgress: SeekBar
    private lateinit var osdCurrentTime: TextView
    private lateinit var osdTotalTime: TextView
    private lateinit var osdPrevBtn: Button
    private lateinit var osdPlayPauseBtn: Button
    private lateinit var osdNextBtn: Button
    private lateinit var osdRetryBtn: Button
    private lateinit var osdVia: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val osdRunnable = Runnable { if (isPlaying) hideOsd() }

    companion object {
        const val EXTRA_VOD_ID = "vod_id"
        const val EXTRA_SITE_KEY = "site_key"
        const val EXTRA_VOD_NAME = "vod_name"
        const val EXTRA_LINE_NAME = "line_name"
        const val EXTRA_EPISODE_INDEX = "episode_index"
        const val EXTRA_IS_DIRECT = "is_direct"
        const val EXTRA_SITE_API = "site_api"

        // 所有剧集 URL 和名称用两个并行数组传递
        const val EXTRA_EP_URLS = "ep_urls"
        const val EXTRA_EP_NAMES = "ep_names"

        fun start(
            context: Context,
            vodId: String,
            siteKey: String,
            vodName: String,
            lineName: String,
            allEpisodes: List<Episode>,
            episodeIndex: Int,
            isDirect: Boolean,
            siteApi: String
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VOD_ID, vodId)
                putExtra(EXTRA_SITE_KEY, siteKey)
                putExtra(EXTRA_VOD_NAME, vodName)
                putExtra(EXTRA_LINE_NAME, lineName)
                putExtra(EXTRA_EPISODE_INDEX, episodeIndex)
                putExtra(EXTRA_IS_DIRECT, isDirect)
                putExtra(EXTRA_SITE_API, siteApi)
                putExtra(EXTRA_EP_URLS, allEpisodes.map { it.url }.toTypedArray())
                putExtra(EXTRA_EP_NAMES, allEpisodes.map { it.name }.toTypedArray())
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        historyStore = HistoryStore(this)
        configStore = ConfigStore(this)

        // 读取参数
        vodId = intent.getStringExtra(EXTRA_VOD_ID) ?: return finish()
        siteKey = intent.getStringExtra(EXTRA_SITE_KEY) ?: return finish()
        vodName = intent.getStringExtra(EXTRA_VOD_NAME) ?: ""
        lineName = intent.getStringExtra(EXTRA_LINE_NAME) ?: ""
        currentEpisodeIndex = intent.getIntExtra(EXTRA_EPISODE_INDEX, 0)
        isDirectLine = intent.getBooleanExtra(EXTRA_IS_DIRECT, false)
        siteApi = intent.getStringExtra(EXTRA_SITE_API) ?: ""

        val epUrls = intent.getStringArrayExtra(EXTRA_EP_URLS) ?: arrayOf()
        val epNames = intent.getStringArrayExtra(EXTRA_EP_NAMES) ?: arrayOf()
        allEpisodes = epUrls.indices.map { i ->
            Episode(name = epNames.getOrElse(i) { "第${i + 1}集" }, url = epUrls[i])
        }

        if (allEpisodes.isEmpty()) {
            Toast.makeText(this, "没有可播放的剧集", Toast.LENGTH_SHORT).show()
            return finish()
        }

        buildUI()
        startPlayback()
    }

    private fun buildUI() {
        // 根容器
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // ExoPlayer 视图
        playerView = PlayerView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            useController = false // 用自定义控制条
        }
        root.addView(playerView)

        // 加载中
        val loadingLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        val progressBar = ProgressBar(this)
        loadingLayout.addView(progressBar, LinearLayout.LayoutParams(dp(52), dp(52)).also {
            it.gravity = android.view.Gravity.CENTER
        })
        val loadingText = TextView(this).apply {
            text = "正在解析播放地址…"
            textSize = 18f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(0, dp(14), 0, 0)
            gravity = android.view.Gravity.CENTER
        }
        loadingLayout.addView(loadingText)
        loadingView = loadingLayout
        root.addView(loadingView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // 错误页
        val errorLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000000"))
            visibility = View.GONE
        }
        errorText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.parseColor("#FFB4AB"))
            gravity = android.view.Gravity.CENTER
        }
        errorLayout.addView(errorText)
        osdRetryBtn = Button(this).apply {
            text = "重试"
            setOnClickListener { retry() }
        }
        errorLayout.addView(osdRetryBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(20); it.gravity = android.view.Gravity.CENTER })
        errorView = errorLayout
        root.addView(errorView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // OSD 控制条
        val osdLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            setPadding(dp(24), dp(24), dp(24), dp(16))
            visibility = View.GONE
        }
        // 标题行
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        osdTitle = TextView(this).apply {
            text = vodName
            textSize = 20f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleRow.addView(osdTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        osdLayout.addView(titleRow)

        // 进度行
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        osdCurrentTime = TextView(this).apply {
            text = "00:00"
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
        }
        progressRow.addView(osdCurrentTime, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))
        osdProgress = SeekBar(this).apply {
            max = 1000
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && player != null && isPrepared) {
                        val pos = (progress.toLong() * (player?.duration ?: 0)) / 1000
                        player?.seekTo(pos)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        progressRow.addView(osdProgress, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        osdTotalTime = TextView(this).apply {
            text = "00:00"
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = android.view.Gravity.END
        }
        progressRow.addView(osdTotalTime, LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT))
        osdLayout.addView(progressRow)

        // 控制按钮行
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        osdPrevBtn = Button(this).apply {
            text = "上一集"
            setOnClickListener { switchEpisode(-1) }
        }
        controlRow.addView(osdPrevBtn)
        osdPlayPauseBtn = Button(this).apply {
            text = "播放"
            setOnClickListener { togglePlay() }
        }
        controlRow.addView(osdPlayPauseBtn)
        osdNextBtn = Button(this).apply {
            text = "下一集"
            setOnClickListener { switchEpisode(1) }
        }
        controlRow.addView(osdNextBtn)
        osdLayout.addView(controlRow)

        // 解析来源
        osdVia = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(8), 0, 0)
        }
        osdLayout.addView(osdVia)

        osdView = osdLayout
        root.addView(osdView, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM
        ))

        setContentView(root)

        // 点击画面切换 OSD
        root.setOnClickListener {
            if (showOsd) {
                if (isPrepared) togglePlay()
            } else {
                showOsd()
            }
        }
    }

    private fun startPlayback() {
        isResolving = true
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        osdView.visibility = View.GONE

        val episode = allEpisodes.getOrNull(currentEpisodeIndex) ?: return
        osdTitle.text = "$vodName ${episode.name}"

        lifecycleScope.launch {
            try {
                if (isDirectLine) {
                    // 直连模式：地址本身就是流
                    resolvedUrl = episode.url
                    resolvedVia = "直连"
                } else {
                    // 走解析
                    val parses = configStore.getConfig()?.parses ?: emptyList()
                    val result = ResolveClient.resolve(episode.url, parses)
                    resolvedUrl = result.url
                    resolvedVia = result.via
                    resolvedHeaders = result.headers
                    if (!result.ok && result.via.contains("未解析")) {
                        // 仍尝试直接播放
                    }
                }

                osdVia.text = "解析：$resolvedVia"
                initPlayer()

            } catch (e: Exception) {
                showError("播放失败：${e.message}")
            } finally {
                isResolving = false
                loadingView.visibility = View.GONE
            }
        }
    }

    private fun initPlayer() {
        if (resolvedUrl.isEmpty()) {
            showError("无法获取播放地址")
            return
        }

        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            isPrepared = true
                            isBuffering = false
                            loadingView.visibility = View.GONE
                            // 如果有历史进度，seek 过去
                            val history = historyStore.get(vodId, siteKey)
                            if (history != null && history.episodeIndex == currentEpisodeIndex && history.position > 0) {
                                p.seekTo(history.position)
                            }
                            p.play()
                            isPlaying = true
                            osdPlayPauseBtn.text = "暂停"
                            showOsd()
                        }
                        Player.STATE_BUFFERING -> {
                            isBuffering = true
                        }
                        Player.STATE_ENDED -> {
                            isPlaying = false
                            osdPlayPauseBtn.text = "播放"
                            // 自动播下一集
                            if (currentEpisodeIndex < allEpisodes.size - 1) {
                                switchEpisode(1)
                            } else {
                                showOsd()
                            }
                        }
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    showError("播放失败：${error.message}")
                }

                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                    osdPlayPauseBtn.text = if (playing) "暂停" else "播放"
                    if (playing) showOsd()
                }
            })

            // 创建媒体项
            val mediaItem = MediaItem.fromUri(resolvedUrl)
            p.setMediaItem(mediaItem)
            p.prepare()
        }

        // 进度更新
        handler.postDelayed(object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (isPrepared && p.duration > 0) {
                        val pos = p.currentPosition
                        val dur = p.duration
                        osdProgress.progress = ((pos * 1000) / dur).toInt()
                        osdCurrentTime.text = formatTime(pos)
                        osdTotalTime.text = formatTime(dur)
                    }
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    private fun togglePlay() {
        player?.let { p ->
            if (p.isPlaying) {
                p.pause()
                isPlaying = false
                osdPlayPauseBtn.text = "播放"
                showOsd()
            } else {
                p.play()
                isPlaying = true
                osdPlayPauseBtn.text = "暂停"
                showOsd()
            }
        }
    }

    private fun switchEpisode(delta: Int) {
        val newIndex = currentEpisodeIndex + delta
        if (newIndex < 0) {
            Toast.makeText(this, "已经是第一集", Toast.LENGTH_SHORT).show()
            return
        }
        if (newIndex >= allEpisodes.size) {
            Toast.makeText(this, "已经是最后一集", Toast.LENGTH_SHORT).show()
            return
        }

        // 保存当前位置
        savePosition()

        currentEpisodeIndex = newIndex
        player?.release()
        player = null
        isPrepared = false
        isPlaying = false

        val episode = allEpisodes[currentEpisodeIndex]
        Toast.makeText(this, "第${currentEpisodeIndex + 1}集 ${episode.name}", Toast.LENGTH_SHORT).show()

        startPlayback()
    }

    private fun retry() {
        player?.release()
        player = null
        isPrepared = false
        isPlaying = false
        errorView.visibility = View.GONE
        startPlayback()
    }

    private fun savePosition() {
        player?.let { p ->
            if (isPrepared && p.currentPosition > 0) {
                historyStore.addOrUpdate(History(
                    vodId = vodId,
                    siteKey = siteKey,
                    vodName = vodName,
                    lineIndex = 0,
                    episodeIndex = currentEpisodeIndex,
                    episodeName = allEpisodes.getOrNull(currentEpisodeIndex)?.name ?: "",
                    position = p.currentPosition,
                    duration = p.duration
                ))
            }
        }
    }

    private fun showOsd() {
        showOsd = true
        osdView.visibility = View.VISIBLE
        handler.removeCallbacks(osdRunnable)
        handler.postDelayed(osdRunnable, 12000)
    }

    private fun hideOsd() {
        showOsd = false
        osdView.visibility = View.GONE
    }

    private fun showError(msg: String) {
        loadingView.visibility = View.GONE
        osdView.visibility = View.GONE
        errorText.text = msg
        errorView.visibility = View.VISIBLE
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        val mm = if (m < 10) "0$m" else m.toString()
        val ss = if (s < 10) "0$s" else s.toString()
        return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
    }

    override fun onPause() {
        super.onPause()
        savePosition()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
