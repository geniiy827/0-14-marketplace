# find all Thumb BL/BLX call sites to given addresses in .text, report containing function
import sys, bisect
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
lib=sys.argv[1]; targets={int(x,16) for x in sys.argv[2:]}
f=open(lib,'rb'); elf=ELFFile(f); data=open(lib,'rb').read()
funcs=sorted((s['st_value']&~1, s['st_size'], s.name) for s in elf.get_section_by_name('.dynsym').iter_symbols() if s['st_info']['type']=='STT_FUNC' and s['st_value'])
starts=[x[0] for x in funcs]
md=Cs(CS_ARCH_ARM, CS_MODE_THUMB); md.skipdata=True
for a,sz,n in funcs:
    for ins in md.disasm(data[a:a+sz], a):  # vaddr==offset for .text here
        if ins.mnemonic in ('bl','blx','b.w','b') and ins.op_str.startswith('#'):
            t=int(ins.op_str[1:],16)
            if t in targets: print(hex(ins.address), ins.mnemonic, hex(t), n)
