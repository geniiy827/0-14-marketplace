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
# Font::_makeTextObject: its bool argument draws "§x" formatting codes (half transparent)
# instead of hiding them, and it is set for every string the font treats as unicode
# (any Cyrillic letter, ...) -> colored nicknames show their codes. Always hide them.
PATCHES.append(('font_hide_codes', 0x32baa0, ['ldrb.w r6, [sp, #0xc8]'], 'movs r6, #0', 4))
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

# 5) NOVA hook gate for ModelPart::load (no runtime code patching needed).
#    load: push.w {r4-r8,lr}  ->  b.w CAVE
#    CAVE (zero padding after .text, still mapped R-X):
#        hook = *SLOT; if (hook) bx hook        (libnova sets SLOT)
#    CAVE_ORIG: push.w {r4-r8,lr}; b.w load+4   (libnova calls this as "original")
#    SLOT sits in the zero tail of the last RW page, past .bss.
CAVE0 = 0x6e9b80          # first cave; each gate takes CAVE_SIZE bytes
SLOT0 = 0x7cff00          # first slot (zero tail of the last RW page); 4 bytes per gate
CAVE_SIZE = 0x30
ORIG_OFF = 0x12           # entry that runs the original function
# (name, function, symbol) - the function's first 4 bytes move into the cave;
# a pc-relative literal load among them is rewritten to load the same constant
GATES = [
    ('modelpart_load', 0x3e0d18, '_ZN9ModelPart4loadERK11GeometryPtrRKSs'),
    ('modelpart_render', 0x38962c, '_ZN9ModelPart6renderERK5Modelf'),
    ('opt_getbool', 0x38f2d4, '_ZN7Options15getBooleanValueEPKNS_6OptionE'),
    ('opt_setbool', 0x38fa8c, '_ZN7Options3setEPKNS_6OptionEb'),
    ('opt_toggle', 0x3e78c4, '_ZN7Options6toggleEPKNS_6OptionEi'),
    ('opt_getint', 0x38f8c8, '_ZN7Options11getIntValueEPKNS_6OptionE'),
    ('opt_setint', 0x38f984, '_ZN7Options3setEPKNS_6OptionEi'),
    ('opt_getvalues', 0x3d20dc, '_ZN7Options9getValuesEPKNS_6OptionE'),
    ('opt_getstring', 0x38f6c0, '_ZN7Options14getStringValueEPKNS_6OptionE'),
    ('opt_setstring', 0x3e7f38, '_ZN7Options3setEPKNS_6OptionERKSs'),
    ('nametags', 0x46b068, '_ZN13LevelRenderer14renderNameTagsEf'),
    ('init_creative', 0x555104, '_ZN4Item17initCreativeItemsEv'),
    ('tess_in_world', 0x3ec3c8, '_ZN16BlockTessellator17tessellateInWorldER5BlockRK8BlockPoshb'),
    ('tess_append', 0x42c2e0, '_ZN16BlockTessellator22appendTessellatedBlockERK9FullBlock'),
    ('gui_block', 0x42cf58, '_ZN16BlockTessellator14renderGuiBlockERK9FullBlockRKN3mce10TexturePtrEff'),
    ('mob_travel', 0x4ca4d8, '_ZN3Mob6travelEff'),
    ('entity_move', 0x4e60d8, '_ZN6Entity4moveERK4Vec3'),
    ('mob_jump', 0x4cfc7c, '_ZN3Mob14jumpFromGroundEv'),
    ('setup_pos', 0x43a064, '_ZN11MobRenderer13setupPositionER6EntityRK4Vec3R6Matrix'),
    ('chat_send', 0x314db8, '_ZN10ChatScreen15sendChatMessageEv'),
    ('can_interact', 0x386204, '_ZNK21MinecraftInputHandler11canInteractEv'),
    ('interact_text', 0x386268, '_ZNK21MinecraftInputHandler15getInteractTextEv'),
    ('interact_press', 0x343fb4, '_ZN15MinecraftClient25handleInteractButtonPressEv'),
    ('render_item', 0x485d1c, '_ZN18ItemInHandRenderer10renderItemER6EntityRK12ItemInstancef'),
    ('render_obj', 0x444450, '_ZN18ItemInHandRenderer13_renderObjectERK10RenderCallb'),
]
import re as _re
_GATE_MOVED = {}


def _moved(buf, func):
    """relocated copies of the instructions in func[0:4] for cave position"""
    out, n = [], 0
    for ins in cs.disasm(bytes(buf[func:func + 8]), func):
        if n >= 4:
            break
        out.append(ins)
        n += ins.size
    return out


def gate_patches(buf):
    for gname, func, sym in GATES:
        _GATE_MOVED[func] = _moved(buf, func)
    for gi, (gname, func, sym) in enumerate(GATES):
        n = sum(i.size for i in _GATE_MOVED[func])
        PATCHES.append((gname + '_gate', func, None, 'b.w #%#x' % (CAVE0 + gi * CAVE_SIZE), n))


def write_cave(buf):
    import struct
    for gi, (gname, func, sym) in enumerate(GATES):
        cave, slot = CAVE0 + gi * CAVE_SIZE, SLOT0 + 4 * gi
        assert cave % 4 == 0
        hdr = asm('''
            ldr.w ip, [pc, #0x20]
            add ip, pc
            ldr.w ip, [ip]
            cmp.w ip, #0
            it ne
            bxne ip
        ''', cave)
        assert len(hdr) == ORIG_OFF
        moved = _GATE_MOVED[func]
        code = bytearray(hdr)
        lits = []
        for ins in moved:
            m = _re.match(r'(r\d+|ip|lr), \[pc, #(0x[0-9a-f]+|\d+)\]$', ins.op_str)
            if ins.mnemonic in ('ldr', 'ldr.w') and m:
                imm = int(m.group(2), 0)
                src = (ins.address + 4) // 4 * 4 + imm
                lits.append(bytes(ins_lit for ins_lit in buf[src:src + 4]))
                laddr = cave + 0x28 + 4 * (len(lits) - 1)
                at = cave + len(code)
                code += asm('ldr.w %s, [pc, #%d]' % (m.group(1), laddr - (at + 4) // 4 * 4), at)
            else:
                assert 'pc' not in ins.op_str and not ins.mnemonic.startswith('b') and not ins.mnemonic.startswith('cb'), \
                    '%s: cannot move %s %s' % (gname, ins.mnemonic, ins.op_str)
                code += ins.bytes
        code += asm('b.w #%#x' % (func + sum(i.size for i in moved)), cave + len(code))
        assert len(code) <= 0x24 and len(lits) <= 2, gname
        assert set(buf[cave:cave + CAVE_SIZE]) == {0}, 'cave not empty'
        buf[cave:cave + len(code)] = code
        buf[cave + 0x24:cave + 0x28] = ((slot - (cave + 8)) & 0xffffffff).to_bytes(4, 'little')
        for k, l in enumerate(lits):
            buf[cave + 0x28 + 4 * k:cave + 0x2c + 4 * k] = l
        print('cave %-17s @%#x slot %#x: %s' % (gname, cave, slot, ' | '.join(dis(buf, cave + ORIG_OFF, len(code) - ORIG_OFF))))
    # grow the R-X LOAD segment over the caves so no loader can treat them as padding
    e_phoff, = struct.unpack_from('<I', buf, 0x1c)
    e_phentsize, e_phnum = struct.unpack_from('<HH', buf, 0x2a)
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type, p_off, p_vaddr, p_paddr, p_filesz, p_memsz, p_flags = struct.unpack_from('<7I', buf, o)
        if p_type == 1 and p_flags & 1:
            new_end = CAVE0 + CAVE_SIZE * len(GATES)
            assert p_off == 0 and p_vaddr == 0 and p_filesz == p_memsz <= CAVE0 and new_end <= 0x6ea160   # up to the RW segment's file offset: 31 gates
            struct.pack_into('<II', buf, o + 16, new_end, new_end)
            print('text segment: filesz %#x -> %#x' % (p_filesz, new_end))
    # header for libnova
    import os
    h = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'native', 'gates.h')
    with open(h, 'w') as f:
        f.write('/* generated by tools/so_patch.py */\n')
        for gi, (gname, func, sym) in enumerate(GATES):
            n = gname.upper()
            cave = CAVE0 + gi * CAVE_SIZE
            f.write('#define G_%s_FUNC %#x\n#define G_%s_ORIG %#x\n#define G_%s_SLOT %#x\n#define G_%s_SYM "%s"\n'
                    % (n, func, n, cave + ORIG_OFF, n, SLOT0 + 4 * gi, n, sym))


def main(inp, out):
    buf = bytearray(open(inp, 'rb').read())
    gate_patches(buf)
    for name, addr, expect, code, size in PATCHES:
        have = dis(buf, addr, 8 if size < 8 else size)
        if expect is None:
            expect = ['%s %s' % (i.mnemonic, i.op_str) for i in _GATE_MOVED[addr]]
        assert have[:len(expect)] == expect, '%s @%#x: expected %r, found %r' % (name, addr, expect, have[:len(expect)])
        enc = asm(code, addr)
        assert len(enc) <= size, '%s: %d bytes > %d' % (name, len(enc), size)
        enc += asm('nop', 0) * ((size - len(enc)) // 2)
        buf[addr:addr + size] = enc
        print('%-24s @%#x  %s  ->  %s' % (name, addr, '; '.join(expect), '; '.join(dis(buf, addr, len(enc))[:3]) + (' ...' if size > 8 else '')))
    write_cave(buf)
    open(out, 'wb').write(buf)


if __name__ == '__main__':
    main(*sys.argv[1:3])
