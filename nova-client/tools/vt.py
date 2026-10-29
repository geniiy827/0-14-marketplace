import sys,struct,subprocess
from elftools.elf.elffile import ELFFile
L='/home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so'
f=open(L,'rb'); e=ELFFile(f); d=open(L,'rb').read()
syms={}; byaddr={}
for s in e.get_section_by_name('.dynsym').iter_symbols():
    if s['st_value']: syms[s.name]=(s['st_value'],s['st_size']); byaddr.setdefault(s['st_value'],s.name)
# relocations into data: addr -> symbol value (ABS32) or addend (RELATIVE)
rel={}
dynsym=e.get_section_by_name('.dynsym')
for sec in e.iter_sections():
    if sec.name.startswith('.rel.dyn'):
        for r in sec.iter_relocations():
            t=r['r_info_type']; off=r['r_offset']
            if t==23: rel[off]=('R',None)
            elif t in (2,21): rel[off]=('S',dynsym.get_symbol(r['r_info_sym']).name)
def foff(a):
    for seg in e.iter_segments():
        if seg['p_type']=='PT_LOAD' and seg['p_vaddr']<=a<seg['p_vaddr']+seg['p_filesz']: return a-seg['p_vaddr']+seg['p_offset']
def entry(a):
    k=rel.get(a)
    if k and k[0]=='S': return k[1]
    v=struct.unpack('<I',d[foff(a):foff(a)+4])[0]
    return byaddr.get(v, hex(v))
def dem(n): return subprocess.run(['c++filt',n],capture_output=True,text=True).stdout.strip()
for name in sys.argv[1:]:
    a,sz=syms[name]
    print('==',dem(name),hex(a),sz)
    for i in range(2,sz//4):
        print(' ',i-2,dem(str(entry(a+4*i))))
