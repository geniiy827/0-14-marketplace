"""Binary AndroidManifest (AXML) reader/writer.

parse(bytes) -> Element tree (namespaces kept), write(tree) -> bytes.
Attributes keep their typed value (type, data) and raw string, so a
round-trip of an unmodified manifest is semantically identical.
"""
import struct

ANDROID_NS = 'http://schemas.android.com/apk/res/android'
T_REF, T_STRING, T_INT_DEC, T_INT_HEX, T_BOOL = 0x01, 0x03, 0x10, 0x11, 0x12


class Attr:
    def __init__(self, name, ns=None, typ=T_STRING, data=None, raw=None, resid=0):
        self.name, self.ns, self.typ, self.data, self.raw, self.resid = name, ns, typ, data, raw, resid

    def __repr__(self):
        return 'Attr(%s=%r)' % (self.name, self.raw if self.typ == T_STRING else self.data)


class Element:
    def __init__(self, tag, attrs=None, children=None, ns=None):
        self.tag, self.ns = tag, ns
        self.attrs = attrs or []
        self.children = children or []

    def get(self, name):
        for a in self.attrs:
            if a.name == name:
                return a
        return None

    def find_all(self, tag):
        out = [self] if self.tag == tag else []
        for c in self.children:
            out += c.find_all(tag)
        return out


def _read_pool(buf, off):
    typ, hsize, size, count, scount, flags, sstart, _ = struct.unpack_from('<HHIIIIII', buf, off)
    utf8 = bool(flags & (1 << 8))
    offs = struct.unpack_from('<%dI' % count, buf, off + hsize)
    out = []
    for o in offs:
        p = off + sstart + o
        if utf8:
            n = buf[p]; p += 2 if n & 0x80 else 1
            n = buf[p]
            if n & 0x80:
                n = ((n & 0x7f) << 8) | buf[p + 1]; p += 2
            else:
                p += 1
            out.append(bytes(buf[p:p + n]).decode('utf-8'))
        else:
            n = struct.unpack_from('<H', buf, p)[0]; p += 2
            if n & 0x8000:
                n = ((n & 0x7fff) << 16) | struct.unpack_from('<H', buf, p)[0]; p += 2
            out.append(bytes(buf[p:p + 2 * n]).decode('utf-16le'))
    return out, off + size


def parse(buf):
    assert struct.unpack_from('<H', buf, 0)[0] == 0x0003
    strings, pos = _read_pool(buf, 8)
    resmap = []
    root, stack, nsmap = None, [], []
    S = lambda i: None if i == 0xffffffff else strings[i]
    while pos < len(buf):
        typ, hsize, size = struct.unpack_from('<HHI', buf, pos)
        body = pos + hsize
        if typ == 0x0180:
            resmap = list(struct.unpack_from('<%dI' % ((size - 8) // 4), buf, pos + 8))
        elif typ == 0x0100:
            prefix, uri = struct.unpack_from('<II', buf, body)
            nsmap.append((S(prefix), S(uri)))
        elif typ == 0x0102:
            ns, name, astart, asize, acount = struct.unpack_from('<IIHHH', buf, body)
            el = Element(S(name), ns=S(ns))
            for k in range(acount):
                p = body + astart + k * asize
                ans, aname, raw, _s, _r, dt, data = struct.unpack_from('<IIIHBBI', buf, p)
                el.attrs.append(Attr(S(aname), S(ans), dt, data, S(raw),
                                     resmap[aname] if aname < len(resmap) else 0))
            if stack:
                stack[-1].children.append(el)
            else:
                root = el
            stack.append(el)
        elif typ == 0x0103:
            stack.pop()
        pos += size
    # resolve string-typed values to text
    def fix(e):
        for a in e.attrs:
            if a.typ == T_STRING:
                a.raw = strings[a.data]
        for c in e.children:
            fix(c)
    fix(root)
    root.nsmap = nsmap
    return root


def write(root):
    # ---- collect strings: attribute names with resource ids first (resource map order)
    named = []      # (name, resid)
    others = []
    def add_other(s):
        if s is not None and s not in others:
            others.append(s)
    def walk(e):
        for a in e.attrs:
            if a.resid:
                if (a.name, a.resid) not in named:
                    named.append((a.name, a.resid))
            else:
                add_other(a.name)
            add_other(a.ns)
            if a.typ == T_STRING or a.raw is not None:
                add_other(a.raw)
        add_other(e.tag)
        add_other(e.ns)
        for c in e.children:
            walk(c)
    for prefix, uri in root.nsmap:
        add_other(prefix); add_other(uri)
    walk(root)
    named.sort(key=lambda x: x[1])
    pool = [n for n, _ in named] + [s for s in others if s not in [n for n, _ in named]]
    idx = {s: i for i, s in enumerate(pool)}
    I = lambda s: 0xffffffff if s is None else idx[s]

    # string pool (UTF-16)
    data = bytearray(); offs = []
    for s in pool:
        offs.append(len(data))
        enc = s.encode('utf-16le')
        n = len(enc) // 2
        assert n < 0x8000
        data += struct.pack('<H', n) + enc + b'\0\0'
    while len(data) % 4:
        data += b'\0'
    hsize = 28
    sstart = hsize + 4 * len(pool)
    sp = struct.pack('<HHIIIIII', 0x0001, hsize, sstart + len(data), len(pool), 0, 0, sstart, 0)
    sp += struct.pack('<%dI' % len(pool), *offs) + bytes(data)

    rm = struct.pack('<HHI', 0x0180, 8, 8 + 4 * len(named)) + struct.pack('<%dI' % len(named), *[r for _, r in named])

    chunks = bytearray()
    line = [1]
    def node(typ, ext):
        chunks.extend(struct.pack('<HHIII', typ, 16, 16 + len(ext), line[0], 0xffffffff) + ext)
        line[0] += 1
    for prefix, uri in root.nsmap:
        node(0x0100, struct.pack('<II', I(prefix), I(uri)))
    def emit(e):
        attrs = sorted(e.attrs, key=lambda a: (a.resid == 0, a.resid))
        ext = struct.pack('<IIHHHHHH', I(e.ns), I(e.tag), 20, 20, len(attrs), 0, 0, 0)
        for a in attrs:
            if a.typ == T_STRING:
                d = idx[a.raw]; raw = idx[a.raw]
            else:
                d = a.data & 0xffffffff; raw = I(a.raw)
            ext += struct.pack('<IIIHBBI', I(a.ns), idx[a.name], raw, 8, 0, a.typ, d)
        node(0x0102, ext)
        for c in e.children:
            emit(c)
        node(0x0103, struct.pack('<II', I(e.ns), I(e.tag)))
    emit(root)
    for prefix, uri in reversed(root.nsmap):
        node(0x0101, struct.pack('<II', I(prefix), I(uri)))

    body = sp + rm + bytes(chunks)
    return struct.pack('<HHI', 0x0003, 8, 8 + len(body)) + body


# ---- helpers to build new elements
def a_str(name, value, resid):
    return Attr(name, ANDROID_NS, T_STRING, None, value, resid)

def a_int(name, value, resid, typ=T_INT_DEC):
    return Attr(name, ANDROID_NS, typ, value, None, resid)

def a_bool(name, value, resid):
    return Attr(name, ANDROID_NS, T_BOOL, 0xffffffff if value else 0, None, resid)

def a_ref(name, value, resid):
    return Attr(name, ANDROID_NS, T_REF, value, None, resid)


def dump(e, ind=0):
    def v(a):
        if a.typ == T_STRING: return repr(a.raw)
        if a.typ == T_BOOL: return 'true' if a.data else 'false'
        if a.typ == T_REF: return '@%08x' % a.data
        return hex(a.data) if a.typ == T_INT_HEX else str(a.data)
    s = '  ' * ind + '<%s %s>\n' % (e.tag, ' '.join('%s=%s' % (a.name, v(a)) for a in e.attrs))
    for c in e.children:
        s += dump(c, ind + 1)
    return s
