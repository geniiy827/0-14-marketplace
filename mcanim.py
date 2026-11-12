#!/usr/bin/env python3
"""mcanim — Minecraft-style 3D cartoon maker.

  python3 mcanim.py сценарий.txt                 # full render -> сценарий.mp4
  python3 mcanim.py сценарий.txt --preview       # fast 360p draft
  python3 mcanim.py сценарий.txt --stills 3,12.5 # PNG frames to check shots
  python3 mcanim.py сценарий.txt --plan          # print timeline only (no render)
Unchanged scenes are reused from cache/, so editing one scene re-renders only that scene.
"""
import argparse
import hashlib
import json
import math
import os
import subprocess
import sys
import time

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from engine.skin import load_skin, analyze_face, face_variants  # noqa: E402
from engine.world import World, make_atlas  # noqa: E402
from engine.render import Renderer  # noqa: E402
from engine.anim import CharSim, particles_at  # noqa: E402
from engine.script import Compiler, Director  # noqa: E402
from engine.audio import mix  # noqa: E402

VERSION = "2.0"
FONT_B = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
WORLD_PRESETS = {"равнина": "plains", "лес": "forest", "пустыня": "desert", "зима": "winter", "город": "city"}


def font(size):
    try:
        return ImageFont.truetype(FONT_B, size)
    except OSError:
        return ImageFont.load_default()


def text_image(parts, size, max_w, stroke=None, align="center", bg=None):
    """parts: list of (text, rgb). Returns RGBA array with word wrap."""
    f = font(size)
    stroke = stroke if stroke is not None else max(2, size // 11)
    words = []
    for txt, col in parts:
        for w in txt.split(" "):
            if w:
                words.append((w, col))
    lines, cur, cw = [], [], 0
    space = f.getlength(" ")
    for w, col in words:
        wl = f.getlength(w)
        if cur and cw + space + wl > max_w:
            lines.append(cur); cur, cw = [], 0
        cur.append((w, col, wl)); cw += (space if len(cur) > 1 else 0) + wl
    if cur:
        lines.append(cur)
    lh = int(size * 1.25)
    W = int(max(sum(w[2] for w in ln) + space * (len(ln) - 1) for ln in lines)) + stroke * 2 + 24
    H = lh * len(lines) + stroke * 2 + 14
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    if bg:
        d.rounded_rectangle((0, 0, W - 1, H - 1), radius=14, fill=bg)
    y = 7
    for ln in lines:
        lw = sum(w[2] for w in ln) + space * (len(ln) - 1)
        x = (W - lw) / 2 if align == "center" else 12
        for w, col, wl in ln:
            d.text((x, y), w, font=f, fill=(*col, 255), stroke_width=stroke, stroke_fill=(0, 0, 0, 255))
            x += wl + space
        y += lh
    return np.array(im)


def hex2rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) if len(h) == 6 else (255, 216, 74)


class Film:
    def __init__(self, script, preview=False, fps=30, cache=None):
        self.script = os.path.abspath(script)
        self.base = os.path.splitext(self.script)[0]
        self.cache = cache or os.path.join(os.path.dirname(self.script), "cache")
        os.makedirs(self.cache, exist_ok=True)
        self.preview = preview
        self.W, self.H = (640, 360) if preview else (1280, 720)
        self.fps = fps
        # header first (world needed for marks)
        from engine.script import parse
        head, _, _ = parse(self.script)
        wname = head.get("мир", "равнина").strip().lower()
        self.seed = int(head.get("сид", 3))
        self.worlds = {}
        self.world = self.get_world(wname)
        t0 = time.time()
        self.comp = Compiler(self.script, self.world, self.cache, world_factory=self.get_world)
        self.tl = self.comp.compile()
        print(f"Сценарий: {len(self.tl.scenes)} сцен, {len(self.tl.speech)} реплик, "
              f"{self.tl.duration:.1f} с  (озвучка {time.time() - t0:.1f} с)")
        self.renderers = {}

    def get_world(self, name):
        name = (name or "равнина").strip().lower()
        key = WORLD_PRESETS.get(name, name)
        if key not in self.worlds:
            self.worlds[key] = World(key, self.seed)
            for line in getattr(self, "_props", []):
                pass
        return self.worlds[key]

    def renderer(self, world_name):
        w = self.get_world(world_name)
        key = id(w)
        if key not in self.renderers:
            tl = self.tl
            r = Renderer(self.W, self.H, w, make_atlas(tl.seed), tl.time, msaa=0)
            for n, c in tl.cast.items():
                if c.get("mob"):
                    r.add_mob(n, c["mob"])
                else:
                    arr, slim = load_skin(c["skin"])
                    r.add_character(n, face_variants(arr, analyze_face(arr)), slim)
            self.renderers[key] = r
        return self.renderers[key]

    # ------------------------------------------------------------ frames
    def overlays(self, t):
        tl, W, H = self.tl, self.W, self.H
        s = W / 1280
        out = []
        if tl.subs:
            for sp in tl.speech:
                if sp["t0"] - 0.05 <= t < sp["t1"] + 0.25:
                    key = ("sub", sp["t0"], W)
                    img = self._cache_img(key, lambda: text_image(
                        [(sp["char"] + ":", hex2rgb(tl.cast[sp["char"]]["color"])), (sp["text"], (255, 255, 255))],
                        int(34 * s), int(1120 * s)))
                    w, h = img.shape[1] / W, img.shape[0] / H
                    out.append((key, img, ((1 - w) / 2, 0.035, w, h), 1.0))
                    break
        if tl.title and t < 3.4:
            a = min(1, max(0, (t - 0.3) / 0.4)) * min(1, max(0, (3.2 - t) / 0.4))
            if a > 0:
                key = ("title", W)
                img = self._cache_img(key, lambda: text_image([(tl.title, (255, 230, 90))], int(78 * s), int(1150 * s)))
                w, h = img.shape[1] / W, img.shape[0] / H
                out.append((key, img, ((1 - w) / 2, 0.62, w, h), a))
                if tl.subtitle:
                    key2 = ("subt", W)
                    img2 = self._cache_img(key2, lambda: text_image([(tl.subtitle, (255, 255, 255))], int(38 * s), int(1100 * s)))
                    w2, h2 = img2.shape[1] / W, img2.shape[0] / H
                    out.append((key2, img2, ((1 - w2) / 2, 0.62 - h2, w2, h2), a))
        if tl.end_text and t > tl.duration - 1.6:
            a = min(1, (t - (tl.duration - 1.6)) / 0.4)
            key = ("end", W)
            img = self._cache_img(key, lambda: text_image([(tl.end_text, (255, 255, 255))], int(70 * s), int(1100 * s)))
            w, h = img.shape[1] / W, img.shape[0] / H
            out.append((key, img, ((1 - w) / 2, 0.45, w, h), a))
        return out

    _imgs = {}

    def _cache_img(self, key, fn):
        if key not in self._imgs:
            self._imgs[key] = fn()
        return self._imgs[key]

    def fade(self, t):
        tl = self.tl
        f = min(1.0, t / 0.5)
        if tl.end_text:
            f *= 1 - 0.75 * min(1.0, max(0.0, (t - (tl.duration - 1.6)) / 0.6))
        else:
            f *= min(1.0, max(0.0, (tl.duration - t) / 0.5))
        return f

    def run_range(self, t_start, t_end, render_times=None, sink=None, window=None):
        tl = self.tl
        sc = next((s for s in tl.scenes if s["t0"] <= t_start + 1e-6 < s["t1"]), tl.scenes[-1])
        world = self.get_world(sc.get("world"))
        rr = self.renderer(sc.get("world"))
        rr.set_time(sc.get("time", tl.time))
        sims = [CharSim(n, tl, world) for n in tl.cast]
        director = Director(tl, world)
        dt = 1 / self.fps
        f0, f1 = int(round(t_start * self.fps)), int(round(t_end * self.fps))
        if window:
            f1 = min(f1, window[1])
        cam = None
        for i in range(f0, f1):
            t = i / self.fps
            snap = i == f0
            pxz = {}
            for s in sims:
                x, z, *_ = s.pos_at(t)
                pxz[s.name] = (x, z, 0.0)
            poses = [s.step(t, dt, pxz, cam, snap) for s in sims]
            eye, tgt, fov = director.frame(t, dt, poses, snap)
            cam = eye
            if window and i < window[0]:
                continue
            if render_times is None or any(abs(t - rt) < dt / 2 for rt in render_times):
                PP = {p["name"]: p for p in poses}
                parts = particles_at(t, tl.particles, PP)
                buf = rr.render(eye, tgt, fov, poses, t, self.overlays(t), fade=self.fade(t), particles=parts)
                sink(t, buf)

    # ------------------------------------------------------------ outputs
    def stills(self, times, outdir):
        os.makedirs(outdir, exist_ok=True)
        paths = []
        for tt in times:
            sc = next((s for s in self.tl.scenes if s["t0"] <= tt < s["t1"]), self.tl.scenes[-1])
            st = max(sc["t0"], tt - 1.5)
            snapped = round(tt * self.fps) / self.fps

            def sink(t, buf, tt=tt):
                p = os.path.join(outdir, f"still_{tt:06.2f}.png")
                Image.frombytes("RGB", (self.W, self.H), buf).transpose(Image.FLIP_TOP_BOTTOM).save(p)
                paths.append(p)
            self.run_range(st, snapped + 1 / self.fps, [snapped], sink)
        return paths

    def scene_key(self, i, sc):
        tl = self.tl
        inr = lambda a, b: a < sc["t1"] + 1 and b > sc["t0"] - 3
        data = dict(v=VERSION, W=self.W, H=self.H, fps=self.fps, sc=sc, world=[tl.world, tl.time, tl.seed],
                    cast={n: [c["skin"], os.path.getmtime(c["skin"]) if c.get("skin") else 0, c["pos"], c.get("yaw"), c["color"], c.get("mob"), c.get("hidden")] for n, c in tl.cast.items()},
                    shots=[s for s in tl.shots if inr(s["t0"], s["t1"])],
                    moves=[m for m in tl.moves if m["t0"] < sc["t1"]],
                    clips=[c for c in tl.clips if inr(c["t0"], c["t1"])],
                    looks=[l for l in tl.looks if l["t"] < sc["t1"]], faces=[f for f in tl.faces if f["t"] < sc["t1"]],
                    sits=[s for s in tl.sits if s["t"] < sc["t1"]],
                    extra=[[v for v in tl.vis if v["t"] < sc["t1"]], [v for v in tl.items if v["t"] < sc["t1"]],
                           [v for v in tl.lies if v["t"] < sc["t1"]], [p for p in tl.particles if inr(p["t0"], p["t1"])]],
                    speech=[(s["char"], s["t0"], s["t1"], s["text"]) for s in tl.speech if inr(s["t0"], s["t1"])],
                    title=[tl.title, tl.subtitle, tl.subs, tl.end_text, tl.duration])
        blob = json.dumps(data, sort_keys=True, default=str).encode()
        return hashlib.md5(blob).hexdigest()[:12]

    def render(self, out=None):
        tl = self.tl
        out = out or (self.base + ("_preview" if self.preview else "") + ".mp4")
        crf = "26" if self.preview else "19"
        files = []
        t_all = time.time()
        for i, sc in enumerate(tl.scenes):
            key = self.scene_key(i, sc)
            path = os.path.join(self.cache, f"scene{i:02d}_{key}.mp4")
            files.append(path)
            if os.path.exists(path):
                print(f"  сцена {i + 1} «{sc['name']}»: из кэша")
                continue
            tmp = path[:-4] + ".part.mp4"
            cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
                   "-s", f"{self.W}x{self.H}", "-r", str(self.fps), "-i", "-", "-vf", "vflip",
                   "-c:v", "libx264", "-preset", "veryfast", "-crf", crf, "-pix_fmt", "yuv420p", tmp]
            p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
            t0 = time.time()
            n = [0]

            def sink(t, buf):
                p.stdin.write(buf); n[0] += 1
            self.run_range(sc["t0"], sc["t1"], None, sink)
            p.stdin.close()
            if p.wait() != 0:
                raise SystemExit("ffmpeg error")
            os.replace(tmp, path)
            print(f"  сцена {i + 1} «{sc['name']}»: {n[0]} кадров за {time.time() - t0:.0f} с")
        lst = os.path.join(self.cache, "concat.txt")
        with open(lst, "w") as f:
            for p_ in files:
                f.write(f"file '{p_}'\n")
        vid = os.path.join(self.cache, "video.mp4")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", vid], check=True)
        wav = mix(tl.duration, tl.speech, tl.sfx, tl.music, tl.mood, os.path.join(self.cache, "mix.wav"),
                  music_plan=tl.music_plan)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", vid, "-i", wav, "-map", "0:v", "-map", "1:a",
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", out], check=True)
        print(f"Готово: {out}  ({tl.duration:.1f} с, рендер {time.time() - t_all:.0f} с)")
        return out

    def render_chunk(self, i, n, out, crf="23"):
        """Render frames [i/n, (i+1)/n) of the whole film (for parallel cloud rendering)."""
        tl = self.tl
        F = int(round(tl.duration * self.fps))
        fa, fb = i * F // n, (i + 1) * F // n
        tmp = out[:-4] + ".part.mp4"
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24",
               "-s", f"{self.W}x{self.H}", "-r", str(self.fps), "-i", "-", "-vf", "vflip",
               "-c:v", "libx264", "-preset", "veryfast", "-crf", crf, "-pix_fmt", "yuv420p", tmp]
        p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
        n_ = [0]
        t0 = time.time()

        def sink(t, buf):
            p.stdin.write(buf); n_[0] += 1
        for sc in tl.scenes:
            s0, s1 = int(round(sc["t0"] * self.fps)), int(round(sc["t1"] * self.fps))
            if s1 <= fa or s0 >= fb:
                continue
            self.run_range(sc["t0"], sc["t1"], None, sink, window=(max(fa, s0), min(fb, s1)))
        p.stdin.close()
        if p.wait() != 0:
            raise SystemExit("ffmpeg error")
        os.replace(tmp, out)
        print(f"кусок {i + 1}/{n}: кадры {fa}-{fb} ({n_[0]}) за {time.time() - t0:.0f} с")

    def assemble(self, chunk_dir, out):
        import glob
        tl = self.tl
        files = sorted(glob.glob(os.path.join(chunk_dir, "chunk_*.mp4")), key=lambda f: int(f.rsplit("_", 1)[1][:-4]))
        lst = os.path.join(self.cache, "concat.txt")
        with open(lst, "w") as f:
            for p_ in files:
                f.write(f"file '{os.path.abspath(p_)}'\n")
        vid = os.path.join(self.cache, "video.mp4")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", vid], check=True)
        wav = mix(tl.duration, tl.speech, tl.sfx, tl.music, tl.mood, os.path.join(self.cache, "mix.wav"),
                  music_plan=tl.music_plan)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", vid, "-i", wav, "-map", "0:v", "-map", "1:a",
                        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", out], check=True)
        print(f"Готово: {out} из {len(files)} кусков")

    def plan(self):
        tl = self.tl
        for sc in tl.scenes:
            print(f"== {sc['name']}  {sc['t0']:.1f}–{sc['t1']:.1f} с")
            for s in tl.shots:
                if sc["t0"] <= s["t0"] < sc["t1"]:
                    k, a, b = s["spec"]
                    print(f"   {s['t0']:6.1f}–{s['t1']:5.1f}  камера: {k} {a or ''} {b or ''}")
            for s in tl.speech:
                if sc["t0"] <= s["t0"] < sc["t1"]:
                    print(f"   {s['t0']:6.1f}  {s['char']}: {s['text']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("script")
    ap.add_argument("-o", "--out")
    ap.add_argument("--preview", action="store_true", help="быстрый черновик 360p")
    ap.add_argument("--stills", help="времена кадров через запятую, напр. 2,10.5")
    ap.add_argument("--plan", action="store_true", help="только показать раскадровку")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--chunk", help="i/n — отрендерить только i-й из n кусков (облачный рендер)")
    ap.add_argument("--assemble", help="папка с chunk_*.mp4 — склеить и добавить звук")
    a = ap.parse_args()
    film = Film(a.script, preview=a.preview, fps=a.fps)
    if a.chunk:
        i, n = map(int, a.chunk.split("/"))
        film.render_chunk(i, n, a.out or f"chunk_{i}.mp4"); return
    if a.assemble:
        film.assemble(a.assemble, a.out or film.base + ".mp4"); return
    if a.plan:
        film.plan(); return
    if a.stills:
        ps = film.stills([float(x) for x in a.stills.split(",")], os.path.join(os.path.dirname(film.base), "stills"))
        print("\n".join(ps)); return
    film.render(a.out)


if __name__ == "__main__":
    main()
