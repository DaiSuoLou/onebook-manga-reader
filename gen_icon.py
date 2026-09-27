# -*- coding: utf-8 -*-
"""生成「一本书」自适应图标前景层：书形 + 白色「一本」二字（黑体）。"""
from PIL import Image, ImageDraw, ImageFont

S = 432  # xxxhdpi 母版尺寸 (108dp * 4)
FONT = "C:/Windows/Fonts/simhei.ttf"


def round_rect(draw, box, r, fill=None, outline=None, width=0):
    draw.rounded_rectangle(box, radius=r, fill=fill, outline=outline, width=width)


def vgradient(w, h, top, bottom):
    """竖直 alpha 渐变层 (RGBA)，top/bottom=(r,g,b,a)。"""
    img = Image.new("RGBA", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(top[0] + (bottom[0] - top[0]) * t)
        g = int(top[1] + (bottom[1] - top[1]) * t)
        b = int(top[2] + (bottom[2] - top[2]) * t)
        a = int(top[3] + (bottom[3] - top[3]) * t)
        for x in range(w):
            px[x, y] = (r, g, b, a)
    return img


def make_master():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 全书厚度/阴影层（深绿，偏右下）
    round_rect(d, [138, 112, 324, 352], 22, fill=(18, 78, 52, 255))

    # 书脊：左侧深色圆角竖带
    round_rect(d, [124, 94, 154, 338], 18, fill=(28, 105, 72, 255))

    # 主封面（竹绿圆角矩形，颜色更深更饱满）
    cover = (132, 100, 310, 330)
    round_rect(d, cover, 16, fill=(34, 158, 111, 255))

    # 封面顶部淡淡高光，只压在最上方，避免冲淡封面主色
    ImageDraw.Draw(img).rectangle([132, 100, 310, 122], fill=(255, 255, 255, 35))

    d = ImageDraw.Draw(img)

    # 右侧书页厚度和底部书页边
    d.line([(302, 115), (302, 325)], fill=(30, 110, 75, 255), width=3)
    d.line([(154, 316), (302, 316)], fill=(30, 110, 75, 255), width=3)

    # 「一本」二字：白字 + 深色细描边，居中于封面，避免越过封面
    f = ImageFont.truetype(FONT, 108)
    cx, cy = 223, 215
    for dx, dy in [(-2, 0), (2, 0), (0, -2), (0, 2)]:
        d.text((cx + dx, cy + dy), "一本", font=f, fill=(12, 48, 33, 255), anchor="mm")
    d.text((cx, cy), "一本", font=f, fill=(255, 255, 255, 255), anchor="mm")

    # 封面底部装饰线
    d.line([(170, 292), (276, 292)], fill=(255, 255, 255, 180), width=3)
    return img


def main():
    master = make_master()
    out_dir = "C:/Users/M/WorkBuddy/设计一个漫画app/icon_out"
    import os
    os.makedirs(out_dir, exist_ok=True)
    sizes = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for name, sz in sizes.items():
        im = master.resize((sz, sz), Image.LANCZOS)
        p = os.path.join(out_dir, f"ic_launcher_foreground_{name}.png")
        im.save(p)
        print("wrote", p, im.size)


if __name__ == "__main__":
    main()
