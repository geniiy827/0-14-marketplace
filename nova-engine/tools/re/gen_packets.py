#!/usr/bin/env python3
"""packets.md from re014/classes: id (wire = getId + 0x8e), and the serializer call order of write()/read()."""
import os, re, sys, glob
R = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/work/re014'
def funcs(path):
    cur, out = None, {}
    for l in open(path):
        if l.startswith(';;; ') and not l.startswith(';;; @') and not l.startswith(';;; =') and '(' in l and not l.startswith(';;; called') and not l.startswith(';;; vtable'):
            cur = l[4:].strip(); out[cur] = []
        elif cur and l.startswith('  '): out[cur].append(l.rstrip())
    return out
SHORT = [(r'BitStream::WriteBits', 'bits'), (r'BitStream::ReadBits', 'bits'), (r'PacketUtil::(\w+)', None), (r'BitStream::(\w+)', None), (r'(\w+::)?(\w+)\(', None)]
def seq(lines):
    r, bits = [], None
    for l in lines:
        m = re.search(r'movs\s+r2, #(0x[0-9a-f]+|\d+)', l)
        if m: bits = int(m.group(1), 0)
        m = re.search(r'\bblx?\s+#0x[0-9a-f]+\s+; (.*)', l)
        if not m:
            if re.search(r'\bb(eq|ne|lt|gt|le|ge|hs|lo|hi|ls|mi|pl)?(\.w)?\s+#.*; \+', l) or re.search(r'cbn?z', l): r.append('?')
            continue
        n = m.group(1).split('(')[0]
        if n.endswith('WriteBits') or n.endswith('ReadBits'): r.append('%s%s' % ('u' if bits else 'bits', bits or '')); bits = None
        else: r.append(n.replace('RakNet::BitStream::', 'BS.').replace('PacketUtil::', 'PU.'))
    s = []
    for x in r:
        if x == '?' and s and s[-1] == '?': continue
        s.append(x)
    noise = lambda x: x in ('?', 'BS.IsNetworkOrderInternal', 'BS.ReverseBytes', '__stack_chk_fail@plt') or re.fullmatch(r'u\d+|bits', x)
    cut = len(s)
    for k, x in enumerate(s):
        if x == 'BS.ReverseBytes' and all(noise(y) for y in s[k:]): cut = k; break
    s = [x for x in s[:cut] if x not in ('BS.IsNetworkOrderInternal', 'BS.ReverseBytes', '__stack_chk_fail@plt') and not x.startswith('_Z') and '@plt' not in x and not x.startswith('void std::')]
    out = []
    for x in s:
        if x == '?' and out and out[-1] == '?': continue
        out.append(x)
    while out and out[-1] == '?': out.pop()
    return ' '.join(out).replace('?', '·')
rows = []
for p in glob.glob(os.path.join(R, 'classes', '*Packet.s')):
    F = funcs(p); c = os.path.basename(p)[:-2]
    gid = [v for k, v in F.items() if k.startswith(c + '::getId')]
    pid = None
    if gid:
        m = re.search(r'movs\s+r0, #(0x[0-9a-f]+|\d+)', '\n'.join(gid[0]))
        if m: pid = int(m.group(1), 0)
    w = [v for k, v in F.items() if k.startswith(c + '::write(')]
    rd = [v for k, v in F.items() if k.startswith(c + '::read(')]
    rows.append((pid if pid is not None else 999, c, seq(w[0]) if w else '', seq(rd[0]) if rd else ''))
o = ['# MCPE 0.14.3 packets (protocol 70)', '', 'wire id = getId + 0x8e. `u8` = WriteBits 8 bits (u16/u32/u64 are byte-swapped big-endian helpers), `·` = a branch (optional / per-type field), BS = RakNet::BitStream, PU = PacketUtil.', '']
for pid, c, w, r in sorted(rows):
    o.append('## %s  getId=%s wire=%s' % (c, pid if pid != 999 else '?', hex(pid + 0x8e) if pid != 999 else '?'))
    o.append('- write: ' + (w or '-')); o.append('- read:  ' + (r or '-')); o.append('')
open(os.path.join(R, 'packets.md'), 'w').write('\n'.join(o))
print(len(rows))
