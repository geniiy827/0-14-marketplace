"""Reference player for exported Story Craft scenes: reads glb + tracks exactly like the game should,
renders one frame to PNG. Usage: python3 tools/verify_export.py EPISODE_DIR SCENE_ID FRAME out.png"""
import io, json, math, sys
import numpy as np, moderngl, pygltflib
from PIL import Image

def acc(g, blob, i):
    a = g.accessors[i]; bv = g.bufferViews[a.bufferView]
    n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a.type]
    return np.frombuffer(blob, np.float32, a.count * n, bv.byteOffset + (a.byteOffset or 0)).reshape(a.count, n)

def quat_mat(q):
    x, y, z, w = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],
                     [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])

def trs(t, r, s):
    M = np.eye(4); M[:3, :3] = quat_mat(r) * np.asarray(s); M[:3, 3] = t; return M

def load(path):
    g = pygltflib.GLTF2().load(path); blob = g.binary_blob()
    texs = []
    for im in g.images:
        bv = g.bufferViews[im.bufferView]
        texs.append(np.array(Image.open(io.BytesIO(blob[bv.byteOffset:bv.byteOffset + bv.byteLength])).convert("RGBA")))
    return g, blob, texs

def main(ep, sid, frame, out):
    E = json.load(open(f"{ep}/episode.json")); sc = E["scenes"][sid]; tr = json.load(open(f"{ep}/{sc['tracks']}"))
    ctx = moderngl.create_standalone_context(backend="egl")
    prog = ctx.program(vertex_shader="""#version 330
uniform mat4 mvp; in vec3 p; in vec2 uv; in vec4 c; out vec2 v_uv; out vec4 v_c;
void main(){ v_uv=uv; v_c=c; gl_Position=mvp*vec4(p,1.0);} """, fragment_shader="""#version 330
uniform sampler2D tex; in vec2 v_uv; in vec4 v_c; out vec4 f;
void main(){ vec4 t=texture(tex,v_uv); if(t.a<0.5) discard; f=vec4(t.rgb*v_c.rgb,1.0);} """)
    W, H = 960, 540
    fbo = ctx.simple_framebuffer((W, H)); fbo.use(); fbo.clear(0.6, 0.75, 1.0, 1.0); ctx.enable(moderngl.DEPTH_TEST)
    items = []
    for path, animated in ((f"{ep}/{E['worlds'][sc['world']]['glb']}", False), (f"{ep}/{sc['glb']}", True)):
        g, blob, texs = load(path)
        gtex = [ctx.texture((t.shape[1], t.shape[0]), 4, t.tobytes()) for t in texs]
        for t in gtex: t.filter = (moderngl.NEAREST, moderngl.NEAREST)
        node_M = {}
        anim = {}
        if animated and g.animations:
            a = g.animations[0]
            for ch in a.channels:
                smp = a.samplers[ch.sampler]
                # sample by time (channels may carry extra keys at hard cuts)
                ts = acc(g, blob, smp.input).reshape(-1); vs = acc(g, blob, smp.output)
                tt = frame / float(tr.get("fps", 15)) + 1e-4
                k = int(np.searchsorted(ts, tt, side="right") - 1); k = max(0, min(k, len(vs) - 1))
                anim.setdefault(ch.target.node, {})[ch.target.path] = vs[k]
        for ni, nd in enumerate(g.nodes):
            t = anim.get(ni, {}).get("translation", nd.translation or [0, 0, 0])
            r = anim.get(ni, {}).get("rotation", nd.rotation or [0, 0, 0, 1])
            s = anim.get(ni, {}).get("scale", nd.scale or [1, 1, 1])
            node_M[ni] = trs(t, r, s)
            if nd.camera is not None:
                cam_M = node_M[ni]
            if nd.mesh is None: continue
            pr = g.meshes[nd.mesh].primitives[0]
            P = acc(g, blob, pr.attributes.POSITION); UV = acc(g, blob, pr.attributes.TEXCOORD_0)
            C = acc(g, blob, pr.attributes.COLOR_0) if pr.attributes.COLOR_0 is not None else np.ones((len(P), 4), np.float32)
            mat = g.materials[pr.material]; ti = g.textures[mat.pbrMetallicRoughness.baseColorTexture.index].source
            # character faces: swap texture from track (like the game will)
            tex = gtex[ti]
            ex = nd.extras or {}
            cid = ex.get("cast")
            if cid and cid in tr["face"] and ex.get("part") in ("head",):
                k = tr["face"][cid][frame]
                fimg = np.array(Image.open(f"{ep}/textures/{cid}_face{k}.png").convert("RGBA"))
                tex = ctx.texture((64, 64), 4, fimg.tobytes()); tex.filter = (moderngl.NEAREST, moderngl.NEAREST)
            items.append((node_M[ni], P, UV, C, tex))
    fov = tr["fov"][frame]
    V = np.linalg.inv(cam_M)
    f = 1 / math.tan(math.radians(fov) / 2); n_, far = 0.08, 220
    Pm = np.array([[f / (W / H), 0, 0, 0], [0, f, 0, 0], [0, 0, (far + n_) / (n_ - far), 2 * far * n_ / (n_ - far)], [0, 0, -1, 0]])
    for M, P, UV, C, tex in items:
        v = np.concatenate([P, UV, C], 1).astype(np.float32)
        vao = ctx.vertex_array(prog, [(ctx.buffer(v.tobytes()), "3f 2f 4f", "p", "uv", "c")])
        prog["mvp"].write((Pm @ V @ M).T.astype(np.float32).tobytes()); tex.use(0); prog["tex"] = 0
        vao.render()
    Image.frombytes("RGB", (W, H), fbo.read(components=3)).transpose(Image.FLIP_TOP_BOTTOM).save(out)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4])
