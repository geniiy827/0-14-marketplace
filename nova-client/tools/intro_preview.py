"""Software preview of the intro title mesh (z-buffer, alpha test). Mirrors IntroView math."""
import struct, sys, math
import numpy as np
from PIL import Image

def read_mesh(path):
    d = open(path, 'rb').read(); o = 6
    n = struct.unpack_from('<H', d, 4)[0]; gs = []
    for _ in range(n):
        ln = struct.unpack_from('<H', d, o)[0]; o += 2
        name = d[o:o+ln].decode(); o += ln
        c = struct.unpack_from('<3f', d, o); o += 12
        q = struct.unpack_from('<H', d, o)[0]; o += 2
        quads = []
        for _ in range(q):
            quads.append([struct.unpack_from('<5f', d, o + 20*k) for k in range(4)]); o += 80
        gs.append((name, c, quads))
    return gs

def raster(W, H, tris, tex, bg=(0, 0, 0)):
    T = np.asarray(tex).astype(np.float32) / 255; th, tw = T.shape[:2]
    col = np.zeros((H, W, 3), np.float32); col[:] = np.array(bg) / 255
    zb = np.full((H, W), 1e9, np.float32)
    for (p, uv, shade, alpha) in tris:       # p: 3x(sx,sy,w)  uv: 3x(u,v)
        xs = [a[0] for a in p]; ys = [a[1] for a in p]
        area = (p[1][0]-p[0][0])*(p[2][1]-p[0][1]) - (p[2][0]-p[0][0])*(p[1][1]-p[0][1])
        if area <= 0: continue                   # back face (screen y down, clockwise = front)
        x0, x1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
        y0, y1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
        if x0 > x1 or y0 > y1: continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        (ax, ay, aw), (bx, by, bw), (cx, cy, cw) = p
        den = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(den) < 1e-9: continue
        l0 = ((by - cy) * (gx - cx) + (cx - bx) * (gy - cy)) / den
        l1 = ((cy - ay) * (gx - cx) + (ax - cx) * (gy - cy)) / den
        l2 = 1 - l0 - l1
        m = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
        if not m.any(): continue
        iw = l0 / aw + l1 / bw + l2 / cw
        u = (l0 * uv[0][0] / aw + l1 * uv[1][0] / bw + l2 * uv[2][0] / cw) / iw
        v = (l0 * uv[0][1] / aw + l1 * uv[1][1] / bw + l2 * uv[2][1] / cw) / iw
        z = 1 / iw
        tx = np.clip((u * tw).astype(int), 0, tw - 1); ty = np.clip((v * th).astype(int), 0, th - 1)
        s = T[ty, tx]
        m &= s[..., 3] > 0.5
        sub = zb[y0:y1+1, x0:x1+1]
        m &= z < sub
        sub[m] = z[m]
        c = col[y0:y1+1, x0:x1+1]
        c[m] = c[m] * (1 - alpha) + s[..., :3][m] * shade * alpha
    return Image.fromarray((np.clip(col, 0, 1) * 255).astype(np.uint8))

def look_at(eye, tgt, up=(0, 1, 0)):
    eye, tgt, up = map(lambda a: np.array(a, float), (eye, tgt, up))
    f = tgt - eye; f /= np.linalg.norm(f)
    s = np.cross(f, up); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    M = np.eye(4); M[0, :3] = s; M[1, :3] = u; M[2, :3] = -f
    M[:3, 3] = -M[:3, :3] @ eye
    return M

def persp(fovy, aspect, n=1, f=2000):
    t = 1 / math.tan(math.radians(fovy) / 2)
    return np.array([[t / aspect, 0, 0, 0], [0, t, 0, 0], [0, 0, (f + n) / (n - f), 2 * f * n / (n - f)], [0, 0, -1, 0]])

def project(gs, mvps, W, H, shades=None, alphas=None):
    """mvps: dict group name -> 4x4 matrix (proj*view*model). returns tris for raster"""
    tris = []
    for gi, (n, c, qs) in enumerate(gs):
        M = mvps[n]
        for q in qs:
            P = []
            for v in q:
                x, y, z, w = M @ np.array([v[0], v[1], v[2], 1.0])
                if w <= 0.01: P = None; break
                P.append(((x / w * .5 + .5) * W, (1 - (y / w * .5 + .5)) * H, w))
            if P is None: continue
            UV = [(v[3], v[4]) for v in q]
            sh = shades[n] if shades else 1; al = alphas[n] if alphas else 1
            tris.append(([P[0], P[1], P[2]], [UV[0], UV[1], UV[2]], sh, al))
            tris.append(([P[0], P[2], P[3]], [UV[0], UV[2], UV[3]], sh, al))
    return tris
