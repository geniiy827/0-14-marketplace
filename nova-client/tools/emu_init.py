"""Run the .init_array of libminecraftpe.so in unicorn (PLT calls stubbed) and dump the resulting
data+bss image, so static values computed at load time (colours, tables) can be read.
usage: emu_init.py LIB OUT.bin  -> OUT.bin = memory 0x6eb000..0x7d0000"""
import sys, struct
from unicorn import *
from unicorn.arm_const import *
lib, out = sys.argv[1], sys.argv[2]
d = open(lib, 'rb').read()
mu = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
mu.mem_map(0, 0x900000)
mu.mem_write(0, d[:0x6e9b40])
mu.mem_write(0x6eb160, d[0x6ea160:0x6ea160 + 0x70618])
# relocations: ABS32 / GLOB_DAT -> symbol value (+addend in place); undefined -> a dummy zeroed area
import subprocess
DUMMY = 0x860000
for line in subprocess.run(['readelf', '-rW', lib], capture_output=True, text=True).stdout.splitlines():
    f = line.split()
    if len(f) >= 4 and f[2] in ('R_ARM_ABS32', 'R_ARM_GLOB_DAT'):
        off = int(f[0], 16); val = int(f[3], 16) if len(f) >= 5 else 0
        if val == 0: val = DUMMY
        cur = struct.unpack('<I', bytes(mu.mem_read(off, 4)))[0] if f[2] == 'R_ARM_ABS32' else 0
        mu.mem_write(off, struct.pack('<I', (val + cur) & 0xffffffff))
STACK = 0x880000
mu.reg_write(UC_ARM_REG_SP, STACK)
mu.reg_write(UC_ARM_REG_C1_C0_2, mu.reg_read(UC_ARM_REG_C1_C0_2) | (0xf << 20))
mu.reg_write(UC_ARM_REG_FPEXC, 0x40000000)
# heap for operator new / malloc
mu.mem_map(0x10000000, 0x4000000)
heap = [0x10000000]
PLT_LO, PLT_HI = 0x29d000, 0x2a0000
def hook(uc, addr, size, ud):
    if PLT_LO <= addr < PLT_HI:
        r0 = uc.reg_read(UC_ARM_REG_R0)
        # treat as operator new / malloc: return fresh zeroed memory
        p = heap[0]; heap[0] += (min(max(r0, 16), 1 << 20) + 15) & ~15
        uc.reg_write(UC_ARM_REG_R0, p)
        lr = uc.reg_read(UC_ARM_REG_LR)
        uc.reg_write(UC_ARM_REG_PC, lr)
mu.hook_add(UC_HOOK_CODE, hook, begin=PLT_LO, end=PLT_HI)
ia = [struct.unpack('<I', d[0x6ea5d8 + 4 * i:0x6ea5dc + 4 * i])[0] for i in range(22)]
for f in ia:
    if f == 0: continue
    mu.reg_write(UC_ARM_REG_SP, STACK)
    mu.reg_write(UC_ARM_REG_LR, 0x8fff00 | 1)
    try:
        mu.emu_start(f, 0x8fff00, count=5000000)
    except UcError as e:
        print('init', hex(f), 'stopped:', e, hex(mu.reg_read(UC_ARM_REG_PC)))
open(out, 'wb').write(bytes(mu.mem_read(0x6eb000, 0x7d0000 - 0x6eb000)))
print('ok')
