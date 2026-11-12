"""Props: a block, an item sprite or a 3D model (.glb/.gltf/.obj) placed in the scene.

  объект Ракета: модель=rocket.glb размер=3 место=5,0
  объект Ящик: блок=тнт место=2,1
  объект Торт: предмет=торт место=0,2 размер=0.8
"""
import math
import os

import numpy as np
from PIL import Image

from . import world as W


def _quad(cs, uvs, n):
    out = []
    for k in (0, 3, 2, 0, 2, 1):
        out.append((*cs[k], *n, *uvs[k], 1.0))
    return out


def block_prop(block):
    """unit cube (1 block), base at y=0, centred; uses the world atlas uvs."""
    tiles = W.BLOCKS.get(block) or (block,) * 3
    v = []
    if block in getattr(W, "PANES", ()):
        # iron bars / pane: one thin plane along X, both sides
        u0, v0, u1, v1 = W.tile_uv(tiles[1])
        uv = [(u0, v0), (u1, v0), (u1, v1), (u0, v1)]
        v += _quad([(-0.5, 1, 0), (0.5, 1, 0), (0.5, 0, 0), (-0.5, 0, 0)], uv, (0, 0, 1))
        v += _quad([(0.5, 1, 0), (-0.5, 1, 0), (-0.5, 0, 0), (0.5, 0, 0)], uv[1::-1] + uv[:1:-1], (0, 0, -1))
        return np.array(v, np.float32)
    x0, x1, y0, y1, z0, z1 = -0.5, 0.5, 0.0, 1.0, -0.5, 0.5
    faces = [((0, 1, 0), [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], 0),
             ((0, -1, 0), [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], 2),
             ((0, 0, 1), [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)], 1),
             ((0, 0, -1), [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)], 1),
             ((1, 0, 0), [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)], 1),
             ((-1, 0, 0), [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)], 1)]
    for n, cs, ti in faces:
        u0, v0, u1, v1 = W.tile_uv(tiles[ti])
        v += _quad(cs, [(u0, v0), (u1, v0), (u1, v1), (u0, v1)], n)
    return np.array(v, np.float32)


def item_prop():
    """two crossed quads (1x1 block) for an item texture."""
    v = []
    for a in (math.pi / 4, -math.pi / 4):
        ex, ez = math.cos(a) * 0.5, math.sin(a) * 0.5
        cs = [(-ex, 1, -ez), (ex, 1, ez), (ex, 0, ez), (-ex, 0, -ez)]
        v += _quad(cs, [(0, 0), (1, 0), (1, 1), (0, 1)], (0, 1, 0))
    v = np.array(v, np.float32)
    v[:, 8] = 2.0  # flat-lit (emissive flag) so sprites never look muddy
    return v


def load_model(path, height=1.0):
    """Returns list of (verts float32 (N,9), texture RGBA array). Model is scaled to `height` blocks,
    base at y=0, centred in x/z, facing +Z as authored (glTF convention)."""
    import trimesh
    sc = trimesh.load(path, force="scene")
    meshes = sc.dump() if hasattr(sc, "dump") else [sc]
    allv = np.concatenate([m.vertices for m in meshes])
    lo, hi = allv.min(0), allv.max(0)
    s = height / max(1e-6, hi[1] - lo[1])
    c = (lo + hi) / 2
    out = []
    for m in meshes:
        V = (m.vertices - [c[0], lo[1], c[2]]) * s
        F = m.faces
        try:
            N = m.vertex_normals
        except Exception:
            N = np.zeros_like(V); N[:, 1] = 1
        tex = None
        uv = None
        vis = m.visual
        if getattr(vis, "kind", None) == "texture" and getattr(vis, "uv", None) is not None:
            mat = vis.material
            img = getattr(mat, "baseColorTexture", None) or getattr(mat, "image", None)
            if img is not None:
                tex = np.array(img.convert("RGBA"))
                uv = np.array(vis.uv, np.float32)
                uv[:, 1] = 1 - uv[:, 1]
            else:
                col = getattr(mat, "baseColorFactor", None)
                if col is None:
                    col = getattr(mat, "main_color", [200, 200, 200, 255])
                col = np.array(col, float)
                if col.max() <= 1.0:
                    col = col * 255
                tex = np.zeros((1, 1, 4), np.uint8); tex[0, 0] = np.clip(col[:4] if len(col) >= 4 else [*col[:3], 255], 0, 255)
                uv = np.full((len(V), 2), 0.5, np.float32)
        if tex is None:  # vertex/face colours -> palette texture
            try:
                cols = np.array(vis.vertex_colors)[:, :4]
            except Exception:
                cols = np.tile([200, 200, 200, 255], (len(V), 1))
            pal, inv = np.unique(cols, axis=0, return_inverse=True)
            pal = pal[:1024]
            tex = np.zeros((1, len(pal), 4), np.uint8); tex[0] = pal
            uv = np.stack([(inv.reshape(-1) % len(pal) + 0.5) / len(pal), np.full(len(V), 0.5)], 1).astype(np.float32)
        tri = F.reshape(-1)
        v = np.zeros((len(tri), 9), np.float32)
        v[:, 0:3] = V[tri]; v[:, 3:6] = N[tri]; v[:, 6:8] = uv[tri]; v[:, 8] = 1.0
        # flip texture to the renderer's top-left convention is handled by uv flip above
        if tex.shape[0] > 1024 or tex.shape[1] > 1024:
            im = Image.fromarray(tex); im.thumbnail((1024, 1024)); tex = np.array(im)
        out.append((v, tex))
    return out
