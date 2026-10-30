"""NOVA launcher icon in the style of the original MCPE icon: grass block side, NOVA logo, "CLIENT" in the game font.
Writes res_new/<density>/icon.png (+ drawable/iconx.png 512) with the original rounded mask."""
import os, sys
from PIL import Image, ImageDraw, ImageFilter
W = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK = '/home/claude/apk/x'
OUT = os.path.join(W, 'res_new')

def make():
    S = 512
    orig = Image.open(os.path.join(APK, 'res/drawable/iconx.png')).convert('RGBA')
    side = Image.open(os.path.join(W, 'assets_new/textures/blocks/grass/7.png')).convert('RGBA')
    bg = side.resize((S, S), Image.NEAREST)
    shade = Image.new('RGBA', (S, S), (0, 0, 0, 0)); sd = ImageDraw.Draw(shade)
    for y in range(S): sd.line([(0, y), (S, y)], fill=(0, 0, 0, int(70 * max(0, (y - 140) / 372))))
    bg = Image.alpha_composite(bg, shade)
    title = Image.open(os.path.join(W, 'assets_new/images/gui/title.png')).convert('RGBA').crop((0, 0, 1024, 373))
    tw = 440; th = int(373 * tw / 1024)
    t = title.resize((tw, th), Image.LANCZOS)
    sh = Image.new('RGBA', t.size, (0, 0, 0, 0)); sh.putalpha(t.split()[3].point(lambda v: int(v * 0.6)))
    sh = sh.filter(ImageFilter.GaussianBlur(6))
    y0 = (S - th) // 2 + 10
    bg.alpha_composite(sh, ((S - tw) // 2 + 4, y0 + 10))
    bg.alpha_composite(t, ((S - tw) // 2, y0))
    font = Image.open(os.path.join(APK, 'assets/images/font/default8.png')).convert('RGBA')
    txt = 'CLIENT'; cw = font.width // 16; sc = 4; gw = cw * sc
    lab = Image.new('RGBA', (gw * len(txt), gw), (0, 0, 0, 0))
    for i, c in enumerate(txt):
        g = font.crop(((ord(c) % 16) * cw, (ord(c) // 16) * cw, (ord(c) % 16 + 1) * cw, (ord(c) // 16 + 1) * cw))
        lab.alpha_composite(g.resize((gw, gw), Image.NEAREST), (i * gw - i * sc, 0))
    col = Image.new('RGBA', lab.size, (130, 245, 230, 255)); col.putalpha(lab.split()[3])
    shd = Image.new('RGBA', lab.size, (20, 30, 40, 255)); shd.putalpha(lab.split()[3])
    x = (S - lab.width) // 2 + 8; yy = y0 + th + 6
    bg.alpha_composite(shd, (x + sc, yy + sc)); bg.alpha_composite(col, (x, yy))
    bg.putalpha(orig.split()[3])
    return bg

if __name__ == '__main__':
    big = make()
    sizes = {'drawable-ldpi-v4': 36, 'drawable-mdpi-v4': 48, 'drawable-hdpi-v4': 72, 'drawable-xhdpi-v4': 96,
             'drawable-xxhdpi-v4': 144, 'drawable-xxxhdpi-v4': 192}
    for d, n in sizes.items():
        os.makedirs(os.path.join(OUT, d), exist_ok=True)
        big.resize((n, n), Image.LANCZOS).save(os.path.join(OUT, d, 'icon.png'))
    os.makedirs(os.path.join(OUT, 'drawable'), exist_ok=True)
    big.save(os.path.join(OUT, 'drawable', 'iconx.png'))
