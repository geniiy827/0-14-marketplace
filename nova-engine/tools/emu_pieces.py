"""Record the building program of each village piece of 0.14.3: run P::postProcess in unicorn with the piece primitives
(placeBlock, generateBox, fillColumnDown, generateAirColumnUp, getBlock, createDoor, createChest) hooked and logged in
piece-local coordinates. Output: JSON {piece: {orientation: {variant: [ops]}}}"""
import sys, struct, json
exec(open('/home/claude/work/tools/emu_strings.py').read())
SYM = {}
for line in open('/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/nm.txt'):
    f = line.split(None, 2)
    if len(f) == 3: SYM.setdefault(f[2].strip(), int(f[0], 16) & ~1)
def sym(n): return SYM[n]
def s32(v): return struct.unpack('<i', struct.pack('<I', v & 0xffffffff))[0]
def arg(i):
    if i < 4: return mu.reg_read([UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3][i])
    return rd(mu.reg_read(UC_ARM_REG_SP) + 4 * (i - 4))
ops = []
rec = [False]
def ret(v=0):
    mu.reg_write(UC_ARM_REG_R0, v & 0xffffffff)
    mu.reg_write(UC_ARM_REG_PC, mu.reg_read(UC_ARM_REG_LR))
def fb(p):
    b = bytes(mu.mem_read(p, 2)); return [b[0], b[1]]
H = {}
H[sym('VillagePiece::placeBlock(BlockSource*, FullBlock, int, int, int, BoundingBox const&)')] = lambda: (ops.append(['P', s32(arg(3)), s32(arg(4)), s32(arg(5))] + fb(arg(2))), ret())
H[sym('StructurePiece::placeBlock(BlockSource*, FullBlock, int, int, int, BoundingBox const&)')] = lambda: (ops.append(['P', s32(arg(3)), s32(arg(4)), s32(arg(5))] + fb(arg(2))), ret())
H[sym('VillagePiece::generateBox(BlockSource*, BoundingBox const&, int, int, int, int, int, int, FullBlock, FullBlock, bool)')] = lambda: (
    ops.append(['B'] + [s32(arg(i)) for i in range(3, 9)] + fb(arg(9)) + fb(arg(10)) + [arg(11) & 255]), ret())
H[sym('StructurePiece::generateBox(BlockSource*, BoundingBox const&, int, int, int, int, int, int, FullBlock, FullBlock, bool)')] = H[sym('VillagePiece::generateBox(BlockSource*, BoundingBox const&, int, int, int, int, int, int, FullBlock, FullBlock, bool)')]
H[sym('VillagePiece::fillColumnDown(BlockSource*, FullBlock, int, int, int, BoundingBox const&)')] = lambda: (ops.append(['D', s32(arg(3)), s32(arg(4)), s32(arg(5))] + fb(arg(2))), ret())
H[sym('StructurePiece::fillColumnDown(BlockSource*, FullBlock, int, int, int, BoundingBox const&)')] = H[sym('VillagePiece::fillColumnDown(BlockSource*, FullBlock, int, int, int, BoundingBox const&)')]
H[sym('StructurePiece::generateAirColumnUp(BlockSource*, int, int, int, BoundingBox const&)')] = lambda: (ops.append(['U', s32(arg(2)), s32(arg(3)), s32(arg(4))]), ret())
def getblock():   # returns FullBlock through the hidden result pointer r0
    res = arg(0); x, y, z = s32(arg(3)), s32(arg(4)), s32(arg(5))
    ops.append(['G', x, y, z]); mu.mem_write(res, bytes([0 if y >= 0 else 1, 0])); ret(res)
H[sym('StructurePiece::getBlock(BlockSource*, int, int, int, BoundingBox const&)')] = getblock
def isair():
    x, y, z = s32(arg(2)), s32(arg(3)), s32(arg(4))
    ops.append(['A', x, y, z]); ret(1 if y >= 0 else 0)
H[sym('StructurePiece::isAir(BlockSource*, int, int, int, BoundingBox const&)')] = isair
H[sym('StructurePiece::createDoor(BlockSource*, BoundingBox const&, Random&, int, int, int, int)')] = lambda: (ops.append(['O', s32(arg(4)), s32(arg(5)), s32(arg(6)), s32(arg(7))]), ret())
H[sym('StructurePiece::createChest(BlockSource*, BoundingBox const&, Random&, int, int, int, int, std::vector<WeighedTreasureItem, std::allocator<WeighedTreasureItem> > const&, int)')] = lambda: (ops.append(['C', s32(arg(4)), s32(arg(5)), s32(arg(6)), s32(arg(7))]), ret(1))
H[sym('Random::genrand_int32()')] = None   # logged, executed
GR = sym('Random::genrand_int32()')
def phook(uc, addr, size, ud):
    if not rec[0]: return
    if addr == GR:
        ops.append(['R']); return
    f = H.get(addr)
    if f: f()
for a in H: mu.hook_add(UC_HOOK_CODE, phook, begin=a, end=a + 1)
unk = set()
def bshook(uc, addr, size, ud):   # any BlockSource call that was not hooked: log once
    if not rec[0]: return
    n = BSN.get(addr)
    if n and n not in unk: unk.add(n); print('BlockSource call', n, file=sys.stderr)
BSN = {v: k for k, v in SYM.items() if k.startswith('BlockSource::') or k.startswith('Level') or k.startswith('Mob') or k.startswith('Villager')}
for a in BSN: mu.hook_add(UC_HOOK_CODE, bshook, begin=a, end=a + 1)
# ---- fake Block objects: id at +4, Block::mBlocks[id]
exec(open('/home/claude/work/tools/blockids.py').read())
BVT = SYM.get('vtable for Block', 0) + 8
for k, v in SYM.items():
    if k.startswith('Block::m') and k[7:] in IDS:
        o = alloc(0x100); mu.mem_write(o, struct.pack('<I', BVT)); mu.mem_write(o + 4, bytes([IDS[k[7:]]]))
        mu.mem_write(v, struct.pack('<I', o))
        if 'Block::mBlocks' in SYM: mu.mem_write(SYM['Block::mBlocks'] + 4 * IDS[k[7:]], struct.pack('<I', o))
# ---- a real village start (gives a StartPiece) for mimi
bs = alloc(0x100); call(0x59c244, [bs, 3351608, 1])
vf = alloc(0x100); call(0x624af8, [vf])
rnd = alloc(0xa00)
cp = alloc(8); mu.mem_write(cp, struct.pack('<ii', 86, 2)); call(0x5e6cb0, [vf, bs, rnd, cp])
st = alloc(0x100); call(0x63c69c, [st, bs, rnd, 86], stack=[2, 0])
startp = rd(rd(st + 0x24))
DESERT = int(sys.argv[1]) if len(sys.argv) > 1 else 0
SIZES = {'SimpleHouse': (5, 6, 5), 'SmallTemple': (5, 12, 9), 'BookHouse': (9, 9, 6), 'SmallHut': (4, 6, 5), 'PigHouse': (9, 7, 11),
         'DoubleFarmland': (13, 4, 9), 'Farmland': (7, 4, 9), 'Smithy': (10, 6, 7), 'TwoRoomHouse': (9, 7, 12), 'LightPost': (3, 4, 2)}
def box(sx, sy, sz, o):     # getComponentToAddBoundingBox at origin 100,64,100 ; o: 0 S,1 W,2 N,3 E
    x, y, z = 100, 64, 100
    if o == 2: return (x, y, z - sz + 1, x + sx - 1, y + sy - 1, z)
    if o == 0: return (x, y, z, x + sx - 1, y + sy - 1, z + sz - 1)
    if o == 1: return (x - sz + 1, y, z, x, y + sy - 1, z + sx - 1)
    return (x, y, z, x + sz - 1, y + sy - 1, z + sx - 1)
out = {}
big = alloc(24); mu.mem_write(big, struct.pack('<6i', -100000, 0, -100000, 100000, 512, 100000))
which = sys.argv[2:] if len(sys.argv) > 2 else list(SIZES)
for name in which:
    out[name] = {}
    ctor = sym('%s::%s(StartPiece*, int, Random&, BoundingBox const&, int)' % (name, name))
    for o in range(4):
        bb = alloc(24); mu.mem_write(bb, struct.pack('<6i', *box(*SIZES[name], o)))
        variants = {}
        for vseed in range(1, 40):
            r2 = alloc(0xa00)
            mu.mem_write(cp, struct.pack('<ii', vseed * 7, vseed * 13)); call(0x5e6cb0, [vf, bs, r2, cp])   # seeds r2
            obj = alloc(0x200)
            call(ctor, [obj, startp, 1, r2], stack=[bb, o])
            fields = tuple(rd(obj + 4 * i) for i in range(10, 16))
            if fields in variants: continue
            mu.mem_write(obj + 0x24, struct.pack('<i', 64))     # averageGroundLevel known: no offset
            del ops[:]; rec[0] = True
            try: call(rd(rd(obj) + 0xc), [obj, alloc(16), r2, big])
            finally: rec[0] = False
            variants[fields] = list(ops)
        out[name][o] = {'box': box(*SIZES[name], o), 'variants': [{'fields': list(k), 'ops': v} for k, v in variants.items()]}
    print(name, [len(out[name][o]['variants']) for o in range(4)], file=sys.stderr)
# Well = the StartPiece itself
wb = (100, 64, 100, 105, 78, 105)
mu.mem_write(startp + 4, struct.pack('<6i', *wb)); mu.mem_write(startp + 0x24, struct.pack('<i', 64))
del ops[:]; rec[0] = True
call(rd(rd(startp) + 0xc), [startp, alloc(16), rnd, big]); rec[0] = False
out['Well'] = {'0': {'box': wb, 'variants': [{'fields': [], 'ops': list(ops)}]}}
print('start fields', [hex(rd(startp + 4 * i)) for i in range(0, 20)], file=sys.stderr)
json.dump(out, open('/home/claude/work/tools/village_pieces%s.json' % ('_desert' if DESERT else ''), 'w'))
