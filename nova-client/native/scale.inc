/* ---------------- entity size (player and mobs) ----------------
 * Everything scales together, as if the world around were bigger:
 *   hitbox (Entity::setSize, +0xac width, +0xb0 height), eye height (+0xc0, players: pos is the eye),
 *   step height (+0xcc), physics: Mob::travel runs in "unscaled" units (motion / s), Entity::move inside it gets
 *   delta * s, so gravity, jumps, speed and drag all scale; jumpFromGround's impulse is scaled too.
 *   model: MobRenderer::setupPosition + Matrix::scale; camera near plane follows the local player.
 * Entity layout (0.14.3): +8 pos, +0x2c motion, +0x90 AABB, +0x138 unique id, +0x168 removed. */
typedef struct { float x, y, z; } SVec3;
typedef struct { char *e; long long id; float s, w, h, off, step; } MScale;
#define SC_MAX 64
static MScale scl[SC_MAX];
static int scl_n;

static void (*sf_setsize)(void *e, float w, float h);
static void (*sf_setpos)(void *e, SVec3 *p);
static void (*sf_mscale)(void *m, SVec3 *v);
static void *(*sf_getentity)(void *level, long long id, int alsoRemoved);
static void *(*sf_getentities)(void *bs, void *except, MAABB *box);
static long long *(*sf_uid_ref)(void *e);   /* returns a reference to +0x138 */
static long long sf_uid(void *e) { return sf_uid_ref ? *sf_uid_ref(e) : *(long long *)((char *)e + 0x138); }

static MScale *sc_find(void *e) {
    for (int i = 0; i < scl_n; i++) if (scl[i].e == e) return &scl[i];
    return 0;
}
static float sc_of(void *e) {
    if (!scl_n) return 1.f;
    MScale *m = sc_find(e);
    return m ? m->s : 1.f;
}

/* camera near plane: LevelRenderer::setupCamera literals 0.05 (0x46d5d4) and 2*near (0x46d5d0) */
static float sc_near_now = 0.05f;
static void sc_set_near(float s) {
    float n = 0.05f * (s < 1.f ? (s < 0.02f ? 0.02f : s) : 1.f);
    if (n == sc_near_now || !mcbase) return;
    u32 a = mcbase + 0x46d5d0, page = a & ~0xfffu;
    if (*(float *)(a + 4) != sc_near_now) { LOGE("scale: near plane constant moved"); return; }
    if (mprotect((void *)page, 4096, 7) != 0 && mprotect((void *)page, 4096, 3) != 0) return;
    *(float *)a = 2.f * n;
    *(float *)(a + 4) = n;
    mprotect((void *)page, 4096, 5);
    sc_near_now = n;
}

static void sc_apply(char *e, MScale *m) {
    float *pos = (float *)(e + 8);
    float feet = pos[1] - *(float *)(e + 0xc0);
    *(float *)(e + 0xc0) = m->off * m->s;
    *(float *)(e + 0xcc) = m->step * m->s;
    if (sf_setsize) sf_setsize(e, m->w * m->s, m->h * m->s);
    SVec3 p = {pos[0], feet + m->off * m->s, pos[2]};
    if (sf_setpos) sf_setpos(e, &p);
}

static int sc_set(char *e, float s) {
    if (!e) return -1;
    if (s < 0.05f) s = 0.05f;
    if (s > 16.f) s = 16.f;
    MScale *m = sc_find(e);
    if (!m) {
        if (s == 1.f) return 0;
        if (scl_n >= SC_MAX) return -1;
        m = &scl[scl_n++];
        m->e = e; m->id = sf_uid(e);
        m->w = *(float *)(e + 0xac); m->h = *(float *)(e + 0xb0);
        m->off = *(float *)(e + 0xc0); m->step = *(float *)(e + 0xcc);
    }
    m->s = s;
    sc_apply(e, m);
    if (s == 1.f) *m = scl[--scl_n];
    if (e == mm_player()) sc_set_near(s);
    return 0;
}

/* per frame: drop removed entities, re-apply when the game resized one (babies growing up...) */
static void sc_frame(void) {
    void *lvl = 0;
    char *pl = mm_player();
    if (pl) lvl = *(void **)(pl + 0x78);
    for (int i = 0; i < scl_n; i++) {
        MScale *m = &scl[i];
        void *live = lvl && sf_getentity ? sf_getentity(lvl, m->id, 0) : 0;
        if (live != m->e || *(unsigned char *)(m->e + 0x168)) { scl[i--] = scl[--scl_n]; continue; }
        if (*(float *)(m->e + 0xb0) != m->h * m->s) {           /* resized by the game: new base size */
            m->h = *(float *)(m->e + 0xb0) / 1.f; m->w = *(float *)(m->e + 0xac);
            sc_apply(m->e, m);
        }
    }
    if (!pl) { scl_n = 0; sc_set_near(1.f); }
    else sc_set_near(sc_of(pl));
}

/* physics */
static char *sc_moving;                      /* entity inside its scaled travel() */
static float sc_moving_s = 1.f;
static void (*o_travel)(void *e, float strafe, float fwd);
static void h_travel(void *e, float strafe, float fwd) {
    float s = sc_of(e);
    if (s == 1.f) { o_travel(e, strafe, fwd); return; }
    float *mo = (float *)((char *)e + 0x2c);
    mo[0] /= s; mo[1] /= s; mo[2] /= s;
    char *pm = sc_moving; float ps = sc_moving_s;
    sc_moving = e; sc_moving_s = s;
    o_travel(e, strafe, fwd);
    sc_moving = pm; sc_moving_s = ps;
    mo[0] *= s; mo[1] *= s; mo[2] *= s;
}
static void (*o_move)(void *e, SVec3 *d);
static void h_move(void *e, SVec3 *d) {
    if (e != sc_moving) { o_move(e, d); return; }
    SVec3 v = {d->x * sc_moving_s, d->y * sc_moving_s, d->z * sc_moving_s};
    o_move(e, &v);
}
static void (*o_jump)(void *e);
static void h_jump(void *e) {
    float s = sc_of(e);
    float *mo = (float *)((char *)e + 0x2c);
    float x0 = mo[0], z0 = mo[2];
    o_jump(e);
    if (s == 1.f) return;
    mo[1] *= s;                               /* jump impulse; the sprint boost is added on top of x/z */
    mo[0] = x0 + (mo[0] - x0) * s;
    mo[2] = z0 + (mo[2] - z0) * s;
}

/* model */
static void (*o_setup_pos)(void *r, void *e, SVec3 *p, void *mat);
static void h_setup_pos(void *r, void *e, SVec3 *p, void *mat) {
    o_setup_pos(r, e, p, mat);
    float s = sc_of(e);
    if (s != 1.f && sf_mscale) { SVec3 v = {s, s, s}; sf_mscale(mat, &v); }
}

/* chat: ChatScreen::sendChatMessage reads the text box string at +0x88 */
static void (*o_chat_send)(void *scr);
static void h_chat_send(void *scr) {
    char *str = (char *)scr + 0x88;
    const char *t = sdata(str);
    if (t && slen(t) > 0 && mm_fire(EV_CHAT, t, 0, 0) && f_strassign) f_strassign(str, "");
    o_chat_send(scr);
}

/* mod API */
static int sc_op(int op, const double *a, int n, double *out) {
    char *pl = mm_player();
    if (!pl) return -1;
    void *lvl = *(void **)(pl + 0x78);
    if (op == OP_PLAYER_ID) { out[0] = (double)sf_uid(pl); return 1; }
    if (op == OP_ENTITY_LIST) {
        float r = n > 0 ? (float)a[0] : 16.f;
        if (r < 1) r = 1;
        if (r > 128) r = 128;
        float *p = (float *)(pl + 8);
        MAABB box = {{p[0] - r, p[1] - r, p[2] - r, p[0] + r, p[1] + r, p[2] + r}, 0, {0, 0, 0}};
        void *bs = mm_region(pl);
        if (!bs || !sf_getentities) return 0;
        char ***v = sf_getentities(bs, pl, &box);
        int k = 0;
        if (v) for (char **it = v[0]; it < v[1] && k < 64; it++) if (*it) out[k++] = (double)sf_uid(*it);
        return k;
    }
    if (n < 1 || !lvl || !sf_getentity) return -1;
    char *e = sf_getentity(lvl, (long long)a[0], 0);
    if (!e) return 0;                                       /* -> null in the script */
    switch (op) {
    case OP_ENTITY_INFO: {
        void **vt = *(void ***)e;
        int (*tid)(void *) = (int (*)(void *))vt[83];
        out[0] = (double)(tid(e) & 0xff);
        float *p = (float *)(e + 8);
        out[1] = p[0]; out[2] = p[1] - *(float *)(e + 0xc0); out[3] = p[2];
        return 4;
    }
    case OP_ENTITY_SCALE: out[0] = sc_of(e); return 1;
    case OP_ENTITY_SET_SCALE: sc_set(e, n > 1 ? (float)a[1] : 1.f); return 0;
    }
    return -1;
}

static void scale_init(void *mc, u32 base) {
    #define S(var, name) do { *(void **)&var = dlsym(mc, name); if (!var) LOGE("scale: missing %s", name); } while (0)
    S(sf_setsize, "_ZN6Entity7setSizeEff");
    S(sf_setpos, "_ZN6Entity6setPosERK4Vec3");
    S(sf_mscale, "_ZN6Matrix5scaleERK4Vec3");
    S(sf_getentity, "_ZNK5Level9getEntityE14EntityUniqueIDb");
    S(sf_getentities, "_ZN11BlockSource11getEntitiesEP6EntityRK4AABB");
    S(sf_uid_ref, "_ZNK6Entity11getUniqueIDEv");
    #undef S
    GATE(MOB_TRAVEL, h_travel, o_travel);
    GATE(ENTITY_MOVE, h_move, o_move);
    GATE(MOB_JUMP, h_jump, o_jump);
    GATE(SETUP_POS, h_setup_pos, o_setup_pos);
    GATE(CHAT_SEND, h_chat_send, o_chat_send);
}
