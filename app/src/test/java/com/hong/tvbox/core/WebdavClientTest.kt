package com.hong.tvbox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 目录解析测试。
 *
 * 这块逻辑踩过两个真实坑，都来自服务端实现不规范：
 *  1. 极空间对**所有** href 都加尾斜杠（文件也加），只看斜杠会把 mp4 判成文件夹；
 *  2. 极空间对文件也返回 <collection/> 标签，只看标签同样误判。
 * 所以判断必须按 contentlength → 斜杠 → collection 的顺序来，这里把三种风格都固化下来。
 */
class WebdavClientTest {

    /** 规范实现：目录 href 带斜杠，文件不带；两者都不带 collection 混淆 */
    private val standardXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/%E7%94%B5%E5%BD%B1/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/%E7%94%B5%E5%BD%B1/%E5%87%A1%E4%BA%BA/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/%E7%94%B5%E5%BD%B1/%E6%B5%8B%E8%AF%95.mp4</D:href>
            <D:propstat><D:prop>
              <D:resourcetype/>
              <D:getcontentlength>1073741824</D:getcontentlength>
            </D:prop></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    /** 极空间风格：所有 href 都带尾斜杠，文件还带 collection 标签 */
    private val zspaceXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/%E7%94%B5%E5%BD%B1/%E5%87%A1%E4%BA%BA/</D:href>
            <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/%E7%94%B5%E5%BD%B1/%E6%B5%8B%E8%AF%95.mp4/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection/></D:resourcetype>
              <D:getcontentlength>1073741824</D:getcontentlength>
            </D:prop></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    /** 老实现：href 一律不带斜杠，只能靠 resourcetype 区分 */
    private val noSlashXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/%E7%94%B5%E5%BD%B1/%E5%87%A1%E4%BA%BA</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/%E7%94%B5%E5%BD%B1/%E6%B5%8B%E8%AF%95.mp4</d:href>
            <d:propstat><d:prop>
              <d:resourcetype/>
              <d:getcontentlength>2048</d:getcontentlength>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>
    """.trimIndent()

    /** 极空间真实响应（2026-11 抓取）：所有条目都是 collection、都没有 contentlength、文件 href 带斜杠 */
    private val zspaceRealXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/%E8%BF%85%E9%9B%B7%E4%B8%8B%E8%BD%BD/%E7%94%B5%E5%BD%B1/</D:href>
            <D:propstat><D:prop>
              <D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype>
              <D:displayname>电影</D:displayname>
            </D:prop></D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/%E8%BF%85%E9%9B%B7%E4%B8%8B%E8%BD%BD/%E7%94%B5%E5%BD%B1/%E5%87%A1%E4%BA%BA%E4%BF%AE%E4%BB%99%E4%BC%A0%E9%87%8D%E8%BF%94%E5%A4%A9%E5%8D%9706.mp4/</D:href>
            <D:propstat><D:prop>
              <D:displayname>凡人修仙传重返天南06.mp4</D:displayname>
              <D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype>
            </D:prop></D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    private val selfPath = "/dav/电影"

    @Test
    fun `规范服务端 - 目录与文件区分正确`() {
        val list = WebdavClient.parse(standardXml, selfPath)

        val dir = list.first { it.name == "凡人" }
        val file = list.first { it.name == "测试.mp4" }

        assertTrue("凡人应是目录", dir.isDir)
        assertFalse("测试.mp4 应是文件", file.isDir)
        assertEquals(1073741824L, file.size)
    }

    @Test
    fun `极空间风格 - 文件 href 带斜杠也不误判为目录`() {
        val list = WebdavClient.parse(zspaceXml, selfPath)

        val dir = list.first { it.name == "凡人" }
        val file = list.first { it.name == "测试.mp4" }

        assertTrue("凡人应是目录", dir.isDir)
        assertFalse("测试.mp4 应是文件（有 contentlength，优先于斜杠判断）", file.isDir)
    }

    @Test
    fun `无斜杠服务端 - 靠 resourcetype 兜底`() {
        val list = WebdavClient.parse(noSlashXml, selfPath)

        val dir = list.first { it.name == "凡人" }
        val file = list.first { it.name == "测试.mp4" }

        assertTrue("凡人应是目录", dir.isDir)
        assertFalse("测试.mp4 应是文件", file.isDir)
    }

    @Test
    fun `极空间真实响应 - 全是collection靠扩展名识别文件`() {
        // selfPath 换成真实目录，避免自身条目被过滤掉后拿不到数据
        val list = WebdavClient.parse(
            zspaceRealXml,
            "/dav/迅雷下载/电影"
        )

        assertTrue("解析结果不应为空", list.isNotEmpty())

        val file = list.first { it.name == "凡人修仙传重返天南06.mp4" }
        assertFalse("mp4 必须是文件，否则点进去是空目录", file.isDir)
    }

    @Test
    fun `文件名带点但不是媒体扩展名 - 仍视为目录`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/%E5%89%A7/S01.E01/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val list = WebdavClient.parse(xml, "/dav/剧")
        assertEquals(1, list.size)
        assertTrue("S01.E01 不是媒体扩展名，应按目录处理", list[0].isDir)
    }

    @Test
    fun `自身条目被过滤`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/%E7%94%B5%E5%BD%B1/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        assertTrue("当前目录自身不应出现在列表里", WebdavClient.parse(xml, selfPath).isEmpty())
    }

    @Test
    fun `文件名含特殊字符也能正确取出`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/%E7%94%B5%E5%BD%B1/%E4%B8%8D%E5%8F%AF%E9%A5%B6%E6%81%95.1992_BD%E5%9B%BD%E8%8B%B1%E5%8F%8C%E8%AF%AD%E4%B8%AD%E5%AD%97.mp4</D:href>
                <D:propstat><D:prop><D:getcontentlength>99</D:getcontentlength></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val list = WebdavClient.parse(xml, selfPath)
        assertEquals(1, list.size)
        assertEquals("不可饶恕.1992_BD国英双语中字.mp4", list[0].name)
        assertFalse(list[0].isDir)
    }

    // ============ 播放地址编码 ============

    @Test
    fun `播放地址里的中文与空格要编码`() {
        val src = WebdavSource(
            id = "1",
            name = "测试",
            url = "https://fs.example.com:88/dav/迅雷下载",
            root = ""
        )
        val u = WebdavClient.urlFor(src, "电影/不可饶恕 1992.mp4")

        // 空格必须编码，否则 HttpURLConnection 直接拒绝或服务端当成截断
        assertFalse("URL 里不应残留空格", u.contains(" "))
        assertTrue("中文应被编码", u.contains("%E7%94%B5%E5%BD%B1"))
        assertTrue("应保留扩展名", u.endsWith(".mp4"))
        // 主机与端口不能被破坏
        assertTrue(u.startsWith("https://fs.example.com:88/dav/"))
    }

    @Test
    fun `已经编码过的地址不会被二次编码`() {
        val once = "https://a.com/dav/%E7%94%B5%E5%BD%B1/x.mp4"
        assertEquals(once, WebdavClient.encodeUrl(once))
    }

    @Test
    fun `decodeHref 不能把加号变成空格`() {
        // URLDecoder.decode 会把 '+' 变空格，文件名带加号就会取错路径
        assertEquals("a+b.mp4", WebdavClient.decodeHref("a+b.mp4"))
        assertEquals("a b.mp4", WebdavClient.decodeHref("a%20b.mp4"))
    }

    @Test
    fun `目录href带编码时也能正确解出中文名`() {
        val xml = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/%E7%94%B5%E5%BD%B1/</D:href>
                <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val list = WebdavClient.parse(xml, "/dav")
        assertEquals(1, list.size)
        assertEquals("电影", list[0].name)
        assertTrue(list[0].isDir)
    }
}
