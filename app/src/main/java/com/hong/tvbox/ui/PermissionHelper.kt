package com.hong.tvbox.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

/**
 * Android 6+ 运行时权限助手
 *
 * Android 6 (API 23) 起敏感权限不再安装时一次性授予，需要动态申请。
 * 本项目涉及的敏感权限只有存储权限（下载 APK 到 Downloads 目录用）。
 */
object PermissionHelper {

    const val REQUEST_STORAGE_CODE = 1001

    /**
     * 检查是否拥有存储权限
     * @return true 表示可以读写外部存储
     */
    fun hasStoragePermission(context: Context): Boolean {
        // Android 6 以下：安装时已授予，无需检查
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true

        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 申请存储权限
     */
    fun requestStoragePermission(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            REQUEST_STORAGE_CODE
        )
    }

    /**
     * 在 onRequestPermissionsResult 中调用，判断存储权限是否已获得
     */
    fun isStorageGranted(
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != REQUEST_STORAGE_CODE) return false
        return grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
    }
}
