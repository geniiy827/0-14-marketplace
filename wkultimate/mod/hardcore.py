"""HARDCORE difficulty mod for Wall Kickers (data only, generator untouched).

* every level recipe is rebuilt so hazard chunks dominate from level 1
* 'X' variants: same chunk, faster timing (spike balls, moving walls, electric, flipping, conveyors)
* 'D' variants: spike-ball chunks with an extra ball in anti-phase (more saws)
* the late-game ramp is kept, but late recipes lean on X/D variants
Tune the numbers in CONFIG below.
Z variants: saw/spike "grinder" on the outer face of the target wall (off the player path)
T variants: same chunk, hazards phase-shifted so the natural arrival time is NOT safe
"""
import copy, struct, random

CONFIG = dict(
    fast_time=0.8,        # X variants: timings x0.8  (=25% faster)
    fast_conveyor=1.35,   # X variants: conveyor speed multiplier
    # T variants: the game normally phases every timed hazard so that a player who just keeps
    # jumping arrives exactly in the safe window ("autopilot").  T variants shift the hazard
    # phase by a fraction of its period -> you have to wait / rush / count the timing.
    timing_shifts=(0.25, 0.5),
    timing_share={'intro': 0.0, 'easy': 0.5, 'med': 0.65, 'hard': 0.8},
    # Z variants ("grinder walls"): the target wall gets a spike strip and saws grinding up and
    # down on its OUTER side.  The player only ever touches the inner face, so it is survivable,
    # it just looks (and feels) awful.  Registered as clear-area blocks so other chunks avoid it.
    zone_share={'intro': 0.0, 'easy': 0.5, 'med': 0.75, 'hard': 0.9},
    zone_saw_period=1.6,
    floor_boost=4,        # floor recipes: floor chunk weights x4 so floor runs still happen
    hard_min_level=8,     # hard recipes start here (orig 10)
    med_min_level=4,      # medium recipes start here (orig 5)
    hard_ramp=((8, 0.5), (14, 1.0)),   # hard recipe weight curve (orig (10,0)->(20,1))
    tiers={
        # weight for: own mechanic, own X, spike balls, spike X, spike D, electric, electric X, other hazards, other X, plain
        'intro': dict(own=10, ownX=0,  sb=3, sbX=0, sbD=0, el=1, elX=0, oth=0, othX=0, plain=1.0),
        'easy':  dict(own=10, ownX=0,  sb=6, sbX=0, sbD=1, el=4, elX=0, oth=2, othX=0, plain=0.4),
        'med':   dict(own=10, ownX=5,  sb=8, sbX=3, sbD=3, el=6, elX=3, oth=3, othX=1, plain=0.25),
        'hard':  dict(own=6,  ownX=12, sb=6, sbX=8, sbD=6, el=3, elX=8, oth=2, othX=4, plain=0.1),
    },
)

GROUPS = [('floor', 'floor'), ('spikeball', 'spikeBall'), ('spikeball', 'bouncySpikeBall'),
          ('electric', 'electric'), ('electric', 'bouncyElectric'), ('path', 'path'), ('path', 'bouncyPath'),
          ('flipping', 'flip'), ('conveyor', 'conveyor'), ('crumbling', 'crumbling'), ('bouncy', 'bouncy')]

def group(name):
    for g, p in GROUPS:
        if name.startswith(p): return g
    return 'plain'

def timed(s):
    return bool(s['spikeBalls'] or s['pathWalls'] or s['electric'] or s['flipSync'] or s['conveyors'])

def _scale_head(head, idx_period, f):
    h = bytearray(head)
    per, ph = struct.unpack_from('<ff', h, 4 * idx_period)
    struct.pack_into('<ff', h, 4 * idx_period, per * f, ph * f)
    return bytes(h)

def make_fast(s, newid, f, fc):
    t = copy.deepcopy(s); t['id'] = newid(); t['name'] = s['name'] + 'X'
    t['dmin'] *= f; t['dmax'] *= f
    t['electric'] = [(x, a, b, p * f, ph * f) for x, a, b, p, ph in t['electric']]
    t['flipSync'] = [(x, a, b, p * f, ph * f) for x, a, b, p, ph in t['flipSync']]
    t['conveyors'] = [(x, a, b, v * fc) for x, a, b, v in t['conveyors']]
    for p in t['pathWalls']: p['head'] = _scale_head(p['head'], 4, f)
    for p in t['spikeBalls']: p['head'] = _scale_head(p['head'], 3, f)
    return t

def _shift_head(head, idx_period, frac):
    h = bytearray(head)
    per, ph = struct.unpack_from('<ff', h, 4 * idx_period)
    struct.pack_into('<f', h, 4 * idx_period + 4, (ph + frac * per) % per if per else ph)
    return bytes(h)

def make_timing(s, newid, frac):
    t = copy.deepcopy(s); t['id'] = newid(); t['name'] = s['name'] + 'T%d' % round(frac * 100)
    t['electric'] = [(x, a, b, p, (ph + frac * p) % p) for x, a, b, p, ph in t['electric']]
    t['flipSync'] = [(x, a, b, p, (ph + frac * p) % p) for x, a, b, p, ph in t['flipSync']]
    for p in t['pathWalls']: p['head'] = _shift_head(p['head'], 4, frac)
    for p in t['spikeBalls']: p['head'] = _shift_head(p['head'], 3, frac)
    return t

def phased(s):
    return bool(s['spikeBalls'] or s['pathWalls'] or s['electric'] or s['flipSync'])

ZONE_WALLS = ('walls', 'electric', 'crumbling', 'conveyors')
def zone_ok(s):
    if not s['tags'] or 'floor' in s['tags'] or 'endoflevel' in s['tags'] or 'startoflevel' in s['tags']:
        return False
    if s['name'].startswith(('bouncy', 'flip')) or s['bouncy'] or s['flipping'] or s['floors']:
        return False
    ex, ey, d = s['exit']
    xs = {w[0] for k in ZONE_WALLS for w in s[k]}
    return d == -1 and ex > 40 and xs == {ex}

def _saw(x, y, length, period, phase):
    head = struct.pack('<ffIffff', x, y, 0, period, phase, 0.5, 0.0)
    return dict(head=head, flag=(1, 1), len=float(length), pts=[(0.0, 0.0, 0.0), (float(length), 0.0, float(length))])

def make_zone(s, newid, period):
    t = copy.deepcopy(s); t['id'] = newid(); t['name'] = s['name'] + 'Z'
    W = s['exit'][0]
    spans = [(w[1], w[2]) for k in ZONE_WALLS for w in s[k] if w[0] == W]
    y1, y2 = min(a for a, b in spans), max(b for a, b in spans)
    t['spikes'] = t['spikes'] + [(W + 7, W + 7, y1, y2)]
    L = max(4.0, (y2 - y1) - 16)
    sx = W + 28
    t['spikeBalls'] = t['spikeBalls'] + [_saw(sx, y1 + 8, L, period, 0.0)]
    if L >= 60:
        t['spikeBalls'].append(_saw(sx, y1 + 8, L, period, period / 2))
    t['blocks'] = t['blocks'] + [(W + 3, y1 - 22, sx + 22, y2 + 22)]
    return t

def make_double(s, newid):
    t = copy.deepcopy(s); t['id'] = newid(); t['name'] = s['name'] + 'D'
    extra = []
    for p in t['spikeBalls']:
        q = copy.deepcopy(p)
        per, ph = struct.unpack_from('<ff', q['head'], 12)
        q['head'] = _scale_head(q['head'], 3, 1.0)
        h = bytearray(q['head']); struct.pack_into('<f', h, 16, (ph + per / 2) % per); q['head'] = bytes(h)
        extra.append(q)
    t['spikeBalls'] += extra
    return t

def tier_of(r):
    f = r.fields
    if f['intro']: return 'intro'
    if f['min'] >= 10: return 'hard'
    if f['min'] >= 5: return 'med'
    return 'easy'

def apply(data, cfg=CONFIG, log=print):
    rnd = random.Random(1337)
    used = {x['id'] for x in data.scn}
    def newid():
        while True:
            b = rnd.getrandbits(48).to_bytes(6, 'little') + b'\0\0'
            if b not in used: used.add(b); return b
    # ---- variants
    base = [s for s in data.scn if s['tags'] and 'startoflevel' not in s['tags'] and 'endoflevel' not in s['tags']]
    fast, dbl = {}, {}
    for s in base:
        if timed(s) and group(s['name']) != 'floor':
            fast[s['name']] = make_fast(s, newid, cfg['fast_time'], cfg['fast_conveyor'])
        if s['spikeBalls']:
            dbl[s['name']] = make_double(s, newid)
    timing = {}
    for src in list(base) + list(fast.values()):
        if phased(src):
            timing[src['name']] = [make_timing(src, newid, f) for f in cfg['timing_shifts']]
    pool = list(base) + list(fast.values()) + [t for v in timing.values() for t in v]
    zone = {x['name']: make_zone(x, newid, cfg['zone_saw_period']) for x in pool if zone_ok(x)}
    data.scn += list(fast.values()) + list(dbl.values()) + [t for v in timing.values() for t in v] + list(zone.values())
    data.byname = {x['name']: x for x in data.scn}; data.byid = {x['id']: x for x in data.scn}
    log(f'variants: {len(fast)} fast (X), {len(dbl)} double-ball (D), '
        f'{sum(map(len, timing.values()))} phase-shifted (T), {len(zone)} grinder-wall (Z)')
    hz = {g: [s['name'] for s in base if group(s['name']) == g] for g in
          ('spikeball', 'electric', 'path', 'flipping', 'conveyor', 'crumbling', 'bouncy')}
    # ---- recipes
    for r in data.recipes:
        tier = tier_of(r); W = cfg['tiers'][tier]
        orig = {n: w for n, w in r.entries}
        own = [n for n in orig if group(n) != 'plain']
        owng = {group(n) for n in own}
        acc = {}
        ts = cfg['timing_share'][tier]; zs = cfg['zone_share'][tier]
        def put(n, w):
            if n in zone and zs > 0:
                acc[n] = acc.get(n, 0) + w * (1 - zs)
                acc[n + 'Z'] = acc.get(n + 'Z', 0) + w * zs
            else:
                acc[n] = acc.get(n, 0) + w
        def add(n, w):
            if w <= 0 or n not in data.byname: return
            vs = timing.get(n)
            if vs and ts > 0:
                put(n, w * (1 - ts))
                for v in vs: put(v['name'], w * ts / len(vs))
            else:
                put(n, w)
        for n, w in orig.items():
            g = group(n)
            if g == 'plain': add(n, W['plain'])
            elif g == 'floor': add(n, w * (1 if tier == 'intro' else cfg['floor_boost']))  # keep floor runs common
            else:
                add(n, W['own'])
                if n in fast: add(n + 'X', W['ownX'])
        for n in hz['spikeball']:
            add(n, W['sb']); add(n + 'X', W['sbX']); add(n + 'D', W['sbD'])
        for n in hz['electric']:
            add(n, W['el']); add(n + 'X', W['elX'])
        for g in ('path', 'flipping', 'conveyor', 'crumbling', 'bouncy'):
            if g in owng: continue
            for n in hz[g]:
                add(n, W['oth']); add(n + 'X', W['othX'])
        r.entries = [[n, round(w, 3)] for n, w in acc.items()]
        f = r.fields
        if tier == 'hard':
            f['min'] = cfg['hard_min_level']; f['curve'] = [tuple(x) for x in cfg['hard_ramp']]
        elif tier == 'med':
            f['min'] = cfg['med_min_level']
        haz = sum(w for n, w in r.entries if group(n) not in ('plain',))
        tot = sum(w for n, w in r.entries)
        log(f'  {r.name:22s} [{tier:5s}] lvl {f["min"]}..{f["max"] if f["max"]<10**9 else "inf"}  '
            f'chunks={len(r.entries):3d}  hazard share={100*haz/tot:4.1f}%')
