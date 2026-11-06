#!/usr/bin/env python3
"""本地模拟 TVBox CMS 站源 + 直播源，用于端到端测试。"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

ROOT = os.path.dirname(os.path.abspath(__file__))
PORT = 18080
BASE = f"http://127.0.0.1:{PORT}"


def vod(i: int):
    return {
        "vod_id": str(i),
        "vod_name": f"测试影片{i}",
        "vod_pic": f"{BASE}/cover{i}.jpg",
        "vod_remarks": "HD" if i % 2 else "更新至20集",
        "vod_year": "2025",
        "vod_area": "大陆",
        "vod_actor": "张三,李四",
        "vod_director": "王五",
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print("[mock]", fmt % args)

    def _send(self, body: bytes, ctype="application/json; charset=utf-8", code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)

        if u.path == "/config.json":
            cfg = {
                "sites": [
                    {
                        "key": "mock",
                        "name": "测试站",
                        "type": 1,
                        "api": f"{BASE}/api",
                        "searchable": 1,
                        "quickSearch": 1,
                        "filter": 0,
                    },
                    {
                        "key": "mock2",
                        "name": "测试站2",
                        "type": 1,
                        "api": f"{BASE}/api2",
                        "searchable": 1,
                        "quickSearch": 1,
                        "filter": 0,
                    },
                ],
                "parses": [],
                "lives": [{"name": "测试直播", "url": f"{BASE}/live.m3u"}],
            }
            return self._send(json.dumps(cfg).encode())

        if u.path == "/live.m3u":
            m3u = (
                "#EXTM3U\n"
                f'#EXTINF:-1 tvg-logo="" group-title="央视",测试台一\n{BASE}/test.mp4\n'
                f'#EXTINF:-1 group-title="央视",测试台二\n{BASE}/test.mp4\n'
                f'#EXTINF:-1 group-title="地方台/北京",北京测试\n{BASE}/test.mp4\n'
                f'#EXTINF:-1 group-title="地方台/上海",上海测试\n{BASE}/test.mp4\n'
            )
            return self._send(m3u.encode(), "application/x-mpegurl")

        if u.path in ("/api", "/api2"):
            ac = q.get("ac", [""])[0]
            if ac == "list":
                body = {
                    "class": [
                        {"type_id": "1", "type_name": "电影"},
                        {"type_id": "2", "type_name": "剧集"},
                    ]
                }
            elif ac == "videolist":
                if "wd" in q:
                    items = [vod(1), vod(2)]
                else:
                    tid = q.get("t", [""])[0]
                    pg = int(q.get("pg", ["1"])[0])
                    items = [vod(pg * 10 + i) for i in range(1, 7)]
                body = {"page": 1, "pagecount": 2, "total": 12, "list": items}
            elif ac == "detail":
                vid = q.get("ids", ["1"])[0]
                d = vod(int(vid) if vid.isdigit() else 1)
                d["vod_content"] = "这是一段用于测试的简介，验证详情页能否正常显示长文本并支持展开收起。"
                d["vod_play_from"] = "直连线路$$$备用线路"
                d["vod_play_url"] = (
                    f"第1集${BASE}/test.mp4#第2集${BASE}/test.mp4#第3集${BASE}/test.mp4"
                    "$$$"
                    f"线路2第1集${BASE}/test.mp4"
                )
                body = {"list": [d]}
            else:
                body = {"code": 0}
            return self._send(json.dumps(body).encode())

        if u.path == "/test.mp4":
            path = os.path.join(ROOT, "test.mp4")
            with open(path, "rb") as f:
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

        if u.path.startswith("/cover"):
            # 纯色封面，避免外网依赖
            import struct, zlib
            w = h = 240
            color = (40, 120, 200)[:3]
            raw = b"".join(b"\x00" + bytes(color) * w for _ in range(h))

            def chunk(tag, data):
                c = struct.pack(">I", len(data)) + tag + data
                return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

            png = (b"\x89PNG\r\n\x1a\n"
                   + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                   + chunk(b"IDAT", zlib.compress(raw))
                   + chunk(b"IEND", b""))
            return self._send(png, "image/png")

        return self._send(b"not found", "text/plain", 404)


if __name__ == "__main__":
    print(f"mock server on {BASE}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
