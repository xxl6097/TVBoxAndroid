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
import com.bumptech.glide.Glide
import com.hong.tvbox.core.Favorite
import com.hong.tvbox.core.Vod
import com.hong.tvbox.data.ConfigStore
import com.hong.tvbox.data.FavoriteStore

/**
 * 收藏页 - 对照鸿蒙 CollectPage.ets
 * 按类型自动分组（全部/电影/剧集/动漫/综艺/其他），6 列海报网格
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var favoriteStore: FavoriteStore
    private lateinit var configStore: ConfigStore
    private lateinit var tabRow: LinearLayout
    private lateinit var gridLayout: GridLayout
    private lateinit var statusView: TextView

    private val tabs = listOf("全部", "电影", "剧集", "动漫", "综艺", "其他")
    private var currentTab = "全部"

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, FavoritesActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favoriteStore = FavoriteStore(this)
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
            setBackgroundColor(TvUi.BG)
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(40), dp(40)))

        val title = TextView(this).apply {
            text = "收藏"
            textSize = 22f
            setTextColor(Color.WHITE)
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val clearBtn = TextView(this).apply {
            text = "清空"
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                favoriteStore.clear()
                render()
                Toast.makeText(this@FavoritesActivity, "已清空收藏", Toast.LENGTH_SHORT).show()
            }
        }
        topBar.addView(clearBtn)
        root.addView(topBar)

        // 分类 Tab 行
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#80FFFFFF"))
            setPadding(0, 0, 0, dp(6))
        }
        root.addView(statusView)

        val scroll = ScrollView(this)
        gridLayout = GridLayout(this).apply { columnCount = 6 }
        scroll.addView(gridLayout)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    /** 从片名/标签推断内容类型 */
    private fun inferType(fav: Favorite): String {
        val cls = fav.vodClass.lowercase()
        val name = fav.vodName.lowercase()
        return when {
            listOf("电影", "movie", "film").any { cls.contains(it) || name.contains(it) } -> "电影"
            listOf("动漫", "动画", "anime", "cartoon").any { cls.contains(it) || name.contains(it) } -> "动漫"
            listOf("综艺", "variety", "真人秀").any { cls.contains(it) || name.contains(it) } -> "综艺"
            listOf("连续剧", "电视剧", "剧集", "series", "drama", "tv").any { cls.contains(it) || name.contains(it) } -> "剧集"
            else -> "其他"
        }
    }

    private fun buildTabRow(all: List<Favorite>) {
        tabRow.removeAllViews()
        // 统计每分组数量，只显示有内容的 tab
        val counts = tabs.associateWith { tab ->
            if (tab == "全部") all.size
            else all.count { inferType(it) == tab }
        }.filter { it.value > 0 || it.key == "全部" }

        counts.forEach { (tab, count) ->
            val chip = TextView(this).apply {
                text = if (count > 0) "$tab $count" else tab
                textSize = 15f
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val isActive = tab == currentTab
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#B3FFFFFF"))
                setBackgroundColor(if (isActive) TvUi.CARD_HI else Color.parseColor("#1C1B1F"))
                setOnClickListener {
                    currentTab = tab
                    render()
                }
                onTvFocus(scale = 1f)
            }
            tabRow.addView(chip, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
            ).also { it.marginEnd = dp(8) })
        }
    }

    private fun render() {
        val all = favoriteStore.getAll()
        buildTabRow(all)
        gridLayout.removeAllViews()

        if (all.isEmpty()) {
            statusView.text = "还没有收藏，在详情页点「☆ 收藏」即可加入"
            return
        }

        val list = if (currentTab == "全部") all
                   else all.filter { inferType(it) == currentTab }

        statusView.text = if (currentTab == "全部") "共 ${all.size} 部"
                          else "$currentTab  ${list.size} 部"

        val cellW = (resources.displayMetrics.widthPixels - dp(48)) / 6
        list.forEachIndexed { pos, fav ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }

            val imgBox = android.widget.FrameLayout(this)
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#33000000"))
            }
            imgBox.addView(img, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(196)
            ))
            if (fav.vodPic.isNotEmpty()) {
                Glide.with(this).load(fav.vodPic).centerCrop().into(img)
            }

            // 右上角删除
            val del = TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#B3000000"))
                setOnClickListener {
                    favoriteStore.remove(fav.vodId, fav.siteKey)
                    render()
                    Toast.makeText(this@FavoritesActivity, "已取消收藏", Toast.LENGTH_SHORT).show()
                }
            }
            imgBox.addView(del, android.widget.FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))

            card.addView(imgBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(196)
            ))

            val name = TextView(this).apply {
                text = fav.vodName
                textSize = 13f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(name)

            val rowSpec = GridLayout.spec(pos / 6, 1)
            val colSpec = GridLayout.spec(pos % 6, 1)
            card.layoutParams = GridLayout.LayoutParams(rowSpec, colSpec).also {
                it.width = cellW - dp(8)
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                it.setMargins(dp(4), dp(4), dp(4), dp(8))
            }

            card.setOnClickListener {
                val siteName = configStore.getConfig()?.sites?.find { it.key == fav.siteKey }?.name.orEmpty()
                DetailActivity.start(this, Vod(
                    id = fav.vodId,
                    name = fav.vodName,
                    pic = fav.vodPic,
                    siteKey = fav.siteKey,
                    siteName = siteName
                ))
            }
            card.onTvFocus(
                normalColor = Color.TRANSPARENT,
                focusColor = TvUi.CARD_HI,
                scale = 1.04f
            )
            gridLayout.addView(card)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}

