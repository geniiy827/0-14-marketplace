"""Build the engine block atlas with the "new" textures: original 0.14.3 terrain-atlas.tga as the base,
every tile that has a new version (work/assets_new/textures_new/blocks, same layout) pasted over it.
usage: new_atlas.py BASE_TGA LAYOUT_JSON NEW_DIR OUT_TGA"""
import json, os, sys
from PIL import Image

base, layout, new, out = sys.argv[1:5]
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
im.save(out)
print('tiles replaced:', n)
