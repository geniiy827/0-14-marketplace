# Glyph widths of the Minecraft font (default8.png for ASCII, glyph_XX.png for the rest), measured the way the game
# does: the rightmost column with a pixel + 1. Output: "codepoint width" lines (width in 8px-font units).
import sys
from PIL import Image
root = sys.argv[1]
def widths(img, cells, cell):
    im = Image.open(img).convert('RGBA')
    px = im.load()
    out = {}
    for i in range(cells * cells):
        cx, cy = (i % cells) * cell, (i // cells) * cell
        cols = [x for x in range(cell) if any(px[cx + x, cy + y][3] > 0 for y in range(cell))]
        out[i] = (cols[0], cols[-1] + 1) if cols else (0, 0)
    return out
res = {}
for i, w in widths(root + '/font/default8.png', 16, 8).items():
    res[i] = 4 if i == 32 else w[1] + 1
for page in (4,):  # Cyrillic
    try:
        for i, w in widths(root + '/font/glyph_%02X.png' % page, 16, 16).items():
            if w[1]:
                res[page * 256 + i] = (w[1] - w[0]) // 2 + 2
    except FileNotFoundError:
        pass
for k in sorted(res):
    print(k, res[k])
