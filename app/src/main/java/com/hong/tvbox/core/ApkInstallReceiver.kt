package com.hong.tvbox.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

/**
 * PackageInstaller 会话安装结果接收器。
 *
 * 部分电视盒子固件（尤其小米盒子 Android 6）把「应用安装器」的
 * `ACTION_VIEW + application/vnd.android.package-archive` intent-filter 精简掉了，
 * 于是传统 Intent 安装会抛 ActivityNotFoundException → 应用闪退。
 *
 * [ApkInstaller] 的最后一级兜底改走系统 `PackageInstaller` 会话式安装：
 * 由系统进程直接解析并安装 APK，不经过任何第三方安装器界面，
 * 因此不存在「找不到 Activity」的问题。
 *
 * 系统会通过这里回传最终结果，我们只做日志 + 失败提示。
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 系统要求用户确认：取出它给的确认 Intent 拉起即可
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    try {
                        if (context !is android.app.Activity) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.e(TAG, "拉起安装确认页失败", e)
                    }
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d(TAG, "PackageInstaller 安装成功")
            }

            else -> {
                Log.e(TAG, "PackageInstaller 安装失败 status=$status message=$message")
                try {
                    Toast.makeText(
                        context,
                        "系统安装失败（$status）：$message",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "TVBoxInstall"
    }
}
