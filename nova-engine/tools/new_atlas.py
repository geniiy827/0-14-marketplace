"""Build the engine block atlas with the "new" textures: original 0.14.3 terrain-atlas.tga as the base,
every tile that has a new version (work/assets_new/textures_new/blocks, same layout) pasted over it.
Engine-only tiles (EXTRA) go into empty atlas cells and get their own terrain.meta entries.
usage: new_atlas.py BASE_TGA BASE_META LAYOUT_JSON NEW_DIR JAVA_BLOCK_DIR OUT_TGA OUT_META"""
import json, os, sys
from PIL import Image

base, meta_in, layout, new, java, out, meta_out = sys.argv[1:8]
EXTRA = [('nova_grass_side', 'grass_block_side'), ('nova_grass_side_overlay', 'grass_block_side_overlay')]
im = Image.open(base).convert('RGBA')
L = json.load(open(layout))
n = 0
for e in L['entries']:
    name = e['name']
    for i, u in enumerate(e['uvs']):
        cands = [os.path.join(new, name, '%d.png' % i)] + ([os.path.join(new, name + '.png')] if len(e['uvs']) == 1 else [])
        p = next((c for c in cands if os.path.exists(c)), None)
        if not p:
            continue
        x0, y0, x1, y1 = [int(round(v)) for v in u[:4]]
        t = Image.open(p).convert('RGBA')
        if t.size != (x1 - x0, y1 - y0):
            if t.size[0] == x1 - x0 and t.size[1] > y1 - y0:
                t = t.crop((0, 0, x1 - x0, y1 - y0))          # animated strip: first frames
            else:
                t = t.resize((x1 - x0, y1 - y0), Image.NEAREST)
        im.paste(t, (x0, y0))
        n += 1
meta = json.load(open(meta_in))
used = set()
for e in meta:
    for u in e['uvs']:
        x0, y0, x1, y1 = [int(v) for v in u[:4]]
        for x in range(x0 // 16, (x1 + 15) // 16):
            for y in range(y0 // 16, (y1 + 15) // 16):
                used.add((x, y))
W, H = im.size
free = [(x, y) for y in range(H // 16) for x in range(W // 16) if (x, y) not in used
        and im.crop((x * 16, y * 16, x * 16 + 16, y * 16 + 16)).getchannel('A').getextrema()[1] == 0]
for (name, jname), (cx, cy) in zip(EXTRA, free):
    t = Image.open(os.path.join(java, jname + '.png')).convert('RGBA').crop((0, 0, 16, 16))
    im.paste(t, (cx * 16, cy * 16))
    meta.append({'name': name, 'uvs': [[cx * 16, cy * 16, cx * 16 + 16, cy * 16 + 16, W, H]]})
json.dump(meta, open(meta_out, 'w'))
im.save(out)
print('tiles replaced:', n)
