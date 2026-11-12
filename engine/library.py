"""Text-defined actions, poses and particle effects (no code needed for new ones).

  действие отжимается: длит=3 повтор=0.9 звук=random_hurt@0.5 эффект=пот@0
    0:    наклон_всего=80 руки=90 высота=0.25
    0.45: наклон_всего=80 руки=55 высота=0.05
  поза руки_в_боки: руки_вбок=45 руки=-25
  эффект слёзы: цвет=#5ab0ff размер=0.06 частота=8 откуда=глаза скорость=0.3,0.4,0 разброс=0.1 гравитация=4 жизнь=0.7
"""
import math
import random
import re

DEG = math.pi / 180
PX = 16 / 0.9375  # model px per block

CH = {  # russian channel -> list of (internal channel, scale)
    "голова_вверх": [("h_p", DEG)], "голова_поворот": [("h_y", DEG)], "голова_наклон": [("h_r", DEG)],
    "тело_вперёд": [("b_p", DEG)], "тело_вбок": [("b_r", DEG)], "тело_поворот": [("b_y", DEG)],
    "правая_рука": [("ra_p", DEG)], "правая_рука_вбок": [("ra_r", DEG)], "правая_рука_поворот": [("ra_y", DEG)],
    "левая_рука": [("la_p", DEG)], "левая_рука_вбок": [("la_r", DEG)], "левая_рука_поворот": [("la_y", DEG)],
    "руки": [("ra_p", DEG), ("la_p", DEG)], "руки_вбок": [("ra_r", DEG), ("la_r", DEG)],
    "руки_поворот": [("ra_y", DEG), ("la_y", DEG)],
    "правая_нога": [("rl_p", DEG)], "левая_нога": [("ll_p", DEG)], "ноги": [("rl_p", DEG), ("ll_p", DEG)],
    "правая_нога_вбок": [("rl_r", DEG)], "левая_нога_вбок": [("ll_r", DEG)], "ноги_вбок": [("rl_r", DEG), ("ll_r", DEG)],
    "высота": [("lift", 1.0)], "сдвиг": [("ox", 1.0)], "разворот": [("yaw_off", DEG)], "наклон_всего": [("r_p", DEG)],
}
for k in list(CH):
    CH[k.replace("ё", "е")] = CH[k]
ADDITIVE = {"oy", "ox", "lift"}


def _parse_kv(s):
    return dict(re.findall(r"([^\s=]+)=([^\s]+)", s))


def _chan_values(kv):
    out = {}
    for k, v in kv.items():
        k2 = k.lower()
        if k2 in CH:
            try:
                val = float(v)
            except ValueError:
                continue
            for ch, sc in CH[k2]:
                out[ch] = val * sc
    return out


def _smooth(u):
    u = 0.0 if u < 0 else 1.0 if u > 1 else u
    return u * u * (3 - 2 * u)


class TextAction:
    def __init__(self, name, opts, frames):
        self.name = name
        self.dur = float(opts.get("длит", 1.5))
        self.loop = float(opts["повтор"]) if "повтор" in opts else None
        self.sounds = [x for x in opts.get("звук", "").split(",") if x]
        self.effects = [x for x in opts.get("эффект", "").split(",") if x]
        self.aliases = [x for x in opts.get("алиасы", "").split(",") if x]
        self.big = opts.get("тело", "да") != "нет"
        self.frames = sorted(frames, key=lambda f: f[0]) or [(0.0, {})]
        keys = set()
        for _, v in self.frames:
            keys |= set(v)
        # fill missing channels with previous value (or 0)
        last = {k: 0.0 for k in keys}
        filled = []
        for t, v in self.frames:
            last = dict(last); last.update(v); filled.append((t, last))
        self.frames = filled

    def __call__(self, u, d):
        fr = self.frames
        if len(fr) == 1:
            return dict(fr[0][1])
        if self.loop:
            T = self.loop
            x = u % T
            pts = fr + [(T, fr[0][1])]
        else:
            x = u * (fr[-1][0] / d) if fr[-1][0] > d else u
            pts = fr
        for (t0, a), (t1, b) in zip(pts, pts[1:]):
            if t0 <= x <= t1:
                w = _smooth((x - t0) / max(1e-6, t1 - t0))
                return {k: a.get(k, 0) + (b.get(k, 0) - a.get(k, 0)) * w for k in a}
        return dict(pts[-1][1])


class Effect:
    def __init__(self, name, opts):
        self.name = name
        cols = opts.get("цвет", "#ffffff").split(",")
        self.colors = [tuple(int(c.strip("#")[i:i + 2], 16) / 255 for i in (0, 2, 4)) for c in cols]
        self.size = float(opts.get("размер", 0.08))
        self.rate = float(opts.get("частота", 10))
        self.burst = int(opts.get("число", 0))
        self.src = opts.get("откуда", "голова")
        v = [float(x) for x in opts.get("скорость", "0,0,0").split(",")]
        self.vel = (v + [0, 0, 0])[:3]  # forward, up, side
        self.spread = float(opts.get("разброс", 0.2))
        self.grav = float(opts.get("гравитация", 0))
        self.life = float(opts.get("жизнь", 1.0))
        self.grow = float(opts.get("растёт", opts.get("растет", 1.0)))
        self.area = float(opts.get("область", 0.0))
        self.swirl = float(opts.get("кружит", 0))
        self.fly = opts.get("полёт", opts.get("полет"))  # 'цель' -> projectile to target
        self.arc = float(opts.get("дуга", 0.6))


SRC = {"голова": (0, 1.55, 0), "глаза": (0.27, 1.62, 0), "рот": (0.28, 1.45, 0), "над_головой": (0, 2.25, 0),
       "тело": (0, 1.0, 0), "рука": (0.35, 1.0, -0.35), "ноги": (0, 0.15, 0), "земля": (0, 0.05, 0)}


def effect_particles(e, ev, t, A, B=None):
    """Generic emitter -> list of (x,y,z,size,(r,g,b))."""
    out = []
    u = t - ev["t0"]
    dur = ev["t1"] - ev["t0"]
    if u < 0 or u > dur + e.life:
        return out
    yaw = A.get("yaw", 0) if A else 0
    fx, fz = math.sin(yaw), math.cos(yaw)
    sx, sz = fz, -fx  # side
    if A is not None:
        ox, oy, oz = SRC.get(e.src, SRC["голова"])
        bx = A["x"] + fx * ox + sx * oz
        by = A["y"] + oy
        bz = A["z"] + fz * ox + sz * oz
    else:
        bx, by, bz = ev["point"][0], ev["point"][1], ev["point"][2]
    if e.fly and B is not None:
        tx, ty, tz = B["x"], B["y"] + 1.3, B["z"]
        s = min(1.0, u / max(0.05, dur))
        if u > dur:
            return out
        x = bx + (tx - bx) * s; z = bz + (tz - bz) * s
        y = by + (ty - by) * s + e.arc * 4 * s * (1 - s)
        out.append((x, y, z, e.size, e.colors[0]))
        for i in range(1, 4):
            s2 = max(0.0, s - i * 0.04)
            out.append((bx + (tx - bx) * s2, by + (ty - by) * s2 + e.arc * 4 * s2 * (1 - s2), bz + (tz - bz) * s2,
                        e.size * (1 - i * 0.22), e.colors[-1]))
        return out
    seed = int(ev["t0"] * 1000) + len(ev.get("char") or "")
    n_rate = int(e.rate * dur)
    spawns = [ev["t0"]] * e.burst + [ev["t0"] + (k + 0.5) / e.rate for k in range(n_rate)]
    for k, ts in enumerate(spawns):
        age = t - ts
        if age < 0 or age > e.life:
            continue
        r = random.Random(seed * 7919 + k)
        vx = e.vel[0] * fx + e.vel[2] * sx + r.uniform(-1, 1) * e.spread
        vz = e.vel[0] * fz + e.vel[2] * sz + r.uniform(-1, 1) * e.spread
        vy = e.vel[1] + r.uniform(-1, 1) * e.spread
        ax_ = r.uniform(-1, 1) * e.area; az_ = r.uniform(-1, 1) * e.area
        x = bx + ax_ + vx * age
        z = bz + az_ + vz * age
        y = by + vy * age - 0.5 * e.grav * age * age
        if e.swirl:
            x += math.sin(age * 5 + k) * 0.15 * e.swirl; z += math.cos(age * 5 + k) * 0.15 * e.swirl
        g = 1 + (e.grow - 1) * (age / e.life)
        size = e.size * g * (1 if age < e.life * 0.8 else max(0.1, (e.life - age) / (e.life * 0.2)))
        out.append((x, y, z, size, e.colors[k % len(e.colors)]))
    return out


class Library:
    def __init__(self):
        self.actions = {}
        self.effects = {}

    def load_file(self, path):
        with open(path, encoding="utf-8") as f:
            self.load_lines(f.read().splitlines())

    def load_lines(self, lines):
        cur = None
        for raw in lines:
            line = re.sub(r"(^|\s)#(\s.*|$)", "", raw).rstrip()
            if not line.strip():
                continue
            st = line.strip()
            m = re.match(r"^(действие|поза|эффект)\s+([^:]+):\s*(.*)$", st)
            if m:
                kind, name, rest = m.group(1), m.group(2).strip().lower(), m.group(3)
                kv = _parse_kv(rest)
                if kind == "эффект":
                    self.effects[name] = Effect(name, kv); cur = None
                elif kind == "поза":
                    a = TextAction(name, kv, [(0.0, _chan_values(kv))]); self.actions[name] = a; cur = None
                else:
                    cur = (name, kv, [])
                    self.actions[name] = TextAction(name, kv, [])
                continue
            m = re.match(r"^(\d+(?:\.\d+)?)\s*:\s*(.*)$", st)
            if m and cur:
                cur[2].append((float(m.group(1)), _chan_values(_parse_kv(m.group(2)))))
                self.actions[cur[0]] = TextAction(cur[0], cur[1], cur[2])

    @staticmethod
    def is_lib_line(st):
        return bool(re.match(r"^(действие|поза|эффект)\s+[^:]+:", st)) or bool(re.match(r"^\d+(?:\.\d+)?\s*:\s*\S+=", st))
