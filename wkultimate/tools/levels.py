"""Wall Kickers: WJScenarios.dat = gzip( scenarios section + 'levels' (recipe) section ).

Scenario = one building chunk (walls, spikes, spike balls, ...), parsed fully by wkfmt.
Level recipe = which chunks a tower level is built from, with weights and the
level-number range where the recipe is allowed.  We parse the recipe header
fields we understand and keep everything else as raw bytes.
"""
import gzip, re, struct
from . import wkfmt

class Recipe:
    def __init__(s, name, rid, hdr_off, list_off, entries, fields):
        s.name, s.id, s.hdr_off, s.list_off = name, rid, hdr_off, list_off
        s.entries = entries          # list of [scenario_name, weight]
        s.fields = fields            # dict or None (min,max,intro,curve,height)

class Data:
    def __init__(s, raw):
        s.scn, s.rest = wkfmt.parse(raw)
        s.byid = {x['id']: x for x in s.scn}
        s.byname = {x['name']: x for x in s.scn}
        s._parse_recipes()

    @classmethod
    def load(cls, path):
        return cls(gzip.decompress(open(path, 'rb').read()))

    # ---------------- recipes
    def _lists(self):
        rest, out, o = self.rest, [], 0
        while o + 4 <= len(rest):
            c = struct.unpack_from('<I', rest, o)[0]
            if 0 < c < 300 and o + 4 + 12 * c <= len(rest) and all(
                    rest[o + 4 + 12 * k:o + 12 + 12 * k] in self.byid for k in range(c)):
                out.append((o, c)); o += 4 + 12 * c
            else:
                o += 1
        return out

    def _parse_recipes(self):
        rest = self.rest
        starts = []
        for m in re.finditer(rb'([\x03-\x30])\x00\x00\x00([A-Za-z][A-Za-z0-9_]+)', rest):
            n = m.group(2)
            if len(n) == m.group(1)[0] and m.start() >= 8 and not n.isupper() and not n.startswith(b'WJ'):
                starts.append((m.start() - 8, n.decode()))
        self.recipes = []
        for o, c in self._lists():
            st, name = [x for x in starts if x[0] < o][-1]
            entries = []
            for k in range(c):
                sid = rest[o + 4 + 12 * k:o + 12 + 12 * k]
                w = struct.unpack_from('<f', rest, o + 12 + 12 * k)[0]
                entries.append([self.byid[sid]['name'], w])
            fields = None
            h = st + 8 + 4 + len(name)
            try:
                mn, mx, intro, l0 = struct.unpack_from('<4i', rest, h)
                w0, = struct.unpack_from('<f', rest, h + 16)
                l1, = struct.unpack_from('<i', rest, h + 20)
                w1, = struct.unpack_from('<f', rest, h + 24)
                npre, = struct.unpack_from('<I', rest, h + 28)
                p = h + 32 + 8 * npre
                slen, = struct.unpack_from('<I', rest, p)   # optional class name string
                p += 4 + slen
                height, x = struct.unpack_from('<fI', rest, p)
                if p + 8 == o and npre < 20 and slen < 64:
                    fields = dict(min=mn, max=mx, intro=intro, curve=[(l0, w0), (l1, w1)],
                                  height=height, off=h, height_off=p)
            except struct.error:
                pass
            self.recipes.append(Recipe(name, rest[st:st + 8], h, o, entries, fields))

    # ---------------- write back
    def build(self):
        rest = bytearray(self.rest)
        for r in sorted(self.recipes, key=lambda r: -r.list_off):
            f = r.fields
            oldc = struct.unpack_from('<I', rest, r.list_off)[0]
            new = struct.pack('<I', len(r.entries)) + b''.join(
                self.byname[n]['id'] + struct.pack('<f', w) for n, w in r.entries)
            rest[r.list_off:r.list_off + 4 + 12 * oldc] = new
            if f:
                (l0, w0), (l1, w1) = f['curve']
                struct.pack_into('<4ifif', rest, f['off'], f['min'], f['max'], f['intro'], l0, w0, l1, w1)
                struct.pack_into('<f', rest, f['height_off'], f['height'])
        raw = wkfmt.write(self.scn, bytes(rest))
        chk = Data(raw)            # must parse back
        assert len(chk.scn) == len(self.scn) and len(chk.recipes) == len(self.recipes)
        return raw

    def save(self, path):
        raw = self.build()
        with gzip.GzipFile(path, 'wb', mtime=0) as f:
            f.write(raw)
        return raw
