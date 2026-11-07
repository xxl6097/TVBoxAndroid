package com.hong.tvbox.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.hong.tvbox.Feedback
import com.hong.tvbox.core.CmsClient
import com.hong.tvbox.core.HttpClient
import com.hong.tvbox.core.Site
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.HistoryStore
import kotlinx.coroutines.launch

/**
 * 设置页 - 移植自 SettingPage.ets
 * 实现：订阅导入、配置地址导入、站源管理（开关+测速排行）、历史清空
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var historyStore: HistoryStore

    private lateinit var subscribeInput: EditText
    private lateinit var subscribeBtn: Button
    private lateinit var subscribeStatus: TextView
    private lateinit var configInput: EditText
    private lateinit var configBtn: Button
    private lateinit var configStatus: TextView
    private lateinit var liveInput: EditText
    private lateinit var liveStatus: TextView
    private lateinit var crawlBtn: Button
    private lateinit var crawlSummary: TextView

    private var qrServer: com.hong.tvbox.core.QrServer? = null
    private var qrDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var sitesContainer: LinearLayout
    private lateinit var speedBtn: Button
    private lateinit var enableBestBtn: Button
    private lateinit var enableAllBtn: Button

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configStore = ConfigStore(this)
        historyStore = HistoryStore(this)
        buildUI()
        refreshSitesUI()
    }

    private fun buildUI() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.parseColor("#141218"))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        // 顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        val backBtn = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        topBar.addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titleView = TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        topBar.addView(titleView)
        root.addView(topBar)

        // ===== 订阅 =====
        root.addView(sectionTitle("订阅（点播 + 直播同时更新）"))

        val subGroup = cardGroup()
        subscribeInput = EditText(this).apply {
            hint = "粘贴订阅链接或 TVBox JSON"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            maxLines = 3
        }
        subscribeInput.tvInputStyle()
        subGroup.addView(subscribeInput)

        val subBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        subscribeBtn = Button(this).apply {
            text = "导入订阅"
            setOnClickListener { importSubscription() }
        }
        subBtnRow.addView(subscribeBtn)
        subBtnRow.addView(Button(this).apply {
            text = "扫码录入"
            setOnClickListener { showQrEntry() }
        })
        subGroup.addView(subBtnRow)
        subscribeStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(8), 0, 0)
        }
        // 显示当前订阅 URL
        val savedUrl = configStore.getSubscribeUrl()
        if (!savedUrl.isNullOrEmpty()) {
            subscribeStatus.text = "当前订阅：$savedUrl"
            subscribeStatus.visibility = View.VISIBLE
        }
        subGroup.addView(subscribeStatus)
        root.addView(subGroup)

        // ===== 配置地址 =====
        root.addView(sectionTitle("配置地址（仅点播）"))

        val cfgGroup = cardGroup()
        configInput = EditText(this).apply {
            hint = "粘贴配置 JSON 或 URL"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            maxLines = 3
        }
        configInput.tvInputStyle()
        cfgGroup.addView(configInput)
        configBtn = Button(this).apply {
            text = "导入配置"
            setOnClickListener { importConfig() }
        }
        cfgGroup.addView(configBtn)
        configStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(8), 0, 0)
        }
        val savedConfig = configStore.getConfig()
        if (savedConfig != null) {
            configStatus.text = "已导入：${savedConfig.sites.size} 个站源，${savedConfig.parses.size} 个解析器"
            configStatus.visibility = View.VISIBLE
        }
        cfgGroup.addView(configStatus)
        root.addView(cfgGroup)

        // ===== 直播源 =====
        root.addView(sectionTitle("直播源（m3u / txt）"))

        val liveGroup = cardGroup()
        liveInput = EditText(this).apply {
            hint = "粘贴直播源地址，如 https://.../iptv.m3u"
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#80FFFFFF"))
            maxLines = 3
            setText(configStore.getLiveSourceUrl() ?: "")
        }
        liveInput.tvInputStyle()
        liveGroup.addView(liveInput)

        val liveBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        liveBtnRow.addView(Button(this).apply {
            text = "保存并启用"
            setOnClickListener { saveLiveSource() }
        })
        liveBtnRow.addView(Button(this).apply {
            text = "从订阅恢复"
            setOnClickListener { applyLivesFromSubscription() }
        })
        liveGroup.addView(liveBtnRow)

        liveStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(8), 0, 0)
        }
        liveGroup.addView(liveStatus)
        root.addView(liveGroup)

        // ===== 站源管理 =====
        root.addView(sectionTitle("站源管理"))

        val speedRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        speedBtn = Button(this).apply {
            text = "测速排行"
            setOnClickListener { startSpeedTest() }
        }
        speedRow.addView(speedBtn)
        enableBestBtn = Button(this).apply {
            text = "启用最快5个"
            visibility = View.GONE
            setOnClickListener { enableBest5() }
        }
        speedRow.addView(enableBestBtn)
        enableAllBtn = Button(this).apply {
            text = "全部启用"
            visibility = View.GONE
            setOnClickListener { enableAll() }
        }
        speedRow.addView(enableAllBtn)
        root.addView(speedRow)

        sitesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(sitesContainer)

        // ===== 历史记录 =====
        root.addView(sectionTitle("其他"))

        // 定时抓取：首页每 6 小时自动跑一轮，这里可以手动触发一次
        crawlBtn = Button(this).apply {
            text = "立即抓取最新（每 6 小时自动）"
            setOnClickListener { runCrawlNow() }
        }
        root.addView(crawlBtn)

        crawlSummary = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, dp(6), 0, dp(10))
        }
        val summary = configStore.getCrawlSummary()
        crawlSummary.text = if (summary.isNotEmpty()) "上轮：$summary" else "还没有抓取记录"
        root.addView(crawlSummary)

        val clearHistBtn = Button(this).apply {
            text = "清空观看历史"
            setOnClickListener {
                historyStore.clear()
                Toast.makeText(this@SettingsActivity, "已清空观看历史", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearHistBtn)

        // 点击反馈：声音 / 震动（默认都开，切开关后即时生效）
        root.addView(switchRow("点击声音", configStore.tapSound()) { on ->
            configStore.setTapSound(on)
            Feedback.refresh(this)
        })
        root.addView(switchRow("点击震动", configStore.tapVibrate()) { on ->
            configStore.setTapVibrate(on)
            Feedback.refresh(this)
        })

        // 隐私政策入口
        root.addView(TextView(this).apply {
            text = "隐私政策"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(8))
            setOnClickListener { showPrivacyDialog() }
        })

        // 上次崩溃日志（有才显示，方便盒子上排障）
        val crash = com.hong.tvbox.TvBoxApp.readCrash(this)
        if (crash.isNotEmpty()) {
            root.addView(Button(this).apply {
                text = "查看上次崩溃日志"
                setOnClickListener { showCrashLog(crash) }
            })
        }

        // 版本信息页脚
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "v${info.versionName}"
        } catch (e: Exception) {
            "v1.0.0"
        }
        root.addView(TextView(this).apply {
            text = "鸿蒙影视 Android TV 版 $version"
            textSize = 13f
            setTextColor(Color.parseColor("#66FFFFFF"))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(8))
        })

        scroll.addView(root)
        setContentView(scroll)
    }

    /** 崩溃日志弹窗：可滚动查看，附清除按钮 */
    private fun showCrashLog(log: String) {
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = log
            textSize = 12f
            setTextColor(Color.parseColor("#DDDDDD"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(tv)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("上次崩溃日志")
            .setView(scroll)
            .setPositiveButton("关闭") { _, _ -> }
            .setNegativeButton("清除") { _, _ ->
                com.hong.tvbox.TvBoxApp.clearCrash(this)
                Toast.makeText(this, "已清除", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 手机扫码录入：起一个局域网小服务 + 显示二维码，离开对话框即停止服务 */
    private fun showQrEntry() {
        val server = qrServer ?: com.hong.tvbox.core.QrServer(8788) { form ->
            runOnUiThread { applyQrForm(form) }
        }.also { qrServer = it }

        if (!server.start()) {
            Toast.makeText(this, "端口 8788 被占用，无法启动扫码服务", Toast.LENGTH_LONG).show()
            return
        }

        val url = server.url()
        val qr = com.hong.tvbox.core.QrCode.encode(url, 520)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }

        val img = ImageView(this).apply {
            setImageBitmap(qr)
            if (qr == null) {
                setBackgroundColor(Color.WHITE)
            }
        }
        box.addView(img, LinearLayout.LayoutParams(dp(260), dp(260)))

        box.addView(TextView(this).apply {
            text = url
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(4))
        })

        box.addView(TextView(this).apply {
            text = "手机与电视连同一 Wi-Fi，扫码后填写订阅/直播源/配置，提交即生效。\n关闭本窗口后服务立即停止。"
            textSize = 13f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        qrDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("扫码录入")
            .setView(box)
            .setPositiveButton("关闭") { _, _ -> }
            .setOnDismissListener { server.stop() }
            .show()
    }

    /** 手机提交的表单：分别落到订阅 / 直播源 / 点播配置 */
    private fun applyQrForm(form: Map<String, String>) {
        val subscribe = form["subscribe"].orEmpty()
        val live = form["live"].orEmpty()
        val config = form["config"].orEmpty()

        if (subscribe.isNotEmpty()) {
            subscribeInput.setText(subscribe)
            importSubscription()
        }
        if (live.isNotEmpty()) {
            liveInput.setText(live)
            saveLiveSource()
        }
        if (config.isNotEmpty()) {
            configInput.setText(config)
            importConfig()
        }
        if (subscribe.isEmpty() && live.isEmpty() && config.isEmpty()) {
            Toast.makeText(this, "手机端没有填写内容", Toast.LENGTH_SHORT).show()
        }
    }

    /** 手动跑一轮定时抓取（首页每 6 小时也会自动跑） */
    private fun runCrawlNow() {
        val sites = configStore.getEnabledSiteList()
        if (sites.isEmpty()) {
            Toast.makeText(this, "没有可用站源", Toast.LENGTH_SHORT).show()
            return
        }

        crawlBtn.isEnabled = false
        crawlBtn.text = "抓取中…"
        crawlSummary.text = "正在抓取 ${sites.size} 个站源，约 10~30 秒"

        lifecycleScope.launch {
            try {
                val r = com.hong.tvbox.core.Crawler.run(sites)
                configStore.saveFeed(r.list)
                configStore.setLastCrawlAt(r.at)
                val summary = "抓取 ${r.sites} 个站源，获得 ${r.items} 条" +
                        if (r.dead.isEmpty()) "" else "，${r.dead.size} 个站源不可用"
                configStore.saveCrawlSummary(summary)
                crawlSummary.text = "上轮：$summary"
                Toast.makeText(this@SettingsActivity, summary, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                crawlSummary.text = "抓取失败：${e.message}"
                Toast.makeText(this@SettingsActivity, "抓取失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                crawlBtn.isEnabled = true
                crawlBtn.text = "立即抓取最新（每 6 小时自动）"
            }
        }
    }

    private fun importSubscription() {
        val input = subscribeInput.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "请输入订阅链接或 JSON", Toast.LENGTH_SHORT).show()
            return
        }

        subscribeBtn.isEnabled = false
        subscribeStatus.text = "导入中…"
        subscribeStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val text = if (input.startsWith("http://") || input.startsWith("https://")) {
                    configStore.saveSubscribeUrl(input)
                    HttpClient.text(input, timeoutMs = 20000)
                } else {
                    input
                }

                val siteCount = configStore.loadConfig(text)
                // 换了配置，旧站源 key 已经不适用，开关重置为「全部启用」
                configStore.resetEnabledSites()
                CmsClient.clearCache()
                absorbLives()

                subscribeStatus.text = "导入成功：$siteCount 个站源"
                Toast.makeText(this@SettingsActivity, "订阅导入成功，共 $siteCount 个站源", Toast.LENGTH_SHORT).show()
                refreshSitesUI()

            } catch (e: Exception) {
                subscribeStatus.text = "导入失败：${e.message}"
                Toast.makeText(this@SettingsActivity, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                subscribeBtn.isEnabled = true
            }
        }
    }

    /**
     * 把配置/订阅里的 lives 收进本地，并自动选中第一个。
     * 订阅 = 一份 JSON 同时带点播站源与直播源，填一个链接两样都更新。
     */
    private fun absorbLives() {
        val lives = configStore.getConfig()?.lives ?: return
        val valid = lives.filter {
            it.url.startsWith("http://") || it.url.startsWith("https://")
        }
        if (valid.isEmpty()) return

        configStore.saveSubLives(valid)

        val cur = configStore.getLiveSourceUrl()
        if (cur.isNullOrEmpty() || valid.none { it.url == cur }) {
            configStore.saveLiveSourceUrl(valid.first().url)
        }
        if (this::liveInput.isInitialized) {
            liveInput.setText(configStore.getLiveSourceUrl() ?: "")
        }
        if (this::liveStatus.isInitialized) {
            liveStatus.visibility = View.VISIBLE
            liveStatus.text = "已收录 ${valid.size} 个直播源：${valid.joinToString("、") { it.name }}"
        }
    }

    private fun saveLiveSource() {
        val url = liveInput.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "请输入直播源地址", Toast.LENGTH_SHORT).show()
            return
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            Toast.makeText(this, "直播源必须是 http/https 地址", Toast.LENGTH_SHORT).show()
            return
        }

        configStore.saveLiveSourceUrl(url)
        liveStatus.visibility = View.VISIBLE
        liveStatus.text = "已启用：$url"
        Toast.makeText(this, "直播源已保存", Toast.LENGTH_SHORT).show()
    }

    private fun applyLivesFromSubscription() {
        val lives = configStore.getSubLives()
        if (lives.isEmpty()) {
            liveStatus.visibility = View.VISIBLE
            liveStatus.text = "订阅里没有直播源（导入带 lives 的订阅后会自动收录）"
            return
        }

        val cur = configStore.getLiveSourceUrl()
        if (cur.isNullOrEmpty() || lives.none { it.url == cur }) {
            configStore.saveLiveSourceUrl(lives.first().url)
        }
        liveInput.setText(configStore.getLiveSourceUrl() ?: "")
        liveStatus.visibility = View.VISIBLE
        liveStatus.text = "共 ${lives.size} 个候选：${lives.joinToString("、") { it.name }}"
    }

    private fun importConfig() {
        val input = configInput.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "请输入配置地址或 JSON", Toast.LENGTH_SHORT).show()
            return
        }

        configBtn.isEnabled = false
        configStatus.text = "导入中…"
        configStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val text = if (input.startsWith("http://") || input.startsWith("https://")) {
                    HttpClient.text(input, timeoutMs = 20000)
                } else {
                    input
                }

                val siteCount = configStore.loadConfig(text)
                configStore.resetEnabledSites()
                CmsClient.clearCache()
                absorbLives()

                configStatus.text = "导入成功：$siteCount 个站源"
                Toast.makeText(this@SettingsActivity, "配置导入成功，共 $siteCount 个站源", Toast.LENGTH_SHORT).show()
                refreshSitesUI()

            } catch (e: Exception) {
                configStatus.text = "导入失败：${e.message}"
                Toast.makeText(this@SettingsActivity, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                configBtn.isEnabled = true
            }
        }
    }

    private fun refreshSitesUI() {
        sitesContainer.removeAllViews()
        val sites = configStore.getConfig()?.sites ?: return

        if (sites.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "还没有站源，请先导入配置或订阅"
                textSize = 16f
                setTextColor(Color.parseColor("#80FFFFFF"))
                setPadding(0, dp(12), 0, 0)
            }
            sitesContainer.addView(emptyView)
            return
        }

        val enabledKeys = configStore.getEnabledSites().toMutableSet()
        val customized = configStore.isEnabledCustomized()

        sites.forEach { site ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val nameView = TextView(this).apply {
                text = site.name
                textSize = 16f
                setTextColor(
                    when {
                        site.type == 3 -> Color.parseColor("#80FFFFFF")
                        else -> Color.WHITE
                    }
                )
            }
            row.addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val typeView = TextView(this).apply {
                text = when (site.type) {
                    0 -> "XML"
                    1 -> "JSON"
                    3 -> "爬虫"
                    4 -> "JSON4"
                    else -> "未知"
                }
                textSize = 13f
                setTextColor(Color.parseColor("#80FFFFFF"))
            }
            row.addView(typeView)

            if (site.type != 3) {
                val toggle = Switch(this).apply {
                    // 没自定义过 = 全部启用；自定义过 = 严格看集合
                    isChecked = !customized || enabledKeys.contains(site.key)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) {
                            enabledKeys.add(site.key)
                        } else {
                            enabledKeys.remove(site.key)
                        }
                        configStore.saveEnabledSites(enabledKeys)
                    }
                }
                row.addView(toggle)
            }

            sitesContainer.addView(row)
            sitesContainer.addView(divider())
        }
    }

    // 测速排行
    private val speedResults = mutableListOf<Pair<Site, Long>>()

    private fun startSpeedTest() {
        val sites = configStore.getEnabledSiteList()
        if (sites.isEmpty()) {
            Toast.makeText(this, "没有可用站源", Toast.LENGTH_SHORT).show()
            return
        }

        speedBtn.isEnabled = false
        speedBtn.text = "测速中…"
        speedResults.clear()
        Toast.makeText(this, "开始测速 ${sites.size} 个站源，约 ${sites.size * 2} 秒", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            sites.forEach { site ->
                val t0 = System.currentTimeMillis()
                val ok = try {
                    HttpClient.text(
                        site.api + (if (site.api.contains('?')) "&" else "?") + "ac=list",
                        timeoutMs = 5000
                    )
                    true
                } catch (e: Exception) {
                    false
                }
                val ms = System.currentTimeMillis() - t0
                speedResults.add(Pair(site, if (ok) ms else Long.MAX_VALUE))
            }

            // 按速度排序
            speedResults.sortBy { it.second }

            speedBtn.isEnabled = true
            speedBtn.text = "测速排行"
            enableBestBtn.visibility = View.VISIBLE
            enableAllBtn.visibility = View.VISIBLE

            // 更新 UI 显示测速结果
            refreshSpeedResultsUI()
        }
    }

    private fun refreshSpeedResultsUI() {
        sitesContainer.removeAllViews()
        speedResults.forEachIndexed { index, (site, ms) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val rank = TextView(this).apply {
                text = "${index + 1}."
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            row.addView(rank, LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT))
            val nameView = TextView(this).apply {
                text = site.name
                textSize = 16f
                setTextColor(Color.WHITE)
            }
            row.addView(nameView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val speedView = TextView(this).apply {
                text = if (ms == Long.MAX_VALUE) "不通" else "${ms} ms"
                textSize = 15f
                setTextColor(
                    when {
                        ms == Long.MAX_VALUE -> Color.parseColor("#FFB4AB")
                        ms < 1000 -> Color.parseColor("#00FF88")
                        ms < 3000 -> Color.parseColor("#FFEB3B")
                        else -> Color.parseColor("#FFB4AB")
                    }
                )
            }
            row.addView(speedView)
            sitesContainer.addView(row)
            sitesContainer.addView(divider())
        }
    }

    private fun enableBest5() {
        val best5 = speedResults.take(5)
            .filter { it.second != Long.MAX_VALUE }
            .map { it.first.key }
            .toSet()

        configStore.saveEnabledSites(best5)
        refreshSitesUI()
        Toast.makeText(this, "已启用最快 ${best5.size} 个站源", Toast.LENGTH_SHORT).show()
    }

    private fun enableAll() {
        // 回到「未自定义」状态：所有可用站源自动启用
        configStore.resetEnabledSites()
        refreshSitesUI()
        Toast.makeText(this, "已恢复全部启用", Toast.LENGTH_SHORT).show()
    }

    // ========== UI 工具 ==========

    /** 一行「文字 + 右侧开关」 */
    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isOn -> onChange(isOn) }
        })
        return row
    }

    /** 隐私政策全文弹窗 */
    private fun showPrivacyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("隐私政策")
            .setMessage(
                """
                本应用没有注册、登录、手机号、邮箱等账号体系，也没有自己的服务器。
                你的源地址、收藏、观看历史、播放进度、画质偏好全部只保存在本机，卸载即清除。

                联网只做这些事：请求你自己填写的点播配置地址与直播源地址，读取其中列出的
                站点接口，并按地址播放视频流；点「测速排行」时向这些地址发一次探测请求。
                这些请求由你的设备直接发往第三方，不经过开发者。

                权限只用三项：联网、读取网络状态、震动。不申请位置、通讯录、短信、相机、
                麦克风、相册、设备标识符等任何权限。

                本应用不包含广告与统计 SDK，无推送、无埋点、无崩溃上报。

                本应用不提供、不存储、不分发任何影视内容，仅为播放器与源管理工具。
                内容全部来自你自行填写的第三方接口，请确保来源合法。
                """.trimIndent()
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.parseColor("#8CFFFFFF"))
            setPadding(0, dp(20), 0, dp(8))
        }
    }

    private fun cardGroup(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1C1B1F"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
    }

    private fun divider(): View {
        return View(this).apply {
            setBackgroundColor(Color.parseColor("#1FFFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.marginStart = dp(18) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        qrDialog?.dismiss()
        qrServer?.stop()
        qrServer = null
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

}

// 扩展 ConfigStore，支持完整的配置加载
fun ConfigStore.loadConfig(json: String): Int {
    saveConfigJson(json)
    return getConfig()?.sites?.size ?: 0
}
