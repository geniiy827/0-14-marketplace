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

H[sym('StructurePiece::generateBox(BlockSource*, BoundingBox const&, int, int, int, int, int, int, BlockID, unsigned char, BlockID, unsigned char, bool)')] = lambda: (
    ops.append(['B'] + [s32(arg(i)) for i in range(3, 9)] + [bytes(mu.mem_read(arg(9), 1))[0], arg(10) & 255, bytes(mu.mem_read(arg(11), 1))[0], arg(12) & 255, arg(13) & 255]), ret())
H[sym('StructurePiece::generateAirBox(BlockSource*, BoundingBox const&, int, int, int, int, int, int)')] = lambda: (
    ops.append(['B'] + [s32(arg(i)) for i in range(3, 9)] + [0, 0, 0, 0, 0]), ret())
H[sym('SwamplandHut::placeCauldron(BlockSource*, Random&, int, int, int, BoundingBox const&)')] = lambda: (ops.append(['Q', s32(arg(3)), s32(arg(4)), s32(arg(5))]), ret())
for nm in ['StructurePiece::maybeGenerateBlock(BlockSource*, BoundingBox const&, Random&, float, int, int, int, BlockID, unsigned char)',
           'StructurePiece::generateMaybeBox(BlockSource*, BoundingBox const&, Random&, float, int, int, int, int, int, int, BlockID, BlockID, bool, Brightness)',
           'StructurePiece::generateUpperHalfSphere(BlockSource*, BoundingBox const&, int, int, int, int, int, int, BlockID, bool)',
           'StructurePiece::generateBox(BlockSource*, BoundingBox const&, BoundingBox const&, BlockID, BlockID, bool)']:
    H[sym(nm)] = (lambda n: (lambda: (ops.append(['X', n]), ret())))(nm.split('(')[0])
H[sym('BlockSource::getBlockEntity(BlockPos const&)')] = lambda: ret(0)
for a in list(H):
    if H[a]: mu.hook_add(UC_HOOK_CODE, phook, begin=a, end=a + 1)
out = {}
big = alloc(24); mu.mem_write(big, struct.pack('<6i', -100000, 0, -100000, 100000, 512, 100000))
for name, ctorn in [('DesertPyramidPiece', 'DesertPyramidPiece::DesertPyramidPiece(Random&, int, int)'), ('SwamplandHut', 'SwamplandHut::SwamplandHut(Random&, int, int)')]:
    r2 = alloc(0xa00); mu.mem_write(r2 + 0x9c4, struct.pack('<I', 625)); mu.mem_write(r2, struct.pack('<I', 5489))
    obj = alloc(0x200)
    call(sym(ctorn), [obj, r2, 0, 0])
    print(name, 'bb', [s32(rd(obj + 4 * i)) for i in range(1, 7)], 'fields', [hex(rd(obj + 4 * i)) for i in range(7, 14)], file=sys.stderr)
    mu.mem_write(obj + 0x30, struct.pack('<i', 64))
    # MT state: seed it
    call(sym('Random::genrand_int32()'), [r2]) if False else None
    del ops[:]; rec[0] = True
    try: call(rd(rd(obj) + 0xc), [obj, alloc(16), r2, big])
    except Exception as e: print('ERR', name, e, file=sys.stderr)
    rec[0] = False
    out[name] = {'box': [s32(rd(obj + 4 * i)) for i in range(1, 7)], 'ops': list(ops)}
    print(name, len(ops), file=sys.stderr)
json.dump(out, open('/home/claude/work/tools/scattered.json', 'w'))
