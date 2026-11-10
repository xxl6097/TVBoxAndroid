# Android 6 (API 23) 适配方案

## 1. 目前状态

| 功能 | 状态 | 说明 |
|------|------|------|
| minSdk | ✅ 21 | Android 5.0+，兼容 Android 6 |
| 网络配置 | ✅ 完成 | network_security_config 已配置，允许 HTTP |
| Cleartext | ✅ 完成 | android:usesCleartextTraffic="true" |
| HttpClient | ✅ 完成 | 使用 OkHttp + Dispatchers.IO 避免主线程网络 |
| 权限宣言 | ⚠️ 部分 | 声明了权限但未动态申请 |

## 2. Android 6 主要变化

### 2.1 运行时权限（Runtime Permissions）
- **Android 6 之前**：所有权限在安装时申请
- **Android 6 及以后**：敏感权限需要在运行时动态申请
- **影响**：存储、位置、摄像头、麦克风等

**当前项目涉及的敏感权限：**
```
- WRITE_EXTERNAL_STORAGE（下载 APK）
- READ_EXTERNAL_STORAGE（读取文件）
```

### 2.2 Doze 模式（低功耗模式）
- 设备未接电、屏幕关闭、未移动 → 进入 Doze
- Doze 模式下后台任务严重受限
- **影响**：直播订阅、定时更新等后台任务

### 2.3 网络限制
- **Android 9+** 默认禁止 Cleartext（已配置允许）
- **Android 6** 引入的 Network Security Policy

### 2.4 FileProvider（Android 7 强制要求）
- ✅ 已配置，文件共享使用 FileProvider

## 3. 适配方案

### 3.1 运行时权限申请

**新增权限助手类**：`PermissionHelper.kt`

```kotlin
package com.hong.tvbox.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity

object PermissionHelper {
    // 权限请求码
    const val REQUEST_STORAGE_CODE = 1001
    
    /**
     * 检查存储权限（READ/WRITE_EXTERNAL_STORAGE）
     * @return true 表示已获得权限
     */
    fun hasStoragePermission(context: Context): Boolean {
        // Android 6+ 需要动态检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        // Android 5 及以下，安装时已申请
        return true
    }
    
    /**
     * 申请存储权限
     */
    fun requestStoragePermission(activity: AppCompatActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                REQUEST_STORAGE_CODE
            )
        }
    }
    
    /**
     * 在 onRequestPermissionsResult 中调用此方法检查申请结果
     */
    fun isStoragePermissionGranted(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        if (requestCode != REQUEST_STORAGE_CODE) return false
        return grantResults.isNotEmpty() && 
               grantResults[0] == PackageManager.PERMISSION_GRANTED
    }
}
```

### 3.2 在关键页面集成权限申请

**在 MainActivity.kt**：
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 首次启动时检查权限
    if (!PermissionHelper.hasStoragePermission(this)) {
        PermissionHelper.requestStoragePermission(this)
    }
}

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (PermissionHelper.isStoragePermissionGranted(requestCode, permissions, grantResults)) {
        // 权限已获得，继续正常操作
    } else {
        // 权限被拒绝，弹出提示或禁用相关功能
        Toast.makeText(this, "需要存储权限才能下载APK", Toast.LENGTH_SHORT).show()
    }
}
```

### 3.3 UpdateManager 权限检查

在 `UpdateManager.kt` 中下载 APK 前先检查权限：

```kotlin
suspend fun checkAndDownloadApk(context: Context, url: String): String {
    // 1. 检查权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        if (!PermissionHelper.hasStoragePermission(context)) {
            throw Exception("没有存储权限，无法下载 APK")
        }
    }
    
    // 2. 继续下载
    return downloadWithProgress(url)
}
```

### 3.4 UI 兼容性检查

**需要检查的 API：**
- `View.generateViewId()` - ✅ API 17+（Android 4.2+）
- `Drawable.mutate()` - ✅ API 3+
- `DateFormat`、`SimpleDateFormat` - ✅ 兼容
- PictureInPicture - ❌ API 26+ 才支持，需要条件检查

**修复 PiP（PlayerActivity.kt）**：

```kotlin
private fun enterPictureInPicture() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            enterPictureInPictureMode()
        } catch (e: Exception) {
            Logger.e("PiP 失败", e)
        }
    }
}

override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPlaying) {
        enterPictureInPicture()
    }
}
```

## 4. 测试清单

- [ ] Android 6.0.1（API 23）模拟器完整流程测试
- [ ] 首次启动权限申请弹窗正常显示
- [ ] 拒绝权限后应用降级处理（不崩溃）
- [ ] HTTP 请求正常（Cleartext 通过）
- [ ] 下载 APK 成功
- [ ] 应用内升级流程完整
- [ ] 播放器正常运行（PiP 功能在 Android 6 上隐藏）

## 5. 兼容性矩阵

| 功能 | API 21-22 | API 23-25 | API 26+ |
|------|-----------|-----------|---------|
| HTTP | ✅ | ✅ | ✅ |
| 存储权限 | 自动授予 | **动态申请** | **动态申请** |
| Cleartext | ✅ | ✅ | ⚠️ Policy |
| PiP | ❌ | ❌ | ✅ |
| Notification | ✅ | ✅ | 需 Channel |
| FileProvider | ✅ | ✅ | ✅ |

## 6. 已知限制

- **PiP** 仅在 Android 8.0+ 可用，Android 6-7 上隐藏
- **Doze 模式** 影响后台任务，但对 UI 应用无关紧要
- **HTTP** 在 targetSdk 28+ 需 network_security_config 支持（已配置）
