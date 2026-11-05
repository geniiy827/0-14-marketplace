"""Run the original MineshaftStart + every piece's postProcess of 0.14.3 in unicorn with a dict world behind the
BlockSource calls. usage: emu_mineshaft.py STARTSEED CX CZ PPSEED -> JSON {pieces:[...], blocks:{"x,y,z":[id,data]}, draws:n}"""
import sys, struct, json, os
A = sys.argv[1:]
exec(open('/home/claude/work/tools/emu_strings.py').read())
exec(open('/home/claude/work/tools/mt.py').read().split("if __name__")[0])
SYM = {}
for line in open('/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/nm.txt'):
    f = line.split(None, 2)
    if len(f) == 3: SYM.setdefault(f[2].strip(), int(f[0], 16) & ~1)
ADDR2 = {}
for k, v in SYM.items(): ADDR2.setdefault(v, k)
def sym(n): return SYM[n]
def s32(v): return struct.unpack('<i', struct.pack('<I', v & 0xffffffff))[0]
def arg(i):
    if i < 4: return mu.reg_read([UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3][i])
    return rd(mu.reg_read(UC_ARM_REG_SP) + 4 * (i - 4))
def ret(v=0):
    mu.reg_write(UC_ARM_REG_R0, v & 0xffffffff)
    mu.reg_write(UC_ARM_REG_PC, mu.reg_read(UC_ARM_REG_LR))
exec(open('/home/claude/work/tools/blockids.py').read())
BVT = SYM.get('vtable for Block', 0) + 8
for k, v in SYM.items():
    if k.startswith('Block::m') and k[7:] in IDS:
        o = alloc(0x100); mu.mem_write(o, struct.pack('<I', BVT)); mu.mem_write(o + 4, bytes([IDS[k[7:]]]))
        mu.mem_write(v, struct.pack('<I', o))
        if 'Block::mBlocks' in SYM: mu.mem_write(SYM['Block::mBlocks'] + 4 * IDS[k[7:]], struct.pack('<I', o))
def world0(x, y, z):
    if y <= 0: return (7, 0)
    if y > 70: return (0, 0)
    if (x * 7 + z * 13 + y * 3) % 11 == 0: return (0, 0)
    return (1, 0)
W = {}
def getb(x, y, z): return W.get((x, y, z)) or world0(x, y, z)
def pos(p): return s32(rd(p)), s32(rd(p + 4)), s32(rd(p + 8))
calls = {}
def note(n): calls[n] = calls.get(n, 0) + 1
H = {}
def bs_getid():
    res = arg(0); x, y, z = pos(arg(2)); note('getBlockID'); mu.mem_write(res, bytes([getb(x, y, z)[0]])); ret(res)
def bs_getdata(): x, y, z = pos(arg(1)); note('getData'); ret(getb(x, y, z)[1])
def bs_getbad():   # FullBlock getBlockAndData(pos): returned in r0 (2 bytes)
    x, y, z = pos(arg(1)); note('getBlockAndData'); b = getb(x, y, z); ret(b[0] | b[1] << 8)
def bs_set():
    x, y, z = pos(arg(1)); b = bytes(mu.mem_read(arg(2), 2)); note('setBlockAndData'); W[(x, y, z)] = (b[0], b[1]); ret(1)
def bs_setb():   # setBlock(pos, BlockID, int)
    x, y, z = pos(arg(1)); note('setBlock'); W[(x, y, z)] = (arg(2) & 255, 0); ret(1)
for n, f in [('BlockSource::getBlockID(BlockPos const&)', bs_getid), ('BlockSource::getData(BlockPos const&)', bs_getdata),
             ('BlockSource::getBlockAndData(BlockPos const&)', bs_getbad), ('BlockSource::setBlockAndData(BlockPos const&, FullBlock, int)', bs_set),
             ('BlockSource::setBlock(BlockPos const&, BlockID, int)', bs_setb)]:
    if n in SYM: H[sym(n)] = f
    else: print('missing', n, file=sys.stderr)
MATS = {}
def mat(id_):
    if id_ not in MATS:
        o = alloc(64); mu.mem_write(o, struct.pack('<I', 0x4d415400 | id_)); MATS[id_] = o
    return MATS[id_]
MATID = {}
def bs_mat3(): x, y, z = s32(arg(1)), s32(arg(2)), s32(arg(3)); note('getMaterial'); ret(mat(getb(x, y, z)[0]))
def bs_mat(): x, y, z = pos(arg(1)); note('getMaterial'); ret(mat(getb(x, y, z)[0]))
def mat_id(): return rd(arg(0)) & 255
LIQ = (8, 9, 10, 11)
H[sym('BlockSource::getMaterial(int, int, int)')] = bs_mat3
H[sym('BlockSource::getMaterial(BlockPos const&)')] = bs_mat
H[sym('Material::isLiquid() const')] = lambda: ret(1 if mat_id() in LIQ else 0)
def m_istype():
    t = arg(1); i = mat_id(); note('isType%d' % t)
    ret(1 if (t == 0 and i == 0) else 0)
H[sym('Material::isType(MaterialType) const')] = m_istype
H[sym('Material::isSolid() const')] = lambda: (note('isSolid'), ret(0 if mat_id() in (0, 8, 9, 10, 11, 30, 50, 66) else 1))
def bs_getblock():
    x, y, z = pos(arg(1)); note('getBlock'); ret(rd(SYM['Block::mBlocks'] + 4 * getb(x, y, z)[0]))
H[sym('BlockSource::getBlock(BlockPos const&)')] = bs_getblock
def bs_set5():
    x, y, z = s32(arg(1)), s32(arg(2)), s32(arg(3)); b = bytes(mu.mem_read(arg(4), 2)); note('setBlockAndData'); W[(x, y, z)] = (b[0], b[1]); ret(1)
H[sym('BlockSource::setBlockAndData(int, int, int, FullBlock, int)')] = bs_set5
H[sym('BlockSource::getBlockEntity(int, int, int)')] = lambda: (note('getBlockEntity'), ret(0))
SPAWNER = alloc(0x100)
H[sym('MobSpawnerBlockEntity::getSpawner()')] = lambda: ret(SPAWNER)
H[sym('BaseMobSpawner::setEntityId(EntityType)')] = lambda: ret(0)
def bs_bright():
    res = arg(0); x, y, z = pos(arg(3)); note('getBrightness'); mu.mem_write(res, bytes([BRIGHT(x, y, z)])); ret(res)
def BRIGHT(x, y, z): return 15 if y >= 30 else 0
H[sym('BlockSource::getBrightness(LightLayer const&, BlockPos const&)')] = bs_bright
H[sym('BlockSource::getBlockEntity(BlockPos const&)')] = lambda: (note('getBlockEntity'), ret(0))
if '_isSurfaceMineshaft(BiomeSource&, ChunkPos const&)' in SYM: H[sym('_isSurfaceMineshaft(BiomeSource&, ChunkPos const&)')] = lambda: ret(int(os.environ.get('SURF', '0')))
DBG = os.environ.get('DBG') if 'os' in dir() else None
import os
DBG = os.environ.get('DBG')
def dbg_hook(name, nargs):
    def f():
        a_ = [s32(arg(i)) for i in range(nargs)]
        print('DBG', name, a_, file=sys.stderr)
    return f
DBGH = {}
if DBG:
    DBGH[sym('MineshaftCorridor::_placeSupport(BlockSource*, BoundingBox const&, int, int, int, int, int, Random&)')] = dbg_hook('support', 8)
    DBGH[sym('MineshaftCorridor::_placeCobWeb(BlockSource*, BoundingBox const&, Random&, float, int, int, int)')] = dbg_hook('web', 8)
    DBGH[sym('StructurePiece::maybeGenerateBlock(BlockSource*, BoundingBox const&, Random&, float, int, int, int, BlockID, unsigned char)')] = lambda: print('DBG mgb', [s32(arg(i)) for i in range(4, 8)], 'mti', rd(arg(3) + 0x9c4), file=sys.stderr)
    DBGH[sym('StructurePiece::generateMaybeBox(BlockSource*, BoundingBox const&, Random&, float, int, int, int, int, int, int, BlockID, BlockID, bool, Brightness)')] = lambda: print('DBG gmb mti', rd(arg(3) + 0x9c4), file=sys.stderr)
    for k_, f_ in DBGH.items():
        mu.hook_add(UC_HOOK_CODE, (lambda ff: (lambda uc, addr, size, ud: ff()))(f_), begin=k_, end=k_ + 1)
BSN = {v: k for k, v in SYM.items() if (k.startswith('BlockSource::') or k.startswith('Level') or k.startswith('Material::')) and v not in H}
unk = set()
draws = [0]
GR = sym('Random::genrand_int32()')
rec = [False]
def phook(uc, addr, size, ud):
    if addr == GR:
        if rec[0]: draws[0] += 1
        return
    f = H.get(addr)
    if f: f(); return
    n = BSN.get(addr)
    if n and n not in unk: unk.add(n); print('unhandled', n, file=sys.stderr)
for a in list(H) + list(BSN) + [GR]: mu.hook_add(UC_HOOK_CODE, phook, begin=a, end=a + 1)
def mkrand(seed):
    r = alloc(0xa00); m = MT(seed)
    mu.mem_write(r, struct.pack('<I', seed & 0xffffffff))
    mu.mem_write(r + 4, struct.pack('<624I', *m.m)); mu.mem_write(r + 0x9c4, struct.pack('<I', 624))
    return r
sol = SYM.get('Block::mSolid')
if sol:
    for i in (1, 3, 5, 7): mu.mem_write(sol + i, b'\x01')
else: print('no mSolid', file=sys.stderr)
start_seed, cx, cz, pp_seed = int(A[0]), int(A[1]), int(A[2]), int(A[3])
r = mkrand(start_seed)
cp = alloc(8); mu.mem_write(cp, struct.pack('<ii', cx, cz))
st = alloc(0x80)
call(sym('MineshaftStart::MineshaftStart(BiomeSource&, Random&, ChunkPos const&)'), [st, alloc(0x100), r, cp])
pieces = []
p0, p1 = rd(st + 0x24), rd(st + 0x28)
for a in range(p0, p1, 4):
    o = rd(a); vt = rd(o)
    name = ADDR2.get(vt - 8, hex(vt))
    pieces.append({'name': name.replace('vtable for ', ''), 'bb': [s32(rd(o + 4 * i)) for i in range(1, 7)],
                   'f': [s32(rd(o + 4 * i)) for i in range(7, 14)], 'o': o})
big = alloc(24); mu.mem_write(big, struct.pack('<6i', -100000, -100, -100000, 100000, 512, 100000))
rp = mkrand(pp_seed)
rec[0] = True
import os
NP = int(os.environ.get('NP', '100000'))
for p in pieces[:NP]:
    o = p['o']
    p['mti0'] = rd(rp + 0x9c4)
    try: call(rd(rd(o) + 0xc), [o, alloc(16), rp, big])
    except Exception as e: print('ERR', p['name'], e, file=sys.stderr)
    p['draws'] = draws[0]
for p in pieces: del p['o']
json.dump({'pieces': pieces, 'blocks': {'%d,%d,%d' % k: list(v) for k, v in W.items()}, 'calls': calls}, open(A[4], 'w'))
print(len(pieces), 'pieces', len(W), 'blocks', calls, file=sys.stderr)
