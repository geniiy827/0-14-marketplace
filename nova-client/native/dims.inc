/* dims.inc - dimensions of mods (`dimension name { portal: {..}, generator: {..} }`, ModDims.java): up to DM_MAX,
 * numbers 2.., each with its own portal block (a frame of the mod's block, lit by fire like the nether's).
 *
 * Level keeps its dimensions in a map<int, unique_ptr<Dimension>> and makes them with Dimension::createNew(id),
 * which knows 0 (NormalDimension) and 1 (HellDimension). For 2 we make a Dimension(Level&, 2) dressed as a
 * NormalDimension (its vtable copied: sky, day, weather of the overworld) that cannot be respawned in. Chunks of a
 * dimension are saved under its number in the world's LevelDB, ChangeDimensionPacket carries it as a byte.
 *   Dimension 0xb4 bytes: +0 vptr, +4 vptr (SavedData part, vtable word 49), +0x10 id, +0x58 Level*.
 *   Dimension::_createGenerator(type) -> unique_ptr<ChunkSource>: ours for 2 - a ChunkSource built like
 *   FlatLevelSource (requestChunk / postProcess of Flat) with our loadChunk: floating islands of striped planks.
 *   LevelChunk: blocks +0xa40 [x<<11 | z<<7 | y], data nibbles +0x8a40; ChunkBlockPos {u8 x, z, y}.
 *
 * The portal: a block made as PortalBlock is (HalfTransparentBlock, material 0x19, ticking, not solid) with its own
 * texture (nova_wood_portal: portal.png recoloured, a flipbook like the portal) and PortalBlock's vtable, except
 * tick (no pigmen), neighborChanged (the frame is planks) and entityInside (remembers who stepped in, then the
 * game's own: Entity::handleInsidePortal -> +0xec = portal, the wait, the overlay, then changeDimension(dim==0 ? 1 : 0)).
 * Level::requestPlayerChangeDimension: a player who was in our portal goes to 2 (from 2: to 0) instead.
 * PortalForcer::translatePosAcrossDimension: no x8 for 2; PortalForcer::force: our wooden portal is found or built
 * at the arrival (the game's would build an obsidian one). A planks frame is lit by fire: FireBlock::onPlace. */
#define DM_MAX 4
typedef struct {
    int id, frame, type, base, size, top, top_d, fill, fill_d, fill_n, roots, roots_d, respawn, portal;
    float density, trees, grass;
    int nbody, nlayer;
    u8 body[16][2], layer[32][3];                  /* islands: stripes {block, data}; flat: {block, data, count} */
    void **biome;
    char tex[48];
} Dim;
static Dim dm[DM_MAX];
static int dm_n, wd_ok, dm_registered;
static u32 *wd_dim_vt, *wd_dim_vt_r, *wd_cs_vt, *wd_pb_vt;
static Dim *dm_by_id(int id) { for (int i = 0; i < dm_n; i++) if (dm[i].id == id) return &dm[i]; return 0; }
static Dim *dm_by_portal(int b) { for (int i = 0; i < dm_n; i++) if (dm[i].portal == b && b > 0) return &dm[i]; return 0; }
static void *(*wd_dim_ctor)(void *self, void *level, int id);
static void (*wd_cs_ctor)(void *self, void *level, void *dim, int side);
static u8 *(*wd_raw_blocks)(void *chunk), *(*wd_raw_data)(void *chunk);
static void (*wd_heightmap)(void *chunk), (*wd_blocklights)(void *chunk);
static int *(*wd_chunk_pos)(void *chunk);
static void *(*wd_chunk_min)(void *chunk);
static void (*wd_set_biome)(void *chunk, void *biome, u8 *cbp);
static void (*wd_set_grass)(void *chunk, int color, u8 *cbp);
static void (*wd_finalized)(void *chunk, int f);
static void (*wd_saved)(void *chunk);
static void (*wd_state)(void *chunk, int from, int to);
static void **wd_forest;
static u32 (*wd_seed)(void *level);
static void *(*wd_region)(void *entity);
static int (*wd_bs_dim)(void *bs);
static void *(*wd_biome_sym)(const char *);
static void (*wd_get_id)(u8 *out, void *bs, int x, int y, int z);   /* BlockID by sret */
static u32 (*wd_get_data)(void *bs, int x, int y, int z);
static void (*wd_travel)(void *forcer, void *entity, MPos *pos, int dim);
static void (*wd_htb_ctor)(void *self, void *name, int id, void *tex, void *mat, int b);
static void *(*wd_material)(int type);
static void (*wd_set_ticking)(void *b, int on), (*wd_set_solid)(void *b, int on);
static void (*wd_pb_inside)(void *b, void *bs, void *pos, void *ent);

/* ---------------------------------------------------------------- generation ---- */
static u32 wd_hash(int x, int z, u32 s) {
    u32 h = (u32)x * 374761393u + (u32)z * 668265263u + s * 2246822519u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}
static float wd_rnd(int x, int z, u32 s) { return (float)(wd_hash(x, z, s) & 0xffffff) / 16777216.f; }
static float wd_vnoise(float x, float z, u32 s) {
    int ix = (int)floorf(x), iz = (int)floorf(z);
    float fx = x - ix, fz = z - iz;
    fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
    float a = wd_rnd(ix, iz, s), b = wd_rnd(ix + 1, iz, s), c = wd_rnd(ix, iz + 1, s), d = wd_rnd(ix + 1, iz + 1, s);
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
}
static float wd_fbm(float x, float z, u32 s) {
    return 0.5f * wd_vnoise(x, z, s) + 0.3f * wd_vnoise(x * 2.03f, z * 2.03f, s + 101) + 0.2f * wd_vnoise(x * 4.1f, z * 4.1f, s + 202);
}
static void wd_put(u8 *bl, u8 *dt, int x, int y, int z, int id, int d) {
    if (x < 0 || x > 15 || z < 0 || z > 15 || y < 0 || y > 127) return;
    int i = (x << 11) | (z << 7) | y;
    bl[i] = (u8)id;
    u8 *p = &dt[i >> 1];
    if (i & 1) *p = (u8)((*p & 0x0f) | ((d & 15) << 4)); else *p = (u8)((*p & 0xf0) | (d & 15));
}
static int wd_at(u8 *bl, int x, int y, int z) {
    if (x < 0 || x > 15 || z < 0 || z > 15 || y < 0 || y > 127) return -1;
    return bl[(x << 11) | (z << 7) | y];
}

/* islands: grass, dirt, then the body in stripes; roots under them (Dim: top, fill, body, roots) */
static int wd_column(Dim *D, u8 *bl, u8 *dt, int x, int z, int wx, int wz, u32 seed) {
    float n = wd_fbm(wx / (float)D->size, wz / (float)D->size, seed);
    if (n <= D->density) return -1;
    float s = (n - D->density) / 0.3f;
    if (s > 1) s = 1;
    int top = D->base + (int)(wd_fbm(wx / 22.f, wz / 22.f, seed + 7) * 16) + (int)(s * 6);
    int depth = 3 + (int)(s * s * 34 * (0.6f + 0.4f * wd_rnd(wx, wz, seed + 3)));
    int bottom = top - depth;
    if (bottom < 4) bottom = 4;
    if (top > 118) top = 118;
    int shift = (int)(wd_vnoise(wx / 9.f, wz / 9.f, seed + 5) * 4);           /* once per column */
    #define WD_FALLS(b) ((b) == 12 || (b) == 13 || (b) == 145 || (b) == 122)   /* sand, gravel, anvil, egg */
    for (int y = bottom; y <= top; y++) {
        int under = y == bottom;                       /* nothing below: sand / gravel would fall into the void */
        if (y == top && !(under && WD_FALLS(D->top))) wd_put(bl, dt, x, y, z, D->top, D->top_d);
        else if (y >= top - D->fill_n && y < top && !(under && WD_FALLS(D->fill))) wd_put(bl, dt, x, y, z, D->fill, D->fill_d);
        else if (y == top) wd_put(bl, dt, x, y, z, D->nbody ? D->body[0][0] : 1, D->nbody ? D->body[0][1] : 0);
        else if (D->nbody) {
            int k = ((y + shift) / 3) % D->nbody;
            if (under && WD_FALLS(D->body[k][0])) k = 0;
            wd_put(bl, dt, x, y, z, WD_FALLS(D->body[k][0]) && under ? 1 : D->body[k][0], D->body[k][1]);
        } else wd_put(bl, dt, x, y, z, 1, 0);
    }
    if (D->roots > 0 && s > 0.15f && wd_rnd(wx, wz, seed + 11) < 0.07f) {
        u32 h = wd_hash(wx, wz, seed + 12);
        int len = 1 + (int)(h % 6);
        if (!WD_FALLS(D->roots)) for (int k = 1; k <= len && bottom - k > 0; k++) wd_put(bl, dt, x, bottom - k, z, D->roots, D->roots_d >= 0 ? D->roots_d : (int)((h >> 8) % 4));
    }
    return top;
}
static void wd_tree(u8 *bl, u8 *dt, int x, int y, int z, u32 h) {
    static const int kinds[3] = {0, 2, 1};                                  /* oak, birch, spruce */
    int kind = kinds[h % 3], height = 4 + (int)((h >> 4) % 3);
    for (int dy = height - 2; dy <= height + 1; dy++) {
        int r = dy >= height ? 1 : 2;
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++) {
                if (r == 2 && (dx == -2 || dx == 2) && (dz == -2 || dz == 2) && ((h >> (dx + dz + 8)) & 1)) continue;
                if (wd_at(bl, x + dx, y + dy, z + dz) == 0) wd_put(bl, dt, x + dx, y + dy, z + dz, 18, kind | 4);
            }
    }
    for (int dy = 0; dy < height; dy++) wd_put(bl, dt, x, y + dy, z, 17, kind);
}

static void wd_load_chunk(void *self, void *chunk) {
    u32 seed = *(u32 *)((char *)self + 0x18);
    int di = *(int *)((char *)self + 0x1c);
    Dim *D = &dm[di >= 0 && di < dm_n ? di : 0];
    int *cp = wd_chunk_pos(chunk);
    int cx = cp[0], cz = cp[1];
    u8 *bl = wd_raw_blocks(chunk), *dt = wd_raw_data(chunk);
    memset(bl, 0, 0x8000);
    memset(dt, 0, 0x4000);
    if (D->type == 1) {                                                      /* flat: layers from the bottom */
        int y = 0;
        for (int l = 0; l < D->nlayer && y < 128; l++)
            for (int c = 0; c < D->layer[l][2] && y < 128; c++, y++)
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) wd_put(bl, dt, x, y, z, D->layer[l][0], D->layer[l][1]);
    } else {
        signed char tops[16][16];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) tops[x][z] = (signed char)wd_column(D, bl, dt, x, z, cx * 16 + x, cz * 16 + z, seed);
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int t = tops[x][z], wx = cx * 16 + x, wz = cz * 16 + z;
                if (t < 0 || t > 110) continue;
                if (x >= 2 && x <= 13 && z >= 2 && z <= 13 && wd_rnd(wx, wz, seed + 21) < D->trees) wd_tree(bl, dt, x, t + 1, z, wd_hash(wx, wz, seed + 22));
                else if (wd_rnd(wx, wz, seed + 31) < D->grass && wd_at(bl, x, t + 1, z) == 0) wd_put(bl, dt, x, t + 1, z, 31, 1);
            }
    }
    wd_blocklights(chunk);
    wd_heightmap(chunk);
    void *biome = D->biome ? *D->biome : (wd_forest ? *wd_forest : 0);
    if (biome) {
        int color = ((int (*)(void *, void *))(*(u32 **)biome)[0x40 / 4])(biome, wd_chunk_min(chunk));
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                u8 cbp[3] = {(u8)x, (u8)z, 0};
                wd_set_biome(chunk, biome, cbp);
                wd_set_grass(chunk, color, cbp);
            }
    }
    wd_finalized(chunk, 2);
    wd_saved(chunk);
    wd_state(chunk, 1, 4);
}

/* ---------------------------------------------------------------- the dimension ---- */
static int wd_no_respawn(void *dim) { (void)dim; return 0; }

static u64 hc_wd_new(u32 orig, u32 *w) {                     /* Dimension::createNew(out, id, Level&) */
    Dim *D = wd_ok ? dm_by_id((int)w[1]) : 0;
    if (D && w[0] && w[2]) {
        u32 *vt = D->respawn ? wd_dim_vt_r : wd_dim_vt;
        char *d = f_new(0xb4);
        memset(d, 0, 0xb4);
        wd_dim_ctor(d, (void *)w[2], 0);           /* the constructor names it from a table of the game's dimensions */
        *(int *)(d + 0x10) = D->id;                 /* by number: past 2 that read garbage (alpha51 crash) */
        char nm[24];                                 /* +0xc: the name its saved data is kept under, its own */
        snprintf(nm, sizeof nm, "NovaDimension%d", D->id);
        f_strdtor(d + 0xc);
        gstr(d + 0xc, nm);
        ((u32 *)d)[0] = (u32)(vt + 2);
        ((u32 *)d)[1] = (u32)(vt + 49);
        *(void **)w[0] = d;
        return w[0];
    }
    return nh_callw(orig, w);
}
static u64 hc_wd_gen(u32 orig, u32 *w) {                     /* Dimension::_createGenerator(out, this, type) */
    char *dim = (char *)w[1];
    Dim *D = wd_ok && dim ? dm_by_id(*(int *)(dim + 0x10)) : 0;
    if (D && w[0]) {
        void *lvl = *(void **)(dim + 0x58);
        char *cs = f_new(0x20);
        memset(cs, 0, 0x20);
        wd_cs_ctor(cs, lvl, dim, 16);
        *(u32 *)cs = (u32)(wd_cs_vt + 2);
        *(u32 *)(cs + 0x18) = (lvl && wd_seed ? wd_seed(lvl) : 0x5eed) + (u32)D->id * 7919u;
        *(int *)(cs + 0x1c) = (int)(D - dm);
        *(void **)w[0] = cs;
        return w[0];
    }
    return nh_callw(orig, w);
}

/* ---------------------------------------------------------------- the portal ---- */
static int wd_air(u32 id) { id &= 0xff; return id == 0 || id == 51; }       /* air or fire */
static u32 wd_id(void *bs, int x, int y, int z) {
    if (y < 0 || y > 127) return 7;
    u8 out[4] = {0, 0, 0, 0};
    wd_get_id(out, bs, x, y, z);
    return out[0];
}
static void wd_set(void *bs, int x, int y, int z, int id, int data, int flags) {
    if (y < 0 || y > 127 || !mf_setblock_pos) return;
    MPos p = {x, y, z};
    MBlockID b = {(unsigned char)id};
    mf_setblock_pos(bs, &p, &b, (unsigned char)data, flags);
}

/* the inside of a planks frame (2..21 wide, 3..21 high) around (x, y, z) along (dx, dz): filled with the portal */
static int wd_try_axis(Dim *D, void *bs, int x, int y, int z, int dx, int dz) {
    int F = D->frame;
    int by = y;
    while (by > 0 && y - by < 21 && wd_air(wd_id(bs, x, by - 1, z))) by--;
    if ((int)wd_id(bs, x, by - 1, z) != F) return 0;
    int lx = x, lz = z, n = 0;
    while (n < 21 && wd_air(wd_id(bs, lx - dx, by, lz - dz))) { lx -= dx; lz -= dz; n++; }
    if ((int)wd_id(bs, lx - dx, by, lz - dz) != F) return 0;
    int wdt = 0;
    while (wdt < 22 && wd_air(wd_id(bs, lx + dx * wdt, by, lz + dz * wdt)) && (int)wd_id(bs, lx + dx * wdt, by - 1, lz + dz * wdt) == F) wdt++;
    if (wdt < 2 || wdt > 21 || (int)wd_id(bs, lx + dx * wdt, by, lz + dz * wdt) != F) return 0;
    int h = 0;
    for (; h < 22; h++) {
        int yy = by + h, air = 1, planks = 1;
        for (int i = 0; i < wdt; i++) {
            u32 id = wd_id(bs, lx + dx * i, yy, lz + dz * i);
            if (!wd_air(id)) air = 0;
            if ((int)id != F) planks = 0;
        }
        if (planks && h >= 3) break;
        if (!air) return 0;
        if ((int)wd_id(bs, lx - dx, yy, lz - dz) != F || (int)wd_id(bs, lx + dx * wdt, yy, lz + dz * wdt) != F) return 0;
    }
    if (h < 3 || h > 21) return 0;
    for (int j = 0; j < h; j++)
        for (int i = 0; i < wdt; i++) wd_set(bs, lx + dx * i, by + j, lz + dz * i, D->portal, dx ? 1 : 2, 2);
    return 1;
}
static u64 hc_wd_fire(u32 orig, u32 *w) {                    /* FireBlock::onPlace(this, BlockSource&, BlockPos const&) */
    if (wd_ok && w[1] && w[2]) {
        int *p = (int *)w[2];
        for (int k = 0; k < dm_n; k++) {
            Dim *D = &dm[k];
            if (D->portal <= 0 || D->frame <= 0) continue;
            if (wd_try_axis(D, (void *)w[1], p[0], p[1], p[2], 1, 0) || wd_try_axis(D, (void *)w[1], p[0], p[1], p[2], 0, 1)) return 0;
        }
    }
    return nh_callw(orig, w);
}

static void wd_tick(void *b, void *bs, void *pos, void *rnd) { (void)b; (void)bs; (void)pos; (void)rnd; }
static void wd_neighbor(void *b, void *bs, MPos *pos, MPos *from) {
    (void)from;
    Dim *D = dm_by_portal(*((u8 *)b + 4));
    if (!D) return;
    int x = pos->x, y = pos->y, z = pos->z;
    int axis = (int)(wd_get_data(bs, x, y, z) & 3), dx = axis == 2 ? 0 : 1, dz = axis == 2 ? 1 : 0;
    #define WD_FRAME(id) ((int)(id) == D->frame || (int)(id) == D->portal)
    if (WD_FRAME(wd_id(bs, x, y - 1, z)) && WD_FRAME(wd_id(bs, x, y + 1, z)) &&
        WD_FRAME(wd_id(bs, x - dx, y, z - dz)) && WD_FRAME(wd_id(bs, x + dx, y, z + dz))) return;
    #undef WD_FRAME
    wd_set(bs, x, y, z, 0, 0, 3);
}

/* who stood in the wooden portal lately */
static struct { void *e; long t; Dim *d; MPos p; } wd_marks[32];
static void *wd_traveller;
/* the portal a player last left a dimension through, per (dimension, portal kind): the way back leads there */
static struct { int dim; Dim *d; MPos p; } wd_links[16];
static void wd_link(int dim, Dim *d, MPos p) {
    int k = 15;
    for (int i = 0; i < 16; i++) if ((wd_links[i].d == d && wd_links[i].dim == dim) || !wd_links[i].d) { k = i; break; }
    wd_links[k].dim = dim; wd_links[k].d = d; wd_links[k].p = p;
}
static Dim *wd_trav_dim;
static void wd_mark(void *e, Dim *d, MPos *p) {
    long now = now_ms();
    int k = 0;
    for (int i = 0; i < 32; i++) {
        if (wd_marks[i].e == e) { k = i; break; }
        if (wd_marks[i].t < wd_marks[k].t) k = i;
    }
    wd_marks[k].e = e; wd_marks[k].t = now; wd_marks[k].d = d; wd_marks[k].p = *p;
}
static Dim *wd_marked(void *e, MPos *at) {
    for (int i = 0; i < 32; i++)
        if (wd_marks[i].e == e && now_ms() - wd_marks[i].t < 1500) { wd_marks[i].e = 0; *at = wd_marks[i].p; return wd_marks[i].d; }
    return 0;
}
static void wd_inside(void *b, void *bs, void *pos, void *ent) {
    Dim *D = dm_by_portal(*((u8 *)b + 4));
    if (ent && D && pos) wd_mark(ent, D, (MPos *)pos);
    if (wd_pb_inside) wd_pb_inside(b, bs, pos, ent);
}

static u64 hc_wd_request(u32 orig, u32 *w) {                 /* Level::requestPlayerChangeDimension(Player&, unique_ptr) */
    void *pl = (void *)w[1];
    char *req = w[2] ? *(char **)w[2] : 0;
    if (pl == wd_traveller) wd_traveller = 0;
    MPos at = {0, 0, 0};
    Dim *D = wd_ok && req && pl ? wd_marked(pl, &at) : 0;
    if (D) {
        int from = *(int *)(req + 8);
        *(int *)(req + 0xc) = from == D->id ? 0 : D->id;
        wd_traveller = pl;
        wd_trav_dim = D;
        wd_link(from, D, at);                                          /* the portal block stepped in */
        LOG("dims: player goes %d -> %d", from, *(int *)(req + 0xc));
    }
    return nh_callw(orig, w);
}
static u64 hc_wd_translate(u32 orig, u32 *w) {               /* (out Vec3, this, Vec3 const&, from, to) */
    if (dm_by_id((int)w[3]) || dm_by_id((int)w[4])) { memcpy((void *)w[0], (void *)w[2], 12); return w[0]; }
    return nh_callw(orig, w);
}
static u64 hc_wd_find(u32 orig, u32 *w) {                    /* findPortal: not the game's portals for our trip */
    if (wd_traveller) return 0;
    return nh_callw(orig, w);
}

/* arrival: the nearest wooden portal within 16 blocks, or a new one on the ground (on a platform over the void) */
static void wd_arrive(void *forcer, void *ent, Dim *D) {
    int P = D->portal, F = D->frame;
    void *bs = wd_region(ent);
    if (!bs) return;
    float *pp = (float *)((char *)ent + 8);
    int bx = (int)floorf(pp[0]), bz = (int)floorf(pp[2]), dim = wd_bs_dim(bs);
    long t0 = now_ms();
    int best = 1 << 30, y0 = (int)pp[1] - 40, y1 = (int)pp[1] + 40;
    if (y0 < 1) y0 = 1;
    if (y1 > 126) y1 = 126;
    MPos at = {0, 0, 0};
    for (int i = 0; i < 16; i++) {                                     /* the portal we left this dimension through */
        if (wd_links[i].d != D || wd_links[i].dim != dim) continue;
        MPos l = wd_links[i].p;
        int ddx = l.x - bx, ddz = l.z - bz;
        if (ddx * ddx + ddz * ddz > 64 * 64 || (int)wd_id(bs, l.x, l.y, l.z) != P) break;
        while (l.y > 1 && (int)wd_id(bs, l.x, l.y - 1, l.z) == P) l.y--;
        at = l; best = 0;
        break;
    }
    for (int dx = -12; dx <= 12 && best; dx++)
        for (int dz = -12; dz <= 12 && best; dz++)
            for (int y = y0; y <= y1; y++) {
                if ((int)wd_id(bs, bx + dx, y, bz + dz) != P) continue;
                int yy = y;
                while (yy > 1 && (int)wd_id(bs, bx + dx, yy - 1, bz + dz) == P) yy--;
                int d = dx * dx + dz * dz + (yy - (int)pp[1]) * (yy - (int)pp[1]);
                if (d < best) { best = d; at.x = bx + dx; at.y = yy; at.z = bz + dz; }
                break;
            }
    if (best == 1 << 30) {
        int sy = -1;
        for (int y = 118; y > 2; y--) {
            u32 id = wd_id(bs, bx, y, bz);
            if (id != 0 && id != 18 && id != 161 && id != 31 && id != 17 && id != 162 && id != 78) { sy = y + 1; break; }
        }
        if (sy < 0) sy = 70;
        if (sy > 118) sy = 118;
        for (int x = bx - 2; x <= bx + 3; x++)                       /* a floor, and room around the frame */
            for (int z = bz - 2; z <= bz + 2; z++) {
                if (wd_air(wd_id(bs, x, sy - 1, z)) || wd_id(bs, x, sy - 1, z) == 31) wd_set(bs, x, sy - 1, z, F, 0, 2);
                for (int y = sy; y <= sy + 3; y++) if ((z != bz || x < bx - 1 || x > bx + 2) && !wd_air(wd_id(bs, x, y, z))) wd_set(bs, x, y, z, 0, 0, 2);
            }
        for (int x = bx - 1; x <= bx + 2; x++) { wd_set(bs, x, sy - 1, bz, F, 0, 2); wd_set(bs, x, sy + 3, bz, F, 0, 2); }
        for (int y = sy; y <= sy + 2; y++) { wd_set(bs, bx - 1, y, bz, F, 0, 2); wd_set(bs, bx + 2, y, bz, F, 0, 2); }
        for (int y = sy; y <= sy + 2; y++) for (int x = bx; x <= bx + 1; x++) wd_set(bs, x, y, bz, P, 1, 2);
        at.x = bx; at.y = sy; at.z = bz;
        LOG("dims: portal built at %d %d %d (dimension %d)", bx, sy, bz, dim);
    }
    wd_travel(forcer, ent, &at, dim);
    LOG("dims: arrival in %d took %ld ms", dim, now_ms() - t0);
}
static u64 hc_wd_force(u32 orig, u32 *w) {                   /* PortalForcer::force(this, Entity&) */
    if (wd_traveller && (void *)w[1] == wd_traveller && wd_trav_dim) {
        wd_traveller = 0;
        wd_arrive((void *)w[0], (void *)w[1], wd_trav_dim);
        return 0;
    }
    return nh_callw(orig, w);
}

/* portal blocks: after the game's blocks and the mods' (Item::initCreativeItems), in numbers no mod gets */
static const u8 wd_pool[] = {29, 33, 34, 250, 251, 252, 253, 254};
static int wd_free_id(int below) {
    for (int id = below; id > 1; id--) {
        if (id >= 200 && id <= 242) continue;                         /* ModBlocks.POOL */
        int pooled = 0;
        for (unsigned k = 0; k < sizeof wd_pool; k++) if (wd_pool[k] == id) pooled = 1;
        if (pooled || mf_items[id] || dm_by_portal(id)) continue;
        return id;
    }
    return -1;
}
static void wd_register(void) {
    dm_registered = 1;
    if (!wd_ok || !dm_n || !mf_blocks || !mf_items || !mf_texset) return;
    int below = 255;
    for (int k = 0; k < dm_n; k++) {
        Dim *D = &dm[k];
        if (D->portal > 0) continue;
        int id = wd_free_id(below);
        if (id < 0) { LOGE("dims: no free block number for the portal of dimension %d", D->id); continue; }
        below = id - 1;
        char *b = f_new(0x9c);
        memset(b, 0, 0x9c);
        u32 nm[1], tx[1];
        char name[64];
        snprintf(name, sizeof name, "nova_portal_%d", D->id);
        gstr(nm, name);
        gstr(tx, D->tex[0] ? D->tex : "portal");
        wd_htb_ctor(b, nm, id, tx, wd_material(0x19), 0);
        *(u32 *)b = (u32)(wd_pb_vt + 2);
        wd_set_ticking(b, 1);
        wd_set_solid(b, 0);
        *(int *)(b + 0x38) = 0x800;
        b[0x40] = 1;
        mf_texset(b + 16, tx, 0);
        f_strdtor(nm);
        f_strdtor(tx);
        mf_blocks[id] = b;
        ((void (*)(void *))wd_pb_vt[2 + 94])(b);                     /* Block::init */
        if (mf_light_emission) mf_light_emission[id] = 11;
        D->portal = id;
        LOG("dims: dimension %d, portal block %d, frame %d", D->id, id, D->frame);
    }
}

/* ModDims.java: per dimension 18 numbers + stripes / layers; names: "texture\x1fbiome" per dimension, \x1e between */
int Java_com_nova_client_ModBridge_nativeDimensions(void **env, void *cls, void *darr, void *jnames) {
    void **f = *(void ***)env;
    if (!darr || dm_n) return dm_n;
    int n = ((int (*)(void *, void *))f[171])(env, darr);
    double *d = f_new(n * 8 + 8);
    ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, d);
    static char names[DM_MAX * 128];
    jstr_copy(env, jnames, names, sizeof names);
    char *np = names;
    int i = 0;
    while (i + 18 <= n && dm_n < DM_MAX) {
        Dim *D = &dm[dm_n];
        memset(D, 0, sizeof *D);
        D->id = (int)d[i]; D->frame = (int)d[i + 1]; D->type = (int)d[i + 2]; D->base = (int)d[i + 3]; D->size = (int)d[i + 4];
        D->density = (float)d[i + 5]; D->top = (int)d[i + 6]; D->top_d = (int)d[i + 7]; D->fill = (int)d[i + 8];
        D->fill_d = (int)d[i + 9]; D->fill_n = (int)d[i + 10]; D->roots = (int)d[i + 11]; D->roots_d = (int)d[i + 12];
        D->trees = (float)d[i + 13]; D->grass = (float)d[i + 14]; D->respawn = (int)d[i + 15];
        int nb = (int)d[i + 16], nl = (int)d[i + 17];
        i += 18;
        for (int k = 0; k < nb && i + 2 <= n; k++, i += 2) if (k < 16) { D->body[k][0] = (u8)d[i]; D->body[k][1] = (u8)d[i + 1]; D->nbody = k + 1; }
        for (int k = 0; k < nl && i + 3 <= n; k++, i += 3) if (k < 32) { D->layer[k][0] = (u8)d[i]; D->layer[k][1] = (u8)d[i + 1]; D->layer[k][2] = (u8)d[i + 2]; D->nlayer = k + 1; }
        if (D->size < 8) D->size = 8;
        int j = 0;
        while (*np && *np != '\x1f' && *np != '\x1e' && j < 47) D->tex[j++] = *np++;
        D->tex[j] = 0;
        char bio[48]; j = 0;
        if (*np == '\x1f') { np++; while (*np && *np != '\x1e' && j < 40) bio[j++] = *np++; }
        bio[j] = 0;
        if (*np == '\x1e') np++;
        if (D->id < 2 || D->id > 100 || dm_by_id(D->id)) continue;
        if (bio[0]) {
            char sym[80];
            int bl = 0; while (bio[bl]) bl++;
            snprintf(sym, sizeof sym, "_ZN5Biome%d%sE", bl, bio);
            D->biome = dlsym(dlopen("libminecraftpe.so", 0x4 | 0x2), sym);
        }
        dm_n++;
    }
    LOG("dims: %d dimension(s) of mods", dm_n);
    if (dm_registered) wd_register();                                /* the blocks are made already: now */
    return dm_n;
}

static void dims_init(void *mc) {
    #define S(v, n) *(void **)&(v) = dlsym(mc, n)
    S(wd_dim_ctor, "_ZN9DimensionC2ER5Level11DimensionId");
    S(wd_cs_ctor, "_ZN11ChunkSourceC2EP5LevelP9Dimensioni");
    S(wd_raw_blocks, "_ZN10LevelChunk12getRawBlocksEv");
    S(wd_raw_data, "_ZN10LevelChunk10getRawDataEv");
    S(wd_heightmap, "_ZN10LevelChunk15recalcHeightmapEv");
    S(wd_blocklights, "_ZN10LevelChunk17recalcBlockLightsEv");
    S(wd_chunk_pos, "_ZNK10LevelChunk11getPositionEv");
    S(wd_chunk_min, "_ZNK10LevelChunk6getMinEv");
    S(wd_set_biome, "_ZN10LevelChunk8setBiomeERK5BiomeRK13ChunkBlockPos");
    S(wd_set_grass, "_ZN10LevelChunk13setGrassColorEiRK13ChunkBlockPos");
    S(wd_finalized, "_ZN10LevelChunk12setFinalizedENS_12FinalizationE");
    S(wd_saved, "_ZN10LevelChunk8setSavedEv");
    S(wd_state, "_ZN10LevelChunk11changeStateE10ChunkStateS0_");
    S(wd_forest, "_ZN5Biome6forestE");
    S(wd_seed, "_ZN5Level7getSeedEv");
    S(wd_region, "_ZNK6Entity9getRegionEv");
    S(wd_bs_dim, "_ZNK11BlockSource14getDimensionIdEv");
    S(wd_get_id, "_ZN11BlockSource10getBlockIDEiii");
    S(wd_get_data, "_ZN11BlockSource7getDataEiii");
    S(wd_travel, "_ZN12PortalForcer12travelPortalER6EntityRK8BlockPos11DimensionId");
    S(wd_htb_ctor, "_ZN20HalfTransparentBlockC2ERKSsiS1_RK8Materialb");
    S(wd_material, "_ZN8Material11getMaterialE12MaterialType");
    S(wd_set_ticking, "_ZN5Block10setTickingEb");
    S(wd_set_solid, "_ZN5Block8setSolidEb");
    u32 *nd = dlsym(mc, "_ZTV15NormalDimension"), *fl = dlsym(mc, "_ZTV15FlatLevelSource"), *cs = dlsym(mc, "_ZTV11ChunkSource");
    u32 *pb = dlsym(mc, "_ZTV11PortalBlock");
    void *cnew = dlsym(mc, "_ZN9Dimension9createNewE11DimensionIdR5Level");
    void *cgen = dlsym(mc, "_ZN9Dimension16_createGeneratorE13GeneratorType");
    void *fire = dlsym(mc, "_ZN9FireBlock7onPlaceER11BlockSourceRK8BlockPos");
    void *req = dlsym(mc, "_ZN5Level28requestPlayerChangeDimensionER6PlayerSt10unique_ptrI22ChangeDimensionRequestSt14default_deleteIS3_EE");
    void *tr = dlsym(mc, "_ZNK12PortalForcer27translatePosAcrossDimensionERK4Vec311DimensionIdS3_");
    void *fp = dlsym(mc, "_ZNK12PortalForcer10findPortalE11DimensionIdRK8BlockPosiRS1_");
    void *fo = dlsym(mc, "_ZN12PortalForcer5forceER6Entity");
    #undef S
    if (!wd_dim_ctor || !wd_cs_ctor || !wd_raw_blocks || !wd_raw_data || !wd_heightmap || !wd_blocklights || !wd_chunk_pos ||
        !wd_chunk_min || !wd_set_biome || !wd_set_grass || !wd_finalized || !wd_saved || !wd_state || !wd_region || !wd_bs_dim ||
        !wd_get_id || !wd_get_data || !wd_travel || !wd_htb_ctor || !wd_material || !wd_set_ticking || !wd_set_solid ||
        !nd || !fl || !cs || !pb || !cnew || !cgen || !fire || !req || !tr || !fp || !fo || !f_new) {
        LOGE("dims: symbols missing, dimensions of mods are off");
        return;
    }
    wd_dim_vt = f_new(53 * 4);
    memcpy(wd_dim_vt, nd, 53 * 4);
    wd_dim_vt[2 + 32] = (u32)wd_no_respawn;                         /* mayRespawn: death sends you home */
    wd_dim_vt_r = f_new(53 * 4);
    memcpy(wd_dim_vt_r, nd, 53 * 4);
    wd_cs_vt = f_new(20 * 4);
    memcpy(wd_cs_vt, fl, 20 * 4);
    wd_cs_vt[2] = cs[2]; wd_cs_vt[3] = cs[3];                        /* ~ChunkSource: our object is a plain one */
    wd_cs_vt[2 + 6] = (u32)wd_load_chunk;
    wd_pb_vt = f_new(112 * 4);
    memcpy(wd_pb_vt, pb, 110 * 4);
    wd_pb_vt[110] = wd_pb_vt[111] = 0;
    *(u32 *)&wd_pb_inside = pb[2 + 60];
    wd_pb_vt[2 + 2] = (u32)wd_tick;
    wd_pb_vt[2 + 46] = (u32)wd_neighbor;
    wd_pb_vt[2 + 60] = (u32)wd_inside;
    int r[7] = {nh_hook_c((u32)cnew, hc_wd_new), nh_hook_c((u32)cgen, hc_wd_gen), nh_hook_c((u32)fire, hc_wd_fire),
                nh_hook_c((u32)req, hc_wd_request), nh_hook_c((u32)tr, hc_wd_translate), nh_hook_c((u32)fp, hc_wd_find),
                nh_hook_c((u32)fo, hc_wd_force)};
    wd_ok = 1;
    for (int i = 0; i < 7; i++) if (r[i] < 0) { LOGE("dims: hook %d failed (%d)", i, r[i]); wd_ok = 0; }
    LOG("dims: %s", wd_ok ? "ready" : "off");
}
