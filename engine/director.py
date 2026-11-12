"""Automatic camera director.

At every cut it tries several camera variants for the shot and keeps the one where the speaker is
in frame and not hidden by blocks or other characters. Inside a shot the camera follows smoothly
(speed-limited spring). Manual shots: fixed point and path.
"""
import math

from .mobs import EYE


def eye_h(p):
    sc = p.get("scale", 1.0)
    if p.get("mob"):
        return EYE.get(p["mob"], 1.0) * sc
    return (0.45 if abs(p.get("r_p", 0)) > 0.7 else 1.5) * sc

TRANSP = ("water", "glass")  # see-through for the camera (leaves are not)

VARIANTS = {  # (side, extra angle, distance multiplier, extra height)
    "close": [(1, 0, 1, 0), (1, 0.35, 1, 0), (1, -0.35, 1, 0), (1, 0, 1.35, 0.25), (-1, 0, 1, 0), (-1, 0.3, 1.2, 0.2),
              (1, 0, 0.8, 0.6), (1, 0.8, 0.9, 0.3), (1, -0.8, 0.9, 0.3), (-1, -0.4, 0.9, 0.5)],
    "medium": [(1, 0, 1, 0), (1, 0.4, 1, 0), (1, -0.4, 1, 0), (1, 0, 1.3, 0.5), (-1, 0, 1, 0), (1, 0, 0.7, 0.4),
               (1, 0, 0.9, 1.6), (1, 0.8, 0.8, 0.6), (1, -0.8, 0.8, 0.6), (-1, 0.5, 0.75, 0.8), (1, 0, 0.6, 2.4)],
    "ots": [(1, 0, 1, 0), (1, 0, 1.25, 0.25), (1, 0.25, 1, 0), (-1, 0, 1, 0), (1, 0, 0.8, 0.45), (1, 0, 1, 1.0)],
    "two": [(1, 0, 1, 0), (1, 0.3, 1, 0), (1, -0.3, 1, 0), (1, 0, 1.3, 0.6), (-1, 0, 1, 0), (1, 0, 0.75, 1.2),
            (1, 0.7, 0.85, 0.8), (1, -0.7, 0.85, 0.8), (1, 0, 1, 2.5)],
    "wide": [(1, 0, 1, 0), (1, 0.35, 1, 0), (1, -0.35, 1, 0), (1, 0, 1.2, 1.0), (-1, 0, 1, 0), (1, 0, 0.8, 2.5),
             (1, 0.8, 0.9, 1.5), (1, -0.8, 0.9, 1.5)],
}


def _rot(x, z, a):
    return x * math.cos(a) - z * math.sin(a), x * math.sin(a) + z * math.cos(a)


class Director:
    def __init__(self, tl, world):
        self.tl = tl
        self.world = world
        self.cur = None
        self.eye = self.tgt = None
        self.side = (0.0, 1.0)  # default camera side (+z)
        self.var = (1, 0, 1, 0)
        self.last_score = None

    def shot_at(self, t):
        for s in self.tl.shots:
            if s["t0"] <= t < s["t1"]:
                return s
        return self.tl.shots[-1]

    # ------------------------------------------------------------ framing
    def _group(self, sh, P, a):
        group = [P[n] for n in sh["participants"] if n in P and not P[n].get("attach")] or \
            [p for p in P.values() if not p.get("attach")] or list(P.values())
        if len(group) > 2:
            def cluster(c):
                return [p for p in group if math.hypot(p["x"] - c["x"], p["z"] - c["z"]) < 16]
            if a in P:
                group = cluster(P[a]) or group
            else:
                best = max(group, key=lambda c: (len(cluster(c)), -len([1 for p in cluster(c) if p.get("mob")])))
                group = cluster(best)
        return group

    def desired(self, kind, a, b, P, group, t, sh, var, u, extra=None):
        side_s, dang, dmul, dh = var
        head = lambda p: (p["x"], p["y"] + eye_h(p), p["z"])
        if not group:   # nobody visible in this shot (e.g. everyone just vanished): look at whoever exists
            group = list(P.values())[:1] or [dict(name="", x=0.0, y=0.0, z=0.0, yaw=0.0)]
        cx = sum(p["x"] for p in group) / len(group)
        cz = sum(p["z"] for p in group) / len(group)
        cy = sum(p["y"] for p in group) / len(group)
        spread = max([math.dist((p["x"], p["z"]), (q["x"], q["z"])) for p in group for q in group] + [0])
        if len(group) >= 2:
            g2 = sorted(group, key=lambda p: p["name"])
            ax, az = g2[-1]["x"] - g2[0]["x"], g2[-1]["z"] - g2[0]["z"]
            L = math.hypot(ax, az) or 1
            nx, nz = -az / L, ax / L
            if nx * self.side[0] + nz * self.side[1] < 0:
                nx, nz = -nx, -nz
        else:
            nx, nz = self.side
        nx, nz = nx * side_s, nz * side_s
        fov = 45
        if kind == "fixed" or kind == "path":
            p1, p2, look, f = extra
            if kind == "path":
                s = u * u * (3 - 2 * u)
                eye = tuple(p1[i] + (p2[i] - p1[i]) * s for i in range(3))
            else:
                eye = p1
            if isinstance(look, str):
                tgt = head(P[look]) if look in P else (cx, cy + 1, cz)
            else:
                tgt = look or (cx, cy + 1, cz)
            return eye, tgt, f or 45
        if kind in ("wide", "two"):
            dist = max(5.0, spread * 0.95 + 4.0) if kind == "wide" else max(3.2, spread * 0.75 + 2.4)
            extra_fov = 0.0
            if dist > 10.0:
                extra_fov = min(20.0, (dist - 10.0) * 2.2)
                dist = 10.0
            ang = (0.22 if kind == "wide" else 0.0) - 0.06 + 0.12 * u + dang
            rx, rz = _rot(nx, nz, ang)
            dist *= (1 - 0.06 * u) * dmul
            hgt = (1.9 if kind == "wide" else 1.55) + dh
            eye = (cx + rx * dist, cy + hgt + 0.06 * dist, cz + rz * dist)
            tgt = (cx, cy + (0.95 if kind == "wide" else 1.15), cz)
            return eye, tgt, (45 if kind == "wide" else 40) + extra_fov
        if kind in ("close", "ots", "medium") and a in P:
            A = P[a]
            Bp = P.get(b) if b else None
            if Bp is not None:
                dx, dz = Bp["x"] - A["x"], Bp["z"] - A["z"]
                L = math.hypot(dx, dz) or 1
                dx, dz = dx / L, dz / L
            else:
                dx, dz = math.sin(A["yaw"]), math.cos(A["yaw"])
            # direction is frozen at the cut so the camera never orbits through the character
            if getattr(self, "frozen_dir", None) is not None and self.frozen_for == (kind, a, b, id(sh)):
                dx, dz = self.frozen_dir
            else:
                self.frozen_dir, self.frozen_for = (dx, dz), (kind, a, b, id(sh))
            spread_ang = (1.05 if kind == "medium" else 0.6)
            r1 = _rot(dx, dz, spread_ang + dang); r2 = _rot(dx, dz, -spread_ang + dang)
            side_dir = r1 if (r1[0] * nx + r1[1] * nz) > (r2[0] * nx + r2[1] * nz) else r2
            hx, hy, hz = head(A)
            sc = A.get("scale", 1.0) if not A.get("mob") else max(0.5, eye_h(A) / 1.5)
            big = eye_h(A) * 1.05 if A.get("mob") else 0.0
            if kind == "close":
                d = max(2.05 * sc, big * 2.4 + 0.8) * (1 - 0.05 * u) * dmul
                return (hx + side_dir[0] * d, hy + 0.08 + dh, hz + side_dir[1] * d), (hx, hy - 0.12 * sc, hz), 36
            if kind == "medium":
                d = max(3.9 * sc, big * 3.2 + 1.5) * (1 - 0.05 * u) * dmul
                low = abs(A.get("r_p", 0)) > 0.7 or (A.get("mob") and eye_h(A) < 1.0)
                cy_ = A["y"] + ((0.35 if low else 1.05) * sc if not A.get("mob") else eye_h(A) * 0.8)
                return (A["x"] + side_dir[0] * d, cy_ + 0.4 * sc + dh + (0.6 if low else 0), A["z"] + side_dir[1] * d), \
                    (A["x"], cy_, A["z"]), 42
            if Bp is None:
                return (hx + side_dir[0] * 2.4, hy + 0.1 + dh, hz + side_dir[1] * 2.4), (hx, hy - 0.1, hz), 38
            bx, by, bz = head(Bp)
            px, pz = -dz, dx
            if px * nx + pz * nz < 0:
                px, pz = -px, -pz
            back = 1.7 * (1 - 0.05 * u) * dmul
            return (bx + dx * back + px * 1.35, by + 0.3 + dh, bz + dz * back + pz * 1.35), \
                (hx - px * 0.1, hy - 0.3, hz - pz * 0.1), 40
        if kind == "orbit":
            ang = math.atan2(nx, nz) + (t - sh["t0"]) * 0.12 - 0.35
            dist = min(9.0, max(6.0, spread * 0.4 + 4.5))
            return (cx + math.sin(ang) * dist, cy + 3.0, cz + math.cos(ang) * dist), (cx, cy + 1.2, cz - 1.5), 50
        if kind == "top":
            return (cx + nx * 4, cy + 9, cz + nz * 4), (cx, cy, cz), 50
        if kind == "follow" and a in P:
            A = P[a]
            fx, fz = math.sin(A["yaw"]), math.cos(A["yaw"])
            return (A["x"] - fx * 4.2 + nx * 1.6, A["y"] + 2.3, A["z"] - fz * 4.2 + nz * 1.6), \
                (A["x"] + fx * 2, A["y"] + 1.0, A["z"] + fz * 2), 48
        return (cx + nx * 6, cy + 2, cz + nz * 6), (cx, cy + 1, cz), 45

    # ------------------------------------------------------------ scoring
    def solid(self, p):
        from .world import TRANSPARENT
        b = self.world.blocks.get((math.floor(p[0]), math.floor(p[1]), math.floor(p[2])))
        return b is not None and b not in TRANSP and not (b in TRANSPARENT and b not in ("leaves", "cactus"))

    def thin(self, p):
        from .world import TRANSPARENT
        b = self.world.blocks.get((math.floor(p[0]), math.floor(p[1]), math.floor(p[2])))
        return b is not None and b in TRANSPARENT and b not in ("leaves", "cactus", "water")

    def visibility(self, eye, tgt, fov, who, P):
        """problems seeing `who` from eye: returns (in_frame, blocked_by_block, blocked_by_char_name)."""
        if who not in P:
            return True, False, None
        A = P[who]
        h = (A["x"], A["y"] + eye_h(A), A["z"])
        f = [tgt[i] - eye[i] for i in range(3)]
        fl = math.sqrt(sum(c * c for c in f)) or 1
        f = [c / fl for c in f]
        v = [h[i] - eye[i] for i in range(3)]
        vl = math.sqrt(sum(c * c for c in v)) or 1
        r = [f[2], 0, -f[0]]
        rl = math.sqrt(r[0] ** 2 + r[2] ** 2) or 1
        r = [c / rl for c in r]
        upv = [r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0]]
        z = sum(v[i] * f[i] for i in range(3))
        in_frame = False
        if z > 0.2:
            th = math.tan(math.radians(fov) / 2)
            x_ = sum(v[i] * r[i] for i in range(3)) / (z * th * 16 / 9)
            y_ = sum(v[i] * upv[i] for i in range(3)) / (z * th)
            in_frame = abs(x_) < 0.85 and abs(y_) < 0.9
        n = max(2, int(vl * 5))
        blocked = False
        by_char = None
        self.thin_count = 0
        last_thin = None
        for i in range(1, n):
            k = i / n
            p = (eye[0] + v[0] * k, eye[1] + v[1] * k, eye[2] + v[2] * k)
            if vl * (1 - k) < 0.45:
                break
            if self.solid(p):
                blocked = True
            elif self.thin(p):
                cell = (math.floor(p[0]), math.floor(p[1]), math.floor(p[2]))
                if cell != last_thin:
                    self.thin_count += 1; last_thin = cell
            for c in P.values():
                if c["name"] == who or c.get("attach") or c.get("prop"):
                    continue
                hgt = (0.45 if abs(c.get("r_p", 0)) > 0.7 else (eye_h(c) + 0.3 if c.get("mob") else 1.9 * c.get("scale", 1.0)))
                if math.hypot(p[0] - c["x"], p[2] - c["z"]) < 0.3 * c.get("scale", 1.0) and \
                        c["y"] - 0.1 < p[1] < c["y"] + hgt:
                    by_char = c["name"]
        return in_frame, blocked, by_char

    def score(self, eye, tgt, fov, focus, P):
        s = 0.0
        if self.solid(eye):
            s -= 6
        for c in P.values():
            rad = max(0.45, eye_h(c) * 1.05) if c.get("mob") else 0.45 * c.get("scale", 1.0)
            top = (eye_h(c) * 2.1 if c.get("mob") else 2.0 * c.get("scale", 1.0))
            if math.hypot(eye[0] - c["x"], eye[2] - c["z"]) < rad + 0.3 and c["y"] - 0.2 < eye[1] < c["y"] + top:
                s -= 4
        names = {w_[0] for w_ in focus}
        for c in P.values():  # someone else's back filling the frame
            if c["name"] in names or c.get("prop") or c.get("attach"):
                continue
            dd = math.dist(eye, (c["x"], c["y"] + 1.1, c["z"]))
            if dd < 1.9:
                fr_, _, _ = self.visibility(eye, tgt, fov, c["name"], P)
                if fr_:
                    s -= (1.9 - dd) * 1.5
        for who, w in focus:
            fr, bl, ch = self.visibility(eye, tgt, fov, who, P)
            s += w * ((0 if fr else -1.2) + (-0.9 if bl else 0) + (-0.8 if ch else 0) - 0.12 * min(6, self.thin_count))
        return s

    # ------------------------------------------------------------ per frame
    def frame(self, t, dt, poses, snap=False):
        sh = self.shot_at(t)
        cut = sh is not self.cur or snap
        self.cur = sh
        spec = sh["spec"]
        kind, a, b = spec[0], spec[1], spec[2]
        extra = spec[3] if len(spec) > 3 else None
        u = (t - sh["t0"]) / max(0.1, sh["t1"] - sh["t0"])
        P = {p["name"]: p for p in poses if not p.get("hidden")}
        group = self._group(sh, P, a)
        if kind in ("close", "ots", "medium", "follow") and a not in P:
            kind, a, b = "two", None, None
        if a in P and group and all(math.hypot(P[a]["x"] - p["x"], P[a]["z"] - p["z"]) > 16 for p in group if p is not P[a]):
            kind, a, b = "two", None, None  # speaker is off-screen: show the listeners
        if cut and kind in VARIANTS:
            focus = [(a, 3.0)] if a in P else []
            if kind == "ots" and b in P:
                focus.append((b, 0.3))
            if kind in ("two", "wide"):
                focus += [(p["name"], 1.0) for p in group if p["name"] != a and not p.get("prop")][:5]
            best, bs = None, -1e9
            self.frozen_dir = None
            for i, var in enumerate(VARIANTS[kind]):
                self.frozen_dir = None
                e, tg, f = self.desired(kind, a, b, P, group, t, sh, var, 0.15, extra)
                e = self.finalize(e, tg, self.subjects(kind, a, b, P, group))
                sc_ = self.score(e, tg, f, focus, P) + (0.35 if i == 0 else 0) - (0.25 if var[0] < 0 else 0)
                if sc_ > bs:
                    best, bs = var, sc_
            self.var = best
            self.last_score = bs
            self.frozen_dir = None
        var = self.var if kind in VARIANTS else (1, 0, 1, 0)
        eye, tgt, fov = self.desired(kind, a, b, P, group, t, sh, var, u, extra)
        if kind not in ("fixed", "path"):
            eye = self.finalize(eye, tgt, self.subjects(kind, a, b, P, group))
        if cut or self.eye is None:
            self.eye, self.tgt, self.fov = list(eye), list(tgt), fov
            self.ve, self.vt = [0.0] * 3, [0.0] * 3
        else:
            self.eye, self.ve = self.damp(self.eye, eye, self.ve, 0.45, 7.0, dt)
            self.tgt, self.vt = self.damp(self.tgt, tgt, self.vt, 0.35, 8.0, dt)
            self.fov += (fov - self.fov) * (1 - math.exp(-dt * 3))
        if kind not in ("fixed", "path"):
            g = self.world.ground(self.eye[0], self.eye[2])
            self.eye[1] = max(self.eye[1], g + 0.5)
            k = 0
            while self.solid(self.eye) and k < 30:  # never end up inside a block after smoothing
                self.eye = [e + (tg - e) * 0.08 for e, tg in zip(self.eye, self.tgt)]
                k += 1
        self.kind_now, self.a_now = kind, a
        return tuple(self.eye), tuple(self.tgt), self.fov

    @staticmethod
    def subjects(kind, a, b, P, group):
        if kind in ("close", "medium") and a in P:
            return [P[a]]
        if kind == "ots" and a in P:
            return [P[a]]
        return group

    def finalize(self, eye, tgt, group):
        g0 = self.world.ground(eye[0], eye[2])
        eye = (eye[0], max(eye[1], g0 + 0.5), eye[2])
        for p in group:
            eye = self.unblock((p["x"], p["y"] + 1.4, p["z"]), eye)
        return self.unblock(tgt, eye)

    @staticmethod
    def damp(cur, target, vel, smooth_time, max_speed, dt):
        """critically damped spring with a speed limit (no camera pops)"""
        omega = 2.0 / smooth_time
        x = omega * dt
        e = 1.0 / (1 + x + 0.48 * x * x + 0.235 * x * x * x)
        ch = [c - t for c, t in zip(cur, target)]
        mag = math.sqrt(sum(c * c for c in ch))
        lim = max_speed * smooth_time
        if mag > lim:
            ch = [c * lim / mag for c in ch]
        tgt2 = [c - d for c, d in zip(cur, ch)]
        temp = [(v + omega * d) * dt for v, d in zip(vel, ch)]
        vel = [(v - omega * tp) * e for v, tp in zip(vel, temp)]
        out = [t2 + (d + tp) * e for t2, d, tp in zip(tgt2, ch, temp)]
        return out, vel

    def unblock(self, tgt, eye):
        tx, ty, tz = tgt
        ex, ey, ez = eye
        L = math.dist(tgt, eye)
        n = max(2, int(L * 6))
        last_free = 0.0
        for i in range(2, n + 1):
            k = i / n
            p = (tx + (ex - tx) * k, ty + (ey - ty) * k, tz + (ez - tz) * k)
            hit = False
            for ox in (-0.25, 0.25):
                for oz in (-0.25, 0.25):
                    if self.solid((p[0] + ox, p[1], p[2] + oz)):
                        hit = True
            if hit:
                k2 = max(0.15, last_free - 0.04)
                return (tx + (ex - tx) * k2, ty + (ey - ty) * k2, tz + (ez - tz) * k2)
            last_free = k
        return eye
