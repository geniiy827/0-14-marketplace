"""Synthesised sounds for the NOVA intro -> OUT/*.ogg (44.1 kHz mono).
rc_in: warm low swell for the studio logo; rc_shine: glassy shimmer for its light sweep;
whoosh: a letter flying past; hit: metallic landing clank; shine: bell chord on the title sweep;
rise: soft upward whoosh when the title glides into the menu."""
import sys, os, subprocess
import numpy as np
from scipy import signal

SR = 44100
OUT = sys.argv[1]
rng = np.random.default_rng(3)

def t(d): return np.arange(int(SR * d)) / SR
def env(n, a, r, shape=2.0):
    e = np.ones(n); A = int(SR * a); R = int(SR * r)
    if A: e[:A] = np.linspace(0, 1, A) ** shape
    if R: e[-R:] *= np.linspace(1, 0, R) ** shape
    return e
def bandsweep(x, f0, f1, q=2.0, blocks=64):
    """band-pass with a centre moving f0 -> f1 (log), processed in blocks"""
    y = np.zeros_like(x); n = len(x); step = n // blocks; zi = None
    for k in range(blocks):
        a, b = k * step, n if k == blocks - 1 else (k + 1) * step
        fc = f0 * (f1 / f0) ** (k / (blocks - 1))
        bw = fc / q
        bb, aa = signal.butter(2, [max(20, fc - bw / 2), min(SR / 2 - 100, fc + bw / 2)], btype='band', fs=SR)
        if zi is None: zi = signal.lfilter_zi(bb, aa) * 0
        y[a:b], zi = signal.lfilter(bb, aa, x[a:b], zi=zi)
    return y
def lp(x, f): b, a = signal.butter(2, f, fs=SR); return signal.lfilter(b, a, x)
def hp(x, f): b, a = signal.butter(2, f, btype='high', fs=SR); return signal.lfilter(b, a, x)
def reverb(x, d=1.2, mix=0.25):
    n = int(SR * d); ir = rng.standard_normal(n) * np.exp(-np.linspace(0, 7, n)); ir = lp(ir, 5000)
    w = signal.fftconvolve(x, ir)[:len(x) + n]; w = np.pad(w, (0, len(x) + n - len(w))); w /= np.max(np.abs(w)) + 1e-9
    y = np.concatenate([x, np.zeros(n)])
    return y * (1 - mix) + w * mix * np.max(np.abs(x))
def save(name, x, gain=0.85):
    x = x / (np.max(np.abs(x)) + 1e-9) * gain
    fade = int(SR * 0.01); x[-fade:] *= np.linspace(1, 0, fade)
    wav = os.path.join(OUT, name + '.wav')
    from scipy.io import wavfile
    wavfile.write(wav, SR, (x * 32767).astype(np.int16))
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', wav, '-c:a', 'libvorbis', '-q:a', '4', os.path.join(OUT, name + '.ogg')], check=True)
    os.remove(wav)

os.makedirs(OUT, exist_ok=True)

# rc_in: low swell (fifths, slow beating) + airy noise breath
T = t(2.2)
tone = sum(a * np.sin(2 * np.pi * f * T + p) for f, a, p in [(55, 1, 0), (82.5, .5, 1), (110, .45, 2), (110.6, .3, 0), (165, .18, 1)])
air = bandsweep(rng.standard_normal(len(T)), 300, 1400, q=1.2) * 0.35
x = (tone * 0.8 + air) * env(len(T), 0.7, 1.3, 1.6)
save('rc_in', reverb(x, 1.0, 0.2), 0.7)

# rc_shine: glassy upward shimmer
T = t(1.0)
f = 1800 * (2.2 ** (T / 1.0))
x = sum(np.sin(2 * np.pi * np.cumsum(f * m) / SR) * a for m, a in [(1, 1), (2.01, .35), (3.03, .15)])
x *= (0.6 + 0.4 * np.sin(2 * np.pi * 14 * T)) * env(len(T), 0.08, 0.8, 1.5)
save('rc_shine', reverb(x, 1.4, 0.4), 0.45)

# whoosh: noise band rising then falling, swell towards the landing (~0.45 s)
T = t(0.7)
n = rng.standard_normal(len(T))
x = bandsweep(n, 350, 2600, q=1.6)
e = np.minimum(1, (T / 0.42) ** 2.2) * np.exp(-np.maximum(0, T - 0.42) * 14)
save('whoosh', x * e, 0.8)

# hit: metallic clank (inharmonic partials) + thump + click
T = t(1.3)
f0 = 196
x = sum(a * np.sin(2 * np.pi * f0 * m * T) * np.exp(-T / d) for m, a, d in
        [(1, 1, .5), (2.76, .7, .3), (5.40, .45, .17), (8.93, .3, .09), (13.3, .18, .05), (1.5, .35, .25)])
x += 1.6 * np.sin(2 * np.pi * 62 * T * (1 - .25 * T)) * np.exp(-T / .09)
click = hp(rng.standard_normal(len(T)), 2500) * np.exp(-T / .006) * 1.2
x = (x + click) * env(len(T), 0.0015, 0.2)
save('hit', reverb(x, 0.9, 0.18), 0.9)

# shine: bell chord on the finished title
T = t(2.4)
x = np.zeros(len(T))
for fr, a in [(523.25, 1), (659.25, .8), (783.99, .7), (1046.5, .55), (1567.98, .3)]:
    for m, pa, d in [(1, 1, 1.2), (2.0, .4, .6), (3.0, .2, .35), (4.2, .12, .2)]:
        x += a * pa * np.sin(2 * np.pi * fr * m * T) * np.exp(-T / d)
x += bandsweep(rng.standard_normal(len(T)), 3000, 9000, q=2) * np.exp(-T / .4) * .25
x *= env(len(T), 0.006, 0.4)
save('shine', reverb(x, 1.6, 0.35), 0.55)

# rise: soft upward whoosh for the hand-over
T = t(0.9)
x = bandsweep(rng.standard_normal(len(T)), 300, 3500, q=1.4) * np.sin(np.pi * np.minimum(1, T / 0.9)) ** 1.5
save('rise', x, 0.55)
print('intro sfx:', sorted(os.listdir(OUT)))
