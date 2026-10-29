#!/usr/bin/env python3
"""nova - one tool for NOVA client RE + build. Index of libminecraftpe.so is cached on first use.

  nova s REGEX            symbols (demangled) matching REGEX
  nova d SYM|ADDR [N]     disassemble function (or N insns from ADDR), calls/strings annotated
  nova dg SYM|ADDR RE     disassemble, only lines matching RE (+ calls)
  nova callers SYM|ADDR   who calls it (bl/blx/b.w)
  nova refs ADDR|SYM      code that references a data address (pc-relative and GOT-relative)
  nova vt CLASS           vtable: slot index (from vt+8, as patch_slot) -> function
  nova at ADDR            which function contains ADDR
  nova str TEXT           find C string, print address and code refs
  nova crash [FILE]       symbolize a NOVA-crash.txt (default: newest in uploads)
  nova build              build APK quietly (version from VERSION file), prints result
"""
import sys, os, re, struct, pickle, bisect, subprocess, glob, signal
signal.signal(signal.SIGPIPE, signal.SIG_DFL)

LIB = '/home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so'
W = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CACHE = os.path.join(W, '.cache', 'mc.idx')

def load_lib():
    return open(LIB, 'rb').read()

def build_index():
    from elftools.elf.elffile import ELFFile
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    data = load_lib(); e = ELFFile(open(LIB, 'rb'))
    ds = e.get_section_by_name('.dynsym')
    funcs, objs = {}, {}
    for s in ds.iter_symbols():
        if not s['st_value'] or not s.name: continue
        if s['st_info']['type'] == 'STT_FUNC': funcs[s['st_value'] & ~1] = (s['st_size'], s.name, s['st_value'] & 1)
        elif s['st_info']['type'] == 'STT_OBJECT': objs[s['st_value']] = (s['st_size'], s.name)
    names = sorted(set(n for _, n, _ in funcs.values()) | set(n for _, n in objs.values()))
    dem = subprocess.run(['c++filt'], input='\n'.join(names), capture_output=True, text=True).stdout.split('\n')
    dmap = dict(zip(names, dem))
    plt = {}
    relplt, pltsec = e.get_section_by_name('.rel.plt'), e.get_section_by_name('.plt')
    if relplt and pltsec:
        st = e.get_section(relplt['sh_link']); base = pltsec['sh_addr'] + 20
        for i, r in enumerate(relplt.iter_relocations()): plt[base + i * 12] = st.get_symbol(r['r_info_sym']).name
    got = {}   # GOT slot -> symbol name / target
    for sec in e.iter_sections():
        if sec.name.startswith('.rel.dyn'):
            for r in sec.iter_relocations():
                if r['r_info_sym']: got[r['r_offset']] = ds.get_symbol(r['r_info_sym']).name
                else:
                    fo = foff(e, r['r_offset'])
                    if fo is not None: got[r['r_offset']] = struct.unpack_from('<I', data, fo)[0]
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB); md.skipdata = True
    callers, refs = {}, {}
    for a, (sz, n, th) in funcs.items():
        if not th or sz <= 0: continue
        lits, pcv, consts = {}, set(), set()
        for i in md.disasm(data[a:a + sz], a):
            m, o = i.mnemonic, i.op_str
            if m in ('bl', 'blx', 'b.w', 'b') and o.startswith('#'):
                t = int(o[1:], 16)
                if not (a <= t < a + sz): callers.setdefault(t, set()).add(a)
            elif m.startswith('ldr') and '[pc, #' in o:
                try:
                    la = ((i.address + 4) & ~3) + int(o.split('#')[1].rstrip(']'), 0)
                    v = struct.unpack_from('<I', data, la)[0]
                    lits[o.split(',')[0]] = v; consts.add(v)
                except Exception: pass
            elif m == 'add' and o.endswith(', pc'):
                r = o.split(',')[0]
                if r in lits: pcv.add((lits[r] + i.address + 4) & 0xffffffff)
        for p in pcv:
            refs.setdefault(p, set()).add(a)
            for c in consts:
                v = (p + c) & 0xffffffff
                if v != p and 0x700000 <= v < 0x7e0000: refs.setdefault(v, set()).add(a)
    idx = dict(funcs=funcs, objs=objs, dem=dmap, plt=plt, got=got, callers=callers, refs=refs)
    os.makedirs(os.path.dirname(CACHE), exist_ok=True)
    pickle.dump(idx, open(CACHE, 'wb'))
    return idx

def foff(e, addr):
    for seg in e.iter_segments():
        if seg['p_type'] == 'PT_LOAD' and seg['p_vaddr'] <= addr < seg['p_vaddr'] + seg['p_filesz']:
            return addr - seg['p_vaddr'] + seg['p_offset']

IDX = None
def idx():
    global IDX
    if IDX is None:
        IDX = pickle.load(open(CACHE, 'rb')) if os.path.exists(CACHE) else build_index()
        IDX['starts'] = sorted(IDX['funcs'])
        IDX['byname'] = {n: a for a, (_, n, _) in IDX['funcs'].items()}
        IDX['byname'].update({n: a for a, (_, n) in IDX['objs'].items()})
    return IDX

def D(n): return idx()['dem'].get(n, n)

def func_at(a):
    I = idx(); s = I['starts']; k = bisect.bisect_right(s, a) - 1
    if k >= 0:
        f = s[k]; sz = I['funcs'][f][0]
        if f <= a < f + max(sz, 2): return f
    return None

def name(a, off=True):
    I = idx()
    if a in I['funcs']: return D(I['funcs'][a][1])
    if a in I['objs']: return D(I['objs'][a][1])
    if a in I['plt']: return D(I['plt'][a]) + '@plt'
    f = func_at(a)
    if f is not None and off: return '%s+%#x' % (D(I['funcs'][f][1]), a - f)
    return None

def resolve(x):
    """hex address, mangled name, or regex on demangled names (must be unique-ish: first match)"""
    I = idx()
    if re.fullmatch(r'(0x)?[0-9a-fA-F]{5,8}', x): return int(x, 16) & ~1
    if x in I['byname']: return I['byname'][x]
    ex = [a for n, a in I['byname'].items() if D(n) == x or D(n).split('(')[0] == x]
    if len(ex) >= 1:
        if len(ex) > 1: print('# %d overloads, using first; add args to pick' % len(ex))
        return ex[0]
    rx = re.compile(x)
    hits = [(a, n) for n, a in I['byname'].items() if rx.search(D(n)) or rx.search(n)]
    if not hits: sys.exit('no symbol: ' + x)
    if len(hits) > 1:
        ex = [h for h in hits if D(h[1]).split('(')[0].endswith(x) or D(h[1]).startswith(x + '(')]
        hits = ex or hits
        if len(hits) > 1: print('# %d matches, using first; others: %s' % (len(hits), '; '.join(D(n) for _, n in hits[1:6])))
    return hits[0][0]

def cstr(data, a):
    if not (0x100000 <= a < 0x700000): return None
    e = data.find(b'\0', a, a + 80)
    if e <= a: return None
    raw = data[a:e]
    if all(32 <= c < 127 for c in raw) and len(raw) >= 3: return '"%s"' % raw.decode()
    return None

def disasm(start, count=None, grep=None):
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    I = idx(); data = load_lib()
    f = start if start in I['funcs'] else func_at(start)
    if count is None and f is not None:
        sz = I['funcs'][f][0]; a0, a1 = f, f + sz
        print('=== %s @%#x size %d' % (D(I['funcs'][f][1]), f, sz))
    else:
        a0, a1 = start, start + 4 * (count or 40)
        print('=== %s' % (name(start) or hex(start)))
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB); md.skipdata = True
    rx = re.compile(grep) if grep else None
    lits = {}
    for i in md.disasm(data[a0:a1], a0):
        if count and i.address >= a1: break
        m, o = i.mnemonic, i.op_str; note = ''
        if m in ('bl', 'blx', 'b.w', 'b') and o.startswith('#'):
            t = int(o[1:], 16); n = name(t)
            if n and not (a0 <= t < a1): note = n
        elif m.startswith('ldr') and '[pc, #' in o:
            try:
                la = ((i.address + 4) & ~3) + int(o.split('#')[1].rstrip(']'), 0)
                v = struct.unpack_from('<I', data, la)[0]; lits[o.split(',')[0]] = v; note = '=%#x' % v
            except Exception: pass
        elif m == 'add' and o.endswith(', pc'):
            r = o.split(',')[0]
            if r in lits:
                v = (lits[r] + i.address + 4) & 0xffffffff
                g = I['got'].get(v)
                note = '-> %#x %s' % (v, (D(g) if isinstance(g, str) else name(g) if g else '') or name(v) or cstr(data, v) or '')
        line = '  %08x: %-8s %s%s' % (i.address, m, o, ('  ; ' + note) if note else '')
        if rx is None or rx.search(line) or (note and m.startswith('b')): print(line)

def main():
    a = sys.argv[1:]
    if not a: print(__doc__); return
    c = a[0]
    if c == 'index': build_index(); print('ok'); return
    if c == 's':
        I = idx(); rx = re.compile(a[1]); k = 0
        for addr, (sz, n, _) in sorted(I['funcs'].items()):
            if rx.search(D(n)) or rx.search(n):
                print('%x %4d %s   [%s]' % (addr, sz, D(n), n) if '-m' in a else '%x %4d %s' % (addr, sz, D(n))); k += 1
                if k >= 60: print('...'); break
        for addr, (sz, n) in sorted(I['objs'].items()):
            if rx.search(D(n)) or rx.search(n): print('%x obj %d %s' % (addr, sz, D(n)))
    elif c == 'd': disasm(resolve(a[1]), int(a[2]) if len(a) > 2 else None)
    elif c == 'dg': disasm(resolve(a[1]), None, a[2])
    elif c == 'callers':
        t = resolve(a[1]); I = idx()
        for f in sorted(I['callers'].get(t, ())): print('%x %s' % (f, D(I['funcs'][f][1])))
    elif c == 'refs':
        t = resolve(a[1]); I = idx()
        gots = [g for g, v in I['got'].items() if v == t or (isinstance(v, str) and I['byname'].get(v) == t)]
        for x in [t] + gots:
            for f in sorted(I['refs'].get(x, ())): print('%x %s%s' % (f, D(I['funcs'][f][1]), '  (via GOT %#x)' % x if x != t else ''))
    elif c == 'vt':
        I = idx(); data = load_lib(); n = '_ZTV%d%s' % (len(a[1]), a[1])
        if n not in I['byname']: sys.exit('no vtable ' + n)
        va = I['byname'][n]; sz = I['objs'][va][0]
        from elftools.elf.elffile import ELFFile
        e = ELFFile(open(LIB, 'rb')); o = foff(e, va)
        for k in range(2, sz // 4):
            slot = va + 4 * k; g = I['got'].get(slot)
            v = struct.unpack_from('<I', data, o + 4 * k)[0]
            nm = D(g) if isinstance(g, str) else name((g or v) & ~1, False) or hex(g or v)
            print('%3d %s' % (k - 2, nm))
    elif c == 'at':
        t = int(a[1], 16); print(name(t & ~1) or '?')
    elif c == 'str':
        data = load_lib(); s = a[1].encode(); p = 0
        while True:
            p = data.find(s, p)
            if p < 0: break
            st = data.rfind(b'\0', 0, p) + 1
            print('%x %s' % (st, cstr(data, st)))
            for f in sorted(idx()['refs'].get(st, ())): print('   ref %x %s' % (f, D(idx()['funcs'][f][1])))
            p += 1
    elif c == 'crash':
        fn = a[1] if len(a) > 1 else max(glob.glob('/mnt/user-data/uploads/*crash*') or ['-'], key=lambda x: os.path.getmtime(x) if x != '-' else 0)
        txt = sys.stdin.read() if fn == '-' else open(fn, errors='replace').read()
        for line in txt.splitlines():
            m = re.search(r'(?:rel |^\s+)(0x[0-9a-f]{8})\s*$', line)
            if m and 'nova+' not in line:
                v = int(m.group(1), 16); print(line, ' ', name(v & ~1) or '')
            else: print(line)
    elif c == 'build':
        r = subprocess.run(['sh', os.path.join(W, 'tools/build.sh')], capture_output=True, text=True)
        log = r.stdout + r.stderr; open(os.path.join(W, 'out/build.log'), 'w').write(log)
        errs = [l for l in log.splitlines() if re.search(r'error|Error|Traceback|FAIL|not empty|AssertionError', l) and 'errors: 0' not in l and 'pre-existing' not in l]
        print('\n'.join(errs[-15:]))
        print('\n'.join(l for l in log.splitlines() if l.startswith('APK ') or 'SIGNED-OK' in l) or 'BUILD FAILED (out/build.log)')
    else: print(__doc__)

if __name__ == '__main__': main()
