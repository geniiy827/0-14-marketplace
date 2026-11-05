"""Emulate Layer::getDefaultLayers(seed) of libminecraftpe 0.14.3 and dump fillArea results of the whole layer chain.
usage: emu_layers.py SEED X Z W H  -> prints for each layer (top = final 1:4 RiverMix ... ) its name and area"""
import sys, struct, subprocess, re
from unicorn import *
from unicorn.arm_const import *
lib = '/home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so'
d = open(lib, 'rb').read()
mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
mu.mem_map(0, 0x900000)
mu.mem_write(0, d[:0x6e9b40])
mu.mem_write(0x6eb160, d[0x6ea160:0x6ea160 + 0x70618])
DUMMY = 0x860000
symval = {}
for line in subprocess.run(['readelf', '-rW', lib], capture_output=True, text=True).stdout.splitlines():
    f = line.split()
    if len(f) >= 4 and f[2] in ('R_ARM_ABS32', 'R_ARM_GLOB_DAT', 'R_ARM_JUMP_SLOT'):
        off = int(f[0], 16); val = int(f[3], 16) if len(f) >= 5 else 0
        if f[2] == 'R_ARM_JUMP_SLOT':
            symval[off] = f[4] if len(f) >= 5 else '?'
            continue
        if val == 0: val = DUMMY
        cur = struct.unpack('<I', bytes(mu.mem_read(off, 4)))[0] if f[2] == 'R_ARM_ABS32' else 0
        mu.mem_write(off, struct.pack('<I', (val + cur) & 0xffffffff))
# PLT stubs (ARM): add ip,pc,#a ; add ip,ip,#b ; ldr pc,[ip,#c]!
PLT_LO, PLT_HI = 0x29d000, 0x2a0000
pltname = {}
a = PLT_LO
while a < PLT_HI:
    w = struct.unpack('<3I', d[a:a + 12])
    if (w[0] & 0xfffff000) == 0xe28fc000 and (w[1] & 0xfffff000) == 0xe28cc000 and (w[2] & 0xfffff000) == 0xe5bcf000:
        def imm(x):
            rot = ((x >> 8) & 15) * 2; v = x & 255
            return ((v >> rot) | (v << (32 - rot))) & 0xffffffff
        got = (a + 8 + imm(w[0]) + imm(w[1]) + (w[2] & 0xfff)) & 0xffffffff
        pltname[a] = symval.get(got, '?').split('@')[0]
        a += 12
    else:
        a += 4
STACK = 0x880000
mu.reg_write(UC_ARM_REG_C1_C0_2, mu.reg_read(UC_ARM_REG_C1_C0_2) | (0xf << 20))
mu.reg_write(UC_ARM_REG_FPEXC, 0x40000000)
mu.mem_map(0x10000000, 0x4000000)
heap = [0x10000000]
def alloc(n):
    p = heap[0]; heap[0] += (max(n, 16) + 15) & ~15; return p
unknown = set()
def hook(uc, addr, size, ud):
    if not (PLT_LO <= addr < PLT_HI): return
    n = pltname.get(addr & ~1, '?')
    r0, r1, r2 = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1), uc.reg_read(UC_ARM_REG_R2)
    ret = 0
    if n in ('_Znwj', '_Znaj', 'malloc'): ret = alloc(r0)
    elif n == 'calloc': ret = alloc(r0 * r1)
    elif n in ('memset',): uc.mem_write(r0, bytes([r1 & 255]) * r2); ret = r0
    elif n in ('memcpy', 'memmove', '__aeabi_memcpy', '__aeabi_memmove'):
        uc.mem_write(r0, bytes(uc.mem_read(r1, r2))); ret = r0
    elif n in ('_ZdlPv', '_ZdaPv', 'free'): ret = 0
    else:
        if n not in unknown: unknown.add(n); print('PLT', n, file=sys.stderr)
    uc.reg_write(UC_ARM_REG_R0, ret)
    uc.reg_write(UC_ARM_REG_PC, uc.reg_read(UC_ARM_REG_LR))
mu.hook_add(UC_HOOK_CODE, hook, begin=PLT_LO, end=PLT_HI)
# fake biomes: object with id at +0x94, Biome::mBiomes[id]
ids = dict(ocean=0, plains=1, desert=2, extremeHills=3, forest=4, taiga=5, swampland=6, river=7, hell=8, sky=9,
           frozenOcean=10, frozenRiver=11, iceFlats=12, iceMountains=13, mushroomIsland=14, mushroomIslandShore=15,
           beaches=16, desertHills=17, forestHills=18, taigaHills=19, smallerExtremeHills=20, jungle=21, jungleHills=22,
           jungleEdge=23, deepOcean=24, stoneBeach=25, coldBeach=26, birchForest=27, birchForestHills=28,
           roofedForest=29, taigaCold=30, taigaColdHills=31, redwoodTaiga=32, redwoodTaigaHills=33,
           extremeHillsWithTrees=34, savanna=35, savannaRock=36, mesa=37, mesaRock=38, mesaClearRock=39)
for k, v in list(ids.items()):
    if k + 'Mutated' not in ids and k not in ('ocean', 'river', 'hell', 'sky', 'frozenOcean', 'frozenRiver', 'iceMountains',
            'mushroomIsland', 'mushroomIslandShore', 'beaches', 'desertHills', 'forestHills', 'taigaHills', 'smallerExtremeHills',
            'jungleHills', 'deepOcean', 'stoneBeach', 'coldBeach', 'taigaColdHills', 'redwoodTaigaHills' if False else 'xx'):
        ids[k + 'Mutated'] = v + 128
ids['redwoodTaigaHillsMutated'] = 161
syms = {}
for line in subprocess.run(['nova', 's', r'^.*Biome::[a-zA-Z]+$'], capture_output=True, text=True).stdout.splitlines():
    m = re.match(r'([0-9a-f]+) obj 4 Biome::(\w+)$', line.strip())
    if m: syms[m.group(2)] = int(m.group(1), 16)
objs = {}
rows = [l.split() for l in open('/home/claude/work/tools/biomes.txt')]
for r in rows:
    i = int(r[0]); objs[i] = alloc(0x200)
for r in rows:
    i, t, rain, cls, par = int(r[0]), float(r[1]), float(r[2]), int(r[3]), int(r[4])
    o = objs[i]
    mu.mem_write(o, struct.pack('<I', (0x70a220 if par >= 0 else 0x709980) + 8))
    mu.mem_write(o + 0x4c, struct.pack('<ff', t, rain))
    mu.mem_write(o + 0x90, struct.pack('<ii', cls, i))
    if par >= 0: mu.mem_write(o + 0x9c, struct.pack('<I', objs[par]))
    mu.mem_write(0x7cb3a4 + 4 * i, struct.pack('<I', o))
for name, addr in syms.items():
    if name in ids and ids[name] in objs: mu.mem_write(addr, struct.pack('<I', objs[ids[name]]))
    else: print('no biome for', name, file=sys.stderr)
def call(fn, args, stack=()):
    sp = STACK - 0x100
    for i, v in enumerate(stack): mu.mem_write(sp + 4 * i, struct.pack('<I', v & 0xffffffff))
    regs = [UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3]
    for i, v in enumerate(args): mu.reg_write(regs[i], v & 0xffffffff)
    mu.reg_write(UC_ARM_REG_SP, sp)
    mu.reg_write(UC_ARM_REG_LR, 0x8fff00 | 1)
    try:
        mu.emu_start(fn | 1, 0x8fff00, count=200000000)
    except UcError as e:
        pc = mu.reg_read(UC_ARM_REG_PC); lr = mu.reg_read(UC_ARM_REG_LR)
        print('ERR', e, hex(pc), 'lr', hex(lr), subprocess.run(['nova','at',hex(pc)],capture_output=True,text=True).stdout.strip(), file=sys.stderr)
        raise
    return mu.reg_read(UC_ARM_REG_R0)
seed, X, Z, W, H = [int(v) for v in sys.argv[1:6]]
sp1, sp2 = alloc(8), alloc(8)
call(0x63e210, [seed & 0xffffffff, (seed >> 32) & 0xffffffff, sp1, sp2], stack=[1])
names = {}
for line in subprocess.run(['nova', 's', 'vtable for .*Layer$'], capture_output=True, text=True).stdout.splitlines():
    m = re.match(r'([0-9a-f]+) obj \d+ vtable for (\w+)$', line.strip())
    if m: names[int(m.group(1), 16) + 8] = m.group(2)
def rd(a): return struct.unpack('<I', bytes(mu.mem_read(a, 4)))[0]
root = rd(sp1 if (len(sys.argv) <= 6 or sys.argv[6] == '1') else sp2)
seen = {}
order = []
def walk(layer, depth):
    if layer in seen: return
    seen[layer] = depth
    order.append(layer)
    for i in range(1, 32):
        w = rd(layer + 4 * i)
        if 0x10000000 <= w < 0x14000000:
            try: vt = rd(w)
            except Exception: continue
            if vt in names: walk(w, depth + 1)
walk(root, 0)
only = sys.argv[7] if len(sys.argv) > 7 else None
for k, layer in enumerate(order):
    vt = rd(layer)
    if only and str(k) not in only.split(','): 
        print('L%d d%d %s' % (k, seen[layer], names.get(vt)), [hex(rd(layer+4*i)) for i in range(4,10)]); continue
    buf1, buf2 = alloc(0x40000), alloc(0x40000)
    ld = alloc(8); mu.mem_write(ld, struct.pack('<II', buf1, buf2))
    call(rd(vt + 0xc), [layer, ld, X, Z], stack=[W, H])
    vals = struct.unpack('<%di' % (W * H), bytes(mu.mem_read(rd(ld), 4 * W * H)))
    print('L%d d%d %s' % (k, seen[layer], names.get(vt)))
    for r in range(H): print(' '.join('%d' % v for v in vals[r * W:(r + 1) * W]))
    sys.stdout.flush()
