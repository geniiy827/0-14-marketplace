"""The wooden dimension's portal: the game's portal.png (16x512, 32 frames) in amber.
  python3 tools/gen_wood_portal.py <game assets/images/portal.png> assets_new/images/nova/wood_portal.png"""
import sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGBA')
px = im.load()
out = Image.new('RGBA', im.size)
po = out.load()
for y in range(im.height):
    for x in range(im.width):
        r, g, b, a = px[x, y]
        v = max(r, g, b) / 255.0
        po[x, y] = (min(255, int(255 * v * 1.05)), min(255, int(255 * v * 0.66)), min(255, int(255 * v * 0.24)), a)
out.save(sys.argv[2])
