"""Run the C++ model constructors of libminecraftpe.so in Unicorn and record
how they build their ModelParts (texOffs, addBox, setPos, rotations, mirror,
children).  Output: JSON with every part of every model, in game units.

usage: emu_models.py LIB OUT.json
"""
import sys, struct, json, subprocess
from unicorn import *
from unicorn.arm_const import *
from elftools.elf.elffile import ELFFile

LIB, OUT = sys.argv[1], sys.argv[2]
f = open(LIB, 'rb'); elf = ELFFile(f); data = open(LIB, 'rb').read()
sym = {}
for s in elf.get_section_by_name('.dynsym').iter_symbols():
    if s['st_value'] and s['st_info']['type'] == 'STT_FUNC':
        sym[s.name] = s['st_value']
plt = {}
relplt = elf.get_section_by_name('.rel.plt'); pltsec = elf.get_section_by_name('.plt')
st = elf.get_section(relplt['sh_link']); pbase = pltsec['sh_addr'] + 20
for i, r in enumerate(relplt.iter_relocations()):
    plt[pbase + i * 12] = st.get_symbol(r['r_info_sym']).name

mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
MEM = 0x2000000
mu.mem_map(0, MEM)
for seg in elf.iter_segments():
    if seg['p_type'] == 'PT_LOAD':
        mu.mem_write(seg['p_vaddr'], data[seg['p_offset']:seg['p_offset'] + seg['p_filesz']])
# enable VFP
mu.reg_write(UC_ARM_REG_C1_C0_2, mu.reg_read(UC_ARM_REG_C1_C0_2) | (0xf << 20))
mu.reg_write(UC_ARM_REG_FPEXC, 0x40000000)
HEAP = [0x1000000]
STACK_TOP = 0x1ff0000
RET = 0x1fff000  # magic return address
mu.mem_write(RET, b'\x00\xbf' * 8)


def alloc(n):
    a = HEAP[0]; HEAP[0] += (n + 15) & ~15
    return a


def rd32(a): return struct.unpack('<I', mu.mem_read(a, 4))[0]
def rdf(a): return struct.unpack('<f', mu.mem_read(a, 4))[0]
def vec(a): return [round(rdf(a + 4 * i), 4) for i in range(3)]
def u2f(u): return struct.unpack('<f', struct.pack('<I', u & 0xffffffff))[0]

parts = {}      # addr -> dict
order = []


def P(a):
    if a not in parts:
        parts[a] = {'tex': [0, 0], 'texsize': [64, 32], 'boxes': [], 'pos': [0, 0, 0], 'children': []}
        order.append(a)
    return parts[a]


def ret(val=None):
    if val is not None: mu.reg_write(UC_ARM_REG_R0, val)
    lr = mu.reg_read(UC_ARM_REG_LR)
    mu.reg_write(UC_ARM_REG_PC, lr)


def stack_arg(i):
    return rd32(mu.reg_read(UC_ARM_REG_SP) + 4 * i)


H = {}
def nohook(fn): return fn
def hook(name):
    def deco(fn):
        H[sym[name] & ~1] = fn
        return fn
    return deco


#hook('_ZN9ModelPartC1Eiiii')  (runs natively)
@nohook
def _ctor(r):
    p = P(r[0]); p['tex'] = [r[1], r[2]]; p['texsize'] = [r[3], stack_arg(0)]
    mu.mem_write(r[0] + 0x18, b'\x00'); ret(r[0])

@hook('_ZN9ModelPartC1EOS_')
def _move(r):
    src = parts.get(r[1])
    if src is not None:
        parts[r[0]] = src
        order.append(r[0])
        src['moved_to'] = r[0]
    mu.mem_write(r[0], bytes(mu.mem_read(r[1], 0xa0))); ret(r[0])

#hook('_ZN9ModelPart7texOffsEii')  (runs natively)
@nohook
def _tex(r): P(r[0])['tex'] = [r[1], r[2]]; ret(r[0])

#hook('_ZN9ModelPart10setTexSizeEii')  (runs natively)
@nohook
def _ts(r): P(r[0])['texsize'] = [r[1], r[2]]; ret(r[0])

#hook('_ZN9ModelPart6setPosERK4Vec3')  (runs natively)
@nohook
def _pos(r):
    v = vec(r[1]); P(r[0])['pos'] = v
    for i in range(3): mu.mem_write(r[0] + 4 * i, struct.pack('<f', v[i]))
    ret()

@hook('_ZN9ModelPart6addBoxERK4Vec3S2_f')
def _box(r):
    p = P(r[0]); mirror = mu.mem_read(r[0] + 0x18, 1)[0]
    p['boxes'].append({'origin': vec(r[1]), 'size': vec(r[2]), 'inflate': round(u2f(r[3]), 4),
                       'uv': [rd32(r[0] + 0x40), rd32(r[0] + 0x44)], 'mirror': bool(mirror)})
    ret(r[0])

@hook('_ZN9ModelPart12addBoxMapTexERK4Vec3S2_RK13TextureOffsetf')
def _boxm(r):
    p = P(r[0]); mirror = mu.mem_read(r[0] + 0x18, 1)[0]
    p['boxes'].append({'origin': vec(r[1]), 'size': vec(r[2]), 'inflate': round(u2f(stack_arg(0)), 4),
                       'uv': [rd32(r[3]), rd32(r[3] + 4)], 'mirror': bool(mirror)})
    ret(r[0])

@hook('_ZN9ModelPart8addChildEPS_')
def _child(r): P(r[0])['children'].append(r[1]); ret()

@hook('_ZN5Model13registerPartsER9ModelPart')
def _reg(r): P(r[1])['registered'] = True; ret()

@hook('_ZN5ModelC2Ev')
def _model(r): ret(r[0])


def find(name):
    for k, v in sym.items():
        if k == name: return v
    raise KeyError(name)


def on_code(uc, addr, size, ud):
    regs = [uc.reg_read(UC_ARM_REG_R0 + i) for i in range(4)]
    a = addr & ~1
    if a in H:
        H[a](regs); return
    if a in plt:
        n = plt[a]
        if n in ('_Znwj', '_Znaj', 'malloc'):
            ret(alloc(regs[0]))
        elif n in ('memcpy', 'memmove'):
            uc.mem_write(regs[0], bytes(uc.mem_read(regs[1], regs[2]))); ret(regs[0])
        elif n in ('sinf', 'cosf', 'sqrtf', 'floorf', 'ceilf', 'fabsf', 'tanf'):
            import math
            x = u2f(regs[0])
            fn = {'sinf': math.sin, 'cosf': math.cos, 'sqrtf': lambda v: math.sqrt(max(v, 0)), 'floorf': math.floor,
                  'ceilf': math.ceil, 'fabsf': abs, 'tanf': math.tan}[n]
            ret(struct.unpack('<I', struct.pack('<f', fn(x)))[0])
        elif n in ('atan2f', 'fmodf', 'powf'):
            import math
            x, y = u2f(regs[0]), u2f(regs[1])
            v = {'atan2f': math.atan2, 'fmodf': math.fmod, 'powf': math.pow}[n](x, y)
            ret(struct.unpack('<I', struct.pack('<f', v))[0])
        elif n == 'memset':
            uc.mem_write(regs[0], bytes([regs[1] & 0xff]) * regs[2]); ret(regs[0])
        else:
            ret(0)
    elif a == RET:
        uc.emu_stop()


mu.hook_add(UC_HOOK_CODE, on_code)

MODELS = {
    'chicken': ('_ZN12ChickenModelC2Ev', []),
    'rabbit': ('_ZN11RabbitModelC2Ev', []),
    'blaze': ('_ZN10BlazeModelC2Ev', []),
    'slime': ('_ZN10SlimeModelC2Ei', [16]),
    'slime_outer': ('_ZN10SlimeModelC2Ei', [0]),
    'magma_cube': ('_ZN14LavaSlimeModelC2Ev', []),
    'silverfish': ('_ZN15SilverfishModelC2Ev', []),
    'boat': ('_ZN9BoatModelC2Ev', []),
    'minecart': ('_ZN13MinecartModelC2Ev', []),
}
result = {}
for key, (ctor, args) in MODELS.items():
    parts.clear(); order.clear()
    obj = alloc(0x2000)
    mu.mem_write(obj, b'\x00' * 0x2000)
    mu.reg_write(UC_ARM_REG_SP, STACK_TOP)
    mu.reg_write(UC_ARM_REG_R0, obj)
    for i, a in enumerate(args): mu.reg_write(UC_ARM_REG_R1 + i, a)
    mu.reg_write(UC_ARM_REG_LR, RET | 1)
    try:
        mu.emu_start(find(ctor), RET, count=2000000)
    except UcError as e:
        print(key, 'emu error', e, hex(mu.reg_read(UC_ARM_REG_PC)))
    # pose: run the model's own setupAnim once (limb 0, age 0) -> editor bind pose
    vt = rd32(obj)
    setup = rd32(vt + 11 * 4)
    f = lambda v: struct.unpack('<I', struct.pack('<f', v))[0]
    mu.reg_write(UC_ARM_REG_SP, STACK_TOP - 0x40)
    for i, v in enumerate([0.0, 0.0, 0.0]): mu.mem_write(STACK_TOP - 0x40 + 4 * i, struct.pack('<f', v))
    mu.mem_write(STACK_TOP - 0x40 + 8, struct.pack('<f', 0.0625))
    mu.reg_write(UC_ARM_REG_R0, obj)
    mu.reg_write(UC_ARM_REG_R1, f(0.0)); mu.reg_write(UC_ARM_REG_R2, f(0.0)); mu.reg_write(UC_ARM_REG_R3, f(0.0))
    mu.reg_write(UC_ARM_REG_LR, RET | 1)
    posed = True
    try:
        mu.emu_start(setup, RET, count=2000000)
    except UcError as e:
        posed = False
        print(key, 'setupAnim emu error', e)
    out = []
    seen = set()
    for a in order:
        p = parts[a]
        if id(p) in seen: continue
        seen.add(id(p))
        addr = p.get('moved_to', a)
        rot = [round(rdf(addr + 0xc + 4 * i), 5) for i in range(3)]
        p['pos'] = vec(addr)
        p['texsize'] = [int(rdf(addr + 0x34)), int(rdf(addr + 0x38))]
        p['mirror'] = bool(mu.mem_read(addr + 0x18, 1)[0])
        out.append({'offset': (addr - obj) if obj <= addr < obj + 0x2000 else None, 'addr': addr,
                    'tex': p['tex'], 'texsize': p['texsize'], 'pos': p['pos'], 'rot': rot,
                    'boxes': p['boxes'], 'children': p['children'], 'registered': p.get('registered', False)})
    # vectors of parts inside the object (std::vector<ModelPart>: begin,end,cap)
    result[key] = out
    print(key, len(out), 'parts', sum(len(p['boxes']) for p in out), 'boxes')
json.dump(result, open(OUT, 'w'), indent=1)
