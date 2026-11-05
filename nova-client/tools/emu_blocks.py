"""Run Material::initMaterials + Block::initBlocks of libminecraftpe 0.14.3 in Unicorn and dump the per-id
light tables: mLightEmission, mLightBlock, mTranslucency, mSolid (+ shape/property info where cheap).
usage: emu_blocks.py LIB OUT.json"""
import sys, struct, json, math
from unicorn import *
from unicorn.arm_const import *
from elftools.elf.elffile import ELFFile
LIB, OUT = sys.argv[1], sys.argv[2]
f = open(LIB, 'rb'); elf = ELFFile(f); data = open(LIB, 'rb').read()
sym = {}; obj = {}
for s in elf.get_section_by_name('.dynsym').iter_symbols():
    if s['st_value']:
        if s['st_info']['type'] == 'STT_FUNC': sym[s.name] = s['st_value']
        else: obj[s.name] = s['st_value']
plt = {}
relplt = elf.get_section_by_name('.rel.plt'); pltsec = elf.get_section_by_name('.plt')
st = elf.get_section(relplt['sh_link']); pbase = pltsec['sh_addr'] + 20
for i, r in enumerate(relplt.iter_relocations()):
    plt[pbase + i * 12] = st.get_symbol(r['r_info_sym']).name
mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
MEM = 0x4000000
mu.mem_map(0, MEM)
for seg in elf.iter_segments():
    if seg['p_type'] == 'PT_LOAD':
        mu.mem_write(seg['p_vaddr'], data[seg['p_offset']:seg['p_offset'] + seg['p_filesz']])
# relocations
dsym = elf.get_section_by_name('.dynsym')
DUMMY = 0x3f00000
for secname in ('.rel.dyn',):
    sec = elf.get_section_by_name(secname)
    for r in sec.iter_relocations():
        t = r['r_info_type']; off = r['r_offset']
        cur = struct.unpack('<I', bytes(mu.mem_read(off, 4)))[0]
        if t == 23:  # RELATIVE
            continue  # already absolute (base 0)
        s = dsym.get_symbol(r['r_info_sym']); val = s['st_value'] or DUMMY
        if t == 2: mu.mem_write(off, struct.pack('<I', (val + cur) & 0xffffffff))
        elif t == 21: mu.mem_write(off, struct.pack('<I', val))
mu.reg_write(UC_ARM_REG_C1_C0_2, mu.reg_read(UC_ARM_REG_C1_C0_2) | (0xf << 20))
mu.reg_write(UC_ARM_REG_FPEXC, 0x40000000)
HEAP = [0x1000000]; STACK_TOP = 0x3ef0000; RET = 0x3eff000
mu.mem_write(RET, b'\x00\xbf' * 8)
def alloc(n):
    if n > 0x1000000: n = 0x100
    a = HEAP[0]; HEAP[0] += (max(n, 4) + 15) & ~15
    mu.mem_write(a, b'\0' * max(n, 4)); return a
def rd32(a): return struct.unpack('<I', mu.mem_read(a, 4))[0]
def u2f(u): return struct.unpack('<f', struct.pack('<I', u & 0xffffffff))[0]
def f2u(v): return struct.unpack('<I', struct.pack('<f', v))[0]
def ret(val=None):
    if val is not None: mu.reg_write(UC_ARM_REG_R0, val & 0xffffffff)
    mu.reg_write(UC_ARM_REG_PC, mu.reg_read(UC_ARM_REG_LR))
def cstr(a):
    b = b''
    while True:
        c = mu.mem_read(a, 1)
        if c == b'\0': return b
        b += bytes(c); a += 1
def sget(p):
    s = rd32(p)
    if s < 0x100c or s >= MEM: return b''
    n = rd32(s - 12)
    if n > 100000: return b''
    return bytes(mu.mem_read(s, n))
def mkstr(b):
    rep = alloc(12 + len(b) + 1)
    mu.mem_write(rep, struct.pack('<iii', len(b), len(b), 0)); mu.mem_write(rep + 12, b + b'\0')
    return rep + 12
EMPTY = mkstr(b'')
unk = {}
def on_code(uc, addr, size, ud):
    global LAST
    LAST = addr
    a = addr & ~1
    if a == RET: uc.emu_stop(); return
    if a in HOOKS: HOOKS[a](); return
    if a not in plt: return
    n = plt[a]; r = [uc.reg_read(UC_ARM_REG_R0 + i) for i in range(4)]
    if n in ('_Znwj', '_Znaj', 'malloc'): ret(alloc(r[0]))
    elif n == 'calloc': ret(alloc(r[0] * r[1]))
    elif n in ('memcpy', 'memmove'):
        if r[2]: uc.mem_write(r[0], bytes(uc.mem_read(r[1], r[2])))
        ret(r[0])
    elif n == 'memset': uc.mem_write(r[0], bytes([r[1] & 0xff]) * r[2]); ret(r[0])
    elif n == 'strlen': ret(len(cstr(r[0])))
    elif n == 'memcmp':
        x = bytes(uc.mem_read(r[0], r[2])); y = bytes(uc.mem_read(r[1], r[2])); ret((x > y) - (x < y))
    elif n == 'strcmp':
        x = cstr(r[0]); y = cstr(r[1]); ret((x > y) - (x < y))
    elif n == '_ZNSsC1EPKcRKSaIcE':   # string(const char*)
        uc.mem_write(r[0], struct.pack('<I', mkstr(cstr(r[1])))); ret(r[0])
    elif n in ('_ZNSsC1ERKSs',):      # copy
        uc.mem_write(r[0], struct.pack('<I', rd32(r[1]))); ret(r[0])
    elif n in ('_ZNSsC1Ev',): uc.mem_write(r[0], struct.pack('<I', EMPTY)); ret(r[0])
    elif n in ('_ZNSs4_Rep10_M_disposeERKSaIcE', '_ZNSsD1Ev', '_ZdlPv', 'free', '_ZdaPv'): ret(0)
    elif n == '_ZNSs6appendEPKcj':
        uc.mem_write(r[0], struct.pack('<I', mkstr(sget(r[0]) + bytes(uc.mem_read(r[1], r[2]))))); ret(r[0])
    elif n == '_ZNSs6appendERKSs':
        uc.mem_write(r[0], struct.pack('<I', mkstr(sget(r[0]) + sget(r[1])))); ret(r[0])
    elif n == '_ZNSsaSERKSs': uc.mem_write(r[0], struct.pack('<I', rd32(r[1]))); ret(r[0])
    elif n == '_ZNKSt8__detail20_Prime_rehash_policy11_M_next_bktEj': ret(1031)
    elif n == '_ZNKSt8__detail20_Prime_rehash_policy14_M_need_rehashEjjj':
        uc.mem_write(r[0], b'\0' * 8); ret(r[0])
    elif n == '_ZNSs6assignEPKc': uc.mem_write(r[0], struct.pack('<I', mkstr(cstr(r[1])))); ret(r[0])
    elif n in ('ceil',): ret(0)
    elif n == '_ZSt11_Hash_bytesPKvjj':
        h = r[2]
        for c in bytes(uc.mem_read(r[0], r[1])): h = (h * 31 + c) & 0xffffffff
        ret(h)
    elif n in ('powf',): ret(f2u(math.pow(u2f(r[0]), u2f(r[1]))))
    elif n in ('sinf', 'cosf', 'sqrtf', 'floorf', 'ceilf'):
        x = u2f(r[0]); ret(f2u({'sinf': math.sin, 'cosf': math.cos, 'sqrtf': lambda v: math.sqrt(max(v, 0)), 'floorf': math.floor, 'ceilf': math.ceil}[n](x)))
    else:
        unk[n] = unk.get(n, 0) + 1; ret(0)
HOOKS = {}
DUMMYITEM = 0
def _item():
    global DUMMYITEM
    if not DUMMYITEM:
        DUMMYITEM = alloc(0x400)
        mu.mem_write(DUMMYITEM, struct.pack('<I', mkstr(b'x')))
    ret(DUMMYITEM)
for k, v in sym.items():
    if k.startswith('_ZNK12TextureAtlas14getTextureItem'): HOOKS[v & ~1] = _item
mu.hook_add(UC_HOOK_CODE, on_code)
def call(name, *args):
    mu.reg_write(UC_ARM_REG_SP, STACK_TOP)
    for i, v in enumerate(args): mu.reg_write(UC_ARM_REG_R0 + i, v)
    mu.reg_write(UC_ARM_REG_LR, RET | 1)
    try:
        mu.emu_start(sym[name], RET, count=40000000)
    except UcError as e:
        print(name, "emu error", e, hex(mu.reg_read(UC_ARM_REG_PC)), hex(LAST), hex(mu.reg_read(UC_ARM_REG_LR)))
ia_sec = elf.get_section_by_name('.init_array')
ia = [struct.unpack('<I', bytes(mu.mem_read(ia_sec['sh_addr'] + 4 * i, 4)))[0] for i in range(ia_sec['sh_size'] // 4)]
for fn in ia:
    if fn in (0, 0xffffffff): continue
    mu.reg_write(UC_ARM_REG_SP, STACK_TOP); mu.reg_write(UC_ARM_REG_LR, RET | 1)
    try: mu.emu_start(fn, RET, count=20000000)
    except UcError as e: print('init', hex(fn), e, hex(LAST))
for n in ('_ZN8Material13initMaterialsEv',):
    if n in sym: call(n)
    else: print('no', n)
call('_ZN5Block10initBlocksEv')
print('unknown plt:', sorted(unk.items(), key=lambda x: -x[1])[:30])
def tab(name, n, fmt, sz):
    a = obj[name]; return [struct.unpack(fmt, mu.mem_read(a + i * sz, sz))[0] for i in range(n)]
mb = obj['_ZN5Block7mBlocksE']
blocks = [rd32(mb + 4 * i) for i in range(256)]
res = {'emission': tab('_ZN5Block14mLightEmissionE', 256, 'B', 1), 'lightBlock': tab('_ZN5Block11mLightBlockE', 256, 'B', 1),
       'translucency': tab('_ZN5Block13mTranslucencyE', 256, '<f', 4), 'solid': tab('_ZN5Block6mSolidE', 256, 'B', 1),
       'exists': [b != 0 for b in blocks]}
names = []
for b in blocks:
    try: names.append(sget(b + 8).decode() if b else '')
    except Exception: names.append('?')
res['names'] = names
json.dump(res, open(OUT, 'w'))
for i in range(256):
    if blocks[i]: print(i, names[i], 'em', res['emission'][i], 'lb', res['lightBlock'][i], 'tr', round(res['translucency'][i], 3), 'solid', res['solid'][i])
