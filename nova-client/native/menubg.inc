/* menubg.inc - the live menu background (octa1)
 *
 * Replaces the panorama behind every menu screen (CubemapBackgroundScreen::render, vtable slot 15):
 * a dark backdrop with clusters of the game's own blocks and items falling into an abyss.
 * Everything is drawn by the game's ItemRenderer (renderGuiItemNew), so the textures are the
 * current atlas: the "new textures" switch and resource packs apply as they do in the inventory.
 *
 * Turning:
 *  - blocks: the GUI block path (renderGuiItemInChunk -> renderGuiBlock) sets Tessellator::tilt() and
 *    transforms every vertex by the matrix at Tessellator+0xa4 (resetTilt() puts the isometric one back
 *    after the block). We write our own matrix there first: camera pitch * isometric * yaw. Only the
 *    3 faces seen from the isometric view are drawn by the game, so yaw is kept within +-45 degrees
 *    (taken modulo 90: a cube turned by 90 looks the same) and pitch within +-12.
 *    Block vertices are 0..1 (corner origin), so the screen position is corrected to turn about the middle.
 *  - items: flat icons go through iconBlit with the tilt flag off; we switch it on with a 2D turn about
 *    the icon's middle (spin + a flip squeeze), then put the flag and matrix back.
 *
 * Safe: the old panorama is drawn when the switch is off (NOVA settings), when a symbol is missing or
 * after any crash inside (guarded: one crash = panorama for the rest of the run).
 */

#define MBG_MAX 44
typedef struct { short id, aux; float x, y, vy, d, yaw, vyaw, ph, vph, spin, vspin; } MBObj;
static MBObj mb_o[MBG_MAX];
static int mb_n, mb_ready, mb_broken, mb_rot_ok, mb_inited;
static float mb_def[16];
static float mb_next_spawn;
static long mb_last;
static u32 mb_rng = 0x9e3779b9u;
static char *mb_tess;

static void (*mb_o_render)(void *self, void *sc);
static void (*mb_clear_ds)(void *rc);
static void (*mb_grad)(void *sr, int x0, int y0, int x1, int y1, const float *c0, const float *c1);
static int (*mb_getw)(void *client), (*mb_geth)(void *client);
static float (*mb_invscale)(void);
static void (*mb_reset_tilt)(void *tess);

/* nice-looking vanilla things: blocks are full cubes only (the turn needs the cube path) */
static const short MB_BLOCKS[][2] = {
    {1,0},{2,0},{3,0},{4,0},{5,0},{5,1},{5,2},{5,3},{5,4},{5,5},{12,0},{13,0},{14,0},{15,0},{16,0},{17,0},{17,1},{17,2},{17,3},
    {18,0},{20,0},{21,0},{22,0},{24,0},{35,1},{35,4},{35,5},{35,11},{35,14},{41,0},{42,0},{45,0},{46,0},{47,0},{48,0},{49,0},
    {56,0},{57,0},{58,0},{61,0},{73,0},{79,0},{80,0},{82,0},{86,0},{87,0},{88,0},{89,0},{91,0},{98,0},{103,0},{110,0},{112,0},
    {121,0},{129,0},{133,0},{152,0},{155,0},{159,14},{159,4},{162,0},{170,0},{172,0},{173,0},{174,0}};
static const short MB_ITEMS[][2] = {
    {256,0},{257,0},{258,0},{260,0},{261,0},{262,0},{263,0},{264,0},{265,0},{266,0},{267,0},{276,0},{277,0},{278,0},{279,0},
    {280,0},{283,0},{297,0},{306,0},{307,0},{310,0},{311,0},{314,0},{320,0},{322,0},{325,0},{331,0},{332,0},{341,0},{344,0},
    {345,0},{347,0},{348,0},{351,4},{351,1},{357,0},{360,0},{364,0},{368,0},{369,0},{388,0},{391,0},{392,0},{396,0},{399,0},
    {406,0},{354,0},{384,0}};
#define MB_NB ((int)(sizeof MB_BLOCKS / sizeof MB_BLOCKS[0]))
#define MB_NI ((int)(sizeof MB_ITEMS / sizeof MB_ITEMS[0]))
static unsigned char mb_bad[512];

static u32 mb_rand(void) { mb_rng ^= mb_rng << 13; mb_rng ^= mb_rng >> 17; mb_rng ^= mb_rng << 5; return mb_rng; }
static float mb_frand(float a, float b) { return a + (b - a) * (float)(mb_rand() & 0xffffff) / 16777216.f; }

static int mb_valid(int id, int aux) {
    if (id <= 0 || id >= 512 || mb_bad[id]) return 0;
    if (id < 256) return mf_blocks && mf_blocks[id] && sf_items && sf_items[id];
    return sf_items && sf_items[id];
}

static void mb_pick(MBObj *o) {
    for (int t = 0; t < 8; t++) {
        int blk = (mb_rand() % 100) < 58;
        const short *e = blk ? MB_BLOCKS[mb_rand() % MB_NB] : MB_ITEMS[mb_rand() % MB_NI];
        if (mb_valid(e[0], e[1])) { o->id = e[0]; o->aux = e[1]; return; }
    }
    o->id = 1; o->aux = 0;
}

static void mb_spawn(float W, float H, int scatter) {
    int k = 1 + (int)(mb_rand() % 4);                     /* a cluster of 1..4 */
    float cx = mb_frand(0.04f, 0.96f) * W, d = mb_frand(0.25f, 1.f);
    for (int i = 0; i < k && mb_n < MBG_MAX; i++) {
        MBObj *o = &mb_o[mb_n++];
        mb_pick(o);
        o->d = d + mb_frand(-0.08f, 0.08f);
        if (o->d < 0.2f) o->d = 0.2f;
        if (o->d > 1.f) o->d = 1.f;
        float sz = 16.f * (0.7f + 1.9f * o->d);
        o->x = cx + mb_frand(-1.4f, 1.4f) * sz;
        o->y = scatter ? mb_frand(-0.1f, 0.95f) * H : -sz * 1.5f - mb_frand(0.f, 1.2f) * sz;
        o->vy = (14.f + 34.f * o->d) * mb_frand(0.85f, 1.15f);
        o->yaw = mb_frand(-45.f, 45.f); o->vyaw = mb_frand(-40.f, 40.f);
        o->ph = mb_frand(0.f, 6.283f); o->vph = mb_frand(0.6f, 1.4f);
        o->spin = mb_frand(0.f, 360.f); o->vspin = mb_frand(-70.f, 70.f);
    }
}

/* column-major 4x4 (glm) */
static void mb_mul(float *r, const float *a, const float *b) {
    float t[16];
    for (int c = 0; c < 4; c++)
        for (int rr = 0; rr < 4; rr++)
            t[c * 4 + rr] = a[0 * 4 + rr] * b[c * 4 + 0] + a[1 * 4 + rr] * b[c * 4 + 1] + a[2 * 4 + rr] * b[c * 4 + 2] + a[3 * 4 + rr] * b[c * 4 + 3];
    memcpy(r, t, sizeof t);
}
static void mb_ident(float *m) { memset(m, 0, 64); m[0] = m[5] = m[10] = m[15] = 1.f; }
static void mb_apply(const float *m, const float *v, float *o) {
    for (int r = 0; r < 3; r++) o[r] = m[r] * v[0] + m[4 + r] * v[1] + m[8 + r] * v[2] + m[12 + r];
}

static int mb_check_def(void) {                           /* the isometric matrix must be a plain turn */
    for (int c = 0; c < 3; c++) {
        float n = mb_def[c * 4] * mb_def[c * 4] + mb_def[c * 4 + 1] * mb_def[c * 4 + 1] + mb_def[c * 4 + 2] * mb_def[c * 4 + 2];
        if (n < 0.8f || n > 1.2f) return 0;
    }
    return mb_def[12] == 0.f && mb_def[13] == 0.f && mb_def[14] == 0.f && mb_def[15] == 1.f;
}

static void mb_draw_one(MBObj *o, float H) {
    float depth = o->y / H;                               /* nearer the bottom: further into the abyss */
    if (depth < 0.f) depth = 0.f;
    if (depth > 1.2f) depth = 1.2f;
    float fall = 1.f - 0.4f * depth * depth;
    float s = (0.7f + 1.9f * o->d) * fall / 1.f;
    float light = (0.22f + 0.78f * o->d) * (1.f - 0.75f * depth * depth);
    if (light < 0.04f) light = 0.04f;
    float sc = s;                                         /* renderGuiItemNew scale: 1 = 16 px, about the slot middle */
    float x = o->x - 8.f, y = o->y - 8.f;                 /* block: the slot's corner (the game keeps it centred) */
    void *ii = rb_make(0, o->id, o->aux, 1);
    if (!*(void **)((char *)ii + 0xc) && !*(void **)((char *)ii + 0x10)) { mb_bad[o->id] = 1; return; }
    char *T = mb_tess;
    if (o->id < 256) {
        if (mb_rot_ok) {
            float yaw = fmodf(o->yaw + 45.f, 90.f);
            if (yaw < 0.f) yaw += 90.f;
            yaw = (yaw - 45.f) * 0.0174533f;
            float pit = 0.21f * sinf(o->ph);
            float Ry[16], Px[16], M[16];
            mb_ident(Ry); Ry[0] = cosf(yaw); Ry[8] = sinf(yaw); Ry[2] = -sinf(yaw); Ry[10] = cosf(yaw);
            mb_ident(Px); Px[5] = cosf(pit); Px[9] = -sinf(pit); Px[6] = sinf(pit); Px[10] = cosf(pit);
            mb_mul(M, mb_def, Ry);
            mb_mul(M, Px, M);
            float c[3] = {0.5f, 0.5f, 0.5f}, a[3], b[3];
            mb_apply(M, c, a);
            mb_apply(mb_def, c, b);
            float S = 9.f * sc;
            x -= S * (a[0] - b[0]);
            y -= S * (a[1] - b[1]);
            memcpy(T + 0xa4, M, 64);                      /* tilt() will use it; resetTilt() puts the isometric one back */
        }
        rb_ir_item(rb_ir_instance(), ii, 0, x, y, 1.f, light, sc, 0);
        if (mb_rot_ok) memcpy(T + 0xa4, mb_def, 64);
    } else {
        int rot = mb_rot_ok && T[0xa0] == 0 && *(float *)(T + 0x44) == 0.f && *(float *)(T + 0x48) == 0.f &&
                  *(float *)(T + 0x50) == 1.f && *(float *)(T + 0x5c) == 1.f && *(float *)(T + 0x60) == 1.f;
        x = o->x - 7.f; y = o->y - 9.f;                  /* iconBlit draws about (x - 1 + 8, y + 1 + 8) */
        if (rot) {
            float a = o->spin * 0.0174533f, fl = cosf(o->ph * 0.7f);
            if (fl > -0.25f && fl < 0.f) fl = -0.25f;     /* never a hairline */
            if (fl >= 0.f && fl < 0.25f) fl = 0.25f;
            float cx = o->x, cy = o->y;
            float M[16];
            mb_ident(M);
            float ca = cosf(a), sa = sinf(a);
            M[0] = ca * fl; M[1] = sa * fl; M[4] = -sa; M[5] = ca;   /* turn(a) * squeeze(fl, 1) */
            M[12] = cx - (M[0] * cx + M[4] * cy);
            M[13] = cy - (M[1] * cx + M[5] * cy);
            memcpy(T + 0xa4, M, 64);
            T[0xa0] = 1;
        }
        rb_ir_item(rb_ir_instance(), ii, 0, x, y, 1.f, light, sc, 0);
        if (rot) { T[0xa0] = 0; memcpy(T + 0xa4, mb_def, 64); }
    }
}

static void mb_draw_scene(void *client, void *sc) {
    float inv = mb_invscale();
    float W = (float)mb_getw(client) * inv, H = (float)mb_geth(client) * inv;
    if (W < 8.f || H < 8.f) return;
    struct timespec_ ts;
    clock_gettime(1, &ts);
    long now = ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    float dt = mb_last ? (float)(now - mb_last) / 1000.f : 0.f;
    if (dt > 0.1f) dt = 0.1f;
    if (dt < 0.f) dt = 0.f;
    mb_last = now;
    if (!mb_inited) {                                     /* a different start every run */
        mb_inited = 1;
        mb_rng ^= (u32)now * 2654435761u ^ (u32)ts.tv_nsec;
        if (!mb_rng) mb_rng = 1;
        for (int i = 0; i < 9; i++) mb_spawn(W, H, 1);
        mb_next_spawn = 0.3f;
    }
    /* backdrop */
    float top[4] = {0.075f, 0.08f, 0.11f, 1.f}, bot[4] = {0.01f, 0.01f, 0.015f, 1.f};
    mb_grad(f_sr(), 0, 0, (int)W + 1, (int)H + 1, top, bot);
    /* move, retire, spawn */
    for (int i = 0; i < mb_n; i++) {
        MBObj *o = &mb_o[i];
        o->vy += dt * (4.f + 8.f * o->d);                 /* falls a little faster as it goes */
        o->y += o->vy * dt;
        o->yaw += o->vyaw * dt; o->ph += o->vph * dt; o->spin += o->vspin * dt;
        if (o->y > H * 1.25f + 40.f) { mb_o[i] = mb_o[--mb_n]; i--; }
    }
    mb_next_spawn -= dt;
    if (mb_next_spawn <= 0.f) { mb_spawn(W, H, 0); mb_next_spawn = mb_frand(0.35f, 0.95f); }
    /* far first */
    for (int i = 1; i < mb_n; i++) {
        MBObj t = mb_o[i]; int j = i - 1;
        while (j >= 0 && mb_o[j].d > t.d) { mb_o[j + 1] = mb_o[j]; j--; }
        mb_o[j + 1] = t;
    }
    void *rc = *(void **)((char *)sc + 0x10);
    if (!rb_ir_instance()) mb_n = 0;                      /* the item renderer is not up yet: backdrop only */
    for (int i = 0; i < mb_n; i++) {
        if (rc && mb_clear_ds) mb_clear_ds(rc);           /* each one over the ones behind it */
        mb_draw_one(&mb_o[i], H);
    }
    /* the abyss below, a shade above */
    float clr[4] = {0.f, 0.f, 0.f, 0.f}, blk[4] = {0.f, 0.f, 0.f, 1.f}, shade[4] = {0.f, 0.f, 0.f, 0.55f};
    mb_grad(f_sr(), 0, (int)(H * 0.55f), (int)W + 1, (int)H + 1, clr, blk);
    mb_grad(f_sr(), 0, 0, (int)W + 1, (int)(H * 0.14f), shade, clr);
}

static void mb_render(void *self, void *sc) {
    mb_load();
    if (!mb_on || mb_broken || !mb_ready || !sc) { mb_o_render(self, sc); return; }
    void *client = *(void **)((char *)self + 0x14);
    void *rc = *(void **)((char *)sc + 0x10);
    void **helper = *(void ***)((char *)self + 4);
    if (!client || !rc || !helper) { mb_o_render(self, sc); return; }
    if (!mb_def[15]) {                                    /* the isometric matrix, once */
        mb_reset_tilt(mb_tess);
        memcpy(mb_def, mb_tess + 0xa4, 64);
        mb_rot_ok = mb_check_def();
        LOG("menu background: isometric matrix %s", mb_rot_ok ? "ok, blocks turn" : "unexpected, no turning");
    }
    mb_clear_ds(rc);
    ((void (*)(void *, void *))((void **)*helper)[2])(helper, sc);   /* as the game's render: begin */
    static long outer[128];
    int g = nova_guard;
    memcpy(outer, nova_guard_jb, sizeof outer);
    if (crash_path[0]) {
        if (sigsetjmp(nova_guard_jb, 1)) {
            memcpy(nova_guard_jb, outer, sizeof outer);
            nova_guard = g;
            mb_broken = 1;
            mb_tess[0xa0] = 0;
            if (mb_def[15]) memcpy(mb_tess + 0xa4, mb_def, 64);
            LOGE("menu background: crashed (pc rel 0x%x), the panorama is back", nova_guard_pc);
            ((void (*)(void *, void *))((void **)*helper)[3])(helper, sc);
            return;
        }
        nova_guard = 1;
    }
    mb_draw_scene(client, sc);
    memcpy(nova_guard_jb, outer, sizeof outer);
    nova_guard = g;
    ((void (*)(void *, void *))((void **)*helper)[3])(helper, sc);   /* end */
}

static void menubg_init(void *mc) {
    u32 vt = (u32)dlsym(mc, "_ZTV23CubemapBackgroundScreen");
    mb_clear_ds = dlsym(mc, "_ZN3mce13RenderContext23clearDepthStencilBufferEv");
    mb_grad = dlsym(mc, "_ZN14ScreenRenderer12fillGradientEiiiiRK5ColorS2_");
    mb_getw = dlsym(mc, "_ZNK15MinecraftClient8getWidthEv");
    mb_geth = dlsym(mc, "_ZNK15MinecraftClient9getHeightEv");
    mb_invscale = dlsym(mc, "_ZN7GuiData14getInvGuiScaleEv");
    mb_reset_tilt = dlsym(mc, "_ZN11Tessellator9resetTiltEv");
    mb_tess = dlsym(mc, "_ZN11Tessellator8instanceE");
    if (!vt || !mb_clear_ds || !mb_grad || !mb_getw || !mb_geth || !mb_invscale || !mb_reset_tilt || !mb_tess ||
        !rb_ir_instance || !rb_ir_item || !sf_item_ctor || !f_sr) {
        LOGE("menu background: unavailable, the panorama stays");
        return;
    }
    if (patch_slot(vt, 15, mb_render, (void **)&mb_o_render) != 0) { LOGE("menu background: hook failed"); return; }
    mb_ready = 1;
    LOG("menu background: ready");
}
