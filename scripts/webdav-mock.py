#!/usr/bin/env python3
"""
极简 WebDAV 服务端，仅用于端到端测试。

支持：
  PROPFIND  Depth:1  → 207 Multi-Status（目录 + 视频 + 同名封面图）
  GET       /dav/... → 返回测试视频 / 纯色封面

鉴权：要求 Basic dGVzdDp0ZXN0（test:test），用来验证客户端是否正确带上 Authorization。

用法：python3 scripts/webdav-mock.py
"""
import base64
import os
import struct
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, quote

PORT = 18081
BASE = f"http://127.0.0.1:{PORT}"
ROOT = "/dav"
AUTH = "Basic " + base64.b64encode(b"test:test").decode()
HERE = os.path.dirname(os.path.abspath(__file__))
VIDEO = os.path.join(HERE, "test.mp4")


def ensure_video():
    """没有测试视频就用 ffmpeg 现造一个 8 秒彩条，免得手动准备素材。"""
    if os.path.exists(VIDEO):
        return True
    try:
        import subprocess
        subprocess.run([
            "ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=25",
            "-f", "lavfi", "-i", "sine=frequency=440", "-t", "8",
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac",
            "-movflags", "+faststart", VIDEO,
        ], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        print(f"[webdav] 已生成测试视频 {VIDEO}", flush=True)
        return True
    except Exception as e:
        print(f"[webdav] 生成测试视频失败：{e}", flush=True)
        return False

# 目录结构：/dav → 电影/ + 剧集/
TREE = {
    "/dav/": [
        ("电影", True),
        ("剧集", True),
    ],
    "/dav/电影/": [
        ("测试电影A.mp4", False),
        ("测试电影A.jpg", False),
        ("测试电影B.mp4", False),
    ],
    "/dav/剧集/": [
        ("folder.jpg", False),
        ("某剧S01E01.mp4", False),
        ("某剧S01E02.mp4", False),
    ],
}


def png_bytes(color=(40, 120, 200), w=240, h=160):
    raw = b"".join(b"\x00" + bytes(color) * w for _ in range(h))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b""))


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[webdav]", fmt % args, flush=True)

    def _need_auth(self):
        if self.headers.get("Authorization") != AUTH:
            body = b"unauthorized"
            self.send_response(401)
            self.send_header("WWW-Authenticate", 'Basic realm="dav"')
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return True
        return False

    # ---------- PROPFIND ----------
    def do_PROPFIND(self):
        if self._need_auth():
            return

        path = unquote(self.path.split("?")[0])
        if not path.endswith("/"):
            path += "/"
        if path not in TREE:
            return self._send(b"not found", "text/plain", 404)

        items = [path] + [path + name + ("/" if is_dir else "") for name, is_dir in TREE[path]]
        parts = []
        for href in items:
            is_dir = href.endswith("/")
            display = href.rstrip("/").rsplit("/", 1)[-1] or "dav"
            parts.append(
                "<D:response>"
                f"<D:href>{quote(href)}</D:href>"
                "<D:propstat><D:prop>"
                f"<D:displayname>{display}</D:displayname>"
                + ("<D:collection/>" if is_dir else
                   "<D:getcontentlength>52428800</D:getcontentlength>")
                + "<D:getlastmodified>Fri, 06 Nov 2026 02:00:00 GMT</D:getlastmodified>"
                "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                "</D:response>"
            )

        xml = ('<?xml version="1.0" encoding="utf-8"?>'
               '<D:multistatus xmlns:D="DAV:">' + "".join(parts) + "</D:multistatus>")
        self._send(xml.encode(), "application/xml; charset=utf-8", 207)

    # ---------- GET ----------
    def do_GET(self):
        if self._need_auth():
            return

        path = unquote(self.path.split("?")[0])
        if path.lower().endswith((".jpg", ".jpeg", ".png")):
            return self._send(png_bytes(), "image/png")

        if path.lower().endswith(".mp4"):
            if not ensure_video():
                return self._send(b"no video", "text/plain", 404)
            with open(VIDEO, "rb") as f:
                data = f.read()

            rng = self.headers.get("Range")
            if rng and rng.startswith("bytes="):
                s = rng[6:].split("-")
                start = int(s[0]) if s[0] else 0
                end = int(s[1]) if len(s) > 1 and s[1] else len(data) - 1
                end = min(end, len(data) - 1)
                chunk = data[start:end + 1]
                self.send_response(206)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
                self.send_header("Content-Length", str(len(chunk)))
                self.end_headers()
                self.wfile.write(chunk)
                return
            return self._send(data, "video/mp4")

        self._send(b"not found", "text/plain", 404)

    def _send(self, body, ctype, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ensure_video()
    print(f"webdav mock on {BASE}/dav  (Basic test:test)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
