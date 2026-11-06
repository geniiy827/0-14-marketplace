/* structs.inc - structures of mods in world generation (format: tools/port_rs.py, structures.bin of a .mcmod).
 *
 * Where: StructureFeature::postProcess(BlockSource*, Random&, chunkX, chunkZ) - RandomLevelSource::postProcess
 * calls it for every chunk (villages, mineshafts...) with a BlockSource over the chunk and its neighbours, on a
 * world generator thread. The first call for a chunk (per thread) places at most one of our structures there:
 * a deterministic choice from the world seed + chunk, the biome in the middle of the chunk, the ground (surface:
 * flat enough, no water) or a cave-free spot underground (dungeons). Centred on the chunk, at most 48 blocks wide,
 * so it stays inside the area the BlockSource can write. Chests get loot with the game's own
 * WeighedTreasureItem::addChestItems (as MonsterRoomFeature does), spawners a mob (BaseMobSpawner::setEntityId).
 * Only the world generator (the host in a friends' game) runs it; friends get the chunks as they are.
 */

void *realloc(void *, size_t);
void *calloc(size_t, size_t);
void free(void *);
int sched_yield(void);

typedef struct { u8 flags, id, data[4], nalt; u16 alt_at; } SPal;      /* alt_at: index into st_alts */
typedef struct { u16 prob; u8 id, data[4]; } SAlt;
typedef struct { u8 x, y, z, extra; u16 pal; } SBlk;
typedef struct {
    char name[40];
    u8 kind, ymin, ymax, slope, nbio, bio[32];
    int chance, yoff, sx, sy, sz;
    SPal *pal; int npal;
    SBlk *blk; int nblk;
    int rand_chests, rand_group, ncand; u8 *cand;
    int ngroups; u16 *group_at; u8 *group_n; u16 *groups;   /* group i: groups[group_at[i] .. +group_n[i]] */
    int loot_base, mob_base;
} SDef;
typedef struct { u8 rmin, rmax; int n; u16 *e; void *vec; } SLoot;   /* e: id, data, min|max<<8, weight */

#define ST_MAX 256
static SDef st_defs[ST_MAX];
static int st_count;
static SAlt *st_alts; static int st_nalts;
static SLoot *st_loot; static int st_nloot;
static char st_mobs[32][24]; static int st_nmobs;
static volatile int st_loot_ready;                  /* 0 not built, 1 building, 2 ready */

static u16 st_rd16(const u8 *p) { return p[0] | p[1] << 8; }
static u32 st_rd32(const u8 *p) { return p[0] | p[1] << 8 | p[2] << 16 | (u32)p[3] << 24; }

/* one structures.bin (several mods may each bring one) */
static int st_parse(const u8 *b, int len) {
    if (len < 10 || memcmp(b, "NSB1", 4)) return -1;
    const u8 *p = b + 4, *end = b + len;
    int nloot = st_rd16(p), nmobs = st_rd16(p + 2), nst = st_rd16(p + 4);
    p += 6;
    int mob_base = st_nmobs, loot_base = st_nloot;
    for (int i = 0; i < nmobs; i++) {
        int l = *p++;
        if (st_nmobs < 32) { int k = l < 23 ? l : 23; memcpy(st_mobs[st_nmobs], p, k); st_mobs[st_nmobs][k] = 0; st_nmobs++; }
        p += l;
    }
    st_loot = realloc(st_loot, (st_nloot + nloot) * sizeof(SLoot));
    for (int i = 0; i < nloot; i++) {
        SLoot *L = &st_loot[st_nloot++];
        L->rmin = p[0]; L->rmax = p[1]; L->n = st_rd16(p + 2); L->vec = 0;
        p += 4;
        L->e = malloc(L->n * 4 * sizeof(u16) + 8);
        for (int k = 0; k < L->n; k++) {
            L->e[k * 4] = st_rd16(p); L->e[k * 4 + 1] = st_rd16(p + 2); L->e[k * 4 + 2] = p[4] | p[5] << 8; L->e[k * 4 + 3] = st_rd16(p + 6);
            p += 8;
        }
        if (p > end) return -2;
    }
    for (int s = 0; s < nst && st_count < ST_MAX; s++) {
        SDef *d = &st_defs[st_count];
        memset(d, 0, sizeof *d);
        int l = *p++;
        memcpy(d->name, p, l < 39 ? l : 39);
        p += l;
        d->kind = p[0]; d->chance = st_rd16(p + 1); d->yoff = (signed char)p[3]; d->ymin = p[4]; d->ymax = p[5]; d->slope = p[6];
        p += 7;
        d->nbio = *p++;
        for (int i = 0; i < d->nbio; i++) { if (i < 32) d->bio[i] = p[i]; }
        p += d->nbio;
        if (d->nbio > 32) d->nbio = 32;
        d->sx = st_rd16(p); d->sy = st_rd16(p + 2); d->sz = st_rd16(p + 4); d->npal = st_rd16(p + 6);
        p += 8;
        d->pal = malloc(d->npal * sizeof(SPal) + 4);
        for (int i = 0; i < d->npal; i++) {
            SPal *e = &d->pal[i];
            e->flags = p[0]; e->id = p[1]; memcpy(e->data, p + 2, 4); e->nalt = p[6];
            p += 7;
            e->alt_at = st_nalts;
            st_alts = realloc(st_alts, (st_nalts + e->nalt + 1) * sizeof(SAlt));
            for (int k = 0; k < e->nalt; k++) {
                SAlt *a = &st_alts[st_nalts++];
                a->prob = st_rd16(p); a->id = p[2]; memcpy(a->data, p + 3, 4);
                p += 7;
            }
        }
        d->nblk = st_rd32(p);
        p += 4;
        d->blk = malloc(d->nblk * sizeof(SBlk) + 4);
        for (int i = 0; i < d->nblk; i++) {
            SBlk *k = &d->blk[i];
            k->x = p[0]; k->y = p[1]; k->z = p[2]; k->pal = st_rd16(p + 3); k->extra = p[5];
            p += 6;
        }
        d->rand_chests = p[0]; d->rand_group = st_rd16(p + 1); d->ncand = st_rd16(p + 3);
        p += 5;
        d->cand = malloc(d->ncand * 3 + 3);
        memcpy(d->cand, p, d->ncand * 3);
        p += d->ncand * 3;
        d->ngroups = *p++;
        d->group_at = malloc(d->ngroups * 2 + 2); d->group_n = malloc(d->ngroups + 1);
        int tot = 0;
        const u8 *q = p;
        for (int g = 0; g < d->ngroups; g++) { int n = *q; tot += n; q += 1 + n * 2; }
        d->groups = malloc(tot * 2 + 2);
        tot = 0;
        for (int g = 0; g < d->ngroups; g++) {
            int n = *p++;
            d->group_at[g] = tot; d->group_n[g] = n;
            for (int k = 0; k < n; k++) { d->groups[tot++] = st_rd16(p) + loot_base; p += 2; }
        }
        d->loot_base = loot_base; d->mob_base = mob_base;
        if (p > end) return -3;
        if (d->sx > 48 || d->sz > 48 || d->sy > 120 || d->chance < 1) continue;   /* would not fit: skip */
        st_count++;
    }
    return st_count;
}

int Java_com_nova_client_ModBridge_nativeStructures(void **env, void *cls, void *arr) {
    void **fns = *(void ***)env;
    int len = ((int (*)(void **, void *))fns[171])(env, arr);
    u8 *buf = malloc(len + 1);
    if (!buf) return -9;
    ((void (*)(void **, void *, int, int, void *))fns[200])(env, arr, 0, len, buf);
    int r = st_parse(buf, len);
    free(buf);
    LOG("structures: %d (loot tables %d, mobs %d)", r, st_nloot, st_nmobs);
    return r;
}

/* ---------------- game functions ---------------- */
static void (*sf_getid)(u8 *out, void *bs, int x, int y, int z);
static int (*sf_set)(void *bs, int x, int y, int z, const u8 *fullblock, int flags);
static void *(*sf_be)(void *bs, int x, int y, int z);
static void *(*sf_biome)(void *bs, const int *pos);
static void *(*sf_bslevel)(void *bs);
static int (*sf_seed)(void *level);
static void (*sf_item_ctor)(void *ii, int id, int count, int aux);
static void **sf_items;                             /* Item::mItems[512] */
static void (*sf_addchest)(void *rnd, void *vec, void *container, int count);
static void *(*sf_getspawner)(void *be);
static void (*sf_setentity)(void *spawner, int type);
static int (*sf_entitytype)(void *str);
static u64 (*o_sfpp)(u32, u32 *);

static int st_get(void *bs, int x, int y, int z) {
    if (y < 0 || y > 127) return 0;
    u8 id = 0;
    sf_getid(&id, bs, x, y, z);
    return id;
}
static void st_set(void *bs, int x, int y, int z, int id, int data) {
    if (y < 1 || y > 127) return;
    u8 fb[2] = {(u8)id, (u8)data};
    sf_set(bs, x, y, z, fb, 2);
}
/* not ground: air, plants, leaves, logs (trees), snow layer, mushrooms, liquids */
static int st_soft(int id) {
    switch (id) {
    case 0: case 6: case 8: case 9: case 10: case 11: case 17: case 18: case 31: case 32: case 37: case 38: case 39:
    case 40: case 78: case 81: case 83: case 99: case 100: case 106: case 111: case 161: case 162: case 175:
        return 1;
    }
    return 0;
}
static int st_liquid(int id) { return id >= 8 && id <= 11; }

/* x/z after r turns clockwise (as tools/port_rs.py unrot) */
static void st_rot(int *x, int *z, int sx, int sz, int r) {
    int a = *x, b = *z;
    for (int i = 0; i < r; i++) { int t = sz - 1 - b; b = a; a = t; int s = sx; sx = sz; sz = s; }
    *x = a; *z = b;
}

/* h mod n without a division call (no __aeabi_uidivmod needed) */
static u32 st_mod(u32 h, u32 n) { return n ? (u32)(((u64)h * n) >> 32) : 0; }

static u32 st_hash(u32 a, u32 b, u32 c) {
    u32 h = a * 0x9E3779B1u ^ (b + 0x7F4A7C15u) * 0x85EBCA77u ^ (c + 0x165667B1u) * 0xC2B2AE3Du;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return h;
}

/* the game's loot vectors: vector<WeighedTreasureItem> (32 bytes: weight, ItemInstance, min, max) */
static void st_build_loot(void) {
    for (int i = 0; i < st_nloot; i++) {
        SLoot *L = &st_loot[i];
        u8 *items = calloc(L->n + 1, 32);
        int m = 0;
        for (int k = 0; k < L->n; k++) {
            int id = L->e[k * 4];
            if (id <= 0 || id >= 512 || !sf_items || !sf_items[id]) continue;   /* an item this game does not have */
            u8 *w = items + m++ * 32;
            *(int *)w = L->e[k * 4 + 3];
            sf_item_ctor(w + 4, L->e[k * 4], 1, L->e[k * 4 + 1]);
            *(int *)(w + 0x18) = L->e[k * 4 + 2] & 0xff;
            *(int *)(w + 0x1c) = L->e[k * 4 + 2] >> 8;
        }
        u32 *v = malloc(12);
        v[0] = (u32)items; v[1] = (u32)(items + m * 32); v[2] = v[1];
        L->n = m;
        L->vec = v;
    }
}

static void st_fill_chest(void *bs, int x, int y, int z, SDef *d, int group, void *rnd, u32 *h) {
    if (group < 0 || group >= d->ngroups || !sf_be || !sf_addchest) return;
    void *be = sf_be(bs, x, y, z);
    if (!be) return;
    void *container = (char *)be + 0x5c;                   /* ChestBlockEntity -> its FillingContainer */
    for (int k = 0; k < d->group_n[group]; k++) {
        SLoot *L = &st_loot[d->groups[d->group_at[group] + k]];
        if (!L->vec || !L->n) continue;
        *h = st_hash(*h, k, 77);
        int rolls = L->rmin + (L->rmax > L->rmin ? (int)st_mod(*h, L->rmax - L->rmin + 1) : 0);
        if (rolls > 0) sf_addchest(rnd, L->vec, container, rolls);
    }
}

static int st_ground(void *bs, int x, int z, int water_ok) {   /* water_ok: the bottom under water (swamps) */
    for (int y = 127; y > 2; y--) {
        int id = st_get(bs, x, y, z);
        if (water_ok && (id == 8 || id == 9)) continue;
        if (st_liquid(id)) return -1;
        if (!st_soft(id)) return y + 1;
    }
    return -1;
}

/* try to put structure d into chunk (cx, cz); 1 = placed */
static int st_place(void *bs, void *rnd, SDef *d, int cx, int cz, u32 h) {
    int r = (h >> 8) & 3;
    int sx = (r & 1) ? d->sz : d->sx, sz = (r & 1) ? d->sx : d->sz;
    int x0 = cx * 16 + 8 - sx / 2, z0 = cz * 16 + 8 - sz / 2, y0;
    if (d->kind == 0 || d->kind == 2) {          /* 0 surface, 2 surface or under shallow water */
        int xs[5] = {x0, x0 + sx - 1, x0, x0 + sx - 1, x0 + sx / 2}, zs[5] = {z0, z0, z0 + sz - 1, z0 + sz - 1, z0 + sz / 2};
        int lo = 999, hi = -1;
        for (int i = 0; i < 5; i++) {
            int g = st_ground(bs, xs[i], zs[i], d->kind == 2);
            if (g < 0) return 0;
            if (g < lo) lo = g;
            if (g > hi) hi = g;
        }
        if (hi - lo > d->slope) return 0;
        y0 = lo + d->yoff;
    } else {
        int tries = 8, ok = 0;
        y0 = 0;
        while (tries-- && !ok) {
            h = st_hash(h, tries, 99);
            y0 = d->ymin + (d->ymax > d->ymin ? (int)st_mod(h, d->ymax - d->ymin + 1) : 0);
            ok = 1;
            int open = 0;
            /* solid floor and ceiling everywhere, a few openings in the walls (like MonsterRoomFeature) */
            for (int x = 0; x < sx && ok; x++)
                for (int z = 0; z < sz && ok; z++) {
                    int f = st_get(bs, x0 + x, y0 - 1, z0 + z), c = st_get(bs, x0 + x, y0 + d->sy, z0 + z);
                    if (st_soft(f) || st_soft(c)) ok = 0;
                    if ((x == 0 || z == 0 || x == sx - 1 || z == sz - 1) && st_get(bs, x0 + x, y0 + 1, z0 + z) == 0 &&
                        st_get(bs, x0 + x, y0 + 2, z0 + z) == 0) open++;
                }
            if (ok && (open < 1 || open > 13)) ok = 0;
        }
        if (!ok) return 0;
    }
    if (y0 < 2 || y0 + d->sy > 127) return 0;
    /* blocks */
    for (int i = 0; i < d->nblk; i++) {
        SBlk *k = &d->blk[i];
        SPal *e = &d->pal[k->pal];
        int id = e->id, data = e->data[r];
        if (e->nalt) {
            h = st_hash(h, i, 3);
            u32 roll = h & 0xffff;
            for (int a = 0; a < e->nalt; a++) {
                SAlt *al = &st_alts[e->alt_at + a];
                if (roll < al->prob) { id = al->id; data = al->data[r]; break; }
            }
        }
        if ((e->flags & 8) && id == 0) continue;                    /* not in 0.14: keep the world there */
        int x = k->x, z = k->z;
        st_rot(&x, &z, d->sx, d->sz, r);
        int wx = x0 + x, wy = y0 + k->y, wz = z0 + z;
        st_set(bs, wx, wy, wz, id, data);
        if ((e->flags & 1) || (d->kind != 1 && k->y == 0 && id && !st_liquid(id))) {   /* pillars / foundation */
            int lim = (e->flags & 1) ? 32 : 6;
            for (int y = wy - 1; y > 1 && lim-- > 0; y--) {
                int w = st_get(bs, wx, y, wz);
                if (!st_soft(w) && !st_liquid(w)) break;
                st_set(bs, wx, y, wz, id, data);
            }
        }
        if ((e->flags & 2) && k->extra && (id == 54 || id == 146)) st_fill_chest(bs, wx, wy, wz, d, k->extra - 1, rnd, &h);
        if ((e->flags & 4) && k->extra && id == 52 && sf_getspawner && sf_setentity && sf_entitytype) {
            void *be = sf_be(bs, wx, wy, wz);
            int mi = d->mob_base + k->extra - 1;
            if (be && mi < st_nmobs) {
                u32 s[1];
                gstr(s, st_mobs[mi]);
                int type = sf_entitytype(s);
                f_strdtor(s);
                void *sp = sf_getspawner(be);
                if (sp && type) sf_setentity(sp, type);
            }
        }
    }
    /* dungeons: chests at random free places along the walls */
    for (int c = 0; c < d->rand_chests && d->ncand > 0; c++) {
        h = st_hash(h, c, 41);
        u8 *q = d->cand + st_mod(h, d->ncand) * 3;
        int x = q[0], z = q[2];
        st_rot(&x, &z, d->sx, d->sz, r);
        int wx = x0 + x, wy = y0 + q[1], wz = z0 + z;
        if (st_get(bs, wx, wy, wz) != 0) continue;
        st_set(bs, wx, wy, wz, 54, 2);
        st_fill_chest(bs, wx, wy, wz, d, d->rand_group, rnd, &h);
    }
    return 1;
}

/* chunks done lately: StructureFeature::postProcess runs 3 times per chunk (no __thread: libnova must not need
 * __emutls_get_address, the game process does not export it and libnova would not load) */
static int st_recent[64][2];
static int st_recent_n;
static volatile int st_lock;
static int st_first_time(int cx, int cz) {
    while (!__sync_bool_compare_and_swap(&st_lock, 0, 1)) sched_yield();
    int fresh = 1;
    for (int i = 0; i < 64 && i < st_recent_n; i++) if (st_recent[i][0] == cx && st_recent[i][1] == cz) { fresh = 0; break; }
    if (fresh) { int k = st_recent_n++ & 63; st_recent[k][0] = cx; st_recent[k][1] = cz; }
    st_lock = 0;
    return fresh;
}

static u64 hc_structure_pp(u32 orig, u32 *w) {     /* StructureFeature::postProcess(this, BlockSource*, Random&, cx, cz) */
    u64 ret = nh_callw(orig, w);
    void *bs = (void *)w[1], *rnd = (void *)w[2];
    int cx = (int)w[3], cz = (int)w[4];
    if (!st_count || !bs || !st_first_time(cx, cz)) return ret;
    if (st_loot_ready != 2) {                        /* the loot vectors need the items: built on first use */
        if (__sync_bool_compare_and_swap(&st_loot_ready, 0, 1)) { st_build_loot(); st_loot_ready = 2; }
        else while (st_loot_ready != 2) sched_yield();
    }
    void *lvl = sf_bslevel ? sf_bslevel(bs) : 0;
    u32 seed = lvl && sf_seed ? (u32)sf_seed(lvl) : 0;
    int pos[3] = {cx * 16 + 8, 64, cz * 16 + 8};
    void *biome = sf_biome ? sf_biome(bs, pos) : 0;
    int bid = biome ? *(int *)((char *)biome + 0x94) : -1;
    u32 h = st_hash(seed, (u32)cx, (u32)cz);
    int start = (int)st_mod(h, st_count);
    for (int k = 0; k < st_count; k++) {             /* every structure gets its own roll; at most one per chunk */
        int idx = start + k >= st_count ? start + k - st_count : start + k;
        SDef *d = &st_defs[idx];
        u32 hh = st_hash(h, (u32)idx, 0x5157);
        if (st_mod(hh, d->chance)) continue;
        int okb = d->nbio == 0;
        for (int i = 0; i < d->nbio && !okb; i++) if (d->bio[i] == bid) okb = 1;
        if (!okb) continue;
        if (st_place(bs, rnd, d, cx, cz, hh)) break;
    }
    return ret;
}

static void structs_init(void *mc) {
    sf_getid = dlsym(mc, "_ZN11BlockSource10getBlockIDEiii");
    sf_set = dlsym(mc, "_ZN11BlockSource15setBlockAndDataEiii9FullBlocki");
    sf_be = dlsym(mc, "_ZN11BlockSource14getBlockEntityEiii");
    sf_biome = dlsym(mc, "_ZN11BlockSource8getBiomeERK8BlockPos");
    sf_bslevel = dlsym(mc, "_ZN11BlockSource8getLevelEv");
    sf_seed = dlsym(mc, "_ZN5Level7getSeedEv");
    sf_item_ctor = dlsym(mc, "_ZN12ItemInstanceC1Eiii");
    sf_items = dlsym(mc, "_ZN4Item6mItemsE");
    sf_addchest = dlsym(mc, "_ZN19WeighedTreasureItem13addChestItemsER6RandomRKSt6vectorIS_SaIS_EER9Containeri");
    sf_getspawner = dlsym(mc, "_ZN21MobSpawnerBlockEntity10getSpawnerEv");
    sf_setentity = dlsym(mc, "_ZN14BaseMobSpawner11setEntityIdE10EntityType");
    sf_entitytype = dlsym(mc, "_Z20EntityTypeFromStringRKSs");
    void *pp = dlsym(mc, "_ZN16StructureFeature11postProcessEP11BlockSourceR6Randomii");
    int ok = sf_getid && sf_set && sf_biome && sf_item_ctor && sf_items && pp;
    LOG("structures: hook %d", ok ? nh_hook_c((u32)pp, hc_structure_pp) : -1);
}
