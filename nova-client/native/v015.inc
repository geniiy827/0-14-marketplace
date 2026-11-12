/* v015.inc - what Minecraft PE 0.15 (Friendly Update) added, switched on by "Version 0.15" in the NOVA tab
 * (nova_version >= 15, files/nova_version). The code is NOVA's own; 0.15.10 (its exported names) is the reference
 * for the rules: ids, data values, facings, the push limit, slime.
 *
 * Pistons (0.15: PistonBlock 33 / sticky 29, PistonArmBlock "pistonArmCollision" 34, MovingBlock 250):
 *   data      bits 0..2 facing (0 down, 1 up, 2 north -z, 3 south +z, 4 west -x, 5 east +x), bit 3 extended;
 *             the arm: facing, bit 3 = sticky
 *   power     the game's redstone (a ConsumerComponent, as RedstoneLampBlock): onRedstoneUpdate extends / retracts
 *   moving    up to 12 blocks in front (slime blocks drag their neighbours), plants and the like break; the cells
 *             the blocks move into hold a moving block (250, invisible) for 2 game ticks (BlockTickingQueue), then
 *             the blocks land; meanwhile they are drawn sliding (v15_render, from LevelRenderer::renderNameTags)
 *   sticky    retracting pulls the block in front of the arm back (with the slime blocks it drags)
 * The slime block (165) is 0.14's own; a piston's load drags what sticks to it.
 * Blocks with block entities (chests, furnaces...) do not move yet. */

#define V15_PISTON 33
#define V15_STICKY 29
#define V15_ARM 34
#define V15_MOVING 250
#define V15_SLIME 165
#define V15_PUSH_LIMIT 12
#define V15_ANIM_MS 100                                   /* 2 game ticks */

static const int v15_dx[6] = {0, 0, 0, 0, -1, 1}, v15_dy[6] = {-1, 1, 0, 0, 0, 0}, v15_dz[6] = {0, 0, -1, 1, 0, 0};
static int v15_on;                                        /* blocks registered */
static volatile int v15_busy;                             /* our own block changes: onRemove of pistons ignores them */

/* ---------------------------------------------------------------------------------------------------- models ---- */
static TexSet v15_tx[7];                                  /* side, top_normal, top_sticky, bottom, inner, (unused), rod */
static const char *v15_tx_names[7] = {"piston_side", "piston_top_normal", "piston_top_sticky", "piston_bottom", "piston_inner",
                                      "slime", "piston_rod"};
static float v15_cubes[3][2][6][3 * MB_CUBE];             /* [base normal/sticky/arm][ext or arm sticky][facing] */
static ModBlock *v15_mb[5];

static void v15_uv(float *dst, int t, float fu0, float fv0, float fu1, float fv1) {
    TexSet *s = &v15_tx[t];
    float du = s->b[2] - s->b[0], dv = s->b[3] - s->b[1];
    dst[0] = s->b[0] + du * fu0; dst[1] = s->b[1] + dv * fv0; dst[2] = s->b[0] + du * fu1; dst[3] = s->b[1] + dv * fv1;
}
static void v15_box(float *c, float x0, float y0, float z0, float x1, float y1, float z1, int facing) {
    c[0] = x0; c[1] = y0; c[2] = z0; c[3] = x1; c[4] = y1; c[5] = z1;
    for (int f = 0; f < 6; f++) c[6 + f * 4] = -1;       /* no face unless set */
    static const float rot[6][3] = {{180, 0, 0}, {0, 0, 0}, {-90, 0, 0}, {90, 0, 0}, {0, 0, 90}, {0, 0, -90}};
    c[30] = rot[facing][0]; c[31] = rot[facing][1]; c[32] = rot[facing][2];
    c[33] = 0.5f; c[34] = 0.5f; c[35] = 0.5f;
}
/* the models are made pointing up and turned to their facing (the textures turn with them) */
static void v15_build_models(void) {
    for (int kind = 0; kind < 3; kind++)
        for (int st = 0; st < 2; st++)
            for (int f = 0; f < 6; f++) {
                float *c = v15_cubes[kind][st][f];
                memset(c, 0, sizeof v15_cubes[0][0][0]);
                if (kind < 2 && !st) {                    /* retracted base: a full cube */
                    v15_box(c, 0, 0, 0, 1, 1, 1, f);
                    v15_uv(c + 6 + 0 * 4, 3, 0, 0, 1, 1);
                    v15_uv(c + 6 + 1 * 4, kind ? 2 : 1, 0, 0, 1, 1);
                    for (int s = 2; s < 6; s++) v15_uv(c + 6 + s * 4, 0, 0, 0, 1, 1);
                } else if (kind < 2) {                    /* extended base: 12 pixels, the inside shows */
                    v15_box(c, 0, 0, 0, 1, 0.75f, 1, f);
                    v15_uv(c + 6 + 0 * 4, 3, 0, 0, 1, 1);
                    v15_uv(c + 6 + 1 * 4, 4, 0, 0, 1, 1);
                    for (int s = 2; s < 6; s++) v15_uv(c + 6 + s * 4, 0, 0, 0.25f, 1, 1);
                } else {                                  /* the arm: head plate and rod */
                    v15_box(c, 0, 0.75f, 0, 1, 1, 1, f);
                    v15_uv(c + 6 + 0 * 4, 1, 0, 0, 1, 1);
                    v15_uv(c + 6 + 1 * 4, st ? 2 : 1, 0, 0, 1, 1);
                    for (int s = 2; s < 6; s++) v15_uv(c + 6 + s * 4, 0, 0, 0, 1, 0.25f);
                    float *r = c + MB_CUBE;
                    v15_box(r, 0.375f, -0.25f, 0.375f, 0.625f, 0.75f, 0.625f, f);
                    for (int s = 2; s < 6; s++) v15_uv(r + 6 + s * 4, 6, 0.75f, 0, 1, 1);
                }
            }
}
/* mcmod.inc asks which cubes to draw for a block and its data */
static void v15_model(ModBlock *m, int data) {
    if (!m || m->special < 10) return;
    int f = data & 7;
    if (f > 5) f = 1;
    if (m->special == 10 || m->special == 11) { m->cubes = v15_cubes[m->special - 10][(data >> 3) & 1][f]; m->ncubes = 1; }
    else if (m->special == 12) { m->cubes = v15_cubes[2][(data >> 3) & 1][f]; m->ncubes = 2; }
}

/* ------------------------------------------------------------------------------------------------- world ---- */
static int v15_id(void *bs, int x, int y, int z) { return mm_blockid(bs, x, y, z); }
static int v15_data(void *bs, int x, int y, int z) { return mf_getdata && y >= 0 && y <= 127 ? mf_getdata(bs, x, y, z) : 0; }
static void (*v15_base_remove)(void *b, void *bs, MPos *pos);
static void v15_set(void *bs, int x, int y, int z, int id, int data) {
    MPos p = {x, y, z};
    MBlockID b = {(unsigned char)id};
    int old = v15_id(bs, x, y, z);
    if ((old == V15_PISTON || old == V15_STICKY) && old != id && v15_base_remove) v15_base_remove(mf_blocks[old], bs, &p);
    char *cs = mf_dim_circuit && mf_bs_dim ? mf_dim_circuit(mf_bs_dim(bs)) : 0;
    char keep = cs ? cs[0] : 0;
    if (cs && old == id) cs[0] = 1;              /* as RedstoneLampBlock: no circuit re-entry while only the data changes */
    v15_busy++;
    mf_setblock_pos(bs, &p, &b, (unsigned char)data, 3);
    v15_busy--;
    if (cs) cs[0] = keep;
}
static void *(*v15_get_be)(void *bs, MPos *pos);
static void *(*v15_tick_queue)(void *bs, MPos *pos);
static void (*v15_tick_add)(void *q, void *bs, MPos *pos, const MBlockID *id, int delay);
static void *(*v15_get_entities)(void *bs, void *except, MAABB *box);
static void (*v15_level_sound)(void *level, MVec3 *pos, void *name, float vol, float pitch);   /* Level::playSound(Vec3, name, ..) */

/* what a piston does to a block: 0 moves, 1 breaks (drops), 2 stops it, 3 nothing there (air, liquids) */
static int v15_react(void *bs, int x, int y, int z) {
    if (y < 0 || y > 127) return 2;
    int id = v15_id(bs, x, y, z);
    if (id == 0 || (id >= 8 && id <= 11) || id == 51) return 3;           /* air, water, lava, fire */
    switch (id) {
    case 7: case 49: case 52: case 116: case 119: case 120: case 130: case 247: case V15_ARM: case V15_MOVING:
        return 2;                                                           /* bedrock, obsidian, spawner, ... */
    case V15_PISTON: case V15_STICKY:
        return (v15_data(bs, x, y, z) & 8) ? 2 : 0;                          /* an extended piston stays */
    case 6: case 30: case 31: case 32: case 37: case 38: case 39: case 40: case 50: case 55: case 59: case 63: case 64:
    case 65: case 68: case 69: case 70: case 71: case 72: case 75: case 76: case 77: case 78: case 81: case 83:
    case 92: case 93: case 94: case 104: case 105: case 106: case 111: case 115: case 122: case 127: case 131:
    case 132: case 140: case 141: case 142: case 143: case 144: case 147: case 148: case 149: case 150: case 171:
    case 175: case 199: case 244:
        return 1;                                                           /* plants, torches, wire, doors, ... */
    }
    MPos p = {x, y, z};
    if (v15_get_be && v15_get_be(bs, &p)) return 2;                         /* block entities: not yet */
    return 0;
}

/* the blocks a piston moves (Java's structure helper): the line in front, slime blocks drag their neighbours */
typedef struct { int n, nb; MPos mv[V15_PUSH_LIMIT + 1]; MPos br[64]; } V15Plan;
static int v15_has(const MPos *a, int n, int x, int y, int z) {
    for (int i = 0; i < n; i++) if (a[i].x == x && a[i].y == y && a[i].z == z) return 1;
    return 0;
}
static int v15_add_line(V15Plan *pl, void *bs, int x, int y, int z, int dir, int px, int py, int pz);
static int v15_add_branch(V15Plan *pl, void *bs, int x, int y, int z, int dir, int px, int py, int pz) {
    /* a slime block in the load: its neighbours across the push come too */
    for (int f = 0; f < 6; f++) {
        if (f == dir || f == (dir ^ 1)) continue;
        int nx = x + v15_dx[f], ny = y + v15_dy[f], nz = z + v15_dz[f];
        if (v15_react(bs, nx, ny, nz) == 0 && !v15_has(pl->mv, pl->n, nx, ny, nz)) {
            if (!v15_add_line(pl, bs, nx, ny, nz, dir, px, py, pz)) return 0;
        }
    }
    return 1;
}
static int v15_add_line(V15Plan *pl, void *bs, int x, int y, int z, int dir, int px, int py, int pz) {
    if (x == px && y == py && z == pz) return 1;                           /* the piston itself */
    int r = v15_react(bs, x, y, z);
    if (r == 3 || v15_has(pl->mv, pl->n, x, y, z)) return 1;
    if (r == 2) return 0;
    if (r == 1) { if (pl->nb < 64 && !v15_has(pl->br, pl->nb, x, y, z)) pl->br[pl->nb++] = (MPos){x, y, z}; return 1; }
    /* back along the push: slime blocks pull what sticks behind them */
    int bx = x, by = y, bz = z, back = 0;
    while (v15_id(bs, bx, by, bz) == V15_SLIME) {
        int qx = bx - v15_dx[dir], qy = by - v15_dy[dir], qz = bz - v15_dz[dir];
        if ((qx == px && qy == py && qz == pz) || v15_react(bs, qx, qy, qz) != 0 || v15_has(pl->mv, pl->n, qx, qy, qz)) break;
        bx = qx; by = qy; bz = qz; back++;
        if (pl->n + back > V15_PUSH_LIMIT) return 0;
    }
    for (int k = back; k > 0; k--) {
        int qx = x - v15_dx[dir] * k, qy = y - v15_dy[dir] * k, qz = z - v15_dz[dir] * k;
        if (pl->n >= V15_PUSH_LIMIT) return 0;
        pl->mv[pl->n++] = (MPos){qx, qy, qz};
    }
    /* forward: everything in the way */
    for (;;) {
        if (pl->n >= V15_PUSH_LIMIT) return 0;
        pl->mv[pl->n++] = (MPos){x, y, z};
        x += v15_dx[dir]; y += v15_dy[dir]; z += v15_dz[dir];
        if (x == px && y == py && z == pz) return 0;                       /* would push into its own piston */
        r = v15_react(bs, x, y, z);
        if (r == 3) break;
        if (r == 2) return 0;
        if (r == 1) { if (pl->nb < 64 && !v15_has(pl->br, pl->nb, x, y, z)) pl->br[pl->nb++] = (MPos){x, y, z}; break; }
        if (v15_has(pl->mv, pl->n, x, y, z)) break;
    }
    /* slime blocks in the load drag their side neighbours */
    for (int i = 0; i < pl->n; i++) {
        MPos q = pl->mv[i];
        if (v15_id(bs, q.x, q.y, q.z) == V15_SLIME && !v15_add_branch(pl, bs, q.x, q.y, q.z, dir, px, py, pz)) return 0;
    }
    return 1;
}

/* a running move: its blocks slide for V15_ANIM_MS, then land (the tick of the moving block at key) */
typedef struct { unsigned char id, data; MPos from, to; } V15Piece;
typedef struct { int used; MPos key; long t0; int n; V15Piece p[V15_PUSH_LIMIT + 2]; } V15Move;
#define V15_MOVES 24
static V15Move v15_moves[V15_MOVES];

static V15Move *v15_new_move(MPos key) {
    V15Move *best = 0;
    for (int i = 0; i < V15_MOVES; i++) if (!v15_moves[i].used) { best = &v15_moves[i]; break; }
    if (!best)                                          /* all busy: reuse the oldest one that landed */
        for (int i = 0; i < V15_MOVES; i++)
            if (v15_moves[i].key.y == -1000 && (!best || v15_moves[i].t0 < best->t0)) best = &v15_moves[i];
    if (!best) return 0;
    memset(best, 0, sizeof *best);
    best->used = 1; best->key = key; best->t0 = now_ms();
    return best;
}
static void v15_schedule(void *bs, MPos *pos) {
    if (!v15_tick_queue || !v15_tick_add) return;
    void *q = v15_tick_queue(bs, pos);
    MBlockID id = {V15_MOVING};
    if (q) v15_tick_add(q, bs, pos, &id, 2);
}
static void v15_sound(void *bs, MPos *p, const char *name, float pitch) {
    void *lvl = mf_getlevel ? mf_getlevel(bs) : 0;
    if (!lvl || !v15_level_sound) return;
    u32 s[1];
    gstr(s, name);
    MVec3 at = {p->x + 0.5f, p->y + 0.5f, p->z + 0.5f};
    v15_level_sound(lvl, &at, s, 0.5f, pitch);
    f_strdtor(s);
}

/* entities in the cells the load moves into go along */
static void v15_push_entities(void *bs, V15Move *m, int dir) {
    if (!v15_get_entities) return;
    for (int i = 0; i < m->n; i++) {
        MPos t = m->p[i].to;
        MAABB box = {{t.x + 0.f, t.y + 0.f, t.z + 0.f, t.x + 1.f, t.y + 1.f, t.z + 1.f}, 0, {0, 0, 0}};
        u32 *v = v15_get_entities(bs, 0, &box);
        if (!v) continue;
        int cnt = (int)((v[1] - v[0]) / 4);
        for (int k = 0; k < cnt && k < 16; k++) {
            char *e = ((char **)v[0])[k];
            if (!e) continue;
            MVec3 d = {(float)v15_dx[dir], (float)v15_dy[dir], (float)v15_dz[dir]};
            ((void (*)(void *, MVec3 *))(*(u32 **)e)[11])(e, &d);           /* Entity::move */
        }
    }
}

static void v15_extend(void *bs, MPos *pos, int data, int sticky) {
    int dir = data & 7;
    if (dir > 5) return;
    int fx = pos->x + v15_dx[dir], fy = pos->y + v15_dy[dir], fz = pos->z + v15_dz[dir];
    if (fy < 0 || fy > 127) return;
    V15Plan pl;
    memset(&pl, 0, sizeof pl);
    if (!v15_add_line(&pl, bs, fx, fy, fz, dir, pos->x, pos->y, pos->z)) return;
    /* the cells the load moves into must be free (or part of the load) */
    for (int i = 0; i < pl.n; i++) {
        int tx = pl.mv[i].x + v15_dx[dir], ty = pl.mv[i].y + v15_dy[dir], tz = pl.mv[i].z + v15_dz[dir];
        if (ty < 0 || ty > 127) return;
    }
    MPos key = {fx, fy, fz};
    V15Move *m = v15_new_move(key);
    if (!m) return;
    /* what breaks drops */
    for (int i = 0; i < pl.nb; i++) {
        MPos b = pl.br[i];
        int id = v15_id(bs, b.x, b.y, b.z);
        void *blk = id > 0 && id < 256 ? mf_blocks[id] : 0;
        if (blk) ((void (*)(void *, void *, MPos *, int, float, int))(*(u32 **)blk)[52])(blk, bs, &b, v15_data(bs, b.x, b.y, b.z), 1.f, 0);
        v15_set(bs, b.x, b.y, b.z, 0, 0);
    }
    /* take the load out (front first is not needed: all go to air, then the moving blocks go in) */
    for (int i = 0; i < pl.n; i++) {
        MPos s = pl.mv[i];
        V15Piece *p = &m->p[m->n++];
        p->id = (unsigned char)v15_id(bs, s.x, s.y, s.z);
        p->data = (unsigned char)v15_data(bs, s.x, s.y, s.z);
        p->from = s;
        p->to = (MPos){s.x + v15_dx[dir], s.y + v15_dy[dir], s.z + v15_dz[dir]};
    }
    for (int i = 0; i < m->n; i++) v15_set(bs, m->p[i].from.x, m->p[i].from.y, m->p[i].from.z, 0, 0);
    /* the arm slides out of the piston */
    V15Piece *arm = &m->p[m->n++];
    arm->id = V15_ARM; arm->data = (unsigned char)(dir | (sticky ? 8 : 0));
    arm->from = *pos; arm->to = key;
    for (int i = 0; i < m->n; i++) v15_set(bs, m->p[i].to.x, m->p[i].to.y, m->p[i].to.z, V15_MOVING, 0);
    v15_set(bs, pos->x, pos->y, pos->z, sticky ? V15_STICKY : V15_PISTON, dir | 8);
    v15_push_entities(bs, m, dir);
    v15_schedule(bs, &key);
    v15_sound(bs, pos, "tile.piston.out", 0.75f);
}

static void v15_retract(void *bs, MPos *pos, int data, int sticky) {
    int dir = data & 7;
    if (dir > 5) return;
    int fx = pos->x + v15_dx[dir], fy = pos->y + v15_dy[dir], fz = pos->z + v15_dz[dir];
    MPos key = {fx, fy, fz};
    V15Move *m = v15_new_move(key);
    if (!m) return;
    V15Piece *arm = &m->p[m->n++];
    arm->id = V15_ARM; arm->data = (unsigned char)(dir | (sticky ? 8 : 0));
    arm->from = key; arm->to = *pos;
    if (v15_id(bs, fx, fy, fz) == V15_ARM) v15_set(bs, fx, fy, fz, 0, 0);
    int pulled = 0;
    if (sticky) {                                       /* pull the block in front of the arm back */
        int ox = fx + v15_dx[dir], oy = fy + v15_dy[dir], oz = fz + v15_dz[dir];
        if (v15_react(bs, ox, oy, oz) == 0) {
            V15Plan pl;
            memset(&pl, 0, sizeof pl);
            int back = dir ^ 1;                         /* pulled: a push towards the piston */
            if (v15_add_line(&pl, bs, ox, oy, oz, back, pos->x, pos->y, pos->z) && pl.nb == 0) {
                for (int i = 0; i < pl.n && m->n < V15_PUSH_LIMIT + 1; i++) {
                    MPos s = pl.mv[i];
                    V15Piece *p = &m->p[m->n++];
                    p->id = (unsigned char)v15_id(bs, s.x, s.y, s.z);
                    p->data = (unsigned char)v15_data(bs, s.x, s.y, s.z);
                    p->from = s;
                    p->to = (MPos){s.x + v15_dx[back], s.y + v15_dy[back], s.z + v15_dz[back]};
                }
                for (int i = 1; i < m->n; i++) v15_set(bs, m->p[i].from.x, m->p[i].from.y, m->p[i].from.z, 0, 0);
                for (int i = 1; i < m->n; i++) v15_set(bs, m->p[i].to.x, m->p[i].to.y, m->p[i].to.z, V15_MOVING, 0);
                pulled = m->n > 1;
            }
        }
    }
    v15_set(bs, pos->x, pos->y, pos->z, sticky ? V15_STICKY : V15_PISTON, dir);
    if (pulled) v15_schedule(bs, &key);
    else m->key.y = -1000;                              /* nothing lands: the move is only drawn */
    v15_sound(bs, pos, "tile.piston.in", 0.65f);
}

/* the moving block at key ticks: its move lands */
static void v15_land(void *bs, MPos *pos) {
    for (int i = 0; i < V15_MOVES; i++) {
        V15Move *m = &v15_moves[i];
        if (!m->used || m->key.x != pos->x || m->key.y != pos->y || m->key.z != pos->z) continue;
        for (int k = 0; k < m->n; k++) {
            V15Piece *p = &m->p[k];
            if (v15_id(bs, p->to.x, p->to.y, p->to.z) == V15_MOVING) v15_set(bs, p->to.x, p->to.y, p->to.z, p->id, p->data);
        }
        m->key.y = -1000;                                /* the drawing ends by itself */
        return;
    }
    /* a moving block nobody waits for (the game was closed mid-move): it goes */
    if (v15_id(bs, pos->x, pos->y, pos->z) == V15_MOVING) v15_set(bs, pos->x, pos->y, pos->z, 0, 0);
}

/* ------------------------------------------------------------------------------------------------ blocks ---- */
static void v15_piston_power(void *b, void *bs, MPos *pos, int strength, int first) {
    if (!rs_server(bs)) return;
    int id = *((unsigned char *)b + 4), data = v15_data(bs, pos->x, pos->y, pos->z);
    int sticky = id == V15_STICKY;
    if (strength > 0 && !(data & 8)) v15_extend(bs, pos, data, sticky);
    else if (strength <= 0 && (data & 8)) v15_retract(bs, pos, data, sticky);
}
static int v15_piston_place(void *b, void *mob, MPos *pos, int face, void *click, int item) {
    float *p = mob ? (float *)((char *)mob + 8) : 0;
    if (!p) return 1;
    int ex = (int)floorf(p[0]), ez = (int)floorf(p[2]);
    int ax = ex - pos->x, az = ez - pos->z;
    if (ax > -2 && ax < 2 && az > -2 && az < 2) {
        float eye = p[1];                                          /* the player's position is at the eyes */
        if (eye - pos->y > 2.f) return 1;
        if (pos->y - eye > 0.f) return 0;
    }
    float yaw = *(float *)((char *)mob + 60);
    static const int h[4] = {2, 5, 3, 4};
    return h[(int)floorf(yaw * 4.f / 360.f + 0.5f) & 3];
}
static int v15_zero_aux(void *b, unsigned char data) { return 0; }
static int v15_no_drop(void *b, void *rnd, int data, int fortune) { return 0; }
/* breaking one half of an extended piston takes the other */
static void v15_piston_removed(void *b, void *bs, MPos *pos) {
    if (v15_busy) return;                                           /* our own change: v15_set keeps the circuit right */
    v15_base_remove(b, bs, pos);                                    /* circuit component */
    if (!rs_server(bs)) return;
    for (int f = 0; f < 6; f++) {
        int x = pos->x + v15_dx[f], y = pos->y + v15_dy[f], z = pos->z + v15_dz[f];
        if (v15_id(bs, x, y, z) == V15_ARM && (v15_data(bs, x, y, z) & 7) == f) v15_set(bs, x, y, z, 0, 0);
    }
}
static void v15_arm_removed(void *b, void *bs, MPos *pos) {
    if (v15_busy || !rs_server(bs)) return;
    for (int f = 0; f < 6; f++) {
        int x = pos->x - v15_dx[f], y = pos->y - v15_dy[f], z = pos->z - v15_dz[f];
        int id = v15_id(bs, x, y, z), d = v15_data(bs, x, y, z);
        if ((id == V15_PISTON || id == V15_STICKY) && (d & 8) && (d & 7) == f) {
            MPos bp = {x, y, z};
            void *blk = mf_blocks[id];
            ((void (*)(void *, void *, MPos *, int, float, int))(*(u32 **)blk)[52])(blk, bs, &bp, 0, 1.f, 0);   /* drops the piston */
            v15_set(bs, x, y, z, 0, 0);
        }
    }
}
static void v15_moving_tick(void *b, void *bs, MPos *pos, void *rnd) { if (rs_server(bs)) v15_land(bs, pos); }

/* shapes: extended base 12 pixels deep, the arm its plate and rod, the moving block nothing */
static void v15_box_world(MAABB *a, MPos *pos, float x0, float y0, float z0, float x1, float y1, float z1, int f) {
    /* a box of the model pointing up, turned to facing f */
    float lo[3] = {x0, y0, z0}, hi[3] = {x1, y1, z1}, o[2][3];
    for (int k = 0; k < 2; k++) {
        float *v = k ? hi : lo, x = v[0] - 0.5f, y = v[1] - 0.5f, z = v[2] - 0.5f, r[3];
        switch (f) {
        case 0: r[0] = x; r[1] = -y; r[2] = -z; break;
        case 2: r[0] = x; r[1] = z; r[2] = -y; break;
        case 3: r[0] = x; r[1] = -z; r[2] = y; break;
        case 4: r[0] = -y; r[1] = x; r[2] = z; break;
        case 5: r[0] = y; r[1] = -x; r[2] = z; break;
        default: r[0] = x; r[1] = y; r[2] = z;
        }
        o[k][0] = r[0] + 0.5f; o[k][1] = r[1] + 0.5f; o[k][2] = r[2] + 0.5f;
    }
    for (int k = 0; k < 3; k++) {
        float a0 = o[0][k], a1 = o[1][k];
        a->v[k] = (a0 < a1 ? a0 : a1) + (&pos->x)[k];
        a->v[k + 3] = (a0 < a1 ? a1 : a0) + (&pos->x)[k];
    }
    a->empty = 0;
}
static void v15_shapes(void *b, void *bs, MPos *pos, void *clip, void *vec, void *ent) {
    int id = *((unsigned char *)b + 4);
    if (!mf_add_aabb) return;
    int d = v15_data(bs, pos->x, pos->y, pos->z), f = d & 7;
    MAABB a;
    if (id == V15_MOVING) { v15_box_world(&a, pos, 0, 0, 0, 1, 1, 1, 1); mf_add_aabb(b, &a, clip, vec); return; }
    if (id == V15_ARM) {
        v15_box_world(&a, pos, 0, 0.75f, 0, 1, 1, 1, f); mf_add_aabb(b, &a, clip, vec);
        v15_box_world(&a, pos, 0.375f, 0, 0.375f, 0.625f, 0.75f, 0.625f, f); mf_add_aabb(b, &a, clip, vec);
        return;
    }
    if (d & 8) v15_box_world(&a, pos, 0, 0, 0, 1, 0.75f, 1, f);
    else v15_box_world(&a, pos, 0, 0, 0, 1, 1, 1, 1);
    mf_add_aabb(b, &a, clip, vec);
}
static MAABB *v15_aabb(void *b, void *bs, MPos *pos, MAABB *out, int x, int y, int z) {
    int id = *((unsigned char *)b + 4);
    int d = v15_data(bs, pos->x, pos->y, pos->z), f = d & 7;
    if (id == V15_MOVING) { v15_box_world(out, pos, 0, 0, 0, 0, 0, 0, 1); out->empty = 1; return out; }
    if (id == V15_ARM) v15_box_world(out, pos, 0, 0.75f, 0, 1, 1, 1, f);
    else if ((id == V15_PISTON || id == V15_STICKY) && (d & 8)) v15_box_world(out, pos, 0, 0, 0, 1, 0.75f, 1, f);
    else v15_box_world(out, pos, 0, 0, 0, 1, 1, 1, 1);
    return out;
}

/* ------------------------------------------------------------------------------------------- registration ---- */
static u32 *v15_vt[5];
static void *v15_make(int idx, int id, const char *name, int special, int matfrom, float hard, int item) {
    if (mblock_count >= MB_MAX) return 0;
    ModBlock *m = &mblocks[mblock_count++];
    memset(m, 0, sizeof *m);
    m->id = id; m->kind = 1; m->special = special; m->hardness = hard; m->resistance = hard * 5;
    for (int i = 0; i < 64 && name[i]; i++) m->name[i] = name[i];
    for (int f = 0; f < 6; f++) m->tex[f] = v15_tx[0];             /* particles */
    m->sel[3] = m->sel[4] = m->sel[5] = 1;
    char *b = f_new(0x98);
    memset(b, 0, 0x98);
    u32 nm[1];
    gstr(nm, name);
    void *from = mf_blocks[matfrom] ? mf_blocks[matfrom] : mf_blocks[1];
    mf_block_ctor(b, nm, id, *(void **)((char *)from + 84));
    *(u32 **)b = v15_vt[idx] + 2;
    *(int *)(b + 48) = 4;
    *(int *)(b + 52) = 0;
    *(float *)(b + 112) = hard;
    *(float *)(b + 116) = hard * 5;
    if (mf_block_setsolid) mf_block_setsolid(b, 0);
    TexSet *own = (TexSet *)(b + 16);
    *own = m->tex[0];
    mblock_of[id] = m;
    m->block = b;
    mf_blocks[id] = b;
    ((void (*)(void *))(*(u32 **)b)[94])(b);                       /* Block::init */
    if (mf_block_setcat) mf_block_setcat(b, 3);
    if (item) {
        char *it = f_new(64);
        memset(it, 0, 64);
        mf_blockitem_ctor(it, nm, id - 256);
        mf_items[id] = it;
        if (mf_item_setcat) mf_item_setcat(it, 3);
    }
    f_strdtor(nm);
    v15_mb[idx] = m;
    v15_model(m, 1);
    return b;
}
static void v15_creative(void) {                     /* the end of the creative list: Tools, after the redstone things */
    if (!v15_on || !mf_add_creative) return;
    mf_add_creative(V15_PISTON, 0);
    mf_add_creative(V15_STICKY, 0);
}
static void v15_register(void) {
    if (v15_on || nova_version < 15) return;
    if (!mf_blocks || !mf_items || !mf_block_ctor || !mf_blockitem_ctor || !mf_texset || !mf_setblock_pos || !rs_lamp_vt[0]) {
        LOGE("v015: symbols missing"); return;
    }
    /* 0.14 already has the slime block (165): pistons use it as it is; the four new blocks must be free */
    int ids[4] = {V15_PISTON, V15_STICKY, V15_ARM, V15_MOVING};
    for (int i = 0; i < 4; i++)                      /* 0.14 puts unnamed placeholders in free numbers: an item = a real block */
        if (mf_items[ids[i]]) { LOGE("v015: block %d is taken, pistons off", ids[i]); return; }
    for (int t = 0; t < 7; t++) {
        u32 s[1];
        gstr(s, v15_tx_names[t]);
        mf_texset(&v15_tx[t], s, 0);
        f_strdtor(s);
    }
    v15_build_models();
    u32 *base = dlsym(dlopen("libminecraftpe.so", 0x4 | 0x2), "_ZTV5Block");
    if (!base) return;
    for (int i = 0; i < 5; i++) {
        u32 *v = f_new(VT_BLOCK_WORDS * 4);
        for (int k = 0; k < VT_BLOCK_WORDS; k++) v[k] = base[k];
        v[2 + 76] = (u32)mb_tex_a; v[2 + 77] = (u32)mb_tex_ai; v[2 + 78] = (u32)mb_tex_world; v[2 + 7] = (u32)mb_tex_ai;
        v[2 + 107] = (u32)v15_zero_aux;
        v15_vt[i] = v;
    }
    v15_base_remove = (void *)base[2 + 28];
    for (int i = 0; i < 2; i++) {                                   /* pistons */
        u32 *v = v15_vt[i];
        v[2 + 27] = (u32)rs_lamp_vt[0]; v[2 + 33] = (u32)rs_lamp_vt[1];   /* a ConsumerComponent, as a lamp */
        v[2 + 32] = (u32)v15_piston_power;
        v[2 + 57] = (u32)v15_piston_place;
        v[2 + 28] = (u32)v15_piston_removed;
        v[2 + 8] = (u32)v15_shapes; v[2 + 10] = (u32)v15_shapes; v[2 + 9] = (u32)v15_aabb;
    }
    v15_vt[2][2 + 48] = (u32)v15_no_drop;                           /* the arm */
    v15_vt[2][2 + 28] = (u32)v15_arm_removed;
    v15_vt[2][2 + 8] = (u32)v15_shapes; v15_vt[2][2 + 10] = (u32)v15_shapes; v15_vt[2][2 + 9] = (u32)v15_aabb;
    v15_vt[4][2 + 48] = (u32)v15_no_drop;                           /* moving block */
    v15_vt[4][2 + 2] = (u32)v15_moving_tick;
    v15_vt[4][2 + 8] = (u32)v15_shapes; v15_vt[4][2 + 10] = (u32)v15_shapes; v15_vt[4][2 + 9] = (u32)v15_aabb;
    v15_make(0, V15_PISTON, "piston", 10, 1, 0.5f, 1);
    v15_make(1, V15_STICKY, "sticky_piston", 11, 1, 0.5f, 1);
    v15_make(2, V15_ARM, "pistonArmCollision", 12, 1, 0.5f, 0);
    v15_make(4, V15_MOVING, "movingBlock", 13, 1, -1.f, 0);
    v15_on = 1;
    /* recipes (0.15): piston, sticky piston, slime block and back to slime balls */
    static const struct { int out, n; const char *s; } rec[2] = {
        {V15_PISTON, 1, "TTT|CIC|CRC|T:5:32767|C:4:0|I:265:0|R:331:0"},
        {V15_STICKY, 1, "S|P||S:341:0|P:33:0"}};
    for (int i = 0; i < 2; i++) {
        double a[3] = {rec[i].out, rec[i].n, 0};
        char s[96];
        int k = 0;
        while (rec[i].s[k] && k < 95) { s[k] = rec[i].s[k]; k++; }
        s[k] = 0;
        rc_op(OP_RECIPE, a, 3, s);
    }
    LOG("v015: pistons and sticky pistons");
}

/* ----------------------------------------------------------------------------------------------- drawing ---- */
static void *v15_fbr;                                     /* the game's FallingBlockRenderer: tessellator, material, atlas */
static void *(*v15_erd_instance)(void);
static void *(*v15_mesh_for)(void *bt, unsigned char *fb);
static void (*v15_mesh_render)(void *mesh, void *mat, void *tex, int a, int b);
static void (*v15_ms_push)(void *ref, void *stack);
static void (*v15_ms_ref_dtor)(void *ref);
static void (*v15_mat_translate)(void *m, MVec3 *v);
static void (*v15_shader_params)(void *r, void *bs, MPos *pos, float a, const float *v2);
static void *v15_ms_world;
static const float *v15_vec2_one;
static u32 v15_fbr_vt;
static void *(*v15_ent_region)(void *e);

static void v15_render(void *client, float *cam, float a) {
    if (!v15_on) return;
    long now = now_ms();
    int any = 0;
    for (int i = 0; i < V15_MOVES; i++) if (v15_moves[i].used) {
        /* a move is forgotten only once it landed: its blocks live in it until then */
        if (v15_moves[i].key.y == -1000 && now - v15_moves[i].t0 > V15_ANIM_MS + 400) { v15_moves[i].used = 0; continue; }
        any = 1;
    }
    if (!any || !v15_erd_instance || !v15_mesh_for || !v15_mesh_render || !v15_ms_push || !v15_mat_translate || !v15_ms_world) return;
    if (!v15_fbr) {
        char *d = v15_erd_instance();
        void *r = d ? *(void **)(d + 0xb4) : 0;
        if (!r || *(u32 *)r != v15_fbr_vt + 8) return;
        v15_fbr = r;
    }
    char *pl = *(char **)((char *)client + 0x120);
    void *bs = pl && v15_ent_region ? v15_ent_region(pl) : 0;
    char *r = v15_fbr;
    void *bt = *(void **)(r + 0x8c);
    for (int i = 0; i < V15_MOVES; i++) {
        V15Move *m = &v15_moves[i];
        if (!m->used) continue;
        float t = (now - m->t0) / (float)V15_ANIM_MS;
        if (t > 1.f) continue;                            /* landed: the world shows the blocks */
        for (int k = 0; k < m->n; k++) {
            V15Piece *p = &m->p[k];
            float x = p->from.x + (p->to.x - p->from.x) * t + 0.5f, y = p->from.y + (p->to.y - p->from.y) * t + 0.5f,
                  z = p->from.z + (p->to.z - p->from.z) * t + 0.5f;
            if (bs && v15_shader_params) v15_shader_params(r, bs, &p->to, a, v15_vec2_one);
            u32 ref[2] = {0, 0};
            v15_ms_push(ref, v15_ms_world);
            *((char *)ref[0] + 0xc) = 1;
            MVec3 v = {x - cam[0], y - cam[1], z - cam[2]};
            v15_mat_translate((void *)ref[1], &v);
            unsigned char fb[2] = {p->id, p->data};
            void *mesh = v15_mesh_for(bt, fb);
            if (mesh) v15_mesh_render(mesh, r + 0x90, r + 0x48, 0, 0);
            v15_ms_ref_dtor(ref);
        }
    }
}

static void v015_init(void *mc) {
    v15_get_be = dlsym(mc, "_ZN11BlockSource14getBlockEntityERK8BlockPos");
    v15_tick_queue = dlsym(mc, "_ZN11BlockSource12getTickQueueERK8BlockPos");
    v15_tick_add = dlsym(mc, "_ZN17BlockTickingQueue3addER11BlockSourceRK8BlockPos7BlockIDi");
    v15_get_entities = dlsym(mc, "_ZN11BlockSource11getEntitiesEP6EntityRK4AABB");
    v15_level_sound = dlsym(mc, "_ZN5Level9playSoundERK4Vec3RKSsff");
    v15_erd_instance = dlsym(mc, "_ZN22EntityRenderDispatcher11getInstanceEv");
    v15_mesh_for = dlsym(mc, "_ZN16BlockTessellator15getMeshForBlockERK9FullBlock");
    v15_mesh_render = dlsym(mc, "_ZNK3mce4Mesh6renderERKNS_11MaterialPtrERKNS_10TexturePtrEjj");
    v15_ms_push = dlsym(mc, "_ZN11MatrixStack4pushEv");
    v15_ms_ref_dtor = dlsym(mc, "_ZN11MatrixStack3RefD1Ev");
    v15_mat_translate = dlsym(mc, "_ZN6Matrix9translateERK4Vec3");
    v15_shader_params = dlsym(mc, "_ZN19EntityShaderManager22_setupShaderParametersER11BlockSourceRK8BlockPosfRK4Vec2");
    v15_ms_world = dlsym(mc, "_ZN11MatrixStack5WorldE");
    v15_vec2_one = dlsym(mc, "_ZN4Vec23ONEE");
    v15_fbr_vt = (u32)dlsym(mc, "_ZTV20FallingBlockRenderer");
    v15_ent_region = dlsym(mc, "_ZNK6Entity9getRegionEv");
    LOG("v015: version 0.%d, %s", nova_version, v15_tick_add && v15_mesh_for && v15_ms_push ? "ok" : "symbols missing");
}
