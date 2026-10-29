"""bbmodel (Minecraft Title Generator) -> intro mesh for the loading screen.
out: assets/nova/intro/title_mesh.bin + title_tex.png
mesh: 'NVM1', u16 groups; per group: u16 nameLen,name, f32 cx,cy,cz (centre), u16 quads,
      per quad 4 x (f32 x,y,z,u,v)   (u,v in 0..1 of the texture)
usage: gen_intro_mesh.py MODEL.bbmodel OUTDIR"""
import json, base64, io, struct, sys, os
from PIL import Image

def load(path):
    m = json.load(open(path))
    tex = m['textures'][0]
    img = Image.open(io.BytesIO(base64.b64decode(tex['source'].split(',', 1)[1]))).convert('RGBA')
    UW, UH = tex.get('uv_width', 16), tex.get('uv_height', 16)
    els = {e['uuid']: e for e in m['elements']}
    groups = {g['uuid']: g for g in m.get('groups', [])}
    out = []          # (name, [quads])
    def collect(node, name):
        res = []
        for c in node:
            if isinstance(c, str): res.append(els[c])
            else:
                g = groups.get(c['uuid'], {})
                res += collect(c['children'], g.get('name', name))
        return res
    def walk(node):
        for c in node:
            if isinstance(c, str): continue
            g = groups.get(c['uuid'], {})
            kids = c['children']
            if all(isinstance(k, str) for k in kids):
                out.append((g.get('name', '?'), [els[k] for k in kids]))
            else:
                walk(kids)
    walk(m['outliner'])
    groups_q = []
    for name, cubes in out:
        quads = []
        for e in cubes:
            # raw from/to: an inverted cube (from > to) is inside-out in Blockbench,
            # its faces look inwards (the title outline). Back faces are culled.
            f, t = e['from'], e['to']
            x0, y0, z0 = f; x1, y1, z1 = t
            # corners: top-left, top-right, bottom-right, bottom-left as seen from outside
            C = {
                'north': [(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)],
                'south': [(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)],
                'east':  [(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)],
                'west':  [(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)],
                'up':    [(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)],
                'down':  [(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)],
            }
            for side, fc in e['faces'].items():
                if fc.get('texture') is None: continue
                u1, v1, u2, v2 = fc['uv']
                uv = [(u1, v1), (u2, v1), (u2, v2), (u1, v2)]
                quads.append([(p[0], p[1], p[2], u / UW, v / UH) for p, (u, v) in zip(C[side], uv)])
        groups_q.append((name, quads))
    return img, groups_q

def main():
    src, outdir = sys.argv[1], sys.argv[2]
    img, gq = load(src)
    os.makedirs(outdir, exist_ok=True)
    # used area only, scaled for a phone GPU
    W = 2048
    img.resize((W, W * img.size[1] // img.size[0]), Image.LANCZOS).save(os.path.join(outdir, 'title_tex.png'))
    b = bytearray(b'NVM1') + struct.pack('<H', len(gq))
    for name, quads in gq:
        xs = [v[0] for q in quads for v in q]; ys = [v[1] for q in quads for v in q]; zs = [v[2] for q in quads for v in q]
        c = ((min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2)
        nb = name.encode()
        b += struct.pack('<H', len(nb)) + nb + struct.pack('<3f', *c) + struct.pack('<H', len(quads))
        for q in quads:
            for v in q: b += struct.pack('<5f', *v)
    open(os.path.join(outdir, 'title_mesh.bin'), 'wb').write(bytes(b))
    print('intro mesh:', [(n, len(q)) for n, q in gq])

if __name__ == '__main__':
    main()
