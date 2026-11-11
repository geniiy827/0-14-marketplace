"""HARDCORE difficulty mod for Wall Kickers (data only, generator untouched).

* every level recipe is rebuilt so hazard chunks dominate from level 1
* 'X' variants: same chunk, faster timing (spike balls, moving walls, electric, flipping, conveyors)
* 'D' variants: spike-ball chunks with an extra ball in anti-phase (more saws)
* the late-game ramp is kept, but late recipes lean on X/D variants
Tune the numbers in CONFIG below.
"""
import copy, struct, random

CONFIG = dict(
    fast_time=0.8,        # X variants: timings x0.8  (=25% faster)
    fast_conveyor=1.35,   # X variants: conveyor speed multiplier
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
    data.scn += list(fast.values()) + list(dbl.values())
    data.byname = {x['name']: x for x in data.scn}; data.byid = {x['id']: x for x in data.scn}
    log(f'variants: {len(fast)} fast (X), {len(dbl)} double-ball (D)')
    hz = {g: [s['name'] for s in base if group(s['name']) == g] for g in
          ('spikeball', 'electric', 'path', 'flipping', 'conveyor', 'crumbling', 'bouncy')}
    # ---- recipes
    for r in data.recipes:
        tier = tier_of(r); W = cfg['tiers'][tier]
        orig = {n: w for n, w in r.entries}
        own = [n for n in orig if group(n) != 'plain']
        owng = {group(n) for n in own}
        acc = {}
        def add(n, w):
            if w > 0 and n in data.byname: acc[n] = acc.get(n, 0) + w
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
