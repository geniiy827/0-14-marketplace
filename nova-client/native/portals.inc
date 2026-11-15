/* portals.inc - see-through portals with seamless crossing (the way Immersive Portals does it on Java, done for 0.14).
 *
 * A portal is a pair of rectangles (ends A and B) in one dimension. Standing in front of an end you see the world
 * beyond the other end through it, and walking through puts you there with no screen in between.
 *
 * How 0.14 draws the world (the facts this is built on):
 *   InGamePlayScreen::_renderedFramedItems(Vec3 cam, LevelRenderer&, Entity& camera, float a) (vtable slot 77)
 *     builds two FrustumCullers {FrustumData 0x4c0, Vec3 camera} with _prepareCuller (slot 86, from the matrices) and
 *     calls LevelRenderer::renderLevel(Entity&, culler, culler, lr+0x1634, a):
 *       updateViewArea(entity) - recentres the ring of chunk meshes (skipped in our pass: it would rebuild them)
 *       startFrame - camera = the camera entity's interpolated position (stored lr+0x1bb4), visibility from there
 *       renderChunks - World matrix translate(-camera): the world is drawn camera-relative; View = rotation (+bob)
 *   MatrixStack::View / Projection (16 bytes: vector of mat4 + dirty byte +0xc; getTop marks it dirty).
 *   The EGL config asks for a stencil buffer (EGL_STENCIL_SIZE 1).
 *
 * One pass per visible end, after the game's own world:
 *   1. the end's rectangle into the stencil (depth-tested: what stands in front of it hides it);
 *   2. inside the stencil: depth back to far, colour = the sky's clear colour;
 *   3. View rotated by the portal turn, Projection with its near plane on the exit (oblique frustum: nothing behind
 *      the exit is drawn), the camera entity moved to the virtual eye, cullers from these matrices, renderLevel
 *      with the stencil test on: the world beyond appears only inside the rectangle.
 * Crossing: when the eye passes an end's plane inside the rectangle, the player is moved (Entity::moveTo: position,
 * previous position, rotation at once - no interpolation) with velocity and yaw turned; the frame is drawn already
 * from the new place.
 * Every GL state we touch is read first and put back: the game keeps its own state cache.
 * A crash inside a pass turns drawing off for the session (crossing keeps working). */

typedef struct { float o[3]; int face; } PEnd;          /* o: bottom centre of the rectangle; face 0 S, 1 W, 2 N, 3 E */
typedef struct { int used, dim; PEnd e[2]; float w, h; } Portal;
#define PT_MAX 16
static Portal pt[PT_MAX];
static int pt_render_on = 1, pt_view_hooked, pt_broken, pt_inpass, pt_ok, pt_gl_ok = -1, pt_stencil_bits, pt_passes, pt_last_us;
static float pt_prev_eye[3]; static int pt_prev_valid; static void *pt_prev_ent;

static void (*o_pt_rfi)(void *scr, float x, float y, float z, void *lr, void *ent, float a);
static void (*pt_render_level)(void *lr, void *ent, void *c1, void *c2, float f, float a);
static void (*pt_frustum_ctor)(void *fd);
static float *(*pt_top)(void *stack);
static void *pt_view, *pt_proj;
static void (*pt_move_to)(void *ent, float *pos, float *rot);
static void *(*pt_local_player)(void *client);

/* ---------------------------------------------------------------- geometry ---- */
static const float pt_n[4][3] = {{0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {1, 0, 0}};   /* the side the end faces */
static void pt_u(int f, float u[3]) { u[0] = -pt_n[f][2]; u[1] = 0; u[2] = pt_n[f][0]; }
static float pt_turn(PEnd *a, PEnd *b) { return (float)((b->face - a->face) * 90 + 180); }   /* yaw added going a -> b */
static void pt_rot(float deg, const float *v, float *o) {               /* the yaw turn: dir(yaw) -> dir(yaw + deg) */
    float r = deg * 0.0174532925f, c = cosf(r), s = sinf(r), x = v[0], z = v[2];
    o[0] = x * c - z * s; o[1] = v[1]; o[2] = x * s + z * c;
}
static void pt_map(PEnd *a, PEnd *b, const float *p, float *o) {          /* a point near a -> the same place at b */
    float d[3] = {p[0] - a->o[0], p[1] - a->o[1], p[2] - a->o[2]}, r[3];
    pt_rot(pt_turn(a, b), d, r);
    o[0] = b->o[0] + r[0]; o[1] = b->o[1] + r[1]; o[2] = b->o[2] + r[2];
}
static float pt_side(PEnd *e, const float *p) { return (p[0] - e->o[0]) * pt_n[e->face][0] + (p[2] - e->o[2]) * pt_n[e->face][2]; }
static int pt_inside(Portal *P, PEnd *e, const float *p, float pad) {
    float u[3];
    pt_u(e->face, u);
    float lu = (p[0] - e->o[0]) * u[0] + (p[2] - e->o[2]) * u[2], ly = p[1] - e->o[1];
    return lu >= -P->w / 2 - pad && lu <= P->w / 2 + pad && ly >= -pad && ly <= P->h + pad;
}
static void pt_corners(Portal *P, PEnd *e, float c[4][3]) {
    float u[3];
    pt_u(e->face, u);
    float hw = P->w / 2;
    for (int k = 0; k < 4; k++) {
        float su = k == 0 || k == 3 ? -hw : hw, sy = k < 2 ? 0 : P->h;
        c[k][0] = e->o[0] + u[0] * su; c[k][1] = e->o[1] + sy; c[k][2] = e->o[2] + u[2] * su;
    }
}
/* glm mat4, column major: m[col * 4 + row] */
static void pt_mul(const float *a, const float *b, float *o) {
    float t[16];
    for (int c = 0; c < 4; c++) for (int r = 0; r < 4; r++) {
        float s = 0;
        for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
        t[c * 4 + r] = s;
    }
    memcpy(o, t, sizeof t);
}
static void pt_xform(const float *m, const float *v, float w, float *o) {
    for (int r = 0; r < 4; r++) o[r] = m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r] * w;
}
/* the turn as a matrix acting on camera-relative world vectors, inverted: V' = V * this */
static void pt_rot_inv_mat(float deg, float *m) {
    float r = -deg * 0.0174532925f, c = cosf(r), s = sinf(r);
    memset(m, 0, 64);
    /* x' = x c - z s ; z' = x s + z c  (columns: images of the axes) */
    m[0] = c; m[2] = s; m[5] = 1; m[8] = -s; m[10] = c; m[15] = 1;
}
/* oblique near plane (Lengyel): clip plane C (camera space, the camera on its negative side) becomes the near plane */
static void pt_oblique(float *P, const float *C) {
    float q[4];
    q[0] = ((C[0] > 0 ? 1.f : C[0] < 0 ? -1.f : 0.f) + P[8]) / P[0];
    q[1] = ((C[1] > 0 ? 1.f : C[1] < 0 ? -1.f : 0.f) + P[9]) / P[5];
    q[2] = -1.f;
    q[3] = (1.f + P[10]) / P[14];
    float d = C[0] * q[0] + C[1] * q[1] + C[2] * q[2] + C[3] * q[3];
    if (d > -1e-6f && d < 1e-6f) return;
    float k = 2.f / d;
    P[2] = C[0] * k; P[6] = C[1] * k; P[10] = C[2] * k + 1.f; P[14] = C[3] * k;
}

/* ---------------------------------------------------------------- GL ---- */
typedef unsigned int GLuint; typedef int GLint; typedef unsigned int GLenum;
#define GL_STENCIL_TEST 0x0B90
#define GL_DEPTH_TEST 0x0B71
#define GL_BLEND 0x0BE2
#define GL_CULL_FACE 0x0B44
#define GL_SCISSOR_TEST 0x0C11
#define GL_STENCIL_BITS 0x0D57
#define GL_STENCIL_BUFFER_BIT 0x400
#define GL_ALWAYS 0x0207
#define GL_EQUAL 0x0202
#define GL_LEQUAL 0x0203
#define GL_KEEP 0x1E00
#define GL_REPLACE 0x1E01
#define GL_CURRENT_PROGRAM 0x8B8D
#define GL_ARRAY_BUFFER 0x8892
#define GL_ARRAY_BUFFER_BINDING 0x8894
#define GL_DEPTH_FUNC 0x0B74
#define GL_DEPTH_WRITEMASK 0x0B72
#define GL_COLOR_WRITEMASK 0x0C23
#define GL_COLOR_CLEAR_VALUE 0x0C22
#define GL_STENCIL_FUNC 0x0B92
#define GL_STENCIL_REF 0x0B97
#define GL_STENCIL_VALUE_MASK 0x0B93
#define GL_STENCIL_WRITEMASK 0x0B98
#define GL_STENCIL_FAIL 0x0B94
#define GL_STENCIL_PASS_DEPTH_FAIL 0x0B95
#define GL_STENCIL_PASS_DEPTH_PASS 0x0B96
#define GL_STENCIL_CLEAR_VALUE 0x0B91
#define GL_VERTEX_ATTRIB_ARRAY_ENABLED 0x8622
#define GL_VERTEX_ATTRIB_ARRAY_SIZE 0x8623
#define GL_VERTEX_ATTRIB_ARRAY_STRIDE 0x8624
#define GL_VERTEX_ATTRIB_ARRAY_TYPE 0x8625
#define GL_VERTEX_ATTRIB_ARRAY_NORMALIZED 0x886A
#define GL_VERTEX_ATTRIB_ARRAY_POINTER 0x8645
#define GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING 0x889F
#define GL_FLOAT 0x1406
#define GL_TRIANGLE_FAN 0x0006
#define GL_VERTEX_SHADER 0x8B31
#define GL_FRAGMENT_SHADER 0x8B30
#define GL_COMPILE_STATUS 0x8B81
#define GL_LINK_STATUS 0x8B82
static struct {
    void (*Enable)(GLenum), (*Disable)(GLenum), (*GetIntegerv)(GLenum, GLint *), (*GetFloatv)(GLenum, float *);
    unsigned char (*IsEnabled)(GLenum);
    void (*StencilFunc)(GLenum, GLint, GLuint), (*StencilOp)(GLenum, GLenum, GLenum), (*StencilMask)(GLuint);
    void (*ClearStencil)(GLint), (*Clear)(GLuint), (*ColorMask)(unsigned char, unsigned char, unsigned char, unsigned char);
    void (*DepthMask)(unsigned char), (*DepthFunc)(GLenum), (*UseProgram)(GLuint), (*BindBuffer)(GLenum, GLuint);
    void (*VertexAttribPointer)(GLuint, GLint, GLenum, unsigned char, GLint, const void *);
    void (*EnableVertexAttribArray)(GLuint), (*DisableVertexAttribArray)(GLuint);
    void (*GetVertexAttribiv)(GLuint, GLenum, GLint *), (*GetVertexAttribPointerv)(GLuint, GLenum, void **);
    void (*DrawArrays)(GLenum, GLint, GLint);
    GLuint (*CreateShader)(GLenum), (*CreateProgram)(void);
    void (*ShaderSource)(GLuint, GLint, const char **, const GLint *), (*CompileShader)(GLuint), (*AttachShader)(GLuint, GLuint);
    void (*BindAttribLocation)(GLuint, GLuint, const char *), (*LinkProgram)(GLuint);
    void (*GetShaderiv)(GLuint, GLenum, GLint *), (*GetProgramiv)(GLuint, GLenum, GLint *);
    void (*GetShaderInfoLog)(GLuint, GLint, GLint *, char *);
    GLint (*GetUniformLocation)(GLuint, const char *);
    void (*UniformMatrix4fv)(GLint, GLint, unsigned char, const float *), (*Uniform4f)(GLint, float, float, float, float);
    void (*Uniform1f)(GLint, float);
} gl;
static GLuint pt_prog; static GLint pt_u_mvp, pt_u_col, pt_u_far;

static int pt_gl_init(void) {
    if (pt_gl_ok >= 0) return pt_gl_ok;
    pt_gl_ok = 0;
    void *g = dlopen("libGLESv2.so", 0x2);
    if (!g) { LOGE("portals: no libGLESv2"); return 0; }
    #define G(f) *(void **)&gl.f = dlsym(g, "gl" #f); if (!gl.f) { LOGE("portals: no gl" #f); return 0; }
    G(Enable) G(Disable) G(GetIntegerv) G(GetFloatv) G(IsEnabled) G(StencilFunc) G(StencilOp) G(StencilMask)
    G(ClearStencil) G(Clear) G(ColorMask) G(DepthMask) G(DepthFunc) G(UseProgram) G(BindBuffer) G(VertexAttribPointer)
    G(EnableVertexAttribArray) G(DisableVertexAttribArray) G(GetVertexAttribiv) G(GetVertexAttribPointerv) G(DrawArrays)
    G(CreateShader) G(CreateProgram) G(ShaderSource) G(CompileShader) G(AttachShader) G(BindAttribLocation) G(LinkProgram)
    G(GetShaderiv) G(GetProgramiv) G(GetShaderInfoLog) G(GetUniformLocation) G(UniformMatrix4fv) G(Uniform4f) G(Uniform1f)
    #undef G
    gl.GetIntegerv(GL_STENCIL_BITS, &pt_stencil_bits);
    if (pt_stencil_bits <= 0) { LOGE("portals: the screen has no stencil buffer, see-through is off"); return 0; }
    static const char *vs = "attribute vec3 p; uniform mat4 mvp; uniform float far;\n"
                            "void main() { vec4 c = mvp * vec4(p, 1.0); if (far > 0.5) c.z = c.w * 0.99999; gl_Position = c; }\n";
    static const char *fs = "precision mediump float; uniform vec4 col; void main() { gl_FragColor = col; }\n";
    GLuint s[2] = {gl.CreateShader(GL_VERTEX_SHADER), gl.CreateShader(GL_FRAGMENT_SHADER)};
    const char *src[2] = {vs, fs};
    for (int i = 0; i < 2; i++) {
        gl.ShaderSource(s[i], 1, &src[i], 0);
        gl.CompileShader(s[i]);
        GLint ok = 0;
        gl.GetShaderiv(s[i], GL_COMPILE_STATUS, &ok);
        if (!ok) { char log[256]; gl.GetShaderInfoLog(s[i], 255, 0, log); log[255] = 0; LOGE("portals: shader: %s", log); return 0; }
    }
    pt_prog = gl.CreateProgram();
    gl.AttachShader(pt_prog, s[0]);
    gl.AttachShader(pt_prog, s[1]);
    gl.BindAttribLocation(pt_prog, 0, "p");
    gl.LinkProgram(pt_prog);
    GLint ok = 0;
    gl.GetProgramiv(pt_prog, GL_LINK_STATUS, &ok);
    if (!ok) { LOGE("portals: program does not link"); return 0; }
    pt_u_mvp = gl.GetUniformLocation(pt_prog, "mvp");
    pt_u_col = gl.GetUniformLocation(pt_prog, "col");
    pt_u_far = gl.GetUniformLocation(pt_prog, "far");
    pt_gl_ok = 1;
    LOG("portals: GL ready, stencil %d bits", pt_stencil_bits);
    return 1;
}

typedef struct {
    GLint prog, abuf, a_en, a_size, a_type, a_norm, a_stride, a_buf, dfunc, dmask, cmask[4];
    GLint sfunc, sref, svmask, swmask, sfail, szfail, szpass;
    void *a_ptr;
    unsigned char depth, blend, cull, stencil, scissor;
} PtGl;
static void pt_gl_save(PtGl *s) {
    gl.GetIntegerv(GL_CURRENT_PROGRAM, &s->prog);
    gl.GetIntegerv(GL_ARRAY_BUFFER_BINDING, &s->abuf);
    gl.GetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_ENABLED, &s->a_en);
    gl.GetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_SIZE, &s->a_size);
    gl.GetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_TYPE, &s->a_type);
    gl.GetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_NORMALIZED, &s->a_norm);
    gl.GetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_STRIDE, &s->a_stride);
    gl.GetVertexAttribiv(0, GL_VERTEX_ATTRIB_ARRAY_BUFFER_BINDING, &s->a_buf);
    gl.GetVertexAttribPointerv(0, GL_VERTEX_ATTRIB_ARRAY_POINTER, &s->a_ptr);
    gl.GetIntegerv(GL_DEPTH_FUNC, &s->dfunc);
    gl.GetIntegerv(GL_DEPTH_WRITEMASK, &s->dmask);
    gl.GetIntegerv(GL_COLOR_WRITEMASK, s->cmask);
    gl.GetIntegerv(GL_STENCIL_FUNC, &s->sfunc);
    gl.GetIntegerv(GL_STENCIL_REF, &s->sref);
    gl.GetIntegerv(GL_STENCIL_VALUE_MASK, &s->svmask);
    gl.GetIntegerv(GL_STENCIL_WRITEMASK, &s->swmask);
    gl.GetIntegerv(GL_STENCIL_FAIL, &s->sfail);
    gl.GetIntegerv(GL_STENCIL_PASS_DEPTH_FAIL, &s->szfail);
    gl.GetIntegerv(GL_STENCIL_PASS_DEPTH_PASS, &s->szpass);
    s->depth = gl.IsEnabled(GL_DEPTH_TEST); s->blend = gl.IsEnabled(GL_BLEND); s->cull = gl.IsEnabled(GL_CULL_FACE);
    s->stencil = gl.IsEnabled(GL_STENCIL_TEST); s->scissor = gl.IsEnabled(GL_SCISSOR_TEST);
}
static void pt_en(GLenum c, int on) { if (on) gl.Enable(c); else gl.Disable(c); }
/* everything but the stencil test, which the caller sets */
static void pt_gl_restore(PtGl *s) {
    gl.UseProgram((GLuint)s->prog);
    gl.BindBuffer(GL_ARRAY_BUFFER, (GLuint)s->a_buf);
    gl.VertexAttribPointer(0, s->a_size ? s->a_size : 4, s->a_type ? (GLenum)s->a_type : GL_FLOAT, (unsigned char)s->a_norm, s->a_stride, s->a_ptr);
    if (s->a_en) gl.EnableVertexAttribArray(0); else gl.DisableVertexAttribArray(0);
    gl.BindBuffer(GL_ARRAY_BUFFER, (GLuint)s->abuf);
    gl.DepthFunc((GLenum)s->dfunc);
    gl.DepthMask((unsigned char)s->dmask);
    gl.ColorMask((unsigned char)s->cmask[0], (unsigned char)s->cmask[1], (unsigned char)s->cmask[2], (unsigned char)s->cmask[3]);
    gl.StencilFunc((GLenum)s->sfunc, s->sref, (GLuint)s->svmask);
    gl.StencilOp((GLenum)s->sfail, (GLenum)s->szfail, (GLenum)s->szpass);
    gl.StencilMask((GLuint)s->swmask);
    pt_en(GL_DEPTH_TEST, s->depth); pt_en(GL_BLEND, s->blend); pt_en(GL_CULL_FACE, s->cull); pt_en(GL_SCISSOR_TEST, s->scissor);
}
static void pt_quad(const float *mvp, float c[4][3], const float *cam, int far, const float *col) {
    float v[12];
    for (int k = 0; k < 4; k++) for (int i = 0; i < 3; i++) v[k * 3 + i] = c[k][i] - cam[i];
    gl.UniformMatrix4fv(pt_u_mvp, 1, 0, mvp);
    gl.Uniform1f(pt_u_far, far ? 1.f : 0.f);
    gl.Uniform4f(pt_u_col, col[0], col[1], col[2], col[3]);
    gl.VertexAttribPointer(0, 3, GL_FLOAT, 0, 12, v);
    gl.DrawArrays(GL_TRIANGLE_FAN, 0, 4);
}

/* ---------------------------------------------------------------- the pass ---- */
static char pt_cull[2][0x4d0] __attribute__((aligned(16)));
static struct { int on; float V[16], P[16], ent[10]; char *e; } pt_undo;   /* what a crashed pass must put back */
static int pt_on_screen(const float *mvp, float c[4][3], const float *cam) {
    int left = 0, right = 0, below = 0, above = 0, behind = 0;
    for (int k = 0; k < 4; k++) {
        float v[3] = {c[k][0] - cam[0], c[k][1] - cam[1], c[k][2] - cam[2]}, o[4];
        pt_xform(mvp, v, 1, o);
        if (o[3] <= 0.01f) { behind++; continue; }
        if (o[0] < -o[3]) left++;
        if (o[0] > o[3]) right++;
        if (o[1] < -o[3]) below++;
        if (o[1] > o[3]) above++;
    }
    return !(behind == 4 || left == 4 || right == 4 || below == 4 || above == 4);
}
static void pt_view_through(void *scr, void *lr, char *ent, const float *cam, float a, Portal *P, int from) {
    PEnd *A = &P->e[from], *B = &P->e[1 - from];
    float *V = pt_top(pt_view), *Pm = pt_top(pt_proj);
    float Vs[16], Ps[16], mvp[16], c[4][3];
    memcpy(Vs, V, 64); memcpy(Ps, Pm, 64);
    memcpy(pt_undo.V, Vs, 64); memcpy(pt_undo.P, Ps, 64); pt_undo.e = 0; pt_undo.on = 1;
    pt_mul(Ps, Vs, mvp);
    pt_corners(P, A, c);
    if (!pt_on_screen(mvp, c, cam)) return;
    float turn = pt_turn(A, B), vcam[3], R[16], V2[16], P2[16];
    pt_map(A, B, cam, vcam);
    pt_rot_inv_mat(turn, R);
    pt_mul(Vs, R, V2);
    memcpy(P2, Ps, 64);
    {                                                                  /* near plane on the exit */
        float q[3] = {B->o[0] - vcam[0], B->o[1] - vcam[1], B->o[2] - vcam[2]}, qc[4], nc[4];
        float n[3] = {pt_n[B->face][0], 0, pt_n[B->face][2]};
        pt_xform(V2, q, 1, qc);
        pt_xform(V2, n, 0, nc);
        float C[4] = {nc[0], nc[1], nc[2], -(nc[0] * qc[0] + nc[1] * qc[1] + nc[2] * qc[2])};
        if (C[3] < 0) pt_oblique(P2, C);
    }
    PtGl s;
    pt_gl_save(&s);
    float clear[4] = {0.5f, 0.7f, 1.f, 1.f};
    gl.GetFloatv(GL_COLOR_CLEAR_VALUE, clear);
    clear[3] = 1.f;
    gl.UseProgram(pt_prog);
    gl.BindBuffer(GL_ARRAY_BUFFER, 0);
    gl.EnableVertexAttribArray(0);
    gl.Disable(GL_BLEND); gl.Disable(GL_CULL_FACE); gl.Disable(GL_SCISSOR_TEST);
    gl.Enable(GL_STENCIL_TEST);
    gl.StencilMask(0xff);
    gl.ClearStencil(0);
    gl.Clear(GL_STENCIL_BUFFER_BIT);
    /* 1: the rectangle where it is seen */
    gl.Enable(GL_DEPTH_TEST); gl.DepthFunc(GL_LEQUAL); gl.DepthMask(0);
    gl.ColorMask(0, 0, 0, 0);
    gl.StencilFunc(GL_ALWAYS, 1, 0xff);
    gl.StencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
    pt_quad(mvp, c, cam, 0, clear);
    /* 2: there: far depth, the sky's colour */
    gl.StencilFunc(GL_EQUAL, 1, 0xff);
    gl.StencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    gl.DepthFunc(GL_ALWAYS); gl.DepthMask(1);
    gl.ColorMask(1, 1, 1, 1);
    pt_quad(mvp, c, cam, 1, clear);
    /* 3: the world beyond, only there */
    pt_gl_restore(&s);
    gl.Enable(GL_STENCIL_TEST);
    gl.StencilFunc(GL_EQUAL, 1, 0xff);
    gl.StencilOp(GL_KEEP, GL_KEEP, GL_KEEP);
    gl.StencilMask(0);
    memcpy(pt_top(pt_view), V2, 64);
    memcpy(pt_top(pt_proj), P2, 64);
    for (int i = 0; i < 2; i++) { memset(pt_cull[i], 0, sizeof pt_cull[i]); pt_frustum_ctor(pt_cull[i]); }
    ((void (*)(void *, void *, void *))(*(u32 **)scr)[86])(scr, pt_cull[0], pt_cull[1]);   /* _prepareCuller */
    for (int i = 0; i < 2; i++) memcpy(pt_cull[i] + 0x4c0, vcam, 12);
    float keep[16];
    memcpy(keep, ent + 8, 12); memcpy(keep + 3, ent + 0x20, 12); memcpy(keep + 6, ent + 0x38, 16);
    memcpy(pt_undo.ent, keep, 40); pt_undo.e = ent;
    memcpy(ent + 8, vcam, 12); memcpy(ent + 0x20, vcam, 12);          /* the camera entity at the virtual eye */
    *(float *)(ent + 0x3c) += turn; *(float *)(ent + 0x44) += turn;
    pt_inpass = 1;
    pt_render_level(lr, ent, pt_cull[0], pt_cull[1], *(float *)((char *)lr + 0x1634), a);
    pt_inpass = 0;
    memcpy(ent + 8, keep, 12); memcpy(ent + 0x20, keep + 3, 12); memcpy(ent + 0x38, keep + 6, 16);
    pt_undo.e = 0;
    memcpy(pt_top(pt_view), Vs, 64);
    memcpy(pt_top(pt_proj), Ps, 64);
    pt_en(GL_STENCIL_TEST, s.stencil);
    gl.StencilMask((GLuint)s.swmask);
    gl.StencilFunc((GLenum)s.sfunc, s.sref, (GLuint)s.svmask);
    gl.StencilOp((GLenum)s.sfail, (GLenum)s.szfail, (GLenum)s.szpass);
    pt_passes++;
    pt_undo.on = 0;
}

static int pt_cur_dim(void *ent) {
    void *bs = ent && wd_region ? wd_region(ent) : 0;
    return bs && wd_bs_dim ? wd_bs_dim(bs) : 0;
}

/* crossing: the eye went through an end inside its rectangle -> the same place at the other end */
static int pt_cross(char *ent, float *cam, int dim) {
    if (ent != pt_prev_ent) { pt_prev_ent = ent; pt_prev_valid = 0; }
    if (pt_prev_valid) {                                                /* a jump (teleport, respawn): no crossing */
        float dx = cam[0] - pt_prev_eye[0], dy = cam[1] - pt_prev_eye[1], dz = cam[2] - pt_prev_eye[2];
        if (dx * dx + dy * dy + dz * dz > 16) pt_prev_valid = 0;
    }
    int moved = 0;
    for (int i = 0; i < PT_MAX && pt_prev_valid && !moved; i++) {
        Portal *P = &pt[i];
        if (!P->used || P->dim != dim) continue;
        for (int k = 0; k < 2; k++) {
            PEnd *A = &P->e[k], *B = &P->e[1 - k];
            float s0 = pt_side(A, pt_prev_eye), s1 = pt_side(A, cam);
            if (!(s0 > 0 && s1 <= 0)) continue;
            float t = s0 / (s0 - s1), hit[3];
            for (int j = 0; j < 3; j++) hit[j] = pt_prev_eye[j] + (cam[j] - pt_prev_eye[j]) * t;
            if (!pt_inside(P, A, hit, 0.05f)) continue;
            float turn = pt_turn(A, B), h = *(float *)(ent + 0xc0);
            float feet[3] = {*(float *)(ent + 8), *(float *)(ent + 0xc) - h, *(float *)(ent + 0x10)}, nf[3];
            pt_map(A, B, feet, nf);
            float rot[2] = {*(float *)(ent + 0x38), *(float *)(ent + 0x3c) + turn};
            float vel[3], nv[3];
            memcpy(vel, ent + 0x2c, 12);
            pt_rot(turn, vel, nv);
            pt_move_to(ent, nf, rot);
            memcpy(ent + 0x2c, nv, 12);
            float nc[3];
            pt_map(A, B, cam, nc);
            memcpy(cam, nc, 12);
            float *V = pt_top(pt_view), R[16];                              /* this frame's view turns too */
            pt_rot_inv_mat(turn, R);
            pt_mul(V, R, V);
            moved = 1;
            break;
        }
    }
    memcpy(pt_prev_eye, cam, 12);
    pt_prev_valid = 1;
    return moved;
}

static void h_pt_rfi(void *scr, float x, float y, float z, void *lr, void *ent, float a) {
    float cam[3] = {x, y, z};
    int any = 0;
    for (int i = 0; i < PT_MAX; i++) if (pt[i].used) any = 1;
    void *me = g_client && pt_local_player ? pt_local_player(g_client) : 0;
    int dim = any ? pt_cur_dim(ent) : 0;
    if (any && ent && ent == me) pt_cross((char *)ent, cam, dim);
    o_pt_rfi(scr, cam[0], cam[1], cam[2], lr, ent, a);
    if (!any || !pt_render_on || !pt_view_hooked || pt_broken || pt_inpass || !ent || pt_gl_init() != 1) return;
    long t0 = now_ms();
    if (crash_path[0] && sigsetjmp(nova_guard_jb, 1)) {
        nova_guard = 0;
        pt_inpass = 0;
        pt_broken = 1;
        if (pt_undo.on) {                                              /* put back what the pass had changed */
            memcpy(pt_top(pt_view), pt_undo.V, 64);
            memcpy(pt_top(pt_proj), pt_undo.P, 64);
            if (pt_undo.e) {
                memcpy(pt_undo.e + 8, pt_undo.ent, 12); memcpy(pt_undo.e + 0x20, pt_undo.ent + 3, 12);
                memcpy(pt_undo.e + 0x38, pt_undo.ent + 6, 16);
            }
            pt_undo.on = 0;
        }
        if (pt_gl_ok == 1) { gl.Disable(GL_STENCIL_TEST); gl.StencilMask(0xff); gl.ColorMask(1, 1, 1, 1); gl.DepthMask(1); }
        LOGE("portals: crashed inside the see-through pass (pc rel 0x%x): drawing off, crossing stays", nova_guard_pc);
        return;
    }
    nova_guard = crash_path[0] != 0;
    pt_passes = 0;
    for (int i = 0; i < PT_MAX; i++) {
        Portal *P = &pt[i];
        if (!P->used || P->dim != dim) continue;
        for (int k = 0; k < 2; k++) {
            PEnd *A = &P->e[k];
            float dx = cam[0] - A->o[0], dz = cam[2] - A->o[2], dy = cam[1] - A->o[1] - P->h / 2;
            if (pt_side(A, cam) <= 0.001f || dx * dx + dy * dy + dz * dz > 64 * 64) continue;
            pt_view_through(scr, lr, (char *)ent, cam, a, P, k);
        }
    }
    nova_guard = 0;
    pt_last_us = (int)(now_ms() - t0);
}

static u64 hc_pt_view_area(u32 orig, u32 *w) {            /* LevelRenderer::updateViewArea: not for our pass */
    if (pt_inpass) return 0;
    return nh_callw(orig, w);
}

/* ---------------------------------------------------------------- mods ---- */
static int pt_add(const double *a, int n, int dim) {
    if (n < 8) return -1;
    for (int i = 0; i < PT_MAX; i++) {
        if (pt[i].used) continue;
        Portal *P = &pt[i];
        memset(P, 0, sizeof *P);
        for (int k = 0; k < 2; k++) {
            P->e[k].o[0] = (float)a[k * 4]; P->e[k].o[1] = (float)a[k * 4 + 1]; P->e[k].o[2] = (float)a[k * 4 + 2];
            P->e[k].face = ((int)a[k * 4 + 3] % 4 + 4) % 4;
        }
        P->w = n > 8 && a[8] > 0 ? (float)a[8] : 2.f;
        P->h = n > 9 && a[9] > 0 ? (float)a[9] : 3.f;
        if (P->w > 32) P->w = 32;
        if (P->h > 32) P->h = 32;
        P->dim = dim;
        P->used = 1;
        pt_prev_valid = 0;
        LOG("portals: %d added (%.1f %.1f %.1f f%d) <-> (%.1f %.1f %.1f f%d) %gx%g, dimension %d", i,
            P->e[0].o[0], P->e[0].o[1], P->e[0].o[2], P->e[0].face, P->e[1].o[0], P->e[1].o[1], P->e[1].o[2], P->e[1].face, P->w, P->h, dim);
        return i;
    }
    return -1;
}

static void portals_init(void *mc) {
    #define S(v, n) *(void **)&(v) = dlsym(mc, n)
    S(pt_render_level, "_ZN13LevelRenderer11renderLevelER6EntityR13FrustumCullerS3_ff");
    S(pt_frustum_ctor, "_ZN11FrustumDataC2Ev");
    S(pt_top, "_ZN11MatrixStack6getTopEv");
    S(pt_view, "_ZN11MatrixStack4ViewE");
    S(pt_proj, "_ZN11MatrixStack10ProjectionE");
    S(pt_move_to, "_ZN6Entity6moveToERK4Vec3RK4Vec2");
    S(pt_local_player, "_ZN15MinecraftClient14getLocalPlayerEv");
    #undef S
    u32 vt = (u32)dlsym(mc, "_ZTV16InGamePlayScreen");
    void *va = dlsym(mc, "_ZN13LevelRenderer14updateViewAreaER6Entityf");
    if (!pt_render_level || !pt_frustum_ctor || !pt_top || !pt_view || !pt_proj || !pt_move_to || !pt_local_player || !vt || !va) {
        LOGE("portals: symbols missing, off");
        return;
    }
    if (patch_slot(vt, 77, h_pt_rfi, (void **)&o_pt_rfi) != 0) { LOGE("portals: screen hook failed, off"); return; }
    int r = nh_hook_c((u32)va, hc_pt_view_area);
    pt_view_hooked = r >= 0;
    if (!pt_view_hooked) LOGE("portals: updateViewArea hook failed (%d): crossing only, no see-through", r);
    pt_ok = 1;
    LOG("portals: ready");
}

/* McMod Script: portal.* (mcmod/api/portal.tsv) */
static int pt_op(int op, const double *a, int n, double *out, void *ent) {
    switch (op) {
    case OP_PORTAL_ADD: out[0] = pt_ok ? pt_add(a, n, pt_cur_dim(ent)) : -1; return 1;
    case OP_PORTAL_REMOVE: { int i = n > 0 ? (int)a[0] : -1; if (i >= 0 && i < PT_MAX) pt[i].used = 0; return 0; }
    case OP_PORTAL_CLEAR: memset(pt, 0, sizeof pt); return 0;
    case OP_PORTAL_RENDER: pt_render_on = n > 0 && a[0] != 0; return 0;
    case OP_PORTAL_STATUS: {
        int cnt = 0;
        for (int i = 0; i < PT_MAX; i++) if (pt[i].used) cnt++;
        out[0] = pt_ok; out[1] = pt_gl_ok; out[2] = pt_stencil_bits; out[3] = pt_render_on && pt_view_hooked && !pt_broken;
        out[4] = pt_passes; out[5] = pt_last_us; out[6] = cnt;
        return 7;
    }
    }
    return -1;
}
