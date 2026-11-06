/* hook.inc - mods can hook and call ANY function of libminecraftpe.so at runtime (no fixed API).
 *
 * Inline mode ('i' in assets/nova/symbols.tsv): the function's first 8 / 10 bytes become
 *   [nop] ldr.w pc, [pc, #0] ; .word thunk
 * and its original prologue is relocated into a trampoline (hook_reloc.h, checked against the emulator on 6000
 * functions of the game). Site mode ('c': too small or not relocatable): every vtable slot and bl call site of the
 * function is pointed at the thunk instead (bl only when the thunk is within +-16 MB).
 *
 * thunk -> nh_entry (asm: saves r0-r3 so they sit right under the caller's stack arguments = one array of argument
 * words) -> nh_dispatch(idx, words) -> Java ModBridge.onHook(idx, int[12]) -> long (r0:r1). Java calls the original
 * through nhCallOrig. Everything is softfp: floats travel in core registers / stack words like ints.
 */
typedef unsigned long long u64;
void *mmap(void *, size_t, int, int, int, long);
int cacheflush(long, long, long);

#define NH_MAX 1024
typedef struct {
    u32 target;          /* function address (thumb, without bit 0) */
    u32 orig;            /* what to call for the original (|1) */
    u32 thunk;           /* our entry for this hook (|1) */
    int mode;            /* 1 inline, 2 sites */
    u8 saved[12]; int nsaved;
    u32 *sites; u32 *site_old; int nsites;
    int on;
} NHook;
static NHook nh_hooks[NH_MAX];
static int nh_count;
/* hooks handled in C (libnova's own features): fn(original, argument words) */
typedef u64 (*nh_cfn)(u32 orig, u32 *w);
static nh_cfn nh_c[NH_MAX];
static void *nh_onhook;                  /* jmethodID ModBridge.onHook(I[I)J */
static u8 *nh_pool; static u32 nh_pool_used, nh_pool_size;
static int nh_pool_rwx = -1;             /* 1: pool pages are RWX; 0: every hook gets its own page, sealed RX */

static u32 nh_align(u32 v, u32 a) { return (v + a - 1) & ~(a - 1); }

/* 256 bytes of code memory per hook (thunk at +0, relocated prologue at +16), near the library if possible
 * (bl call sites reach +-16 MB). Written, then nh_seal(). */
static u8 *nh_block(void) {
    void *hint = (void *)nh_align(mcbase + 0x800000, 0x10000);
    if (nh_pool_rwx != 0) {
        if (!nh_pool || nh_pool_used + 256 > nh_pool_size) {
            void *p = mmap(hint, 65536, 7, 0x22, -1, 0);           /* RWX, MAP_PRIVATE|MAP_ANONYMOUS */
            if (p != (void *)-1 && p) { nh_pool = p; nh_pool_used = 0; nh_pool_size = 65536; nh_pool_rwx = 1; }
            else if (nh_pool_rwx == -1) nh_pool_rwx = 0;
            else return 0;
        }
        if (nh_pool_rwx == 1) { u8 *r = nh_pool + nh_pool_used; nh_pool_used += 256; return r; }
    }
    void *p = mmap(hint, 4096, 3, 0x22, -1, 0);
    return (p == (void *)-1) ? 0 : p;
}
static void nh_seal(u8 *blk) {
    if (nh_pool_rwx != 1) mprotect((void *)((u32)blk & ~4095u), 4096, 5);
    cacheflush((long)blk, (long)(blk + 256), 0);
}

/* write into the game's code / data */
static int nh_poke_code(u32 addr, const u8 *bytes, int n) {
    u32 p0 = addr & ~4095u, p1 = (addr + n + 4095) & ~4095u;
    if (mprotect((void *)p0, p1 - p0, 7) != 0) return -1;
    for (int i = 0; i < n; i++) ((u8 *)addr)[i] = bytes[i];
    mprotect((void *)p0, p1 - p0, 5);
    cacheflush((long)addr, (long)(addr + n), 0);
    return 0;
}
static int nh_poke_word(u32 addr, u32 v, int code) {
    u32 p0 = addr & ~4095u;
    if (mprotect((void *)p0, 4096, code ? 7 : 3) != 0) return -1;
    *(u32 *)addr = v;
    mprotect((void *)p0, 4096, code ? 5 : 1);
    if (code) cacheflush((long)addr, (long)(addr + 4), 0);
    return 0;
}

typedef u64 (*nh_fn12)(u32, u32, u32, u32, u32, u32, u32, u32, u32, u32, u32, u32);
static u64 nh_callw(u32 fn, const u32 *w) {
    return ((nh_fn12)fn)(w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8], w[9], w[10], w[11]);
}

static volatile int nh_depth;     /* recursion guard (approximate across threads) */

__attribute__((used)) static u64 nh_dispatch(int idx, u32 *w) {
    NHook *h = &nh_hooks[idx];
    if (h->on && nh_c[idx]) return nh_c[idx](h->orig, w);
    if (!h->on || !mm_ok || !nh_onhook || nh_depth > 24) return nh_callw(h->orig, w);
    void **env = mm_env();
    if (!env) return nh_callw(h->orig, w);
    void **f = *(void ***)env;
    nh_depth++;
    u64 r;
    if (((int (*)(void *, int))f[19])(env, 4) != 0) { nh_depth--; return nh_callw(h->orig, w); }
    void *arr = ((void *(*)(void *, int))f[179])(env, 12);                       /* NewIntArray */
    if (!arr) { ((void *(*)(void *, void *))f[20])(env, 0); nh_depth--; return nh_callw(h->orig, w); }
    ((void (*)(void *, void *, int, int, const u32 *))f[211])(env, arr, 0, 12, w); /* SetIntArrayRegion */
    MJValue args[2];
    args[0].j = 0; args[0].i = idx;
    args[1].j = 0; args[1].l = arr;
    r = ((u64 (*)(void *, void *, void *, MJValue *))f[134])(env, mm_cls, nh_onhook, args);   /* CallStaticLongMethodA */
    if (((int (*)(void *))f[228])(env)) {                                         /* ExceptionCheck */
        ((void (*)(void *))f[16])(env);
        ((void (*)(void *))f[17])(env);
        r = nh_callw(h->orig, w);
    }
    ((void *(*)(void *, void *))f[20])(env, 0);
    nh_depth--;
    return r;
}

/* r0-r3 pushed right below the caller's stack arguments: one contiguous array of argument words */
__attribute__((naked, used)) static void nh_entry(void) {
    __asm__ volatile(
        "push {r0-r3}\n"
        "push {r4, lr}\n"
        "mov r0, ip\n"
        "add r1, sp, #8\n"
        "bl nh_dispatch\n"
        "pop {r4, lr}\n"
        "add sp, sp, #16\n"
        "bx lr\n");
}

static void nh_put_thunk(u8 *t, int idx) {
    u32 *w = (u32 *)t;
    w[0] = 0xC004F8DF;               /* ldr.w ip, [pc, #4] */
    w[1] = 0xF004F8DF;               /* ldr.w pc, [pc, #4] */
    w[2] = (u32)idx;
    w[3] = (u32)nh_entry | 1;
}

static int nh_install(u32 addr, int mode, u32 *sites, int nsites);

/* a hook handled in C: inline only. Returns the index or < 0. */
static int nh_hook_c(u32 addr, nh_cfn fn) {
    int idx = nh_install(addr, 1, 0, 0);
    if (idx >= 0) nh_c[idx] = fn;
    return idx;
}

/* JNI: hook the function at runtime address addr. mode 1 inline, 2 sites (int[] of runtime addresses, negative =
 * vtable slot, positive = bl site). Returns hook index or < 0. */
int Java_com_nova_client_ModBridge_nhHook(void **env, void *cls, int addr, int mode, void *sitesArr) {
    void **f = *(void ***)env;
    int n = 0;
    u32 *sites = 0;
    if (mode != 1) {
        n = sitesArr ? ((int (*)(void *, void *))f[171])(env, sitesArr) : 0;
        if (n <= 0) return -105;
        sites = malloc(n * 4);
        ((void (*)(void *, void *, int, int, u32 *))f[203])(env, sitesArr, 0, n, sites);      /* GetIntArrayRegion */
    }
    return nh_install((u32)addr, mode, sites, n);
}

static int nh_install(u32 addr0, int mode, u32 *sitesIn, int nsites) {
    if (nh_count >= NH_MAX) return -100;
    u32 a = addr0 & ~1u;
    for (int i = 0; i < nh_count; i++) if (nh_hooks[i].target == a && nh_hooks[i].on) return -101;   /* already hooked */
    int idx = nh_count;
    NHook *h = &nh_hooks[idx];
    memset(h, 0, sizeof *h);
    h->target = a; h->mode = mode;
    u8 *blk = nh_block();
    if (!blk) return -102;
    nh_put_thunk(blk, idx);
    h->thunk = (u32)blk | 1;
    if (mode == 1) {
        int need = (a & 2) ? 10 : 8;
        u8 *tr = blk + 16;
        int cons = 0;
        int n = nh_relocate((const u8 *)a, a, tr, (u32)tr, need, &cons);
        nh_seal(blk);
        if (n < 0 || n > 240) return -200 + n;
        h->orig = (u32)tr | 1;
        u8 patch[12]; int k = 0;
        if (a & 2) { patch[k++] = 0x00; patch[k++] = 0xBF; }       /* nop */
        patch[k++] = 0xDF; patch[k++] = 0xF8; patch[k++] = 0x00; patch[k++] = 0xF0;   /* ldr.w pc, [pc, #0] */
        u32 th = h->thunk;
        patch[k++] = th; patch[k++] = th >> 8; patch[k++] = th >> 16; patch[k++] = th >> 24;
        for (int i = 0; i < k; i++) h->saved[i] = ((u8 *)a)[i];
        h->nsaved = k;
        if (nh_poke_code(a, patch, k)) return -104;
    } else {
        int n = nsites;
        if (n <= 0 || !sitesIn) return -105;
        h->sites = sitesIn; h->site_old = malloc(n * 4);
        h->orig = a | 1;
        nh_seal(blk);
        for (int i = 0; i < n; i++) {
            int s = (int)h->sites[i];
            if (s < 0) {                                           /* vtable slot */
                u32 slot = (u32)(-s);
                h->site_old[i] = *(u32 *)slot;
                if (nh_poke_word(slot, h->thunk, 0) == 0) h->nsites = i + 1; else h->site_old[i] = 0;
            } else {                                               /* bl site -> bl thunk */
                u32 site = (u32)s, t = h->thunk & ~1u;
                int off = (int)(t - (site + 4));
                h->site_old[i] = *(u32 *)site;
                if (off < -16777216 || off >= 16777216) { h->site_old[i] = 0; h->nsites = i + 1; continue; }
                u32 S = (off >> 24) & 1, I1 = (off >> 23) & 1, I2 = (off >> 22) & 1;
                u32 J1 = (!I1) ^ S, J2 = (!I2) ^ S;
                u32 hw1 = 0xF000 | (S << 10) | ((off >> 12) & 0x3ff);
                u32 hw2 = 0xD000 | (J1 << 13) | (J2 << 11) | ((off >> 1) & 0x7ff);
                if (nh_poke_word(site, hw1 | (hw2 << 16), 1) != 0) h->site_old[i] = 0;
                h->nsites = i + 1;
            }
        }
    }
    h->on = 1;
    nh_count++;
    return idx;
}

void Java_com_nova_client_ModBridge_nhUnhook(void **env, void *cls, int idx) {
    if (idx < 0 || idx >= nh_count) return;
    NHook *h = &nh_hooks[idx];
    if (!h->on) return;
    if (h->mode == 1) nh_poke_code(h->target, h->saved, h->nsaved);
    else for (int i = 0; i < h->nsites; i++) {
        if (!h->site_old[i]) continue;
        int s = (int)h->sites[i];
        if (s < 0) nh_poke_word((u32)(-s), h->site_old[i], 0); else nh_poke_word((u32)s, h->site_old[i], 1);
    }
    h->on = 0;   /* the slot stays reserved: a thread may still be inside the thunk */
}

static void nh_words(void **env, void *arr, u32 *w) {
    void **f = *(void ***)env;
    for (int i = 0; i < 12; i++) w[i] = 0;
    if (!arr) return;
    int n = ((int (*)(void *, void *))f[171])(env, arr);
    if (n > 12) n = 12;
    ((void (*)(void *, void *, int, int, u32 *))f[203])(env, arr, 0, n, w);
}

long long Java_com_nova_client_ModBridge_nhCall(void **env, void *cls, int fn, void *arr) {
    u32 w[12]; nh_words(env, arr, w);
    return (long long)nh_callw((u32)fn | 1, w);
}
long long Java_com_nova_client_ModBridge_nhCallOrig(void **env, void *cls, int idx, void *arr) {
    u32 w[12]; nh_words(env, arr, w);
    if (idx < 0 || idx >= nh_count) return 0;
    return (long long)nh_callw(nh_hooks[idx].orig, w);
}

int Java_com_nova_client_ModBridge_nhBase(void **env, void *cls) { return (int)mcbase; }

/* memory: kind 1 u8, -1 s8, 2 u16, -2 s16, 4 u32 */
int Java_com_nova_client_ModBridge_nhPeek(void **env, void *cls, int addr, int kind) {
    if ((u32)addr < 0x10000) return 0;
    switch (kind) {
        case 1: return *(u8 *)addr;
        case -1: return *(signed char *)addr;
        case 2: return *(u16 *)addr;
        case -2: return *(short *)addr;
        default: return *(int *)addr;
    }
}
void Java_com_nova_client_ModBridge_nhPoke(void **env, void *cls, int addr, int kind, int v) {
    if ((u32)addr < 0x10000) return;
    switch (kind) {
        case 1: case -1: *(u8 *)addr = v; break;
        case 2: case -2: *(u16 *)addr = v; break;
        default: *(int *)addr = v; break;
    }
}
int Java_com_nova_client_ModBridge_nhAlloc(void **env, void *cls, int n) {
    void *p = f_new ? f_new(n > 0 ? n : 4) : malloc(n);
    if (p) memset(p, 0, n > 0 ? n : 4);
    return (int)p;
}
void Java_com_nova_client_ModBridge_nhFree(void **env, void *cls, int p) { if (p && f_delete) f_delete((void *)p); }

/* std::string (gnustl, COW): new 4-byte object holding the text; nhStrGet reads one */
int Java_com_nova_client_ModBridge_nhStrNew(void **env, void *cls, void *jstr) {
    void **f = *(void ***)env;
    if (!f_strctor || !f_new) return 0;
    const char *c = jstr ? ((const char *(*)(void *, void *, void *))f[169])(env, jstr, 0) : "";
    void *s = f_new(4);
    char al[4];
    f_strctor(s, c ? c : "", al);
    if (jstr && c) ((void (*)(void *, void *, const char *))f[170])(env, jstr, c);
    return (int)s;
}
void *Java_com_nova_client_ModBridge_nhStrGet(void **env, void *cls, int p) {
    void **f = *(void ***)env;
    if ((u32)p < 0x10000) return 0;
    const char *d = sdata((void *)p);
    if ((u32)d < 0x10000) return 0;
    int n = slen(d);
    if (n < 0 || n > 65536) return 0;
    char *buf = malloc(n + 1);
    if (!buf) return 0;
    for (int i = 0; i < n; i++) buf[i] = d[i];
    buf[n] = 0;
    mm_clean_utf(buf);
    void *r = ((void *(*)(void *, const char *))f[167])(env, buf);
    return r;
}
void *Java_com_nova_client_ModBridge_nhCStr(void **env, void *cls, int p) {
    void **f = *(void ***)env;
    if ((u32)p < 0x10000) return 0;
    char buf[1024]; int i = 0;
    for (; i < 1023 && ((const char *)p)[i]; i++) buf[i] = ((const char *)p)[i];
    buf[i] = 0;
    mm_clean_utf(buf);
    return ((void *(*)(void *, const char *))f[167])(env, buf);
}

/* the objects mods usually start from: 0 MinecraftClient, 1 LocalPlayer, 2 its BlockSource, 3 Level, 4 GuiData */
int Java_com_nova_client_ModBridge_nhGlobal(void **env, void *cls, int which) {
    char *pl = mm_player();
    switch (which) {
        case 0: return (int)g_client;
        case 1: return (int)pl;
        case 2: return (int)mm_region(pl);
        case 3: { void *bs = mm_region(pl); return bs && mf_getlevel ? (int)mf_getlevel(bs) : 0; }
        case 4: return g_client ? *(int *)((char *)g_client + 0x130) : 0;
        default: return 0;
    }
}

static void hook_init(void **env) {
    void **f = *(void ***)env;
    nh_onhook = ((void *(*)(void *, void *, const char *, const char *))f[113])(env, mm_cls, "onHook", "(I[I)J");
    if (!nh_onhook && ((int (*)(void *))f[228])(env)) { ((void (*)(void *))f[17])(env); }
    LOG("hooks: %s", nh_onhook ? "ready" : "onHook missing");
}
