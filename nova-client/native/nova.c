/* libnova.so - NOVA native layer for MCPE 0.14.3 (armeabi-v7a).
 *
 * 1. Bone trees.  ModelPart::load(GeometryPtr const&, std::string const&)
 *    loads one bone and ignores "parent", so bones added in Blockbench never
 *    show.  After the original load we attach every geometry node named
 *    "<name>><child>" as a child ModelPart (recursively).  EntityRegistry.java
 *    writes those names when it builds Mobs.json.  The call reaches us through
 *    a static gate in libminecraftpe.so (tools/so_patch.py): no runtime code
 *    patching, we only store our address into the library's spare RW tail.
 *
 * 2. Animations.  Every mob Model's setupAnim (vtable slot 11) is wrapped:
 *    the game's own animation runs first, then the Bedrock animations that
 *    Java compiled from entities/<id>/<id>.animation.json are applied to the
 *    bones they name (rotation = bind pose + sum of animations).
 */
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;
typedef unsigned long size_t;

void *dlopen(const char *, int);
void *dlsym(void *, const char *);
int mprotect(void *, size_t, int);
void *malloc(size_t);
void *memset(void *, int, size_t);
int memcmp(const void *, const void *, size_t);
float sinf(float), cosf(float), sqrtf(float), floorf(float), ceilf(float), fmodf(float, float), powf(float, float);
int __android_log_print(int, const char *, const char *, ...);
int snprintf(char *, size_t, const char *, ...);
struct timespec_ { long tv_sec; long tv_nsec; };
int clock_gettime(int, struct timespec_ *);
int open(const char *, int, ...);
int read(int, void *, size_t);
int write(int, const void *, size_t);
int close(int);

#define LOG(...) __android_log_print(4, "NOVA.native", __VA_ARGS__)
#define LOGE(...) __android_log_print(6, "NOVA.native", __VA_ARGS__)

#define DEG2RAD 0.017453292f

typedef void (*load_fn)(void *part, void *gptr, void *name);
typedef void (*ctor_fn)(void *part, int, int, int, int);
typedef void (*addchild_fn)(void *part, void *child);
typedef void (*setup_fn)(void *model, float, float, float, float, float, float);

static load_fn orig_load;
static ctor_fn part_ctor;
static addchild_fn add_child;
static int status;        /* 1 = ok, <0 = error */
static int attached;      /* child bones attached */
static int hooked_vt;     /* model vtables wrapped */
static int anim_sets;     /* animation sets loaded */
static int anim_models;   /* model instances animated */
static int assets_routed; /* JSON reads go through Java */
static int rebuilt;       /* C++ models rebuilt from geometry */
static int faces_set;     /* cube faces given per-face UVs */
static int stage2_ok;     /* settings/HUD hooks installed */
static int menus_ok;      /* NOVA menus + chunk borders installed */

#define MODELPART_SIZE 0xA0
/* ModelPart: +0 pos[3], +0xc rot[3], +0x28 children vector<ModelPart*> */
/* Model: +0xa8 registered parts vector<ModelPart*> */

static inline const char *sdata(const void *str) { return *(const char *const *)str; }
static inline int slen(const char *d) { return ((const int *)d)[-3]; }

static char *dupn(const char *s, int n) {
    char *r = malloc(n + 1);
    if (!r) return 0;
    for (int i = 0; i < n; i++) r[i] = s[i];
    r[n] = 0;
    return r;
}

/* ---------------- part registry: part ptr -> geometry + bone key ------- */
typedef struct { void *part; const char *geo; const char *key; float bind[3]; float pos[3]; int pos_ok; } PartInfo;
#define PCAP 8192
static PartInfo parts[PCAP];

static PartInfo *pinfo(void *part, int create) {
    u32 h = ((u32)part >> 3) * 2654435761u;
    for (int i = 0; i < PCAP; i++) {
        PartInfo *e = &parts[(h + i) & (PCAP - 1)];
        if (e->part == part) return e;
        if (!e->part) {
            if (!create) return 0;
            e->part = part;
            return e;
        }
    }
    return 0;
}

static const char *geo_name(char *geo) {
    /* Geometry: +8 std::string name; keep one copy per distinct pointer */
    static char *last_geo; static const char *last_name;
    if (geo == last_geo) return last_name;
    const char *d = sdata(geo + 8);
    last_geo = geo;
    last_name = dupn(d, slen(d));
    return last_name;
}

/* ---------------- per-face UV (Blockbench "uv": {"north": {...}}) ------ */
/* Cube (664 bytes, inline in ModelPart+0x1c vector): 6 quads x 84 bytes
 * (int + 4 vertices {x,y,z,u,v}); quad order west,east,up,down,north,south */
typedef struct { const char *geo, *key; int cube; int mask; float f[6][4]; } FaceUv;
static FaceUv *fuv;
static int nfuv;
static void *geo_group;    /* GeometryGroup*, from the first GeometryPtr seen */

static int streq(const char *a, const char *b);

static void apply_face_uv(void *part, const char *geo, const char *key) {
    char *cb = *(char **)((char *)part + 0x1c), *ce = *(char **)((char *)part + 0x20);
    float tw = ((float *)part)[0x34 / 4], th = ((float *)part)[0x38 / 4];
    if (tw <= 0 || th <= 0) return;
    for (int i = 0; i < nfuv; i++) {
        FaceUv *F = &fuv[i];
        if (!streq(F->geo, geo) || !streq(F->key, key)) continue;
        char *cube = cb + F->cube * 664;
        if (cube + 664 > ce) continue;
        for (int q = 0; q < 6; q++) {
            float *v0 = (float *)(cube + q * 84 + 4);
            float ou0 = 1e9f, ou1 = -1e9f, ov0 = 1e9f, ov1 = -1e9f;
            for (int j = 0; j < 4; j++) {
                float u = v0[j * 5 + 3], v = v0[j * 5 + 4];
                if (u < ou0) ou0 = u; if (u > ou1) ou1 = u;
                if (v < ov0) ov0 = v; if (v > ov1) ov1 = v;
            }
            float nu0, nu1, nv0, nv1;
            if (F->mask & (1 << q)) {
                nu0 = F->f[q][0]; nu1 = F->f[q][0] + F->f[q][2];
                nv0 = F->f[q][1]; nv1 = F->f[q][1] + F->f[q][3];
                if (q == 2 || q == 3) { float t = nu0; nu0 = nu1; nu1 = t; t = nv0; nv0 = nv1; nv1 = t; }
            } else {
                nu0 = nu1 = 0; nv0 = nv1 = 0;     /* face not in the model: collapse it */
            }
            nu0 /= tw; nu1 /= tw; nv0 /= th; nv1 /= th;
            for (int j = 0; j < 4; j++) {
                float *u = &v0[j * 5 + 3], *v = &v0[j * 5 + 4];
                float tu = ou1 > ou0 ? (*u - ou0) / (ou1 - ou0) : 0;
                float tv = ov1 > ov0 ? (*v - ov0) / (ov1 - ov0) : 0;
                *u = nu0 + tu * (nu1 - nu0);
                *v = nv0 + tv * (nv1 - nv0);
            }
            faces_set++;
        }
    }
}

/* ---------------- 1. bone trees ----------------------------------------- */
static int is_direct_child(const char *k, int kl, const char *n, int nl) {
    if (nl <= kl + 1 || n[kl] != '>' || memcmp(n, k, kl) != 0) return 0;
    for (int i = kl + 1; i < nl; i++) if (n[i] == '>') return 0;
    return 1;
}

static char *rb_next(char *x) {      /* libstdc++ _Rb_tree_increment */
    char *r = *(char **)(x + 0xc);
    if (r) {
        while (*(char **)(r + 8)) r = *(char **)(r + 8);
        return r;
    }
    char *p = *(char **)(x + 4);
    while (x == *(char **)(p + 0xc)) { x = p; p = *(char **)(p + 4); }
    if (*(char **)(x + 0xc) != p) x = p;
    return x;
}

static void hook_load(void *part, void *gptr, void *name) {
    orig_load(part, gptr, name);
    char *geo = *(char **)((char *)gptr + 4);
    if (!geo) return;
    const char *k = sdata(name);
    int kl = slen(k);
    if (!geo_group) geo_group = *(void **)gptr;
    PartInfo *pi = pinfo(part, 1);
    if (nfuv) apply_face_uv(part, geo_name(geo), k);
    if (pi) {
        float *f = (float *)part;
        pi->geo = geo_name(geo);
        pi->key = dupn(k, kl);
        pi->bind[0] = f[3]; pi->bind[1] = f[4]; pi->bind[2] = f[5];
        pi->pos_ok = 0;
    }
    if (*(u32 *)(geo + 0x20) == 0) return;
    char *header = geo + 0x10;
    for (char *n = *(char **)(header + 8); n != header; n = rb_next(n)) {
        void *nkey = n + 0x10;
        const char *nd = sdata(nkey);
        if (!is_direct_child(k, kl, nd, slen(nd))) continue;
        void *child = malloc(MODELPART_SIZE);
        if (!child) return;
        memset(child, 0, MODELPART_SIZE);
        part_ctor(child, 0, 0, 64, 32);
        hook_load(child, gptr, nkey);
        float *cp = (float *)child, *pp = (float *)part;
        cp[0] -= pp[0]; cp[1] -= pp[1]; cp[2] -= pp[2];
        add_child(part, child);
        attached++;
    }
}

/* ---------------- 2. Molang VM ------------------------------------------ */
enum { OP_PUSH = 1, OP_VAR, OP_ADD, OP_SUB, OP_MUL, OP_DIV, OP_NEG, OP_LT, OP_GT, OP_LE, OP_GE,
       OP_EQ, OP_NE, OP_AND, OP_OR, OP_NOT, OP_SEL, OP_CALL };
enum { V_ANIM_TIME, V_LIFE_TIME, V_DIST, V_SPEED, V_YAW, V_PITCH, V_ON_GROUND, V_ARG3, V_COUNT };

static float rdf(const u8 *p) {
    union { u32 u; float f; } v;
    v.u = p[0] | (p[1] << 8) | (p[2] << 16) | ((u32)p[3] << 24);
    return v.f;
}
static u32 rd16(const u8 *p) { return p[0] | (p[1] << 8); }

static float fn_call(int fn, float *a, int n) {
    switch (fn) {
    case 0: return sinf(a[0] * DEG2RAD);
    case 1: return cosf(a[0] * DEG2RAD);
    case 2: return a[0] < 0 ? -a[0] : a[0];
    case 3: return a[0] < a[1] ? a[1] : (a[0] > a[2] ? a[2] : a[0]);
    case 4: return a[0] < a[1] ? a[0] : a[1];
    case 5: return a[0] > a[1] ? a[0] : a[1];
    case 6: return a[0] + (a[1] - a[0]) * a[2];
    case 7: return a[0] > 0 ? sqrtf(a[0]) : 0;
    case 8: return floorf(a[0]);
    case 9: return ceilf(a[0]);
    case 10: return floorf(a[0] + 0.5f);
    case 11: return a[1] != 0 ? fmodf(a[0], a[1]) : 0;
    case 12: return powf(a[0], a[1]);
    case 13: return a[0] < 0 ? ceilf(a[0]) : floorf(a[0]);
    case 14: { float t = a[2]; t = t * t * (3 - 2 * t); return a[0] + (a[1] - a[0]) * t; } /* lerprotate~ */
    case 15: return a[0] < 0 ? -1.f : (a[0] > 0 ? 1.f : 0.f);
    }
    return 0;
}

/* program: u16 len + bytes; empty program evaluates to dflt */
static float eval(const u8 *prog, const float *vars, float dflt) {
    u32 n = rd16(prog);
    if (!n) return dflt;
    const u8 *p = prog + 2, *end = p + n;
    float st[32];
    int sp = 0;
    while (p < end) {
        u8 op = *p++;
        float a, b;
        switch (op) {
        case OP_PUSH: if (sp < 32) st[sp++] = rdf(p); p += 4; break;
        case OP_VAR: { u8 v = *p++; if (sp < 32) st[sp++] = v < V_COUNT ? vars[v] : 0; break; }
        case OP_NEG: if (sp) st[sp - 1] = -st[sp - 1]; break;
        case OP_NOT: if (sp) st[sp - 1] = st[sp - 1] == 0 ? 1.f : 0.f; break;
        case OP_SEL:
            if (sp >= 3) { float c = st[sp - 3]; st[sp - 3] = c != 0 ? st[sp - 2] : st[sp - 1]; sp -= 2; }
            break;
        case OP_CALL: {
            u8 fn = *p++, argc = *p++;
            if (argc > sp || argc > 4) return dflt;
            float args[4] = {0, 0, 0, 0};
            for (int i = 0; i < argc; i++) args[i] = st[sp - argc + i];
            sp -= argc;
            st[sp++] = fn_call(fn, args, argc);
            break;
        }
        default:
            if (sp < 2) return dflt;
            b = st[--sp]; a = st[sp - 1];
            switch (op) {
            case OP_ADD: a = a + b; break;
            case OP_SUB: a = a - b; break;
            case OP_MUL: a = a * b; break;
            case OP_DIV: a = b != 0 ? a / b : 0; break;
            case OP_LT: a = a < b; break;
            case OP_GT: a = a > b; break;
            case OP_LE: a = a <= b; break;
            case OP_GE: a = a >= b; break;
            case OP_EQ: a = a == b; break;
            case OP_NE: a = a != b; break;
            case OP_AND: a = (a != 0 && b != 0); break;
            case OP_OR: a = (a != 0 || b != 0); break;
            default: return dflt;
            }
            st[sp - 1] = a;
        }
    }
    return sp ? st[sp - 1] : dflt;
}

static const u8 *skip_prog(const u8 *p) { return p + 2 + rd16(p); }

/* ---------------- animation data (blob from Java) ----------------------- */
typedef struct { u8 loop; float length; const u8 *time, *weight; } Anim;
typedef struct { u8 anim, target, nkeys; const u8 *data; } Chan;   /* data: 3 progs, or keys */
typedef struct { const char *name; int nch; Chan *ch; } Bone;
typedef struct { const char *geo; int nanim; Anim *anim; int nbone; Bone *bone; } Set;

static Set *sets;
static int nsets;

static const u8 *rdstr(const u8 *p, const char **out) {
    *out = dupn((const char *)p + 1, p[0]);
    return p + 1 + p[0];
}

static int parse_blob(const u8 *b, int len) {
    if (len < 6 || memcmp(b, "NVA1", 4)) return -1;
    const u8 *p = b + 4;
    nsets = rd16(p); p += 2;
    sets = malloc(sizeof(Set) * (nsets ? nsets : 1));
    for (int s = 0; s < nsets; s++) {
        Set *S = &sets[s];
        p = rdstr(p, &S->geo);
        S->nanim = rd16(p); p += 2;
        S->anim = malloc(sizeof(Anim) * (S->nanim ? S->nanim : 1));
        for (int a = 0; a < S->nanim; a++) {
            Anim *A = &S->anim[a];
            A->loop = *p++;
            A->length = rdf(p); p += 4;
            A->time = p; p = skip_prog(p);
            A->weight = p; p = skip_prog(p);
        }
        S->nbone = rd16(p); p += 2;
        S->bone = malloc(sizeof(Bone) * (S->nbone ? S->nbone : 1));
        for (int i = 0; i < S->nbone; i++) {
            Bone *B = &S->bone[i];
            p = rdstr(p, &B->name);
            B->nch = rd16(p); p += 2;
            B->ch = malloc(sizeof(Chan) * (B->nch ? B->nch : 1));
            for (int c = 0; c < B->nch; c++) {
                Chan *C = &B->ch[c];
                C->anim = *p++; C->target = *p++; C->nkeys = *p++;
                C->data = p;
                if (!C->nkeys) { for (int k = 0; k < 3; k++) p = skip_prog(p); }
                else for (int k = 0; k < C->nkeys; k++) { p += 4; for (int j = 0; j < 3; j++) p = skip_prog(p); }
            }
        }
        if (p > b + len) return -2;
    }
    return nsets;
}

/* JNI: static native int nativeLoadAnimations(byte[]) */
int Java_com_nova_client_NovaActivity_nativeLoadAnimations(void **env, void *cls, void *arr) {
    typedef int (*getlen_fn)(void **, void *);
    typedef void (*getreg_fn)(void **, void *, int, int, void *);
    void **fns = *(void ***)env;
    int len = ((getlen_fn)fns[171])(env, arr);
    u8 *buf = malloc(len + 1);
    if (!buf) return -9;
    ((getreg_fn)fns[200])(env, arr, 0, len, buf);
    int r = parse_blob(buf, len);
    anim_sets = r > 0 ? r : 0;
    LOG("animations: %d sets (%d bytes)", r, len);
    return r;
}

int Java_com_nova_client_NovaActivity_nativeStatus(void *env, void *cls) {
    if (status != 1) return status;
    return 1 + attached * 1 + 0 * hooked_vt;   /* >0 = ok */
}

static int menu_seen;                    /* a menu screen was drawn: the intro may hand over */
static volatile int intro_done;          /* IntroView finished: the menu title (title_image) may show */
void Java_com_nova_client_NovaActivity_nativeIntroDone(void *env, void *cls) { intro_done = 1; }
int Java_com_nova_client_NovaActivity_nativeInfo(void *env, void *cls, int what) {
    switch (what) {
    case 0: return status;
    case 1: return attached;
    case 2: return hooked_vt;
    case 3: return anim_sets;
    case 4: return anim_models;
    case 5: return assets_routed;
    case 6: return rebuilt;
    case 7: return faces_set;
    case 8: return stage2_ok;
    case 9: return menus_ok;
    case 10: return menu_seen;
    }
    return 0;
}

/* ---------------- model -> resolved animation set ---------------------- */
typedef struct { int nparts; void **parts; } BoneParts;   /* parts matching one Bone */
typedef struct { void *model; Set *set; BoneParts *bp; } ModelAnim;
#define MCAP 1024
static ModelAnim models[MCAP];

static const char *last_seg(const char *k) {
    const char *r = k;
    for (; *k; k++) if (*k == '>') r = k + 1;
    return r;
}

static int streq(const char *a, const char *b) {
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

static void collect(void *part, const char *bone, void **out, int *n, int max) {
    PartInfo *pi = pinfo(part, 0);
    if (pi && pi->key && streq(last_seg(pi->key), bone) && *n < max) out[(*n)++] = part;
    void **c = *(void ***)((char *)part + 0x28), **e = *(void ***)((char *)part + 0x2c);
    for (; c && c < e; c++) collect(*c, bone, out, n, max);
}

static ModelAnim *model_anim(void *model) {
    u32 h = ((u32)model >> 3) * 2654435761u;
    ModelAnim *slot = 0;
    for (int i = 0; i < MCAP; i++) {
        ModelAnim *e = &models[(h + i) & (MCAP - 1)];
        if (e->model == model) return e;
        if (!e->model) { slot = e; break; }
    }
    if (!slot) return 0;
    slot->model = model;
    void **b = *(void ***)((char *)model + 0xa8), **e = *(void ***)((char *)model + 0xac);
    const char *geo = 0;
    for (void **q = b; q && q < e && !geo; q++) { PartInfo *pi = pinfo(*q, 0); if (pi) geo = pi->geo; }
    if (!geo) return slot;
    for (int s = 0; s < nsets; s++) if (streq(sets[s].geo, geo)) { slot->set = &sets[s]; break; }
    if (!slot->set) return slot;
    Set *S = slot->set;
    slot->bp = malloc(sizeof(BoneParts) * (S->nbone ? S->nbone : 1));
    for (int i = 0; i < S->nbone; i++) {
        void *tmp[8]; int n = 0;
        for (void **q = b; q < e; q++) collect(*q, S->bone[i].name, tmp, &n, 8);
        slot->bp[i].nparts = n;
        slot->bp[i].parts = malloc(sizeof(void *) * (n ? n : 1));
        for (int j = 0; j < n; j++) slot->bp[i].parts[j] = tmp[j];
    }
    anim_models++;
    return slot;
}

static void chan_value(const Chan *C, float t, const float *vars, float out[3]) {
    const u8 *p = C->data;
    if (!C->nkeys) {
        for (int k = 0; k < 3; k++) { out[k] = eval(p, vars, 0); p = skip_prog(p); }
        return;
    }
    /* keyframes: linear between neighbours */
    const u8 *prev = 0, *next = 0;
    float tp = 0, tn = 0;
    for (int k = 0; k < C->nkeys; k++) {
        float kt = rdf(p);
        if (kt <= t) { prev = p; tp = kt; }
        else if (!next) { next = p; tn = kt; }
        p += 4;
        for (int j = 0; j < 3; j++) p = skip_prog(p);
    }
    if (!prev) { prev = next; tp = tn; }
    if (!next) { next = prev; tn = tp; }
    float a[3], b[3];
    const u8 *q = prev + 4, *r = next + 4;
    for (int k = 0; k < 3; k++) { a[k] = eval(q, vars, 0); q = skip_prog(q); b[k] = eval(r, vars, 0); r = skip_prog(r); }
    float f = tn > tp ? (t - tp) / (tn - tp) : 0;
    for (int k = 0; k < 3; k++) out[k] = a[k] + (b[k] - a[k]) * f;
}

static void animate(void *model, float limb, float amount, float age, float yaw, float pitch) {
    if (!nsets) return;
    ModelAnim *M = model_anim(model);
    if (!M || !M->set) return;
    Set *S = M->set;
    float vars[V_COUNT];
    vars[V_LIFE_TIME] = age / 20.f;
    vars[V_DIST] = limb;
    vars[V_SPEED] = amount;
    vars[V_YAW] = yaw;
    vars[V_PITCH] = pitch;
    vars[V_ON_GROUND] = 1.f;
    vars[V_ARG3] = age;
    float atime[32], aw[32];
    int na = S->nanim < 32 ? S->nanim : 32;
    for (int a = 0; a < na; a++) {
        vars[V_ANIM_TIME] = vars[V_LIFE_TIME];
        float t = eval(S->anim[a].time, vars, vars[V_LIFE_TIME]);
        if (S->anim[a].length > 0) {
            if (S->anim[a].loop) t = fmodf(t, S->anim[a].length);
            else if (t > S->anim[a].length) t = S->anim[a].length;
            if (t < 0) t += S->anim[a].length;
        }
        atime[a] = t;
        vars[V_ANIM_TIME] = t;
        aw[a] = eval(S->anim[a].weight, vars, 1.f);
    }
    for (int i = 0; i < S->nbone; i++) {
        Bone *B = &S->bone[i];
        BoneParts *bp = &M->bp[i];
        if (!bp->nparts) continue;
        float rot[3] = {0, 0, 0}, pos[3] = {0, 0, 0};
        int has_rot = 0, has_pos = 0;
        for (int c = 0; c < B->nch; c++) {
            Chan *C = &B->ch[c];
            if (C->anim >= na || aw[C->anim] == 0) continue;
            vars[V_ANIM_TIME] = atime[C->anim];
            float v[3];
            chan_value(C, atime[C->anim], vars, v);
            float *dst = C->target ? pos : rot;
            for (int k = 0; k < 3; k++) dst[k] += v[k] * aw[C->anim];
            if (C->target) has_pos = 1; else has_rot = 1;
        }
        for (int j = 0; j < bp->nparts; j++) {
            float *f = (float *)bp->parts[j];
            PartInfo *pi = pinfo(bp->parts[j], 0);
            if (!pi) continue;
            if (has_rot) {
                f[3] = pi->bind[0] + rot[0] * DEG2RAD;
                f[4] = pi->bind[1] + rot[1] * DEG2RAD;
                f[5] = pi->bind[2] + rot[2] * DEG2RAD;
            }
            if (has_pos) {
                if (!pi->pos_ok) { pi->pos[0] = f[0]; pi->pos[1] = f[1]; pi->pos[2] = f[2]; pi->pos_ok = 1; }
                f[0] = pi->pos[0] + pos[0];
                f[1] = pi->pos[1] - pos[1];
                f[2] = pi->pos[2] + pos[2];
            }
        }
    }
}

/* ---------------- blobs from Java ---------------------------------------- */
static u8 *jbytes(void **env, void *arr, int *len) {
    typedef int (*getlen_fn)(void **, void *);
    typedef void (*getreg_fn)(void **, void *, int, int, void *);
    void **fns = *(void ***)env;
    *len = ((getlen_fn)fns[171])(env, arr);
    u8 *buf = malloc(*len + 1);
    if (buf) ((getreg_fn)fns[200])(env, arr, 0, *len, buf);
    return buf;
}

int Java_com_nova_client_NovaActivity_nativeLoadFaceUv(void **env, void *cls, void *arr) {
    int len; u8 *b = jbytes(env, arr, &len);
    if (!b || len < 6 || memcmp(b, "NVF1", 4)) return -1;
    const u8 *p = b + 4;
    int n = rd16(p); p += 2;
    fuv = malloc(sizeof(FaceUv) * (n ? n : 1));
    for (int i = 0; i < n; i++) {
        FaceUv *F = &fuv[i];
        p = rdstr(p, &F->geo);
        p = rdstr(p, &F->key);
        F->cube = *p++;
        F->mask = *p++;
        for (int q = 0; q < 6; q++) {
            if (!(F->mask & (1 << q))) continue;
            for (int k = 0; k < 4; k++) { F->f[q][k] = rdf(p); p += 4; }
        }
    }
    nfuv = n;
    LOG("per-face uv: %d cubes", n);
    return n;
}

typedef struct { u32 vt; const char *geo; int cond_off, cond_val; int n; const char **names; u16 *offs; } Rebuild;
static Rebuild *rbs;
static int nrbs;

int Java_com_nova_client_NovaActivity_nativeLoadRebuild(void **env, void *cls, void *arr) {
    int len; u8 *b = jbytes(env, arr, &len);
    if (!b || len < 6 || memcmp(b, "NVR1", 4)) return -1;
    void *mc = dlopen("libminecraftpe.so", 0x4 | 0x2);
    const u8 *p = b + 4;
    int n = rd16(p); p += 2;
    rbs = malloc(sizeof(Rebuild) * (n ? n : 1));
    for (int i = 0; i < n; i++) {
        Rebuild *R = &rbs[i];
        const char *vtname;
        p = rdstr(p, &vtname);
        R->vt = mc ? (u32)dlsym(mc, vtname) : 0;
        if (R->vt) R->vt += 8;
        p = rdstr(p, &R->geo);
        R->cond_off = (int)(p[0] | (p[1] << 8) | (p[2] << 16) | ((u32)p[3] << 24)); p += 4;
        R->cond_val = (int)(p[0] | (p[1] << 8) | (p[2] << 16) | ((u32)p[3] << 24)); p += 4;
        R->n = rd16(p); p += 2;
        R->names = malloc(sizeof(char *) * (R->n ? R->n : 1));
        R->offs = malloc(sizeof(u16) * (R->n ? R->n : 1));
        for (int k = 0; k < R->n; k++) { p = rdstr(p, &R->names[k]); R->offs[k] = rd16(p); p += 2; }
    }
    nrbs = n;
    LOG("rebuild specs: %d", n);
    return n;
}

/* Geometry* by name from GeometryGroup (map<string, unique_ptr<Geometry>> at +0x18) */
static void *find_geometry(const char *name) {
    if (!geo_group) return 0;
    char *g = geo_group;
    if (*(u32 *)(g + 0x2c) == 0 && *(char **)(g + 0x20) == 0) return 0;
    char *header = g + 0x1c;
    for (char *n = *(char **)(header + 8); n && n != header; n = rb_next(n)) {
        const char *k = sdata(n + 0x10);
        if (streq(k, name)) return *(void **)(n + 0x14);
    }
    return 0;
}

/* fake COW std::string for ModelPart::load */
static void *make_string(const char *s) {
    int n = 0; while (s[n]) n++;
    int *rep = malloc(12 + n + 1);
    rep[0] = n; rep[1] = n; rep[2] = 0x40000000;          /* never freed / shared */
    char *d = (char *)(rep + 3);
    for (int i = 0; i <= n; i++) d[i] = s[i];
    char **str = malloc(sizeof(char *));
    *str = d;
    return str;
}

#define DONE_CAP 512
static void *done_models[DONE_CAP];

static void ensure_rebuilt(void *model) {
    if (!nrbs) return;
    u32 h = ((u32)model >> 3) * 2654435761u;
    int slot = -1;
    for (int i = 0; i < DONE_CAP; i++) {
        int j = (h + i) & (DONE_CAP - 1);
        if (done_models[j] == model) return;
        if (!done_models[j]) { slot = j; break; }
    }
    if (slot < 0) return;
    done_models[slot] = model;
    u32 vt = *(u32 *)model;
    for (int i = 0; i < nrbs; i++) {
        Rebuild *R = &rbs[i];
        if (R->vt != vt) continue;
        if (R->cond_off >= 0 && *(int *)((char *)model + R->cond_off) != R->cond_val) continue;
        void *geo = find_geometry(R->geo);
        if (!geo) { LOGE("rebuild: %s not in Mobs.json", R->geo); return; }
        void *gptr[2] = { geo_group, geo };
        for (int k = 0; k < R->n; k++)
            hook_load((char *)model + R->offs[k], gptr, make_string(R->names[k]));
        rebuilt++;
        return;
    }
}

/* ---------------- setupAnim wrappers ------------------------------------ */
typedef struct { u32 vt; setup_fn orig; } VtOrig;
static VtOrig vtorig[64];
static int nvt;

static void hook_setup(void *model, float a, float b, float c, float d, float e, float f) {
    u32 vt = *(u32 *)model;
    for (int i = 0; i < nvt; i++) {
        if (vtorig[i].vt == vt) {
            ensure_rebuilt(model);
            vtorig[i].orig(model, a, b, c, d, e, f);
            animate(model, a, b, c, d, e);
            return;
        }
    }
}

typedef void (*render_fn)(void *model, void *entity, float, float, float, float, float, float);
typedef struct { u32 vt; render_fn orig; } VtRender;
static VtRender vtrender[64];
static int nvtr;

static void hook_render(void *model, void *ent, float a, float b, float c, float d, float e, float f) {
    u32 vt = *(u32 *)model;
    for (int i = 0; i < nvtr; i++) {
        if (vtrender[i].vt == vt) {
            ensure_rebuilt(model);
            vtrender[i].orig(model, ent, a, b, c, d, e, f);
            return;
        }
    }
}

static const char *const VTABLES[] = {
    "_ZTV14QuadrupedModel", "_ZTV8CowModel", "_ZTV8PigModel", "_ZTV10SheepModel",
    "_ZTV13HumanoidModel", "_ZTV11ZombieModel", "_ZTV13SkeletonModel", "_ZTV19VillagerZombieModel",
    "_ZTV13EnderManModel", "_ZTV12CreeperModel", "_ZTV11SpiderModel", "_ZTV10SquidModel",
    "_ZTV10GhastModel", "_ZTV8BatModel", "_ZTV14IronGolemModel", "_ZTV14SnowGolemModel",
    "_ZTV13VillagerModel", "_ZTV10WitchModel", "_ZTV9WolfModel", "_ZTV11OcelotModel",
    "_ZTV12ChickenModel", "_ZTV11RabbitModel", "_ZTV10BlazeModel", "_ZTV15SilverfishModel",
    "_ZTV10SlimeModel", "_ZTV14LavaSlimeModel", "_ZTV9BoatModel", "_ZTV13MinecartModel", "_ZTV11PlayerModel", 0
};
#define SETUP_SLOT_OFF (8 + 11 * 4)

static void hook_vtables(void *mc) {
    for (int i = 0; VTABLES[i] && nvt < 64; i++) {
        u32 vt = (u32)dlsym(mc, VTABLES[i]);
        if (!vt) continue;
        u32 *slot = (u32 *)(vt + SETUP_SLOT_OFF);
        u32 page = (u32)(slot - 1) & ~4095u;                    /* covers render slot too */
        if (mprotect((void *)page, 8192, 3) != 0) { LOGE("mprotect vtable %s failed", VTABLES[i]); continue; }
        vtorig[nvt].vt = vt + 8;
        vtorig[nvt].orig = (setup_fn)*slot;
        nvt++;
        *slot = (u32)hook_setup;
        u32 *rslot = (u32 *)(vt + 8 + 10 * 4);                    /* render(Entity&, ...) */
        if (nvtr < 64) {
            vtrender[nvtr].vt = vt + 8;
            vtrender[nvtr].orig = (render_fn)*rslot;
            nvtr++;
            *rslot = (u32)hook_render;
        }
        mprotect((void *)page, 8192, 1);
        hooked_vt++;
    }
}

/* ---------------- asset reads through Java ----------------------------- */
/* On API 23+ the game uses AppPlatform_android23::readAssetFile, which reads
 * the APK with AAssetManager and bypasses Java.  Point that vtable slot back
 * at AppPlatform_android::readAssetFile (Java getFileDataBytes), so the NOVA
 * entity tree (generated Mobs.json) and .mcres packs see every JSON read. */

static void route_assets(void *mc) {
    u32 vt = (u32)dlsym(mc, "_ZTV21AppPlatform_android23");
    u32 f23 = (u32)dlsym(mc, "_ZN21AppPlatform_android2313readAssetFileERKSs");
    u32 fj = (u32)dlsym(mc, "_ZN19AppPlatform_android13readAssetFileERKSs");
    if (!vt || !f23 || !fj) { LOGE("readAssetFile symbols missing"); return; }
    u32 *slot = (u32 *)(vt + 8 + 46 * 4);
    if (*slot != f23) { LOGE("readAssetFile slot mismatch"); return; }
    u32 page = (u32)slot & ~4095u;
    if (mprotect((void *)page, 4096, 3) != 0) { LOGE("mprotect AppPlatform vtable failed"); return; }
    *slot = fj;
    mprotect((void *)page, 4096, 1);
    assets_routed = 1;
}


/* ======================= NOVA settings, FPS, F3, doll ===================== */
/* Settings live in the game's own (unused on phones) VR options, so the game
 * draws, saves and loads them:  doll = vr_hud_at_top, doll size = vr_sensitivity,
 * F3 = vr_hud_drift, FPS = vr_gaze_pitch_boost (captions come from the .lang). */
typedef void *(*fn_v_v)(void *);
typedef int (*createcat_fn)(void *scr, int id, int size, int ix, int iy, int a, int b);
typedef void *(*cgroup_fn)(void *pane, void *str, int flag);
typedef void (*citem_fn)(void *group, void *opt, void *client);
typedef int (*getbool_fn)(void *options, void *opt);
typedef float (*getprog_fn)(void *options, void *opt);
typedef void (*strctor_fn)(void *str, const char *s, void *alloc);
typedef void (*strdtor_fn)(void *str);
typedef void (*fontdraw_fn)(void *font, void *str, float x, float y, void *color, int b);
typedef void *(*biome_fn)(void *region, int *pos);
typedef void (*bright_fn)(void *out, void *region, int *pos, int b);   /* returns Brightness via sret */
typedef void (*screen_fn)(void *scr);
typedef void (*crender_fn)(void *self, void *client, void **ctrl, int pass);

static u32 mcbase;
static createcat_fn f_createcat;
static fn_v_v f_pane_ctor, f_getoptions, f_getlevel;
static cgroup_fn f_cgroup;
static citem_fn f_ctoggle, f_cslider, f_cstep;
static void (*f_release)(void *);
static void (*f_selectcat)(void *, int, int);
static getbool_fn f_getbool;
static getprog_fn f_getprog;
static strctor_fn f_strctor;
static strdtor_fn f_strdtor;
static fontdraw_fn f_fontdraw;
static biome_fn f_biome;
static bright_fn f_bright;
static void *(*f_new)(size_t);
static void (*f_delete)(void *);
static void *opt_doll, *opt_dollsize, *opt_f3, *opt_fps;
static screen_fn o_createcats, o_genscreens, o_setuppos;
static crender_fn o_dbg_render, o_doll_render;
#define OFF_SP_PANE_VT 0x6f07c8
#define NOVA_ICON_X 106
#define NOVA_ICON_Y 84

static void *last_screen;        /* options screen that got the NOVA category */
static int nova_cats(void *scr);
static int nova_panes(void *scr);
static void h_setbool(void *opts, void *opt, int v);
static void *opt_chunks;
static void *newtex_option(void);
static void *opt_sprint;
static int last_pane_doll = -1;  /* doll toggle state the NOVA pane was built with */

/* doll size is not saved by the game (that VR option has no key): own file */
static char cfg_path[256];
static int doll_size_saved = 4, doll_size_applied;

int Java_com_nova_client_NovaActivity_nativeSetDataDir(void **env, void *cls, void *jstr) {
    typedef const char *(*getutf_fn)(void **, void *, void *);
    typedef void (*relutf_fn)(void **, void *, const char *);
    void **fns = *(void ***)env;
    const char *d = ((getutf_fn)fns[169])(env, jstr, 0);
    if (!d) return -1;
    int n = 0;
    while (d[n] && n < 200) { cfg_path[n] = d[n]; n++; }
    const char *f = "/nova_doll_size";
    for (int i = 0; f[i]; i++) cfg_path[n++] = f[i];
    cfg_path[n] = 0;
    ((relutf_fn)fns[170])(env, jstr, d);
    int fd = open(cfg_path, 0);
    if (fd >= 0) {
        char b[8] = {0};
        int r = read(fd, b, 7);
        close(fd);
        if (r > 0 && b[0] >= '1' && b[0] <= '7') doll_size_saved = b[0] - '0';
    }
    return doll_size_saved;
}

#define OPT_DOLLSIZE_OFF 0xb0      /* Options::getVRRightStickPitchAssistSteppings */
static int doll_size(void *opts) {
    int *v = (int *)((char *)opts + OPT_DOLLSIZE_OFF);
    if (!doll_size_applied) { *v = doll_size_saved; doll_size_applied = 1; }
    if (*v < 1 || *v > 7) *v = 4;
    if (*v != doll_size_saved && cfg_path[0]) {
        doll_size_saved = *v;
        int fd = open(cfg_path, 01 | 0100 | 01000, 0644);   /* O_WRONLY|O_CREAT|O_TRUNC */
        if (fd >= 0) { char c = '0' + *v; write(fd, &c, 1); close(fd); }
    }
    return *v;
}

static void gstr(void *s, const char *c) { char a[4]; f_strctor(s, c, a); }

static void h_createcats(void *scr) {
    if (nova_cats(scr)) return;               /* NOVA menu screen: only its own tabs */
    o_createcats(scr);
    char *S = scr;
    int n = (*(u32 *)(S + 0xcc) - *(u32 *)(S + 0xc8)) / 8;
    f_createcat(scr, 2 + n, *(int *)(S + 0xa4), NOVA_ICON_X, NOVA_ICON_Y, 0, 0);
    last_screen = scr;
}

static void push_pane(char *S, void *pane) {
    u32 *cb = f_new(0x14);
    cb[0] = mcbase + OFF_SP_PANE_VT + 8; cb[1] = 1; cb[2] = 1; cb[3] = (u32)pane; cb[4] = 0;
    u32 *b = *(u32 **)(S + 0x8c), *e = *(u32 **)(S + 0x90), *c = *(u32 **)(S + 0x94);
    if (e == c) {
        int n = (e - b) / 2, cap = n ? n * 2 : 4;
        u32 *nb = f_new(cap * 8);
        for (int i = 0; i < n * 2; i++) nb[i] = b[i];
        if (b) f_delete(b);
        b = nb; e = nb + n * 2; c = nb + cap * 2;
        *(u32 **)(S + 0x8c) = b; *(u32 **)(S + 0x94) = c;
    }
    e[0] = (u32)pane; e[1] = (u32)cb;
    *(u32 **)(S + 0x90) = e + 2;
}

static void *build_nova_pane(void *client) {
    void *opts = f_getoptions(client);
    int doll = opts ? f_getbool(opts, opt_doll) : 0;
    if (opts) doll_size(opts);
    void *pane = f_new(0x70);
    f_pane_ctor(pane);
    u32 str[1];
    gstr(str, "options.group.nova");
    void *group = f_cgroup(pane, str, 1);
    f_strdtor(str);
    f_ctoggle(group, opt_doll, client);
    if (doll) f_cstep(group, opt_dollsize, client);   /* size only while the doll is on */
    f_ctoggle(group, opt_f3, client);
    int f3 = opts ? f_getbool(opts, opt_f3) : 0;
    if (f3 && opt_chunks) f_ctoggle(group, opt_chunks, client);   /* chunk borders only with F3 */
    f_ctoggle(group, opt_fps, client);
    if (opt_sprint) f_ctoggle(group, opt_sprint, client);
    if (menus_ok) {                                      /* textures: applied by a restart */
        gstr(str, "options.group.nova.textures");
        void *g2 = f_cgroup(pane, str, 1);
        f_strdtor(str);
        f_ctoggle(g2, newtex_option(), client);
    }
    last_pane_doll = doll | (f3 << 1);
    return pane;
}

static void h_genscreens(void *scr) {
    if (nova_panes(scr)) return;
    o_genscreens(scr);
    if (scr != last_screen) return;
    char *S = scr;
    int ncat = (*(u32 *)(S + 0xcc) - *(u32 *)(S + 0xc8)) / 8;
    int npane = (*(u32 *)(S + 0x90) - *(u32 *)(S + 0x8c)) / 8;
    if (npane != ncat - 1) { LOGE("options: %d panes for %d categories", npane, ncat); last_screen = 0; return; }
    push_pane(S, build_nova_pane(*(void **)(S + 0x14)));
}

/* doll toggled while the NOVA tab is open: rebuild the pane (shows/hides size) */
static screen_fn o_opt_tick;
static void h_opt_tick(void *scr) {
    o_opt_tick(scr);
    if (scr != last_screen) return;
    char *S = scr;
    void *client = *(void **)(S + 0x14);
    void *opts = f_getoptions(client);
    if (!opts) return;
    int f3 = f_getbool(opts, opt_f3);
    if (!f3 && menus_ok && opt_chunks && f_getbool(opts, opt_chunks)) h_setbool(opts, opt_chunks, 0);  /* off with F3 */
    int doll = f_getbool(opts, opt_doll) | (f3 << 1);
    doll_size(opts);
    if (doll == last_pane_doll) return;
    int n = (*(u32 *)(S + 0x90) - *(u32 *)(S + 0x8c)) / 8;
    if (n < 1) return;
    u32 *slot = *(u32 **)(S + 0x8c) + (n - 1) * 2;
    void *oldcb = (void *)slot[1];
    void *pane = build_nova_pane(client);
    u32 *cb = f_new(0x14);
    cb[0] = mcbase + OFF_SP_PANE_VT + 8; cb[1] = 1; cb[2] = 1; cb[3] = (u32)pane; cb[4] = 0;
    slot[0] = (u32)pane; slot[1] = (u32)cb;
    if (*(int *)(S + 0xa0) == n - 1) {
        f_selectcat(scr, n - 1, 0);
        void (*setup)(void *) = (void (*)(void *))(*(u32 **)scr)[53];
        setup(scr);
    }
    if (oldcb) f_release(oldcb);
}

static void h_setuppos(void *scr) {
    o_setuppos(scr);
    char *S = scr;
    u32 *b = *(u32 **)(S + 0xc8), *e = *(u32 **)(S + 0xcc);
    int n = (e - b) / 2;
    if (n < 1) return;
    int H = *(int *)(S + 0x10);
    char *hdr = *(char **)(S + 0xa8);
    int top = (hdr ? *(int *)(hdr + 0x14) : 0) + 3;
    int total = n * 29 - 1;
    int y = (H - total) / 2;
    if (y < top) y = top;
    for (int i = 0; i < n; i++, y += 29) *(int *)((char *)b[i * 2] + 0xc) = y;
}

/* ---- HUD: FPS / F3 / doll ---- */
static int fps, frames;
static long fps_t0;
static int text_lines;           /* lines drawn this frame (doll goes below) */

static void draw_line(void *font, float x, float y, const char *txt) {
    static float white[4] = {1, 1, 1, 1};
    u32 str[1];
    gstr(str, txt);
    f_fontdraw(font, str, x, y, white, 1);
    f_strdtor(str);
}

static const char *FACING[4] = {"south (Towards positive Z)", "west (Towards negative X)",
                                "north (Towards negative Z)", "east (Towards positive X)"};

static int ifloor(float v) { int i = (int)v; return (v < i) ? i - 1 : i; }

static void draw_toast(void *client);
/* start screen: keep the sibling "title_image" hidden until the intro has handed over (no double title) */
static void title_gate(char *cc) {
    char *parent = *(char **)(cc + 0x38);                    /* weak_ptr<UIControl> mParent */
    if (!parent) return;
    char **it = *(char ***)(parent + 0x40), **end = *(char ***)(parent + 0x44);   /* vector<shared_ptr<UIControl>> */
    for (; it && it < end; it += 2) {
        char *ch = it[0];
        if (!ch || !streq(sdata(ch + 0xc), "title_image")) continue;
        int vis = (ch[0x30] >> 2) & 1, want = intro_done ? 1 : 0;
        if (vis != want) ch[0x30] = (char)((ch[0x30] & ~4) | (want << 2));
    }
}
static void h_dbg_render(void *self, void *client, void **ctrl, int pass) {
    o_dbg_render(self, client, ctrl, pass);
    if (pass != 0) return;
    char *cc = ctrl ? *(char **)ctrl : 0;
    if (cc && streq(sdata(cc + 0xc), "nova_title_gate")) { menu_seen = 1; title_gate(cc); return; }
    if (menus_ok) draw_toast(client);
    if (cc && streq(sdata(cc + 0xc), "nova_toast")) { menu_seen = 1; return; }   /* menu screens: toast only */
    struct timespec_ ts;
    clock_gettime(1, &ts);
    long now = ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    frames++;
    if (now - fps_t0 >= 1000) { fps = frames * 1000 / (int)(now - fps_t0); frames = 0; fps_t0 = now; }
    void *opts = f_getoptions(client);
    if (!opts) return;
    int show_f3 = f_getbool(opts, opt_f3), show_fps = f_getbool(opts, opt_fps);
    void *font = *(void **)((char *)client + 0x7c);
    char buf[160];
    int line = 0;
    text_lines = 0;
    if (!show_f3) {
        if (show_fps) { snprintf(buf, sizeof buf, "FPS: %d", fps); draw_line(font, 2, 2, buf); text_lines = 1; }
        return;
    }
    char *pl = *(char **)((char *)client + 0x120);
    #define L(...) do { snprintf(buf, sizeof buf, __VA_ARGS__); draw_line(font, 2, 2 + 10 * line++, buf); } while (0)
    L("Minecraft PE 0.14.3 (NOVA client)");
    L("%d fps", fps);
    if (pl) {
        float *pos = (float *)(pl + 8);
        float x = pos[0], y = pos[1] - 1.62f, z = pos[2];
        int bx = ifloor(x), by = ifloor(y), bz = ifloor(z);
        float pitch = *(float *)(pl + 0x38), yaw = *(float *)(pl + 0x3c);
        float w = yaw - 360.f * (float)ifloor(yaw / 360.f);
        if (w >= 180.f) w -= 360.f;
        int dir = ifloor(w * 4.f / 360.f + 0.5f) & 3;
        line++;
        L("XYZ: %.3f / %.5f / %.3f", x, y, z);
        L("Block: %d %d %d", bx, by, bz);
        L("Chunk: %d %d %d in %d %d", bx & 15, by & 15, bz & 15, bx >> 4, bz >> 4);
        L("Facing: %s (%.1f / %.1f)", FACING[dir], w, pitch);
        void *region = *(void **)(pl + 0x16c);
        if (region) {
            int bp[3] = {bx, by, bz};
            char *biome = f_biome ? f_biome(region, bp) : 0;
            if (biome) { const char *bn = sdata(biome + 0x38); L("Biome: %s", bn); }
            if (f_bright) { u32 br = 0; f_bright(&br, region, bp, 1); L("Light: %u", br & 0xff); }
            char *lvl = f_getlevel ? f_getlevel(region) : 0;
            if (lvl) {
                int t = *(int *)(lvl + 0x48 + 0x34);   /* Level::getTime -> LevelData+0x34 */
                L("Day %d, time %d (%02d:%02d)", t / 24000, t % 24000, ((t % 24000) / 1000 + 6) % 24, (t % 1000) * 60 / 1000);
            }
        }
    }
    #undef L
    text_lines = line;
}

static void h_doll_render(void *self, void *client, void **ctrl, int pass) {
    char *c = ctrl ? *(char **)ctrl : 0;
    if (c) {
        const char *nm = sdata(c + 0xc);
        if (streq(nm, "nova_doll")) {
            void *opts = f_getoptions(client);
            if (!opts || !f_getbool(opts, opt_doll)) return;
            /* the renderer puts the player's eye point at the control position and
               scales one block to size.x, so place the eyes, not the corner */
            float S = 12.f + 4.f * (float)doll_size(opts);      /* 16 .. 40, middle 28 */
            float *pos = (float *)(c + 0x10), *size = (float *)(c + 0x18);
            float top = 4.f + (text_lines ? text_lines * 10.f + 2.f : 0.f);
            size[0] = S;
            size[1] = S * 2.1f;
            pos[0] = 4.f + S * 0.5f;
            pos[1] = top + S * 0.32f;               /* head top is ~0.3 block above the eyes */
        }
    }
    o_doll_render(self, client, ctrl, pass);
}

static int patch_slot(u32 vt, int idx, void *fn, void **orig) {
    u32 *slot = (u32 *)(vt + 8 + idx * 4);
    u32 page = (u32)slot & ~4095u;
    if (mprotect((void *)page, 4096, 3) != 0) return -1;
    *orig = (void *)*slot;
    *slot = (u32)fn;
    mprotect((void *)page, 4096, 1);
    return 0;
}


static void stage2_init(void *mc, u32 base) {
    mcbase = base;
    void *gs = dlopen("libgnustl_shared.so", 0x4 | 0x2);
    if (!gs) gs = dlopen("libgnustl_shared.so", 0x2);
    f_strctor = gs ? dlsym(gs, "_ZNSsC1EPKcRKSaIcE") : 0;
    f_strdtor = gs ? dlsym(gs, "_ZNSsD1Ev") : 0;
    f_new = gs ? dlsym(gs, "_Znwj") : 0;
    f_delete = gs ? dlsym(gs, "_ZdlPv") : 0;
    if (!f_new) { f_new = dlsym(mc, "_Znwj"); f_delete = dlsym(mc, "_ZdlPv"); }
    if (!f_new) { f_new = malloc; }
    f_createcat = dlsym(mc, "_ZN13OptionsScreen20createCategoryButtonEiiiiii");
    f_pane_ctor = dlsym(mc, "_ZN11OptionsPaneC1Ev");
    f_cgroup = dlsym(mc, "_ZN11OptionsPane18createOptionsGroupERKSsb");
    f_ctoggle = dlsym(mc, "_ZN12OptionsGroup12createToggleERKN7Options6OptionER15MinecraftClient");
    f_cslider = dlsym(mc, "_ZN12OptionsGroup20createProgressSliderERKN7Options6OptionER15MinecraftClient");
    f_getbool = dlsym(mc, "_ZN7Options15getBooleanValueEPKNS_6OptionE");
    f_getprog = dlsym(mc, "_ZN7Options16getProgressValueEPKNS_6OptionE");
    f_getoptions = dlsym(mc, "_ZN15MinecraftClient10getOptionsEv");
    f_fontdraw = dlsym(mc, "_ZN4Font4drawERKSsffRK5Colorb");
    f_biome = dlsym(mc, "_ZN11BlockSource8getBiomeERK8BlockPos");
    f_bright = dlsym(mc, "_ZN11BlockSource16getRawBrightnessERK8BlockPosb");
    f_getlevel = dlsym(mc, "_ZN11BlockSource8getLevelEv");
    opt_doll = dlsym(mc, "_ZN7Options6Option13VR_HUD_AT_TOPE");
    opt_dollsize = dlsym(mc, "_ZN7Options6Option35VR_RIGHTSTICK_PITCHASSIST_STEPPINGSE");
    f_cstep = dlsym(mc, "_ZN12OptionsGroup16createStepSliderERKN7Options6OptionER15MinecraftClient");
    f_release = dlsym(mc, "_ZNSt16_Sp_counted_baseILN9__gnu_cxx12_Lock_policyE2EE10_M_releaseEv");
    f_selectcat = dlsym(mc, "_ZN13OptionsScreen14selectCategoryEib");
    /* doll size steps 1..7 (middle = 4): replace the option's value list */
    u32 *steps = dlsym(mc, "_ZN7Options44VR_RIGHTSTICK_PITCHASSIST_STEPPINGS_DIVISORSE");
    if (steps && f_new) {
        int *v = f_new(7 * 4);
        for (int i = 0; i < 7; i++) v[i] = i + 1;
        steps[0] = (u32)v; steps[1] = (u32)(v + 7); steps[2] = (u32)(v + 7);
    }
    opt_f3 = dlsym(mc, "_ZN7Options6Option13VR_HUD_DRIFTSE");
    opt_fps = dlsym(mc, "_ZN7Options6Option19VR_GAZE_PITCH_BOOSTE");
    if (!f_strctor || !f_strdtor || !f_createcat || !f_pane_ctor || !f_cgroup || !f_ctoggle || !f_cslider ||
        !f_getbool || !f_getprog || !f_cstep || !f_release || !f_selectcat || !f_getoptions || !f_fontdraw || !opt_doll || !opt_dollsize || !opt_f3 || !opt_fps || !f_delete) {
        LOGE("stage2 symbols missing");
        return;
    }
    u32 vt_opt = (u32)dlsym(mc, "_ZTV13OptionsScreen");
    u32 vt_dbg = (u32)dlsym(mc, "_ZTV23HudDebugOverlayRenderer");
    u32 vt_doll = (u32)dlsym(mc, "_ZTV18LivePlayerRenderer");
    if (vt_opt) {
        patch_slot(vt_opt, 74, h_createcats, (void **)&o_createcats);
        patch_slot(vt_opt, 73, h_genscreens, (void **)&o_genscreens);
        patch_slot(vt_opt, 53, h_setuppos, (void **)&o_setuppos);
        patch_slot(vt_opt, 56, h_opt_tick, (void **)&o_opt_tick);
    }
    if (vt_dbg) patch_slot(vt_dbg, 5, h_dbg_render, (void **)&o_dbg_render);
    if (vt_doll) patch_slot(vt_doll, 5, h_doll_render, (void **)&o_doll_render);
    stage2_ok = 1;
}

/* ---------------- init --------------------------------------------------- */
#include "gates.h"

typedef void (*prender_fn)(void *part, void *model, float scale);
static prender_fn orig_part_render;

static void ensure_rebuilt(void *model);
static void hook_part_render(void *part, void *model, float scale) {
    ensure_rebuilt(model);
    orig_part_render(part, model, scale);
}

/* is the gate at func in place (b.w), and is the cave's original entry there? */
static int gate_ok(u32 base, u32 func, u32 orig) {
    const u16 *g = (const u16 *)(base + func), *o = (const u16 *)(base + orig);
    return (g[0] & 0xf800) == 0xf000 && (g[1] & 0xd000) == 0x9000 && o[0] != 0;
}
#define GATE(name, hook, origvar) do { \
    if (gate_ok(base, G_##name##_FUNC, G_##name##_ORIG)) { \
        *(void **)&(origvar) = (void *)((base + G_##name##_ORIG) | 1); \
        *(volatile u32 *)(base + G_##name##_SLOT) = (u32)(hook); \
    } else LOGE("gate " #name " missing"); } while (0)

#include "menus.inc"
#include "mcmod.inc"
#include "scale.inc"
#include "events.inc"
#include "items.inc"

__attribute__((constructor)) static void nova_init(void) {
    void *mc = dlopen("libminecraftpe.so", 0x4 | 0x2 /* NOLOAD|NOW */);
    if (!mc) mc = dlopen("libminecraftpe.so", 0x2);
    if (!mc) { status = -1; LOGE("libminecraftpe.so not loaded"); return; }
    u32 load = (u32)dlsym(mc, G_MODELPART_LOAD_SYM);
    part_ctor = (ctor_fn)dlsym(mc, "_ZN9ModelPartC1Eiiii");
    add_child = (addchild_fn)dlsym(mc, "_ZN9ModelPart8addChildEPS_");
    if (!load || !part_ctor || !add_child) { status = -2; LOGE("symbols missing"); return; }
    u32 base = (load & ~1u) - G_MODELPART_LOAD_FUNC;
    if (!gate_ok(base, G_MODELPART_LOAD_FUNC, G_MODELPART_LOAD_ORIG)) {
        status = -3; LOGE("hook gate missing: unpatched libminecraftpe.so?"); return;
    }
    GATE(MODELPART_LOAD, hook_load, orig_load);
    GATE(MODELPART_RENDER, hook_part_render, orig_part_render);
    hook_vtables(mc);
    route_assets(mc);
    stage2_init(mc, base);
    menus_init(mc, base);
    mcmod_init(mc, base);
    scale_init(mc, base);
    events_init(mc, base);
    items_init(mc, base);
    status = 1;
    LOG("NOVA native ready: bone trees, %d model vtables wrapped", hooked_vt);
}

/* integer division helpers (no libgcc here) */
__attribute__((visibility("hidden"))) unsigned __aeabi_uidiv(unsigned n, unsigned d) {
    if (!d) return 0;
    unsigned q = 0, r = 0;
    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1);
        if (r >= d) { r -= d; q |= 1u << i; }
    }
    return q;
}
__attribute__((visibility("hidden"))) int __aeabi_idiv(int n, int d) {
    int neg = (n < 0) ^ (d < 0);
    unsigned q = __aeabi_uidiv(n < 0 ? -(unsigned)n : (unsigned)n, d < 0 ? -(unsigned)d : (unsigned)d);
    return neg ? -(int)q : (int)q;
}
