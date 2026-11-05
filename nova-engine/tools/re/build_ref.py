#!/usr/bin/env python3
"""build_ref - turn libminecraftpe.so (0.14.3, armeabi-v7a) into a browsable reference tree.

  python3 build_ref.py [OUT]      (default OUT = /home/claude/work/re014)

OUT/
  INDEX.md                 how to look things up
  classes/<Class>.s        every method of the class, annotated: calls by name, pc-literals as int+float,
                           strings, GOT symbols, data objects; header has size, vtable slot(s), callers
  free/<x>.s               free functions grouped by first letter
  vtables/<Class>.txt      slot -> function (slot index as used by patch_slot, from vt+8)
  classes.tsv              class, methods, bytes, file
  symbols.tsv              addr, size, kind, demangled, mangled
  strings.tsv              addr, text, referencing functions
  calls.tsv                caller -> callee edges (names)
Output is generated from the user's own APK on this machine; it is NOT committed anywhere.
"""
import sys, os, re, struct, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nova
from nova import idx, D, name, cstr, load_lib, foff, LIB

OUT = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/work/re014'

def fl(v):
    f = struct.unpack('<f', struct.pack('<I', v))[0]
    if f != f or f == 0 or not (1e-6 < abs(f) < 1e7): return None
    s = ('%.6g' % f)
    return s if len(s.replace('-', '').replace('.', '').lstrip('0')) <= 7 else None

def cls_of(dn):
    base = dn.split('(')[0]
    # strip templates for splitting
    depth, cut, i = 0, -1, 0
    while i < len(base):
        c = base[i]
        if c == '<': depth += 1
        elif c == '>': depth -= 1
        elif depth == 0 and base.startswith('::', i): cut = i; i += 1
        i += 1
    if cut <= 0: return None
    c = base[:cut]
    for p in ('non-virtual thunk to ', 'virtual thunk to ', 'vtable for ', 'typeinfo for '):
        if c.startswith(p): c = c[len(p):]
    return c

def fname(c):
    c = re.sub(r'<.*', '_T', c)
    return re.sub(r'[^A-Za-z0-9_.]+', '_', c.replace('::', '.'))[:120] or '_'

def main():
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
    from elftools.elf.elffile import ELFFile
    I = idx(); data = load_lib(); e = ELFFile(open(LIB, 'rb'))
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB); md.skipdata = True
    for d in ('classes', 'free', 'vtables'): os.makedirs(os.path.join(OUT, d), exist_ok=True)

    used = {}
    def uniq(c):
        b = fname(c); k = used.setdefault(b, [])
        if c not in k: k.append(c)
        return b if k.index(c) == 0 else '%s~%d' % (b, k.index(c))
    # ---- vtables first: function -> [(class, slot)]
    vslot = collections.defaultdict(list)
    for va, (sz, n) in I['objs'].items():
        if not n.startswith('_ZTV'): continue
        c = D(n).replace('vtable for ', '')
        o = foff(e, va)
        if o is None: continue
        lines = []
        for k in range(2, sz // 4):
            slot = va + 4 * k; g = I['got'].get(slot)
            v = struct.unpack_from('<I', data, o + 4 * k)[0]
            t = None
            if isinstance(g, str): nm = D(g); t = I['byname'].get(g)
            else:
                t = (g or v) & ~1; nm = name(t, False) or hex(g or v)
            lines.append('%3d  %s' % (k - 2, nm))
            if t: vslot[t].append('%s[%d]' % (c, k - 2))
        open(os.path.join(OUT, 'vtables', uniq(c) + '.txt'), 'w').write('vtable for %s @%#x (%d slots)\n' % (c, va, len(lines)) + '\n'.join(lines) + '\n')

    groups = collections.defaultdict(list)
    for a, (sz, n, th) in I['funcs'].items():
        dn = D(n); c = cls_of(dn)
        groups[c if c else '#' + (re.sub(r'[^a-z]', '_', dn[:1].lower()) or '_')].append(a)

    strings = collections.defaultdict(set); edges = []
    fields = collections.defaultdict(dict)
    ctab = []
    for g, addrs in sorted(groups.items()):
        free = g.startswith('#')
        path = os.path.join(OUT, 'free' if free else 'classes', (g[1:] if free else uniq(g)) + '.s')
        out = []
        tot = 0
        for a in sorted(addrs):
            sz, n, th = I['funcs'][a]; dn = D(n); tot += sz
            out.append('\n;;; ' + '=' * 70)
            out.append(';;; %s' % dn)
            out.append(';;; @%#x size %d  [%s]' % (a, sz, n))
            if vslot.get(a): out.append(';;; vtable: ' + ', '.join(vslot[a][:8]))
            cl = sorted(I['callers'].get(a, ()))
            if cl: out.append(';;; called by: ' + '; '.join(D(I['funcs'][f][1]).split('(')[0] for f in cl[:12]) + (' ...(+%d)' % (len(cl) - 12) if len(cl) > 12 else ''))
            if not th or sz <= 0: out.append(';;; (arm or empty)'); continue
            lits = {}
            this = {'r0': 0} if (g and not free and '::' in dn and not dn.startswith(('non-virtual', 'virtual'))) else {}
            fn_short = dn.split('(')[0].split('::')[-1]
            for i in md.disasm(data[a:a + sz], a):
                m, o = i.mnemonic, i.op_str; note = ''
                if this:
                    fm = re.match(r'(\w+)(?:, \w+)?, \[(\w+)(?:, #(-?0x[0-9a-f]+|-?\d+))?\]!?$', o)
                    if fm and fm.group(2) in this and re.match(r'v?(ldr|str)', m):
                        off = this[fm.group(2)] + (int(fm.group(3), 0) if fm.group(3) else 0)
                        k = 'w' if m.startswith(('str', 'vstr')) else 'r'
                        fields[g].setdefault(off, {}).setdefault(k + re.sub(r'^v?(ldr|str)', '', m.replace('.w', '')).replace('.32', ''), set()).add(fn_short)
                        note = 'this+%#x' % off
                    ops = [x.strip() for x in o.split(',')]
                    if m.startswith(('bl', 'blx')):
                        for r in ('r0', 'r1', 'r2', 'r3', 'r12', 'ip', 'lr'): this.pop(r, None)
                    elif m in ('mov', 'movs') and len(ops) == 2 and ops[1] in this: this[ops[0]] = this[ops[1]]
                    elif m in ('add', 'add.w', 'adds', 'addw') and len(ops) == 3 and ops[1] in this and ops[2].startswith('#'):
                        try: this[ops[0]] = this[ops[1]] + int(ops[2][1:], 0)
                        except ValueError: this.pop(ops[0], None)
                    elif m.startswith(('pop', 'ldm')):
                        for r in re.findall(r'r\d+|sl|fp|ip|lr', o.split('{')[-1] if '{' in o else ''): this.pop(r, None)
                    elif ops and not m.startswith(('str', 'cmp', 'cmn', 'tst', 'teq', 'b', 'cb', 'push', 'vstr', 'vcmp', 'it', 'vpush', 'stm', 'pld')): this.pop(ops[0], None)
                    if '!' in o or re.search(r'\], #', o):
                        bm = re.search(r'\[(\w+)', o)
                        if bm: this.pop(bm.group(1), None)
                if m in ('bl', 'blx', 'b.w', 'b', 'cbz', 'cbnz') or m.startswith('b'):
                    mm = re.search(r'#(0x[0-9a-f]+)$', o)
                    if mm:
                        t = int(mm.group(1), 16)
                        if not (a <= t < a + sz):
                            nn = name(t)
                            if nn: note = nn; edges.append((dn.split('(')[0], nn.split('(')[0]))
                        else: note = '+%#x' % (t - a)
                elif '[pc, #' in o and (m.startswith('ldr') or m.startswith('vldr')):
                    try:
                        la = ((i.address + 4) & ~3) + int(o.split('#')[1].rstrip(']'), 0)
                        v = struct.unpack_from('<I', data, la)[0]
                        if m.startswith('vldr'):
                            if o.startswith('d'):
                                dv = struct.unpack_from('<d', data, la)[0]; note = '=%r (double)' % dv
                            else: note = '=%s f' % (fl(v) or repr(struct.unpack('<f', struct.pack('<I', v))[0]))
                        else:
                            lits[o.split(',')[0]] = v; f = fl(v)
                            note = '=%#x (%d)%s' % (v, v if v < 0x80000000 else v - (1 << 32), ('  %sf' % f) if f and v > 0x10000 else '')
                    except Exception: pass
                elif m == 'add' and o.endswith(', pc'):
                    r = o.split(',')[0]
                    if r in lits:
                        v = (lits[r] + i.address + 4) & 0xffffffff
                        gg = I['got'].get(v)
                        s = cstr(data, v)
                        if s: strings[v].add(dn.split('(')[0])
                        note = '-> %#x %s' % (v, (D(gg) if isinstance(gg, str) else name(gg) if gg else '') or name(v) or s or '')
                elif m in ('movw', 'mov.w', 'movs', 'mov') and '#' in o:
                    try:
                        v = int(o.split('#')[1], 0)
                        if v > 9: note = '%d' % v
                        if v >= 0x10000 and fl(v): note += '  %sf' % fl(v)
                    except Exception: pass
                elif m == 'movt':
                    try:
                        v = int(o.split('#')[1], 0) << 16; f = fl(v)
                        note = 'hi=%#x%s' % (v, ('  %sf' % f) if f else '')
                    except Exception: pass
                out.append('  %06x  %-7s %s%s' % (i.address, m, o, ('   ; ' + note) if note else ''))
        hdr = ';;; %s  -  %d functions, %d bytes  (libminecraftpe.so 0.14.3)\n' % (g, len(addrs), tot)
        open(path, 'a' if free else 'w').write(hdr + '\n'.join(out) + '\n')
        if not free: ctab.append((g, len(addrs), tot, os.path.relpath(path, OUT)))

    os.makedirs(os.path.join(OUT, 'fields'), exist_ok=True)
    for c, fd in fields.items():
        if not fd: continue
        with open(os.path.join(OUT, 'fields', uniq(c) + '.txt'), 'w') as f:
            f.write('fields of %s seen as [this, #off] in its own methods (r = read, w = write; suffix b/h/sb/sh/d = byte/half/signed/double-word, f32 vldr)\n' % c)
            for off in sorted(fd):
                f.write('%#6x  %s\n' % (off, '  '.join('%s: %s' % (k or 'r', ', '.join(sorted(v)[:10])) for k, v in sorted(fd[off].items()))))
    with open(os.path.join(OUT, 'classes.tsv'), 'w') as f:
        f.write('class\tmethods\tbytes\tfile\n')
        for r in sorted(ctab): f.write('%s\t%d\t%d\t%s\n' % r)
    with open(os.path.join(OUT, 'symbols.tsv'), 'w') as f:
        for a, (sz, n, th) in sorted(I['funcs'].items()): f.write('%x\t%d\tfunc\t%s\t%s\n' % (a, sz, D(n), n))
        for a, (sz, n) in sorted(I['objs'].items()): f.write('%x\t%d\tobj\t%s\t%s\n' % (a, sz, D(n), n))
    for v, fs in I['refs'].items():
        if cstr(data, v) and data[v - 1] == 0:
            for fa in fs: strings[v].add(D(I['funcs'][fa][1]).split('(')[0])
    strings = {k: v for k, v in strings.items() if data[k - 1] == 0}
    with open(os.path.join(OUT, 'strings.tsv'), 'w') as f:
        for a in sorted(strings): f.write('%x\t%s\t%s\n' % (a, cstr(data, a), '; '.join(sorted(strings[a])[:10])))
    with open(os.path.join(OUT, 'calls.tsv'), 'w') as f:
        for x, y in sorted(set(edges)): f.write('%s\t%s\n' % (x, y))
    print('classes', len(ctab), 'strings', len(strings), 'edges', len(set(edges)))

if __name__ == '__main__': main()
