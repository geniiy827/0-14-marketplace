"""Headless OpenGL renderer (moderngl + EGL/llvmpipe)."""
import math
import numpy as np
import moderngl

from .skin import player_parts

# ------------------------------------------------------------- math helpers
def perspective(fov_deg, aspect, near, far):
    f = 1.0 / math.tan(math.radians(fov_deg) / 2)
    m = np.zeros((4, 4), np.float32)
    m[0, 0] = f / aspect; m[1, 1] = f
    m[2, 2] = (far + near) / (near - far); m[2, 3] = 2 * far * near / (near - far)
    m[3, 2] = -1
    return m


def ortho(l, r, b, t, n, f):
    m = np.eye(4, dtype=np.float32)
    m[0, 0] = 2 / (r - l); m[1, 1] = 2 / (t - b); m[2, 2] = -2 / (f - n)
    m[0, 3] = -(r + l) / (r - l); m[1, 3] = -(t + b) / (t - b); m[2, 3] = -(f + n) / (f - n)
    return m


def look_at(eye, target, up=(0, 1, 0)):
    eye, target, up = map(lambda v: np.asarray(v, np.float64), (eye, target, up))
    f = target - eye; f /= np.linalg.norm(f)
    s = np.cross(f, up); s /= np.linalg.norm(s)
    u = np.cross(s, f)
    m = np.eye(4)
    m[0, :3] = s; m[1, :3] = u; m[2, :3] = -f
    m[:3, 3] = -m[:3, :3] @ eye
    return m.astype(np.float32)


def T(x, y, z):
    m = np.eye(4, dtype=np.float32); m[:3, 3] = (x, y, z); return m


def S(s):
    m = np.eye(4, dtype=np.float32); m[0, 0] = m[1, 1] = m[2, 2] = s; return m


def Rx(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[1, 0, 0, 0], [0, c, -s, 0], [0, s, c, 0], [0, 0, 0, 1]], np.float32)


def Ry(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s, 0], [0, 1, 0, 0], [-s, 0, c, 0], [0, 0, 0, 1]], np.float32)


def Rz(a):
    c, s = math.cos(a), math.sin(a)
    return np.array([[c, -s, 0, 0], [s, c, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]], np.float32)


def gl_mat(m):
    return np.ascontiguousarray(m.T, dtype=np.float32).tobytes()


# ------------------------------------------------------------------ shaders
VS = """
#version 330
uniform mat4 model; uniform mat4 vp; uniform mat4 lvp;
in vec3 in_pos; in vec3 in_norm; in vec2 in_uv; in float in_ao;
out vec3 wpos; out vec3 wn; out vec2 uv; out float ao; out vec4 lpos;
void main(){
  vec4 w = model*vec4(in_pos,1.0);
  wpos = w.xyz; wn = normalize(mat3(model)*in_norm); uv = in_uv; ao = in_ao;
  lpos = lvp*w; gl_Position = vp*w;
}"""

FS = """
#version 330
uniform sampler2D tex; uniform sampler2DShadow sh_static; uniform sampler2DShadow sh_dyn;
uniform vec3 sun_dir; uniform vec3 sun_col; uniform vec3 amb_col; uniform vec3 fog_col;
uniform vec3 cam; uniform float fog_near; uniform float fog_far; uniform float alpha_mul;
uniform float time; uniform float flat_light; uniform vec3 torch_col; uniform vec3 torch_pos;
in vec3 wpos; in vec3 wn; in vec2 uv; in float ao; in vec4 lpos;
out vec4 frag;
float pcf(sampler2DShadow s, vec3 p){
  vec2 ts = 1.0/vec2(textureSize(s,0));
  // hardware 2x2 PCF (linear compare) x2 taps = soft edges at 1/4.5 of the cost
  return 0.5*(texture(s, vec3(p.xy+vec2(-0.6,0.4)*ts, p.z)) + texture(s, vec3(p.xy+vec2(0.6,-0.4)*ts, p.z)));
}
void main(){
  vec4 c = texture(tex, uv);
#ifdef CUTOUT
  if(c.a < 0.08 || (alpha_mul > 0.99 && c.a < 0.5)) discard;
#endif
  vec3 n = normalize(wn);
  if(!gl_FrontFacing) n = -n;
  float face = 0.5 + 0.5*max(n.y,0.0) + 0.3*abs(n.z) + 0.15*abs(n.x) - 0.25*max(-n.y,0.0);
  face = clamp(face*0.85, 0.35, 1.0);
  vec3 p = lpos.xyz/lpos.w*0.5+0.5;
  float bias = 0.0015;
  float s = 1.0;
  if(p.x>0.0 && p.x<1.0 && p.y>0.0 && p.y<1.0 && p.z<1.0){
    s = min(pcf(sh_static, vec3(p.xy, p.z-bias)), pcf(sh_dyn, vec3(p.xy, p.z-bias)));
  }
  float ndl = max(dot(n, sun_dir), 0.0);
  float a = ao > 1.5 ? 1.0 : ao;
  vec3 light = amb_col*face*a + sun_col*ndl*s*mix(1.0, a, 0.5);
  if(flat_light > 0.5) light = vec3(1.0);
  vec3 col = c.rgb*light;
  if(ao > 1.5) col = c.rgb*1.3;  // emissive
  // torch glow near house
  col += c.rgb*torch_col*max(0.0, 1.0 - length(wpos - torch_pos)/7.0);
  float d = length(wpos - cam);
  float fog = clamp((d - fog_near)/(fog_far - fog_near), 0.0, 1.0);
  col = mix(col, fog_col, fog*fog);
  frag = vec4(col, alpha_mul < 0.99 ? c.a*alpha_mul : 1.0);
}"""

SH_VS = """
#version 330
uniform mat4 model; uniform mat4 lvp;
in vec3 in_pos; in vec2 in_uv; out vec2 uv;
void main(){ uv=in_uv; gl_Position = lvp*model*vec4(in_pos,1.0); }"""
SH_FS = """
#version 330
uniform sampler2D tex; in vec2 uv;
void main(){ if(texture(tex,uv).a<0.5) discard; }"""

SKY_VS = """
#version 330
in vec2 in_pos; out vec2 ndc;
void main(){ ndc=in_pos; gl_Position=vec4(in_pos,0.9999,1.0); }"""
SKY_FS = """
#version 330
uniform mat4 inv_vp; uniform vec3 cam; uniform vec3 sun_dir; uniform vec3 zenith; uniform vec3 horizon;
uniform vec3 sun_color; uniform float time; uniform float stars; uniform vec3 cloud_col;
in vec2 ndc; out vec4 frag;
float h(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7)))*43758.5453); }
void main(){
  vec4 a = inv_vp*vec4(ndc, 1.0, 1.0); vec3 d = normalize(a.xyz/a.w - cam);
  float t = clamp(d.y*1.6, 0.0, 1.0);
  vec3 col = mix(horizon, zenith, pow(t, 0.7));
  if(d.y < 0.0) col = horizon*0.9;
  // square sun
  vec3 sd = normalize(sun_dir);
  vec3 r = normalize(cross(sd, vec3(0,1,0))); vec3 u = cross(r, sd);
  float k = dot(d, sd);
  if(k>0.0){ vec2 q = vec2(dot(d,r), dot(d,u))/k; if(max(abs(q.x),abs(q.y))<0.06) col = sun_color;
     else col += sun_color*0.25*exp(-length(q)*8.0); }
  if(stars>0.0 && d.y>0.05){ vec2 g = floor(d.xz/max(d.y,0.05)*60.0); if(h(g)>0.996) col += vec3(stars); }
  // blocky clouds
  if(d.y > 0.02){
    float H = 45.0; float tt = (H - cam.y)/d.y; vec2 p = cam.xz + d.xz*tt + vec2(time*0.8, 0.0);
    vec2 c = floor(p/9.0);
    float on = step(0.62, h(c)) + step(0.8, h(c+vec2(1.0,0.0)))*step(0.5,h(c*1.3));
    float fade = clamp(1.0 - tt/600.0, 0.0, 1.0);
    col = mix(col, cloud_col, clamp(on,0.0,1.0)*0.85*fade);
  }
  frag = vec4(col,1.0);
}"""

UI_VS = """
#version 330
uniform vec4 rect; in vec2 in_pos; out vec2 uv;
void main(){ uv = vec2(in_pos.x*0.5+0.5, 0.5-in_pos.y*0.5);
  vec2 p = rect.xy + (in_pos*0.5+0.5)*rect.zw; gl_Position = vec4(p*2.0-1.0, 0.0, 1.0); }"""
UI_FS = """
#version 330
uniform sampler2D tex; uniform float alpha; in vec2 uv; out vec4 frag;
void main(){ vec4 c = texture(tex, vec2(uv.x, uv.y)); frag = vec4(c.rgb, c.a*alpha); }"""


FX_VS = """
#version 330
in vec2 in_pos; out vec2 uv;
void main(){ uv = in_pos*0.5+0.5; gl_Position = vec4(in_pos,0.0,1.0); }"""
FX_FS = """
#version 330
uniform sampler2D tex; uniform vec2 px; uniform float fade; in vec2 uv; out vec4 frag;
float L(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }
void main(){
  vec3 nw=texture(tex,uv+vec2(-1,-1)*px).rgb, ne=texture(tex,uv+vec2(1,-1)*px).rgb;
  vec3 sw=texture(tex,uv+vec2(-1,1)*px).rgb, se=texture(tex,uv+vec2(1,1)*px).rgb;
  vec3 m=texture(tex,uv).rgb;
  float a=L(nw), b=L(ne), c=L(sw), d=L(se), e=L(m);
  float mn=min(e,min(min(a,b),min(c,d))), mx=max(e,max(max(a,b),max(c,d)));
  vec2 dir = vec2(-((a+b)-(c+d)), (a+c)-(b+d));
  float red = max((a+b+c+d)*(0.25/8.0), 1.0/128.0);
  float rcp = 1.0/(min(abs(dir.x),abs(dir.y))+red);
  dir = clamp(dir*rcp, vec2(-8.0), vec2(8.0))*px;
  vec3 A = 0.5*(texture(tex,uv+dir*(1.0/3.0-0.5)).rgb+texture(tex,uv+dir*(2.0/3.0-0.5)).rgb);
  vec3 B = A*0.5+0.25*(texture(tex,uv-dir*0.5).rgb+texture(tex,uv+dir*0.5).rgb);
  float lb=L(B);
  vec3 col = (lb<mn||lb>mx)?A:B;
  // gentle vignette + grade
  vec2 q = uv-0.5; col *= 1.0 - dot(q,q)*0.35;
  frag = vec4(col*fade,1.0);
}"""

PT_VS = """
#version 330
uniform mat4 vp; in vec3 in_pos; in vec3 in_col; out vec3 col; out vec3 wpos;
void main(){ col = in_col; wpos = in_pos; gl_Position = vp*vec4(in_pos,1.0); }"""
PT_FS = """
#version 330
in vec3 col; in vec3 wpos; out vec4 frag; uniform vec3 cam; uniform vec3 fog_col;
void main(){ float d = length(wpos-cam); float f = clamp((d-18.0)/28.0,0.0,1.0);
  frag = vec4(mix(col, fog_col, f*f), 1.0); }"""

_CUBE = None


def cube_verts(cx, cy, cz, s, col):
    global _CUBE
    if _CUBE is None:
        c = []
        for (a, b, d) in ((0, 1, 2), (0, 2, 1), (1, 0, 2), (1, 2, 0), (2, 0, 1), (2, 1, 0)):
            pass
        q = np.array([[-1, -1, -1], [1, -1, -1], [1, 1, -1], [-1, 1, -1], [-1, -1, 1], [1, -1, 1], [1, 1, 1], [-1, 1, 1]], np.float32)
        idx = [0, 1, 2, 0, 2, 3, 4, 6, 5, 4, 7, 6, 0, 4, 5, 0, 5, 1, 3, 2, 6, 3, 6, 7, 0, 3, 7, 0, 7, 4, 1, 5, 6, 1, 6, 2]
        shade = [0.75] * 6 + [0.9] * 6 + [0.6] * 6 + [1.0] * 6 + [0.8] * 6 + [0.8] * 6
        _CUBE = (q[idx], np.array(shade, np.float32))
    p, sh = _CUBE
    v = np.empty((36, 6), np.float32)
    v[:, :3] = p * (s / 2) + (cx, cy, cz)
    v[:, 3:] = np.outer(sh, col)
    return v


TIMES = {
    "день": dict(sun=(0.45, 0.8, 0.35), sun_col=(0.62, 0.6, 0.55), amb=(0.62, 0.66, 0.75), zen=(0.33, 0.55, 0.95),
                 hor=(0.68, 0.82, 1.0), sunc=(1.0, 0.98, 0.85), stars=0.0, cloud=(1, 1, 1), torch=(0, 0, 0)),
    "закат": dict(sun=(0.85, 0.28, 0.3), sun_col=(0.75, 0.48, 0.3), amb=(0.5, 0.45, 0.55), zen=(0.25, 0.3, 0.6),
                  hor=(1.0, 0.6, 0.4), sunc=(1.0, 0.75, 0.4), stars=0.0, cloud=(1.0, 0.8, 0.75), torch=(0.25, 0.15, 0.0)),
    "ночь": dict(sun=(-0.4, 0.75, 0.3), sun_col=(0.18, 0.22, 0.35), amb=(0.2, 0.24, 0.38), zen=(0.02, 0.03, 0.1),
                 hor=(0.08, 0.1, 0.2), sunc=(0.85, 0.88, 0.95), stars=0.9, cloud=(0.2, 0.22, 0.3), torch=(0.9, 0.6, 0.2)),
}
TIMES["day"], TIMES["sunset"], TIMES["night"] = TIMES["день"], TIMES["закат"], TIMES["ночь"]
TIMES["вечер"] = TIMES["закат"]


_CTX = None


def shared_context():
    global _CTX
    if _CTX is None:
        _CTX = moderngl.create_standalone_context(backend="egl")
    return _CTX


class Renderer:
    def __init__(self, W, H, world, atlas, time_of_day="день", msaa=4):
        self.W, self.H = W, H
        ctx = self.ctx = shared_context()
        self.prog = ctx.program(vertex_shader=VS, fragment_shader=FS.replace("#version 330", "#version 330\n#define CUTOUT"))
        self.solid_prog = ctx.program(vertex_shader=VS, fragment_shader=FS)
        self.sh_prog = ctx.program(vertex_shader=SH_VS, fragment_shader=SH_FS)
        self.sky_prog = ctx.program(vertex_shader=SKY_VS, fragment_shader=SKY_FS)
        self.ui_prog = ctx.program(vertex_shader=UI_VS, fragment_shader=UI_FS)
        quad = np.array([-1, -1, 1, -1, -1, 1, 1, 1], np.float32)
        qb = ctx.buffer(quad.tobytes())
        self.sky_vao = ctx.vertex_array(self.sky_prog, [(qb, "2f", "in_pos")])
        self.ui_vao = ctx.vertex_array(self.ui_prog, [(qb, "2f", "in_pos")])
        self._qb = qb
        self.env = TIMES.get(time_of_day, TIMES["день"])
        sd = np.array(self.env["sun"], float); self.sun_dir = sd / np.linalg.norm(sd)
        fog = np.array(self.env["hor"]) * 0.95
        self.fog = fog

        # targets
        self.msaa = msaa
        self.fbo_ms = ctx.framebuffer(color_attachments=[ctx.renderbuffer((W, H), 4, samples=msaa)],
                                      depth_attachment=ctx.depth_renderbuffer((W, H), samples=msaa)) if msaa else None
        self.fbo = ctx.framebuffer(color_attachments=[ctx.renderbuffer((W, H), 4)])
        self.scene_tex = ctx.texture((W, H), 4)
        self.scene_tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.scene_tex.repeat_x = self.scene_tex.repeat_y = False
        self.fbo_scene = ctx.framebuffer(color_attachments=[self.scene_tex],
                                         depth_attachment=ctx.depth_renderbuffer((W, H)))
        self.fx_prog = ctx.program(vertex_shader=FX_VS, fragment_shader=FX_FS)
        SM = 2048
        self.sm_static = ctx.depth_texture((SM, SM)); self.sm_dyn = ctx.depth_texture((SM, SM))
        for t in (self.sm_static, self.sm_dyn):
            t.compare_func = "<="; t.repeat_x = t.repeat_y = False
            t.filter = (moderngl.LINEAR, moderngl.LINEAR)
        self.fbo_sm_static = ctx.framebuffer(depth_attachment=self.sm_static)
        self.fbo_sm_dyn = ctx.framebuffer(depth_attachment=self.sm_dyn)
        # light matrix centered on the stage
        c = np.array([0, 0, -2.0])
        self.lvp = ortho(-30, 30, -30, 30, 1, 120) @ look_at(c + self.sun_dir * 60, c,
                                                               up=(0, 0, 1) if abs(self.sun_dir[1]) > 0.99 else (0, 1, 0))
        # world
        self.atlas = ctx.texture((atlas.shape[1], atlas.shape[0]), 4, atlas.tobytes())
        self.atlas.filter = (moderngl.NEAREST, moderngl.NEAREST)
        op, wa = world.build_mesh()
        cutv = world.cutout
        self.world_sh = None
        # split the world into 12x12 column chunks for frustum culling; solid ones drawn front-to-back (early-z)
        def chunks_of(arr, prog):
            out = []
            if len(arr) == 0:
                return out
            tri = arr.reshape(-1, 3, 9)
            cx = np.floor(tri[:, :, 0].mean(1) / 12).astype(int); cz = np.floor(tri[:, :, 2].mean(1) / 12).astype(int)
            keys = cx * 1000 + cz
            for k in np.unique(keys):
                sel = tri[keys == k].reshape(-1, 9)
                lo = sel[:, :3].min(0); hi = sel[:, :3].max(0)
                corners = np.array([[x, y, z, 1] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])], np.float32)
                buf = self.ctx.buffer(np.ascontiguousarray(sel, np.float32).tobytes())
                vao = self.ctx.vertex_array(prog, [(buf, "3f 3f 2f 1f", "in_pos", "in_norm", "in_uv", "in_ao")])
                out.append((vao, corners, (lo + hi) / 2))
            return out
        self.chunks = chunks_of(op, self.solid_prog)
        self.cut_chunks = chunks_of(cutv, self.prog)
        allw = np.concatenate([op, cutv]) if len(cutv) else op
        self.water_vao = self._vao(wa, ao=True) if len(wa) else None
        self.world_sh = self._sh_vao(allw)
        self.world_n = len(allw)
        # static shadow pass
        self.fbo_sm_static.use(); self.fbo_sm_static.clear(depth=1.0)
        ctx.enable(moderngl.DEPTH_TEST)
        self.atlas.use(0)
        self.sh_prog["tex"] = 0
        self.sh_prog["lvp"].write(gl_mat(self.lvp)); self.sh_prog["model"].write(gl_mat(np.eye(4)))
        self.world_sh.render()
        self.chars = {}
        self.ui_tex = {}
        self.fx_vao = ctx.vertex_array(self.fx_prog, [(self._qb, "2f", "in_pos")])
        self.pt_prog = ctx.program(vertex_shader=PT_VS, fragment_shader=PT_FS)
        self.pt_buf = ctx.buffer(reserve=36 * 6 * 4 * 4000, dynamic=True)
        self.pt_vao = ctx.vertex_array(self.pt_prog, [(self.pt_buf, "3f 3f", "in_pos", "in_col")])
        hd = getattr(world, "house_door", None)
        self.torch_pos = (hd[0] + 0.5, 1.5, hd[1] + 1.5) if hd else (999.0, 0.0, 999.0)
        # item quad in right-arm local space (px): held in the fist, pointing forward
        q = []
        for dx in (-3.5, 3.5):  # X-shaped cross of two quads, visible from any angle
            a, b = (-6 - dx, -1), (-6 + dx, 7)
            q += [(a[0], 15, a[1], 0, 1, 0, 0, 0, 1), (b[0], 15, b[1], 0, 1, 0, 1, 0, 1), (b[0], 6, b[1], 0, 1, 0, 1, 1, 1),
                  (a[0], 15, a[1], 0, 1, 0, 0, 0, 1), (b[0], 6, b[1], 0, 1, 0, 1, 1, 1), (a[0], 6, a[1], 0, 1, 0, 0, 1, 1)]
        iq = np.array(q, np.float32)
        self.item_vao = self._vao(iq, ao=True)
        self.item_sh = self._sh_vao(iq)
        self.item_tex = {}

    def set_time(self, time_of_day):
        env = TIMES.get(time_of_day, TIMES["день"])
        if env is self.env:
            return
        self.env = env
        sd = np.array(env["sun"], float); self.sun_dir = sd / np.linalg.norm(sd)
        self.fog = np.array(env["hor"]) * 0.95
        c = np.array([0, 0, -2.0])
        self.lvp = ortho(-30, 30, -30, 30, 1, 120) @ look_at(c + self.sun_dir * 60, c)
        ctx = self.ctx
        self.fbo_sm_static.use(); self.fbo_sm_static.clear(depth=1.0)
        ctx.enable(moderngl.DEPTH_TEST)
        self.atlas.use(0)
        self.sh_prog["lvp"].write(gl_mat(self.lvp)); self.sh_prog["model"].write(gl_mat(np.eye(4)))
        self.world_sh.render()

    def get_item_tex(self, name):
        if name in self.item_tex:
            return self.item_tex[name]
        import os
        from PIL import Image
        from .world import ASSETS
        p = os.path.join(ASSETS, "items", name)
        if os.path.isdir(p):
            fs = sorted(os.listdir(p)); p = os.path.join(p, fs[0])
        elif not p.endswith(".png"):
            p += ".png"
        if not os.path.exists(p):
            p2 = os.path.join(ASSETS, "blocks", name + ".png")
            p = p2 if os.path.exists(p2) else None
        if p is None:
            self.item_tex[name] = None; return None
        im = Image.open(p).convert("RGBA"); im = im.crop((0, 0, im.size[0], im.size[0]))
        t = self.ctx.texture(im.size, 4, im.tobytes()); t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        self.item_tex[name] = t
        return t

    def add_mob(self, name, kind):
        from .mobs import load_mob
        parts, tex = load_mob(kind)
        t = self.ctx.texture((64, 64), 4, np.ascontiguousarray(tex).tobytes())
        t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        P = {}
        for bn, b in parts.items():
            v = b["verts"]
            v = np.concatenate([v, np.ones((len(v), 1), np.float32)], axis=1)
            P[bn] = (b["pivot"], self._vao(v, ao=True), self._sh_vao(v), len(v))
        self.chars[name] = dict(texs={(0, 0): t}, parts=P, mob=kind, bones=parts)

    @staticmethod
    def mob_matrices(pose, ch):
        g = pose.get
        root = T(g("x"), g("y"), g("z")) @ Ry(g("yaw")) @ S(1 / 16) @ T(0, g("oy", 0), 0)
        M = {}
        legs_a = {"leg0": "rl_p", "leg3": "rl_p", "leg1": "ll_p", "leg2": "ll_p"}
        for bn, b in ch["bones"].items():
            piv = b["pivot"]; rx, ry, rz = b["rest"]
            A = np.eye(4, dtype=np.float32)
            if bn in ("head", "beak", "comb"):
                A = Ry(g("h_y", 0)) @ Rx(-g("h_p", 0))
            elif bn in legs_a:
                A = Rx(-g(legs_a[bn], 0))
            elif bn == "wing0":
                A = Rz(g("ra_r", 0))
            elif bn == "wing1":
                A = Rz(-g("la_r", 0))
            elif bn == "tail":
                A = Ry(0.3 * math.sin(g("t", 0) * 6))
            parent = M.get(b.get("parent")) if b.get("parent") else root
            if parent is None:
                parent = root
            M[bn] = parent @ T(*piv) @ A @ Rz(rz) @ Ry(ry) @ Rx(rx) @ T(*[-c for c in piv])
        return M

    def _vao(self, v, ao=False):
        buf = self.ctx.buffer(np.ascontiguousarray(v, np.float32).tobytes())
        if ao:
            return self.ctx.vertex_array(self.prog, [(buf, "3f 3f 2f 1f", "in_pos", "in_norm", "in_uv", "in_ao")])
        return self.ctx.vertex_array(self.prog, [(buf, "3f 3f 2f", "in_pos", "in_norm", "in_uv")])

    def _sh_vao(self, v):
        buf = self.ctx.buffer(np.ascontiguousarray(v, np.float32).tobytes())
        stride = "3f 3x4 2f 1x4" if v.shape[1] == 9 else "3f 3x4 2f"
        return self.ctx.vertex_array(self.sh_prog, [(buf, stride, "in_pos", "in_uv")])

    # ----------------------------------------------------------- characters
    def add_character(self, name, variants, slim):
        ctx = self.ctx
        texs = {}
        for k, arr in variants.items():
            t = ctx.texture((64, 64), 4, np.ascontiguousarray(arr).tobytes())
            t.filter = (moderngl.NEAREST, moderngl.NEAREST)
            texs[k] = t
        parts = {}
        for pname, (pivot, base, over) in player_parts(slim).items():
            v = np.concatenate([base, over])
            v = np.concatenate([v, np.ones((len(v), 1), np.float32)], axis=1)
            parts[pname] = (pivot, self._vao(v, ao=True), self._sh_vao(v), len(v))
        self.chars[name] = dict(texs=texs, parts=parts)

    @staticmethod
    def part_matrices(pose, pivots):
        """pose: dict with x,y,z,yaw and joint angles (radians)."""
        g = pose.get
        root = T(g("x"), g("y"), g("z")) @ Ry(g("yaw")) @ Rx(g("r_p", 0)) @ S(1 / 16 * 0.9375)
        root = root @ T(g("ox", 0) * 16, g("oy", 0), 0)
        bp = pivots["body"]
        body_m = root @ T(*bp) @ Ry(g("b_y", 0)) @ Rx(g("b_p", 0)) @ Rz(g("b_r", 0)) @ T(*[-c for c in bp])
        M = {"body": body_m}
        hp = pivots["head"]
        M["head"] = body_m @ T(*hp) @ Ry(g("h_y", 0)) @ Rx(-g("h_p", 0)) @ Rz(g("h_r", 0)) @ T(*[-c for c in hp])
        for side, sgn in (("r", -1), ("l", 1)):
            ap = pivots[side + "arm"]
            M[side + "arm"] = body_m @ T(*ap) @ Ry(g(side + "a_y", 0) * -sgn) @ Rx(-g(side + "a_p", 0)) \
                @ Rz(g(side + "a_r", 0) * sgn) @ T(*[-c for c in ap])
            lp = pivots[side + "leg"]
            M[side + "leg"] = root @ T(*lp) @ Rx(-g(side + "l_p", 0)) @ Rz(g(side + "l_r", 0) * sgn) @ T(*[-c for c in lp])
        return M

    # --------------------------------------------------------------- frame
    def render(self, cam_eye, cam_target, fov, poses, time, overlays=(), roll=0.0, fade=1.0, particles=()):
        ctx = self.ctx
        W, H = self.W, self.H
        view = look_at(cam_eye, cam_target, up=(math.sin(roll), math.cos(roll), 0))
        proj = perspective(fov, W / H, 0.08, 220)
        vp = proj @ view
        # matrices for characters
        drawlist = []
        items = []
        for pose in poses:
            if pose.get("hidden"):
                continue
            ch = self.chars[pose["name"]]
            if ch.get("mob"):
                M = self.mob_matrices(pose, ch)
                tex = ch["texs"][(0, 0)]
            else:
                piv = {k: v[0] for k, v in ch["parts"].items()}
                M = self.part_matrices(pose, piv)
                tex = ch["texs"][(pose.get("blink", 0), pose.get("mouth", 0))]
                if pose.get("item"):
                    it = self.get_item_tex(pose["item"])
                    if it is not None:
                        items.append((M["rarm"], it))
            drawlist.append((ch, M, tex))
        # dynamic shadows
        self.fbo_sm_dyn.use(); self.fbo_sm_dyn.clear(depth=1.0)
        ctx.enable(moderngl.DEPTH_TEST); ctx.disable(moderngl.CULL_FACE)
        self.sh_prog["lvp"].write(gl_mat(self.lvp))
        for ch, M, tex in drawlist:
            tex.use(0)
            for pname, (pivot, vao, shvao, n) in ch["parts"].items():
                self.sh_prog["model"].write(gl_mat(M[pname])); shvao.render()
        for M, it in items:
            it.use(0); self.sh_prog["model"].write(gl_mat(M)); self.item_sh.render()
        # main pass
        target = self.fbo_ms or self.fbo_scene
        target.use(); target.clear(0, 0, 0, 1, depth=1.0)
        ctx.viewport = (0, 0, W, H)
        e = self.env
        # sky
        ctx.disable(moderngl.DEPTH_TEST)
        sp = self.sky_prog
        sp["inv_vp"].write(gl_mat(np.linalg.inv(vp).astype(np.float32)))
        sp["cam"].value = tuple(cam_eye); sp["sun_dir"].value = tuple(self.sun_dir)
        sp["zenith"].value = e["zen"]; sp["horizon"].value = e["hor"]; sp["sun_color"].value = e["sunc"]
        sp["time"].value = time; sp["stars"].value = e["stars"]; sp["cloud_col"].value = e["cloud"]
        self.sky_vao.render(moderngl.TRIANGLE_STRIP)
        ctx.enable(moderngl.DEPTH_TEST)
        for p in (self.prog, self.solid_prog):
            def setu(name, val, p=p):
                if name in p:
                    p[name].value = val
            p["vp"].write(gl_mat(vp)); p["lvp"].write(gl_mat(self.lvp))
            setu("sun_dir", tuple(self.sun_dir)); setu("sun_col", e["sun_col"]); setu("amb_col", e["amb"])
            setu("fog_col", tuple(self.fog)); setu("cam", tuple(cam_eye))
            setu("fog_near", 18.0); setu("fog_far", 46.0)
            setu("flat_light", 0.0); setu("torch_col", e["torch"]); setu("torch_pos", self.torch_pos)
            setu("tex", 0); setu("sh_static", 1); setu("sh_dyn", 2)
            setu("alpha_mul", 1.0)
            p["model"].write(gl_mat(np.eye(4)))
        p = self.prog
        self.sm_static.use(1); self.sm_dyn.use(2)
        self.atlas.use(0)
        ce = np.array(cam_eye)

        def visible(corners):
            c = corners @ vp.T
            w = c[:, 3]
            return not (np.all(c[:, 0] < -w) or np.all(c[:, 0] > w) or np.all(c[:, 1] < -w)
                        or np.all(c[:, 1] > w) or np.all(w < 0.05))
        for vao, corners, mid in sorted(self.chunks, key=lambda ch: float(np.sum((ch[2] - ce) ** 2))):
            if visible(corners):
                vao.render()
        for vao, corners, mid in self.cut_chunks:
            if visible(corners):
                vao.render()
        for ch, M, tex in drawlist:
            tex.use(0)
            for pname, (pivot, vao, shvao, n) in ch["parts"].items():
                p["model"].write(gl_mat(M[pname])); vao.render()
        for M, it in items:
            it.use(0); p["model"].write(gl_mat(M)); self.item_vao.render()
        if particles:
            pv = np.concatenate([cube_verts(*pp) for pp in particles[:4000]])
            self.pt_buf.orphan(pv.nbytes); self.pt_buf.write(pv.tobytes())
            self.pt_prog["vp"].write(gl_mat(vp)); self.pt_prog["cam"].value = tuple(cam_eye)
            self.pt_prog["fog_col"].value = tuple(self.fog)
            self.pt_vao.render(vertices=len(pv))
        if self.water_vao:
            ctx.enable(moderngl.BLEND); ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
            ctx.depth_mask = False
            self.atlas.use(0); p["alpha_mul"].value = 0.8; p["model"].write(gl_mat(np.eye(4)))
            self.water_vao.render()
            ctx.depth_mask = True; ctx.disable(moderngl.BLEND)
        if self.fbo_ms:
            ctx.copy_framebuffer(self.fbo_scene, self.fbo_ms)
        # FXAA + grade into output
        ctx.disable(moderngl.DEPTH_TEST)
        self.fbo.use()
        self.scene_tex.use(0); self.fx_prog["tex"] = 0
        self.fx_prog["px"].value = (1.0 / W, 1.0 / H); self.fx_prog["fade"].value = fade
        self.fx_vao.render(moderngl.TRIANGLE_STRIP)
        # UI overlays
        ctx.enable(moderngl.BLEND); ctx.blend_func = moderngl.SRC_ALPHA, moderngl.ONE_MINUS_SRC_ALPHA
        for key, img, rect, alpha in overlays:
            tex = self.ui_tex.get(key)
            if tex is None:
                tex = self.ctx.texture((img.shape[1], img.shape[0]), 4, np.ascontiguousarray(img).tobytes())
                tex.filter = (moderngl.LINEAR, moderngl.LINEAR)
                self.ui_tex[key] = tex
                if len(self.ui_tex) > 40:
                    k0 = next(iter(self.ui_tex)); self.ui_tex.pop(k0).release()
            tex.use(0); self.ui_prog["tex"] = 0
            self.ui_prog["rect"].value = rect; self.ui_prog["alpha"].value = alpha
            self.ui_vao.render(moderngl.TRIANGLE_STRIP)
        ctx.disable(moderngl.BLEND)
        return self.fbo.read(components=3, alignment=1)
