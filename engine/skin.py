"""Minecraft skin loading, face rig (blink + mouth) and player-model geometry."""
import numpy as np
from PIL import Image


def load_skin(path):
    im = Image.open(path).convert("RGBA")
    if im.size[0] != 64:
        im = im.resize((64, im.size[1] * 64 // im.size[0]), Image.NEAREST)
    if im.size == (64, 32):  # legacy skin -> 64x64
        new = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        new.paste(im, (0, 0))
        new.paste(im.crop((0, 16, 16, 32)), (16, 48))   # left leg from right leg
        new.paste(im.crop((40, 16, 56, 32)), (32, 48))  # left arm from right arm
        im = new
    arr = np.array(im)
    slim = arr[20, 54, 3] == 0 and arr[31, 55, 3] == 0 and arr[20, 50, 3] != 0
    return arr, bool(slim)


def _dist(a, b):
    return float(np.abs(np.asarray(a, float) - np.asarray(b, float)).sum())


def analyze_face(arr, eye_rows=None, mouth_row=None):
    face = arr[8:16, 8:16, :3].astype(int)
    skin = np.median(face[6:8].reshape(-1, 3), axis=0)

    def sclera(c):
        return c.min() > 165 and (c.max() - c.min()) < 45

    if eye_rows is None:
        rows = [r for r in range(2, 7) if any(sclera(face[r, c]) for c in (1, 2, 5, 6))]
        if not rows:
            rows = [r for r in range(3, 7)
                    if min(_dist(face[r, c], skin) for c in (1, 2, 5, 6)) > 90]
            rows = rows[:1] or [4]
        top, bot = min(rows), max(rows)
        # anime-style lashes: dark row right above eyes with skin above it
        if top >= 2 and all(face[top - 1, c].sum() < 200 for c in (1, 6)) \
                and _dist(face[top - 2, 1], skin) < 90:
            top -= 1
        eye_rows = list(range(top, bot + 1))
    if mouth_row is None:
        mouth_row = 7 if max(eye_rows) < 7 else min(7, max(eye_rows) + 1)
    return dict(skin=skin, eye_rows=eye_rows, mouth_row=mouth_row)


def face_variants(arr, info):
    """Return dict[(eyes_closed:int, mouth:int)] -> RGBA array."""
    skin = info["skin"]
    lid = (skin * 0.45).astype(np.uint8)
    rows = info["eye_rows"]
    mr = info["mouth_row"]
    dark = np.array([70, 22, 30], np.uint8)
    tongue = np.array([170, 60, 70], np.uint8)
    out = {}
    for closed in (0, 1):
        for mouth in (0, 1, 2):
            a = arr.copy()
            if closed:
                for r in rows:
                    for c in (1, 2, 5, 6):
                        a[8 + r, 8 + c, :3] = skin
                line = rows[-2] if len(rows) >= 3 else rows[-1]
                for c in (1, 2, 5, 6):
                    a[8 + line, 8 + c, :3] = lid
            px = []
            if mouth == 1:
                px = [(mr, 3, dark), (mr, 4, dark)]
            elif mouth == 2:
                px = [(mr, 2, dark), (mr, 3, tongue), (mr, 4, tongue), (mr, 5, dark),
                      (mr - 1, 3, dark), (mr - 1, 4, dark)]
            for r, c, col in px:
                a[8 + r, 8 + c, :3] = col
                a[8 + r, 8 + c, 3] = 255
                a[8 + r, 40 + c, 3] = 0  # clear hat layer over mouth
            out[(closed, mouth)] = a
    return out


# ---------------------------------------------------------------- geometry
def box(x0, y0, z0, x1, y1, z1, u, v, w, h, d, inflate=0.0, tex=64.0):
    """Minecraft-layout box. Returns float32 array (N,8): pos3 normal3 uv2."""
    i = inflate
    x0, y0, z0, x1, y1, z1 = x0 - i, y0 - i, z0 - i, x1 + i, y1 + i, z1 + i
    faces = [
        # corners TL,TR,BR,BL as seen from outside ; uv rect ; normal
        (((x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)), (u + d, v + d, u + d + w, v + d + h), (0, 0, 1)),
        (((x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)), (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h), (0, 0, -1)),
        (((x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)), (u, v + d, u + d, v + d + h), (-1, 0, 0)),
        (((x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)), (u + d + w, v + d, u + 2 * d + w, v + d + h), (1, 0, 0)),
        (((x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)), (u + d, v, u + d + w, v + d), (0, 1, 0)),
        (((x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)), (u + d + w, v, u + d + 2 * w, v + d), (0, -1, 0)),
    ]
    out = []
    for (tl, tr, br, bl), (u0, v0, u1, v1), n in faces:
        uv = {0: (u0, v0), 1: (u1, v0), 2: (u1, v1), 3: (u0, v1)}
        cs = (tl, tr, br, bl)
        for k in (0, 3, 2, 0, 2, 1):
            out.append((*cs[k], *n, uv[k][0] / tex, uv[k][1] / tex))
    return np.array(out, np.float32)


def player_parts(slim=False):
    """Part name -> (pivot px, base verts, overlay verts). Model faces +Z, feet at y=0, 1px units."""
    aw = 3 if slim else 4
    P = {}
    P["head"] = ((0, 24, 0), box(-4, 24, -4, 4, 32, 4, 0, 0, 8, 8, 8),
                 box(-4, 24, -4, 4, 32, 4, 32, 0, 8, 8, 8, 0.5))
    P["body"] = ((0, 12, 0), box(-4, 12, -2, 4, 24, 2, 16, 16, 8, 12, 4),
                 box(-4, 12, -2, 4, 24, 2, 16, 32, 8, 12, 4, 0.25))
    P["rarm"] = ((-5, 22, 0), box(-4 - aw, 12, -2, -4, 24, 2, 40, 16, aw, 12, 4),
                 box(-4 - aw, 12, -2, -4, 24, 2, 40, 32, aw, 12, 4, 0.25))
    P["larm"] = ((5, 22, 0), box(4, 12, -2, 4 + aw, 24, 2, 32, 48, aw, 12, 4),
                 box(4, 12, -2, 4 + aw, 24, 2, 48, 48, aw, 12, 4, 0.25))
    P["rleg"] = ((-2, 12, 0), box(-4, 0, -2, 0, 12, 2, 0, 16, 4, 12, 4),
                 box(-4, 0, -2, 0, 12, 2, 0, 32, 4, 12, 4, 0.25))
    P["lleg"] = ((2, 12, 0), box(0, 0, -2, 4, 12, 2, 16, 48, 4, 12, 4),
                 box(0, 0, -2, 4, 12, 2, 0, 48, 4, 12, 4, 0.25))
    return P
