/* mobs.inc - mod mobs: Bedrock models, animations and animation controllers on top of a carrier mob of 0.14.
 *
 * Blob "NVM1" from Java (ModMobs.java), one record per mob type:
 *   name, texture path ("nova/mod/<id>/..."), scale, shadow, width, height, health, speed, damage,
 *   bones (parent, pivot, bind rotation), quads (bone, normal, 4 x {x y z u v}) in Bedrock model space,
 *   animations (loop, length, anim_time_update, blend_weight, channels with keyframes), controllers (states:
 *   animations + weights, blend time, transitions), the top-level animate list, names of per-entity values.
 *
 * A mod mob is a PigZombie (does not burn in the sun, walks, fights back) whose vtable is our copy per type:
 *   - the copy is how we recognise the mob (no lookup tables to go stale);
 *   - addAdditionalSaveData writes "NovaMob" = type name (+ the per-entity values), PigZombie::readAdditionalSaveData
 *     (hooked) gives a loaded PigZombie with that tag our vtable back;
 *   - sounds of the carrier are silent (mod sounds come later).
 * Rendering: EntityRenderDispatcher::getRenderer(Entity&) answers our MobRenderer for these mobs. Its model is a
 * GhastModel with a vtable copy whose render() draws the posed Bedrock mesh with the Tessellator, inside the
 * matrices, light and hurt colour the MobRenderer has already set up.
 *
 * Coordinates: everything is posed in Bedrock space (x as in the file, y up, pixels). A posed point p goes to the
 * 0.14 model space as (p.x, 24 - p.y, p.z) * scale, the same mapping the game's geometry loader uses. */

#define NM_MAXT 32
#define NM_SLOTS 64
#define NM_CUSTOM0 16

typedef struct { u16 parent; float pivot[3], rot[3]; } NmBone;
typedef struct { u16 bone; float n[3]; float v[4][5]; } NmQuad;
typedef struct { u16 bone; u8 kind, nkeys; const u8 *data; } NmChan;
typedef struct { const char *name; u8 loop; float length; const u8 *time, *weight; int nch; NmChan *ch; } NmAnim;
typedef struct { u16 anim; const u8 *weight; } NmStAnim;
typedef struct { u16 target; const u8 *cond; } NmTrans;
typedef struct { const char *name; float blend; int nanim; NmStAnim *an; int ntr; NmTrans *tr; } NmState;
typedef struct { int init, nstate; NmState *st; } NmCtrl;
typedef struct { u8 kind; u16 index; const u8 *weight; } NmAnimate;
typedef struct {
    const char *name, *tex;
    float scale, shadow, width, height, health, speed, damage;
    int nbone; NmBone *bone;
    int nquad; NmQuad *quad;
    int nanim; NmAnim *anim;
    int nctrl; NmCtrl *ctrl;
    int nanimate; NmAnimate *animate;
    int nslot; const char **slot;
    u32 *vt;                    /* our vtable copy (address point) */
    void *renderer;             /* MobRenderer */
    u32 tex_ptr[4];             /* TexturePtr */
    int tex_ok;
} NmType;

static void *nm_mc;
static void nm_hooks(void);
static NmType nm_types[NM_MAXT];
static int nm_ntypes;
static u8 *nm_blob;

/* per entity: values, controller states (4 controllers max) */
#define NM_ECAP 256
#define NM_CMAX 4
typedef struct {
    void *e; long long uid;
    float val[NM_SLOTS];
    int cur[NM_CMAX], prev[NM_CMAX];
    float since[NM_CMAX], prev_since[NM_CMAX], switched[NM_CMAX];
    int started;
    u32 seen;
} NmEnt;
static NmEnt nm_ents[NM_ECAP];
static u32 nm_frame;
static int (*f_mob_health)(void *mob);

/* ---------------- blob ---------------- */
static const u8 *nm_p;
static u8 nm_u8(void) { return *nm_p++; }
static u16 nm_u16(void) { u16 v = nm_p[0] | nm_p[1] << 8; nm_p += 2; return v; }
static u32 nm_u32(void) { u32 v = nm_p[0] | nm_p[1] << 8 | nm_p[2] << 16 | (u32)nm_p[3] << 24; nm_p += 4; return v; }
static float nm_f32(void) { u32 v = nm_u32(); float f; memcpy(&f, &v, 4); return f; }
static const char *nm_str(void) { int n = nm_u8(); char *s = dupn((const char *)nm_p, n); nm_p += n; return s; }
static const u8 *nm_prog(void) { const u8 *p = nm_p; nm_p += 2 + rd16(nm_p); return p; }

static void *nm_alloc(int n) { void *p = malloc(n ? n : 1); if (p) memset(p, 0, n ? n : 1); return p; }

int Java_com_nova_client_ModBridge_nativeMobs(void **env, void *cls, void *arr) {
    int len = 0;
    u8 *b = jbytes(env, arr, &len);
    if (!b || len < 6 || memcmp(b, "NVM1", 4) != 0) { LOGE("mobs: bad blob"); return -1; }
    nm_blob = b;
    nm_p = b + 4;
    int n = nm_u16();
    if (n > NM_MAXT) n = NM_MAXT;
    for (int t = 0; t < n; t++) {
        NmType *T = &nm_types[t];
        T->name = nm_str(); T->tex = nm_str();
        T->scale = nm_f32(); T->shadow = nm_f32(); T->width = nm_f32(); T->height = nm_f32();
        T->health = nm_f32(); T->speed = nm_f32(); T->damage = nm_f32();
        T->nbone = nm_u16();
        T->bone = nm_alloc(sizeof(NmBone) * T->nbone);
        for (int i = 0; i < T->nbone; i++) {
            T->bone[i].parent = nm_u16();
            for (int k = 0; k < 3; k++) T->bone[i].pivot[k] = nm_f32();
            for (int k = 0; k < 3; k++) T->bone[i].rot[k] = nm_f32();
        }
        T->nquad = (int)nm_u32();
        T->quad = nm_alloc(sizeof(NmQuad) * T->nquad);
        for (int i = 0; i < T->nquad; i++) {
            NmQuad *Q = &T->quad[i];
            Q->bone = nm_u16();
            for (int k = 0; k < 3; k++) Q->n[k] = nm_f32();
            for (int v = 0; v < 4; v++) for (int k = 0; k < 5; k++) Q->v[v][k] = nm_f32();
        }
        T->nanim = nm_u16();
        T->anim = nm_alloc(sizeof(NmAnim) * T->nanim);
        for (int a = 0; a < T->nanim; a++) {
            NmAnim *A = &T->anim[a];
            A->name = nm_str(); A->loop = nm_u8(); A->length = nm_f32();
            A->time = nm_prog(); A->weight = nm_prog();
            A->nch = nm_u16();
            A->ch = nm_alloc(sizeof(NmChan) * A->nch);
            for (int c = 0; c < A->nch; c++) {
                NmChan *C = &A->ch[c];
                C->bone = nm_u16(); C->kind = nm_u8(); C->nkeys = nm_u8();
                C->data = nm_p;
                if (!C->nkeys) { for (int k = 0; k < 3; k++) nm_prog(); }
                else for (int k = 0; k < C->nkeys; k++) { nm_p += 5; for (int j = 0; j < 6; j++) nm_prog(); }
            }
        }
        T->nctrl = nm_u16();
        T->ctrl = nm_alloc(sizeof(NmCtrl) * T->nctrl);
        for (int c = 0; c < T->nctrl; c++) {
            NmCtrl *C = &T->ctrl[c];
            C->init = nm_u16(); C->nstate = nm_u16();
            C->st = nm_alloc(sizeof(NmState) * C->nstate);
            for (int s = 0; s < C->nstate; s++) {
                NmState *S = &C->st[s];
                S->name = nm_str(); S->blend = nm_f32();
                S->nanim = nm_u16();
                S->an = nm_alloc(sizeof(NmStAnim) * S->nanim);
                for (int i = 0; i < S->nanim; i++) { S->an[i].anim = nm_u16(); S->an[i].weight = nm_prog(); }
                S->ntr = nm_u16();
                S->tr = nm_alloc(sizeof(NmTrans) * S->ntr);
                for (int i = 0; i < S->ntr; i++) { S->tr[i].target = nm_u16(); S->tr[i].cond = nm_prog(); }
            }
        }
        T->nanimate = nm_u16();
        T->animate = nm_alloc(sizeof(NmAnimate) * T->nanimate);
        for (int i = 0; i < T->nanimate; i++) {
            T->animate[i].kind = nm_u8(); T->animate[i].index = nm_u16(); T->animate[i].weight = nm_prog();
        }
        T->nslot = nm_u16();
        T->slot = nm_alloc(sizeof(char *) * (T->nslot ? T->nslot : 1));
        for (int i = 0; i < T->nslot; i++) T->slot[i] = nm_str();
        if (nm_p > b + len) { LOGE("mobs: blob overrun at type %d", t); break; }
        LOG("mobs: %s: %d bones, %d quads, %d animations, %d controllers, %d values", T->name, T->nbone, T->nquad,
            T->nanim, T->nctrl, T->nslot);
        nm_ntypes = t + 1;
    }
    if (nm_ntypes) nm_hooks();
    return nm_ntypes;
}

/* ---------------- engine functions ---------------- */
static void *nm_tess;                                          /* Tessellator::instance */
static void (*nm_tbegin)(void *t, int n);
static void (*nm_tnormal)(void *t, float x, float y, float z);
static void (*nm_tvertex)(void *t, float x, float y, float z, float u, float v);
static void (*nm_tdraw)(void *t, void *mat, void *tex);
/* MonsterPlacerItem::spawnMobAt returns Level::addEntity's bool, not the mob: we do its steps ourselves */
static void (*nm_create)(void **sret, int type, void *bs, float *pos);    /* MobFactory::CreateMob -> unique_ptr<Mob> */
static void (*nm_reset_attr)(void *mob);
static void (*nm_persistent)(void *mob);
static void (*nm_moveto)(void *e, float *pos, float *rot);
static void *(*nm_getlevel)(void *bs);
static void (*nm_ghast_ctor)(void *m);
static void (*nm_mobrenderer_ctor)(void *r, void **model, void *tex, float shadow);
static void (*nm_renderer_init)(void *r, void *dispatcher);
static void (*nm_setbaby)(void *e, int baby);
static void (*nm_putstr)(void *tag, void *k, void *v);
static void *(*nm_getstr)(void *tag, void *k);
static int (*nm_contains)(void *tag, void *k);
static u32 nm_pz_vt;                                           /* PigZombie vtable (address point) */
static u32 nm_o_save;                                          /* PigZombie::addAdditionalSaveData */
#define NM_VT_WORDS 229
#define NM_PIGZOMBIE 0x10b24

static int nm_type_of(void *e) {
    if (!e || !nm_ntypes) return -1;
    u32 vt = *(u32 *)e;
    for (int t = 0; t < nm_ntypes; t++) if (nm_types[t].vt && (u32)nm_types[t].vt == vt) return t;
    return -1;
}

static NmEnt *nm_ent(void *e, int create) {
    long long uid = sf_uid(e);
    u32 h = ((u32)e >> 3) * 2654435761u;
    NmEnt *old = 0;
    for (int i = 0; i < 16; i++) {
        NmEnt *x = &nm_ents[(h + i) & (NM_ECAP - 1)];
        if (x->e == e && x->uid == uid) { x->seen = nm_frame; return x; }
        if (!old || x->seen < old->seen || !x->e) old = x;
    }
    if (!create) return 0;
    memset(old, 0, sizeof *old);
    old->e = e; old->uid = uid; old->seen = nm_frame;
    return old;
}

/* ---------------- posing ---------------- */
typedef struct { float m[12]; } NmMat;     /* 3x4, row major: [r00 r01 r02 tx; ...] */
static float nm_rot[256][3], nm_pos[256][3], nm_scl[256][3];
static NmMat nm_world[256];

static void nm_rotmat(const float *deg, float *r) {              /* Rz(-z) Ry(y) Rx(-x) */
    float ax = -deg[0] * DEG2RAD, ay = deg[1] * DEG2RAD, az = -deg[2] * DEG2RAD;
    float cx = cosf(ax), sx = sinf(ax), cy = cosf(ay), sy = sinf(ay), cz = cosf(az), sz = sinf(az);
    r[0] = cz * cy; r[1] = cz * sy * sx - sz * cx; r[2] = cz * sy * cx + sz * sx;
    r[3] = sz * cy; r[4] = sz * sy * sx + cz * cx; r[5] = sz * sy * cx - cz * sx;
    r[6] = -sy;     r[7] = cy * sx;                r[8] = cy * cx;
}

static void nm_mul(const NmMat *a, const NmMat *b, NmMat *o) {
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++)
            o->m[r * 4 + c] = a->m[r * 4] * b->m[c] + a->m[r * 4 + 1] * b->m[4 + c] + a->m[r * 4 + 2] * b->m[8 + c];
        o->m[r * 4 + 3] = a->m[r * 4] * b->m[3] + a->m[r * 4 + 1] * b->m[7] + a->m[r * 4 + 2] * b->m[11] + a->m[r * 4 + 3];
    }
}

static void nm_key(const u8 *p, float *t, u8 *lerp, float pre[3], float post[3], const float *vars) {
    *t = rdf(p); *lerp = p[4]; p += 5;
    for (int k = 0; k < 3; k++) { pre[k] = eval(p, vars, 0); p = skip_prog(p); }
    for (int k = 0; k < 3; k++) { post[k] = eval(p, vars, 0); p = skip_prog(p); }
}
static const u8 *nm_key_skip(const u8 *p) { p += 5; for (int j = 0; j < 6; j++) p = skip_prog(p); return p; }

static void nm_chan(const NmChan *C, float t, const float *vars, float out[3]) {
    const u8 *p = C->data;
    if (!C->nkeys) { for (int k = 0; k < 3; k++) { out[k] = eval(p, vars, 0); p = skip_prog(p); } return; }
    /* find the keys around t */
    const u8 *keys[256];
    int n = C->nkeys;
    for (int i = 0; i < n; i++) { keys[i] = p; p = nm_key_skip(p); }
    int nx = 0;
    while (nx < n && rdf(keys[nx]) <= t) nx++;
    float t0, t1, pre[3], post[3], a[3], b[3];
    u8 lerp;
    if (nx == 0) { nm_key(keys[0], &t0, &lerp, pre, post, vars); for (int k = 0; k < 3; k++) out[k] = pre[k]; return; }
    if (nx == n) { nm_key(keys[n - 1], &t0, &lerp, pre, post, vars); for (int k = 0; k < 3; k++) out[k] = post[k]; return; }
    u8 l1;
    nm_key(keys[nx - 1], &t0, &lerp, pre, a, vars);
    nm_key(keys[nx], &t1, &l1, b, post, vars);
    float f = t1 > t0 ? (t - t0) / (t1 - t0) : 0;
    if (lerp == 2) { for (int k = 0; k < 3; k++) out[k] = a[k]; return; }
    if (lerp == 1 || l1 == 1) {                                  /* catmull-rom through the neighbours */
        float p0[3], p3[3], d0, dpre[3];
        u8 lx;
        if (nx >= 2) nm_key(keys[nx - 2], &d0, &lx, dpre, p0, vars); else for (int k = 0; k < 3; k++) p0[k] = a[k];
        if (nx + 1 < n) nm_key(keys[nx + 1], &d0, &lx, p3, dpre, vars); else for (int k = 0; k < 3; k++) p3[k] = b[k];
        float f2 = f * f, f3 = f2 * f;
        for (int k = 0; k < 3; k++)
            out[k] = 0.5f * ((2 * a[k]) + (-p0[k] + b[k]) * f + (2 * p0[k] - 5 * a[k] + 4 * b[k] - p3[k]) * f2
                             + (-p0[k] + 3 * a[k] - 3 * b[k] + p3[k]) * f3);
        return;
    }
    for (int k = 0; k < 3; k++) out[k] = a[k] + (b[k] - a[k]) * f;
}


/* one animation at its own time, weight w: adds into nm_rot/pos/scl; returns 1 when it has finished */
static int nm_play(NmType *T, NmAnim *A, float t, float w, float *vars) {
    if (w == 0) return 0;
    int done = 0;
    vars[V_ANIM_TIME] = t;
    float tu = eval(A->time, vars, t);
    t = tu;
    if (A->length > 0) {
        if (A->loop == 1) t = fmodf(t, A->length);
        else if (t >= A->length) { done = 1; t = A->length; if (A->loop == 0) return 1; }
        if (t < 0) t = 0;
    }
    vars[V_ANIM_TIME] = t;
    w *= eval(A->weight, vars, 1.f);
    for (int c = 0; c < A->nch; c++) {
        NmChan *C = &A->ch[c];
        if (C->bone >= T->nbone || C->bone >= 256) continue;
        float v[3];
        nm_chan(C, t, vars, v);
        if (C->kind == 0) for (int k = 0; k < 3; k++) nm_rot[C->bone][k] += v[k] * w;
        else if (C->kind == 1) for (int k = 0; k < 3; k++) nm_pos[C->bone][k] += v[k] * w;
        else for (int k = 0; k < 3; k++) nm_scl[C->bone][k] *= 1.f + (v[k] - 1.f) * w;
    }
    return done;
}

static void nm_play_state(NmType *T, NmState *S, float t, float w, float *vars, int *all_done, int *any_done) {
    for (int i = 0; i < S->nanim; i++) {
        if (S->an[i].anim >= T->nanim) continue;
        vars[V_ANIM_TIME] = t;
        float aw = eval(S->an[i].weight, vars, 1.f);
        int d = nm_play(T, &T->anim[S->an[i].anim], t, w * aw, vars);
        if (d) *any_done = 1; else *all_done = 0;
    }
}

static void nm_pose(NmType *T, NmEnt *E, char *e, float limb, float amount, float age, float yaw, float pitch) {
    int nb = T->nbone < 256 ? T->nbone : 256;
    for (int i = 0; i < nb; i++) {
        nm_rot[i][0] = nm_rot[i][1] = nm_rot[i][2] = 0;
        nm_pos[i][0] = nm_pos[i][1] = nm_pos[i][2] = 0;
        nm_scl[i][0] = nm_scl[i][1] = nm_scl[i][2] = 1;
    }
    float now = age / 20.f;
    float *vars = E->val;                       /* built-ins are the first NM_CUSTOM0 values */
    vars[V_LIFE_TIME] = now;
    vars[V_DIST] = limb;
    vars[V_SPEED] = amount;
    vars[V_YAW] = yaw;
    vars[V_PITCH] = pitch;
    vars[V_ON_GROUND] = 1.f;
    vars[V_ARG3] = age;
    vars[10] = e && f_mob_health ? (float)f_mob_health(e) : 20.f;
    vars[11] = 1.f;
    vars[15] = amount > 0.01f ? 1.f : 0.f;
    eval_nvars_hi = NM_SLOTS;
    if (!E->started) {
        for (int c = 0; c < T->nctrl && c < NM_CMAX; c++) {
            E->cur[c] = T->ctrl[c].init; E->prev[c] = -1; E->since[c] = now; E->switched[c] = -100.f;
        }
        E->started = 1;
    }
    for (int i = 0; i < T->nanimate; i++) {
        NmAnimate *M = &T->animate[i];
        vars[V_ANIM_TIME] = now;
        float w = eval(M->weight, vars, 1.f);
        if (w == 0) continue;
        if (M->kind == 0) { if (M->index < T->nanim) nm_play(T, &T->anim[M->index], now, w, vars); continue; }
        int c = M->index;
        if (c >= T->nctrl || c >= NM_CMAX) continue;
        NmCtrl *C = &T->ctrl[c];
        if (E->cur[c] < 0 || E->cur[c] >= C->nstate) E->cur[c] = C->init;
        /* transitions of the current state (first true wins), evaluated with the state's own finish flags */
        for (int guard = 0; guard < 4; guard++) {
            NmState *S = &C->st[E->cur[c]];
            int all = 1, any = 0;
            float t = now - E->since[c];
            for (int k = 0; k < S->nanim; k++) {
                if (S->an[k].anim >= T->nanim) continue;
                NmAnim *A = &T->anim[S->an[k].anim];
                if (A->loop != 1 && A->length > 0 && t >= A->length) any = 1; else all = 0;
            }
            vars[8] = S->nanim ? (float)all : 1.f;
            vars[9] = (float)any;
            vars[V_ANIM_TIME] = t;
            int to = -1;
            for (int k = 0; k < S->ntr; k++) if (eval(S->tr[k].cond, vars, 0) != 0) { to = S->tr[k].target; break; }
            if (to < 0 || to == E->cur[c] || to >= C->nstate) break;
            E->prev[c] = E->cur[c]; E->prev_since[c] = E->since[c];
            E->cur[c] = to; E->since[c] = now; E->switched[c] = now;
        }
        NmState *S = &C->st[E->cur[c]];
        float bw = 1.f;
        if (E->prev[c] >= 0 && S->blend > 0 && now - E->switched[c] < S->blend) {
            bw = (now - E->switched[c]) / S->blend;
            int a1 = 1, a2 = 0;
            nm_play_state(T, &C->st[E->prev[c]], now - E->prev_since[c], w * (1.f - bw), vars, &a1, &a2);
        }
        int all = 1, any = 0;
        nm_play_state(T, S, now - E->since[c], w * bw, vars, &all, &any);
    }
    eval_nvars_hi = 0;
    /* bone matrices, parents first (Bedrock files list parents before children; fix up if not) */
    static u8 done[256];
    memset(done, 0, sizeof done);
    for (int pass = 0, left = nb; pass < 64 && left > 0; pass++) {
        for (int i = 0; i < nb; i++) {
            if (done[i]) continue;
            NmBone *B = &T->bone[i];
            int par = B->parent < nb ? B->parent : -1;
            if (par >= 0 && !done[par]) continue;
            float deg[3] = {B->rot[0] + nm_rot[i][0], B->rot[1] + nm_rot[i][1], B->rot[2] + nm_rot[i][2]};
            float r[9];
            nm_rotmat(deg, r);
            NmMat L;
            float *s = nm_scl[i], *pv = B->pivot, *ap = nm_pos[i];
            for (int row = 0; row < 3; row++) {
                L.m[row * 4 + 0] = r[row * 3 + 0] * s[0];
                L.m[row * 4 + 1] = r[row * 3 + 1] * s[1];
                L.m[row * 4 + 2] = r[row * 3 + 2] * s[2];
                /* T(pivot + pos) R S T(-pivot) */
                L.m[row * 4 + 3] = pv[row] + ap[row] - (L.m[row * 4] * pv[0] + L.m[row * 4 + 1] * pv[1] + L.m[row * 4 + 2] * pv[2]);
            }
            if (par >= 0) nm_mul(&nm_world[par], &L, &nm_world[i]); else nm_world[i] = L;
            done[i] = 1;
            left--;
        }
    }
}

/* ---------------- the model: GhastModel object with our render() ---------------- */
static u32 *nm_model_vt;

static void nm_model_render(void *model, char *e, float limb, float amount, float age, float yaw, float pitch, float scale) {
    int t = nm_type_of(e);
    if (t < 0 || !nm_tess || !nm_tbegin || !nm_tvertex || !nm_tdraw) return;
    NmType *T = &nm_types[t];
    nm_frame++;
    NmEnt *E = nm_ent(e, 1);
    if (!E) return;
    nm_pose(T, E, e, limb, amount, age, yaw, pitch);
    void *mat = *(void **)((char *)model + 0x10);
    void *tex = *(void **)((char *)model + 0x14);
    if (!tex) tex = T->tex_ptr;
    if (!mat) return;
    float s = T->scale * scale;
    nm_tbegin(nm_tess, T->nquad * 4);
    for (int q = 0; q < T->nquad; q++) {
        NmQuad *Q = &T->quad[q];
        const float *m = Q->bone < 256 ? nm_world[Q->bone].m : 0;
        if (!m) continue;
        float nx = m[0] * Q->n[0] + m[1] * Q->n[1] + m[2] * Q->n[2];
        float ny = m[4] * Q->n[0] + m[5] * Q->n[1] + m[6] * Q->n[2];
        float nz = m[8] * Q->n[0] + m[9] * Q->n[1] + m[10] * Q->n[2];
        float l = sqrtf(nx * nx + ny * ny + nz * nz);
        if (l > 0) { nx /= l; ny /= l; nz /= l; }
        nm_tnormal(nm_tess, nx, -ny, nz);
        for (int k = 0; k < 4; k++) {
            const float *v = Q->v[k];
            float x = m[0] * v[0] + m[1] * v[1] + m[2] * v[2] + m[3];
            float y = m[4] * v[0] + m[5] * v[1] + m[6] * v[2] + m[7];
            float z = m[8] * v[0] + m[9] * v[1] + m[10] * v[2] + m[11];
            nm_tvertex(nm_tess, x * s, 24.f * scale - y * s, z * s, v[3], v[4]);
        }
    }
    nm_tdraw(nm_tess, mat, tex);
}

static void nm_model_setup(void *model, float a, float b, float c, float d, float e, float f) { }

static void *nm_dispatcher;

static void *nm_renderer(int t, void *dispatcher) {
    NmType *T = &nm_types[t];
    if (T->renderer) return T->renderer;
    if (!nm_ghast_ctor || !nm_mobrenderer_ctor || !f_new || !f_gettex || !f_textures || !g_client) return 0;
    char *model = f_new(0x6f4);
    if (!model) return 0;
    nm_ghast_ctor(model);
    if (!nm_model_vt) {
        u32 *src = *(u32 **)model - 2;
        nm_model_vt = malloc(4 * 34);
        memcpy(nm_model_vt, src, 4 * 34);
        nm_model_vt[2 + 10] = (u32)nm_model_render;
        nm_model_vt[2 + 11] = (u32)nm_model_setup;
    }
    *(u32 **)model = nm_model_vt + 2;
    if (!T->tex_ok) {
        u32 s[1];
        gstr(s, T->tex);
        f_gettex(T->tex_ptr, f_textures(g_client), s, 0);
        f_strdtor(s);
        T->tex_ok = 1;
    }
    /* MobRenderer(unique_ptr<Model>, TexturePtr by value (a temporary we lend), float shadow) */
    u32 tmp[4];
    u32 s2[1];
    gstr(s2, T->tex);
    f_gettex(tmp, f_textures(g_client), s2, 0);
    f_strdtor(s2);
    void *r = f_new(0xc4);
    if (!r) return 0;
    void *up = model;
    nm_mobrenderer_ctor(r, &up, tmp, T->shadow);
    if (nm_renderer_init) nm_renderer_init(r, dispatcher);
    T->renderer = r;
    LOG("mobs: renderer for %s ready", T->name);
    return r;
}

/* EntityRenderDispatcher::getRenderer(Entity&) */
static u64 hc_nm_getrenderer(u32 orig, u32 *w) {
    int t = nm_type_of((void *)w[1]);
    if (t >= 0) {
        void *r = nm_renderer(t, (void *)w[0]);
        if (r) return (u32)r;
    }
    return nh_callw(orig, w);
}

/* ---------------- the carrier: PigZombie with our vtable ---------------- */
static const char NM_TAG[] = "NovaMob";

static void nm_save_values(char *e, void *tag) {
    int t = nm_type_of(e);
    if (t < 0) return;
    NmType *T = &nm_types[t];
    NmEnt *E = nm_ent(e, 0);
    char buf[1024];
    int n = 0;
    if (E) for (int i = 0; i < T->nslot && NM_CUSTOM0 + i < NM_SLOTS && n < 900; i++) {
        float v = E->val[NM_CUSTOM0 + i];
        if (v == 0) continue;
        int iv = (int)(v * 1000.f);
        n += snprintf(buf + n, sizeof buf - n, "%s=%d;", T->slot[i], iv);
    }
    buf[n] = 0;
    u32 k[1], v[1];
    gstr(k, NM_TAG); gstr(v, T->name);
    nm_putstr(tag, k, v);
    f_strdtor(k); f_strdtor(v);
    gstr(k, "NovaVals"); gstr(v, buf);
    nm_putstr(tag, k, v);
    f_strdtor(k); f_strdtor(v);
}

static void nm_slot_set(NmType *T, NmEnt *E, const char *key, int kl, float v) {
    for (int i = 0; i < T->nslot; i++) {
        const char *s = T->slot[i];
        int j = 0;
        while (j < kl && s[j] && s[j] == key[j]) j++;
        if (j == kl && !s[j]) { if (NM_CUSTOM0 + i < NM_SLOTS) E->val[NM_CUSTOM0 + i] = v; return; }
    }
}

static void nm_load_values(char *e, NmType *T, const char *s) {
    NmEnt *E = nm_ent(e, 1);
    if (!E) return;
    while (s && *s) {
        const char *eq = s;
        while (*eq && *eq != '=' && *eq != ';') eq++;
        if (*eq != '=') break;
        const char *num = eq + 1;
        int neg = *num == '-', v = 0;
        if (neg) num++;
        while (*num >= '0' && *num <= '9') v = v * 10 + (*num++ - '0');
        nm_slot_set(T, E, s, (int)(eq - s), (neg ? -v : v) / 1000.f);
        s = *num == ';' ? num + 1 : num;
    }
}

/* our addAdditionalSaveData (vtable slot 114) */
static void nm_vt_save(char *e, void *tag) {
    ((void (*)(void *, void *))nm_o_save)(e, tag);
    nm_save_values(e, tag);
}
/* _getAmbientSound returns const char* (null = no sound, Mob::playAmbientSound); _getHurtSound / _getDeathSound
 * return std::string (sret first) */
static const char *nm_no_ambient(void *e) { return 0; }
static void nm_silent_sound(u32 *sret, void *e) { gstr(sret, ""); }
static void nm_no_step(void *e, void *pos, int block) { }
static int nm_no_xp(void *e) { return 5; }

static void nm_make_vt(NmType *T, u32 *vt) {
    if (T->vt) return;
    u32 *c = malloc(4 * (NM_VT_WORDS + 2));
    if (!c) return;
    memcpy(c, vt - 2, 4 * (NM_VT_WORDS + 2));
    if (!nm_o_save) nm_o_save = vt[114];
    c[2 + 114] = (u32)nm_vt_save;
    c[2 + 115] = (u32)nm_no_step;
    c[2 + 205] = (u32)nm_no_ambient;
    c[2 + 206] = (u32)nm_silent_sound;
    c[2 + 207] = (u32)nm_silent_sound;
    T->vt = c + 2;
}

static void nm_become(char *e, int t) {
    NmType *T = &nm_types[t];
    u32 *vt = *(u32 **)e;
    if (T->vt && vt == T->vt) return;
    if (!T->vt) nm_make_vt(T, vt);
    if (!T->vt) return;
    if (nm_setbaby && *(u32 *)e == nm_pz_vt) nm_setbaby(e, 0);
    *(u32 **)e = T->vt;
    if (sf_setsize) sf_setsize(e, T->width, T->height);
    nm_ent(e, 1);
}

static int nm_find(const char *name) {
    for (int t = 0; t < nm_ntypes; t++) if (streq(nm_types[t].name, name)) return t;
    for (int t = 0; t < nm_ntypes; t++) {                      /* "name" without the mod */
        const char *c = nm_types[t].name;
        while (*c && *c != ':') c++;
        if (*c == ':' && streq(c + 1, name)) return t;
    }
    return -1;
}

/* PigZombie::readAdditionalSaveData(CompoundTag const&): a saved mod mob gets its type back */
static u64 hc_nm_read(u32 orig, u32 *w) {
    u64 r = nh_callw(orig, w);
    char *e = (char *)w[0];
    void *tag = (void *)w[1];
    if (!nm_ntypes || !tag || !nm_contains || !nm_getstr) return r;
    u32 k[1];
    gstr(k, NM_TAG);
    if (nm_contains(tag, k)) {
        void *sv = nm_getstr(tag, k);
        const char *nm = sv ? sdata(sv) : 0;
        int t = nm ? nm_find(nm) : -1;
        if (t >= 0) {
            nm_become(e, t);
            u32 k2[1];
            gstr(k2, "NovaVals");
            if (nm_contains(tag, k2)) { void *vv = nm_getstr(tag, k2); if (vv) nm_load_values(e, &nm_types[t], sdata(vv)); }
            f_strdtor(k2);
        }
    }
    f_strdtor(k);
    return r;
}

/* ---------------- mod API ---------------- */
static void *nm_entity(double id) {
    char *pl = mm_player();
    void *lvl = pl ? *(void **)(pl + 0x78) : 0;
    return lvl && sf_getentity ? sf_getentity(lvl, (long long)id, 0) : 0;
}

static int nm_op(int op, const double *a, int n, const char *s, double *out) {
    char *pl = mm_player();
    if (!pl) return -1;
    if (op == OP_MOB_SPAWN) {
        int t = nm_find(s);
        if (t < 0 || n < 3) return 0;
        void *bs = mm_region(pl);
        if (!bs) return 0;
        float pos[3] = {(float)a[0], (float)a[1], (float)a[2]};
        void *level = nm_getlevel ? nm_getlevel(bs) : 0;
        if (!level || !nm_create) return 0;
        void *up = 0;
        nm_create(&up, NM_PIGZOMBIE, bs, pos);
        char *e = up;
        if (!e) return 0;
        if (nm_reset_attr) nm_reset_attr(e);
        if (nm_persistent) nm_persistent(e);
        float rot[2] = {0, 0};
        if (nm_moveto) nm_moveto(e, pos, rot);
        nm_become(e, t);
        long long uid = sf_uid(e);
        /* Level::addEntity(unique_ptr<Entity>) is virtual (slot 12): MultiPlayerLevel / ServerLevel */
        int ok = ((int (*)(void *, void **))((*(void ***)level)[12]))(level, &up);
        if (up) { ((void (*)(void *))((*(void ***)up)[1]))(up); up = 0; }   /* not taken: delete it */
        if (!ok) return 0;
        out[0] = (double)uid;
        return 1;
    }
    if (n < 1) return -1;
    char *e = nm_entity(a[0]);
    if (!e) return 0;
    if (op == OP_ENTITY_REMOVE) { ((void (*)(void *))((*(void ***)e)[5]))(e); return 0; }
    int t = nm_type_of(e);
    if (op == OP_MOB_INFO) { out[0] = t; return 1; }
    if (t < 0) return 0;
    NmEnt *E = nm_ent(e, 1);
    if (!E) return 0;
    int kl = 0;
    while (s[kl]) kl++;
    if (op == OP_MOB_SET) { nm_slot_set(&nm_types[t], E, s, kl, n > 1 ? (float)a[1] : 0); return 0; }
    if (op == OP_MOB_GET) {
        NmType *T = &nm_types[t];
        for (int i = 0; i < T->nslot; i++) if (streq(T->slot[i], s)) { out[0] = E->val[NM_CUSTOM0 + i]; return 1; }
        return 0;
    }
    return -1;
}


static void mobs_init(void *mc) {
    #define S(var, name) do { *(void **)&var = dlsym(mc, name); if (!var) LOGE("mobs: missing %s", name); } while (0)
    void **ti = dlsym(mc, "_ZN11Tessellator8instanceE");
    nm_tess = ti;
    S(nm_tbegin, "_ZN11Tessellator5beginEi");
    S(nm_tnormal, "_ZN11Tessellator6normalEfff");
    S(nm_tvertex, "_ZN11Tessellator8vertexUVEfffff");
    S(nm_tdraw, "_ZN11Tessellator4drawERKN3mce11MaterialPtrERKNS0_10TexturePtrE");
    S(nm_create, "_ZN10MobFactory9CreateMobE10EntityTypeR11BlockSourceRK4Vec3");
    S(nm_reset_attr, "_ZN3Mob15resetAttributesEv");
    S(nm_persistent, "_ZN3Mob13setPersistentEv");
    S(nm_moveto, "_ZN6Entity6moveToERK4Vec3RK4Vec2");
    S(nm_getlevel, "_ZN11BlockSource8getLevelEv");
    S(nm_ghast_ctor, "_ZN10GhastModelC1Ev");
    S(nm_mobrenderer_ctor, "_ZN11MobRendererC1ESt10unique_ptrI5ModelSt14default_deleteIS1_EEN3mce10TexturePtrEf");
    S(nm_renderer_init, "_ZN14EntityRenderer4initEP22EntityRenderDispatcher");
    S(nm_setbaby, "_ZN6Zombie7setBabyEb");
    S(nm_putstr, "_ZN11CompoundTag9putStringERKSsS1_");
    S(nm_getstr, "_ZNK11CompoundTag9getStringERKSs");
    S(nm_contains, "_ZNK11CompoundTag8containsERKSs");
    S(f_mob_health, "_ZNK3Mob9getHealthEv");
    #undef S
    u32 *pzvt = dlsym(mc, "_ZTV9PigZombie");
    nm_pz_vt = pzvt ? (u32)(pzvt + 2) : 0;
    nm_mc = mc;
    if (nm_ntypes) nm_hooks();
}

static void nm_hooks(void) {
    static int done;
    if (done || !nm_mc) return;
    done = 1;
    void *mc = nm_mc;
    void *gr = dlsym(mc, "_ZN22EntityRenderDispatcher11getRendererER6Entity");
    void *rd = dlsym(mc, "_ZN9PigZombie22readAdditionalSaveDataERK11CompoundTag");
    if (gr) nh_hook_c((u32)gr, hc_nm_getrenderer); else LOGE("mobs: no getRenderer");
    if (rd) nh_hook_c((u32)rd, hc_nm_read); else LOGE("mobs: no PigZombie::readAdditionalSaveData");
    LOG("mobs: %d type(s), hooks in", nm_ntypes);
}
