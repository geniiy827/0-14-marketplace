"""Minimal in-place patcher for a binary AndroidManifest.xml (AXML).

- replaces one string in the string pool with another of the SAME length
  (so no offsets change), used for the package name;
- sets an integer attribute value (e.g. uses-sdk targetSdkVersion).

usage: axml_patch.py IN OUT --string OLD NEW --int-attr ELEMENT ATTR VALUE
"""
import struct, sys, argparse

RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102
UTF8_FLAG = 1 << 8


def parse_pool(buf, off):
    typ, hsize, size, count, scount, flags, sstart, _ = struct.unpack_from('<HHIIIIII', buf, off)
    assert typ == RES_STRING_POOL
    utf8 = bool(flags & UTF8_FLAG)
    offs = struct.unpack_from('<%dI' % count, buf, off + hsize)
    strings = []  # (text, absolute byte offset of chars, byte length)
    base = off + sstart
    for o in offs:
        p = base + o
        if utf8:
            n = buf[p]; p += 2 if n & 0x80 else 1          # utf16 length
            n = buf[p]
            if n & 0x80:
                n = ((n & 0x7f) << 8) | buf[p + 1]; p += 2
            else:
                p += 1
            strings.append((bytes(buf[p:p + n]).decode('utf-8'), p, n, utf8))
        else:
            n = struct.unpack_from('<H', buf, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', buf, p)[0]; p += 2
            strings.append((bytes(buf[p:p + 2 * n]).decode('utf-16le'), p, 2 * n, utf8))
    return strings, off + size


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('inp'); ap.add_argument('out')
    ap.add_argument('--string', nargs=2, action='append', default=[])
    ap.add_argument('--int-attr', nargs=3, action='append', default=[])
    a = ap.parse_args()
    buf = bytearray(open(a.inp, 'rb').read())
    assert struct.unpack_from('<H', buf, 0)[0] == 0x0003, 'not AXML'
    strings, pos = parse_pool(buf, 8)
    texts = [s[0] for s in strings]

    for old, new in a.string:
        assert len(old) == len(new), 'replacement must keep length'
        hits = [i for i, t in enumerate(texts) if t == old]
        assert len(hits) == 1, 'string %r found %d times' % (old, len(hits))
        t, p, n, utf8 = strings[hits[0]]
        enc = new.encode('utf-8' if utf8 else 'utf-16le')
        assert len(enc) == n
        buf[p:p + n] = enc
        print('string #%d: %r -> %r' % (hits[0], old, new))

    targets = {(e, at): int(v) for e, at, v in a.int_attr}
    done = set()
    # walk chunks after the string pool / resource map
    while pos < len(buf):
        typ, hsize, size = struct.unpack_from('<HHI', buf, pos)
        if typ == RES_XML_START_ELEMENT:
            ext = pos + hsize
            ns, name, astart, asize, acount = struct.unpack_from('<iiHHH', buf, ext)
            el = texts[name]
            for k in range(acount):
                ap_ = ext + astart + k * asize
                ans, aname, raw, vsize, _r, dtype, data = struct.unpack_from('<iiiHBBI', buf, ap_)
                key = (el, texts[aname])
                if key in targets:
                    assert dtype == 0x10, 'attribute is not an int (type %#x)' % dtype
                    struct.pack_into('<I', buf, ap_ + 16, targets[key])
                    print('%s/%s: %d -> %d' % (el, key[1], data, targets[key]))
                    done.add(key)
        pos += size
    missing = set(targets) - done
    assert not missing, 'attributes not found: %r' % missing
    open(a.out, 'wb').write(buf)


if __name__ == '__main__':
    main()
