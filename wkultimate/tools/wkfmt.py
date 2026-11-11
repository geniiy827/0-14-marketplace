"""Wall Kickers WJScenarios.dat (gunzipped) scenario-section parser/writer."""
import struct
SIMPLE=[('walls',3),('startWalls',3),('endWalls',2),('markers',2),('blocks',4),
        ('flipping',5),('spikes',4),('conveyors',4),('coins',3),('flipSync',5),
        ('electric',5),('crumbling',3),('bouncy',4)]
TAIL=[('floors',3),('L17',3),('L18',4),('L19',1),('L20',1)]
class R:
    def __init__(s,d,o=0): s.d=d; s.o=o
    def u(s): v=struct.unpack_from('<I',s.d,s.o)[0]; s.o+=4; return v
    def i(s): v=struct.unpack_from('<i',s.d,s.o)[0]; s.o+=4; return v
    def f(s): v=struct.unpack_from('<f',s.d,s.o)[0]; s.o+=4; return v
    def raw(s,n): v=s.d[s.o:s.o+n]; s.o+=n; return v
    def str(s): n=s.u(); return s.raw(n).decode()
def rpath(r,nhead):
    item=r.raw(4*nhead)
    flag=(r.u(),r.u()); ln=r.f()
    m=r.u(); pts=[(r.f(),r.f(),r.f()) for _ in range(m)]
    return dict(head=item,flag=flag,len=ln,pts=pts)
def parse_scn(r):
    s={}
    s['id']=r.raw(8); s['name']=r.str()
    s['weight'],s['dmin'],s['dmax']=r.f(),r.f(),r.f()
    s['tags']=[r.str() for _ in range(r.u())]
    s['exit']=(r.f(),r.f(),r.i())
    for k,n in SIMPLE:
        c=r.u(); s[k]=[tuple(r.f() for _ in range(n)) for _ in range(c)]
    s['pathWalls']=[rpath(r,8) for _ in range(r.u())]
    s['spikeBalls']=[rpath(r,7) for _ in range(r.u())]
    for k,n in TAIL:
        c=r.u(); s[k]=[tuple(r.f() for _ in range(n)) for _ in range(c)]
    return s
def parse(d):
    r=R(d); n=r.u(); scns=[parse_scn(r) for _ in range(n)]
    return scns, d[r.o:]
def wf(b,*v): b+=struct.pack('<%df'%len(v),*v)
def w_scn(s):
    b=bytearray(); b+=s['id']; nm=s['name'].encode(); b+=struct.pack('<I',len(nm))+nm
    b+=struct.pack('<fff',s['weight'],s['dmin'],s['dmax'])
    b+=struct.pack('<I',len(s['tags']))
    for t in s['tags']: t=t.encode(); b+=struct.pack('<I',len(t))+t
    b+=struct.pack('<ffi',*s['exit'])
    def lst(items,n):
        nonlocal b; b+=struct.pack('<I',len(items))
        for it in items: assert len(it)==n,(it,n); wf(b,*it)
    for k,n in SIMPLE: lst(s[k],n)
    for key,nh in (('pathWalls',8),('spikeBalls',7)):
        b+=struct.pack('<I',len(s[key]))
        for p in s[key]:
            b+=p['head']; b+=struct.pack('<IIf',*p['flag'],p['len'])
            b+=struct.pack('<I',len(p['pts']))
            for q in p['pts']: wf(b,*q)
    for k,n in TAIL: lst(s[k],n)
    return bytes(b)
def write(scns,rest):
    return struct.pack('<I',len(scns))+b''.join(w_scn(s) for s in scns)+rest
