"""Action library + per-frame character simulation."""
import math
import random

PI = math.pi
sin, cos = math.sin, math.cos


def clamp(v, a, b):
    return a if v < a else b if v > b else v


def wrap(a):
    return (a + PI) % (2 * PI) - PI


def smooth(u):
    u = clamp(u, 0, 1)
    return u * u * (3 - 2 * u)


# --------------------------------------------------------------- actions
# Each: f(u, d) -> dict of channels (angles in rad; oy in px; ox in blocks)
def a_wave(u, d):
    return dict(ra_r=2.75 + 0.28 * sin(u * 11), ra_p=0.15, h_r=0.06 * sin(u * 5))


def a_jump(u, d):
    s = (u - 0.1) / 0.5
    if u < 0.1:
        return dict(oy=-1.5 * u / 0.1, b_p=0.15)
    if s <= 1:
        air = 4 * s * (1 - s)
        return dict(oy=19 * air, ra_r=0.55 * air + 0.1, la_r=0.55 * air + 0.1, rl_p=0.35 * air, ll_p=-0.25 * air)
    k = clamp((u - 0.6) / 0.15, 0, 1)
    return dict(oy=-1.5 * sin(PI * k), b_p=0.12 * sin(PI * k))


def a_nod(u, d):
    return dict(h_p=-0.4 * sin(u / d * 2 * PI * 2) ** 2)


def a_shake(u, d):
    return dict(h_y=0.5 * sin(u * 15) * (1 - u / d))


def a_point(u, d):
    return dict(ra_p=1.55 + 0.04 * sin(u * 3), ra_y=0.08)


def a_dance(u, d):
    f = u * 2 * PI * 1.8
    return dict(oy=2.0 * abs(sin(f)), ra_r=1.3 + 1.0 * sin(f), la_r=1.3 - 1.0 * sin(f), b_r=0.12 * sin(f),
                h_r=0.15 * sin(f), rl_r=0.2 * max(0, sin(f)), ll_r=0.2 * max(0, -sin(f)), yaw_off=0.35 * sin(f / 2),
                h_p=0.1)


def a_laugh(u, d):
    f = u * 2 * PI * 4.5
    return dict(b_p=-0.12 + 0.05 * sin(f), h_p=0.35 + 0.08 * sin(f), ra_p=0.55, la_p=0.55, ra_r=-0.25, la_r=-0.25,
                oy=0.7 * abs(sin(f)))


def a_surprise(u, d):
    s = smooth(u / 0.15)
    hop = 4 * sin(PI * min(1, u / 0.35)) if u < 0.35 else 0
    return dict(ra_r=2.3 * s, la_r=2.3 * s, ra_p=0.35 * s, la_p=0.35 * s, h_p=0.25 * s, oy=hop, b_p=-0.1 * s)


def a_celebrate(u, d):
    s = (u % 0.65) / 0.65
    return dict(oy=10 * 4 * s * (1 - s), ra_r=2.95 + 0.12 * sin(u * 14), la_r=2.95 + 0.12 * sin(u * 14 + 1),
                h_p=0.3, rl_p=0.2 * sin(PI * s), ll_p=-0.2 * sin(PI * s))


def a_punch(u, d):
    k = u / d
    p = sin(PI * clamp(k * 1.6, 0, 1))
    return dict(ra_p=1.65 * p, ra_y=-0.15 * p, b_y=-0.35 * p, la_p=0.4 * p)


def a_facepalm(u, d):
    return dict(ra_p=2.25, ra_r=-0.6, h_p=-0.35, b_p=0.18)


def a_shrug(u, d):
    s = sin(PI * clamp(u / d, 0, 1))
    return dict(ra_r=0.55 * s, la_r=0.55 * s, ra_p=0.5 * s, la_p=0.5 * s, h_r=0.22 * s, h_p=0.05 * s)


def a_think(u, d):
    return dict(ra_p=2.05, ra_r=-0.55, la_p=0.6, la_r=-0.45, h_r=0.14, h_p=0.2 + 0.04 * sin(u * 2))


def a_scared(u, d):
    return dict(ox=0.025 * sin(u * 45), ra_p=1.35, la_p=1.35, ra_r=-0.25, la_r=-0.25, b_p=-0.12, h_p=0.12)


def a_angry(u, d):
    return dict(ra_r=0.35, la_r=0.35, ra_p=-0.3, la_p=-0.3, b_p=0.12, h_p=-0.18, oy=1.5 * abs(sin(u * 11)),
                ox=0.01 * sin(u * 30))


def a_crouch(u, d):
    return dict(b_p=0.5, oy=-2.5, h_p=0.35, ra_p=0.35, la_p=0.35)


def a_lookaround(u, d):
    return dict(h_y=0.9 * sin(u / d * 2 * PI), h_p=0.1)


def a_cry(u, d):
    return dict(ra_p=2.3, la_p=2.3, ra_r=-0.5, la_r=-0.5, h_p=-0.3, oy=0.6 * abs(sin(u * 9)), b_p=0.15)


def a_bow(u, d):
    s = sin(PI * clamp(u / d, 0, 1))
    return dict(b_p=0.7 * s, h_p=-0.2 * s)


def a_clap(u, d):
    return dict(ra_p=1.2, la_p=1.2, ra_r=-0.45 + 0.25 * abs(sin(u * 14)), la_r=-0.45 + 0.25 * abs(sin(u * 14)))


def a_spit(u, d):
    k = sin(PI * clamp(u / 0.45, 0, 1))
    return dict(h_p=-0.15 * k + 0.1, b_p=0.18 * k, oy=-0.5 * k)


def a_struggle(u, d):
    return dict(ra_p=1.35, la_p=1.35, ra_r=-0.4, la_r=-0.4, b_p=0.12 + 0.04 * sin(u * 23),
                ox=0.012 * sin(u * 47), h_p=-0.1 + 0.05 * sin(u * 31), oy=0.4 * sin(u * 37))


def a_grab(u, d):
    return dict(ra_p=1.55 + 0.1 * sin(u * 9), la_p=1.55 + 0.1 * sin(u * 9 + 2), ra_r=-0.1, la_r=-0.1, b_p=0.08)


def a_love(u, d):
    return dict(ra_p=0.9, la_p=0.9, ra_r=-0.45, la_r=-0.45, h_r=0.2 * sin(u * 3), b_r=0.06 * sin(u * 3),
                oy=0.6 * abs(sin(u * 3)))


def a_eat(u, d):
    return dict(ra_p=1.9 + 0.2 * sin(u * 16), ra_r=-0.35, h_p=-0.05 + 0.05 * sin(u * 16))


def a_hide(u, d):
    return dict(b_p=0.5, oy=-2.5, h_p=0.2, ra_p=1.0, la_p=1.0, ra_r=-0.3, la_r=-0.3)


def a_lazy(u, d):
    return dict(ra_r=0.25, la_r=0.25, h_r=0.25, b_r=0.05, h_p=-0.1)


def a_spin(u, d):
    return dict(yaw_off=u * 2 * PI * 1.2)


def a_wobble(u, d):
    return dict(b_r=0.3 * sin(u * 7), r_p=0.12 * sin(u * 5))


def a_flex(u, d):
    s = smooth(u / 0.3)
    return dict(ra_r=1.6 * s, la_r=1.6 * s, ra_p=0.3 * s, la_p=0.3 * s, b_p=-0.08 * s, h_p=0.15 * s,
                oy=0.4 * sin(u * 10) * s)


ACTIONS = {
    "wave": (a_wave, 1.6), "jump": (a_jump, 0.8), "nod": (a_nod, 0.9), "shake": (a_shake, 0.9),
    "point": (a_point, 1.6), "dance": (a_dance, 3.5), "laugh": (a_laugh, 1.6), "surprise": (a_surprise, 1.0),
    "celebrate": (a_celebrate, 2.0), "punch": (a_punch, 0.55), "facepalm": (a_facepalm, 1.8),
    "shrug": (a_shrug, 1.1), "think": (a_think, 2.2), "scared": (a_scared, 1.8), "angry": (a_angry, 1.6),
    "crouch": (a_crouch, 1.2), "lookaround": (a_lookaround, 2.0), "cry": (a_cry, 2.0), "bow": (a_bow, 1.2),
    "clap": (a_clap, 1.5), "spit": (a_spit, 0.7), "struggle": (a_struggle, 2.0), "grab": (a_grab, 1.6),
    "spin": (a_spin, 2.0), "wobble": (a_wobble, 2.0),
    "love": (a_love, 2.0), "eat": (a_eat, 1.4), "hide": (a_hide, 2.0), "lazy": (a_lazy, 2.0), "flex": (a_flex, 1.6),
}
BIG_ACTIONS = {"spit", "struggle", "grab", "love", "eat", "hide", "flex", "fall", "jump", "dance", "celebrate", "surprise", "facepalm", "scared", "angry", "crouch", "cry", "bow",
               "point", "punch", "clap", "think"}

LIB = None  # engine.library.Library, set by the compiler

CHANNELS = ["lift", "r_p", "ra_p", "ra_r", "ra_y", "la_p", "la_r", "la_y", "rl_p", "ll_p", "rl_r", "ll_r",
            "h_p", "h_y", "h_r", "b_p", "b_r", "b_y", "oy", "ox", "yaw_off"]


# ------------------------------------------------------------------ sim
class CharSim:
    def __init__(self, name, tl, world):
        self.name = name
        self.tl = tl
        self.world = world
        self.moves = [m for m in tl.moves if m["char"] == name]
        cum = 0.0
        for m in self.moves:
            m["cum"] = cum
            cum += m["dist"]
        self.clips = [c for c in tl.clips if c["char"] == name]
        self.looks = sorted([l for l in tl.looks if l["char"] == name], key=lambda l: l["t"])
        self.faces = sorted([f for f in tl.faces if f["char"] == name], key=lambda f: f["t"])
        self.sits = sorted([s for s in tl.sits if s["char"] == name], key=lambda s: s["t"])
        self.speech = [s for s in tl.speech if s["char"] == name]
        self.vis = sorted([v for v in tl.vis if v["char"] == name], key=lambda v: v["t"])
        self.items = sorted([v for v in tl.items if v["char"] == name], key=lambda v: v["t"])
        self.lies = sorted([v for v in tl.lies if v["char"] == name], key=lambda v: v["t"])
        self.poses = sorted([v for v in tl.poses if v["char"] == name], key=lambda v: v["t"])
        self.pose_w = 0.0
        self.mob = tl.cast[name].get("mob")
        self.attach = sorted([v for v in tl.attach if v["char"] == name], key=lambda v: v["t"])
        self.scales = sorted([v for v in tl.scales if v["char"] == name], key=lambda v: v["t"])
        self.widths = sorted([v for v in tl.widths if v["char"] == name], key=lambda v: v["t"])
        self.base_lift = tl.cast[name].get("lift", 0.0)
        self.base_scale = tl.cast[name].get("scale", 1.0)
        self.hidden0 = tl.cast[name].get("hidden", False)
        self.start = tl.cast[name]["pos"]
        self.start_yaw = tl.cast[name].get("yaw")
        rnd = random.Random(__import__("zlib").crc32(name.encode()))
        self.blinks = []
        t = rnd.uniform(0.5, 2.5)
        while t < tl.duration + 5:
            self.blinks.append(t)
            t += rnd.uniform(1.8, 4.8)
        self.gseed = rnd.uniform(0, 100)
        self.reset_needed = True

    # -------- queries
    def pos_at(self, t):
        x, z = self.start
        dist = 0.0
        moving, mv = False, None
        for m in self.moves:
            if t >= m["t1"]:
                x, z = m["to"]; dist = m["cum"] + m["dist"]
            elif t >= m["t0"]:
                u = (t - m["t0"]) / (m["t1"] - m["t0"])
                k = 0.85
                s = u - k * sin(2 * PI * u) / (2 * PI)
                ax, az = m["from"]; bx, bz = m["to"]
                x, z = ax + (bx - ax) * s, az + (bz - az) * s
                dist = m["cum"] + m["dist"] * s
                moving, mv = True, m
                break
            else:
                break
        return x, z, dist, moving, mv

    def lift_at(self, t):
        h = self.base_lift
        for m in self.moves:
            if "h1" not in m:
                continue
            if t >= m["t1"]:
                h = m["h1"]
            elif t >= m["t0"]:
                u = (t - m["t0"]) / max(1e-6, m["t1"] - m["t0"])
                h = m["h0"] + (m["h1"] - m["h0"]) * smooth(u)
                break
            else:
                break
        return h

    def scale_at(self, t):
        s = self.base_scale
        for e in self.scales:
            if t >= e["t"] + 0.6:
                s = e["v"]
            elif t >= e["t"]:
                s = s + (e["v"] - s) * smooth((t - e["t"]) / 0.6)
                break
        return s

    def width_at(self, t):
        s = 1.0
        for e in self.widths:
            if t >= e["t"] + 0.6:
                s = e["v"]
            elif t >= e["t"]:
                s = s + (e["v"] - s) * smooth((t - e["t"]) / 0.6)
                break
        return s

    def target_point(self, tgt, t, poses_xz):
        if tgt is None:
            return None
        kind = tgt[0]
        if kind == "char":
            p = poses_xz.get(tgt[1])
            if p is None:
                return None
            from .mobs import EYE
            c_ = self.tl.cast.get(tgt[1], {})
            eh = (EYE.get(c_.get("mob"), 1.0) if c_.get("mob") else 1.55) * c_.get("scale", 1.0)
            return (p[0], self.world.ground(p[0], p[1]) + eh + p[2], p[1])
        if kind == "point":
            x, z = tgt[1]
            return (x, self.world.ground(x, z) + 1.2, z)
        if kind == "yaw":
            x0, z0 = self.pos_at(t)[:2]
            return (x0 + sin(tgt[1]) * 5, self.world.ground(x0, z0) + 1.55, z0 + cos(tgt[1]) * 5)
        if kind == "cam":
            return tuple(self.cam_eye) if getattr(self, "cam_eye", None) is not None else None
        return None

    def latest(self, lst, t):
        r = None
        for e in lst:
            if e["t"] <= t:
                r = e
            else:
                break
        return r

    # -------- step
    def step(self, t, dt, poses_xz, cam_eye, snap=False):
        self.cam_eye = cam_eye
        x, z, dist, moving, mv = self.pos_at(t)
        gy = self.world.ground(x, z)
        if self.reset_needed or snap:
            self.yaw = self.start_yaw if self.start_yaw is not None else 0.0
            self.h_y = self.h_p = 0.0
            self.walk_amp = 0.0
            self.sit_w = 0.0
            self.lie_w = 0.0
            self.prev_xz = (x, z)
            self.reset_needed = False
            snap = True
        # body yaw target
        yaw_t = self.yaw
        if moving:
            fx, fz = mv["to"][0] - mv["from"][0], mv["to"][1] - mv["from"][1]
            if abs(fx) + abs(fz) > 1e-4:
                yaw_t = math.atan2(fx, fz)
        else:
            look = self.latest(self.looks, t)
            face = self.latest(self.faces, t)
            last_move_end = max([m["t1"] for m in self.moves if m["t1"] <= t] or [-1])
            ft = face if face and face["t"] >= last_move_end - 0.01 else None
            tp = self.target_point(ft["target"], t, poses_xz) if ft else None
            if tp is None and look:
                lp = self.target_point(look["target"], t, poses_xz)
                if lp is not None:
                    want = math.atan2(lp[0] - x, lp[2] - z)
                    if abs(wrap(want - self.yaw)) > 0.95:
                        tp = lp
            if tp is not None and (abs(tp[0] - x) + abs(tp[2] - z)) > 0.05:
                yaw_t = math.atan2(tp[0] - x, tp[2] - z)
        k = 1.0 if snap else 1 - math.exp(-dt * (10 if moving else 6))
        self.yaw = wrap(self.yaw + wrap(yaw_t - self.yaw) * k)
        # head look
        look = self.latest(self.looks, t)
        hy_t, hp_t = 0.0, 0.0
        if look and not (moving and look["t"] < (mv["t0"] if mv else 0)):
            lp = self.target_point(look["target"], t, poses_xz)
            if lp is not None:
                dx, dz = lp[0] - x, lp[2] - z
                dy = lp[1] - (gy + 1.62)
                hy_t = clamp(wrap(math.atan2(dx, dz) - self.yaw), -1.15, 1.15)
                hp_t = clamp(math.atan2(dy, math.hypot(dx, dz) + 1e-3), -0.6, 0.6)
        kh = 1.0 if snap else 1 - math.exp(-dt * 9)
        self.h_y += (hy_t - self.h_y) * kh
        self.h_p += (hp_t - self.h_p) * kh
        # sitting
        sit = self.latest(self.sits, t)
        sit_t = 1.0 if (sit and sit["on"]) else 0.0
        self.sit_w = sit_t if snap else self.sit_w + (sit_t - self.sit_w) * (1 - math.exp(-dt * 7))
        lie = self.latest(self.lies, t)
        lie_t = 1.0 if (lie and lie["on"]) else 0.0
        self.lie_w = lie_t if snap else self.lie_w + (lie_t - self.lie_w) * (1 - math.exp(-dt * 9))
        # walk amplitude
        run = bool(mv and mv.get("run"))
        amp_t = (0.95 if run else 0.62) if moving and not mv.get("fly") else 0.0
        self.walk_amp = amp_t if snap else self.walk_amp + (amp_t - self.walk_amp) * (1 - math.exp(-dt * 10))

        P = {c: 0.0 for c in CHANNELS}
        # idle
        P["ra_r"] = 0.05 + 0.04 * sin(t * 1.3 + self.gseed)
        P["la_r"] = 0.05 + 0.04 * sin(t * 1.1 + self.gseed + 1)
        P["ra_p"] = 0.04 * sin(t * 0.9 + self.gseed)
        P["la_p"] = -0.04 * sin(t * 0.9 + self.gseed)
        P["oy"] = 0.25 * sin(t * 2.2 + self.gseed)
        # walk
        ph = dist / (1.25 if run else 0.95) * 2 * PI
        a = self.walk_amp
        sw = sin(ph)
        P["rl_p"] += a * sw; P["ll_p"] -= a * sw
        P["ra_p"] -= a * 0.85 * sw; P["la_p"] += a * 0.85 * sw
        P["oy"] += a * 1.2 * abs(cos(ph))
        if run:
            P["b_p"] += 0.18 * a
        if moving and mv.get("sneak"):
            for c_, v_ in dict(b_p=0.45, oy=-2.5, h_p=0.25).items():
                P[c_] += v_
            P["rl_p"] *= 0.6; P["ll_p"] *= 0.6
        # head
        P["h_y"] = self.h_y
        P["h_p"] = self.h_p
        # talking: mouth + head bob + gestures
        mouth = 0
        for s in self.speech:
            if s["t0"] <= t < s["t1"]:
                i = int((t - s["t0"]) * 30)
                env = s["env"]
                lv = env[i] if 0 <= i < len(env) else 0.0
                mouth = 0 if lv < 0.18 else (1 if lv < 0.55 else 2)
                P["h_p"] += 0.07 * lv - 0.02
                P["h_r"] += 0.04 * sin(t * 3.1 + self.gseed)
                g = t * 1.7 + self.gseed
                ga = smooth((t - s["t0"]) / 0.4) * smooth((s["t1"] - t) / 0.4)
                if not moving:
                    P["ra_p"] += ga * (0.25 + 0.25 * sin(g) + 0.15 * lv)
                    P["ra_r"] += ga * 0.12 * (1 + sin(g * 1.3))
                    P["la_p"] += ga * (0.1 + 0.18 * sin(g * 0.8 + 2))
                break
        # sit pose
        if self.sit_w > 0.001:
            w = self.sit_w
            for c, v in dict(rl_p=1.45, ll_p=1.45, rl_r=0.12, ll_r=0.12, oy=-10.0 + P["oy"] * 0.3,
                             ra_p=0.55, la_p=0.55).items():
                P[c] = P[c] * (1 - w) + v * w
        # actions
        for c in self.clips:
            if c["t0"] <= t < c["t1"] + 0.25:
                fn, _ = ACTIONS[c["kind"]]
                d = c["t1"] - c["t0"]
                u = min(t - c["t0"], d)
                fi = min(0.15, d / 3)
                w = smooth((t - c["t0"]) / fi) * smooth((c["t1"] + 0.25 - t) / 0.25)
                vals = fn(u, d)
                for ch, v in vals.items():
                    if ch in ("oy", "ox", "lift"):
                        P[ch] += v * w
                    else:
                        if ch in ("r_p", "yaw_off"):
                            v = wrap(v)
                        P[ch] = P[ch] * (1 - w) + v * w
        # persistent pose (from the text library)
        pe = self.latest(self.poses, t)
        want = pe["name"] if pe and pe["name"] in ACTIONS else None
        if want:
            self.pose_name = want
        self.pose_w = (1.0 if want else 0.0) if snap else self.pose_w + ((1.0 if want else 0.0) - self.pose_w) * (1 - math.exp(-dt * 8))
        if self.pose_w > 0.001 and getattr(self, "pose_name", None):
            fn, d = ACTIONS[self.pose_name]
            for ch, v in fn((t - (pe["t"] if pe else 0)), d).items():
                if ch in ("oy", "ox", "lift"):
                    P[ch] += v * self.pose_w
                else:
                    P[ch] = P[ch] * (1 - self.pose_w) + v * self.pose_w
        if self.lie_w > 0.001:
            w = self.lie_w
            P["r_p"] = -PI / 2 * w
            P["oy"] = P["oy"] * (1 - w) + 2.2 * w
            for c, v in dict(ra_r=0.5, la_r=0.5, rl_r=0.15, ll_r=0.15, h_y=P["h_y"] * 0.3, h_p=0.0).items():
                P[c] = P[c] * (1 - w) + v * w
        if self.mob == "chicken":  # wings flap while walking
            P["ra_r"] = P["la_r"] = 0.6 * abs(sin(t * 18)) * self.walk_amp
        if self.mob in ("zombie", "zombie_pigman", "zombie_villager"):
            P["ra_p"] += 1.45; P["la_p"] += 1.45
        it_ = self.latest(self.items, t)
        if it_ and it_.get("carry") and it_.get("item"):
            P["ra_p"], P["la_p"], P["ra_r"], P["la_r"] = 1.05, 1.05, -0.12, -0.12
        # blink
        blink = 1 if any(b <= t < b + 0.13 for b in self.blinks) else 0
        v = self.latest(self.vis, t)
        hidden = (not v["on"]) if v else self.hidden0
        it = self.latest(self.items, t)
        att = self.latest(self.attach, t)
        P["lift"] += self.lift_at(t)
        P["scale"] = self.scale_at(t)
        P["width"] = self.width_at(t)
        if self.mob in ("slime", "magma_cube") and moving:  # slimes hop instead of walking
            P["oy"] += 7 * abs(sin(dist / 0.9 * PI))
        P["attach"] = (att["to"], att["slot"]) if att and att["to"] else None
        P.update(name=self.name, x=x + P["ox"] * cos(self.yaw), y=gy + P["lift"], z=z - P["ox"] * sin(self.yaw),
                 yaw=self.yaw + P["yaw_off"], mouth=0 if self.mob else mouth, blink=blink, hidden=hidden, mob=self.mob,
                 item=(it["item"] if it else None), t=t)
        P["ox"] = 0
        self.last = P
        self.prev_xz = (x, z)
        return P


# ------------------------------------------------------------- particles
def _head(p, up=1.62):
    return (p["x"], p["y"] + up, p["z"])


def particles_at(t, events, P):
    """events: dicts kind,t0,t1,char,target. P: name->pose. Returns list (x,y,z,size,(r,g,b))."""
    out = []
    for e in events:
        if not (e["t0"] <= t < e["t1"] + 1.5):
            continue
        A = P.get(e["char"])
        if e["kind"] == "gen":
            if LIB is None or e["name"] not in LIB.effects:
                continue
            from .library import effect_particles
            B = P.get(e["target"][1]) if e.get("target") and e["target"][0] == "char" else None
            if A is not None and A.get("hidden"):
                continue
            out += effect_particles(LIB.effects[e["name"]], e, t, A, B)
            continue
        if A is None or A.get("hidden") and e["kind"] != "poof":
            continue
        u = t - e["t0"]
        k = e["kind"]
        hx, hy, hz = _head(A, 1.5 if not A.get("mob") else 1.0)
        fx, fz = sin(A["yaw"]), cos(A["yaw"])
        rnd = random.Random(int(e["t0"] * 1000) + len(e["char"]))
        if k == "spit":
            B = P.get(e["target"][1]) if e.get("target") and e["target"][0] == "char" else None
            sx, sy, sz = hx + fx * 0.35, hy - 0.15, hz + fz * 0.35
            if B is not None:
                tx, ty, tz = _head(B, 1.5)
            else:
                tx, ty, tz = sx + fx * 3, sy - 0.3, sz + fz * 3
            fly = 0.35
            if u < fly:
                for i in range(6):
                    s_ = clamp(u / fly - i * 0.06, 0, 1)
                    if s_ <= 0:
                        continue
                    out.append((sx + (tx - sx) * s_, sy + (ty - sy) * s_ + 0.35 * 4 * s_ * (1 - s_),
                                sz + (tz - sz) * s_, 0.09 - i * 0.008, (0.93, 0.95, 0.88)))
            elif B is not None and u < fly + 2.2:
                bx, by, bz = _head(B, 1.45)
                bf = (sin(B["yaw"]), cos(B["yaw"]))
                drip = (u - fly) * 0.08
                for i in range(7):
                    ox, oy = rnd.uniform(-0.2, 0.2), rnd.uniform(-0.15, 0.15)
                    out.append((bx + bf[0] * 0.27 + bf[1] * ox, by + oy - drip * (i % 3), bz + bf[1] * 0.27 - bf[0] * ox,
                                0.07, (0.92, 0.94, 0.86)))
        elif k == "hearts":
            if u > e["t1"] - e["t0"] + 1.0:
                continue
            for i in range(int(u / 0.3) + 1):
                age = u - i * 0.3
                if age < 0 or age > 1.3 or i * 0.3 > e["t1"] - e["t0"]:
                    continue
                ox = rnd.uniform(-0.35, 0.35) + 0.08 * sin(age * 6)
                y = hy + 0.45 + age * 0.6
                s_ = 0.11 * (1 - age / 1.6)
                for dx, dy in ((-0.5, 0.5), (0.5, 0.5), (0, 0)):
                    out.append((hx + ox * fz + dx * s_ * fz, y + dy * s_, hz - ox * fx - dx * s_ * fx, s_, (0.95, 0.15, 0.3)))
        elif k == "sweat":
            if u > e["t1"] - e["t0"]:
                continue
            for i in range(int(u / 0.22) + 1):
                age = u - i * 0.22
                if age < 0 or age > 0.6:
                    continue
                side = 1 if i % 2 else -1
                out.append((hx + side * 0.32 * fz, hy + 0.3 - age * age * 2.5, hz - side * 0.32 * fx, 0.06, (0.4, 0.7, 1.0)))
        elif k == "anger":
            if u > e["t1"] - e["t0"]:
                continue
            pul = 0.08 + 0.03 * abs(sin(u * 10))
            cx, cy, cz = hx + 0.25 * fz, hy + 0.55, hz - 0.25 * fx
            for dx, dy in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
                out.append((cx + dx * pul * fz, cy + dy * pul, cz - dx * pul * fx, pul, (0.9, 0.1, 0.1)))
        elif k == "stars":
            if u > 1.3:
                continue
            for i in range(5):
                a = u * 6 + i * 2 * PI / 5
                out.append((hx + cos(a) * 0.45, hy + 0.5 + 0.05 * sin(a * 2), hz + sin(a) * 0.45, 0.08, (1.0, 0.9, 0.2)))
        elif k == "poof":
            if u > 0.7:
                continue
            for i in range(16):
                a = i * 2 * PI / 16 + rnd.uniform(0, 0.4)
                r = 0.2 + u * 1.6
                out.append((A["x"] + cos(a) * r, A["y"] + 0.3 + (i % 4) * 0.4 + u * 0.5, A["z"] + sin(a) * r,
                            0.22 * (1 - u / 0.7), (0.85, 0.85, 0.85)))
        elif k == "dart":
            B = P.get(e["target"][1]) if e.get("target") else None
            if B is None or u > e["t1"] - e["t0"]:
                continue
            s_ = u / (e["t1"] - e["t0"])
            sx, sy, sz = A["x"] + fx * 0.6, A["y"] + 1.35, A["z"] + fz * 0.6
            tx, ty, tz = B["x"], B["y"] + 1.35, B["z"]
            px, py, pz = sx + (tx - sx) * s_, sy + (ty - sy) * s_, sz + (tz - sz) * s_
            ddx, ddz = (tx - sx), (tz - sz); L = math.hypot(ddx, ddz) or 1
            for i in range(4):
                out.append((px - ddx / L * i * 0.08, py, pz - ddz / L * i * 0.08, 0.05,
                            (0.9, 0.15, 0.15) if i == 3 else (0.35, 0.35, 0.4)))
        elif k == "dart_stuck":
            lying = A.get("r_p", 0) < -0.7
            if lying:
                bx, by, bz = A["x"] - fx * 1.3, A["y"] + 0.35, A["z"] - fz * 1.3
            else:
                bx, by, bz = A["x"] + fz * 0.2, A["y"] + 1.4, A["z"] - fx * 0.2
            for i in range(3):
                out.append((bx + fz * 0.07 * i, by + 0.07 * i, bz - fx * 0.07 * i, 0.045,
                            (0.9, 0.15, 0.15) if i == 2 else (0.35, 0.35, 0.4)))
        elif k == "zzz":
            if u > e["t1"] - e["t0"]:
                continue
            for i in range(3):
                age = (u + i * 0.6) % 1.8
                out.append((hx + age * 0.3, hy + 0.4 + age * 0.5, hz, 0.05 + age * 0.05, (1, 1, 1)))
    return out
