"""Small native tweaks for libKumoGame.so (arm64-v8a only; other ABIs keep vanilla behaviour).

WJTowerBuilder::ChooseNext caps how many electric chunks may follow each other:
2 (levels <=10), 3 (11..20), 4 (21+).  Raised to ELECTRIC_CAP below.
Usage: native_tweaks.py in.so out.so
"""
import sys
ELECTRIC_CAP = (5, 8)   # (early cap; mid = early+1), late cap

def main(src, dst):
    import keystone
    ks = keystone.Ks(keystone.KS_ARCH_ARM64, keystone.KS_MODE_LITTLE_ENDIAN)
    asm = lambda code, a: bytes(ks.asm(code, a)[0])
    B = bytearray(open(src, 'rb').read())
    for addr, old, new in [(0x5e8738, 'mov w8,#2', f'mov w8,#{ELECTRIC_CAP[0]}'),
                           (0x5e873c, 'mov w9,#4', f'mov w9,#{ELECTRIC_CAP[1]}')]:
        assert B[addr:addr + 4] == asm(old, addr), ('unexpected code at', hex(addr))
        B[addr:addr + 4] = asm(new, addr)
    open(dst, 'wb').write(B)
    print('native tweaks: electric chain cap ->', ELECTRIC_CAP)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
