# Draws preview.c's commands with the real textures and the Minecraft font. usage: render.py root W H scale out.png < cmds
import sys
from PIL import Image
root, W, H, S, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
img = Image.new('RGBA', (W * S, H * S), (40, 44, 52, 255))
cache = {}
def tex(p):
    if p not in cache:
        im = None
        for ext in ('.png', '.jpg'):
            try:
                im = Image.open(root + '/' + p + ext).convert('RGBA'); break
            except FileNotFoundError:
                pass
        cache[p] = im
    return cache[p]
ascii_font = Image.open(root + '/font/default8.png').convert('RGBA')
pages = {}
widths = {}
for l in open(sys.argv[6] if len(sys.argv) > 6 else 'widths.txt'):
    a, b = l.split(); widths[int(a)] = int(b)
def glyph(cp):
    if cp < 256:
        return ascii_font.crop(((cp % 16) * 8, (cp // 16) * 8, (cp % 16) * 8 + 8, (cp // 16) * 8 + 8)), 1
    pg = cp >> 8
    if pg not in pages:
        try: pages[pg] = Image.open(root + '/font/glyph_%02X.png' % pg).convert('RGBA')
        except FileNotFoundError: pages[pg] = None
    p = pages[pg]
    if p is None: return None, 1
    i = cp & 255
    g = p.crop(((i % 16) * 16, (i // 16) * 16, (i % 16) * 16 + 16, (i // 16) * 16 + 16))
    bb = g.getbbox()
    if bb: g = g.crop((bb[0], 0, 16, 16))
    return g, 0.5
def tint(im, c):
    r, g, b, a = im.split()
    from PIL import ImageChops
    mul = Image.new('RGBA', im.size, tuple(int(255 * x) for x in c))
    return ImageChops.multiply(im, mul)
def draw_text(s, x, y, sc, c, sh):
    if sh: draw_text(s, x + 1, y + 1, sc, (c[0] * .25, c[1] * .25, c[2] * .25, c[3]), 0)
    cx = x
    i = 0
    while i < len(s):
        ch = s[i]; i += 1
        if ch == '§': i += 1; continue
        cp = ord(ch)
        g, k = glyph(cp)
        w = widths.get(cp, 6)
        if g is not None and cp != 32:
            gw, gh = g.size
            gs = g.resize((max(1, int(gw * k * sc * S)), max(1, int(gh * k * sc * S))), Image.NEAREST)
            gs = tint(gs, c)
            img.alpha_composite(gs, (int(cx * S), int(y * S)))
        cx += w * sc
for line in sys.stdin:
    f = line.rstrip('\n').split('\t')
    if f[0] == 'I':
        t = tex(f[1])
        if t is None: continue
        x, y, w, h, u0, v0, u1, v1, r, g, b, a = map(float, f[2:14])
        tw, th = t.size
        box = (int(round(u0 * tw)), int(round(v0 * th)), max(int(round(u1 * tw)), int(round(u0 * tw)) + 1), max(int(round(v1 * th)), int(round(v0 * th)) + 1))
        part = t.crop(box)
        W2, H2 = int(round((x + w) * S)) - int(round(x * S)), int(round((y + h) * S)) - int(round(y * S))
        if W2 <= 0 or H2 <= 0: continue
        part = part.resize((W2, H2), Image.NEAREST)
        if (r, g, b, a) != (1, 1, 1, 1): part = tint(part, (r, g, b, a))
        img.alpha_composite(part, (int(round(x * S)), int(round(y * S))))
    elif f[0] == 'T':
        x, y, sc, r, g, b, a = map(float, f[1:8])
        draw_text(f[9], x, y, sc, (r, g, b, a), int(f[8]))
    elif f[0] == 'C':
        from PIL import ImageDraw
        d = ImageDraw.Draw(img)
        x, y, w, h = map(float, f[2:6])
        d.rectangle((x * S, y * S, (x + w) * S, (y + h) * S), outline=(255, 0, 255, 160))
        d.text((x * S + 2, y * S + 2), f[1], fill=(255, 0, 255, 200))
img.save(out)
