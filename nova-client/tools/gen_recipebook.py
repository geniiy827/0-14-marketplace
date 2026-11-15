"""Pictures of the NOVA recipe book (native/recipebook.inc) cut from Java 1.19.4 GUI textures.

  python3 tools/gen_recipebook.py <Java textures dir> <assets_new dir>
  -> images/gui/nova/rb/*.png (loaded by libnova as "gui/nova/rb/<name>.png")
"""
import os, sys
from PIL import Image

J, OUT = sys.argv[1], os.path.join(sys.argv[2], 'images/gui/nova/rb')
os.makedirs(OUT, exist_ok=True)


def img(p):
    return Image.open(os.path.join(J, p)).convert('RGBA')


def save(im, name):
    im.save(os.path.join(OUT, name + '.png'))


btn = img('gui/recipe_button.png')
save(btn.crop((0, 0, 20, 18)), 'button')                 # the green book
save(btn.crop((0, 19, 20, 37)), 'button_on')             # hovered / open

book = img('gui/recipe_book.png')
bg = book.crop((1, 1, 148, 167))                          # 147 x 166 panel, cut in 9 for any size
B = 4
W, H = bg.size
parts = {'tl': (0, 0, B, B), 't': (B, 0, W - B, B), 'tr': (W - B, 0, W, B),
         'l': (0, B, B, H - B), 'c': (B + 4, B + 30, W - B - 4, H - B - 4), 'r': (W - B, B, W, H - B),
         'bl': (0, H - B, B, H), 'b': (B, H - B, W - B, H), 'br': (W - B, H - B, W, H)}
for k, r in parts.items():
    save(bg.crop(r), 'bg_' + k)
save(book.crop((29, 206, 54, 231)), 'slot')              # 25 x 25 recipe button
save(book.crop((1, 208, 13, 225)), 'arrow_next')          # page arrows 12 x 17
save(book.crop((14, 208, 26, 225)), 'arrow_prev')
save(book.crop((1, 226, 13, 243)), 'arrow_next_on')
save(book.crop((14, 226, 26, 243)), 'arrow_prev_on')

tabs = img('gui/container/creative_inventory/tabs.png')
save(tabs.crop((0, 0, 26, 32)), 'tab')                    # top tab, not selected
save(tabs.crop((0, 32, 26, 64)), 'tab_on')                # selected


def window(src, h=80):
    """the top part of a container picture (title, slots) + its bottom rim"""
    s = img(src)
    w = Image.new('RGBA', (176, h + 4))
    w.paste(s.crop((0, 0, 176, h)), (0, 0))
    w.paste(s.crop((0, 162, 176, 166)), (0, h))
    return w


save(window('gui/container/crafting_table.png'), 'craft')     # grid 3x3 at 30,17 (18 px), result 124,35 (center)
fur = window('gui/container/furnace.png')
f = img('gui/container/furnace.png')
fur.alpha_composite(f.crop((176, 0, 190, 14)), (56, 36))       # lit flame
fur.alpha_composite(f.crop((176, 14, 200, 31)), (79, 34))      # full progress arrow
save(fur, 'furnace')                                       # input 56,17  fuel 56,53  result 116,35 (slot corners)
print('ok')
