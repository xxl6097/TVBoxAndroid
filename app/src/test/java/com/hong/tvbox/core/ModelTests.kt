package com.hong.tvbox.core

import com.google.gson.Gson
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * ConfigStore 单元测试
 */
class ConfigStoreTest {
    
    private lateinit var store: ConfigStore
    
    @Before
    fun setUp() {
        // Mock SharedPreferences
        // 实际项目中使用 RobolectricTestRunner
        store = ConfigStore(mockContext)
    }
    
    @Test
    fun testPlaySpeedPref() {
        // 默认应为 1.0x (idx=2)
        assertEquals(2, store.playSpeedPref())
        
        // 设置为 1.5x (idx=4)
        store.setPlaySpeedPref(4)
        assertEquals(4, store.playSpeedPref())
    }
    
    @Test
    fun testAutoPlayNext() {
        // 默认应为 false
        assertEquals(false, store.autoPlayNext())
        
        // 设置为 true
        store.setAutoPlayNext(true)
        assertEquals(true, store.autoPlayNext())
    }
    
    @Test
    fun testWebdavScanCache() {
        val srcId = "test_source_1"
        val vods = listOf(
            WebdavVod(name = "电影1", path = "/path/to/movie1"),
            WebdavVod(name = "电影2", path = "/path/to/movie2")
        )
        
        // 保存缓存
        store.saveWebdavScanCache(srcId, vods)
        
        // 读取缓存
        val cached = store.getWebdavScanCache(srcId)
        assertNotNull(cached)
        assertEquals(2, cached?.size)
        assertEquals("电影1", cached?.get(0)?.name)
    }
    
    @Test
    fun testWebdavScanCacheTTL() {
        val srcId = "test_source_2"
        val vods = listOf(WebdavVod(name = "电影", path = "/path"))
        
        store.saveWebdavScanCache(srcId, vods)
        
        // 模拟 7 天后
        val cached = store.getWebdavScanCache(srcId, maxAgeMs = 1)
        
        // TTL 过期，应返回 null
        assertEquals(null, cached)
    }
    
    @Test
    fun testClearWebdavScanCache() {
        val srcId = "test_source_3"
        val vods = listOf(WebdavVod(name = "电影", path = "/path"))
        
        store.saveWebdavScanCache(srcId, vods)
        store.clearWebdavScanCache(srcId)
        
        val cached = store.getWebdavScanCache(srcId)
        assertEquals(null, cached)
    }
    
    @Test
    fun testKeywordDedup() {
        store.clearKeywords()
        
        store.addKeyword("尖皮直漫")
        store.addKeyword("尖皮直漫")  // 重复
        store.addKeyword("鬼灭之刃")
        
        val keywords = store.getKeywords()
        assertEquals(2, keywords.size)  // 去重后只有 2 条
        assert(keywords.contains("尖皮直漫"))
        assert(keywords.contains("鬼灭之刃"))
    }
}

/**
 * CmsClient 单元测试
 */
class CmsClientTest {
    
    @Test
    fun testParseXmlResponse() {
        val xml = """
            <rss>
                <item>
                    <vod_name>Test Movie</vod_name>
                    <vod_pic>http://pic.jpg</vod_pic>
                    <vod_year>2024</vod_year>
                    <vod_area>China</vod_area>
                </item>
            </rss>
        """.trimIndent()
        
        val result = CmsClient.parseXml(xml)
        assertNotNull(result)
        assertEquals("Test Movie", result.first().name)
        assertEquals("http://pic.jpg", result.first().pic)
    }
    
    @Test
    fun testParseJsonResponse() {
        val json = """
            {
                "list": [
                    {
                        "vod_name": "Test Movie",
                        "vod_pic": "http://pic.jpg",
                        "vod_remarks": "New"
                    }
                ]
            }
        """.trimIndent()
        
        val result = CmsClient.parseJson(json)
        assertNotNull(result)
        assertEquals("Test Movie", result.first().name)
    }
}

/**
 * Model 数据类序列化测试
 */
class ModelSerializationTest {
    
    private val gson = Gson()
    
    @Test
    fun testVodSerialization() {
        val vod = Vod(
            id = "1",
            name = "Test Movie",
            pic = "http://pic.jpg",
            year = "2024"
        )
        
        val json = gson.toJson(vod)
        val deserialized = gson.fromJson(json, Vod::class.java)
        
        assertEquals("1", deserialized.id)
        assertEquals("Test Movie", deserialized.name)
    }
    
    @Test
    fun testEpisodeWithUrlsSerialization() {
        val episode = Episode(
            name = "第1集",
            url = "http://url1.m3u8",
            urls = mutableListOf("http://url1.m3u8", "http://url2.m3u8")
        )
        
        val json = gson.toJson(episode)
        val deserialized = gson.fromJson(json, Episode::class.java)
        
        assertEquals("第1集", deserialized.name)
        assertEquals(2, deserialized.urls.size)
    }
    
    @Test
    fun testFavoriteSerialization() {
        val favorite = Favorite(
            vodId = "1",
            vodName = "Movie",
            vodClass = "电影",
            createdAt = System.currentTimeMillis()
        )
        
        val json = gson.toJson(favorite)
        val deserialized = gson.fromJson(json, Favorite::class.java)
        
        assertEquals("1", deserialized.vodId)
        assertEquals("电影", deserialized.vodClass)
    }
}
