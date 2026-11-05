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
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 3
        }
        subGroup.addView(subscribeInput)
        subscribeBtn = Button(this).apply {
            text = "导入订阅"
            setOnClickListener { importSubscription() }
        }
        subGroup.addView(subscribeBtn)
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
            setBackgroundColor(Color.TRANSPARENT)
            maxLines = 3
        }
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

        val clearHistBtn = Button(this).apply {
            text = "清空观看历史"
            setOnClickListener {
                historyStore.clear()
                Toast.makeText(this@SettingsActivity, "已清空观看历史", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearHistBtn)

        scroll.addView(root)
        setContentView(scroll)
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
                CmsClient.clearCache()

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
                CmsClient.clearCache()

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
                    isChecked = enabledKeys.isEmpty() || enabledKeys.contains(site.key)
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
        Toast.makeText(this, "已启用最快 ${best5.size} 个站源", Toast.LENGTH_SHORT).show()
    }

    private fun enableAll() {
        configStore.saveEnabledSites(emptySet())
        Toast.makeText(this, "已恢复全部启用", Toast.LENGTH_SHORT).show()
    }

    // ========== UI 工具 ==========

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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

}

// 扩展 ConfigStore，支持完整的配置加载
fun ConfigStore.loadConfig(json: String): Int {
    saveConfigJson(json)
    return getConfig()?.sites?.size ?: 0
}
