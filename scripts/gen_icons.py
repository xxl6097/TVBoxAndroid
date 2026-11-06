#!/usr/bin/env python3
"""
生成 Android 图标与 TV Banner。

设计：深色底 #141218 + 白色播放三角 + 底部黄色高亮条，
与 App 内配色（FongMi/TV 主题：纯白主色 + #FFEB3B 选中色）保持一致。

用法：python3 scripts/gen_icons.py
产物：app/src/main/res/mipmap-*/ic_launcher*.png, app/src/main/res/drawable-nodpi/app_banner.png
"""

import os
from PIL import Image, ImageDraw

BG = (20, 18, 24, 255)        # #141218
WHITE = (255, 255, 255, 255)
YELLOW = (255, 235, 59, 255)  # #FFEB3B

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# 各密度图标边长
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def squircle_mask(size: int, exponent: float = 5.0) -> Image.Image:
    """生成超级椭圆（squircle）遮罩，指数 5 接近 Apple 图标轮廓。"""
    ss = 4  # 超采样，抗锯齿
    w = size * ss
    img = Image.new("L", (w, w), 0)
    draw = ImageDraw.Draw(img)
    c = w / 2.0
    r = w / 2.0
    # 逐像素判断 |x|^n + |y|^n <= r^n
    px = img.load()
    for y in range(w):
        ny = abs((y + 0.5 - c) / r)
        ny_n = ny ** exponent
        for x in range(w):
            nx = abs((x + 0.5 - c) / r)
            if (nx ** exponent) + ny_n <= 1.0:
                px[x, y] = 255
    return img.resize((size, size), Image.LANCZOS)


def circle_mask(size: int) -> Image.Image:
    ss = 4
    w = size * ss
    img = Image.new("L", (w, w), 0)
    draw = ImageDraw.Draw(img)
    draw.ellipse((0, 0, w - 1, w - 1), fill=255)
    return img.resize((size, size), Image.LANCZOS)


def draw_mark(size: int, inset_ratio: float = 0.30) -> Image.Image:
    """深色底 + 播放三角 + 底部黄条。"""
    img = Image.new("RGBA", (size, size), BG)
    draw = ImageDraw.Draw(img)

    s = size
    # 播放三角：居中偏上，等边三角形指向右
    tri_h = s * (1 - inset_ratio * 2) * 0.72
    tri_w = tri_h * 0.86
    cx = s / 2.0
    cy = s * 0.47
    left = cx - tri_w / 2.2
    top = cy - tri_h / 2.0
    bottom = cy + tri_h / 2.0
    draw.polygon(
        [(left, top), (left, bottom), (left + tri_w, cy)],
        fill=WHITE,
    )

    # 底部黄条：宽度约为图标的 34%，高度 5%，圆角
    bar_w = s * 0.34
    bar_h = max(2, int(s * 0.05))
    bar_x = (s - bar_w) / 2.0
    bar_y = s * 0.76
    draw.rounded_rectangle(
        (bar_x, bar_y, bar_x + bar_w, bar_y + bar_h),
        radius=bar_h / 2.0,
        fill=YELLOW,
    )
    return img


def make_icon(size: int, round_icon: bool) -> Image.Image:
    mark = draw_mark(size)
    mask = circle_mask(size) if round_icon else squircle_mask(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(mark, (0, 0), mask)
    return out


def make_banner(w: int = 320, h: int = 180) -> Image.Image:
    """TV Launcher banner：320x180，深色底 + 居中标志。"""
    img = Image.new("RGBA", (w, h), BG)
    draw = ImageDraw.Draw(img)

    tri_h = h * 0.36
    tri_w = tri_h * 0.86
    cx = w * 0.5
    cy = h * 0.46
    left = cx - tri_w / 2.2
    draw.polygon(
        [(left, cy - tri_h / 2), (left, cy + tri_h / 2), (left + tri_w, cy)],
        fill=WHITE,
    )
    bar_w = w * 0.18
    bar_h = 4
    draw.rounded_rectangle(
        ((w - bar_w) / 2, h * 0.70, (w + bar_w) / 2, h * 0.70 + bar_h),
        radius=bar_h / 2,
        fill=YELLOW,
    )
    return img


def main() -> None:
    for density, size in DENSITIES.items():
        out_dir = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)

        make_icon(size, False).save(os.path.join(out_dir, "ic_launcher.png"))
        make_icon(size, True).save(os.path.join(out_dir, "ic_launcher_round.png"))
        print(f"  mipmap-{density}: ic_launcher {size}x{size}")

    banner_dir = os.path.join(RES, "drawable-nodpi")
    os.makedirs(banner_dir, exist_ok=True)
    make_banner().save(os.path.join(banner_dir, "app_banner.png"))
    print("  drawable-nodpi: app_banner 320x180")

    # 顺带给上架物料出一张 512 预览
    preview = os.path.join(ROOT, "docs")
    os.makedirs(preview, exist_ok=True)
    make_icon(512, False).save(os.path.join(preview, "icon_512.png"))
    print("  docs/icon_512.png")


if __name__ == "__main__":
    main()
