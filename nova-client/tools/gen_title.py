"""NOVA title picture of the start screen (assets_new/images/gui/title.png: 1024 x 512, the logo in the top
1024 x 373 that ui_new/start_screen.json shows): the 3D letters of the intro (gen_intro_mesh.py output) seen from
below as at the end of the intro, on a transparent background.
usage: gen_title.py title_mesh.bin title_tex.png out.png"""
import sys, math
import numpy as np
from PIL import Image
sys.path.insert(0, __import__('os').path.dirname(__file__))
from intro_preview import read_mesh, look_at, persp

ZS = -1.0                    # the intro camera looks from -z
SS = 4                       # supersampling
W, H = 1024, 373
CANVAS = (1024, 512)


def render(gs, tex, eye, tgt, fov):
    T = np.asarray(tex.convert('RGBA')).astype(np.float32) / 255
    th, tw = T.shape[:2]
    w, h = W * SS, H * SS
    col = np.zeros((h, w, 4), np.float32)
    zb = np.full((h, w), 1e9, np.float32)
    V = look_at(eye, tgt); P = persp(fov, W / H); M = P @ V
    for name, c, qs in gs:
        for q in qs:
            a, b, d = (np.array(q[i][:3]) for i in (0, 1, 2))
            n = np.cross(b - a, d - a); n /= (np.linalg.norm(n) or 1)
            # light like the Minecraft title: front full, top a bit darker, sides and bottom dark
            shade = 1.0                       # the texture has the light painted in (as in IntroView)
            pts = []
            for v in q:
                x, y, z, ww = M @ np.array([v[0], v[1], v[2], 1.0])
                pts.append(((x / ww * .5 + .5) * w, (1 - (y / ww * .5 + .5)) * h, ww, v[3], v[4]))
            for tri in ((0, 1, 2), (0, 2, 3)):
                p = [pts[i] for i in tri]
                (ax, ay, aw, au, av), (bx, by, bw, bu, bv), (cx, cy, cw, cu, cv) = p
                if (bx - ax) * (cy - ay) - (cx - ax) * (by - ay) <= 0: continue   # back face (as IntroView culls)
                x0, x1 = max(0, int(min(ax, bx, cx))), min(w - 1, int(max(ax, bx, cx)) + 1)
                y0, y1 = max(0, int(min(ay, by, cy))), min(h - 1, int(max(ay, by, cy)) + 1)
                if x0 > x1 or y0 > y1: continue
                den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
                if abs(den) < 1e-9: continue
                gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
                l0 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
                l1 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
                l2 = 1 - l0 - l1
                m = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
                if not m.any(): continue
                iw = l0 / aw + l1 / bw + l2 / cw
                u = (l0 * au / aw + l1 * bu / bw + l2 * cu / cw) / iw
                v = (l0 * av / aw + l1 * bv / bw + l2 * cv / cw) / iw
                z = 1 / iw
                tx = np.clip((u * tw).astype(int), 0, tw - 1); ty = np.clip((v * th).astype(int), 0, th - 1)
                s = T[ty, tx]
                m &= s[..., 3] > 0.5
                sub = zb[y0:y1 + 1, x0:x1 + 1]
                m &= z < sub
                sub[m] = z[m]
                cc = col[y0:y1 + 1, x0:x1 + 1]
                cc[m, :3] = s[..., :3][m] * shade
                cc[m, 3] = 1
    img = Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8), 'RGBA')
    return img.resize((W, H), Image.LANCZOS)


def main(mesh, texp, out):
    gs = read_mesh(mesh)
    allv = np.array([v[:3] for _, _, qs in gs for q in qs for v in q])
    lo, hi = allv.min(0), allv.max(0)
    ctr = (lo + hi) / 2
    width = hi[0] - lo[0]
    # the camera of the intro's last frame (IntroView: elevation 30 degrees from below, from -z), fov chosen so the
    # logo fills the picture's height
    fov, elev = 10.5, math.radians(30)          # the intro camera zoomed in (same place, narrower view)
    dist = 400.0
    eye = (ctr[0], ctr[1] - math.sin(elev) * dist, ctr[2] + ZS * math.cos(elev) * dist)
    img = render(gs, Image.open(texp), eye, tuple(ctr), fov)
    bb = img.getbbox()
    if bb:                                  # scale the logo to the picture's height, centred
        logo = img.crop(bb); k = min((H - 4) / logo.size[1], (W - 8) / logo.size[0])
        logo = logo.resize((max(1, int(logo.size[0] * k)), max(1, int(logo.size[1] * k))), Image.NEAREST)
        img = Image.new('RGBA', (W, H)); img.alpha_composite(logo, ((W - logo.size[0]) // 2, (H - logo.size[1]) // 2))
    full = Image.new('RGBA', CANVAS)
    full.alpha_composite(img, (0, 0))
    print('title', full.size, 'logo box', full.getbbox())
    full.save(out)


if __name__ == '__main__':
    main(*sys.argv[1:4])
