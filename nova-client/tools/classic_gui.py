"""Classic button pictures from 1.1.5 (textures/gui/newgui/ButtonWithBorder*.png, 6x6 of each 8x8 file) ->
assets_new/images/gui/nova/btn.png, btn_hover.png, btn_pressed.png, btn_locked.png (used by ui_new, nineslice 2,
and by McKit's Java screens). usage: classic_gui.py <1.1.5 textures/gui/newgui> <assets_new>"""
import os, sys
from PIL import Image

src, out = sys.argv[1], sys.argv[2]
d = os.path.join(out, 'images', 'gui', 'nova')
os.makedirs(d, exist_ok=True)
for name, f in (('btn', 'ButtonWithBorder'), ('btn_hover', 'ButtonWithBorderHover'), ('btn_pressed', 'ButtonWithBorderPressed')):
    Image.open(os.path.join(src, f + '.png')).convert('RGBA').crop((0, 0, 6, 6)).save(os.path.join(d, name + '.png'))
# locked: the black outline of the normal button around 1.1.5's disabled grey (disabledButtonNoBorder)
im = Image.open(os.path.join(src, 'ButtonWithBorder.png')).convert('RGBA').crop((0, 0, 6, 6))
grey = Image.open(os.path.join(src, 'disabledButtonNoBorder.png')).convert('RGBA').getpixel((0, 0))
for y in range(1, 5):
    for x in range(1, 5):
        im.putpixel((x, y), grey)
im.save(os.path.join(d, 'btn_locked.png'))
print('classic buttons ->', d)
