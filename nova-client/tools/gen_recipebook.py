"""Pictures of the NOVA recipe book (native/recipebook.inc), in the colours of NOVA's own inventory (ui_new/ui_common.json):
panel = dialog-background-atlas.png uv 0,80 16x16 nineslice 4 tinted 0.44 0.49 0.62; slot = Indent.png nineslice 1 tinted
0.62 0.69 0.86; button = gui/nova/btn_square.png nineslice 4 (0.47 0.53 0.68, hover 0.34 0.8 0.82); close = the
screen close button (red 0.75 0.28 0.34 + X); arrows gui/newgui/arrow*.png. libnova's blit has no tint or uv, so the
tinted nine-slice pieces are baked here. The book on the button: Java 1.19.4 gui/recipe_button.png.

  python3 tools/gen_recipebook.py <game assets/images> <Java textures> <assets_new>
"""
import os, shutil, sys
from PIL import Image

G, J, A = sys.argv[1], sys.argv[2], sys.argv[3]
OUT = os.path.join(A, 'images/gui/nova/rb')
shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT)


def tint(im, c, a=1.0):
    im = im.convert('RGBA')
    px = im.load()
    for y in range(im.height):
        for x in range(im.width):
            r, g, b, al = px[x, y]
            px[x, y] = (int(r * c[0]), int(g * c[1]), int(b * c[2]), int(al * a))
    return im


def nine(im, n, prefix):
    W, H = im.size
    xs, ys = [0, n, W - n, W], [0, n, H - n, H]
    names = [['tl', 't', 'tr'], ['l', 'c', 'r'], ['bl', 'b', 'br']]
    for j in range(3):
        for i in range(3):
            im.crop((xs[i], ys[j], xs[i + 1], ys[j + 1])).save(os.path.join(OUT, '%s_%s.png' % (prefix, names[j][i])))


atlas = Image.open(os.path.join(G, 'gui/newgui/dialog-background-atlas.png'))
nine(tint(atlas.crop((0, 80, 16, 96)), (0.44, 0.49, 0.62)), 4, 'panel')
indent = Image.open(os.path.join(G, 'gui/newgui/Indent.png')).crop((0, 0, 5, 5))
nine(tint(indent, (0.62, 0.69, 0.86)), 1, 'cell')
btn = Image.open(os.path.join(A, 'images/gui/nova/btn_square.png'))
nine(tint(btn, (0.47, 0.53, 0.68), 0.92), 4, 'btn')
nine(tint(btn, (0.34, 0.8, 0.82)), 4, 'btnh')
nine(tint(btn, (0.75, 0.28, 0.34)), 4, 'btnx')
tint(Image.open(os.path.join(G, 'gui/newgui/arrow.png')), (0.4, 0.95, 0.88)).save(os.path.join(OUT, 'arrow.png'))
tint(Image.open(os.path.join(G, 'gui/newgui/X.png')), (1.0, 1.0, 1.0)).save(os.path.join(OUT, 'x.png'))
for n in ('arrowLeft', 'arrowRight'):
    Image.open(os.path.join(G, 'gui/newgui/%s.png' % n)).convert('RGBA').save(os.path.join(OUT, n + '.png'))

# the green book of Java's recipe button (with its pages), without the button around it
rb = Image.open(os.path.join(J, 'gui/recipe_button.png')).convert('RGBA').crop((2, 21, 18, 35))
px = rb.load()
face = px[0, 0]
for y in range(rb.height):
    for x in range(rb.width):
        if px[x, y][:3] == face[:3]:
            px[x, y] = (0, 0, 0, 0)
rb.save(os.path.join(OUT, 'book.png'))
print('ok')
