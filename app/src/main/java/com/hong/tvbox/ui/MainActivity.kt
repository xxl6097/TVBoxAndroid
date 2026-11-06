package com.hong.tvbox.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import com.hong.tvbox.data.ConfigStore

/**
 * 主界面 - 托管 Leanback BrowseFragment
 *
 * 首启必须先展示隐私政策、用户同意后才允许联网（应用市场硬性要求）。
 * 内容页放在同意之后再挂载，保证未同意时不会发起任何网络请求。
 */
class MainActivity : FragmentActivity() {

    private var restored = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState != null

        val store = ConfigStore(this)
        if (store.isPrivacyAgreed()) {
            showContent()
        } else {
            showPrivacyDialog(store)
        }
    }

    private fun showContent() {
        if (restored) return
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, MainBrowseFragment())
            .commit()
    }

    private fun showPrivacyDialog(store: ConfigStore) {
        AlertDialog.Builder(this)
            .setTitle("隐私政策")
            .setMessage(
                """
                本应用是本地影视索引工具，自身不提供、不存储任何影片内容。

                1. 应用只申请「网络访问」权限，用于读取你自行导入的站源/订阅配置；
                2. 你填写的站源地址、订阅链接、播放记录、收藏均只保存在本机，
                   不会上传到任何服务器；
                3. 点播内容来自第三方站点，其内容与隐私实践由该站点负责；
                4. 你可以随时在系统设置中清除本应用数据以删除全部本地记录。

                点击「同意并继续」表示你已阅读并同意上述说明。
                """.trimIndent()
            )
            .setCancelable(false)
            .setPositiveButton("同意并继续") { _, _ ->
                store.setPrivacyAgreed()
                showContent()
            }
            .setNegativeButton("不同意") { _, _ ->
                finishAffinity()
            }
            .show()
    }
}
