"""Export a screenplay as a Story Craft episode package (baked glTF scenes + side tracks + audio stems).

  python3 mcanim.py сценарий.txt --export-game out_dir [--logic логика.json]
"""
import hashlib
import io
import json
import math
import os
import re
import subprocess

import numpy as np
from PIL import Image

from .gltf import GLB, decompose, fix_quat_continuity
from .skin import load_skin, analyze_face, face_variants, player_parts
from .mobs import load_mob
from .props import block_prop, item_prop, load_model
from .world import make_atlas
from .render import Renderer, look_at, T, S, TIMES
from .anim import CharSim, particles_at
from .director import Director
from .audio import SR, sfx as sfx_fn, nb_music

TR = dict(zip("абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
              ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
               "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"]))


def ascii_id(name):
    s = "".join(TR.get(c, c) for c in name.lower())
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return s or "x"


class _Stub:
    SLOTS = Renderer.SLOTS


def item_quad():
    q = []
    for dx in (-3.5, 3.5):
        a, b = (-6 - dx, -1), (-6 + dx, 7)
        q += [(a[0], 15, a[1], 0, 1, 0, 0, 0, 1), (b[0], 15, b[1], 0, 1, 0, 1, 0, 1), (b[0], 6, b[1], 0, 1, 0, 1, 1, 1),
              (a[0], 15, a[1], 0, 1, 0, 0, 0, 1), (b[0], 6, b[1], 0, 1, 0, 1, 1, 1), (a[0], 6, a[1], 0, 1, 0, 0, 1, 1)]
    return np.array(q, np.float32)


def png_bytes(arr):
    b = io.BytesIO(); Image.fromarray(np.ascontiguousarray(arr, np.uint8)).save(b, "PNG"); return b.getvalue()


def ogg(path, mono):
    data = (np.clip(mono, -1, 1) * 32767).astype(np.int16).tobytes()
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SR), "-ac", "1", "-i", "-",
                    "-c:a", "libvorbis", "-q:a", "3", path], input=data, check=True)


class Exporter:
    def __init__(self, film, out_dir, fps=15, logic_path=None):
        self.film, self.tl, self.out, self.fps = film, film.tl, out_dir, fps
        self.logic_path = logic_path
        os.makedirs(out_dir, exist_ok=True)
        for d in ("scenes", "worlds", "textures", "audio"):
            os.makedirs(os.path.join(out_dir, d), exist_ok=True)
        self.cids = {n: ascii_id(n) for n in self.tl.cast}
        seen = {}
        for n, c in list(self.cids.items()):  # unique ids
            if c in seen:
                self.cids[n] = f"{c}_{len(seen)}"
            seen[self.cids[n]] = 1
        self.atlas = make_atlas(self.tl.seed)
        self._rigs = {}

    # ------------------------------------------------------------ rigs
    def rig(self, name):
        """parts: list of (part, verts(px), texture_key, alpha_mode). Also writes face textures."""
        if name in self._rigs:
            return self._rigs[name]
        c = self.tl.cast[name]
        cid = self.cids[name]
        out = dict(parts=[], textures={}, kind="player", faces=False)
        if c.get("prop"):
            sp = c["prop"]
            out["kind"] = "prop"
            if sp["kind"] == "block":
                out["parts"].append(("main", block_prop(sp["block"]), "atlas", "MASK"))
                out["textures"]["atlas"] = self.atlas
            elif sp["kind"] == "item":
                from .render import Renderer as _R
                tex = self._item_tex(sp["item"])
                out["parts"].append(("main", item_prop(), "item_" + sp["item"], "MASK"))
                out["textures"]["item_" + sp["item"]] = tex
            else:
                for i, (v, img) in enumerate(load_model(sp["model"], 1.0)):
                    out["parts"].append((f"m{i}", v, f"m{i}", "MASK"))
                    out["textures"][f"m{i}"] = img
            out["size"] = sp.get("size", 1.0)
        elif c.get("mob"):
            parts, tex = load_mob(c["mob"], c.get("texture"), c.get("tint"))
            out["kind"] = "mob"; out["bones"] = parts
            for bn, b in parts.items():
                out["parts"].append((bn, b["verts"], "skin", "MASK"))
            out["textures"]["skin"] = tex
        else:
            arr, slim = load_skin(c["skin"])
            fv = face_variants(arr, analyze_face(arr))
            out["slim"] = slim
            out["pivots"] = {}
            for pn, (piv, base, over) in player_parts(slim).items():
                out["parts"].append((pn, np.concatenate([base, over]), "skin", "MASK"))
                out["pivots"][pn] = piv
            out["textures"]["skin"] = fv[(0, 0)]
            out["faces"] = True
            for (bl, mo), a in fv.items():
                Image.fromarray(a).save(os.path.join(self.out, "textures", f"{cid}_face{bl * 3 + mo}.png"))
        self._rigs[name] = out
        return out

    def _item_tex(self, item):
        from .world import ASSETS
        p = os.path.join(ASSETS, "items", item)
        if os.path.isdir(p):
            p = os.path.join(p, sorted(os.listdir(p))[0])
        elif not p.endswith(".png"):
            p += ".png"
        if not os.path.exists(p):
            p2 = os.path.join(ASSETS, "blocks", item + ".png")
            p = p2 if os.path.exists(p2) else None
        if p is None:
            return np.full((16, 16, 4), 255, np.uint8)
        im = Image.open(p).convert("RGBA"); im = im.crop((0, 0, im.size[0], im.size[0]))
        return np.array(im)

    # ------------------------------------------------------------ worlds
    def export_world(self, wname):
        w = self.film.get_world(wname)
        wid = ascii_id(wname)
        path = os.path.join(self.out, "worlds", wid + ".glb")
        if os.path.exists(path):
            return wid
        op, wa = w.build_mesh()
        cut = w.cutout
        atlas = make_atlas(self.tl.seed)
        g = GLB()
        tex = g.texture(atlas, "atlas")
        m_solid = g.material(tex, "OPAQUE", "solid")
        m_cut = g.material(tex, "MASK", "cutout")
        m_water = g.material(tex, "BLEND", "water")

        def emit(arr, mat, tag):
            if len(arr) == 0:
                return
            tri = arr.reshape(-1, 3, 9)
            cx = np.floor(tri[:, :, 0].mean(1) / 16).astype(int); cz = np.floor(tri[:, :, 2].mean(1) / 16).astype(int)
            keys = cx * 1000 + cz
            for k in np.unique(keys):
                sel = tri[keys == k].reshape(-1, 9)
                ao = np.clip(sel[:, 8:9], 0, 1.0)
                emis = sel[:, 8:9] > 1.5
                col = np.concatenate([np.where(emis, 1.0, ao)] * 3 + [np.ones_like(ao)], axis=1)
                mi = g.mesh(f"{tag}_{k}", sel, mat, color=col)
                g.node(f"{tag}_{k // 1000}_{k % 1000}", mesh=mi)
        emit(op, m_solid, "solid"); emit(cut, m_cut, "cutout"); emit(wa, m_water, "water")
        g.save(path)
        meta = dict(id=wid, name=wname, marks={k: list(v) for k, v in w.marks.items()})
        json.dump(meta, open(os.path.join(self.out, "worlds", wid + ".json"), "w"), ensure_ascii=False, indent=1)
        return wid

    # ------------------------------------------------------------ audio
    def stems(self):
        tl = self.tl
        n = int(tl.duration * SR) + SR
        voice = np.zeros(n, np.float32); fx = np.zeros(n, np.float32); mus = np.zeros(n, np.float32)
        duck = np.ones(n, np.float32)
        for s in tl.speech:
            a = s["audio"]; i0 = int(s["t0"] * SR); i1 = min(n, i0 + len(a))
            voice[i0:i1] += a[: i1 - i0]
            duck[max(0, i0 - 4000):min(n, i1 + 6000)] = 0.45
        for (t, kind, vol) in tl.sfx:
            x = sfx_fn(kind) * vol
            i0 = int(t * SR); i1 = min(n, i0 + len(x))
            if 0 <= i0 < n:
                fx[i0:i1] += x[: i1 - i0]
        if tl.music:
            duck = np.convolve(duck, np.ones(4410) / 4410, mode="same")
            for (a0, a1, md) in (tl.music_plan or [(0, tl.duration, tl.mood)]):
                if md in ("нет", "тишина"):
                    continue
                seg = nb_music(a1 - a0 + 0.5, md, seed=int(a0 * 7) + 1)
                i0 = int(a0 * SR); L = min(len(seg), n - i0)
                seg = seg[:L].copy()
                fi = min(int(0.5 * SR), L); seg[:fi] *= np.linspace(0, 1, fi)
                mus[i0:i0 + L] += seg
            mus *= duck * 0.55
        return voice, fx, mus

    # ------------------------------------------------------------ scenes
    def export(self):
        tl = self.tl
        voice, fx, mus = self.stems()
        scenes = {}
        worlds = {}
        for si, sc in enumerate(tl.scenes):
            sid = sc.get("sid") or f"s{si + 1}"
            wid = self.export_world(sc.get("world"))
            worlds[wid] = dict(glb=f"worlds/{wid}.glb", meta=f"worlds/{wid}.json")
            info = self.export_scene(sc, sid, wid)
            for stem, arr in (("voice", voice), ("sfx", fx), ("music", mus)):
                a = arr[int(sc["t0"] * SR):int(sc["t1"] * SR)]
                ogg(os.path.join(self.out, "audio", f"{sid}_{stem}.ogg"), a)
                info["audio"][stem] = f"audio/{sid}_{stem}.ogg"
            scenes[sid] = info
            print(f"  сцена {sid} «{sc['name']}»: {info['frames']} кадров, {info['duration']:.1f} с")
        cast = {}
        for n, c in tl.cast.items():
            cid = self.cids[n]
            kind = "prop" if c.get("prop") else ("mob" if c.get("mob") else ("extra" if c.get("extra") else "player"))
            cast[cid] = dict(name=n, kind=kind, color=c.get("color", "#ffffff"))
            if kind in ("player", "extra"):
                cast[cid]["faces"] = [f"textures/{cid}_face{k}.png" for k in range(6)]
        ids = list(scenes)
        logic = None
        if self.logic_path and os.path.exists(self.logic_path):
            logic = json.load(open(self.logic_path, encoding="utf-8"))
        nodes = (logic or {}).get("nodes")
        if not nodes:
            nodes = {}
            for i, sid in enumerate(ids):
                nodes[sid] = dict(type="scene", scene=sid, next=ids[i + 1] if i + 1 < len(ids) else "end")
            nodes["end"] = dict(type="end")
        ep = dict(format="storycraft-episode", version=1, title=tl.title or "", subtitle=tl.subtitle or "",
                  start=(logic or {}).get("start", ids[0]), variables=(logic or {}).get("variables", {}),
                  cast=cast, worlds=worlds, scenes=scenes, nodes=nodes,
                  duration=round(sum(s["duration"] for s in scenes.values()), 2))
        if logic:
            for k in ("id", "number", "title", "description"):
                if k in logic:
                    ep[k] = logic[k]
        json.dump(ep, open(os.path.join(self.out, "episode.json"), "w"), ensure_ascii=False, indent=1)
        return ep

    def export_scene(self, sc, sid, wid):
        tl, film = self.tl, self.film
        world = film.get_world(sc.get("world"))
        sims = [CharSim(n, tl, world) for n in tl.cast]
        d = Director(tl, world)
        f0, f1 = int(round(sc["t0"] * 30)), int(round(sc["t1"] * 30))
        step = max(1, int(round(30 / self.fps)))
        rec_mats, rec_cam, rec_face, rec_part = [], [], [], {}
        cam = None
        for i in range(f0, f1):
            t = i / 30
            pxz = {s.name: (*s.pos_at(t)[:2], 0.0) for s in sims}
            poses = [s.step(t, 1 / 30, pxz, cam, i == f0) for s in sims]
            eye, tgt, fov = d.frame(t, 1 / 30, poses, i == f0)
            cam = eye
            if (i - f0) % step:
                continue
            k = len(rec_cam)
            mats, faces = {}, {}
            charM = {}
            order = [p for p in poses if not tl.cast[p["name"]].get("prop")] + [p for p in poses if tl.cast[p["name"]].get("prop")]
            for p in order:
                n = p["name"]; cid = self.cids[n]; rg = self.rig(n)
                if p.get("hidden"):
                    continue
                if rg["kind"] == "prop":
                    Mp = Renderer.prop_matrix(_Stub(), p, dict(size=rg.get("size", 1.0)), charM)
                    for pn, *_ in rg["parts"]:
                        mats[f"{cid}__{pn}"] = Mp
                    continue
                if rg["kind"] == "mob":
                    M = Renderer.mob_matrices(p, dict(bones=rg["bones"], mob=tl.cast[n]["mob"]))
                else:
                    M = Renderer.part_matrices(p, rg["pivots"])
                    faces[cid] = p.get("blink", 0) * 3 + p.get("mouth", 0)
                    if p.get("item"):
                        Mi = M["rarm"]
                        if "/" in p["item"]:
                            Mi = Mi @ T(-6, 10, 3) @ S(0.6) @ T(6, -10, -3)
                        mats[f"{cid}__item_{ascii_id(p['item'])}"] = Mi
                        rec_part.setdefault(cid, set()).add(p["item"])
                charM[n] = M
                for pn, Mx in M.items():
                    mats[f"{cid}__{pn}"] = Mx
            rec_mats.append(mats)
            rec_face.append(faces)
            rec_cam.append((tuple(eye), tuple(tgt), fov, t - sc["t0"]))
        frames = len(rec_cam)
        rec_cam = clean_camera(rec_cam)
        times = np.arange(frames, dtype=np.float32) / self.fps
        # ---- glb: static nodes for every part that ever appears, animated TRS
        g = GLB()
        tex_cache = {}
        node_ids = {}
        all_names = set()
        for m in rec_mats:
            all_names |= set(m)
        for n in tl.cast:
            cid = self.cids[n]; rg = self.rig(n)
            mats_for = {}
            for key, img in rg["textures"].items():
                tk = (cid if key == "skin" else "") + key
                if tk not in tex_cache:
                    tex_cache[tk] = g.material(g.texture(img, tk), "MASK", name=f"{cid}_{key}")
                mats_for[key] = tex_cache[tk]
            for pn, v, tkey, alpha in rg["parts"]:
                nm = f"{cid}__{pn}"
                if nm not in all_names:
                    continue
                mi = g.mesh(nm, v, mats_for[tkey])
                node_ids[nm] = g.node(nm, mesh=mi, extras=dict(cast=cid, part=pn))
            for it in sorted(rec_part.get(cid, [])):
                nm = f"{cid}__item_{ascii_id(it)}"
                tk = "item_" + it
                if tk not in tex_cache:
                    tex_cache[tk] = g.material(g.texture(self._item_tex(it), tk), "MASK", name=tk)
                mi = g.mesh(nm, item_quad(), tex_cache[tk])
                node_ids[nm] = g.node(nm, mesh=mi, extras=dict(cast=cid, part="item", item=it))
        cam_node = g.node("camera", camera=g.camera(math.radians(45)))
        chans = []
        for nm, ni in node_ids.items():
            ts, rs, ss = [], [], []
            last = (np.zeros(3), np.array([0, 0, 0, 1.0]))
            for m in rec_mats:
                if nm in m:
                    t_, r_, s_ = decompose(m[nm]); last = (t_, r_)
                    ts.append(t_); rs.append(r_); ss.append(s_)
                else:
                    ts.append(last[0]); rs.append(last[1]); ss.append(np.zeros(3))
            ts, rs, ss = np.array(ts), fix_quat_continuity(rs), np.array(ss)
            # hard breaks: appear/disappear (scale 0 <-> visible) and teleports (> 0.9 block per frame)
            brk = [k for k in range(1, frames)
                   if (ss[k].min() < 1e-6) != (ss[k - 1].min() < 1e-6) or np.linalg.norm(ts[k] - ts[k - 1]) > 0.9]
            chans += stepped(ni, times, brk, ts, rs, ss)
        cts, crs = [], []
        for eye, tgt, fov, _ in rec_cam:
            V = look_at(eye, tgt)
            t_, r_, _ = decompose(np.linalg.inv(V))
            cts.append(t_); crs.append(r_)
        cts, crs = np.array(cts), fix_quat_continuity(crs)
        # camera cuts: a jump of the eye or a sharp turn between two samples
        cuts = []
        for k in range(1, frames):
            e0, e1 = np.array(rec_cam[k - 1][0]), np.array(rec_cam[k][0])
            d0 = np.array(rec_cam[k - 1][1]) - e0; d1 = np.array(rec_cam[k][1]) - e1
            ang = math.degrees(math.acos(np.clip(np.dot(d0, d1) / (np.linalg.norm(d0) * np.linalg.norm(d1) + 1e-9), -1, 1)))
            if np.linalg.norm(e1 - e0) > 0.8 or ang > 25 or abs(rec_cam[k][2] - rec_cam[k - 1][2]) > 6:
                cuts.append(k)
        chans += stepped(cam_node, times, cuts, cts, crs, None)
        g.animation("main", times, chans)
        g.save(os.path.join(self.out, "scenes", f"{sid}.glb"))
        # ---- side tracks
        env = TIMES.get(sc.get("time", tl.time), TIMES["день"])
        sd = np.array(env["sun"], float); sd /= np.linalg.norm(sd)
        side = dict(fps=self.fps, frames=frames, duration=round(frames / self.fps, 3),
                    fov=[round(c[2], 2) for c in rec_cam],
                    cuts=cuts,
                    face={self.cids[n]: [f.get(self.cids[n], 0) for f in rec_face] for n in tl.cast
                          if self.rig(n).get("faces")},
                    subtitles=[dict(t0=round(s["t0"] - sc["t0"], 2), t1=round(s["t1"] - sc["t0"], 2),
                                    who=self.cids[s["char"]], name=s["char"],
                                    color=tl.cast[s["char"]].get("color", "#ffffff"), text=s["text"])
                               for s in tl.speech if sc["t0"] <= s["t0"] < sc["t1"]],
                    env=dict(time=sc.get("time", tl.time), sun_dir=[round(x, 3) for x in sd],
                             sun_color=list(env["sun_col"]), ambient=list(env["amb"]), sky_zenith=list(env["zen"]),
                             sky_horizon=list(env["hor"]), sun_disc=list(env["sunc"]), stars=env["stars"],
                             clouds=list(env["cloud"]), fog_color=[round(x * 0.95, 3) for x in env["hor"]],
                             fog_near=18.0, fog_far=46.0),
                    particles=self._particles(sc, sims, world),
                    world=wid)
        side_path = os.path.join(self.out, "scenes", f"{sid}.json")
        json.dump(side, open(side_path, "w"), ensure_ascii=False, separators=(",", ":"))
        return dict(name=sc["name"], glb=f"scenes/{sid}.glb", tracks=f"scenes/{sid}.json", world=wid,
                    duration=side["duration"], frames=frames, fps=self.fps, audio={})

    def _particles(self, sc, sims_unused, world):
        """particles per exported frame (sparse): {frame: [[x,y,z,size,r,g,b],...]}"""
        tl = self.tl
        sims = [CharSim(n, tl, world) for n in tl.cast]
        d = Director(tl, world)
        f0, f1 = int(round(sc["t0"] * 30)), int(round(sc["t1"] * 30))
        step = max(1, int(round(30 / self.fps)))
        out = {}
        cam = None
        for i in range(f0, f1):
            t = i / 30
            pxz = {s.name: (*s.pos_at(t)[:2], 0.0) for s in sims}
            poses = [s.step(t, 1 / 30, pxz, cam, i == f0) for s in sims]
            if (i - f0) % step:
                continue
            pts = particles_at(t, tl.particles, {p["name"]: p for p in poses})
            if pts:
                out[str((i - f0) // step)] = [[round(float(v), 3) for v in (x, y, z, s)] + [round(float(c), 3) for c in col]
                                             for (x, y, z, s, col) in pts]
        return out


# ---------------------------------------------------------------- menu rigs
def _cam_breaks(rc, pos_thr, ang_thr, fov_thr=3.0):
    br = []
    for k in range(1, len(rc)):
        e0, e1 = np.array(rc[k - 1][0]), np.array(rc[k][0])
        d0 = np.array(rc[k - 1][1]) - e0; d1 = np.array(rc[k][1]) - e1
        ang = math.degrees(math.acos(np.clip(np.dot(d0, d1) / (np.linalg.norm(d0) * np.linalg.norm(d1) + 1e-9), -1, 1)))
        if np.linalg.norm(e1 - e0) > pos_thr or ang > ang_thr or abs(rc[k][2] - rc[k - 1][2]) > fov_thr:
            br.append(k)
    return br


def clean_camera(rc, min_len=8):
    """Remove camera 'flashes': shots shorter than min_len samples (≈0.5 s at 15 fps) — the
    director's first frame before it settles, a new framing it starts in the last frames of a scene,
    or a 1-3 frame detour before a cut. Such samples hold the neighbouring shot instead."""
    rc = [list(c) for c in rc]
    n = len(rc)
    if n < 2:
        return rc
    for _ in range(3):
        br = _cam_breaks(rc, 0.35, 9.0)
        seg = [0] + br + [n]
        changed = False
        for a, b in zip(seg[:-1], seg[1:]):
            if b - a >= min_len or b - a == n:
                continue
            src = b if a == 0 else a - 1          # first shot -> copy the next one, else hold the previous
            if src >= n:
                continue
            for k in range(a, b):
                rc[k][0], rc[k][1], rc[k][2] = rc[src][0], rc[src][1], rc[src][2]
            changed = True
        if not changed:
            break
    return [tuple(c) for c in rc]


def stepped(node, times, brk, ts, rs, ss):
    """TRS channels; at every break frame k a copy of frame k-1 is keyed 1 ms before k,
    so LINEAR playback switches instantly instead of sliding through an in-between pose."""
    if not brk:
        out = [(node, "translation", ts), (node, "rotation", rs)]
        return out + ([(node, "scale", ss)] if ss is not None else [])
    tt, idx = [], []
    bs = set(brk)
    for k in range(len(times)):
        if k in bs:
            tt.append(times[k] - 0.001); idx.append(k - 1)
        tt.append(times[k]); idx.append(k)
    tt = np.array(tt, np.float32)
    out = [(node, "translation", ts[idx], tt), (node, "rotation", rs[idx], tt)]
    return out + ([(node, "scale", ss[idx], tt)] if ss is not None else [])


def export_mob_rig(kind, path, tint=None):
    """Hierarchical rig for runtime procedural animation (main menu animals).
    Root node 'rig' (scale 1/16, model px units). Each bone = node at its pivot with rest rotation;
    mesh offset by -pivot. Runtime: bone.basis = Basis(anim) * rest_basis."""
    from .render import Rx, Ry, Rz
    parts, tex = load_mob(kind, None, tint)
    g = GLB()
    mat = g.material(g.texture(tex, "skin"), "MASK", "skin")
    ids = {}
    order = []
    pend = dict(parts)
    while pend:  # parents first
        for bn in list(pend):
            par = pend[bn].get("parent")
            if not par or par in ids or par not in parts:
                order.append(bn); ids[bn] = None; pend.pop(bn)
    ids = {}
    child_lists = {bn: [] for bn in parts}
    nodes_tmp = {}
    for bn in order:
        b = parts[bn]
        piv = np.array(b["pivot"], float)
        par = b.get("parent") if b.get("parent") in parts else None
        ppiv = np.array(parts[par]["pivot"], float) if par else np.zeros(3)
        v = b["verts"].copy(); v[:, 0:3] -= piv
        rx, ry, rz = b["rest"]
        R = (Rz(rz) @ Ry(ry) @ Rx(rx))[:3, :3]
        from .gltf import mat_to_quat
        q = mat_to_quat(np.asarray(R, float))
        mi = g.mesh(bn, v, mat)
        nodes_tmp[bn] = g.node(bn, mesh=mi, t=piv - ppiv, r=q, root=False,
                               extras=dict(pivot=[float(x) for x in piv], rest=[float(x) for x in (rx, ry, rz)]))
        if par:
            child_lists[par].append(nodes_tmp[bn])
    for bn, kids in child_lists.items():
        if kids:
            g.j["nodes"][nodes_tmp[bn]]["children"] = kids
    roots = [nodes_tmp[bn] for bn in order if not (parts[bn].get("parent") in parts)]
    g.node("rig", s=(1 / 16, 1 / 16, 1 / 16), children=roots, extras=dict(kind=kind))
    g.save(path)
