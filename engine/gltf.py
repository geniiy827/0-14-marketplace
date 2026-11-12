"""Minimal glTF 2.0 (.glb) writer: meshes, textures, node hierarchy, cameras, TRS animations."""
import io
import json
import struct

import numpy as np
from PIL import Image

FLOAT, USHORT, UINT = 5126, 5123, 5125
NEAREST, LINEAR, CLAMP, REPEAT = 9728, 9729, 33071, 10497


class GLB:
    def __init__(self):
        self.j = {"asset": {"version": "2.0", "generator": "mcanim-storycraft"}, "scene": 0,
                  "scenes": [{"nodes": []}], "nodes": [], "meshes": [], "materials": [], "textures": [],
                  "images": [], "samplers": [{"magFilter": NEAREST, "minFilter": NEAREST, "wrapS": REPEAT, "wrapT": REPEAT},
                                             {"magFilter": LINEAR, "minFilter": LINEAR, "wrapS": REPEAT, "wrapT": REPEAT}],
                  "buffers": [], "bufferViews": [], "accessors": [], "animations": [], "cameras": []}
        self.bin = bytearray()
        self._tex_cache = {}

    def _view(self, data, target=None):
        while len(self.bin) % 4:
            self.bin.append(0)
        off = len(self.bin)
        self.bin += data
        v = {"buffer": 0, "byteOffset": off, "byteLength": len(data)}
        if target:
            v["target"] = target
        self.j["bufferViews"].append(v)
        return len(self.j["bufferViews"]) - 1

    def accessor(self, arr, typ, minmax=False, target=None):
        arr = np.ascontiguousarray(arr, np.float32)
        n = arr.shape[0]
        a = {"bufferView": self._view(arr.tobytes(), target), "componentType": FLOAT, "count": int(n), "type": typ}
        if minmax:
            a["min"] = [float(x) for x in arr.reshape(n, -1).min(0)]
            a["max"] = [float(x) for x in arr.reshape(n, -1).max(0)]
        self.j["accessors"].append(a)
        return len(self.j["accessors"]) - 1

    def texture(self, rgba, key=None, smooth=False):
        if key is not None and key in self._tex_cache:
            return self._tex_cache[key]
        im = rgba if isinstance(rgba, Image.Image) else Image.fromarray(np.ascontiguousarray(rgba, np.uint8))
        buf = io.BytesIO(); im.save(buf, "PNG")
        self.j["images"].append({"bufferView": self._view(buf.getvalue()), "mimeType": "image/png"})
        self.j["textures"].append({"source": len(self.j["images"]) - 1, "sampler": 1 if smooth else 0})
        idx = len(self.j["textures"]) - 1
        if key is not None:
            self._tex_cache[key] = idx
        return idx

    def material(self, tex, alpha="MASK", name="mat", unlit=False, cutoff=0.5):
        m = {"name": name, "pbrMetallicRoughness": {"baseColorTexture": {"index": tex}, "metallicFactor": 0.0,
                                                     "roughnessFactor": 1.0},
             "alphaMode": alpha, "doubleSided": True}
        if alpha == "MASK":
            m["alphaCutoff"] = cutoff
        if unlit:
            m["extensions"] = {"KHR_materials_unlit": {}}
            self.j.setdefault("extensionsUsed", [])
            if "KHR_materials_unlit" not in self.j["extensionsUsed"]:
                self.j["extensionsUsed"].append("KHR_materials_unlit")
        self.j["materials"].append(m)
        return len(self.j["materials"]) - 1

    def mesh(self, name, v, mat, color=None):
        """v: (N, >=8) float array: pos3 normal3 uv2 [ao]. Non-indexed triangles."""
        v = np.asarray(v, np.float32)
        attrs = {"POSITION": self.accessor(v[:, 0:3], "VEC3", minmax=True, target=34962),
                 "NORMAL": self.accessor(self._norm(v[:, 3:6]), "VEC3", target=34962),
                 "TEXCOORD_0": self.accessor(v[:, 6:8], "VEC2", target=34962)}
        if color is not None:
            attrs["COLOR_0"] = self.accessor(color, "VEC4", target=34962)
        self.j["meshes"].append({"name": name, "primitives": [{"attributes": attrs, "material": mat, "mode": 4}]})
        return len(self.j["meshes"]) - 1

    @staticmethod
    def _norm(n):
        n = np.array(n, np.float32)
        ln = np.linalg.norm(n, axis=1, keepdims=True)
        ln[ln == 0] = 1
        return n / ln

    def node(self, name, mesh=None, t=None, r=None, s=None, children=None, camera=None, root=True, extras=None):
        nd = {"name": name}
        if mesh is not None:
            nd["mesh"] = mesh
        if camera is not None:
            nd["camera"] = camera
        if t is not None:
            nd["translation"] = [float(x) for x in t]
        if r is not None:
            nd["rotation"] = [float(x) for x in r]
        if s is not None:
            nd["scale"] = [float(x) for x in s]
        if children:
            nd["children"] = children
        if extras:
            nd["extras"] = extras
        self.j["nodes"].append(nd)
        idx = len(self.j["nodes"]) - 1
        if root:
            self.j["scenes"][0]["nodes"].append(idx)
        return idx

    def camera(self, yfov=0.8, aspect=16 / 9):
        self.j["cameras"].append({"type": "perspective", "perspective": {"yfov": yfov, "aspectRatio": aspect,
                                                                        "znear": 0.08, "zfar": 220.0}})
        return len(self.j["cameras"]) - 1

    def animation(self, name, times, channels):
        """channels: list of (node, path, values ndarray[, own times]).
        A channel may carry its own times (used for hard cuts: a duplicated key 1 ms before
        the jump keeps LINEAR interpolation from inventing in-between frames)."""
        shared = self.accessor(np.asarray(times, np.float32).reshape(-1, 1), "SCALAR", minmax=True)
        own = {}
        anim = {"name": name, "samplers": [], "channels": []}
        for ch in channels:
            node, path, vals = ch[:3]
            typ = "VEC4" if path == "rotation" else "VEC3"
            out = self.accessor(vals, typ)
            inp = shared
            if len(ch) > 3:
                key = np.asarray(ch[3], np.float32).tobytes()
                if key not in own:
                    own[key] = self.accessor(np.asarray(ch[3], np.float32).reshape(-1, 1), "SCALAR", minmax=True)
                inp = own[key]
            anim["samplers"].append({"input": inp, "output": out, "interpolation": "LINEAR"})
            anim["channels"].append({"sampler": len(anim["samplers"]) - 1, "target": {"node": node, "path": path}})
        self.j["animations"].append(anim)

    def save(self, path):
        j = {k: v for k, v in self.j.items() if v != [] or k in ("scenes", "nodes")}
        j["buffers"] = [{"byteLength": len(self.bin)}]
        js = json.dumps(j, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        while len(js) % 4:
            js += b" "
        while len(self.bin) % 4:
            self.bin.append(0)
        total = 12 + 8 + len(js) + 8 + len(self.bin)
        with open(path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total))
            f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942)); f.write(self.bin)


def decompose(M):
    """4x4 -> (t, quat xyzw, scale) via polar decomposition (tolerates mild shear)."""
    M = np.asarray(M, np.float64)
    t = M[:3, 3]
    A = M[:3, :3]
    sc = np.linalg.norm(A, axis=0)
    if sc.min() < 1e-9:
        return t, np.array([0, 0, 0, 1.0]), np.zeros(3)
    U, _, Vt = np.linalg.svd(A)
    R = U @ Vt
    if np.linalg.det(R) < 0:
        U[:, -1] *= -1; R = U @ Vt
    s = np.diag(R.T @ A)
    return t, mat_to_quat(R), s


def mat_to_quat(R):
    tr = R[0, 0] + R[1, 1] + R[2, 2]
    if tr > 0:
        S = np.sqrt(tr + 1.0) * 2
        w = 0.25 * S; x = (R[2, 1] - R[1, 2]) / S; y = (R[0, 2] - R[2, 0]) / S; z = (R[1, 0] - R[0, 1]) / S
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        S = np.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        w = (R[2, 1] - R[1, 2]) / S; x = 0.25 * S; y = (R[0, 1] + R[1, 0]) / S; z = (R[0, 2] + R[2, 0]) / S
    elif R[1, 1] > R[2, 2]:
        S = np.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        w = (R[0, 2] - R[2, 0]) / S; x = (R[0, 1] + R[1, 0]) / S; y = 0.25 * S; z = (R[1, 2] + R[2, 1]) / S
    else:
        S = np.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
        w = (R[1, 0] - R[0, 1]) / S; x = (R[0, 2] + R[2, 0]) / S; y = (R[1, 2] + R[2, 1]) / S; z = 0.25 * S
    q = np.array([x, y, z, w])
    return q / np.linalg.norm(q)


def fix_quat_continuity(qs):
    """keep consecutive quaternions in the same hemisphere so LINEAR interpolation doesn't spin."""
    qs = np.array(qs, np.float32)
    for i in range(1, len(qs)):
        if np.dot(qs[i], qs[i - 1]) < 0:
            qs[i] = -qs[i]
    return qs
