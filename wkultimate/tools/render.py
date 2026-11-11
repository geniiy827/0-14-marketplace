import sys,struct
from . import wkfmt
from PIL import Image,ImageDraw
def render(s,scale=1.5,pad=40):
    xs=[0,s['exit'][0]];ys=[0,s['exit'][1]]
    for k in ['walls','startWalls','conveyors','electric','crumbling','bouncy','flipping','flipSync']:
        for it in s[k]: xs.append(it[0]); ys+= [it[1],it[2]]
    for it in s['blocks']: xs+=[it[0],it[2]]; ys+=[it[1],it[3]]
    for it in s['spikes']: xs+=[it[0],it[1]]; ys+=[it[2],it[3]]
    for it in s['floors']: xs+=[it[0],it[1]]; ys.append(it[2])
    for it in s['coins']+s['markers']: xs.append(it[0]); ys.append(it[1])
    for p in s['pathWalls']+s['spikeBalls']:
        hx,hy=struct.unpack_from('<ff',p['head'])
        for q in p['pts']: xs.append(hx+q[1]); ys.append(hy+q[2])
    x0,x1,y0,y1=min(xs)-pad,max(xs)+pad,min(ys)-pad,max(ys)+pad
    W,H=int((x1-x0)*scale),int((y1-y0)*scale)+16
    im=Image.new('RGB',(max(W,160),H),(25,25,35)); dr=ImageDraw.Draw(im)
    T=lambda x,y:((x-x0)*scale,(y1-y)*scale+16)
    def vline(x,ya,yb,c,w=4): dr.line([T(x,ya),T(x,yb)],fill=c,width=w)
    for it in s['blocks']: dr.rectangle([T(it[0],it[3]),T(it[2],it[1])],outline=(90,90,140))
    for it in s['floors']: dr.line([T(it[0],it[2]),T(it[1],it[2])],fill=(200,200,200),width=4)
    for it in s['walls']: vline(it[0],it[1],it[2],(220,220,220))
    for it in s['startWalls']: vline(it[0],it[1],it[2],(120,255,120))
    for it in s['conveyors']: vline(it[0],it[1],it[2],(255,160,0))
    for it in s['electric']: vline(it[0],it[1],it[2],(80,180,255))
    for it in s['crumbling']: vline(it[0],it[1],it[2],(160,110,60))
    for it in s['bouncy']: vline(it[0],it[1],it[2],(255,80,200))
    for it in s['flipping']: vline(it[0],it[2],it[3],(255,255,0))
    for it in s['spikes']: dr.line([T(it[0],it[2]),T(it[1],it[3])],fill=(255,40,40),width=3)
    for it in s['coins']: 
        a,b=T(it[0],it[1]); r=4 if it[2]==0 else 7; dr.ellipse([a-r,b-r,a+r,b+r],fill=(255,215,0))
    for it in s['markers']:
        a,b=T(it[0],it[1]); dr.line([a-5,b-5,a+5,b+5],fill=(0,255,255)); dr.line([a-5,b+5,a+5,b-5],fill=(0,255,255))
    for p in s['pathWalls']+s['spikeBalls']:
        hx,hy=struct.unpack_from('<ff',p['head'])
        pts=[T(hx+q[1],hy+q[2]) for q in p['pts']]
        if len(pts)>1: dr.line(pts,fill=(150,150,255),width=1)
        a,b=pts[0]
        if p in s['spikeBalls']: dr.ellipse([a-8,b-8,a+8,b+8],outline=(255,40,40),width=2)
        else:
            y1_,y2_=struct.unpack_from('<ff',p['head'],4); vline(hx,y1_,y2_,(150,150,255))
    a,b=T(0,0); dr.ellipse([a-3,b-3,a+3,b+3],fill=(0,255,0))
    a,b=T(s['exit'][0],s['exit'][1]); dr.polygon([(a,b-6),(a+6*s['exit'][2],b),(a,b+6)],fill=(255,0,0))
    dr.text((2,2),s['name'],fill=(255,255,255))
    return im
def sheet(data, path, names=None, cols=8):
    ims=[render(x) for x in data.scn if x['tags'] and (names is None or x['name'] in names)]
    cw=min(max(i.width for i in ims),420); ch=min(max(i.height for i in ims),560)
    rows=(len(ims)+cols-1)//cols
    out=Image.new('RGB',(cols*cw,rows*ch),(0,0,0))
    for k,im in enumerate(ims): out.paste(im.crop((0,0,cw,ch)),((k%cols)*cw,(k//cols)*ch))
    out.save(path)
