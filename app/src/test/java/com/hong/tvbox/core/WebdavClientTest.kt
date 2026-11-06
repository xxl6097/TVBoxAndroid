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
}
