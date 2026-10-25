"""Disassemble exported functions of libminecraftpe.so (ARM/Thumb) with symbol names.
usage: dis.py LIB REGEX [--demangle]
"""
import sys, re, subprocess, struct
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB, CS_MODE_ARM

lib, pat = sys.argv[1], sys.argv[2]
f = open(lib, 'rb'); elf = ELFFile(f); data = open(lib, 'rb').read()
syms = {}
byaddr = {}
for s in elf.get_section_by_name('.dynsym').iter_symbols():
    if s['st_info']['type'] == 'STT_FUNC' and s['st_value']:
        syms[s.name] = (s['st_value'], s['st_size'])
        byaddr.setdefault(s['st_value'] & ~1, s.name)

def dem(n):
    try: return subprocess.run(['c++filt', n], capture_output=True, text=True).stdout.strip()
    except Exception: return n

# PLT: map plt stub addresses to imported names via .rel.plt
plt = {}
relplt = elf.get_section_by_name('.rel.plt')
pltsec = elf.get_section_by_name('.plt')
if relplt and pltsec:
    symtab = elf.get_section(relplt['sh_link'])
    base = pltsec['sh_addr'] + 20
    for i, r in enumerate(relplt.iter_relocations()):
        plt[base + i * 12] = symtab.get_symbol(r['r_info_sym']).name

def off(addr):
    for seg in elf.iter_segments():
        if seg['p_type'] == 'PT_LOAD' and seg['p_vaddr'] <= addr < seg['p_vaddr'] + seg['p_filesz']:
            return addr - seg['p_vaddr'] + seg['p_offset']

def name_of(a):
    a &= ~1
    if a in byaddr: return dem(byaddr[a])
    if a in plt: return dem(plt[a]) + '@plt'
    return None

def cstr(addr):
    o = off(addr)
    if o is None: return None
    e = data.find(b'\0', o, o + 80)
    if e < 0: return None
    raw = data[o:e]
    try:
        t = raw.decode('ascii')
    except Exception:
        return None
    return t if t and all(32 <= ord(ch) < 127 for ch in t) else None

for n, (a, sz) in sorted(syms.items(), key=lambda x: x[1][0]):
    if not re.search(pat, n): continue
    thumb = a & 1; start = a & ~1
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB if thumb else CS_MODE_ARM); md.detail = False
    print('\n=== %s\n    %s  @%#x size %d %s' % (dem(n), n, start, sz, 'thumb' if thumb else 'arm'))
    code = data[off(start):off(start) + sz]
    lits = {}
    for ins in md.disasm(code, start):
        extra = ''
        mm2 = re.match(r'(r\d+|ip|lr), pc$', ins.op_str)
        if ins.mnemonic == 'add' and mm2 and mm2.group(1) in lits:
            st = cstr(lits[mm2.group(1)] + ins.address + 4)
            if st: extra = '  ; "%s"' % st
        m = re.match(r'#0x([0-9a-f]+)', ins.op_str)
        if ins.mnemonic.startswith(('bl', 'b.', 'b', 'cb')) and m:
            t = name_of(int(m.group(1), 16))
            if t: extra = '  ; ' + t
        if ins.mnemonic.startswith('ldr') and '[pc' in ins.op_str:
            mm = re.search(r'#(-?0x[0-9a-f]+|-?\d+)\]', ins.op_str)
            imm = int(mm.group(1), 0) if mm else 0
            lit = ((ins.address + (4 if thumb else 8)) & ~3) + imm
            o = off(lit)
            if o is not None:
                v = struct.unpack_from('<I', data, o)[0]
                extra = '  ; =%#x' % v
                lits[ins.op_str.split(',')[0]] = v
        print('  %08x: %-8s %s%s' % (ins.address, ins.mnemonic, ins.op_str, extra))
