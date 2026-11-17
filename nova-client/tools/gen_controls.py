"""NOVA custom controls: the atlas of new button pictures, computed (not hand-drawn) in the style of 0.14 buttons.

Writes assets_new/images/gui/nova/controls.png (256x256) and native/controls_atlas.h (uv of every sprite).

Every shape is a function tested at pixel centres (x + 0.5, y + 0.5), so it comes out symmetric and centred.
Buttons are 26x26 like the game's dpad cells: the stone face of a 0.14 arrow button (gui.png 0,107) without its
arrow, and a flat dark glyph (the arrows' grey). The glyph centre is where the game's arrow sits: x 13, y 12 of the
cell (the face has a thicker bottom edge, so its visual middle is one pixel up). "On" states use the NOVA mint.
The joystick: square plates with rounded corners, a dark edge and a bevel like the game's buttons.
"""
import math, sys
from PIL import Image

GUI = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/apk/x/assets/images/gui/gui.png'
OUT = 'assets_new/images/gui/nova/controls.png'
HDR = 'native/controls_atlas.h'
DARK = (40, 40, 40, 255)
MID = (104, 104, 104, 255)
MINT = (96, 222, 206, 255)
CX, CY = 13.0, 12.0          # glyph centre in a 26x26 cell

gui = Image.open(GUI).convert('RGBA')


# ---------------- the stone button ----------------
def blank26():
    c = gui.crop((0, 107, 26, 133)).copy()
    p = c.load()
    pal = [p[x, y] for y in range(18, 20) for x in range(4, 22)]   # stone rows under the arrow
    ok = set(pal)
    for y in range(5, 18):
        for x in range(5, 21):
            if p[x, y] not in ok:
                p[x, y] = pal[(x * 7 + y * 13) % len(pal)]
    return c


BASE = blank26()


# ---------------- a tiny rasteriser: shapes are functions of a point ----------------
def fill(img, inside, color, box=None):
    p = img.load()
    W, H = img.size
    x0, y0, x1, y1 = box or (0, 0, W, H)
    for y in range(y0, y1):
        for x in range(x0, x1):
            if inside(x + 0.5, y + 0.5):
                p[x, y] = color


def rect(x0, y0, x1, y1):
    return lambda x, y: x0 <= x < x1 and y0 <= y < y1


def in_poly(pts):
    def f(x, y):
        c = False
        n = len(pts)
        for i in range(n):
            ax, ay = pts[i]
            bx, by = pts[(i + 1) % n]
            if (ay > y) != (by > y) and x < (bx - ax) * (y - ay) / (by - ay) + ax:
                c = not c
        return c
    return f


def union(*fs):
    return lambda x, y: any(f(x, y) for f in fs)


def minus(a, b):
    return lambda x, y: a(x, y) and not b(x, y)


def button(shapes):
    b = BASE.copy()
    for f, c in shapes:
        fill(b, f, c, (4, 3, 22, 21))
    return b


# ---------------- glyphs, all centred on (CX, CY) ----------------
def person(crouch):
    c = CX
    if not crouch:                                                # 4 + 6 + 6 rows, centred
        return union(rect(c - 2, 4, c + 2, 8),                    # head 4x4
                     rect(c - 3, 9, c + 3, 14),                   # body 6 wide
                     rect(c - 5, 9, c - 4, 13), rect(c + 4, 9, c + 5, 13),   # arms, a pixel away
                     rect(c - 3, 14, c - 1, 20), rect(c + 1, 14, c + 3, 20))  # legs
    return union(rect(c - 2, 7, c + 2, 11),
                 rect(c - 3, 12, c + 3, 16),
                 rect(c - 5, 12, c - 4, 16), rect(c + 4, 12, c + 5, 16),
                 rect(c - 5, 16, c - 1, 17), rect(c + 1, 16, c + 5, 17),      # thighs out
                 rect(c - 5, 17, c - 3, 20), rect(c + 3, 17, c + 5, 20))      # shins down


def sword():
    """along the diagonal from the bottom-left to the top-right through the centre"""
    s = math.sqrt(0.5)
    def uv(x, y):
        dx, dy = x - CX, y - CY
        return (dx - dy) * s, (dx + dy) * s       # u: along (up-right), v: across
    def f(x, y):
        u, v = uv(x, y)
        u -= 1.0                                   # balance the picture: the blade is longer than the grip
        if -1.5 <= u <= 4.8:
            return abs(v) <= 1.75                  # blade
        if 4.8 < u <= 8.0:
            return abs(v) <= 1.75 * (8.0 - u) / 3.2 + 0.05   # point
        if -3.2 <= u < -1.5:
            return abs(v) <= 4.6                   # guard
        if -6.2 <= u < -3.2:
            return abs(v) <= 1.05                  # grip
        if -8.2 <= u < -6.2:
            return abs(v) <= 2.0                   # pommel
        return False
    return f


def cube():
    """an isometric block: the top face mid grey, the sides dark"""
    c, t = CX, CY - 8.0
    top = in_poly([(c, t), (c + 7, t + 3.5), (c, t + 7), (c - 7, t + 3.5)])
    left = in_poly([(c - 7, t + 3.5), (c, t + 7), (c, t + 16), (c - 7, t + 12.5)])
    right = in_poly([(c, t + 7), (c + 7, t + 3.5), (c + 7, t + 12.5), (c, t + 16)])
    return top, union(left, right)


def chevrons():
    def chev(tip_y):                               # a ^ with straight 2.5-px arms, symmetric about CX
        h, w, t = 6.0, 7.0, 3.6
        return in_poly([(CX, tip_y), (CX + w, tip_y + h), (CX + w, tip_y + h + t * 0.9), (CX, tip_y + t),
                        (CX - w, tip_y + h + t * 0.9), (CX - w, tip_y + h)])
    return union(chev(CY - 8.0), chev(CY - 0.5))


def eye():
    def lens(rx, ry):
        def f(x, y):
            k = abs(x - CX) / rx
            return k <= 1 and abs(y - CY) <= ry * (1 - k * k) ** 0.75
        return f
    pupil = lambda x, y: (x - CX) ** 2 + (y - CY) ** 2 <= 2.5 ** 2
    return union(minus(lens(8.0, 5.2), lens(6.2, 3.5)), pupil)


def drop():
    return union(rect(CX - 1, CY - 8, CX + 1, CY - 1),                       # shaft
                 in_poly([(CX - 5, CY - 2), (CX + 5, CY - 2), (CX, CY + 3)]),  # head
                 rect(CX - 6, CY + 5, CX + 6, CY + 7))                       # the ground


SPRITES = {}
atlas = Image.new('RGBA', (256, 256), (0, 0, 0, 0))


def put(name, img, x, y):
    atlas.alpha_composite(img, (x, y))
    SPRITES[name] = (x, y, img.width, img.height)


cube_top, cube_sides = cube()
row = [('blank', BASE),
       ('sneak_stand', button([(person(False), DARK)])),
       ('sneak_crouch', button([(person(True), MINT)])),
       ('attack', button([(sword(), DARK)])),
       ('interact', button([(cube_top, MID), (cube_sides, DARK)])),
       ('sprint_off', button([(chevrons(), DARK)])),
       ('sprint_on', button([(chevrons(), MINT)])),
       ('f5', button([(eye(), DARK)])),
       ('drop', button([(drop(), DARK)]))]
for i, (n, img) in enumerate(row):
    put(n, img, (i % 8) * 32, 32 * (i // 8))


# ---------------- joystick: rounded square plates ----------------
def rounded(S, r, inset=0.0):
    a, b = inset, S - inset
    def f(x, y):
        if not (a <= x <= b and a <= y <= b):
            return False
        cx = min(max(x, a + r), b - r)
        cy = min(max(y, a + r), b - r)
        return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
    return f


STONE = [(126, 126, 126), (130, 130, 130), (126, 126, 126), (140, 140, 140), (126, 126, 126), (147, 147, 147), (126, 126, 126), (118, 118, 118)]


def stone(x, y, k):
    c = STONE[(x * 7 + y * 13 + (x * y) % 5) % len(STONE)]
    return (int(c[0] * k), int(c[1] * k), int(c[2] * k), 255)


def plate(S, r, k, marks):
    im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    p = im.load()
    edge = rounded(S, r)
    in1 = rounded(S, r - 2, 2)                # inside the dark edge (2 px)
    in2 = rounded(S, r - 4, 4)                # inside the bevel (2 px)
    for y in range(S):
        for x in range(S):
            X, Y = x + 0.5, y + 0.5
            if not edge(X, Y):
                continue
            if not in1(X, Y):
                p[x, y] = (24, 24, 24, 255)
            elif not in2(X, Y):
                light = X - 4 < S - Y - 4          # the top-left half of the ring is lit, as the game's buttons
                p[x, y] = (171, 171, 171, 255) if light else (80, 80, 80, 255)
            else:
                p[x, y] = stone(x, y, k)
    if marks:                                  # four arrows, exactly on the axes
        c = S / 2
        for dx, dy in ((0, -1), (1, 0), (0, 1), (-1, 0)):
            tip = (c + dx * (c - 8), c + dy * (c - 8))
            back = (c + dx * (c - 13), c + dy * (c - 13))
            ox, oy = -dy, dx
            fill(im, in_poly([tip, (back[0] + ox * 5, back[1] + oy * 5), (back[0] - ox * 5, back[1] - oy * 5)]), DARK)
    return im


put('joy_base', plate(64, 12, 0.82, True), 0, 64)
put('joy_knob', plate(30, 7, 1.06, False), 64, 64)


# ---------------- editor chrome, 16x16 white (tinted when drawn), smooth edges ----------------
def chrome(f):
    big = Image.new('RGBA', (64, 64), (0, 0, 0, 0))
    fill(big, f, (255, 255, 255, 255))
    return big.resize((16, 16), Image.LANCZOS)


def bar(ax, ay, bx, by, w):
    L = math.hypot(bx - ax, by - ay)
    ux, uy = (bx - ax) / L, (by - ay) / L
    def f(x, y):
        t = (x - ax) * ux + (y - ay) * uy
        if t < -w / 2 or t > L + w / 2:
            return False
        d = abs((x - ax) * uy - (y - ay) * ux)
        if t < 0: d = math.hypot(x - ax, y - ay)
        if t > L: d = math.hypot(x - bx, y - by)
        return d <= w / 2
    return f


W = (255, 255, 255, 255)
put('ic_close', chrome(union(bar(19, 19, 45, 45, 8), bar(45, 19, 19, 45, 8))), 96, 64)
put('ic_check', chrome(union(bar(15, 34, 27, 46, 8), bar(27, 46, 50, 20, 8))), 112, 64)
put('ic_plus', chrome(union(bar(32, 14, 32, 50, 8), bar(14, 32, 50, 32, 8))), 128, 64)
put('ic_dot', chrome(lambda x, y: (x - 32) ** 2 + (y - 32) ** 2 <= 28 ** 2), 144, 64)
put('ic_ring', chrome(lambda x, y: 22 ** 2 <= (x - 32) ** 2 + (y - 32) ** 2 <= 28 ** 2), 160, 64)
put('ic_round', chrome(rounded(64, 14)), 176, 64)
put('white', Image.new('RGBA', (8, 8), W), 192, 64)

atlas.save(OUT)
with open(HDR, 'w') as f:
    f.write('/* generated by tools/gen_controls.py: sprites of gui/nova/controls.png (x, y, w, h) */\n')
    for n, (x, y, w, h) in SPRITES.items():
        f.write('#define CA_%s %d, %d, %d, %d\n' % (n.upper(), x, y, w, h))
print(len(SPRITES), 'sprites')
