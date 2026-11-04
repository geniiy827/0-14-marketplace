"""Emulate VillageFeature::isFeatureChunk + VillageStart of 0.14.3 for a chunk; dump the pieces.
usage: emu_village.py SEED CX CZ"""
import sys, struct
sys.argv = [sys.argv[0]] + sys.argv[1:]
exec(open('/home/claude/work/tools/emu_base.py').read())
def rd(a): return struct.unpack('<I', bytes(mu.mem_read(a, 4)))[0]
# --- std::string (COW, gnustl): rep = [len, cap, refcount] + chars; object = pointer to chars
def mkstr(b):
    rep = alloc(12 + len(b) + 1)
    mu.mem_write(rep, struct.pack('<iii', len(b), len(b), 0) + b + b'\0')
    return rep + 12
def cstr(p):
    out = b''
    while True:
        c = bytes(mu.mem_read(p, 64)); i = c.find(b'\0')
        if i >= 0: return out + c[:i]
        out += c; p += 64
def strof(obj): return cstr(rd(obj))
EMPTY = mkstr(b'')
extra = {}
def h_ctor(uc):   # _ZNSsC1EPKcRKSaIcE(this, cstr, alloc)
    t, p = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1)
    uc.mem_write(t, struct.pack('<I', mkstr(cstr(p)))); return t
def h_assign(uc):  # assign(this, const string&)
    t, o = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1)
    uc.mem_write(t, struct.pack('<I', rd(o))); return t
def h_swap(uc):
    t, o = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1)
    a, b = rd(t), rd(o); uc.mem_write(t, struct.pack('<I', b)); uc.mem_write(o, struct.pack('<I', a)); return t
def h_copy(uc):   # _ZNSsC1ERKSs(this, other)
    t, o = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1)
    uc.mem_write(t, struct.pack('<I', rd(o))); return t
def h_cmp(uc):    # _ZNKSs7compareEPKc(this, cstr)
    t, p = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1)
    a, b = strof(t), cstr(p); return 0 if a == b else (-1 if a < b else 1)
def h_cmp2(uc):   # _ZNKSs7compareERKSs
    t, o = uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1)
    a, b = strof(t), strof(o); return 0 if a == b else (-1 if a < b else 1)
def h_zero(uc): return 0
STRF = {'_ZNSsC1EPKcRKSaIcE': h_ctor, '_ZNSs6assignERKSs': h_assign, '_ZNSs4swapERSs': h_swap, '_ZNSsC1ERKSs': h_copy,
        '_ZNKSs7compareEPKc': h_cmp, '_ZNKSs7compareERKSs': h_cmp2, '_ZNSs4_Rep10_M_destroyERKSaIcE': h_zero,
        'pthread_mutex_lock': h_zero, 'pthread_mutex_unlock': h_zero}
def hook2(uc, addr, size, ud):
    if not (PLT_LO <= addr < PLT_HI): return
    n = pltname.get(addr & ~1, '?')
    if n in STRF:
        r = STRF[n](uc)
        uc.reg_write(UC_ARM_REG_R0, r & 0xffffffff)
        uc.reg_write(UC_ARM_REG_PC, uc.reg_read(UC_ARM_REG_LR))
        return
mu.hook_add(UC_HOOK_CODE, hook2, begin=PLT_LO, end=PLT_HI)
# NOTE: hook order: hook2 is added after hook; unicorn calls both; hook (base) would overwrite r0 for unknown names,
# so make the base hook skip names handled here
seed, CX, CZ = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3])
bs = alloc(0x100)
call(0x59c244, [bs, seed & 0xffffffff, 1])
vf = alloc(0x100)
call(0x624af8, [vf])
rnd = alloc(0xa00)
cp = alloc(8); mu.mem_write(cp, struct.pack('<ii', CX, CZ))
ok = call(0x5e6cb0, [vf, bs, rnd, cp])
print('isFeatureChunk', ok & 0xff)
st = alloc(0x100)
call(0x63c69c, [st, bs, rnd, CX], stack=[CZ, 0])
names = {}
for line in open('/tmp/claude-0/-home-claude/35a8a87f-967e-5dfe-920c-0842806e132a/scratchpad/nm.txt'):
    f = line.split(None, 2)
    if len(f) == 3 and f[2].startswith('vtable for '): names[int(f[0], 16) + 8] = f[2][11:].strip()
print('start words', [hex(rd(st + 4 * i)) for i in range(16)])
b, e = rd(st + 0x24), rd(st + 0x28)
print('pieces', (e - b) // 4)
for p in range(b, e, 4):
    o = rd(p)
    print(names.get(rd(o), hex(rd(o))), [struct.unpack('<i', struct.pack('<I', rd(o + 4 * i)))[0] for i in range(1, 12)])
