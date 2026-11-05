class MT:
    def __init__(s, seed):
        s.m=[0]*624; s.m[0]=seed&0xffffffff
        for i in range(1,624): s.m[i]=(0x6c078965*(s.m[i-1]^(s.m[i-1]>>30))+i)&0xffffffff
        s.i=624
    def u(s):
        if s.i>=624:
            for k in range(624):
                y=(s.m[k]&0x80000000)|(s.m[(k+1)%624]&0x7fffffff)
                s.m[k]=s.m[(k+397)%624]^(y>>1)^(0x9908b0df if y&1 else 0)
            s.i=0
        y=s.m[s.i]; s.i+=1
        y^=y>>11; y^=(y<<7)&0x9d2c5680; y^=(y<<15)&0xefc60000; y^=y>>18
        return y
def village_chunk(seed, rx, rz):
    s=(rx*0x9939F508 + rz*0xF1565BD5 + seed + 10387312)&0xffffffff
    m=MT(s); return rx*40 + m.u()%28, rz*40 + m.u()%28
if __name__=='__main__':
    import sys
    for rx in range(-1,3):
        for rz in range(-1,2): print(rx,rz,village_chunk(int(sys.argv[1]),rx,rz))
def is_village(seed, cx, cz):
    ax = cx - 39 if cx < 0 else cx; az = cz - 39 if cz < 0 else cz
    s = (ax*0x9939F508 + az*0xF1565BD5 + seed + 10387312) & 0xffffffff
    m = MT(s); u1 = m.u(); u2 = m.u()
    q = lambda a: int(a/40)  # C truncation
    return (u1 % 28) + q(ax)*40 == cx and (u2 % 28) + q(az)*40 == cz
