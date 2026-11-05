# TVBox Android 快速上手

## 项目现状

✅ **核心层已完成** - 协议解析、网络请求、数据存储 100% 可用  
🚧 **UI 层待实现** - 需要创建 Activity 和 Fragment

---

## 一、立即可用的代码

### 1. 测试 HTTP 客户端

```kotlin
import com.hong.tvbox.core.HttpClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val html = HttpClient.text("https://example.com")
    println("获取到 ${html.length} 字节")
}
```

### 2. 测试 CMS 协议

```kotlin
import com.hong.tvbox.core.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    // 创建站源
    val site = Site(
        key = "test",
        name = "测试站",
        type = 1,  // JSON
        api = "https://example.com/api.php/provide/vod/"
    )
    
    // 创建客户端
    val cms = CmsClient(site)
    
    // 获取分类
    val cats = cms.categories()
    cats.forEach { println("${it.id}: ${it.name}") }
    
    // 获取列表
    val page = cms.list("1", 1)
    println("共 ${page.total} 条，当前第 ${page.page}/${page.pageCount} 页")
    page.items.forEach { println("- ${it.name}") }
    
    // 获取详情
    val vod = cms.detail(page.items[0].id)
    println("片名：${vod.name}")
    println("简介：${vod.content}")
    
    // 拆分线路
    val lines = CmsClient.splitLines(vod.from, vod.playUrl)
    lines.forEach { line ->
        println("${line.name}（${if (line.direct) "直连" else "需解析"}）")
        line.eps.take(3).forEach { ep -> println("  - ${ep.name}: ${ep.url}") }
    }
}
```

### 3. 测试数据存储

```kotlin
import android.content.Context
import com.hong.tvbox.data.*

// 配置存储
val configStore = ConfigStore(context)
configStore.saveConfigJson("""{"sites":[...]}""")
val config = configStore.getConfig()

// 历史记录
val historyStore = HistoryStore(context)
historyStore.addOrUpdate(History(
    vodId = "123",
    siteKey = "test",
    vodName = "测试视频",
    episodeIndex = 0,
    position = 60000
))

// 收藏
val favoriteStore = FavoriteStore(context)
favoriteStore.add(Favorite(
    vodId = "123",
    siteKey = "test",
    vodName = "测试视频"
))
```

---

## 二、下一步：创建主界面

### 方案 A：Leanback（推荐，TV 原生）

```kotlin
// MainActivity.kt
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, BrowseFragment())
                .commit()
        }
    }
}

// BrowseFragment.kt
class BrowseFragment : androidx.leanback.app.BrowseFragment() {
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        title = "鸿蒙影视"
        
        // 加载站源
        loadSites()
    }
    
    private fun loadSites() {
        val configStore = ConfigStore(requireContext())
        val sites = configStore.getEnabledSiteList()
        
        val adapter = ArrayObjectAdapter(ListRowPresenter())
        sites.forEach { site ->
            // 为每个站源创建一行
            val cms = CmsClient(site)
            lifecycleScope.launch {
                val cats = cms.categories()
                // 创建 ListRow 并添加到 adapter
            }
        }
        
        this.adapter = adapter
    }
}
```

### 方案 B：Compose for TV（现代化，但学习成本高）

```kotlin
@Composable
fun MainScreen() {
    TvLazyColumn {
        item {
            Text("鸿蒙影视", style = MaterialTheme.typography.headlineLarge)
        }
        
        // 站源列表
        items(sites) { site ->
            SiteRow(site)
        }
    }
}
```

---

## 三、播放器集成（ExoPlayer）

```kotlin
// PlayerActivity.kt
class PlayerActivity : AppCompatActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        player = ExoPlayer.Builder(this).build()
        playerView = findViewById(R.id.player_view)
        playerView.player = player
        
        // 获取播放地址
        val url = intent.getStringExtra("url") ?: return
        
        // 创建媒体项
        val mediaItem = MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
```

布局文件 `res/layout/activity_player.xml`：
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.media3.ui.PlayerView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/player_view"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:keepScreenOn="true" />
```

---

## 四、导入 TVBox 配置

### 方式 1：从 URL 导入

```kotlin
val configUrl = "https://example.com/config.json"
lifecycleScope.launch {
    try {
        val json = HttpClient.text(configUrl)
        ConfigStore(context).saveConfigJson(json)
        Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "导入失败：${e.message}", Toast.LENGTH_SHORT).show()
    }
}
```

### 方式 2：粘贴 JSON

```kotlin
val json = """
{
  "sites": [
    {
      "key": "test",
      "name": "测试站",
      "type": 1,
      "api": "https://example.com/api.php/provide/vod/"
    }
  ]
}
"""
ConfigStore(context).saveConfigJson(json)
```

---

## 五、常见问题

### Q1: 如何在 Android Studio 中运行？
1. 打开项目
2. 等待 Gradle 同步完成
3. Tools → Device Manager → 创建 TV 模拟器
4. Run → Run 'app'

### Q2: 核心代码在哪里测试？
创建一个 JUnit 测试：
```kotlin
// app/src/test/java/com/hong/tvbox/CmsTest.kt
class CmsTest {
    @Test
    fun testCms() = runBlocking {
        // 测试代码
    }
}
```

### Q3: 为什么没有 UI？
当前只完成了核心协议层，UI 层需要你继续实现。参考上面的示例代码创建 Activity。

### Q4: 可以直接用在手机上吗？
可以，但需要调整布局：
- 去掉 `android:software.leanback` 声明
- 改用 RecyclerView 替代 Leanback
- 适配竖屏布局

---

## 六、推荐学习资源

1. **Android TV 开发**
   - https://developer.android.com/training/tv/start

2. **Leanback 库**
   - https://developer.android.com/jetpack/androidx/releases/leanback

3. **ExoPlayer**
   - https://developer.android.com/media/media3/exoplayer/hello-world

4. **Kotlin 协程**
   - https://kotlinlang.org/docs/coroutines-guide.html

---

**当前进度：** 核心层 100% | UI 层 0%  
**可编译：** ✅ 是  
**可运行：** ⚠️ 需要先创建 MainActivity  
**预估剩余工作量：** 20-30 小时
