import sys,struct
from elftools.elf.elffile import ELFFile
f=open('/home/claude/apk/x/lib/armeabi-v7a/libminecraftpe.so','rb'); e=ELFFile(f)
def off(va):
    for s in e.iter_segments():
        if s['p_type']=='PT_LOAD' and s['p_vaddr']<=va<s['p_vaddr']+s['p_filesz']:
            return va-s['p_vaddr']+s['p_offset']
for a in sys.argv[1:]:
    n=1
    if ':' in a: a,n=a.split(':'); n=int(n)
    va=int(a,16); f.seek(off(va)); d=f.read(4*n)
    for i in range(n):
        w=d[4*i:4*i+4]; print(hex(va+4*i), struct.unpack('<f',w)[0], hex(struct.unpack('<I',w)[0]))
