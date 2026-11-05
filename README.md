# TVBox Android - 鸿蒙影视 Android TV 版

从 HarmonyOS 原生应用 **TVBoxTV（鸿蒙影视）** 移植到 **Android TV** 平台。

**仓库：** https://github.com/xxl6097/TVBoxAndroid
**自动构建：** 每次 push 到 `main` 由 GitHub Actions 编译 Debug APK，产物在 Actions 页面 `TVBoxAndroid-debug` 附件里。

```bash
# 本地构建（需 Android SDK + JDK 17）
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

已产出验证版本：`dist/app-debug.apk`（7.9 MB，debug 签名，minSdk 21）。

## 项目结构

```
TVBoxAndroid/
├── app/src/main/
│   ├── java/com/hong/tvbox/
│   │   ├── core/           # 核心协议层
│   │   │   ├── HttpClient.kt      # HTTP 客户端（从 Http.ets 移植）
│   │   │   ├── CmsClient.kt       # 采集站协议（从 Cms.ets 移植）
│   │   │   ├── Model.kt           # 数据模型
│   │   │   └── ResolveClient.kt   # 播放地址解析（待实现）
│   │   ├── data/           # 数据持久化层
│   │   │   ├── ConfigStore.kt     # 配置存储
│   │   │   ├── HistoryStore.kt    # 历史记录
│   │   │   └── FavoriteStore.kt   # 收藏管理
│   │   └── ui/             # 界面层
│   │       ├── MainActivity.kt    # 主界面
│   │       ├── BrowseFragment.kt  # 浏览页
│   │       ├── DetailActivity.kt  # 详情页
│   │       └── PlayerActivity.kt  # 播放器
│   ├── res/
│   │   ├── layout/         # 布局文件
│   │   ├── values/         # 主题、颜色、字符串
│   │   └── drawable/       # 图片资源
│   └── AndroidManifest.xml
└── build.gradle.kts

```

## 技术栈

| 模块 | 技术选型 |
|---|---|
| 语言 | Kotlin |
| UI 框架 | Android TV Leanback |
| 网络 | OkHttp + Retrofit |
| 播放器 | ExoPlayer (Media3) |
| 数据持久化 | SharedPreferences |
| 协程 | Kotlin Coroutines |
| JSON 解析 | Gson |
| 图片加载 | Glide |

## 移植进度

### ✅ 已完成（100%）
- [x] 项目骨架创建
- [x] HTTP 客户端（HttpClient.kt）
- [x] 数据模型（Model.kt）
- [x] CMS 协议客户端（CmsClient.kt）
- [x] 数据存储层（ConfigStore / HistoryStore / FavoriteStore）
- [x] UI 界面（Leanback Fragment）
- [x] 播放器集成（ExoPlayer）
- [x] 搜索功能
- [x] 收藏与历史（数据层）
- [x] 主浏览页（MainBrowseFragment）
- [x] 详情页（DetailActivity）
- [x] 播放器页（PlayerActivity）

### ⚠️  待优化
- [ ] 播放地址解析（ResolveClient.kt）- 非直连链接需要
- [ ] 配置导入 UI - 当前需通过 API 导入
- [ ] 直播功能
- [ ] 设置页面
- [ ] 图片加载（Glide 集成）

## 核心特性

### 协议支持
- ✅ Type 0: XML 采集接口（完整移植）
- ✅ Type 1: JSON 采集接口（完整移植）
- ✅ Type 4: 多层 data 的 JSON 接口
- ❌ Type 3: 爬虫型站源（需 JVM 运行时，Android 可支持，待实现）

### 缓存策略
- 分类表：5 分钟
- 列表：90 秒
- 详情：10 分钟
- 搜索：不缓存

### 线路识别
自动判断播放地址是否可直连：
- .m3u8 / .mp4 / .flv / .mkv / .ts → 直连
- 网页地址 → 需解析

## 构建方式

```bash
# 1. 用 Android Studio 打开项目
# 2. 同步 Gradle
# 3. 连接 Android TV 设备或模拟器
# 4. 运行
```

## 与鸿蒙版本对照

| 模块 | 鸿蒙版本 | Android 版本 |
|---|---|---|
| HTTP 客户端 | @ohos.net.http | OkHttp |
| 播放器 | AVPlayer + XComponent | ExoPlayer |
| 存储 | @ohos.data.preferences | SharedPreferences |
| UI | ArkUI (ArkTS) | Leanback (Kotlin) |
| 协程 | Promise | Kotlin Coroutines |

## 下一步

1. **实现 ResolveClient**（播放地址解析）
2. **创建数据存储层**（配置/历史/收藏）
3. **搭建 Leanback UI**（首页/浏览/详情/播放）
4. **集成 ExoPlayer**

---

**移植自：** [TVBoxTV (HarmonyOS)](../TVBoxTV/)  
**开发环境：** Android Studio + JDK 17  
**最低 SDK：** API 21 (Android 5.0)  
**目标 SDK：** API 34 (Android 14)
