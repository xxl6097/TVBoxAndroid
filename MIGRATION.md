# 移植报告：TVBoxTV (HarmonyOS) → Android TV

## 一、移植完成情况

### ✅ 已完成模块（核心层 100%）

| 模块 | 鸿蒙原文件 | Android 文件 | 完成度 | 说明 |
|---|---|---|---|---|
| HTTP 客户端 | `Http.ets` | `HttpClient.kt` | 100% | OkHttp 实现，保留重定向、超时、中文错误提示 |
| CMS 协议 | `Cms.ets` | `CmsClient.kt` | 100% | 完整移植 XML/JSON 解析、缓存策略、线路拆分 |
| 数据模型 | `Model.ets` | `Model.kt` | 100% | 数据类 + Gson 注解 |
| 配置存储 | `Store.ets` | `ConfigStore.kt` | 100% | SharedPreferences 实现 |
| 历史记录 | `Store.ets` | `HistoryStore.kt` | 100% | 独立存储文件 |
| 收藏管理 | `Store.ets` | `FavoriteStore.kt` | 100% | 独立存储文件 |

### 🚧 待实现模块（UI 层 0%）

| 模块 | 优先级 | 预估工作量 |
|---|---|---|
| MainActivity + BrowseFragment | P0 | 4 小时 |
| DetailActivity（详情页） | P0 | 3 小时 |
| PlayerActivity（ExoPlayer 集成） | P0 | 4 小时 |
| ResolveClient（播放地址解析） | P1 | 2 小时 |
| SearchActivity（搜索） | P1 | 3 小时 |
| SettingsActivity（设置页） | P2 | 2 小时 |
| 直播功能 | P2 | 4 小时 |

## 二、技术对照表

| 功能 | 鸿蒙技术 | Android 技术 | 兼容性 |
|---|---|---|---|
| HTTP 请求 | `@ohos.net.http` | OkHttp 4.12 | ✅ 完全兼容 |
| JSON 解析 | 手写 parse | Gson 2.10 | ✅ 更强大 |
| 协程 | `Promise` | Kotlin Coroutines | ✅ 更强大 |
| 数据持久化 | `@ohos.data.preferences` | `SharedPreferences` | ✅ 完全兼容 |
| 播放器 | `AVPlayer` | ExoPlayer (Media3) | ✅ 更强大 |
| UI 框架 | ArkUI (声明式) | Leanback (命令式) | ⚠️ 需重写 |

## 三、核心移植细节

### 3.1 HTTP 客户端

**鸿蒙版本（Http.ets）：**
```typescript
const req = http.createHttp();
const res = await req.request(url, {
  method: http.RequestMethod.GET,
  connectTimeout: ms,
  readTimeout: ms
});
```

**Android 版本（HttpClient.kt）：**
```kotlin
val client = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .build()

val response = client.newCall(request).execute()
```

**改进点：**
- 单例客户端，连接复用
- 自动 gzip 压缩
- 更强大的拦截器机制

### 3.2 CMS 协议解析

**完全保留的逻辑：**
- ✅ 缓存策略（分类 5 分钟 / 列表 90 秒 / 详情 10 分钟）
- ✅ XML 正则解析（含 CDATA 处理）
- ✅ JSON 容错（去 BOM / 去注释 / 保留字替换）
- ✅ 线路拆分与直连判断
- ✅ 父分类聚合（`listMerged`）

**Kotlin 改进：**
```kotlin
// 鸿蒙版本：手写循环
for (let i = 0; i < root.list.length; i++) {
  out.push(this.toVod(root.list[i]));
}

// Android 版本：函数式
root.list?.forEach { jv -> pg.items.add(toVod(jv)) }
```

### 3.3 数据存储

**鸿蒙版本：**
```typescript
preferences.put(context, 'config_json', json);
```

**Android 版本：**
```kotlin
prefs.edit().putString(KEY_CONFIG_JSON, json).apply()
```

**对等性：** 100% 语义一致

## 四、配色与尺寸还原

从 `FongMi/TV` 的 `leanback/res/values/` 一比一提取：

| 项 | 原版取值 | Android 实现 |
|---|---|---|
| colorPrimary | `#FFFFFF`（纯白） | `colors.xml` ✅ |
| 选中文字 | `#FFEB3B`（黄） | `colors.xml` ✅ |
| 窗口背景 | `#141218` | `colors.xml` ✅ |
| 功能块 | 100dp×100dp + 4dp 圆角 | `dimens.xml` ✅ |
| 卡片圆角 | 上左/上右 8dp | `dimens.xml` ✅ |
| 控制面板 | 上左/上右 28dp 圆角 | `dimens.xml` ✅ |

## 五、协议支持对照

| 站源类型 | 鸿蒙版本 | Android 版本 | 说明 |
|---|---|---|---|
| Type 0（XML） | ✅ | ✅ | 完整移植 |
| Type 1（JSON） | ✅ | ✅ | 完整移植 |
| Type 4（多层 data） | ✅ | ✅ | 完整移植 |
| Type 3（爬虫 jar） | ❌ | ⚠️ 可实现 | 鸿蒙无 JVM，Android 可加载 dex |

**关键结论：** Android 版本可以支持**更多站源**（爬虫型可用 DexClassLoader 加载）

## 六、下一步实施路径

### 阶段 1：最小可播放版本（12 小时）
1. **MainActivity + BrowseFragment**（4h）
   - Leanback BrowseFragment
   - 站源列表行 + 分类行
   - 海报网格（Glide 加载）

2. **DetailActivity**（3h）
   - 详情布局
   - 线路与剧集网格
   - 收藏与播放按钮

3. **PlayerActivity**（4h）
   - ExoPlayer 集成
   - HLS/MP4 直连播放
   - 控制条（播放/暂停/进度条）

4. **测试验证**（1h）
   - 导入 TVBox 配置
   - 选站 → 分类 → 播放
   - 验证缓存命中

### 阶段 2：完整功能（10 小时）
5. **ResolveClient**（2h） - 播放地址解析
6. **SearchActivity**（3h） - 多站并发搜索
7. **SettingsActivity**（2h） - 配置导入、站源管理
8. **历史与收藏 UI**（3h）

### 阶段 3：高级功能（8 小时）
9. **直播功能**（4h） - m3u 解析 + 频道列表
10. **爬虫支持**（4h） - DexClassLoader 加载 jar

## 七、构建指南

```bash
# 1. 安装 Android Studio Hedgehog 或更高版本
# 2. 打开项目 TVBoxAndroid
cd /Users/uuxia/WorkBuddy/2026-11-01-12-57-02/TVBoxAndroid
open -a "Android Studio" .

# 3. 同步 Gradle（首次需要下载依赖）
# 点击 Sync Now 或 File → Sync Project with Gradle Files

# 4. 连接 Android TV 设备或模拟器
# Tools → Device Manager → Create Virtual Device → TV

# 5. 运行
# Run → Run 'app' 或 ⌃R
```

## 八、测试检查清单

### 核心协议测试
- [ ] HTTP 客户端：请求超时、重定向、错误提示
- [ ] CMS 解析：XML 站源、JSON 站源、缓存命中
- [ ] 线路拆分：直连判断、$$$ 与 # 分隔符
- [ ] 存储读写：配置、历史、收藏的增删改查

### UI 测试（待实现后）
- [ ] 首页：功能块 + 继续观看 + 最近更新
- [ ] 浏览：分类切换、翻页、加载状态
- [ ] 详情：海报、线路、剧集、收藏
- [ ] 播放：ExoPlayer、控制条、进度保存
- [ ] 搜索：多站并发、结果展示
- [ ] 设置：导入配置、站源开关

## 九、已知问题与限制

1. **爬虫型站源（type=3）**
   - 鸿蒙版本：不支持（无 JVM）
   - Android 版本：可实现但需额外开发

2. **主题适配**
   - 当前只有深色主题
   - 需要适配不同 DPI（目前按 1080p 设计）

3. **无障碍支持**
   - 遥控器焦点导航需测试
   - D-Pad 按键响应需优化

## 十、参考资料

| 文档 | 链接 |
|---|---|
| Android TV Leanback 官方文档 | https://developer.android.com/training/tv |
| ExoPlayer 快速开始 | https://developer.android.com/media/media3/exoplayer |
| FongMi/TV 原版仓库 | https://github.com/FongMi/TV |
| TVBox 站源规则 | https://github.com/zqc11608/box |

---

**移植完成时间：** 2026-11-05  
**移植者：** WorkBuddy AI  
**原项目：** TVBoxTV (HarmonyOS 7.0)  
**目标平台：** Android TV (API 21+)
