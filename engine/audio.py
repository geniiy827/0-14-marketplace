"""TTS (RHVoice), lip-sync envelopes, procedural SFX and music, final mix."""
import hashlib
import os
import subprocess
import wave
import numpy as np

SR = 44100


def read_wav(path):
    with wave.open(path, "rb") as w:
        sr, n, ch, sw = w.getframerate(), w.getnframes(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(n)
    a = np.frombuffer(raw, np.int16).astype(np.float32) / 32768.0
    if ch > 1:
        a = a.reshape(-1, ch).mean(1)
    if sr != SR:
        x = np.arange(int(len(a) * SR / sr)) * (sr / SR)
        a = np.interp(x, np.arange(len(a)), a).astype(np.float32)
    return a


def write_wav(path, stereo):
    s = np.clip(stereo, -1, 1)
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((s * 32767).astype(np.int16).tobytes())


def clean_for_tts(text):
    import re
    t = re.sub(r"\[[^\]]*\]", " ", text)
    t = t.replace("…", "...").replace("—", ",")
    return re.sub(r"\s+", " ", t).strip()


def tts(text, voice="aleksandr-hq", pitch=None, rate=None, cache="cache"):
    os.makedirs(cache, exist_ok=True)
    t = clean_for_tts(text)
    key = hashlib.md5(f"{t}|{voice}|{pitch}|{rate}".encode()).hexdigest()[:16]
    path = os.path.join(cache, f"tts_{key}.wav")
    if not os.path.exists(path):
        cmd = ["RHVoice-test", "-p", voice, "-o", path + ".tmp.wav"]
        if pitch is not None:
            cmd += ["-t", str(pitch)]
        if rate is not None:
            cmd += ["-r", str(rate)]
        subprocess.run(cmd, input=t.encode(), check=True, capture_output=True)
        os.replace(path + ".tmp.wav", path)
    a = read_wav(path)
    # trim silence
    nz = np.where(np.abs(a) > 0.01)[0]
    if len(nz):
        a = a[max(0, nz[0] - 800): nz[-1] + 1500]
    peak = np.abs(a).max() + 1e-6
    a = a / peak * 0.85
    return a


def envelope(a, fps=30):
    hop = SR // fps
    n = len(a) // hop + 1
    e = np.array([np.sqrt(np.mean(a[i * hop:(i + 1) * hop] ** 2) + 1e-9) for i in range(n)])
    ref = np.percentile(e, 92) + 1e-6
    e = np.clip(e / ref, 0, 1)
    # light smoothing, keep syllable flaps
    return np.convolve(e, [0.25, 0.5, 0.25], mode="same").tolist()


# ------------------------------------------------------------------- sfx
_rng = np.random.default_rng(5)


def _lp(x, a):
    y = np.zeros_like(x); acc = 0.0
    for i, v in enumerate(x):
        acc += a * (v - acc); y[i] = acc
    return y


SND_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets", "sounds")
_snd_cache = {}
_vrng = np.random.default_rng(11)


def game_sound(name, variant=None):
    """name like 'step_grass' or 'random_burp'; picks a random numbered variant."""
    if not os.path.isdir(SND_DIR):
        return None
    if name not in _snd_cache:
        fs = sorted(f for f in os.listdir(SND_DIR) if f[:-4].rstrip("0123456789") == name)
        lst = []
        for f in fs:
            a = read_wav(os.path.join(SND_DIR, f))
            if not name.startswith("note_"):
                a = a / (np.abs(a).max() + 1e-6)
            lst.append(a)
        _snd_cache[name] = lst
    vs = _snd_cache[name]
    if not vs:
        return None
    a = vs[variant % len(vs)] if variant is not None else vs[int(_vrng.integers(0, len(vs)))]
    return a


GAME_SFX = {"step": ("step_grass", 0.35), "land": ("damage_fallsmall", 0.8), "punch": ("damage_hit", 0.9),
            "pop": ("random_pop", 0.7), "poof": ("random_fizz", 0.5), "spit": ("mob_slime_small", 0.9),
            "hurt": ("random_hurt", 0.9), "eat": ("random_eat", 0.7), "levelup": ("random_levelup", 0.6),
            "orb": ("random_orb", 0.5), "clap": ("random_click", 0.5)}


def sfx(kind):
    if kind in GAME_SFX:
        nm, vol = GAME_SFX[kind]
        a = game_sound(nm)
        if a is not None:
            return a * vol
    elif "_" in kind:
        a = game_sound(kind)
        if a is not None:
            return a * 0.8
    if kind == "step":
        n = int(0.07 * SR)
        env = np.exp(-np.linspace(0, 1, n) * 9)
        x = _lp(_rng.normal(0, 1, n), 0.25 + _rng.random() * 0.1) * env
        return x / (np.abs(x).max() + 1e-6) * 0.22
    if kind == "jump":
        n = int(0.22 * SR); t = np.arange(n) / SR
        f = 180 + 500 * t / t[-1]
        x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t * 9) * 0.25
        return x
    if kind == "land":
        n = int(0.15 * SR); t = np.arange(n) / SR
        x = (_lp(_rng.normal(0, 1, n), 0.12) * 3 + np.sin(2 * np.pi * 70 * t)) * np.exp(-t * 30)
        return x / (np.abs(x).max() + 1e-6) * 0.4
    if kind == "pop":
        n = int(0.12 * SR); t = np.arange(n) / SR
        return np.sin(2 * np.pi * (600 + 900 * t / t[-1]) * t) * np.exp(-t * 35) * 0.3
    if kind == "punch":
        n = int(0.18 * SR); t = np.arange(n) / SR
        x = _lp(_rng.normal(0, 1, n), 0.3) * np.exp(-t * 25)
        return x / (np.abs(x).max() + 1e-6) * 0.45
    if kind == "clap":
        n = int(0.06 * SR)
        x = _rng.normal(0, 1, n) * np.exp(-np.linspace(0, 1, n) * 12)
        return x * 0.18
    return np.zeros(10)


# ----------------------------------------------------------------- music
def music(duration, mood="весело", seed=1):
    rng = np.random.default_rng(seed)
    bpm = {"весело": 118, "спокойно": 88, "тревожно": 100, "ночь": 76}.get(mood, 112)
    beat = 60 / bpm
    n = int((duration + 2) * SR)
    out = np.zeros(n, np.float32)
    minor = mood in ("тревожно", "ночь")
    root = 48 if not minor else 45
    prog = [[0, 4, 7], [7, 11, 14], [9, 12, 16], [5, 9, 12]] if not minor else \
           [[0, 3, 7], [8, 12, 15], [3, 7, 10], [7, 10, 14]]
    mf = lambda m: 440 * 2 ** ((m - 69) / 12)

    def note(t0, dur, freq, vol, shape="pulse"):
        i0 = int(t0 * SR); m = int(dur * SR)
        if i0 >= n:
            return
        m = min(m, n - i0)
        t = np.arange(m) / SR
        ph = (t * freq) % 1.0
        if shape == "pulse":
            w = np.where(ph < 0.25, 1.0, -1.0) * 0.6
        elif shape == "tri":
            w = 4 * np.abs(ph - 0.5) - 1
        else:
            w = np.sin(2 * np.pi * ph)
        env = np.minimum(1, t / 0.005) * np.exp(-t * (3.0 / dur))
        out[i0:i0 + m] += (w * env * vol).astype(np.float32)

    bars = int(duration / (4 * beat)) + 2
    mel_pattern = rng.integers(0, 3, 16)
    for b in range(bars):
        ch = prog[b % 4]
        t0 = b * 4 * beat
        for k in range(4):  # bass
            note(t0 + k * beat, beat * 0.9, mf(root - 12 + ch[0] + (12 if k % 2 else 0)), 0.22, "tri")
        for k in range(8):  # arpeggio
            m = root + 12 + ch[k % 3] + (12 if k >= 6 else 0)
            note(t0 + k * beat / 2, beat * 0.45, mf(m), 0.06, "pulse")
        if b % 2 == 1 and not minor:  # little melody every other bar
            for k in range(4):
                m = root + 24 + ch[mel_pattern[(b * 4 + k) % 16]]
                note(t0 + k * beat, beat * 0.8, mf(m), 0.05, "sine")
        for k in range(8):  # hats
            i0 = int((t0 + k * beat / 2) * SR); m = int(0.03 * SR)
            if i0 + m < n:
                out[i0:i0 + m] += (rng.normal(0, 1, m) * np.exp(-np.arange(m) / (0.006 * SR)) * 0.025).astype(np.float32)
    # soft low-pass for warmth
    out = np.convolve(out, np.ones(6) / 6, mode="same")
    return out[: int(duration * SR)]


NB = {"harp": ("note_harp", 66), "bass": ("note_bass", 54), "pling": ("note_pling", 66), "bd": ("note_bd", None),
      "snare": ("note_snare", None), "hat": ("note_hat", None)}


def nb_music(duration, mood="весело", seed=1):
    """Note-block style soundtrack built from the game's note samples."""
    samp = {k: game_sound(v[0], 0) for k, v in NB.items()}
    if any(v is None for v in samp.values()):
        return music(duration, mood, seed)
    rng = np.random.default_rng(seed)
    moods = {
        "весело": (124, False, [[0, 4, 7], [7, 11, 14], [9, 12, 16], [5, 9, 12]], 0),
        "спокойно": (92, False, [[0, 4, 7], [5, 9, 12], [9, 12, 16], [7, 11, 14]], 0),
        "погоня": (160, True, [[0, 3, 7], [8, 12, 15], [10, 14, 17], [7, 11, 14]], -2),
        "тревожно": (104, True, [[0, 3, 7], [1, 5, 8], [0, 3, 7], [7, 10, 14]], -3),
        "ночь": (78, True, [[0, 3, 7], [8, 12, 15], [3, 7, 10], [7, 10, 14]], -5),
        "эпично": (112, True, [[0, 3, 7], [8, 12, 15], [3, 7, 10], [10, 14, 17]], -2),
        "грустно": (70, True, [[0, 3, 7], [5, 8, 12], [8, 12, 15], [7, 10, 14]], -3),
    }
    bpm, minor, prog, tr = moods.get(mood, moods["весело"])
    beat = 60 / bpm
    n = int((duration + 2) * SR)
    out = np.zeros(n, np.float32)
    root = 60 + tr

    def put(name, t0, midi=None, vol=0.3):
        a = samp[name]
        base = NB[name][1]
        if midi is not None and base is not None:
            ratio = 2 ** ((midi - base) / 12)
            idx = np.arange(0, len(a) - 1, ratio)
            a = np.interp(idx, np.arange(len(a)), a).astype(np.float32)
        i0 = int(t0 * SR)
        if i0 >= n:
            return
        m = min(len(a), n - i0)
        out[i0:i0 + m] += a[:m] * vol

    bars = int(duration / (4 * beat)) + 2
    mel = rng.integers(0, 3, 32)
    chase = mood == "погоня"
    for b in range(bars):
        ch = prog[b % 4]
        t0 = b * 4 * beat
        for k in range(4):
            put("bass", t0 + k * beat, root - 12 + ch[0] + (7 if k % 2 else 0), 0.55)
            put("bd" if k % 2 == 0 else "snare", t0 + k * beat, None, 0.35 if k % 2 == 0 else 0.25)
        for k in range(8):
            put("hat", t0 + k * beat / 2, None, 0.12)
            put("harp", t0 + k * beat / 2, root + ch[k % 3] + (12 if k >= 4 else 0), 0.16)
        if b % 2 == 1 or chase:
            for k in range(4 if not chase else 8):
                step = beat if not chase else beat / 2
                put("pling", t0 + k * step, root + 12 + ch[mel[(b * 8 + k) % 32]], 0.13)
    return out[: int(duration * SR)]


def mix(duration, speech, sfx_events, music_on=True, mood="весело", out="mix.wav", music_plan=None):
    n = int(duration * SR) + SR
    L = np.zeros(n, np.float32); R = np.zeros(n, np.float32)
    duck = np.ones(n, np.float32)
    for s in speech:
        a = s["audio"]; i0 = int(s["t0"] * SR); i1 = min(n, i0 + len(a))
        pan = s.get("pan", 0.0)
        L[i0:i1] += a[: i1 - i0] * (1 - max(0, pan) * 0.5)
        R[i0:i1] += a[: i1 - i0] * (1 + min(0, pan) * 0.5)
        duck[max(0, i0 - 4000):min(n, i1 + 6000)] = 0.45
    for (t, kind, vol) in sfx_events:
        x = sfx(kind) * vol
        i0 = int(t * SR); i1 = min(n, i0 + len(x))
        if i0 >= n:
            continue
        L[i0:i1] += x[: i1 - i0]; R[i0:i1] += x[: i1 - i0]
    if music_on:
        duck = np.convolve(duck, np.ones(4410) / 4410, mode="same")
        m = np.zeros(n, np.float32)
        plan = music_plan or [(0.0, duration, mood)]
        for (a0, a1, md) in plan:
            if md in ("нет", "тишина"):
                continue
            seg = nb_music(a1 - a0 + 1.0, md, seed=int(a0 * 7) + 1)
            i0 = int(a0 * SR); L_ = min(len(seg), n - i0)
            seg = seg[:L_].copy()
            fi = min(int(0.6 * SR), L_); fo = min(int(1.0 * SR), L_)
            seg[:fi] *= np.linspace(0, 1, fi)
            cut = int((a1 - a0) * SR)
            if cut < L_:
                seg[max(0, cut - fo // 2):cut + fo // 2] *= np.linspace(1, 0, len(seg[max(0, cut - fo // 2):cut + fo // 2]))
                seg[cut + fo // 2:] = 0
            m[i0:i0 + L_] += seg
        m *= duck * 0.55
        # fade in/out
        fi = int(1.0 * SR); fo = int(2.0 * SR)
        m[:fi] *= np.linspace(0, 1, fi)
        end = int(duration * SR)
        m[max(0, end - fo):end] *= np.linspace(1, 0, min(fo, end))
        m[end:] = 0
        L += m; R += m
    st = np.stack([L, R], 1)[: int(duration * SR)]
    peak = np.abs(st).max() + 1e-6
    if peak > 0.98:
        st *= 0.98 / peak
    write_wav(out, st)
    return out
