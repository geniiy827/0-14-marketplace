"""EXPERIMENTAL (disabled by default): horizontal trampoline floors in libKumoGame.so (arm64).
Floors whose x0 has a .5 fraction become trampolines. Usage: native_trampoline.py in.so out.so"""
import keystone,struct,sys
src,dst=sys.argv[1],sys.argv[2]
B=bytearray(open(src,'rb').read())
ks=keystone.Ks(keystone.KS_ARCH_ARM64,keystone.KS_MODE_LITTLE_ENDIAN)
def asm(code,addr):
    enc,_=ks.asm(code,addr); return bytes(enc)
def put(addr,code,expect=None):
    b=asm(code,addr)
    if expect is not None: assert B[addr:addr+len(expect)]==expect,(hex(addr),B[addr:addr+8].hex())
    B[addr:addr+len(b)]=b; return len(b)
def orig(addr): return bytes(B[addr:addr+4])
P=dict(GetLevelRuntime=0x87ca00,GetWallAnim=0x8822b0,CreateAnimInst=0x87f120,OpDelete=0x87ca30,
       StrCtor=0x513a90,SetFillAnim=0x8826d0,SetStartAnim=0x8826e0,SetEndAnim=0x8826f0,
       GetAnimFloor=0x884160,AutoJump=0x884380)
S=dict(bouncy=0x3d920b,bouncyTop=0x3cb108,bouncyBottom=0x3ca807)
CAVE=0x7f78dc; CAVE_END=0x7f7f20
FLAG=0xf8; ANIMS=0x100   # bouncy anims at 0x100 (fill),0x108 (start),0x110 (end)
NEWSIZE=0x118
TRAMP_VY=0x43c80000      # 400.0 (normal jump 250.0)
TYPE_TRAMP=6
# ---- 1. floor object size
assert B[0x595e9c:0x595ea0]==asm('mov w0,#0xf8',0x595e9c)
put(0x595e9c,f'mov w0,#{NEWSIZE:#x}')
cave=CAVE
def emit(code):
    global cave
    n=put(cave,code); a=cave; cave+=n; cave=(cave+15)&~15; return a
# ---- 2. OnActivate: create bouncy anim instances
def mkanim(name,dst):
    L='L_'+name
    return f'''
    mov x0,x19
    bl {P["GetLevelRuntime"]:#x}
    mov x20,x0
    adrp x8,#0x8ce000
    ldr x8,[x8,#0x340]
    ldr x21,[x8]
    add x0,sp,#0x20
    adrp x1,#{S[name]&~0xfff:#x}
    add x1,x1,#{S[name]&0xfff:#x}
    bl {P["StrCtor"]:#x}
    mov x0,x21
    add x1,sp,#0x20
    bl {P["GetWallAnim"]:#x}
    mov x2,x0
    mov x0,x20
    ldr x1,[x19,#0x10]
    bl {P["CreateAnimInst"]:#x}
    str x0,[x19,#{dst:#x}]
    ldrb w8,[sp,#0x20]
    tbz w8,#0,{L}
    ldr x0,[sp,#0x30]
    bl {P["OpDelete"]:#x}
{L}:
'''
HOOK_ACT=0x595680
assert B[HOOK_ACT:HOOK_ACT+4]==asm('ldr x0,[x19,#0x10]',HOOK_ACT)
code_act=f'''
    stp x29,x30,[sp,#-0x50]!
    mov x29,sp
    stp x20,x21,[sp,#0x40]
    strb wzr,[x19,#{FLAG:#x}]
    str xzr,[x19,#{ANIMS:#x}]
    str xzr,[x19,#{ANIMS+8:#x}]
    str xzr,[x19,#{ANIMS+16:#x}]
''' + mkanim('bouncy',ANIMS)+mkanim('bouncyBottom',ANIMS+8)+mkanim('bouncyTop',ANIMS+16)+f'''
    ldp x20,x21,[sp,#0x40]
    ldp x29,x30,[sp],#0x50
    ldr x0,[x19,#0x10]
    ret
'''
a_act=emit(code_act)
put(HOOK_ACT,f'bl {a_act:#x}')
# ---- 3. Spawn: detect trampoline floor (x0 has .5 fraction) and swap textures
HOOK_SP=0x595030
assert B[HOOK_SP:HOOK_SP+4]==asm('sub sp,sp,#0x80',HOOK_SP)
code_sp=f'''
    stp x29,x30,[sp,#-0x40]!
    mov x29,sp
    stp x0,x1,[sp,#0x10]
    stp s0,s1,[sp,#0x20]
    str s2,[sp,#0x28]
    str x19,[sp,#0x30]
    mov x19,x0
    frintm s3,s0
    fsub s3,s0,s3
    fmov s4,#0.5
    fcmp s3,s4
    cset w9,eq
    ldr x10,[x19,#{ANIMS:#x}]
    cmp x10,#0
    csel w9,wzr,w9,eq
    strb w9,[x19,#{FLAG:#x}]
    mov x10,#0x58
    mov x11,#{ANIMS:#x}
    cmp w9,#0
    csel x10,x10,x11,eq
    str x10,[sp,#0x38]
    add x0,x19,#0x70
    ldr x1,[x19,x10]
    bl {P["SetFillAnim"]:#x}
    ldr x10,[sp,#0x38]
    add x10,x10,#8
    add x0,x19,#0x70
    ldr x1,[x19,x10]
    bl {P["SetStartAnim"]:#x}
    ldr x10,[sp,#0x38]
    add x10,x10,#16
    add x0,x19,#0x70
    ldr x1,[x19,x10]
    bl {P["SetEndAnim"]:#x}
    ldr x19,[sp,#0x30]
    ldr s2,[sp,#0x28]
    ldp s0,s1,[sp,#0x20]
    ldp x0,x1,[sp,#0x10]
    ldp x29,x30,[sp],#0x40
    sub sp,sp,#0x80
    b {HOOK_SP+4:#x}
'''
a_sp=emit(code_sp)
put(HOOK_SP,f'b {a_sp:#x}')
# ---- 4. Player prestep: auto-jump while animating on a trampoline floor
HOOK_PP=0x5ac4ec
assert B[HOOK_PP:HOOK_PP+4]==asm('ldrb w8,[x19,#0x3a8]',HOOK_PP)
code_pp=f'''
    stp x29,x30,[sp,#-0x10]!
    mov x29,sp
    add x0,x19,#0x280
    bl {P["GetAnimFloor"]:#x}
    cbz x0,Lpp
    ldrb w8,[x0,#{FLAG:#x}]
    cbz w8,Lpp
    add x0,x19,#0xf0
    fmov s0,#0.25
    mov w1,#{TYPE_TRAMP}
    bl {P["AutoJump"]:#x}
Lpp:
    ldp x29,x30,[sp],#0x10
    ldrb w8,[x19,#0x3a8]
    ret
'''
a_pp=emit(code_pp)
put(HOOK_PP,f'bl {a_pp:#x}')
# ---- 5. Jump: trampoline jump type -> stronger vertical velocity, reported as bouncy(2)
HOOK_J=0x5b2ac4
assert B[HOOK_J:HOOK_J+4]==asm('mov w8,#0x437a0000',HOOK_J)
code_j=f'''
    mov w8,#0x437a0000
    cmp w3,#{TYPE_TRAMP}
    b.ne Lj
    movz w8,#{TRAMP_VY>>16:#x},lsl #16
    mov w3,#2
Lj:
    ret
'''
a_j=emit(code_j)
put(HOOK_J,f'bl {a_j:#x}')
assert cave<=CAVE_END, hex(cave)
print('cave used',hex(cave-CAVE))
open(dst,'wb').write(B)
