"""Binary patches for libminecraftpe.so (MCPE 0.14.3 armeabi-v7a).

Every patch checks the ORIGINAL instruction text at the address (via capstone)
before writing, so a wrong library version is refused instead of corrupted.
usage: so_patch.py IN.so OUT.so
"""
import sys
from capstone import Cs, CS_ARCH_ARM, CS_MODE_THUMB
from keystone import Ks, KS_ARCH_ARM, KS_MODE_THUMB

ks = Ks(KS_ARCH_ARM, KS_MODE_THUMB)
cs = Cs(CS_ARCH_ARM, CS_MODE_THUMB)

def asm(code, addr):
    enc, _ = ks.asm(code, addr)
    return bytes(enc)

def dis(buf, addr, n):
    return ['%s %s' % (i.mnemonic, i.op_str) for i in cs.disasm(bytes(buf[addr:addr + n]), addr)]

# (name, address, expected original instructions, new asm, size of region)
F = lambda v: '#0x%x' % v
PATCHES = []

# 1) ScreenChooser gets UIScreenType 0 ("desktop") -> JSON screens for
#    inventory/crafting/containers/world list.  blx r3 -> movs r0,#0
PATCHES.append(('ui_screen_type_desktop', 0x353e4a, ['blx r3'], 'movs r0, #0', 2))

# 2) Pause screen: always use the JSON PauseScreenController (was trial only)
PATCHES.append(('pause_json', 0x3f92e2, ['cbnz r0, #0x3f9332'], 'b #0x3f9332', 2))
PATCHES.append(('pause_prev_json', 0x3f93fc, ['cbnz r0, #0x3f944e'], 'b #0x3f944e', 2))

# 3) DeathScreen::setupPositions: Java-style vertical stack of wide buttons
#    w=[this+0xc] h=[this+0x10]; respawn=[this+0x8c], menu=[this+0x94]
#    Button: +8 x, +0xc y, +0x10 width, +0x14 height
DEATH_ORIG = 60
death_new = '''
    push {r4, r5, r6, r7}
    ldr r3, [r0, #0xc]
    ldr r4, [r0, #0x10]
    ldr.w r1, [r0, #0x8c]
    ldr.w r2, [r0, #0x94]
    movs r5, #200
    cmp r3, #240
    bge skip
    sub.w r5, r3, #40
skip:
    subs r6, r3, r5
    asrs r6, r6, #1
    asrs r4, r4, #2
    adds r4, #60
    str r6, [r1, #8]
    str r4, [r1, #0xc]
    str r5, [r1, #0x10]
    ldr r7, [r1, #0x14]
    adds r7, #4
    adds r7, r7, r4
    str r6, [r2, #8]
    str r7, [r2, #0xc]
    str r5, [r2, #0x10]
    pop {r4, r5, r6, r7}
    bx lr
'''
PATCHES.append(('death_layout', 0x2cb684, ['ldr r3, [r0, #0xc]', 'push {r4, r5}'], death_new, DEATH_ORIG))

# 4) Death overlay gradient: red -> black (top) to dark teal (bottom)
GRAD = [  # (address of movw, reg, original lo, address of movt, original hi, new float bits)
    (0x34878a, 'lr', 0xa0a1, 0x348790, 0x3ea0, 0x00000000),  # top r   0.31 -> 0.0
    (0x34879a, 'ip', 0x8081, 0x3487a2, 0x3f00, 0x3d23d70a),  # bottom r 0.50 -> 0.04
    (0x34879e, 'lr', 0xc0c2, 0x3487a6, 0x3ec0, 0x3eb33333),  # top a   0.38 -> 0.35
    (0x3487aa, 'r7', 0xc0c2, 0x3487b2, 0x3e40, 0x3e99999a),  # bottom g,b 0.19 -> 0.30
    (0x3487b6, 'lr', 0xa0a1, 0x3487be, 0x3f20, 0x3f19999a),  # bottom a 0.63 -> 0.60
]
for lo_at, reg, lo, hi_at, hi, new in GRAD:
    PATCHES.append(('death_grad_lo', lo_at, ['movw %s, #0x%x' % (reg, lo)], 'movw %s, #0x%x' % (reg, new & 0xffff), 4))
    PATCHES.append(('death_grad_hi', hi_at, ['movt %s, #0x%x' % (reg, hi)], 'movt %s, #0x%x' % (reg, new >> 16), 4))


def main(inp, out):
    buf = bytearray(open(inp, 'rb').read())
    for name, addr, expect, code, size in PATCHES:
        have = dis(buf, addr, 8 if size < 8 else size)
        assert have[:len(expect)] == expect, '%s @%#x: expected %r, found %r' % (name, addr, expect, have[:len(expect)])
        enc = asm(code, addr)
        assert len(enc) <= size, '%s: %d bytes > %d' % (name, len(enc), size)
        enc += asm('nop', 0) * ((size - len(enc)) // 2)
        buf[addr:addr + size] = enc
        print('%-24s @%#x  %s  ->  %s' % (name, addr, '; '.join(expect), '; '.join(dis(buf, addr, len(enc))[:3]) + (' ...' if size > 8 else '')))
    open(out, 'wb').write(buf)


if __name__ == '__main__':
    main(*sys.argv[1:3])
