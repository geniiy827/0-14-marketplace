/* dims.inc - the wooden dimension (number 2) and its portal.
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
#define WD_ID 2
#define WD_PLANKS 5
static int wd_ok, wd_portal_id = -1;
static u32 *wd_dim_vt, *wd_cs_vt, *wd_pb_vt;
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
static u32 (*wd_get_id)(void *bs, int x, int y, int z);
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

/* one column of the islands: grass, dirt, then planks in stripes of the six woods; logs hang under them */
static int wd_column(u8 *bl, u8 *dt, int x, int z, int wx, int wz, u32 seed) {
    float n = wd_fbm(wx / 56.f, wz / 56.f, seed);
    if (n <= 0.56f) return -1;
    float s = (n - 0.56f) / 0.3f;
    if (s > 1) s = 1;
    int top = 62 + (int)(wd_fbm(wx / 22.f, wz / 22.f, seed + 7) * 16) + (int)(s * 6);
    int depth = 3 + (int)(s * s * 34 * (0.6f + 0.4f * wd_rnd(wx, wz, seed + 3)));
    int bottom = top - depth;
    if (bottom < 4) bottom = 4;
    for (int y = bottom; y <= top; y++) {
        if (y == top) wd_put(bl, dt, x, y, z, 2, 0);
        else if (y >= top - 3) wd_put(bl, dt, x, y, z, 3, 0);
        else wd_put(bl, dt, x, y, z, WD_PLANKS, ((y + (int)(wd_vnoise(wx / 9.f, wz / 9.f, seed + 5) * 4)) / 3) % 6);
    }
    if (s > 0.15f && wd_rnd(wx, wz, seed + 11) < 0.07f) {                 /* roots under the island */
        u32 h = wd_hash(wx, wz, seed + 12);
        int len = 1 + (int)(h % 6);
        for (int k = 1; k <= len && bottom - k > 0; k++) wd_put(bl, dt, x, bottom - k, z, 17, (int)((h >> 8) % 4));
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
    int *cp = wd_chunk_pos(chunk);
    int cx = cp[0], cz = cp[1];
    u8 *bl = wd_raw_blocks(chunk), *dt = wd_raw_data(chunk);
    memset(bl, 0, 0x8000);
    memset(dt, 0, 0x4000);
    signed char tops[16][16];
    for (int x = 0; x < 16; x++)
        for (int z = 0; z < 16; z++) {
            int t = wd_column(bl, dt, x, z, cx * 16 + x, cz * 16 + z, seed);
            tops[x][z] = (signed char)(t > 120 ? -1 : t);
        }
    for (int x = 0; x < 16; x++)
        for (int z = 0; z < 16; z++) {
            int t = tops[x][z], wx = cx * 16 + x, wz = cz * 16 + z;
            if (t < 0) continue;
            if (x >= 2 && x <= 13 && z >= 2 && z <= 13 && wd_rnd(wx, wz, seed + 21) < 0.035f) wd_tree(bl, dt, x, t + 1, z, wd_hash(wx, wz, seed + 22));
            else if (wd_rnd(wx, wz, seed + 31) < 0.18f && wd_at(bl, x, t + 1, z) == 0) wd_put(bl, dt, x, t + 1, z, 31, 1);
        }
    wd_blocklights(chunk);
    wd_heightmap(chunk);
    void *biome = wd_forest ? *wd_forest : 0;
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
    if (wd_ok && (int)w[1] == WD_ID && w[0] && w[2]) {
        char *d = f_new(0xb4);
        memset(d, 0, 0xb4);
        wd_dim_ctor(d, (void *)w[2], WD_ID);
        ((u32 *)d)[0] = (u32)(wd_dim_vt + 2);
        ((u32 *)d)[1] = (u32)(wd_dim_vt + 49);
        *(void **)w[0] = d;
        return w[0];
    }
    return nh_callw(orig, w);
}
static u64 hc_wd_gen(u32 orig, u32 *w) {                     /* Dimension::_createGenerator(out, this, type) */
    char *dim = (char *)w[1];
    if (wd_ok && dim && w[0] && *(int *)(dim + 0x10) == WD_ID) {
        void *lvl = *(void **)(dim + 0x58);
        char *cs = f_new(0x20);
        memset(cs, 0, 0x20);
        wd_cs_ctor(cs, lvl, dim, 16);
        *(u32 *)cs = (u32)(wd_cs_vt + 2);
        *(u32 *)(cs + 0x18) = lvl && wd_seed ? wd_seed(lvl) : 0x5eed;
        *(void **)w[0] = cs;
        return w[0];
    }
    return nh_callw(orig, w);
}

/* ---------------------------------------------------------------- the portal ---- */
static int wd_air(u32 id) { id &= 0xff; return id == 0 || id == 51; }       /* air or fire */
static u32 wd_id(void *bs, int x, int y, int z) { return y < 0 || y > 127 ? 7 : wd_get_id(bs, x, y, z) & 0xff; }
static void wd_set(void *bs, int x, int y, int z, int id, int data, int flags) {
    if (y < 0 || y > 127 || !mf_setblock_pos) return;
    MPos p = {x, y, z};
    MBlockID b = {(unsigned char)id};
    mf_setblock_pos(bs, &p, &b, (unsigned char)data, flags);
}

/* the inside of a planks frame (2..21 wide, 3..21 high) around (x, y, z) along (dx, dz): filled with the portal */
static int wd_try_axis(void *bs, int x, int y, int z, int dx, int dz) {
    int by = y;
    while (by > 0 && y - by < 21 && wd_air(wd_id(bs, x, by - 1, z))) by--;
    if (wd_id(bs, x, by - 1, z) != WD_PLANKS) return 0;
    int lx = x, lz = z, n = 0;
    while (n < 21 && wd_air(wd_id(bs, lx - dx, by, lz - dz))) { lx -= dx; lz -= dz; n++; }
    if (wd_id(bs, lx - dx, by, lz - dz) != WD_PLANKS) return 0;
    int wdt = 0;
    while (wdt < 22 && wd_air(wd_id(bs, lx + dx * wdt, by, lz + dz * wdt)) && wd_id(bs, lx + dx * wdt, by - 1, lz + dz * wdt) == WD_PLANKS) wdt++;
    if (wdt < 2 || wdt > 21 || wd_id(bs, lx + dx * wdt, by, lz + dz * wdt) != WD_PLANKS) return 0;
    int h = 0;
    for (; h < 22; h++) {
        int yy = by + h, air = 1, planks = 1;
        for (int i = 0; i < wdt; i++) {
            u32 id = wd_id(bs, lx + dx * i, yy, lz + dz * i);
            if (!wd_air(id)) air = 0;
            if (id != WD_PLANKS) planks = 0;
        }
        if (planks && h >= 3) break;
        if (!air) return 0;
        if (wd_id(bs, lx - dx, yy, lz - dz) != WD_PLANKS || wd_id(bs, lx + dx * wdt, yy, lz + dz * wdt) != WD_PLANKS) return 0;
    }
    if (h < 3 || h > 21) return 0;
    for (int j = 0; j < h; j++)
        for (int i = 0; i < wdt; i++) wd_set(bs, lx + dx * i, by + j, lz + dz * i, wd_portal_id, dx ? 1 : 2, 2);
    return 1;
}
static u64 hc_wd_fire(u32 orig, u32 *w) {                    /* FireBlock::onPlace(this, BlockSource&, BlockPos const&) */
    if (wd_ok && wd_portal_id > 0 && w[1] && w[2]) {
        int *p = (int *)w[2];
        if (wd_try_axis((void *)w[1], p[0], p[1], p[2], 1, 0) || wd_try_axis((void *)w[1], p[0], p[1], p[2], 0, 1)) return 0;
    }
    return nh_callw(orig, w);
}

static void wd_tick(void *b, void *bs, void *pos, void *rnd) { (void)b; (void)bs; (void)pos; (void)rnd; }
static void wd_neighbor(void *b, void *bs, MPos *pos, MPos *from) {
    (void)b; (void)from;
    int x = pos->x, y = pos->y, z = pos->z;
    int axis = (int)(wd_get_data(bs, x, y, z) & 3), dx = axis == 2 ? 0 : 1, dz = axis == 2 ? 1 : 0;
    #define WD_FRAME(id) ((id) == WD_PLANKS || (int)(id) == wd_portal_id)
    if (WD_FRAME(wd_id(bs, x, y - 1, z)) && WD_FRAME(wd_id(bs, x, y + 1, z)) &&
        WD_FRAME(wd_id(bs, x - dx, y, z - dz)) && WD_FRAME(wd_id(bs, x + dx, y, z + dz))) return;
    #undef WD_FRAME
    wd_set(bs, x, y, z, 0, 0, 3);
}

/* who stood in the wooden portal lately */
static struct { void *e; long t; } wd_marks[32];
static void *wd_traveller;
static void wd_mark(void *e) {
    long now = now_ms();
    int k = 0;
    for (int i = 0; i < 32; i++) {
        if (wd_marks[i].e == e) { k = i; break; }
        if (wd_marks[i].t < wd_marks[k].t) k = i;
    }
    wd_marks[k].e = e; wd_marks[k].t = now;
}
static int wd_marked(void *e) {
    for (int i = 0; i < 32; i++) if (wd_marks[i].e == e && now_ms() - wd_marks[i].t < 1500) { wd_marks[i].e = 0; return 1; }
    return 0;
}
static void wd_inside(void *b, void *bs, void *pos, void *ent) {
    if (ent) wd_mark(ent);
    if (wd_pb_inside) wd_pb_inside(b, bs, pos, ent);
}

static u64 hc_wd_request(u32 orig, u32 *w) {                 /* Level::requestPlayerChangeDimension(Player&, unique_ptr) */
    void *pl = (void *)w[1];
    char *req = w[2] ? *(char **)w[2] : 0;
    if (pl == wd_traveller) wd_traveller = 0;
    if (wd_ok && req && pl && wd_marked(pl)) {
        int from = *(int *)(req + 8);
        *(int *)(req + 0xc) = from == WD_ID ? 0 : WD_ID;
        wd_traveller = pl;
        LOG("wood: player goes %d -> %d", from, *(int *)(req + 0xc));
    }
    return nh_callw(orig, w);
}
static u64 hc_wd_translate(u32 orig, u32 *w) {               /* (out Vec3, this, Vec3 const&, from, to) */
    if ((int)w[3] == WD_ID || (int)w[4] == WD_ID) { memcpy((void *)w[0], (void *)w[2], 12); return w[0]; }
    return nh_callw(orig, w);
}
static u64 hc_wd_find(u32 orig, u32 *w) {                    /* findPortal: not the game's portals for our trip */
    if (wd_traveller) return 0;
    return nh_callw(orig, w);
}

/* arrival: the nearest wooden portal within 16 blocks, or a new one on the ground (on a platform over the void) */
static void wd_arrive(void *forcer, void *ent) {
    void *bs = wd_region(ent);
    if (!bs) return;
    float *pp = (float *)((char *)ent + 8);
    int bx = (int)floorf(pp[0]), bz = (int)floorf(pp[2]), dim = wd_bs_dim(bs);
    int best = 1 << 30;
    MPos at = {0, 0, 0};
    for (int dx = -16; dx <= 16; dx++)
        for (int dz = -16; dz <= 16; dz++)
            for (int y = 1; y < 127; y++) {
                if ((int)wd_id(bs, bx + dx, y, bz + dz) != wd_portal_id) continue;
                int yy = y;
                while (yy > 1 && (int)wd_id(bs, bx + dx, yy - 1, bz + dz) == wd_portal_id) yy--;
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
                if (wd_air(wd_id(bs, x, sy - 1, z)) || wd_id(bs, x, sy - 1, z) == 31) wd_set(bs, x, sy - 1, z, WD_PLANKS, 0, 3);
                for (int y = sy; y <= sy + 3; y++) if (z != bz || x < bx - 1 || x > bx + 2) wd_set(bs, x, y, z, 0, 0, 3);
            }
        for (int x = bx - 1; x <= bx + 2; x++) { wd_set(bs, x, sy - 1, bz, WD_PLANKS, 0, 3); wd_set(bs, x, sy + 3, bz, WD_PLANKS, 0, 3); }
        for (int y = sy; y <= sy + 2; y++) { wd_set(bs, bx - 1, y, bz, WD_PLANKS, 0, 3); wd_set(bs, bx + 2, y, bz, WD_PLANKS, 0, 3); }
        for (int y = sy; y <= sy + 2; y++) for (int x = bx; x <= bx + 1; x++) wd_set(bs, x, y, bz, wd_portal_id, 1, 2);
        at.x = bx; at.y = sy; at.z = bz;
        LOG("wood: portal built at %d %d %d (dimension %d)", bx, sy, bz, dim);
    }
    wd_travel(forcer, ent, &at, dim);
}
static u64 hc_wd_force(u32 orig, u32 *w) {                   /* PortalForcer::force(this, Entity&) */
    if (wd_traveller && (void *)w[1] == wd_traveller) {
        wd_traveller = 0;
        wd_arrive((void *)w[0], (void *)w[1]);
        return 0;
    }
    return nh_callw(orig, w);
}

/* the portal block: after the game's blocks and the mods' (Item::initCreativeItems), in a number no mod gets */
static const u8 wd_pool[] = {29, 33, 34, 250, 251, 252, 253, 254};
static void wd_register(void) {
    if (!wd_ok || wd_portal_id > 0 || !mf_blocks || !mf_items || !mf_texset) return;
    for (int id = 255; id > 1 && wd_portal_id < 0; id--) {
        if (id >= 200 && id <= 242) continue;                         /* ModBlocks.POOL */
        int pooled = 0;
        for (unsigned k = 0; k < sizeof wd_pool; k++) if (wd_pool[k] == id) pooled = 1;
        if (pooled || mf_items[id]) continue;
        wd_portal_id = id;
    }
    if (wd_portal_id < 0) { LOGE("wood: no free block number for the portal"); return; }
    char *b = f_new(0x9c);
    memset(b, 0, 0x9c);
    u32 nm[1], tx[1];
    gstr(nm, "nova_wood_portal");
    gstr(tx, "nova_wood_portal");
    wd_htb_ctor(b, nm, wd_portal_id, tx, wd_material(0x19), 0);
    *(u32 *)b = (u32)(wd_pb_vt + 2);
    wd_set_ticking(b, 1);
    wd_set_solid(b, 0);
    *(int *)(b + 0x38) = 0x800;
    b[0x40] = 1;
    mf_texset(b + 16, tx, 0);
    f_strdtor(nm);
    f_strdtor(tx);
    mf_blocks[wd_portal_id] = b;
    ((void (*)(void *))wd_pb_vt[2 + 94])(b);                         /* Block::init */
    if (mf_light_emission) mf_light_emission[wd_portal_id] = 11;
    LOG("wood: portal block %d", wd_portal_id);
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
        LOGE("wood: symbols missing, the wooden dimension is off");
        return;
    }
    wd_dim_vt = f_new(53 * 4);
    memcpy(wd_dim_vt, nd, 53 * 4);
    wd_dim_vt[2 + 32] = (u32)wd_no_respawn;                         /* mayRespawn: death sends you home */
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
    for (int i = 0; i < 7; i++) if (r[i] < 0) { LOGE("wood: hook %d failed (%d)", i, r[i]); wd_ok = 0; }
    LOG("wood: dimension %d %s", WD_ID, wd_ok ? "ready" : "off");
}
