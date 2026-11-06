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
import com.hong.tvbox.data.FavoriteStore

/**
 * 收藏页 - 对照鸿蒙 CollectPage.ets
 * 6 列海报网格，卡片右上角删除
 */
class FavoritesActivity : AppCompatActivity() {

    private lateinit var favoriteStore: FavoriteStore
    private lateinit var gridLayout: GridLayout
    private lateinit var statusView: TextView

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, FavoritesActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        favoriteStore = FavoriteStore(this)
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
            text = "收藏"
            textSize = 20f
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

        statusView = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#B3FFFFFF"))
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(statusView)

        val scroll = ScrollView(this)
        gridLayout = GridLayout(this).apply { columnCount = 6 }
        scroll.addView(gridLayout)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    private fun render() {
        val list = favoriteStore.getAll()
        gridLayout.removeAllViews()

        if (list.isEmpty()) {
            statusView.text = "还没有收藏，在详情页点「☆ 收藏」即可加入"
            return
        }
        statusView.text = "共 ${list.size} 部"

        list.forEach { fav ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }

            val imgBox = FrameLayout(this)
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#33000000"))
            }
            imgBox.addView(img, FrameLayout.LayoutParams(
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
            imgBox.addView(del, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END))

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

            val spec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f)
            card.layoutParams = GridLayout.LayoutParams(spec, spec).also {
                it.width = 0
                it.height = ViewGroup.LayoutParams.WRAP_CONTENT
                it.setMargins(dp(4), dp(4), dp(4), dp(8))
            }

            card.setOnClickListener {
                DetailActivity.start(this, Vod(
                    id = fav.vodId,
                    name = fav.vodName,
                    pic = fav.vodPic,
                    siteKey = fav.siteKey
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
