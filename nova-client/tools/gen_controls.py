"""NOVA custom controls (octa4): the atlas of new button pictures, drawn by geometry in the style of 0.14 buttons.

Writes assets_new/images/gui/nova/controls.png (256x256) and native/controls_atlas.h (uv of every sprite).
Buttons: the stone base of a 0.14 arrow button (gui.png 0,107 26x26) with its glyph removed, and a flat dark glyph
(37..45 grey, like the arrows) drawn from polygons on a 22x22 grid. "On" states use the NOVA mint.
"""
import math, sys
from PIL import Image, ImageDraw

GUI = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/apk/x/assets/images/gui/gui.png'
OUT = 'assets_new/images/gui/nova/controls.png'
HDR = 'native/controls_atlas.h'
DARK = (40, 40, 40, 255)
MINT = (96, 222, 206, 255)

gui = Image.open(GUI).convert('RGBA')


def blank26():
    """the up-arrow cell with the arrow painted over by the button's own stone colours"""
    c = gui.crop((0, 107, 26, 133)).copy()
    p = c.load()
    pal = [p[x, y] for y in range(18, 20) for x in range(4, 22)]   # clean stone rows under the arrow
    ok = set(pal)
    k = 0
    for y in range(5, 18):                                          # the arrow and its soft edge
        for x in range(5, 21):
            if p[x, y] not in ok:
                p[x, y] = pal[(x * 7 + y * 13 + k) % len(pal)]
                k += 1
    return c


BASE = blank26()


def glyph(draw_fn, color=DARK):
    """a 26x26 button: stone base + glyph drawn on the 22x22 face (offset 2,2)"""
    g = Image.new('RGBA', (22, 22), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    draw_fn(d, color)
    b = BASE.copy()
    b.alpha_composite(g, (2, 2))
    return b


def poly(d, pts, c):
    d.polygon([(round(x), round(y)) for x, y in pts], fill=c)


# ---- glyphs (22x22 face; the vanilla arrow glyph spans about 5..17) ----
def person_stand(d, c):
    d.rectangle([9, 3, 12, 6], fill=c)          # head
    d.rectangle([9, 8, 12, 13], fill=c)         # body
    d.rectangle([7, 8, 7, 12], fill=c)          # arms
    d.rectangle([14, 8, 14, 12], fill=c)
    d.rectangle([9, 14, 10, 18], fill=c)        # legs
    d.rectangle([11, 14, 12, 18], fill=c)


def person_crouch(d, c):
    d.rectangle([9, 6, 12, 9], fill=c)          # head, lower
    d.rectangle([9, 10, 12, 14], fill=c)        # shorter body
    d.rectangle([7, 10, 7, 13], fill=c)         # arms
    d.rectangle([14, 10, 14, 13], fill=c)
    d.rectangle([8, 15, 10, 15], fill=c)        # thighs out
    d.rectangle([11, 15, 13, 15], fill=c)
    d.rectangle([7, 16, 8, 18], fill=c)         # shins
    d.rectangle([13, 16, 14, 18], fill=c)


def sword(d, c):
    # blade from the bottom-left to the top-right corner, 3 px wide, a point at the end
    a = math.radians(-45)
    ux, uy = math.cos(a), math.sin(a)           # along the blade (up-right)
    vx, vy = -uy, ux                             # across
    def P(s, t, ox=8.0, oy=14.0):
        return (ox + ux * s + vx * t, oy + uy * s + vy * t)
    poly(d, [P(0, -1.6), P(8.5, -1.6), P(10.5, 0), P(8.5, 1.6), P(0, 1.6)], c)   # blade + point
    poly(d, [P(-0.8, -4.2), P(0.9, -4.2), P(0.9, 4.2), P(-0.8, 4.2)], c)          # guard
    poly(d, [P(-4.6, -1.1), P(-0.8, -1.1), P(-0.8, 1.1), P(-4.6, 1.1)], c)        # grip
    poly(d, [P(-6.2, -1.7), P(-4.6, -1.7), P(-4.6, 1.7), P(-6.2, 1.7)], c)        # pommel


def hand(d, c):
    d.rectangle([7, 10, 14, 17], fill=c)        # palm
    d.rectangle([7, 5, 8, 10], fill=c)          # fingers
    d.rectangle([9, 3, 10, 10], fill=c)
    d.rectangle([11, 4, 12, 10], fill=c)
    d.rectangle([13, 6, 14, 10], fill=c)
    poly(d, [(4, 10), (6, 10), (7, 12), (7, 15), (5, 13)], c)   # thumb
    d.rectangle([8, 17, 13, 18], fill=c)        # wrist


def chevrons(d, c):
    for y0 in (4, 10):                          # two chevrons pointing up (forward, fast)
        poly(d, [(11, y0), (17, y0 + 6), (14, y0 + 6), (11, y0 + 3), (8, y0 + 6), (5, y0 + 6)], c)


def eye(d, c):
    pts_top = [(4 + i, 11 - 5 * math.sin(math.pi * i / 14)) for i in range(15)]
    pts_bot = [(18 - i, 11 + 5 * math.sin(math.pi * i / 14)) for i in range(15)]
    poly(d, pts_top + pts_bot, c)
    hole = (0, 0, 0, 0)
    pts_top = [(6 + i, 11 - 3 * math.sin(math.pi * i / 10)) for i in range(11)]
    pts_bot = [(16 - i, 11 + 3 * math.sin(math.pi * i / 10)) for i in range(11)]
    poly(d, pts_top + pts_bot, hole)
    d.ellipse([9, 9, 13, 13], fill=c)           # pupil


def drop(d, c):
    d.rectangle([10, 3, 12, 10], fill=c)        # shaft
    poly(d, [(6, 10), (16, 10), (11, 15)], c)   # head
    d.rectangle([5, 17, 17, 18], fill=c)        # the ground


def carve_holes(img):
    """glyph pixels painted transparent (eye white) show the stone base again"""
    return img


SPRITES = {}
atlas = Image.new('RGBA', (256, 256), (0, 0, 0, 0))


def put(name, img, x, y):
    atlas.alpha_composite(img, (x, y))
    SPRITES[name] = (x, y, img.width, img.height)


def eye_button():
    g = Image.new('RGBA', (22, 22), (0, 0, 0, 0))
    d = ImageDraw.Draw(g)
    eye(d, DARK)
    # eye white: transparent ring -> stone shows through
    b = BASE.copy()
    b.alpha_composite(g, (2, 2))
    return b


row = [('blank', BASE), ('sneak_stand', glyph(person_stand)), ('sneak_crouch', glyph(person_crouch, MINT)),
       ('attack', glyph(sword)), ('interact', glyph(hand)), ('sprint_off', glyph(chevrons)),
       ('sprint_on', glyph(chevrons, MINT)), ('f5', eye_button()), ('drop', glyph(drop))]
for i, (n, img) in enumerate(row):
    put(n, img, (i % 8) * 32, 32 * (i // 8))


# ---- joystick: a stone ring and a round stone knob, shaded like the buttons ----
def stone(x, y):
    return BASE.getpixel((4 + (x * 5 + y * 3) % 18, 18 + (x + y * 7) % 2))


def joy_base(S=64):
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    p = im.load()
    c = (S - 1) / 2
    R = S / 2 - 1
    for y in range(S):
        for x in range(S):
            r = math.hypot(x - c, y - c)
            if r > R:
                continue
            if r > R - 2:
                p[x, y] = (30, 30, 30, 255)                       # dark rim
            elif r > R - 4:
                p[x, y] = (150, 150, 150, 255) if (x - c) + (y - c) < 0 else (93, 93, 93, 255)   # bevel
            else:
                s = stone(x, y)
                k = 0.78
                p[x, y] = (int(s[0] * k), int(s[1] * k), int(s[2] * k), 255)
    # four small direction marks
    d = ImageDraw.Draw(im)
    m = (60, 60, 60, 255)
    for ang in range(4):
        a = math.radians(90 * ang)
        cx, cy = c + math.cos(a) * (R - 8), c - math.sin(a) * (R - 8)
        ux, uy = math.cos(a), -math.sin(a)
        vx, vy = -uy, ux
        poly(d, [(cx + ux * 3, cy + uy * 3), (cx - ux * 2 + vx * 3, cy - uy * 2 + vy * 3),
                 (cx - ux * 2 - vx * 3, cy - uy * 2 - vy * 3)], m)
    return im


def joy_knob(S=32):
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    p = im.load()
    c = (S - 1) / 2
    R = S / 2 - 1
    for y in range(S):
        for x in range(S):
            r = math.hypot(x - c, y - c)
            if r > R:
                continue
            if r > R - 1.6:
                p[x, y] = (30, 30, 30, 255)
            elif r > R - 3.4:
                p[x, y] = (171, 171, 171, 255) if (x - c) + (y - c) < 0 else (87, 87, 87, 255)
            else:
                s = stone(x, y)
                p[x, y] = (min(255, s[0] + 14), min(255, s[1] + 14), min(255, s[2] + 14), 255)
    return im


put('joy_base', joy_base(), 0, 64)
put('joy_knob', joy_knob(), 64, 64)


# ---- editor chrome, 16x16, white on transparent (tinted when drawn) ----
def chrome(fn):
    im = Image.new('RGBA', (64, 64), (0, 0, 0, 0))   # drawn 4x, then reduced: smooth edges
    d = ImageDraw.Draw(im)
    fn(d)
    return im.resize((16, 16), Image.LANCZOS)


W = (255, 255, 255, 255)
put('ic_close', chrome(lambda d: (d.line([(16, 16), (48, 48)], fill=W, width=8), d.line([(48, 16), (16, 48)], fill=W, width=8))), 96, 64)
put('ic_check', chrome(lambda d: d.line([(12, 34), (26, 48), (52, 18)], fill=W, width=8, joint='curve')), 112, 64)
put('ic_plus', chrome(lambda d: (d.line([(32, 12), (32, 52)], fill=W, width=8), d.line([(12, 32), (52, 32)], fill=W, width=8))), 128, 64)
put('ic_dot', chrome(lambda d: d.ellipse([4, 4, 60, 60], fill=W)), 144, 64)
put('ic_ring', chrome(lambda d: d.ellipse([6, 6, 58, 58], outline=W, width=6)), 160, 64)
put('ic_round', chrome(lambda d: d.rounded_rectangle([0, 0, 63, 63], radius=14, fill=W)), 176, 64)
put('white', Image.new('RGBA', (8, 8), W), 192, 64)

atlas.save(OUT)
with open(HDR, 'w') as f:
    f.write('/* generated by tools/gen_controls.py: sprites of gui/nova/controls.png (x, y, w, h) */\n')
    for n, (x, y, w, h) in SPRITES.items():
        f.write('#define CA_%s %d, %d, %d, %d\n' % (n.upper(), x, y, w, h))
atlas.resize((512, 512), Image.NEAREST).save('/tmp/controls_preview.png')
print(len(SPRITES), 'sprites')
