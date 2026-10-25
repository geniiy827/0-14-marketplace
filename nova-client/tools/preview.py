# Approximate preview: nineslice + multiplicative tint, like the game's ui shader.
import sys
from PIL import Image, ImageDraw, ImageFont
IMG = sys.argv[1]; OUT = sys.argv[2]
S = 3  # gui scale
def tex(path, uv=(0,0), uvs=None):
    im = Image.open(IMG + '/' + path).convert('RGBA')
    if uvs: im = im.crop((uv[0], uv[1], uv[0]+uvs[0], uv[1]+uvs[1]))
    return im
def tint(im, c, a=1.0):
    r,g,b,al = im.split()
    r = r.point(lambda v: int(v*c[0])); g = g.point(lambda v: int(v*c[1])); b = b.point(lambda v: int(v*c[2]))
    al = al.point(lambda v: int(v*a))
    return Image.merge('RGBA',(r,g,b,al))
def nine(im, w, h, n):
    W,H = im.size; out = Image.new('RGBA',(w,h))
    xs=[(0,n,0,n),(n,W-n,n,w-n),(W-n,W,w-n,w)]; ys=[(0,n,0,n),(n,H-n,n,h-n),(H-n,H,h-n,h)]
    for sx0,sx1,dx0,dx1 in xs:
        for sy0,sy1,dy0,dy1 in ys:
            if dx1>dx0 and dy1>dy0 and sx1>sx0 and sy1>sy0:
                out.paste(im.crop((sx0,sy0,sx1,sy1)).resize((dx1-dx0,dy1-dy0),Image.NEAREST),(dx0,dy0))
    return out
def put(canvas, im, x, y):
    im = im.resize((im.width*S, im.height*S), Image.NEAREST); canvas.alpha_composite(im,(x*S,y*S))
try: F = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 8*S)
except: F = ImageFont.load_default()
def text(d, x, y, t, c, anchor='mm'):
    col = tuple(int(v*255) for v in c[:3])
    d.text((x*S+S, y*S+S), t, fill=(30,30,30,255), font=F, anchor=anchor)
    d.text((x*S, y*S), t, fill=col+(255,), font=F, anchor=anchor)

W,H = 300, 170
c = Image.new('RGBA',(W*S,H*S),(0,0,0,255))
# panorama stand-in (darkened sky) + start screen
pan = tex('gui/background/panorama_0.png').resize((W,H)); put(c, pan, 0, 0)
d = ImageDraw.Draw(c)
put(c, tex('gui/title.png').resize((256,32)), 22, 16)
put(c, tint(tex('gui/newgui/newTouchScrollBox.png').resize((118,1)),(0.4,0.95,0.88)), 91, 52)
BTN = tex('gui/newgui/NormalButtonStroke.png')
put(c, tint(nine(tex('gui/newgui/NormalButtonNoStroke.png'),138,68,4),(0.05,0.06,0.12),0.55), 81, 82)
put(c, tint(nine(BTN,124,28,4),(0.22,0.6,0.64)), 88, 89); text(d,150,103,'Играть',(1,1,1))
put(c, tint(nine(BTN,61,24,4),(0.32,0.36,0.48),0.92), 88, 120); text(d,118,132,'Настройки',(1,1,1))
put(c, tint(nine(BTN,61,24,4),(0.24,0.66,0.7)), 151, 120); text(d,181,132,'Скины',(0.4,0.95,0.88))
put(c, tint(nine(BTN,60,14,4),(0.12,0.14,0.22),0.85), 4, 4)
put(c, tint(tex('gui/newgui/NormalButtonNoStroke.png').resize((6,6)),(0.4,0.95,0.88)), 8, 8); text(d,14,11,'NOVA client',(0.9,0.95,1),'lm')
put(c, tint(tex('gui/newgui/newTouchScrollBox.png').resize((W,12)),(0.02,0.03,0.07),0.7), 0, H-12)
put(c, tint(tex('gui/newgui/newTouchScrollBox.png').resize((W,1)),(0.68,0.52,1.0)), 0, H-12)
text(d,3,H-6,'© Mojang AB',(0.62,0.67,0.78),'lm'); text(d,W-3,H-6,'v0.14.3',(0.4,0.95,0.88),'rm')
c.save(OUT + '/start_preview.png')

# components sheet: container window + hotbar + chat
c = Image.new('RGBA',(W*S,H*S),(18,22,30,255)); d = ImageDraw.Draw(c)
dlg = tex('gui/newgui/dialog-background-atlas.png',(0,80),(16,16))
put(c, tint(nine(dlg,176,96,4),(0.27,0.3,0.4)), 6, 6)
text(d,13,14,'Сундук',(0.4,0.95,0.88),'lm')
cell = tex('gui/newgui/Indent.png',(0,0),(5,5))
for i in range(9):
    for j in range(3):
        put(c, tint(nine(cell,18,18,1),(0.45,0.52,0.68)), 13+i*18, 22+j*18)
put(c, tint(tex('gui/gui.png',(13,196),(1,1)).resize((16,16)),(0.4,0.95,0.88),0.45), 14+2*18, 23+18)
d2 = tex('gui/newgui/newTouchScrollBox.png')
for i in range(9):
    put(c, tint(nine(tex('gui/newgui/NormalButtonNoStroke.png'),20,22,4),(0.07,0.08,0.14),0.62), 60+i*20, 140)
put(c, tint(tex('gui/gui.png',(0,22),(24,22)),(0.4,0.95,0.88)), 58+3*20, 139)
put(c, tint(d2.resize((150,10)),(0.04,0.05,0.12),0.6), 192, 10)
put(c, tint(d2.resize((2,10)),(0.4,0.95,0.88)), 192, 10); text(d,197,15,'<Steve> привет!',(0.94,0.96,1),'lm')
put(c, tint(nine(BTN,90,22,4),(0.2,0.23,0.32)), 196, 30)
put(c, tint(d2.resize((82,1)),(0.4,0.95,0.88)), 200, 33); text(d,241,44,'Диалог',(0.4,0.95,0.88))
text(d,150,130,'Алмазный меч',(0.55,1.0,0.93))
c.save(OUT + '/components_preview.png')
