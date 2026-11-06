# TVBox Android - 鸿蒙影视 Android TV 版

从 HarmonyOS 原生应用 **TVBoxTV（鸿蒙影视）** 移植到 **Android TV** 平台。

**仓库：** https://github.com/xxl6097/TVBoxAndroid
**自动构建：** 每次 push 到 `main` 由 GitHub Actions 编译 Debug APK，产物在 Actions 页面 `TVBoxAndroid-debug` 附件里。

```bash
# 本地构建（需 Android SDK + JDK 17）
./gradlew assembleDebug          # 8.3 MB，可直接安装
./scripts/gen-keystore.sh        # 生成签名密钥（可选，用于正式包）
./gradlew assembleRelease        # R8 混淆后 3.7 MB
```

## 功能一览

| 模块 | 说明 |
|---|---|
| 点播 | 站源切换、分类胶囊、筛选（地区/年份）、6 列海报网格、翻页；父分类自动聚合子分类 |
| 直播 | m3u/txt 解析、分组树、频道探活（失效标灰）、多源切换、多线路起播、画质限制与弱网自动降档 |
| 直连 | 粘贴 m3u8/mp4 直接播，保留最近 12 条 |
| 网盘 | WebDAV（极空间/群晖/AList）：源增删改、4 列海报墙、带 Basic 认证播放、最近观看与断点续播 |
| 搜索 | 最多 18 站分批并发，按站源分组展示，搜索历史 |
| 播放 | ExoPlayer；遥控器按键（±10s/±60s/播放暂停）、音量/静音、续播定位、剧集正倒序 |
| 收藏/历史 | 本地持久化，历史带进度条，点击一键续播 |
| 设置 | 订阅/配置导入、直播源、站源开关（含爬虫 jar）、测速排行、扫码录入、手动抓取、崩溃日志 |
| 抓取 | 每 6 小时自动抓各站最新，首页「最近更新」 |
| 合规 | 首启隐私政策；明文 HTTP 白名单；仅网络权限 |

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

### ✅ 已完成
- 核心协议层：HTTP / CMS（XML+JSON）/ 播放地址解析 / 直播解析 / WebDAV
- 数据层：配置、历史、收藏、订阅直播源、直连记录、抓取结果、搜索历史
- UI：Leanback 首页、点播、详情、播放、搜索、收藏、历史、设置、直播、直连、网盘
- 遥控器：方向键焦点高亮、确定/左右±10s/上下±60s/媒体键、音量与静音
- 合规与发布：首启隐私政策、明文 HTTP 白名单、图标与 banner、release 签名与 R8

### ⚠️ 已知限制
- 爬虫站源（type=3）：通过内置 `com.github.catvod.crawler.Spider` 占位基类 + DexClassLoader
  反射调用实现，支持 csp_<key> 命名；jar 若不是 dex 格式或类名不符会加载失败，
  失败原因可在日志中查看（SpiderCms.lastError）
- 首页加载态是「加载中」占位行而非骨架屏（leanback 1.2.0-alpha04 无 ProgressBarManager）
- 直播画质为「限制最大分辨率」，非逐档手动选择具体码率
- 网盘封面依赖同名图片或 folder/poster/cover/fanart，没有元数据刮削

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
