#!/usr/bin/env python3
"""gen_symbols.py OUT.tsv - the table that lets mods call / hook any function of libminecraftpe.so 0.14.3 by name.

One line per function:
  demangled  mangled  addr(hex)  size  args  ret  mode  sites
args: one letter per argument word group as the callee expects them (softfp: everything in r0-r3 / stack):
  s = this (member function), p = pointer/reference/int-like (32 bit), f = float, d = double, q = 64-bit integer,
  v = unknown by-value class (passed as 1 word, may be wrong), S = std::string (const&), c = char const*
ret:  f = float in r0, d = double in r0:r1, i = int/pointer/void (guessed from the code: vmov r0, sX before return)
mode: i = inline hook (prologue relocatable, no branch into it), c = small or not relocatable: hook through
      vtable slots and call sites (sites = comma list of hex addresses: V<slot addr> or B<bl site>)
"""
import sys, os, re, struct, ctypes, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nova
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB

OUT = sys.argv[1]
I = nova.idx(); data = nova.load_lib()
here = os.path.dirname(os.path.abspath(__file__))
LIBREL = os.path.join(here, '..', 'hooktest', 'librel.so')
src = os.path.join(here, '..', 'native', 'hook_reloc.h')
os.makedirs(os.path.dirname(LIBREL), exist_ok=True)
open(os.path.join(os.path.dirname(LIBREL), 'rel.c'), 'w').write('#include "%s"\nint reloc(const unsigned char*c,unsigned pc,unsigned char*o,unsigned opc,int need,int*cons){return nh_relocate(c,pc,o,opc,need,cons);}\n' % src)
os.system('gcc -O1 -shared -fPIC -o %s %s' % (LIBREL, os.path.join(os.path.dirname(LIBREL), 'rel.c')))
R = ctypes.CDLL(LIBREL)
md = Cs(CS_ARCH_ARM, CS_MODE_THUMB); md.skipdata = True

# scopes that are classes: have a vtable/typeinfo, or appear as a parameter type
classes = set()
for n in I['byname']:
    d = nova.D(n)
    if d.startswith('vtable for ') or d.startswith('typeinfo for '): classes.add(d.split(' for ', 1)[1])
types_seen = collections.Counter()
for a, (sz, n, th) in I['funcs'].items():
    d = nova.D(n)
    if '(' in d:
        for t in re.findall(r'([A-Za-z_][\w:]*)(?: const)?[*&]', d[d.index('('):]): types_seen[t] += 1
classes |= {t for t, c in types_seen.items() if c >= 1}

def split_params(s):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch in '<(': depth += 1
        elif ch in '>)': depth -= 1
        if ch == ',' and depth == 0: out.append(cur.strip()); cur = ''
        else: cur += ch
    if cur.strip(): out.append(cur.strip())
    return out

INTS = {'bool', 'char', 'signed char', 'unsigned char', 'short', 'unsigned short', 'int', 'unsigned int', 'long', 'unsigned long', 'wchar_t', 'unsigned'}
def code_of(t):
    t = t.strip()
    if 'basic_string' in t: return 'S'
    if t in ('char const*', 'char*', 'unsigned char const*'): return 'c'
    if t.endswith('*') or t.endswith('&') or '(*)' in t or t.endswith('const*') or t.endswith('&&'): return 'p'
    t2 = t.replace('const', '').strip()
    if t2 == 'float': return 'f'
    if t2 == 'double': return 'd'
    if t2 in ('long long', 'unsigned long long'): return 'q'
    if t2 in INTS: return 'p'
    return 'v'

def scope_name(d):
    base = d.split('(')[0]
    depth, cut, i = 0, -1, 0
    while i < len(base):
        c = base[i]
        if c == '<': depth += 1
        elif c == '>': depth -= 1
        elif depth == 0 and base.startswith('::', i): cut = i; i += 1
        i += 1
    return (base[:cut], base[cut + 2:]) if cut > 0 else (None, base)

# branch targets inside functions (a branch into the patched prologue makes inline hooking unsafe)
def scan(a, sz):
    tgt, rets_f, rets_d = set(), False, False
    prev = []
    for i in md.disasm(data[a:a + sz], a):
        m, o = i.mnemonic, i.op_str
        mm = re.search(r'#(0x[0-9a-f]+)$', o)
        if mm and (m.startswith('b') or m.startswith('cb')) and m not in ('bic', 'bics', 'bfi', 'bfc'):
            tgt.add(int(mm.group(1), 16))
        if m == 'bx' and o == 'lr' or (m.startswith('pop') and 'pc' in o) or m == 'vpop':
            for p in prev[-4:]:
                if p[0] == 'vmov' and p[1].startswith('r0, s'): rets_f = True
                if p[0] == 'vmov' and p[1].startswith('r0, r1, d'): rets_d = True
        prev.append((m, o)); prev = prev[-6:]
    return tgt, rets_f, rets_d

# vtable slots and bl sites per function (for c mode)
import bisect
vslots = collections.defaultdict(list)
vt_bases_sorted = sorted(va for va, (sz, n) in I['objs'].items() if n.startswith('_ZTV'))
from elftools.elf.elffile import ELFFile
e = ELFFile(open(nova.LIB, 'rb'))
for va, (sz, n) in I['objs'].items():
    if not n.startswith('_ZTV'): continue
    o = nova.foff(e, va)
    if o is None: continue
    for k in range(2, sz // 4):
        slot = va + 4 * k
        g = I['got'].get(slot)
        v = struct.unpack_from('<I', data, o + 4 * k)[0]
        t = I['byname'].get(g) if isinstance(g, str) else ((g or v) & ~1)
        if t: vslots[t].append(slot)
blsites = collections.defaultdict(list)
for a, (sz, n, th) in I['funcs'].items():
    if not th or sz <= 0: continue
    for i in md.disasm(data[a:a + sz], a):
        if i.mnemonic == 'bl' and i.op_str.startswith('#'):
            t = int(i.op_str[1:], 16)
            if not (a <= t < a + sz): blsites[t].append(i.address)

alltargets = collections.Counter()
info = {}
for a, (sz, n, th) in I['funcs'].items():
    if not th or sz <= 0: continue
    tg, rf, rd = scan(a, sz)
    info[a] = (rf, rd)
    for t in tg: alltargets[t] += 1

# virtual overrides share the return type: group by (method name + params, slot index)
vt_of = {}
for va, (sz, n) in I['objs'].items():
    if not n.startswith('_ZTV'): continue
    for k, slot in enumerate(range(va + 8, va + sz, 4)):
        pass
grp = collections.defaultdict(set)
for t, slots in vslots.items():
    d = nova.D(I['funcs'][t][1]) if t in I['funcs'] else ''
    if not d: continue
    key0 = d.split('(')[0].split('::')[-1] + '(' + d.split('(', 1)[1] if '(' in d else d
    for sl in slots:
        # slot index inside its vtable
        for va in vt_bases_sorted[bisect.bisect_right(vt_bases_sorted, sl) - 1:bisect.bisect_right(vt_bases_sorted, sl)]:
            grp[(key0, (sl - va - 8) // 4)].add(t)
for key, fs in grp.items():
    r = [info.get(f, (False, False)) for f in fs]
    if any(x[1] for x in r): 
        for f in fs: info[f] = (False, True)
    elif any(x[0] for x in r):
        for f in fs: info[f] = (True, False)

rows = []; stats = collections.Counter()
for a, (sz, n, th) in sorted(I['funcs'].items()):
    d = nova.D(n)
    if not th: continue
    sc, nm = scope_name(d)
    params = d[d.index('(') + 1:d.rindex(')')] if '(' in d and ')' in d else ''
    ps = [] if params in ('', 'void') else split_params(params)
    member = n.startswith('_ZNK') or (sc is not None and (sc in classes or nm == sc.split('::')[-1] or nm.startswith('~')))
    args = ('s' if member else '') + ''.join(code_of(p) for p in ps)
    rf, rd = info.get(a, (False, False))
    ret = 'd' if rd else 'f' if rf else 'i'
    mode = 'c'
    need = 8 if a % 4 == 0 else 10
    if sz >= need:
        out = ctypes.create_string_buffer(256); cons = ctypes.c_int(0)
        r = R.reloc(ctypes.create_string_buffer(bytes(data[a:a + 64]), 64), a, out, 0x10000000, need, ctypes.byref(cons))
        if r > 0 and cons.value <= sz and not any(a < t < a + cons.value for t in range(a + 1, a + cons.value) if alltargets.get(t)):
            mode = 'i'
    sites = ''
    if mode == 'c':
        sites = ','.join(['V%x' % s for s in vslots.get(a, [])] + ['B%x' % s for s in blsites.get(a, [])])
    stats[mode] += 1
    if mode == 'c' and not sites: stats['c_no_sites'] += 1
    rows.append('\t'.join([d, n, '%x' % a, str(sz), args, ret, mode, sites]))
open(OUT, 'w').write('\n'.join(rows) + '\n')
print(stats, len(rows), 'rows', os.path.getsize(OUT), 'bytes')
