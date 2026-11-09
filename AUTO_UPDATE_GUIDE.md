# 📱 TVBoxAndroid 自动更新功能指南

## 功能概述

基于 GitHub Releases 的应用内自动更新系统，用户无需手动下载 APK，应用内一键检查更新并自动安装。

---

## 功能特性

✅ **自动检测版本**: 对比本地版本与 GitHub 最新 Release  
✅ **语义化版本比较**: 支持 x.y.z 格式，自动识别新旧  
✅ **后台下载**: 使用 Android DownloadManager 后台下载  
✅ **通知提示**: 下载进度和完成通知  
✅ **自动安装**: 下载完成后自动弹出安装界面  
✅ **Release Notes 展示**: 显示更新内容和文件大小  
✅ **网络容错**: 失败时提示用户检查网络

---

## 使用方法

### 用户端

1. 打开应用，进入「设置」页面
2. 点击左侧导航的「关于」
3. 点击「检查更新」
4. 如果有新版本，会显示更新对话框：
   - 版本号对比（当前 vs 最新）
   - 文件大小
   - 更新内容
   - 发布时间
5. 点击「立即更新」开始下载
6. 下载完成后自动弹出安装提示

### 开发者端

1. **发布新版本到 GitHub Releases**
   ```bash
   # 创建标签
   git tag -a v1.2.1 -m "Bug fixes and improvements"
   git push origin v1.2.1
   
   # 在 GitHub Releases 页面上传 APK
   ```

2. **APK 命名规范**（自动识别）
   - 优先识别: `*-release.apk` 或 `*release*.apk`
   - 备选: 任意 `.apk` 文件
   - 示例: `app-release.apk`, `TVBoxAndroid-v1.2.1-release.apk`

3. **版本号要求**
   - Release tag 必须以 `v` 开头，如 `v1.2.1`
   - 版本号格式: `主版本.次版本.修订号`
   - 示例: `v1.0.0`, `v1.2.1`, `v2.0.0`

---

## 技术实现

### 核心类

#### 1. UpdateManager.kt
版本更新管理器，负责：
- 调用 GitHub API 检查最新 Release
- 版本号语义化比较
- 使用 DownloadManager 下载 APK
- 调用 FileProvider 安装 APK

**主要方法**:
```kotlin
// 检查更新
suspend fun checkUpdate(): UpdateInfo

// 下载并安装
fun downloadAndInstall(updateInfo: UpdateInfo): Long
```

#### 2. UpdateDialog.kt
更新对话框，显示版本信息和下载按钮。

#### 3. SettingsActivity.kt
集成入口，在「关于」页面添加「检查更新」按钮。

### GitHub API 调用

**Endpoint**: `https://api.github.com/repos/{owner}/{repo}/releases/latest`

**响应格式** (关键字段):
```json
{
  "tag_name": "v1.2.0",
  "body": "Release notes...",
  "published_at": "2026-11-09T05:47:00Z",
  "assets": [
    {
      "name": "app-release.apk",
      "browser_download_url": "https://github.com/.../app-release.apk",
      "size": 3145728
    }
  ]
}
```

### 版本比较算法

语义化版本（Semantic Versioning）:
```
v1.2.3 > v1.2.2  (修订号)
v1.3.0 > v1.2.9  (次版本号)
v2.0.0 > v1.9.9  (主版本号)
```

算法实现:
```kotlin
private fun compareVersions(v1: String, v2: String): Int {
    val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
    val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
    
    for (i in 0 until maxOf(parts1.size, parts2.size)) {
        val p1 = parts1.getOrNull(i) ?: 0
        val p2 = parts2.getOrNull(i) ?: 0
        if (p1 != p2) return p1 - p2
    }
    return 0
}
```

### APK 安装机制

**Android 7.0+ (API 24+)**:
- 使用 FileProvider 共享文件 URI
- 配置文件: `res/xml/file_paths.xml`
- AndroidManifest.xml 声明 provider

**Android 7.0 以下**:
- 直接使用 file:// URI

---

## 权限要求

### 必需权限

```xml
<!-- 网络访问 (已包含) -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- 下载到外部存储 -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

### AndroidManifest 配置

```xml
<!-- FileProvider for APK installation on Android 7.0+ -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

### FileProvider 路径配置

`res/xml/file_paths.xml`:
```xml
<paths>
    <external-files-path name="downloads" path="Download/" />
    <external-cache-path name="cache" path="." />
</paths>
```

---

## 工作流程图

```
用户点击「检查更新」
    ↓
显示「正在检查...」对话框
    ↓
调用 GitHub API (releases/latest)
    ↓
解析 JSON 响应
    ├─ 有新版本 → 显示「发现新版本」对话框
    │   ↓
    │  用户点击「立即更新」
    │   ↓
    │  启动 DownloadManager 后台下载
    │   ↓
    │  显示通知栏下载进度
    │   ↓
    │  下载完成 → 广播接收器触发
    │   ↓
    │  调用 FileProvider 生成 content:// URI
    │   ↓
    │  启动 ACTION_VIEW Intent 安装 APK
    │   ↓
    │  用户点击「安装」完成更新
    │
    └─ 已是最新版本 → 显示「已是最新版本」对话框
    
    └─ 网络错误 → 显示错误提示
```

---

## 测试步骤

### 本地测试

1. **修改当前版本号**（模拟旧版本）
   ```kotlin
   // app/build.gradle.kts
   versionName = "1.0.0"  // 改为比 GitHub 上更低的版本
   ```

2. **发布测试版本到 GitHub**
   ```bash
   git tag -a v1.2.1-test -m "Test release"
   git push origin v1.2.1-test
   # 在 GitHub Releases 上传 APK
   ```

3. **运行应用测试更新流程**
   - 打开应用 → 设置 → 关于 → 检查更新
   - 验证能否检测到新版本
   - 点击「立即更新」测试下载
   - 验证安装流程是否正常

### CI/CD 集成

GitHub Actions 自动构建 APK 并上传到 Release:

```yaml
name: Build and Release

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
      
      - name: Build Release APK
        run: ./gradlew assembleRelease
      
      - name: Upload APK to Release
        uses: softprops/action-gh-release@v1
        with:
          files: app/build/outputs/apk/release/app-release.apk
```

---

## 常见问题 (FAQ)

### Q1: 检查更新失败，提示"无法连接到 GitHub"？

**A**: 可能原因：
- 网络不通或需要代理
- GitHub API 限流（未认证每小时 60 次）
- 防火墙屏蔽

**解决方案**:
- 检查网络连接
- 使用 VPN 或代理
- 等待一段时间后重试

### Q2: 下载完成后没有弹出安装提示？

**A**: 检查：
- 是否授予了「安装未知应用」权限
- 下载的 APK 文件是否完整
- FileProvider 配置是否正确

### Q3: 显示"已是最新版本"但实际有新版？

**A**: 可能原因：
- GitHub Release 的 tag 格式不正确（必须以 `v` 开头）
- 版本号格式不符合 x.y.z
- Release 中没有上传 APK 文件

### Q4: 能否支持增量更新？

**A**: 当前不支持。增量更新需要：
- 服务端生成差分包
- 客户端合并补丁
- 更复杂的实现成本

建议：压缩 APK 大小（使用 R8 混淆，移除无用资源）

### Q5: 如何强制用户更新？

**A**: 修改 UpdateDialog.kt:
```kotlin
// 移除「稍后再说」按钮
.setNegativeButton("稍后再说", null)  // 删除这一行

// 禁止关闭对话框
.setCancelable(false)
```

---

## 性能优化建议

### 1. 减少 APK 体积
```gradle
// app/build.gradle.kts
android {
    buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles(...)
        }
    }
}
```

### 2. 使用 CDN 加速下载
- 将 APK 上传到 CDN（如阿里云 OSS、七牛云）
- 修改 UpdateManager 中的 downloadUrl

### 3. 添加下载进度回调
```kotlin
// 监听下载进度
val query = DownloadManager.Query().setFilterById(downloadId)
val cursor = downloadManager.query(query)
if (cursor.moveToFirst()) {
    val bytesDownloaded = cursor.getLong(
        cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
    )
    val bytesTotal = cursor.getLong(
        cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
    )
    val progress = (bytesDownloaded * 100 / bytesTotal).toInt()
    // 更新 UI
}
```

---

## 安全建议

### 1. APK 签名验证
下载后验证 APK 签名:
```kotlin
val packageInfo = packageManager.getPackageArchiveInfo(
    apkFile.absolutePath,
    PackageManager.GET_SIGNATURES
)
// 对比签名哈希
```

### 2. HTTPS 强制
GitHub API 和下载链接都使用 HTTPS。

### 3. 防止中间人攻击
- 验证 SSL 证书
- 使用证书锁定（Certificate Pinning）

---

## 相关文件清单

| 文件 | 说明 |
|------|------|
| `UpdateManager.kt` | 版本检查和下载管理器 |
| `UpdateDialog.kt` | 更新对话框 UI |
| `SettingsActivity.kt` | 集成入口（「检查更新」按钮） |
| `AndroidManifest.xml` | FileProvider 配置 |
| `res/xml/file_paths.xml` | FileProvider 路径配置 |
| `AUTO_UPDATE_GUIDE.md` | 本文档 |

---

## 更新日志

### v1.2.0 (2026-11-09)
- ✅ 首次发布自动更新功能
- ✅ 支持 GitHub Releases API 检查版本
- ✅ 后台下载 + 自动安装
- ✅ Release Notes 展示
- ✅ 语义化版本比较

---

## 许可证

本功能遵循项目主许可证。

---

**完成日期**: 2026-11-09  
**作者**: AI Assistant  
**版本**: 1.0
