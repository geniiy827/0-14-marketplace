/* ---------------- McMod Script bridge (minimal) ------------------------------
 * Java ModRuntime <-> game, all on the game thread. No game-logic hooks: events are
 * world.join / world.leave / tick, detected from the client update.
 * Mod blocks: Java sends the definitions at start (nativeDefineBlocks), they are registered
 * right after Item::initCreativeItems (gate) and model blocks are drawn by a gate on
 * BlockTessellator::tessellateInWorld.
 * Operation and event numbers come from mcmod_ops.h (tools/gen_mcmod.py from mcmod/api). */
#include "mcmod_ops.h"

typedef struct { float x, y, z; } MVec3;
typedef struct { int x, y, z; } MPos;
typedef struct { unsigned char v; } MBlockID;
typedef union { int i; void *l; double d; long long j; } MJValue;

static void *mm_vm, *mm_cls, *mm_event;
static int mm_ok;

/* game functions */
static void (*mf_gd_msg)(void *gd, void *str);
static void (*mf_gd_tip)(void *gd, void *str);
static void (*mf_gd_popup)(void *gd, void *a, void *b);
static unsigned char (*mf_getblock)(void *bs, int x, int y, int z);
static unsigned char (*mf_getdata)(void *bs, int x, int y, int z);
static int (*mf_setblock)(void *bs, int x, int y, int z, const MBlockID *id, unsigned char data, int flags);   /* BlockID goes by pointer (non-trivial type) */
static void *(*mf_getlevel)(void *bs);
static int (*mf_gettime)(void *lvl);
static void (*mf_settime)(void *lvl, int t);
static void *(*mf_item_lookup)(void *str, int b);
static void *(*mf_block_lookup)(void *str, int b);

static int sc_op(int op, const double *a, int n, double *out);
static int ev_op(int op, const double *a, int n, char *s, double *out);
static void ev_frame(void);
static char *mm_player(void) { return g_client ? *(char **)((char *)g_client + 0x120) : 0; }
static void *mm_region(void *pl) { return pl ? *(void **)((char *)pl + 0x16c) : 0; }

/* ---------------- Java side ---------------- */
static void **mm_env(void) {
    void **env = 0;
    void **vmf = *(void ***)mm_vm;
    typedef int (*getenv_fn)(void *, void ***, int);
    typedef int (*attach_fn)(void *, void ***, void *);
    if (((getenv_fn)vmf[6])(mm_vm, &env, 0x10006) != 0 || !env) {
        if (((attach_fn)vmf[4])(mm_vm, &env, 0) != 0) return 0;
    }
    return env;
}

int Java_com_nova_client_ModBridge_nativeModInit(void **env, void *cls) {
    void **f = *(void ***)env;
    if (((int (*)(void *, void **))f[219])(env, &mm_vm) != 0) return -1;            /* GetJavaVM */
    mm_cls = ((void *(*)(void *, void *))f[21])(env, cls);                         /* NewGlobalRef */
    mm_event = ((void *(*)(void *, void *, const char *, const char *))f[113])(env, mm_cls, "event", "(ILjava/lang/String;[D)I");
    mm_ok = mm_cls && mm_event;
    LOG("mcmod bridge %s", mm_ok ? "ready" : "FAILED");
    return mm_ok ? 0 : -2;
}

static void mm_clean_utf(char *s) {
    for (unsigned char *p = (unsigned char *)s; *p; p++)
        if (*p >= 0xf0) { int n = 1; while (p[n] >= 0x80 && p[n] < 0xc0 && n < 4) n++; for (int k = 0; k < n; k++) p[k] = '?'; p += n - 1; }
}

static int mm_fire(int ev, const char *text, const double *a, int n) {
    if (!mm_ok) return 0;
    void **env = mm_env();
    if (!env) return 0;
    void **f = *(void ***)env;
    if (((int (*)(void *, int))f[19])(env, 8) != 0) return 0;                      /* PushLocalFrame */
    void *js = 0;
    if (text) {
        char buf[1024];
        int k = 0;
        while (text[k] && k < 1023) { buf[k] = text[k]; k++; }
        buf[k] = 0;
        mm_clean_utf(buf);
        js = ((void *(*)(void *, const char *))f[167])(env, buf);
    }
    void *arr = ((void *(*)(void *, int))f[182])(env, n);
    if (arr && n) ((void (*)(void *, void *, int, int, const double *))f[214])(env, arr, 0, n, a);
    MJValue args[3];
    args[0].j = 0; args[0].i = ev;
    args[1].j = 0; args[1].l = js;
    args[2].j = 0; args[2].l = arr;
    int r = ((int (*)(void *, void *, void *, MJValue *))f[131])(env, mm_cls, mm_event, args);
    if (((int (*)(void *))f[228])(env)) {                                          /* ExceptionCheck */
        ((void (*)(void *))f[16])(env);
        ((void (*)(void *))f[17])(env);
        r = 0;
    }
    ((void *(*)(void *, void *))f[20])(env, 0);                                    /* PopLocalFrame */
    return r;
}

/* ---------------- operations ---------------- */
static char *mm_second(char *s) {
    char *p = s;
    while (*p && *p != '\x1f') p++;
    if (*p) { *p = 0; return p + 1; }
    return p;
}

static int mm_op(int op, const double *a, int n, char *s, double *out) {
    char *pl = mm_player();
    void *bs = mm_region(pl);
    void *gd = g_client ? *(void **)((char *)g_client + 0x130) : 0;
    u32 st[1], st2[1];
    #define A(i) ((i) < n ? a[i] : 0.0)
    #define FL(i) ((int)floorf((float)A(i)))
    switch (op) {
    case OP_CHAT: case OP_TIP: case OP_POPUP: {
        if (!gd) return -1;
        if (op == OP_POPUP) {
            char *b = mm_second(s);
            if (!mf_gd_popup) return 0;
            gstr(st, s); gstr(st2, b);
            mf_gd_popup(gd, st, st2);
            f_strdtor(st); f_strdtor(st2);
            return 0;
        }
        void (*fn)(void *, void *) = op == OP_CHAT ? mf_gd_msg : mf_gd_tip;
        if (!fn) return 0;
        gstr(st, s);
        fn(gd, st);
        f_strdtor(st);
        return 0;
    }
    case OP_ITEM_ID: {
        if (!mf_item_lookup) return -1;
        gstr(st, s);
        char *it = mf_item_lookup(st, 0);
        int id = it ? *(short *)(it + 18) : -1;
        if (id < 0 && mf_block_lookup) {
            char *b = mf_block_lookup(st, 0);
            if (b) id = *(unsigned char *)(b + 4);
        }
        f_strdtor(st);
        out[0] = id;
        return 1;
    }
    default: break;
    }
    if (!pl || !bs) return -1;
    void *lvl = mf_getlevel ? mf_getlevel(bs) : 0;
    switch (op) {
    case OP_PLAYER_POS: {
        float *p = (float *)(pl + 8);
        out[0] = p[0]; out[1] = p[1] - 1.62f; out[2] = p[2];
        out[3] = *(float *)(pl + 60); out[4] = *(float *)(pl + 56);
        return 5;
    }
    case OP_WORLD_BLOCK:
        out[0] = mf_getblock ? mf_getblock(bs, FL(0), FL(1), FL(2)) : 0;
        out[1] = mf_getdata ? mf_getdata(bs, FL(0), FL(1), FL(2)) : 0;
        return 2;
    case OP_WORLD_SET_BLOCK: {
        if (!mf_setblock || FL(1) < 0 || FL(1) > 127) return 0;
        MBlockID id = {(unsigned char)(int)A(3)};
        mf_setblock(bs, FL(0), FL(1), FL(2), &id, (unsigned char)(int)A(4), 3);
        return 0;
    }
    case OP_WORLD_TIME: out[0] = lvl && mf_gettime ? mf_gettime(lvl) : 0; return 1;
    case OP_WORLD_SET_TIME: if (lvl && mf_settime) mf_settime(lvl, (int)A(0)); return 0;
    default: return -1;
    }
    #undef A
    #undef FL
}

void *Java_com_nova_client_ModBridge_nativeApi(void **env, void *cls, int op, void *darr, void *jstr) {
    void **f = *(void ***)env;
    double a[16];
    int n = 0;
    if (darr) {
        n = ((int (*)(void *, void *))f[171])(env, darr);
        if (n > 16) n = 16;
        if (n) ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, a);
    }
    static char s[2048];
    jstr_copy(env, jstr, s, sizeof s);
    double out[72];
    int r = (op == OP_PLAYER_STATE || op == OP_PLAYER_TARGET || op == OP_UI_ACTION) ? ev_op(op, a, n, s, out)
        : (op == OP_ENTITY_LIST || op == OP_ENTITY_INFO || op == OP_ENTITY_SCALE || op == OP_ENTITY_SET_SCALE || op == OP_PLAYER_ID)
        ? sc_op(op, a, n, out) : mm_op(op, a, n, s, out);
    if (r < 0) return 0;
    void *arr = ((void *(*)(void *, int))f[182])(env, r);
    if (arr && r) ((void (*)(void *, void *, int, int, const double *))f[214])(env, arr, 0, r, out);
    return arr;
}

void *Java_com_nova_client_ModBridge_nativeApiStr(void **env, void *cls, int op, void *darr, void *jstr) { return 0; }

/* ---------------- mod blocks ----------------
 * nativeDefineBlocks(double[] data, String names): per block
 *   id, kind (0 classic, 1 model), solid, light, lightBlock, hardness, resistance, materialFrom, layer,
 *   box x0 y0 z0 x1 y1 z1 (0..1, classic only),
 *   6 faces x (u0 v0 u1 v1)    classic: texture of each face; model: particles (face 0)
 *   ncubes, then per cube: x0 y0 z0 x1 y1 z1, 6 faces x (u0 v0 u1 v1) (u0 < 0: no face)
 * faces: 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x). UVs are atlas 0..1. */
#define MB_MAX 64
#define MB_CUBE 36   /* box 6, uv 24, rotation x y z (degrees), pivot x y z (block units) */
typedef struct { float b[4]; unsigned short size[2]; void *filler; void *file; } TexSet;   /* TextureUVCoordinateSet */
typedef struct {
    int id, kind, solid, light, lightblock, matfrom, layer;
    float hardness, resistance, box[6];
    TexSet tex[6];
    int ncubes;
    float *cubes;                        /* ncubes * MB_CUBE */
    int ncol;
    float *col;                          /* ncol * 6, block units */
    float sel[6];                        /* selection / outline box */
    char name[64];
    void *block;
} ModBlock;
static ModBlock mblocks[MB_MAX];
static int mblock_count, mblocks_done;
static ModBlock *mblock_of[256];

static void **mf_blocks, **mf_items;
static unsigned char *mf_light_emission, *mf_light_block, *mf_solid;
static void (*mf_block_ctor)(void *b, void *name, int id, void *material);
static void (*mf_blockitem_ctor)(void *it, void *name, int id);
static void (*mf_block_setsolid)(void *b, int solid);
static void (*mf_block_setcat)(void *b, int cat);
static void (*mf_item_setcat)(void *it, int cat);
static void (*mf_add_creative)(short id, short aux);
static void (*mf_block_setshape)(void *b, MVec3 *a, MVec3 *c);
static void (*mf_tess_color)(void *t, float r, float g, float b, float a);
static void (*mf_tess_vertex)(void *t, float x, float y, float z, float u, float v);
static void (*mf_bt_light)(void *out, void *bt, MPos *pos);   /* sret BrightnessPair */
static void (*mf_touv)(float *out, void *pair);
static void (*mf_tex1)(void *bt, float *uv);
static u32 *mb_vtable;                     /* copy of Block's vtable with our texture getters */
#define VT_BLOCK_WORDS 110

int Java_com_nova_client_ModBridge_nativeDefineBlocks(void **env, void *cls, void *darr, void *jnames) {
    void **f = *(void ***)env;
    if (!darr || mblocks_done) return -1;
    int n = ((int (*)(void *, void *))f[171])(env, darr);
    double *d = f_new(n * 8 + 8);
    ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, d);
    static char names[4096];
    jstr_copy(env, jnames, names, sizeof names);
    char *np = names;
    int p = 0;
    mblock_count = 0;
    while (p + 40 <= n && mblock_count < MB_MAX) {
        ModBlock *m = &mblocks[mblock_count];
        m->id = (int)d[p]; m->kind = (int)d[p + 1]; m->solid = (int)d[p + 2]; m->light = (int)d[p + 3];
        m->lightblock = (int)d[p + 4]; m->hardness = (float)d[p + 5]; m->resistance = (float)d[p + 6];
        m->matfrom = (int)d[p + 7]; m->layer = (int)d[p + 8];
        for (int k = 0; k < 6; k++) m->box[k] = (float)d[p + 9 + k];
        for (int k = 0; k < 6; k++) {
            TexSet *t = &m->tex[k];
            for (int c = 0; c < 4; c++) t->b[c] = (float)d[p + 15 + k * 4 + c];
            if (t->b[0] < 0) { for (int c = 0; c < 4; c++) t->b[c] = m->tex[0].b[c]; }
            t->size[0] = t->size[1] = 16;
            t->filler = 0; t->file = 0;
        }
        p += 39;
        m->ncubes = (int)d[p++];
        if (m->ncubes < 0 || p + m->ncubes * MB_CUBE > n) break;
        m->cubes = m->ncubes ? f_new(m->ncubes * MB_CUBE * 4) : 0;
        for (int k = 0; k < m->ncubes * MB_CUBE; k++) m->cubes[k] = (float)d[p + k];
        p += m->ncubes * MB_CUBE;
        if (p >= n) break;
        m->ncol = (int)d[p++];
        if (m->ncol < 0 || p + m->ncol * 6 + 6 > n) break;
        m->col = m->ncol ? f_new(m->ncol * 6 * 4) : 0;
        for (int k = 0; k < m->ncol * 6; k++) m->col[k] = (float)d[p + k];
        p += m->ncol * 6;
        for (int k = 0; k < 6; k++) m->sel[k] = (float)d[p + k];
        p += 6;
        int k = 0;
        while (*np && *np != '\x1f' && k < 63) m->name[k++] = *np++;
        m->name[k] = 0;
        if (*np == '\x1f') np++;
        if (m->id > 0 && m->id < 256) mblock_count++;
    }
    LOG("mcmod: %d block definition(s)", mblock_count);
    return mblock_count;
}

/* texture getters of mod blocks (Block vtable slots 76, 77, 78 and getCarriedTexture 7) */
static TexSet *mb_tex(void *block, int face) {
    ModBlock *m = mblock_of[*((unsigned char *)block + 4)];
    if (!m) return (TexSet *)((char *)block + 16);
    if (face < 0 || face > 5) face = 0;
    TexSet *t = &m->tex[face];
    if (!t->file && mf_blocks && mf_blocks[1]) {            /* atlas pointer as in stone's texture */
        TexSet *stone = (TexSet *)((char *)mf_blocks[1] + 16);
        t->file = stone->file; t->filler = stone->filler; t->size[0] = stone->size[0]; t->size[1] = stone->size[1];
    }
    return t;
}
static void *mb_tex_a(void *b, signed char f) { return mb_tex(b, f); }
static void *mb_tex_ai(void *b, signed char f, int d) { return mb_tex(b, f); }
static void *mb_tex_world(void *b, void *bs, void *pos, signed char f) { return mb_tex(b, f); }

/* collision: any number of boxes per block */
typedef struct { float v[6]; unsigned char empty, pad[3]; } MAABB;
static int (*mf_add_aabb)(void *b, MAABB *box, void *clip, void *vec);
static void mb_add_shapes(void *b, void *bs, MPos *pos, void *clip, void *vec, void *ent) {
    ModBlock *m = mblock_of[*((unsigned char *)b + 4)];
    if (!m || !mf_add_aabb) return;
    for (int i = 0; i < m->ncol; i++) {
        float *c = m->col + i * 6;
        MAABB a = {{c[0] + pos->x, c[1] + pos->y, c[2] + pos->z, c[3] + pos->x, c[4] + pos->y, c[5] + pos->z}, 0, {0, 0, 0}};
        mf_add_aabb(b, &a, clip, vec);
    }
}
static MAABB *mb_get_aabb(void *b, void *bs, MPos *pos, MAABB *out, int x, int y, int z) {
    ModBlock *m = mblock_of[*((unsigned char *)b + 4)];
    float *s = m ? m->sel : 0;
    float def[6] = {0, 0, 0, 1, 1, 1};
    if (!s) s = def;
    for (int k = 0; k < 3; k++) { out->v[k] = s[k] + (&pos->x)[k]; out->v[k + 3] = s[k + 3] + (&pos->x)[k]; }
    out->empty = 0;
    return out;
}

static void mcmod_register_blocks(void) {
    if (mblocks_done || !mblock_count) return;
    mblocks_done = 1;
    if (!mf_blocks || !mf_items || !mf_block_ctor || !mf_blockitem_ctor) { LOGE("mcmod: block registration unavailable"); return; }
    u32 *base = dlsym(dlopen("libminecraftpe.so", 0x4 | 0x2), "_ZTV5Block");
    if (!base) return;
    mb_vtable = f_new(VT_BLOCK_WORDS * 4);
    for (int i = 0; i < VT_BLOCK_WORDS; i++) mb_vtable[i] = base[i];
    mb_vtable[2 + 76] = (u32)mb_tex_a;
    mb_vtable[2 + 77] = (u32)mb_tex_ai;
    mb_vtable[2 + 78] = (u32)mb_tex_world;
    mb_vtable[2 + 7] = (u32)mb_tex_ai;                      /* getCarriedTexture */
    mb_vtable[2 + 9] = (u32)mb_get_aabb;                    /* outline / pick box */
    mb_vtable[2 + 8] = (u32)mb_add_shapes;                  /* addAABBs (stairs-style multi-box collision) */
    mb_vtable[2 + 10] = (u32)mb_add_shapes;                 /* addCollisionShapes */
    int done = 0;
    for (int i = 0; i < mblock_count; i++) {
        ModBlock *m = &mblocks[i];
        if (mf_blocks[m->id] && mf_items[m->id]) { LOGE("mcmod: block id %d is taken, %s skipped", m->id, m->name); continue; }
        void *from = m->matfrom > 0 && m->matfrom < 256 ? mf_blocks[m->matfrom] : 0;
        if (!from) from = mf_blocks[1];
        void *mat = *(void **)((char *)from + 84);
        char *b = f_new(0x98);
        for (int k = 0; k < 0x98; k++) b[k] = 0;
        u32 nm[1];
        gstr(nm, m->name);
        mf_block_ctor(b, nm, m->id, mat);
        *(u32 *)b = (u32)(mb_vtable + 2);
        mblock_of[m->id] = m;
        m->block = b;
        /* 0.14.3: +48 render layer (3 opaque, 8 alpha-tested like glass), +52 block shape (0 cube), +56 flags: keep */
        *(int *)(b + 48) = (m->kind == 1 || m->layer == 3) ? 4 : 3;   /* 4 = alpha-tested (fancy leaves); 8 = glass (sorted, broke models) */
        *(int *)(b + 52) = 0;
        if (m->kind != 1 && (m->box[0] > 0 || m->box[1] > 0 || m->box[2] > 0 || m->box[3] < 1 || m->box[4] < 1 || m->box[5] < 1)) {
            MVec3 lo = {m->box[0], m->box[1], m->box[2]}, hi = {m->box[3], m->box[4], m->box[5]};
            if (mf_block_setshape) mf_block_setshape(b, &lo, &hi);
        }
        *(float *)(b + 112) = m->hardness;
        *(float *)(b + 116) = m->resistance > m->hardness * 5 ? m->resistance : m->hardness * 5;
        if (mf_block_setsolid) mf_block_setsolid(b, m->solid);      /* also sets mLightBlock (0..15) */
        if (mf_light_emission) mf_light_emission[m->id] = (unsigned char)(m->light < 0 ? 0 : m->light > 15 ? 15 : m->light);
        /* breaking particles use the block's own texture */
        TexSet *own = (TexSet *)(b + 16), *t = mb_tex(b, 1);
        for (int c = 0; c < 4; c++) own->b[c] = t->b[c];
        mf_blocks[m->id] = b;      /* replaces initBlocks' unnamed placeholder for free ids */
        ((void (*)(void *))mb_vtable[2 + 94])(b);                /* Block::init: translucency from material */
        if (mf_block_setcat) mf_block_setcat(b, m->kind == 1 ? 2 : 1);   /* 1 blocks, 2 decorations */
        char *it = f_new(64);
        for (int k = 0; k < 64; k++) it[k] = 0;
        mf_blockitem_ctor(it, nm, m->id - 256);
        f_strdtor(nm);
        mf_items[m->id] = it;
        if (mf_item_setcat) mf_item_setcat(it, m->kind == 1 ? 2 : 1);
        done++;
    }
    LOG("mcmod: %d block(s) registered", done);
}

static void mcmod_register_items(void);
static void (*o_init_creative)(void);
static void h_init_creative(void) {
    o_init_creative();
    mcmod_register_blocks();
    mcmod_register_items();
    for (int i = 0; i < mblock_count; i++)
        if (mblocks[i].block && mf_add_creative) mf_add_creative((short)mblocks[i].id, 0);
}

/* ---- model drawing: shared by the world (chunks), the inventory icon and the item in hand ---- */
static void (*mf_tess_begin)(void *t, int n);
static void (*mf_tess_addoff)(void *t, float x, float y, float z);
static void (*mf_tess_draw)(void *t, void *mat, void *tex);
static void *mf_tess_instance;

/* rotation of one cube: Blockbench order (three.js 'ZYX': x first, then y, then z) about the pivot */
typedef struct { float m[9], o[3]; int on; } MRot;
static void mb_rot_setup(MRot *r, const float *c) {
    float rx = c[30] * 0.01745329f, ry = c[31] * 0.01745329f, rz = c[32] * 0.01745329f;
    r->on = rx != 0 || ry != 0 || rz != 0;
    if (!r->on) return;
    float cx = cosf(rx), sx = sinf(rx), cy = cosf(ry), sy = sinf(ry), cz = cosf(rz), sz = sinf(rz);
    /* M = Rz * Ry * Rx */
    r->m[0] = cz * cy; r->m[1] = cz * sy * sx - sz * cx; r->m[2] = cz * sy * cx + sz * sx;
    r->m[3] = sz * cy; r->m[4] = sz * sy * sx + cz * cx; r->m[5] = sz * sy * cx - cz * sx;
    r->m[6] = -sy;     r->m[7] = cy * sx;                r->m[8] = cy * cx;
    r->o[0] = c[33]; r->o[1] = c[34]; r->o[2] = c[35];
}

/* corners of one face of a cube (after inset), rotated */
static const float mb_shade[6] = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};
static const float mb_norm[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
static void mb_quad(float q[4][3], int face, const float *c, const MRot *r, float inset) {
    float x0 = c[0], y0 = c[1], z0 = c[2], x1 = c[3], y1 = c[4], z1 = c[5];
    if (inset > 0) {
        if (x1 - x0 > 4 * inset) { x0 += inset; x1 -= inset; }
        if (y1 - y0 > 4 * inset) { y0 += inset; y1 -= inset; }
        if (z1 - z0 > 4 * inset) { z0 += inset; z1 -= inset; }
    }
    #define V(i, a, b, c_) do { q[i][0] = a; q[i][1] = b; q[i][2] = c_; } while (0)
    switch (face) {
    case 0: V(0, x0, y0, z1); V(1, x1, y0, z1); V(2, x1, y0, z0); V(3, x0, y0, z0); break;   /* down */
    case 1: V(0, x0, y1, z0); V(1, x1, y1, z0); V(2, x1, y1, z1); V(3, x0, y1, z1); break;   /* up */
    case 2: V(0, x1, y1, z0); V(1, x0, y1, z0); V(2, x0, y0, z0); V(3, x1, y0, z0); break;   /* north */
    case 3: V(0, x0, y1, z1); V(1, x1, y1, z1); V(2, x1, y0, z1); V(3, x0, y0, z1); break;   /* south */
    case 4: V(0, x0, y1, z0); V(1, x0, y1, z1); V(2, x0, y0, z1); V(3, x0, y0, z0); break;   /* west */
    default: V(0, x1, y1, z1); V(1, x1, y1, z0); V(2, x1, y0, z0); V(3, x1, y0, z1); break; /* east */
    }
    #undef V
    if (r && r->on) {
        for (int k = 0; k < 4; k++) {
            float a = q[k][0] - r->o[0], b = q[k][1] - r->o[1], d = q[k][2] - r->o[2];
            q[k][0] = r->m[0] * a + r->m[1] * b + r->m[2] * d + r->o[0];
            q[k][1] = r->m[3] * a + r->m[4] * b + r->m[5] * d + r->o[1];
            q[k][2] = r->m[6] * a + r->m[7] * b + r->m[8] * d + r->o[2];
        }
    }
}

/* both windings: the world draws back faces culled, so each side shows once */
static void mb_put(void *t, float q[4][3], const float *uv, float shade, float alpha, float px, float py, float pz, int outer_only) {
    float uvs[4][2] = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
    mf_tess_color(t, shade, shade, shade, alpha);
    /* hand (one_side): only the q-order winding - the reversed one is lit black there (zebra / black face) */
    for (int k = 0; k < 4; k++) mf_tess_vertex(t, q[k][0] + px, q[k][1] + py, q[k][2] + pz, uvs[k][0], uvs[k][1]);
    if (!outer_only) for (int k = 3; k >= 0; k--) mf_tess_vertex(t, q[k][0] + px, q[k][1] + py, q[k][2] + pz, uvs[k][0], uvs[k][1]);
}

/* mode 0: world, 1: item in hand (tiny inset: touching cubes do not flicker), 2: inventory icon
 * (only faces turned to the viewer, far to near: the icon has no reliable depth test) */
static void mb_emit(void *t, ModBlock *m, float px, float py, float pz, float bright, float alpha, int mode) {
    float inset = mode ? 0.0008f : 0.f;
    if (mode != 2) {
        for (int i = 0; i < m->ncubes; i++) {
            const float *c = m->cubes + i * MB_CUBE;
            MRot r;
            mb_rot_setup(&r, c);
            for (int f = 0; f < 6; f++) {
                const float *uv = c + 6 + f * 4;
                if (uv[0] < 0) continue;
                float q[4][3];
                mb_quad(q, f, c, &r, inset);
                mb_put(t, q, uv, mb_shade[f] * bright, alpha, px, py, pz, mode == 1);   /* hand: no culling there, two windings striped */
            }
        }
        return;
    }
    static const float view[3] = {-0.612f, 0.5f, 0.612f};      /* icon camera: sees up, south, west */
    enum { QMAX = 384 };
    static float qs[QMAX][4][3], key[QMAX];
    static const float *quv[QMAX];
    static float qsh[QMAX];
    int n = 0;
    for (int i = 0; i < m->ncubes && n < QMAX; i++) {
        const float *c = m->cubes + i * MB_CUBE;
        MRot r;
        mb_rot_setup(&r, c);
        for (int f = 0; f < 6 && n < QMAX; f++) {
            const float *uv = c + 6 + f * 4;
            if (uv[0] < 0) continue;
            const float *nn = mb_norm[f];
            float nx = nn[0], ny = nn[1], nz = nn[2];
            if (r.on) {
                nx = r.m[0] * nn[0] + r.m[1] * nn[1] + r.m[2] * nn[2];
                ny = r.m[3] * nn[0] + r.m[4] * nn[1] + r.m[5] * nn[2];
                nz = r.m[6] * nn[0] + r.m[7] * nn[1] + r.m[8] * nn[2];
            }
            if (nx * view[0] + ny * view[1] + nz * view[2] <= 0.001f) continue;
            mb_quad(qs[n], f, c, &r, inset);
            float cx = 0, cy = 0, cz = 0;
            for (int k = 0; k < 4; k++) { cx += qs[n][k][0]; cy += qs[n][k][1]; cz += qs[n][k][2]; }
            key[n] = cx * view[0] + cy * view[1] + cz * view[2];
            quv[n] = uv; qsh[n] = mb_shade[f] * bright;
            n++;
        }
    }
    int order[QMAX];
    for (int i = 0; i < n; i++) order[i] = i;
    for (int i = 1; i < n; i++) {                                 /* insertion sort, far (small key) first */
        int v = order[i], j = i - 1;
        while (j >= 0 && key[order[j]] > key[v]) { order[j + 1] = order[j]; j--; }
        order[j + 1] = v;
    }
    for (int i = 0; i < n; i++) mb_put(t, qs[order[i]], quv[order[i]], qsh[order[i]], alpha, px, py, pz, 0);
}

static ModBlock *mb_model_of_id(int id) {
    ModBlock *m = (id > 0 && id < 256) ? mblock_of[id] : 0;
    return m && m->kind == 1 && m->block && m->ncubes ? m : 0;
}

static int (*o_tess)(void *bt, void *block, MPos *pos, unsigned char data, int b);
static int h_tess(void *bt, void *block, MPos *pos, unsigned char data, int b) {
    ModBlock *m = block ? mblock_of[*((unsigned char *)block + 4)] : 0;
    if (!m || m->kind != 1 || m->block != block) return o_tess(bt, block, pos, data, b);
    void *t = *(void **)((char *)bt + 0x2cc);
    if (!t || !mf_tess_color || !mf_tess_vertex || !mf_bt_light || !mf_touv || !mf_tex1) return o_tess(bt, block, pos, data, b);
    { unsigned char lc[8] = {0}; float luv[2] = {0, 0};     /* same light setup as the start of tessellateInWorld */
      mf_bt_light(lc, bt, pos); mf_touv(luv, lc); mf_tex1(bt, luv); }
    mb_emit(t, m, (float)pos->x, (float)pos->y, (float)pos->z, 1.f, 1.f, 0);
    return 1;
}

/* item in hand / dropped item mesh: like the anvil branch of appendTessellatedBlock */
static void (*o_append)(void *bt, unsigned char *fb);
static void h_append(void *bt, unsigned char *fb) {
    ModBlock *m = fb ? mb_model_of_id(fb[0]) : 0;
    void *t = *(void **)((char *)bt + 0x2cc);
    if (!m || !t || !mf_tess_addoff) { o_append(bt, fb); return; }
    char *b = bt;
    b[1] = 1; b[0x28] = 1;
    mf_tess_addoff(t, -0.5f, -0.5f, -0.5f);
    mb_emit(t, m, 0, 0, 0, 1.f, 1.f, 1);
    mf_tess_addoff(t, 0.5f, 0.5f, 0.5f);
    b[0x28] = 0; b[1] = 0;
}

/* inventory icon: like the anvil branch of renderGuiBlock (begin, tessellate at 0..1, draw with the bt material) */
static void (*o_gui)(void *bt, unsigned char *fb, void *tex, float bright, float alpha);
static void h_gui(void *bt, unsigned char *fb, void *tex, float bright, float alpha) {
    ModBlock *m = fb ? mb_model_of_id(fb[0]) : 0;
    void *t = *(void **)((char *)bt + 0x2cc);
    if (!m || !t || !mf_tess_begin || !mf_tess_draw || !mf_tess_instance) { o_gui(bt, fb, tex, bright, alpha); return; }
    char *b = bt;
    char keep = b[1];
    b[0x28] = 1; b[1] = 0;
    mf_tess_begin(t, m->ncubes * 48);
    mb_emit(t, m, 0, 0, 0, bright, alpha, 2);
    mf_tess_draw(mf_tess_instance, b + 0x2c0, tex);
    b[1] = keep; b[0x28] = 0;
}

/* world join / leave and ticks from the client loop (no game hooks) */
static char *mm_last_pl;
static long mm_t0, mm_ticks;
static void sc_frame(void);
static void mcmod_frame(void *client) {
    sc_frame();
    if (mm_ok) ev_frame();
    if (!mblocks_done && mblock_count) mcmod_register_blocks();   /* in case initCreativeItems ran before the definitions */
    if (!mm_ok) return;
    char *pl = *(char **)((char *)client + 0x120);
    if (pl && !mm_last_pl) {
        mm_last_pl = pl;
        mm_t0 = now_ms();
        mm_ticks = 0;
        mm_fire(EV_WORLD_JOIN, 0, 0, 0);
    } else if (!pl && mm_last_pl) {
        mm_last_pl = 0;
        mm_fire(EV_WORLD_LEAVE, 0, 0, 0);
    }
    if (!pl) return;
    mm_last_pl = pl;
    long due = (now_ms() - mm_t0) / 50;
    if (due - mm_ticks > 10) mm_ticks = due - 10;
    for (int k = 0; k < 3 && mm_ticks < due; k++, mm_ticks++) mm_fire(EV_TICK, 0, 0, 0);
}

static void mcmod_init(void *mc, u32 base) {
    #define S(var, name) do { *(void **)&var = dlsym(mc, name); if (!var) LOGE("mcmod: missing %s", name); } while (0)
    S(mf_gd_msg, "_ZN7GuiData20displayClientMessageERKSs");
    S(mf_gd_tip, "_ZN7GuiData14showTipMessageERKSs");
    S(mf_gd_popup, "_ZN7GuiData15showPopupNoticeERKSsS1_");
    S(mf_getblock, "_ZN11BlockSource10getBlockIDEiii");
    S(mf_getdata, "_ZN11BlockSource7getDataEiii");
    S(mf_setblock, "_ZN11BlockSource15setBlockAndDataEiii7BlockIDhi");
    S(mf_getlevel, "_ZN11BlockSource8getLevelEv");
    S(mf_gettime, "_ZNK5Level7getTimeEv");
    S(mf_settime, "_ZN5Level7setTimeEi");
    S(mf_item_lookup, "_ZN4Item12lookupByNameERKSsb");
    S(mf_block_lookup, "_ZN5Block12lookupByNameERKSsb");
    S(mf_blocks, "_ZN5Block7mBlocksE");
    S(mf_items, "_ZN4Item6mItemsE");
    S(mf_light_emission, "_ZN5Block14mLightEmissionE");
    S(mf_light_block, "_ZN5Block11mLightBlockE");
    S(mf_solid, "_ZN5Block6mSolidE");
    S(mf_block_ctor, "_ZN5BlockC1ERKSsiRK8Material");
    S(mf_blockitem_ctor, "_ZN9BlockItemC1ERKSsi");
    S(mf_block_setsolid, "_ZN5Block8setSolidEb");
    S(mf_block_setcat, "_ZN5Block11setCategoryE20CreativeItemCategory");
    S(mf_item_setcat, "_ZN4Item11setCategoryE20CreativeItemCategory");
    S(mf_add_creative, "_ZN4Item15addCreativeItemEss");
    S(mf_block_setshape, "_ZN5Block14setVisualShapeERK4Vec3S2_");
    S(mf_tess_color, "_ZN11Tessellator5colorEffff");
    S(mf_tess_vertex, "_ZN11Tessellator8vertexUVEfffff");
    S(mf_bt_light, "_ZN16BlockTessellator13getLightColorERK8BlockPos");
    S(mf_touv, "_Z4toUV14BrightnessPair");
    S(mf_tex1, "_ZN16BlockTessellator5_tex1ERK4Vec2");
    S(mf_tess_begin, "_ZN11Tessellator5beginEi");
    S(mf_add_aabb, "_ZN5Block7addAABBERK4AABBPS1_RSt6vectorIS0_SaIS0_EE");
    S(mf_tess_addoff, "_ZN11Tessellator9addOffsetEfff");
    S(mf_tess_draw, "_ZN11Tessellator4drawERKN3mce11MaterialPtrERKNS0_10TexturePtrE");
    S(mf_tess_instance, "_ZN11Tessellator8instanceE");
    #undef S
    GATE(INIT_CREATIVE, h_init_creative, o_init_creative);
    GATE(TESS_IN_WORLD, h_tess, o_tess);
    GATE(TESS_APPEND, h_append, o_append);
    GATE(GUI_BLOCK, h_gui, o_gui);
}

/* TerrainParticle::render picks a 4x4-pixel piece with fixed steps made for a 512x256 atlas:
 * u step 1/128 (0x363d0c), v step 1/64 (0x363d10), sizes 0x363d14/0x363d18. A taller atlas (mod block strip)
 * needs the v constants scaled, or break/step particles show pieces of other textures. */
int Java_com_nova_client_NovaActivity_nativeTerrainHeight(void *env, void *cls, int h) {
    if (!mcbase || h <= 0) return -1;
    float step = 4.0f / (float)h, size = step * 0.99902f;
    u32 a = mcbase + 0x363d10, page = a & ~0xfffu;
    if (*(u32 *)a != 0x3c800000u && *(float *)a != step) { LOGE("particles: unexpected constant %08x", *(u32 *)a); return -2; }
    if (mprotect((void *)page, 4096, 7) != 0 && mprotect((void *)page, 4096, 3) != 0) { LOGE("particles: mprotect failed"); return -3; }
    *(float *)a = step;
    *(float *)(a + 8) = size;
    mprotect((void *)page, 4096, 5);
    LOG("particles: atlas height %d", h);
    return 0;
}
