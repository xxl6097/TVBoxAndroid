package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
    /** 续播目标位置（毫秒），0 表示从头播 */
    private var pendingSeek: Long = 0L
    /** 直播模式：不记观看历史，上/下一集改叫上/下一线路 */
    private var isLiveMode = false
    /** 直连/网盘这类一次性播放：不写观看历史，但按钮文案仍是「上/下一集」 */
    private var skipHistory = false
    /** WebDAV 等需要认证的源，播放请求要带 Authorization */
    private var authHeader = ""
    /** 海报地址与线路索引：写观看历史时要用，否则「继续观看」没封面、续播还会串线路 */
    private var vodPic = ""
    private var lineIndex = 0

    /** 本轮播放是否已经因错误自动换过线路（避免无限换线） */
    private var autoSwitched = false
    /** 直播当前台的全部线路（含主线路），播挂了按这个顺序换线 */
    private var liveCandidates: List<String> = emptyList()
    /** 当前用的是第几条线路（从 0 起） */
    private var liveLineIdx = 0

    // ---- 画质 ----
    private val qualityNames = arrayOf("自动", "1080P", "720P", "480P")
    private var qualityIdx = 0
    /** 画面比例：等比留黑边 / 裁剪铺满 / 拉伸铺满 */
    private val scaleNames = arrayOf("等比", "裁剪", "拉伸")
    private var scaleIdx = 2
    /** 连续起播卡顿次数，达到阈值自动降档 */
    private var rebufferCount = 0
    /** 上一次卡顿的时间点，用来判断“连续”卡顿 */
    private var lastBufferAt = 0L
    /** 本轮是否已经因弱网自动降过档，避免反复降 */
    private var autoDowngraded = false

    // ---- 片头 / 片尾跳过 ----
    private var introSkipSec = 0
    private var outroSkipSec = 0
    private var introDone = false
    private var outroFired = false
    private lateinit var skipBtn: TextView
    private val skipRunnable = Runnable { hideSkipButton() }
    private var skipCountdown = 0
    private var skipAction: (() -> Unit)? = null

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
    private lateinit var osdMuteBtn: Button
    private lateinit var osdQualityBtn: Button
    private lateinit var osdScaleBtn: Button
    /** 画面正中的一次性提示（±10 秒 / 音量 / 切集），3 米外也看得清 */
    private lateinit var centerTip: TextView

    private var isMuted = false
    private var volumeBeforeMute = 1.0f

    private val handler = Handler(Looper.getMainLooper())
    private val osdRunnable = Runnable { if (isPlaying) hideOsd() }
    private val tipRunnable = Runnable { centerTip.visibility = View.GONE }

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
        const val EXTRA_START_POS = "start_pos"
        const val EXTRA_IS_LIVE = "is_live"
        const val EXTRA_AUTH = "auth_header"
        const val EXTRA_VOD_PIC = "vod_pic"
        const val EXTRA_LINE_INDEX = "line_index"
        const val EXTRA_SKIP_HISTORY = "skip_history"
        const val EXTRA_EP_JSON = "ep_json"

        fun start(
            context: Context,
            vodId: String,
            siteKey: String,
            vodName: String,
            lineName: String,
            allEpisodes: List<Episode>,
            episodeIndex: Int,
            isDirect: Boolean,
            siteApi: String,
            startPosition: Long = 0L,
            isLive: Boolean = false,
            skipHistory: Boolean = false,
            authHeader: String = "",
            vodPic: String = "",
            lineIndex: Int = 0
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_VOD_ID, vodId)
                putExtra(EXTRA_SITE_KEY, siteKey)
                putExtra(EXTRA_VOD_NAME, vodName)
                putExtra(EXTRA_LINE_NAME, lineName)
                putExtra(EXTRA_EPISODE_INDEX, episodeIndex)
                putExtra(EXTRA_IS_DIRECT, isDirect)
                putExtra(EXTRA_SITE_API, siteApi)
                putExtra(EXTRA_EP_JSON, Gson().toJson(allEpisodes))
                putExtra(EXTRA_EP_URLS, allEpisodes.map { it.url }.toTypedArray())
                putExtra(EXTRA_EP_NAMES, allEpisodes.map { it.name }.toTypedArray())
                putExtra(EXTRA_START_POS, startPosition)
                putExtra(EXTRA_IS_LIVE, isLive)
                putExtra(EXTRA_SKIP_HISTORY, skipHistory)
                putExtra(EXTRA_AUTH, authHeader)
                putExtra(EXTRA_VOD_PIC, vodPic)
                putExtra(EXTRA_LINE_INDEX, lineIndex)
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
        pendingSeek = intent.getLongExtra(EXTRA_START_POS, 0L)
        isLiveMode = intent.getBooleanExtra(EXTRA_IS_LIVE, false)
        skipHistory = intent.getBooleanExtra(EXTRA_SKIP_HISTORY, false)
        authHeader = intent.getStringExtra(EXTRA_AUTH) ?: ""
        vodPic = intent.getStringExtra(EXTRA_VOD_PIC) ?: ""
        lineIndex = intent.getIntExtra(EXTRA_LINE_INDEX, 0)

        introSkipSec = configStore.introSkipSec()
        outroSkipSec = configStore.outroSkipSec()

        // 恢复画质偏好（0=自动/>=1080→1080/>=720→720/>=480→480）
        qualityIdx = when (val q = configStore.qPref()) {
            0 -> 0
            in 1..480 -> 3
            in 481..720 -> 2
            else -> 1
        }

        val epJson = intent.getStringExtra(EXTRA_EP_JSON)
        allEpisodes = if (!epJson.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<Episode>>() {}.type
                Gson().fromJson<List<Episode>>(epJson, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            val epUrls = intent.getStringArrayExtra(EXTRA_EP_URLS) ?: arrayOf()
            val epNames = intent.getStringArrayExtra(EXTRA_EP_NAMES) ?: arrayOf()
            epUrls.indices.map { i ->
                Episode(name = epNames.getOrElse(i) { "第${i + 1}集" }, url = epUrls[i])
            }
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
            text = if (isLiveMode) "上一台" else "上一集"
            setOnClickListener { switchEpisode(-1) }
        }
        controlRow.addView(osdPrevBtn)

        // 快退 / 快进 10 秒（遥控器左右键的等价按钮）
        controlRow.addView(osdButton("−  10 秒") { seekBy(-10_000L) })
        osdPlayPauseBtn = Button(this).apply {
            text = "播放"
            setOnClickListener { togglePlay() }
        }
        controlRow.addView(osdPlayPauseBtn)
        controlRow.addView(osdButton("+  10 秒") { seekBy(10_000L) })

        osdNextBtn = Button(this).apply {
            text = if (isLiveMode) "下一台" else "下一集"
            setOnClickListener { switchEpisode(1) }
        }
        controlRow.addView(osdNextBtn)

        // 音量：调的是播放器音量（不受系统静音影响，电视上更可控）
        controlRow.addView(osdButton("音量−") { changeVolume(-0.1f) })
        osdMuteBtn = osdButton("静音") { toggleMute() }
        controlRow.addView(osdMuteBtn)
        controlRow.addView(osdButton("音量+") { changeVolume(0.1f) })

        // 画质：HLS 自适应之外，允许手动限高；弱网也会自动降一档
        osdQualityBtn = osdButton("画质 ${qualityNames[qualityIdx]}") { cycleQuality() }
        controlRow.addView(osdQualityBtn)

        // 画面比例：等比 / 裁剪 / 拉伸
        osdScaleBtn = osdButton("比例 ${scaleNames[scaleIdx]}") { cycleScale() }
        controlRow.addView(osdScaleBtn)

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

        // 中央提示层：叠在画面正中，播完自动淡出
        centerTip = TextView(this).apply {
            textSize = 34f
            setTextColor(Color.WHITE)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        root.addView(centerTip, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        ))

        // 片头/片尾跳过按钮：右下角胶囊 + 倒计时
        skipBtn = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            gravity = android.view.Gravity.CENTER
            setPadding(dp(22), dp(12), dp(22), dp(12))
            visibility = View.GONE
            setOnClickListener {
                skipAction?.invoke()
            }
        }
        root.addView(skipBtn, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM or android.view.Gravity.END
        ).also {
            it.bottomMargin = dp(120)
            it.rightMargin = dp(24)
        })

        // 点击画面切换 OSD
        root.setOnClickListener {
            if (showOsd) {
                if (isPrepared) togglePlay()
            } else {
                showOsd()
            }
        }
    }

    /** 控制条里的紧凑按钮 */
    private fun osdButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            minimumWidth = 0
            minWidth = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = dp(8) }
            setOnClickListener { action() }
        }
    }

    /**
     * 遥控器按键。
     *
     * OSD 可见且焦点在控制条上时，方向键要留给焦点移动，
     * 否则用户想在「上一集 / 播放 / 下一集」之间挪都挪不动。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)
        val osdFocused = osdHasFocus()

        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                if (osdFocused) return super.onKeyDown(keyCode, event)
                if (isPrepared) togglePlay()
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (osdFocused) return super.onKeyDown(keyCode, event)
                // 长按连发时步长放大到 30 秒，避免一路点
                seekBy(if (event.repeatCount > 0) -30_000L else -10_000L)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (osdFocused) return super.onKeyDown(keyCode, event)
                seekBy(if (event.repeatCount > 0) 30_000L else 10_000L)
                return true
            }

            // 鸿蒙版：↑ / ↓ 是音量 ±（VOL_STEP=0.05），不是 seek
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (osdFocused) return super.onKeyDown(keyCode, event)
                changeVolume(0.05f)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (osdFocused) return super.onKeyDown(keyCode, event)
                changeVolume(-0.05f)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                player?.play(); return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.pause(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun osdHasFocus(): Boolean {
        if (osdView.visibility != View.VISIBLE) return false
        var v: View? = currentFocus ?: return false
        while (v != null) {
            if (v === osdView) return true
            v = v.parent as? View
        }
        return false
    }

    /** 相对定位，越界自动夹到 [0, duration] */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        if (!isPrepared || p.duration <= 0) return

        val target = (p.currentPosition + deltaMs).coerceIn(0L, p.duration)
        p.seekTo(target)

        val secs = Math.abs(deltaMs) / 1000
        showTip((if (deltaMs > 0) "▶▶ +" else "◀◀ −") + "${secs}s    " + formatTime(target))
        showOsd()
    }

    private fun changeVolume(delta: Float) {
        val p = player ?: return
        val next = (p.volume + delta).coerceIn(0f, 1f)
        p.volume = next
        if (next > 0f) {
            isMuted = false
            volumeBeforeMute = next
            if (this::osdMuteBtn.isInitialized) osdMuteBtn.text = "静音"
        }
        showTip("音量 ${(next * 100).toInt()}%")
        showOsd()
    }

    private fun toggleMute() {
        val p = player ?: return
        if (isMuted) {
            p.volume = volumeBeforeMute
            isMuted = false
            if (this::osdMuteBtn.isInitialized) osdMuteBtn.text = "静音"
            showTip("已取消静音")
        } else {
            volumeBeforeMute = p.volume
            p.volume = 0f
            isMuted = true
            if (this::osdMuteBtn.isInitialized) osdMuteBtn.text = "取消静音"
            showTip("静音")
        }
        showOsd()
    }

    private fun showTip(text: String) {
        if (!this::centerTip.isInitialized) return
        centerTip.text = text
        centerTip.visibility = View.VISIBLE
        handler.removeCallbacks(tipRunnable)
        handler.postDelayed(tipRunnable, 900)
    }

    // ============ 画质 ============

    private fun cycleScale() {
        scaleIdx = (scaleIdx + 1) % scaleNames.size
        playerView.resizeMode = when (scaleIdx) {
            0 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
        if (this::osdScaleBtn.isInitialized) {
            osdScaleBtn.text = "比例 ${scaleNames[scaleIdx]}"
        }
        showTip("画面比例：${scaleNames[scaleIdx]}")
    }

    private fun cycleQuality() {
        qualityIdx = (qualityIdx + 1) % qualityNames.size
        // 用户手动选了画质，就别再让自动降档插手
        autoDowngraded = true
        rebufferCount = 0
        applyQuality()
        // 持久化：0=自动，>0 存高度上限
        configStore.setQPref(
            when (qualityIdx) {
                1 -> 1080
                2 -> 720
                3 -> 480
                else -> 0
            }
        )
        if (this::osdQualityBtn.isInitialized) {
            osdQualityBtn.text = "画质 ${qualityNames[qualityIdx]}"
        }
        showTip("画质限制：${qualityNames[qualityIdx]}")
        showOsd()
    }

    /**
     * 通过限制最大分辨率来「降档」。
     * HLS 多码率流本来就由 ExoPlayer 自适应，这里给用户一个上限：
     * 电视盒子 4K 解码吃力时，锁 1080P/720P 比让它自己乱切稳。
     */
    private fun applyQuality() {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        when (qualityIdx) {
            1 -> b.setMaxVideoSize(1920, 1080).setMaxVideoBitrate(Int.MAX_VALUE)
            2 -> b.setMaxVideoSize(1280, 720).setMaxVideoBitrate(Int.MAX_VALUE)
            3 -> b.setMaxVideoSize(854, 480).setMaxVideoBitrate(Int.MAX_VALUE)
            else -> b.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE).setMaxVideoBitrate(Int.MAX_VALUE)
        }
        p.trackSelectionParameters = b.build()
    }

    /**
     * 卡顿计数。20 秒内连续缓冲才算「网络不稳」，
     * 隔很久偶发一次是源站抖动，不该降画质。
     */
    private fun noteRebuffer() {
        val now = android.os.SystemClock.elapsedRealtime()
        rebufferCount = if (now - lastBufferAt < 20_000) rebufferCount + 1 else 1
        lastBufferAt = now

        if (!autoDowngraded && qualityIdx == 0 && rebufferCount >= 3) {
            autoDowngraded = true
            qualityIdx = 2
            applyQuality()
            if (this::osdQualityBtn.isInitialized) {
                osdQualityBtn.text = "画质 ${qualityNames[qualityIdx]}"
            }
            showTip("网络不稳，已自动降到 720P（可在画质里改回）")
        }
    }

    private fun startPlayback() {
        isResolving = true
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
        osdView.visibility = View.GONE

        val episode = allEpisodes.getOrNull(currentEpisodeIndex) ?: return
        osdTitle.text = if (vodName == episode.name || episode.name.isEmpty()) {
            vodName
        } else {
            "$vodName ${episode.name}"
        }

        lifecycleScope.launch {
            try {
                val spiderSite = configStore.getConfig()?.sites?.find { it.key == siteKey }
                if (spiderSite != null && spiderSite.type == 3) {
                    // 爬虫站源：episode.url 其实是剧集 id，要交给 jar 换真实地址
                    val real = SpiderCms.player(this@PlayerActivity, spiderSite, lineName, episode.url)
                    if (real.isEmpty()) {
                        throw Exception(SpiderCms.errorOf(siteKey).ifEmpty { "爬虫没有返回播放地址" })
                    }
                    resolvedUrl = real
                    resolvedVia = "爬虫"
                } else if (isDirectLine) {
                    // 直连模式：地址本身就是流
                    if (isLiveMode && episode.urls.isNotEmpty()) {
                        // 直播：同台的多条线路全备好，先播主线路，挂了再换线
                        liveCandidates = episode.urls
                        liveLineIdx = 0
                        resolvedUrl = episode.urls[0]
                    } else {
                        liveCandidates = emptyList()
                        liveLineIdx = 0
                        resolvedUrl = episode.url
                    }
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

        // 数据源 + 失败重试策略：
        // 弱网/源站抖动时先自己重试几次，别一有风浪就弹错误页。
        // WebDAV 等需要 Basic 认证的源，把 Authorization 挂在数据源上，
        // 让播放器带着头去请求，而不是先下载再播。
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            // 部分 NAS / 反代会按 User-Agent 拦截：默认的 ExoPlayerLib/x.x 会被当成
            // 爬虫，返回一张 HTML 错误页（表现为 Source error）。用浏览器的 UA 最稳。
            .setUserAgent(
                "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        // 请求头：鉴权头（WebDAV Basic）之外，解析器返回的自定义头（Referer/UA/Cookie）
        // 也必须一并挂到数据源，否则某些解析接口要求的头没带上、地址会被当成无效。
        val headers = mutableMapOf<String, String>()
        if (authHeader.isNotEmpty()) headers["Authorization"] = authHeader
        if (resolvedHeaders.isNotEmpty()) headers.putAll(resolvedHeaders)
        if (headers.isNotEmpty()) {
            httpFactory.setDefaultRequestProperties(headers)
        }

        val mediaFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(
                androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3)
            )

        // 缓冲调大一些：电视盒子网络抖动比手机明显，宁可多缓几秒也别卡
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // 最小缓冲
                120_000,  // 最大缓冲
                1_500,    // 起播缓冲
                3_000     // 卡顿后重缓冲
            )
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaFactory)
            .setLoadControl(loadControl)
            .build()
            .also { p ->
            playerView.player = p

            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            isPrepared = true
                            isBuffering = false
                            // 真的播起来了才重置自动换线标记，否则失败→换线→失败会无限循环
                            autoSwitched = false
                            loadingView.visibility = View.GONE
                            // 续播定位：优先用调用方传入的位置，其次读本地历史
                            // 直播没有“看到哪”的概念，跳过
                            val target = when {
                                isLiveMode -> 0L
                                pendingSeek > 0 -> pendingSeek
                                else -> {
                                    val history = historyStore.get(vodId, siteKey)
                                    if (history != null && history.episodeIndex == currentEpisodeIndex) {
                                        history.position
                                    } else 0L
                                }
                            }
                            if (target > 0) {
                                p.seekTo(target)
                                pendingSeek = 0L
                            }
                            p.play()
                            isPlaying = true
                            osdPlayPauseBtn.text = "暂停"
                            showOsd()

                            // 片头跳过：起播后若配了片头秒数且还没跳过，浮现「跳过片头」倒计时
                            maybeShowIntroSkip(p.duration)
                        }
                        Player.STATE_BUFFERING -> {
                            isBuffering = true
                            // 这是「播放中的卡顿」，不是起播缓冲：起播时 isPrepared 还是 false
                            if (isPrepared) noteRebuffer()
                            loadingView.visibility = if (isPrepared) View.GONE else View.VISIBLE
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
                    // 直播：先试同台备用线路，试完再换下一台；点播失败不自动换集（只弹错误页+重试）
                    if (isLiveMode) {
                        if (liveLineIdx + 1 < liveCandidates.size) {
                            liveLineIdx++
                            resolvedUrl = liveCandidates[liveLineIdx]
                            showTip("线路异常，切备用线路 ${liveLineIdx + 1}/${liveCandidates.size}")
                            initPlayer()
                            return
                        }
                        val hasNext = currentEpisodeIndex + 1 < allEpisodes.size
                        if (hasNext && !autoSwitched) {
                            autoSwitched = true
                            showTip("当前台异常，切换下一台")
                            switchEpisode(1)
                            return
                        }
                    }
                    // 服务器返回的内容不是视频（不是 4xx/5xx，而是 HTML/文本），
                    // 这种最常见于地址不对、被 UA 拦截、或需要登录态 —— 给一句能指导用户的话
                    val hint = when {
                        error.cause is androidx.media3.exoplayer.source.UnrecognizedInputFormatException ->
                            "服务器返回的内容不是视频（可能被拦截或地址已失效）"
                        else -> error.message
                    }
                    showError("播放失败：$hint")
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

                        // 片尾跳过：靠近结尾时浮现「跳过片尾 / 下一集」倒计时
                        maybeShowOutroSkip(pos, dur)
                    }
                }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    // ============ 片头 / 片尾跳过 ============

    private fun maybeShowIntroSkip(dur: Long) {
        if (introSkipSec <= 0 || introDone || isLiveMode) return
        // 片太短（剩余不足 30 秒）就不跳，别把正片跳没了
        if (dur > 0 && introSkipSec * 1000L >= dur - 30_000L) return
        showSkipButton("跳过片头") {
            hideSkipButton()
            introDone = true
            player?.seekTo(introSkipSec * 1000L)
            showTip("已跳过片头 ${fmtSec(introSkipSec)}")
        }
    }

    private fun maybeShowOutroSkip(pos: Long, dur: Long) {
        if (outroSkipSec <= 0 || outroFired || isLiveMode) return
        if (pos >= dur - outroSkipSec * 1000L) {
            outroFired = true
            val hasNext = currentEpisodeIndex + 1 < allEpisodes.size
            showSkipButton(if (hasNext) "下一集" else "跳过片尾") {
                hideSkipButton()
                if (hasNext) switchEpisode(1) else player?.seekTo(dur)
            }
        }
    }

    private fun showSkipButton(label: String, action: () -> Unit) {
        skipAction = action
        skipCountdown = 5
        skipBtn.text = "$label $skipCountdown"
        skipBtn.visibility = View.VISIBLE
        handler.removeCallbacks(skipRunnable)
        // 倒计时每秒刷新，归零自动跳过
        handler.post(object : Runnable {
            override fun run() {
                skipCountdown--
                if (skipCountdown <= 0) {
                    hideSkipButton()
                    skipAction?.invoke()
                    skipAction = null
                } else if (skipBtn.visibility == View.VISIBLE) {
                    skipBtn.text = "$label $skipCountdown"
                    handler.postDelayed(this, 1000)
                }
            }
        })
    }

    private fun hideSkipButton() {
        handler.removeCallbacks(skipRunnable)
        skipBtn.visibility = View.GONE
    }

    private fun fmtSec(sec: Int): String {
        return if (sec >= 60) "${sec / 60} 分 ${sec % 60} 秒" else "$sec 秒"
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
        // 直播不写观看历史：否则「继续观看」会被一堆频道占满；直连/网盘同理
        if (isLiveMode || skipHistory) return
        player?.let { p ->
            if (isPrepared && p.currentPosition > 0) {
                historyStore.addOrUpdate(History(
                    vodId = vodId,
                    siteKey = siteKey,
                    vodName = vodName,
                    vodPic = vodPic,
                    lineIndex = lineIndex,
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
