/* ======================= NOVA menus inside the game =======================
 * Included from nova.c (after the settings/HUD code).
 *
 * The game's own options screen is reused as a host: libnova pushes an
 * OptionsScreen in "NOVA mode" and fills it with its own categories and panes.
 * Toggles, step sliders and text boxes are bound to NOVA-owned Option objects;
 * Options::get/set/toggle are gated so values of those options live here.
 * Java opens a screen with nativeOpenNova() and polls nativePollNova() for the
 * result (pack switches, a world to create).
 *
 * Also here: chunk borders (F3 option), drawn after the level's name tags.
 */

static void *g_client;

/* ---------------- NOVA-owned options ------------------------------------ */
typedef struct { int type; u32 caption; } OptObj;      /* = Options::Option */
#define NOPT_MAX 96
static OptObj nopts[NOPT_MAX];
static int nopt_count;
static int nopt_val[NOPT_MAX];
static int nopt_radio[NOPT_MAX];        /* radio group (0 = none) */
static int nopt_steps[NOPT_MAX];        /* step slider: values 1..n */
static int nopt_role[NOPT_MAX];         /* what the option means (R_*) */
static char nopt_text[NOPT_MAX][64];
static void (*f_strassign)(void *, const char *);
static void (*f_i18n)(void *out, void *key);          /* I18n::get -> std::string (sret) */

/* game-language text for a lang key (falls back to the key) */
static void loc_into(const char *key, char *dst, int cap) {
    int n = 0;
    if (f_i18n) {
        u32 k[1], out[1] = {0};
        gstr(k, key);
        f_i18n(out, k);
        const char *d = sdata(out);
        while (d && d[n] && n < cap - 1) { dst[n] = d[n]; n++; }
        f_strdtor(out);
        f_strdtor(k);
    }
    if (!n) while (key[n] && n < cap - 1) { dst[n] = key[n]; n++; }
    dst[n] = 0;
}

static int is_nopt(const void *o) {
    return (const char *)o >= (const char *)nopts && (const char *)o < (const char *)(nopts + NOPT_MAX);
}
static int nidx(const void *o) { return (const OptObj *)o - nopts; }

static OptObj *nopt_new(int type, const char *caption, int role) {
    if (nopt_count >= NOPT_MAX) return &nopts[NOPT_MAX - 1];
    int i = nopt_count++;
    OptObj *o = &nopts[i];
    o->type = type;
    if (o->caption) f_strassign(&o->caption, caption);
    else gstr(&o->caption, caption);
    nopt_val[i] = 0; nopt_radio[i] = 0; nopt_steps[i] = 0; nopt_role[i] = role; nopt_text[i][0] = 0;
    return o;
}

static void nopt_on_change(int i);

static void nopt_set(int i, int v) {
    if (nopt_radio[i]) {
        if (!v) return;                                  /* a radio choice can't be cleared */
        for (int k = 0; k < nopt_count; k++) if (nopt_radio[k] == nopt_radio[i]) nopt_val[k] = 0;
    }
    nopt_val[i] = v;
    nopt_on_change(i);
}

typedef int (*o_getb_fn)(void *, void *);
typedef void (*o_setb_fn)(void *, void *, int);
typedef void (*o_toggle_fn)(void *, void *, int);
typedef void (*o_getvals_fn)(void *, void *, void *);
typedef void (*o_gets_fn)(void *, void *, void *);
typedef void (*o_sets_fn)(void *, void *, void *);
static o_getb_fn o_getbool, o_getint;
static o_setb_fn o_setbool, o_setint;
static o_toggle_fn o_toggle;
static o_getvals_fn o_getvalues;
static o_gets_fn o_getstring;
static o_sets_fn o_setstring;

static int h_getbool(void *opts, void *opt) {
    if (is_nopt(opt)) return nopt_val[nidx(opt)] != 0;
    return o_getbool(opts, opt);
}
static void h_setbool(void *opts, void *opt, int v) {
    if (is_nopt(opt)) { nopt_set(nidx(opt), (v & 0xff) != 0); return; }
    o_setbool(opts, opt, v);
}
static void h_toggle(void *opts, void *opt, int d) {
    if (is_nopt(opt)) { int i = nidx(opt); nopt_set(i, !nopt_val[i]); return; }
    o_toggle(opts, opt, d);
}
static int h_getint(void *opts, void *opt) {
    if (is_nopt(opt)) return nopt_val[nidx(opt)];
    return o_getint(opts, opt);
}
static void h_setint(void *opts, void *opt, int v) {
    if (is_nopt(opt)) { nopt_set(nidx(opt), v); return; }
    o_setint(opts, opt, v);
}
static void h_getvalues(void *out, void *opts, void *opt) {
    if (is_nopt(opt)) {
        int n = nopt_steps[nidx(opt)];
        if (n < 1) n = 1;
        int *v = f_new(n * 4);
        for (int k = 0; k < n; k++) v[k] = k + 1;
        u32 *vec = out;
        vec[0] = (u32)v; vec[1] = (u32)(v + n); vec[2] = (u32)(v + n);
        return;
    }
    o_getvalues(out, opts, opt);
}
static void h_getstring(void *out, void *opts, void *opt) {
    if (is_nopt(opt)) { char a[4]; f_strctor(out, nopt_text[nidx(opt)], a); return; }
    o_getstring(out, opts, opt);
}
static void h_setstring(void *opts, void *opt, void *str) {
    if (is_nopt(opt)) {
        int i = nidx(opt);
        const char *d = sdata(str);
        int n = slen(d);
        if (n > 63) n = 63;
        for (int k = 0; k < n; k++) nopt_text[i][k] = d[k];
        nopt_text[i][n] = 0;
        return;
    }
    o_setstring(opts, opt, str);
}

/* ---------------- NOVA screens ------------------------------------------- */
enum { NOVA_NONE, NOVA_PACKS, NOVA_CREATE };
enum { R_NONE, R_PACK_ON, R_PACK_DEL, R_NAME, R_SEED, R_SURV, R_CREAT, R_INF, R_FLAT, R_OLD,
       R_T_DAWN, R_T_NOON, R_T_DUSK, R_T_NIGHT, R_CYCLE, R_CLEAR, R_RAIN, R_THUNDER,
       R_MOBS, R_SPX, R_SPZ, R_CREATE, R_NEWTEX, R_IMPORT };

static int nova_pending, nova_creating, nova_kind, nova_close, nova_ttl;
static void *nova_screen;
static char nova_a[4096], nova_b[512];
static char nova_result[4096];
static volatile int nova_result_ready;

typedef void (*push_opts_fn)(void *chooser, int, int, void *str, int);
typedef void *(*getchooser_fn)(void *client);
typedef void (*ctext_fn)(void *group, void *opt, void *client, void *str);
static push_opts_fn f_pushopts;
static getchooser_fn f_getchooser;
static ctext_fn f_ctext;
static void (*f_closescreen)(void *);

static int nova_cats(void *scr);           /* used by h_createcats */
static int nova_panes(void *scr);          /* used by h_genscreens */

static int pack_count, pack_gen;
static char imp_title[64], imp_sub[64];
static char pack_names[40][64], pack_subs[40][64];
static char pack_mod[40];                 /* 1 = .mcmod: shown on the Mods tab */

/* nova_a: "#<gen>\n" then one "name\tsubtitle" line per pack */
static void parse_packs(void) {
    pack_count = 0;
    const char *p = nova_a;
    if (*p == '#') {
        pack_gen = 0;
        p++;
        while (*p >= '0' && *p <= '9') pack_gen = pack_gen * 10 + (*p++ - '0');
        while (*p && *p != '\n') p++;
        if (*p) p++;
    }
    while (*p && pack_count < 40) {
        if (*p == '!') {                         /* "!title\tsubtitle" of the import card */
            int n = 0, m = 0;
            p++;
            while (*p && *p != '\n' && *p != '\t') { if (n < 63) imp_title[n++] = *p; p++; }
            if (*p == '\t') { p++; while (*p && *p != '\n') { if (m < 63) imp_sub[m++] = *p; p++; } }
            imp_title[n] = 0; imp_sub[m] = 0;
            if (*p == '\n') p++;
            continue;
        }
        int n = 0, m = 0;
        pack_mod[pack_count] = 0;
        while (*p && *p != '\n' && *p != '\t') { if (n < 63) pack_names[pack_count][n++] = *p; p++; }
        if (*p == '\t') { p++; while (*p && *p != '\n' && *p != '\t') { if (m < 63) pack_subs[pack_count][m++] = *p; p++; } }
        if (*p == '\t') { p++; if (*p == 'm') pack_mod[pack_count] = 1; while (*p && *p != '\n') p++; }
        pack_names[pack_count][n] = 0;
        pack_subs[pack_count][m] = 0;
        if (*p == '\n') p++;
        if (n) pack_count++;
    }
}

static void *new_card(void *pane, const char *title, const char *sub, int icon);
static void cards_free(void);
static void *new_pane(void) { void *pane = f_new(0x70); f_pane_ctor(pane); return pane; }
static void *new_group(void *pane, const char *title) {
    u32 str[1];
    gstr(str, title);
    void *g = f_cgroup(pane, str, 1);
    f_strdtor(str);
    return g;
}
static void add_toggle(void *g, const char *cap, int role, int val, int radio) {
    OptObj *o = nopt_new(0, cap, role);
    int i = nidx(o);
    nopt_val[i] = val; nopt_radio[i] = radio;
    f_ctoggle(g, o, g_client);
}
static void add_text(void *g, const char *cap, int role, const char *val) {
    OptObj *o = nopt_new(2, cap, role);
    int i = nidx(o), n = 0;
    while (val[n] && n < 63) { nopt_text[i][n] = val[n]; n++; }
    nopt_text[i][n] = 0;
    u32 str[1];
    gstr(str, val);
    f_ctext(g, o, g_client, str);
    f_strdtor(str);
}

static int nova_cats(void *scr) {
    if (!nova_creating) return 0;
    nova_screen = scr;
    nova_kind = nova_creating;
    nova_creating = 0;
    char *S = scr;
    /* let the game build its tabs first (it also creates the tab backgrounds),
       then replace the tabs with ours */
    o_createcats(scr);
    u32 *b = *(u32 **)(S + 0xc8), *e = *(u32 **)(S + 0xcc);
    for (u32 *q = b; q < e; q += 2) if (q[1]) f_release((void *)q[1]);
    *(u32 **)(S + 0xcc) = b;
    int size = *(int *)(S + 0xa4);
    char *hdr = *(char **)(S + 0xa8);                 /* Touch::THeader, text at +0x60 */
    if (hdr && nova_kind == NOVA_PACKS) {
        char t[64];
        loc_into("nova.storage.title", t, sizeof t);
        f_strassign(hdr + 0x60, t);
    }
    if (nova_kind == NOVA_PACKS) {
        f_createcat(scr, 2, size, 168, 84, 0, 0);     /* resource packs */
        f_createcat(scr, 3, size, 196, 112, 0, 0);    /* mods */
        f_createcat(scr, 4, size, 140, 84, 0, 0);     /* import */
    } else {
        f_createcat(scr, 2, size, 224, 84, 0, 0);
        f_createcat(scr, 3, size, 168, 112, 0, 0);
        f_createcat(scr, 4, size, 196, 112, 0, 0);
    }
    return 1;
}

static int nova_panes(void *scr) {
    if (scr != nova_screen) return 0;
    char *S = scr;
    nopt_count = 0;
    if (nova_kind == NOVA_PACKS) {
        parse_packs();
        void *p0 = new_pane(), *pm = new_pane(), *p1 = new_pane();
        cards_free();
        int nres = 0, nmod = 0;
        for (int k = 0; k < pack_count; k++) { if (pack_mod[k]) nmod++; else nres++; }
        if (!nres) new_group(p0, "nova.packs.none");
        if (!nmod) new_group(pm, "nova.mods.none");
        for (int k = 0; k < pack_count; k++) {
            /* one card per pack: icon, name, version/author, then its switches */
            void *g = new_card(pack_mod[k] ? pm : p0, pack_names[k], pack_subs[k], k);
            add_toggle(g, "nova.packs.on", R_PACK_ON, nova_b[k] == '1', 0);
            add_toggle(g, "nova.packs.del", R_PACK_DEL, 0, 0);
        }
        loc_into("nova.packs.import", imp_title, sizeof imp_title);
        loc_into("nova.packs.import.sub", imp_sub, sizeof imp_sub);
        void *gi = new_card(p1, imp_title, imp_sub, 99);
        add_toggle(gi, "nova.packs.import.file", R_IMPORT, 0, 0);
        push_pane(S, p0);
        push_pane(S, pm);
        push_pane(S, p1);
    } else {
        void *p0 = new_pane(), *p1 = new_pane(), *p2 = new_pane();
        void *g = new_group(p0, "nova.cw.world");
        add_text(g, "nova.cw.name", R_NAME, nova_a);
        add_text(g, "nova.cw.seed", R_SEED, "");
        g = new_group(p0, "nova.cw.mode");
        add_toggle(g, "nova.cw.survival", R_SURV, 1, 1);
        add_toggle(g, "nova.cw.creative", R_CREAT, 0, 1);
        g = new_group(p0, "nova.cw.type");
        add_toggle(g, "nova.cw.infinite", R_INF, 1, 2);
        add_toggle(g, "nova.cw.flat", R_FLAT, 0, 2);
        add_toggle(g, "nova.cw.old", R_OLD, 0, 2);
        g = new_group(p1, "nova.cw.time");
        add_toggle(g, "nova.cw.dawn", R_T_DAWN, 1, 3);
        add_toggle(g, "nova.cw.noon", R_T_NOON, 0, 3);
        add_toggle(g, "nova.cw.dusk", R_T_DUSK, 0, 3);
        add_toggle(g, "nova.cw.night", R_T_NIGHT, 0, 3);
        add_toggle(g, "nova.cw.cycle", R_CYCLE, 1, 0);
        g = new_group(p1, "nova.cw.weather");
        add_toggle(g, "nova.cw.clear", R_CLEAR, 1, 4);
        add_toggle(g, "nova.cw.rain", R_RAIN, 0, 4);
        add_toggle(g, "nova.cw.thunder", R_THUNDER, 0, 4);
        g = new_group(p2, "nova.cw.more");
        add_toggle(g, "nova.cw.mobs", R_MOBS, 1, 0);
        add_text(g, "nova.cw.spawnx", R_SPX, "0");
        add_text(g, "nova.cw.spawnz", R_SPZ, "0");
        g = new_group(p2, "nova.cw.finish");
        add_toggle(g, "nova.cw.create", R_CREATE, 0, 0);
        push_pane(S, p0);
        push_pane(S, p1);
        push_pane(S, p2);
    }
    return 1;
}

static int role_val(int role) {
    for (int i = 0; i < nopt_count; i++) if (nopt_role[i] == role) return nopt_val[i];
    return 0;
}
static const char *role_text(int role) {
    for (int i = 0; i < nopt_count; i++) if (nopt_role[i] == role) return nopt_text[i];
    return "";
}

static int append(char *dst, int pos, int cap, const char *s) {
    while (*s && pos < cap - 1) dst[pos++] = *s++;
    dst[pos] = 0;
    return pos;
}

static void nova_finish_packs(void) {
    int p = 0;
    p = append(nova_result, p, sizeof nova_result, "P|");
    for (int i = 0; i < nopt_count; i++) if (nopt_role[i] == R_PACK_ON) p = append(nova_result, p, sizeof nova_result, nopt_val[i] ? "1" : "0");
    p = append(nova_result, p, sizeof nova_result, "|");
    for (int i = 0; i < nopt_count; i++) if (nopt_role[i] == R_PACK_DEL) p = append(nova_result, p, sizeof nova_result, nopt_val[i] ? "1" : "0");
    nova_result_ready = 1;
}

static void nova_finish_create(void) {
    char num[16];
    int p = 0;
    #define FIELD(s) do { p = append(nova_result, p, sizeof nova_result, s); p = append(nova_result, p, sizeof nova_result, "\x1f"); } while (0)
    p = append(nova_result, p, sizeof nova_result, "C|");
    FIELD(role_text(R_NAME));
    FIELD(role_text(R_SEED));
    FIELD(role_val(R_CREAT) ? "1" : "0");
    FIELD(role_val(R_FLAT) ? "2" : role_val(R_OLD) ? "0" : "1");
    FIELD(role_val(R_T_NOON) ? "6000" : role_val(R_T_DUSK) ? "12000" : role_val(R_T_NIGHT) ? "18000" : "0");
    FIELD(role_val(R_CYCLE) ? "1" : "0");
    FIELD(role_val(R_MOBS) ? "1" : "0");
    FIELD(role_val(R_THUNDER) ? "2" : role_val(R_RAIN) ? "1" : "0");
    FIELD(role_text(R_SPX));
    FIELD(role_text(R_SPZ));
    #undef FIELD
    (void)num;
    nova_result_ready = 1;
}

/* "new textures" switch in the NOVA settings tab: applied by a restart */
static int tex_active, tex_want = -1;
int Java_com_nova_client_NovaActivity_nativeSetTexMode(void *env, void *cls, int on) { tex_active = on; return 0; }

static void *newtex_option(void) {
    nopt_count = 0;
    OptObj *o = nopt_new(0, "nova.opt.newtextures", R_NEWTEX);
    nopt_val[nidx(o)] = tex_want >= 0 ? tex_want : tex_active;
    return o;
}

static void options_closed(void *scr) {
    if (scr == last_screen && tex_want >= 0 && tex_want != tex_active) {
        nova_result[0] = 'R'; nova_result[1] = '0' + tex_want; nova_result[2] = 0;
        nova_result_ready = 1;
    }
}

static void nopt_on_change(int i) {
    if (nopt_role[i] == R_NEWTEX) { tex_want = nopt_val[i]; return; }
    if (nopt_role[i] == R_IMPORT && nopt_val[i]) {
        nopt_val[i] = 0;
        nova_result[0] = 'I'; nova_result[1] = 0;   /* Java opens the file picker */
        nova_result_ready = 1;
        return;
    }
    if (nopt_role[i] == R_CREATE && nopt_val[i] && nova_kind == NOVA_CREATE && nova_screen) {
        nova_finish_create();
        nova_close = 1;                 /* closed from the next update, not inside the click */
    }
}

/* OptionsScreen destructors: the NOVA screen is going away */
static void (*o_opt_d1)(void *), (*o_opt_d0)(void *);
static void nova_screen_gone(void *scr) {
    options_closed(scr);
    if (scr != nova_screen) return;
    if (nova_kind == NOVA_PACKS) nova_finish_packs();
    cards_free();
    nova_screen = 0;
    nova_kind = NOVA_NONE;
}
static void h_opt_d1(void *scr) { nova_screen_gone(scr); o_opt_d1(scr); }
static void h_opt_d0(void *scr) { nova_screen_gone(scr); o_opt_d0(scr); }

/* JNI */
static int jstr_copy(void **env, void *js, char *dst, int cap) {
    typedef const char *(*getutf_fn)(void **, void *, void *);
    typedef void (*relutf_fn)(void **, void *, const char *);
    void **fns = *(void ***)env;
    dst[0] = 0;
    if (!js) return 0;
    const char *d = ((getutf_fn)fns[169])(env, js, 0);
    if (!d) return 0;
    int n = 0;
    while (d[n] && n < cap - 1) { dst[n] = d[n]; n++; }
    dst[n] = 0;
    ((relutf_fn)fns[170])(env, js, d);
    return n;
}

int Java_com_nova_client_NovaActivity_nativeOpenNova(void **env, void *cls, int kind, void *a, void *b) {
    if (!stage2_ok || !f_pushopts) return -1;
    jstr_copy(env, a, nova_a, sizeof nova_a);
    jstr_copy(env, b, nova_b, sizeof nova_b);
    nova_result_ready = 0;
    nova_pending = kind;
    return 0;
}

void *Java_com_nova_client_NovaActivity_nativePollNova(void **env, void *cls) {
    typedef void *(*newutf_fn)(void **, const char *);
    if (!nova_result_ready) return 0;
    nova_result_ready = 0;
    void **fns = *(void ***)env;
    return ((newutf_fn)fns[167])(env, nova_result);
}

/* ---------------- chunk borders ------------------------------------------ */
typedef void (*nametags_fn)(void *lr, float a);
typedef void (*campos_fn)(float *out, void *lr, float a, void *ent);
typedef void (*tbegin_fn)(void *t, int mode, int n);
typedef void (*tvertex_fn)(void *t, float x, float y, float z);
typedef void (*tdraw_fn)(void *t, void *mat);
typedef void (*matptr_fn)(void *mat, void *group, void *str);
typedef void (*setcolor_fn)(void *sc, float *color);
static nametags_fn o_nametags;
static campos_fn f_campos;
static tbegin_fn f_tbegin;
static tvertex_fn f_tvertex;
static tdraw_fn f_tdraw;
static matptr_fn f_matptr;
static setcolor_fn f_setcolor;
static void *g_tess, *g_switchable, *g_shadercolor;
static void *opt_chunks;
static u32 line_mat[16];
static int line_mat_ok;
static float cam[3];

static void quad(float ax, float ay, float az, float bx, float by, float bz,
                 float cx, float cy, float cz, float dx, float dy, float dz) {
    f_tvertex(g_tess, ax - cam[0], ay - cam[1], az - cam[2]);
    f_tvertex(g_tess, bx - cam[0], by - cam[1], bz - cam[2]);
    f_tvertex(g_tess, cx - cam[0], cy - cam[1], cz - cam[2]);
    f_tvertex(g_tess, dx - cam[0], dy - cam[1], dz - cam[2]);
    f_tvertex(g_tess, dx - cam[0], dy - cam[1], dz - cam[2]);     /* back side */
    f_tvertex(g_tess, cx - cam[0], cy - cam[1], cz - cam[2]);
    f_tvertex(g_tess, bx - cam[0], by - cam[1], bz - cam[2]);
    f_tvertex(g_tess, ax - cam[0], ay - cam[1], az - cam[2]);
}
#define LW 0.02f
static void vline(float x, float z, float y0, float y1) {
    quad(x - LW, y0, z, x + LW, y0, z, x + LW, y1, z, x - LW, y1, z);
    quad(x, y0, z - LW, x, y0, z + LW, x, y1, z + LW, x, y1, z - LW);
}
static void xline(float x0, float x1, float y, float z) {
    quad(x0, y - LW, z, x1, y - LW, z, x1, y + LW, z, x0, y + LW, z);
    quad(x0, y, z - LW, x1, y, z - LW, x1, y, z + LW, x0, y, z + LW);
}
static void zline(float z0, float z1, float y, float x) {
    quad(x, y - LW, z0, x, y - LW, z1, x, y + LW, z1, x, y + LW, z0);
    quad(x - LW, y, z0, x + LW, y, z0, x + LW, y, z1, x - LW, y, z1);
}
static void draw_with(float r, float g, float b) {
    float c[4] = {r, g, b, 1.f};
    f_setcolor(g_shadercolor, c);
    f_tdraw(g_tess, line_mat);
}

static void h_nametags(void *lr, float a) {
    o_nametags(lr, a);
    void *client = g_client;
    if (!client || !f_campos || !f_tbegin) return;
    void *opts = f_getoptions(client);
    if (!opts || !f_getbool(opts, opt_f3) || !f_getbool(opts, opt_chunks)) return;
    char *pl = *(char **)((char *)client + 0x120);
    if (!pl) return;
    if (!line_mat_ok) {
        u32 str[1];
        gstr(str, "entity_flat_color");
        f_matptr(line_mat, g_switchable, str);
        f_strdtor(str);
        line_mat_ok = 1;
    }
    f_campos(cam, lr, a, pl);
    float *pos = (float *)(pl + 8);
    int cx = ifloor(pos[0]) >> 4, cz = ifloor(pos[2]) >> 4;
    float x0 = cx * 16.f, z0 = cz * 16.f;
    float py = pos[1];
    /* red: corners of the neighbouring chunks */
    f_tbegin(g_tess, 1, 16 * 16);
    for (int i = -1; i <= 2; i++)
        for (int j = -1; j <= 2; j++) {
            if ((i == 0 || i == 1) && (j == 0 || j == 1)) continue;
            vline(x0 + 16.f * i, z0 + 16.f * j, 0.f, 256.f);
        }
    draw_with(1.f, 0.f, 0.f);
    /* yellow: this chunk's corners */
    f_tbegin(g_tess, 1, 4 * 16);
    vline(x0, z0, 0.f, 256.f); vline(x0 + 16.f, z0, 0.f, 256.f);
    vline(x0, z0 + 16.f, 0.f, 256.f); vline(x0 + 16.f, z0 + 16.f, 0.f, 256.f);
    draw_with(1.f, 1.f, 0.f);
    /* cyan grid on this chunk's walls, every 2 blocks, around the player */
    int ylo = ifloor(py) - 24, yhi = ifloor(py) + 24;
    ylo &= ~1; if (ylo < 0) ylo = 0; if (yhi > 256) yhi = 256;
    f_tbegin(g_tess, 1, 4096);
    for (int k = 2; k < 16; k += 2) {
        vline(x0 + k, z0, (float)ylo, (float)yhi); vline(x0 + k, z0 + 16.f, (float)ylo, (float)yhi);
        vline(x0, z0 + k, (float)ylo, (float)yhi); vline(x0 + 16.f, z0 + k, (float)ylo, (float)yhi);
    }
    for (int y = ylo; y <= yhi; y += 2) {
        xline(x0, x0 + 16.f, (float)y, z0); xline(x0, x0 + 16.f, (float)y, z0 + 16.f);
        zline(z0, z0 + 16.f, (float)y, x0); zline(z0, z0 + 16.f, (float)y, x0 + 16.f);
    }
    draw_with(0.25f, 0.85f, 1.f);
    float w[4] = {1, 1, 1, 1};
    f_setcolor(g_shadercolor, w);
}

/* ---------------- font: widths of Latin letters in unicode mode -------------
 * A line with any non-Latin character is drawn with the unicode font. Its width
 * cache for U+0000..U+00FF is filled by scanning the 8 px default8 sheet as if it
 * had 16 px cells, which gives every Latin letter the width of a full cell
 * ("N O V A"). Correct widths (same formula, from glyph_00.png) are put in. */
#include "glyph00_widths.h"
static void fix_font_widths(void *client) {
    char *font = *(char **)((char *)client + 0x7c);
    if (!font) return;
    char *cache = *(char **)(font + 0xa64);
    if (!cache || *(int *)(cache - 12) < 256) return;
    if (cache['a'] == (char)glyph00_widths['a'] && cache['W'] == (char)glyph00_widths['W']) return;
    for (int c = 0x21; c < 0x100; c++) if (glyph00_widths[c]) cache[c] = (char)glyph00_widths[c];
}

/* ---------------- per-frame: MinecraftClient::update -------------------- */
static void mcmod_frame(void *client);
static void (*o_client_update)(void *);
static void h_client_update(void *client) {
    g_client = client;
    o_client_update(client);
    fix_font_widths(client);
    mcmod_frame(client);
    if (nova_creating && nova_ttl > 0 && --nova_ttl == 0) { nova_creating = 0; LOGE("nova screen: init not reached"); }
    if (nova_close && nova_screen) {
        nova_close = 0;
        f_closescreen(nova_screen);
    } else nova_close = 0;
    if (nova_pending && !nova_screen) {
        int kind = nova_pending;
        nova_pending = 0;
        void *chooser = f_getchooser(client);
        if (chooser) {
            u32 str[1];
            gstr(str, "");
            nova_creating = kind;
            f_pushopts(chooser, 0, 0, str, 0);
            f_strdtor(str);
            nova_ttl = 120;      /* if init is deferred, the flag waits a little */
        }
    }
}


/* ---------------- toast (Bedrock-style, top centre) ---------------------- */
typedef void *(*sr_single_fn)(void);
typedef void (*sr_fill_fn)(void *sr, float x0, float y0, float x1, float y1, float *color);
typedef void *(*getguidata_fn)(void *client);
typedef int (*screenw_fn)(void *gd);
static sr_single_fn f_sr;
static sr_fill_fn f_fill;
static getguidata_fn f_guidata;
static screenw_fn f_screenw;
static char toast_title[96], toast_sub[96];
static int toast_icon;              /* >0: Java serves images/nova/toast_icon_<n>.png */
static u32 toast_tex[16];
static int toast_tex_ok;
typedef void (*gettex_fn)(void *out, void *group, void *str, int loc);
typedef void (*blit_fn)(void *sr, void *tex, int *rect);
static gettex_fn f_gettex;
static blit_fn f_blit;
static void (*f_texdtor)(void *);
static void *(*f_textures)(void *client);
static long toast_t0;
static int toast_on;
static long toast_drawn_at;

int Java_com_nova_client_NovaActivity_nativeToast(void **env, void *cls, void *t, void *sub, int icon) {
    jstr_copy(env, t, toast_title, sizeof toast_title);
    jstr_copy(env, sub, toast_sub, sizeof toast_sub);
    char k[96];                                          /* "@key": localized here */
    if (toast_title[0] == '@') { int i = 0; while ((k[i] = toast_title[i + 1])) i++; loc_into(k, toast_title, sizeof toast_title); }
    if (toast_sub[0] == '@') { int i = 0; while ((k[i] = toast_sub[i + 1])) i++; loc_into(k, toast_sub, sizeof toast_sub); }
    toast_icon = icon;
    toast_on = 1;
    toast_t0 = 0;                       /* starts on the first frame that shows it */
    return 0;
}

static long now_ms(void) {
    struct timespec_ ts;
    clock_gettime(1, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void draw_text_col(void *font, float x, float y, const char *txt, float *col) {
    u32 str[1];
    gstr(str, txt);
    f_fontdraw(font, str, x, y, col, 1);
    f_strdtor(str);
}

static void draw_toast(void *client) {
    if (!toast_on || !f_fill || !f_sr || !f_guidata || !f_screenw) return;
    long now = now_ms();
    if (toast_drawn_at == now) return;          /* once per frame */
    toast_drawn_at = now;
    if (!toast_t0) toast_t0 = now;
    float t = (now - toast_t0) / 1000.f;
    if (t > 3.6f) {
        toast_on = 0;
        if (toast_tex_ok && f_texdtor) { f_texdtor(toast_tex); toast_tex_ok = 0; }
        return;
    }
    float k = t < 0.25f ? t / 0.25f : (t > 3.35f ? (3.6f - t) / 0.25f : 1.f);
    void *gd = f_guidata(client);
    int W = gd ? f_screenw(gd) : 320;
    float w = 170.f, h = 30.f;
    float x0 = (W - w) / 2.f, y0 = -h - 2.f + (h + 6.f) * k;
    void *sr = f_sr();
    float border[4] = {0.55f, 0.6f, 0.75f, 0.95f}, bg[4] = {0.09f, 0.1f, 0.15f, 0.92f};
    f_fill(sr, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, border);
    f_fill(sr, x0, y0, x0 + w, y0 + h, bg);
    float yellow[4] = {1.f, 1.f, 0.4f, 1.f}, white[4] = {1, 1, 1, 1};
    void *font = *(void **)((char *)client + 0x7c);
    float tx = x0 + 6;
    if (toast_icon > 0 && f_gettex && f_blit && f_textures) {
        if (!toast_tex_ok) {
            char path[64];
            snprintf(path, sizeof path, "nova/toast_icon_%d.png", toast_icon);
            u32 str[1];
            gstr(str, path);
            for (int i = 0; i < 16; i++) toast_tex[i] = 0;
            f_gettex(toast_tex, f_textures(client), str, 0);
            f_strdtor(str);
            toast_tex_ok = 1;
        }
        int rect[4] = {(int)(x0 + 4), (int)(y0 + 3), 24, 24};
        f_blit(sr, toast_tex, rect);
        tx = x0 + 32;
    }
    draw_text_col(font, tx, y0 + 5, toast_title, yellow);
    draw_text_col(font, tx, y0 + 17, toast_sub, white);
}

/* ---- cards: pack groups get their own vtable (render + setupPositions) ----
 * A card is a framed block: icon and two text lines on top, the group's
 * switches below, a gap before the next card. */
#define CARD_MAX 48
#define CARD_HEAD 40
#define CARD_GAP 6
typedef struct { void *g; int icon; char title[64], sub[64]; u32 tex[16]; int tex_ok; } Card;
static Card cards[CARD_MAX];
static int card_count;
static u32 grp_vt[38];
static int grp_vt_ok;
static void (*o_grp_render)(void *, void *, int, int);

static Card *card_of(void *g) {
    for (int i = 0; i < card_count; i++) if (cards[i].g == g) return &cards[i];
    return 0;
}

static void cards_free(void) {
    for (int i = 0; i < card_count; i++)
        if (cards[i].tex_ok && f_texdtor) { f_texdtor(cards[i].tex); cards[i].tex_ok = 0; }
    card_count = 0;
}

static void h_grp_setup(void *g) {
    char *G = g;
    Card *c = card_of(g);
    int x = *(int *)(G + 8), y = *(int *)(G + 0xc), w = *(int *)(G + 0x10);
    int top = c ? CARD_HEAD + 2 : 10, acc = top;
    u32 *b = *(u32 **)(G + 0x30), *e = *(u32 **)(G + 0x34);
    for (u32 *q = b; q < e; q += 2) {
        char *ch = (char *)q[0];
        if (!ch) continue;
        *(int *)(ch + 8) = x + 10;
        *(int *)(ch + 0xc) = y + acc;
        *(int *)(ch + 0x10) = c ? w - 18 : w - 5;
        void (**vt)(void *) = *(void (***)(void *))ch;
        vt[5](ch);
        acc += *(int *)(ch + 0x14) + 3;
    }
    *(int *)(G + 0x14) = c ? acc + 3 + CARD_GAP : (b == e ? 0 : acc);
}

static void h_grp_render(void *g, void *client, int mx, int my) {
    char *G = g;
    Card *c = card_of(g);
    if (!c || !f_fill || !f_sr) { o_grp_render(g, client, mx, my); return; }
    float x0 = *(int *)(G + 8) + 3, y0 = *(int *)(G + 0xc);
    float x1 = *(int *)(G + 8) + *(int *)(G + 0x10) - 2, y1 = y0 + *(int *)(G + 0x14) - CARD_GAP;
    void *sr = f_sr();
    float black[4] = {0, 0, 0, 1}, bg[4] = {0.22f, 0.22f, 0.25f, 0.88f}, hi[4] = {1, 1, 1, 0.16f},
          head[4] = {0.f, 0.f, 0.f, 0.22f}, line[4] = {0, 0, 0, 0.55f};
    f_fill(sr, x0 - 1, y0 - 1, x1 + 1, y1 + 1, black);          /* 1px black border */
    f_fill(sr, x0, y0, x1, y1, bg);
    f_fill(sr, x0, y0, x1, y0 + CARD_HEAD, head);               /* header band */
    f_fill(sr, x0, y0, x1, y0 + 1, hi);                         /* top highlight */
    f_fill(sr, x0, y0 + CARD_HEAD, x1, y0 + CARD_HEAD + 1, line);
    o_grp_render(g, client, mx, my);                            /* switches (title is empty) */
    float tx = x0 + 6;
    if (c->icon >= 0 && f_gettex && f_blit && f_textures) {
        if (!c->tex_ok) {
            char path[64];
            snprintf(path, sizeof path, "nova/pack_icon_%d_%d.png", pack_gen, c->icon);
            u32 str[1];
            gstr(str, path);
            for (int i = 0; i < 16; i++) c->tex[i] = 0;
            f_gettex(c->tex, f_textures(client), str, 0);
            f_strdtor(str);
            c->tex_ok = 1;
        }
        f_fill(sr, x0 + 5, y0 + 5, x0 + 35, y0 + 35, black);
        int rect[4] = {(int)x0 + 6, (int)y0 + 6, 28, 28};
        f_blit(sr, c->tex, rect);
        tx = x0 + 42;
    }
    void *font = *(void **)((char *)client + 0x7c);
    float white[4] = {1, 1, 1, 1}, grey[4] = {0.68f, 0.68f, 0.72f, 1};
    if (c->sub[0]) {
        draw_text_col(font, tx, y0 + 9, c->title, white);
        draw_text_col(font, tx, y0 + 22, c->sub, grey);
    } else draw_text_col(font, tx, y0 + 16, c->title, white);
}

static void *new_group(void *pane, const char *title);
static void *new_card(void *pane, const char *title, const char *sub, int icon) {
    void *g = new_group(pane, "");
    if (!grp_vt_ok || card_count >= CARD_MAX) return g;
    Card *c = &cards[card_count++];
    c->g = g; c->icon = icon; c->tex_ok = 0;
    int n = 0;
    while (title[n] && n < 63) { c->title[n] = title[n]; n++; }
    c->title[n] = 0;
    n = 0;
    while (sub && sub[n] && n < 63) { c->sub[n] = sub[n]; n++; }
    c->sub[n] = 0;
    *(u32 *)g = (u32)(grp_vt + 2);
    return g;
}


/* ---------------- auto sprint: the forward button sprints ---------------- */
static void *opt_sprint;
static void (*o_move_tick)(void *h, void *player);
static void h_move_tick(void *h, void *player) {
    u8 *b = h;
    void *opts = g_client ? f_getoptions(g_client) : 0;
    int on = opts && opt_sprint && f_getbool(opts, opt_sprint);
    u8 old = b[0x4f];
    if (on && b[0x51]) b[0x4f] = 1;     /* "button.up" held -> act as "button.sprint" */
    o_move_tick(h, player);
    b[0x4f] = old;
}

/* ---------------- crash report ------------------------------------------- */
struct ksigaction { void *handler; unsigned long mask; int flags; void *restorer; };
int sigaction(int, const struct ksigaction *, struct ksigaction *);
static char crash_path[256];
static u32 nova_base_ref;

static int hexw(char *p, u32 v) {
    const char *d = "0123456789abcdef";
    p[0] = '0'; p[1] = 'x';
    for (int i = 0; i < 8; i++) p[2 + i] = d[(v >> (28 - 4 * i)) & 15];
    return 10;
}
static int strw(char *p, const char *s) { int n = 0; while (s[n]) { p[n] = s[n]; n++; } return n; }

static void crash_handler(int sig, void *info, void *uc) {
    if (crash_path[0]) {
        char buf[1024];
        int n = 0;
        u32 *mc = (u32 *)((char *)uc + 20);           /* struct sigcontext */
        u32 pc = mc[18], lr = mc[17], sp = mc[16], fault = mc[20];
        n += strw(buf + n, "NOVA crash\nsignal ");
        buf[n++] = '0' + sig / 10; buf[n++] = '0' + sig % 10;
        n += strw(buf + n, "\nfault "); n += hexw(buf + n, fault);
        n += strw(buf + n, "\nmcpe  "); n += hexw(buf + n, mcbase);
        n += strw(buf + n, "\nnova  "); n += hexw(buf + n, nova_base_ref);
        n += strw(buf + n, "\npc    "); n += hexw(buf + n, pc);
        n += strw(buf + n, "  rel "); n += hexw(buf + n, pc - mcbase);
        n += strw(buf + n, "\nlr    "); n += hexw(buf + n, lr);
        n += strw(buf + n, "  rel "); n += hexw(buf + n, lr - mcbase);
        n += strw(buf + n, "\nstack:");
        u32 *s = (u32 *)sp;
        int found = 0;
        for (int i = 0; i < 512 && found < 24 && n < 900; i++) {
            u32 v = s[i];
            if ((v & 1) && v - mcbase < 0x6ea000) { n += strw(buf + n, "\n  "); n += hexw(buf + n, v - mcbase); found++; }
            else if ((v & 1) && v - nova_base_ref < 0x10000) { n += strw(buf + n, "\n  nova+"); n += hexw(buf + n, v - nova_base_ref); found++; }
        }
        buf[n++] = '\n';
        int fd = open(crash_path, 01 | 0100 | 01000, 0644);
        if (fd >= 0) { write(fd, buf, n); close(fd); }
    }
    struct ksigaction dfl = {0, 0, 0, 0};
    sigaction(sig, &dfl, 0);               /* default action on return -> real crash */
}

int Java_com_nova_client_NovaActivity_nativeCrashFile(void **env, void *cls, void *path) {
    jstr_copy(env, path, crash_path, sizeof crash_path);
    struct ksigaction sa = {(void *)crash_handler, 0, 4 /* SA_SIGINFO */ | 0x40000000 /* SA_RESTART */, 0};
    int sigs[] = {11, 7, 4, 6, 8};
    for (int i = 0; i < 5; i++) sigaction(sigs[i], &sa, 0);
    return 0;
}

/* ---------------- anisotropic filtering (distant ground no longer blurs) -----
 * glTexParameteri is reached through the game's GOT; mipmapped textures also get
 * GL_TEXTURE_MAX_ANISOTROPY_EXT when the GPU has the extension. */
#define GOT_GLTEXPARAMETERI 0x75aaf4
static void (*o_texparami)(unsigned, unsigned, int);
static void (*f_texparamf)(unsigned, unsigned, float);
static void (*f_getfloatv)(unsigned, float *);
static const char *(*f_glgetstring)(unsigned);
static float max_aniso = -1;
static int strstr_(const char *h, const char *n) {
    for (; *h; h++) {
        int i = 0;
        while (n[i] && h[i] == n[i]) i++;
        if (!n[i]) return 1;
    }
    return 0;
}
static void h_texparami(unsigned target, unsigned pname, int param) {
    o_texparami(target, pname, param);
    if (pname != 0x2801 || param < 0x2700 || param > 0x2703) return;     /* MIN_FILTER with mipmaps */
    if (max_aniso < 0) {
        max_aniso = 0;
        const char *ext = f_glgetstring ? f_glgetstring(0x1F03) : 0;
        if (ext && strstr_(ext, "texture_filter_anisotropic")) f_getfloatv(0x84FF, &max_aniso);
        LOGE("anisotropic filtering x%d", (int)max_aniso);
    }
    if (max_aniso > 1.f) f_texparamf(target, 0x84FE, max_aniso > 8.f ? 8.f : max_aniso);
}
static void aniso_init(u32 base) {
    void *gl = dlopen("libGLESv2.so", 0x4 | 0x2);
    if (!gl) return;
    f_texparamf = dlsym(gl, "glTexParameterf");
    f_getfloatv = dlsym(gl, "glGetFloatv");
    f_glgetstring = dlsym(gl, "glGetString");
    if (!f_texparamf || !f_getfloatv || !f_glgetstring) return;
    u32 *slot = (u32 *)(base + GOT_GLTEXPARAMETERI);
    u32 page = (u32)slot & ~4095u;
    if (mprotect((void *)page, 4096, 3) != 0) { LOGE("aniso: mprotect failed"); return; }
    o_texparami = (void *)*slot;
    *slot = (u32)h_texparami;
    mprotect((void *)page, 4096, 1);
}

static void menus_init(void *mc, u32 base) {
    if (!stage2_ok) return;
    void *gs = dlopen("libgnustl_shared.so", 0x4 | 0x2);
    f_strassign = gs ? dlsym(gs, "_ZNSs6assignEPKc") : 0;
    f_i18n = dlsym(mc, "_ZN4I18n3getERKSs");
    f_pushopts = dlsym(mc, "_ZN13ScreenChooser17pushOptionsScreenEbbRKSsi");
    f_getchooser = dlsym(mc, "_ZNK15MinecraftClient16getScreenChooserEv");
    f_ctext = dlsym(mc, "_ZN12OptionsGroup13createTextBoxERKN7Options6OptionER15MinecraftClientRKSs");
    f_closescreen = dlsym(mc, "_ZN13OptionsScreen11closeScreenEv");
    f_campos = dlsym(mc, "_ZNK13LevelRenderer22getCameraPosFromEntityEfRK6Entity");
    f_tbegin = dlsym(mc, "_ZN11Tessellator5beginEN3mce13PrimitiveModeEi");
    f_tvertex = dlsym(mc, "_ZN11Tessellator6vertexEfff");
    f_tdraw = dlsym(mc, "_ZN11Tessellator4drawERKN3mce11MaterialPtrE");
    f_matptr = dlsym(mc, "_ZN3mce11MaterialPtrC1ERNS_19RenderMaterialGroupERKSs");
    f_setcolor = dlsym(mc, "_ZN11ShaderColor8setColorERK5Color");
    g_tess = dlsym(mc, "_ZN11Tessellator8instanceE");
    g_switchable = dlsym(mc, "_ZN3mce19RenderMaterialGroup10switchableE");
    g_shadercolor = dlsym(mc, "currentShaderColor");
    opt_chunks = dlsym(mc, "_ZN7Options6Option25VR_USES_RED_FLASH_FOR_HITE");
    opt_sprint = dlsym(mc, "_ZN7Options6Option24VR_RIGHTSTICK_GAZEADJUSTE");
    f_sr = dlsym(mc, "_ZN14ScreenRenderer9singletonEv");
    f_fill = dlsym(mc, "_ZNK14ScreenRenderer4fillEffffRK5Color");
    f_guidata = dlsym(mc, "_ZN15MinecraftClient10getGuiDataEv");
    f_screenw = dlsym(mc, "_ZNK7GuiData14getScreenWidthEv");
    f_gettex = dlsym(mc, "_ZN3mce12TextureGroup10getTextureERKSs15TextureLocation");
    f_blit = dlsym(mc, "_ZN14ScreenRenderer4blitERKN3mce10TexturePtrERK12IntRectangle");
    f_texdtor = dlsym(mc, "_ZN3mce10TexturePtrD1Ev");
    f_textures = dlsym(mc, "_ZNK15MinecraftClient11getTexturesEv");
    nova_base_ref = (u32)menus_init & ~0xfffffu;
    u32 vt_move = (u32)dlsym(mc, "_ZTV16MoveInputHandler");
    if (vt_move && opt_sprint) patch_slot(vt_move, 2, h_move_tick, (void **)&o_move_tick);
    if (!f_strassign || !f_pushopts || !f_getchooser || !f_ctext || !f_closescreen) { LOGE("menus: symbols missing"); return; }
    GATE(OPT_GETBOOL, h_getbool, o_getbool);
    GATE(OPT_SETBOOL, h_setbool, o_setbool);
    GATE(OPT_TOGGLE, h_toggle, o_toggle);
    GATE(OPT_GETINT, h_getint, o_getint);
    GATE(OPT_SETINT, h_setint, o_setint);
    GATE(OPT_GETVALUES, h_getvalues, o_getvalues);
    GATE(OPT_GETSTRING, h_getstring, o_getstring);
    GATE(OPT_SETSTRING, h_setstring, o_setstring);
    if (f_campos && f_tbegin && f_tvertex && f_tdraw && f_matptr && f_setcolor && g_tess && g_switchable && g_shadercolor && opt_chunks)
        GATE(NAMETAGS, h_nametags, o_nametags);
    else LOGE("chunk borders: symbols missing");
    u32 vt_opt = (u32)dlsym(mc, "_ZTV13OptionsScreen");
    u32 vt_cl = (u32)dlsym(mc, "_ZTV15MinecraftClient");
    if (vt_opt) {
        patch_slot(vt_opt, 0, h_opt_d1, (void **)&o_opt_d1);
        patch_slot(vt_opt, 1, h_opt_d0, (void **)&o_opt_d0);
    }
    if (vt_cl) patch_slot(vt_cl, 18, h_client_update, (void **)&o_client_update);
    u32 vt_grp = (u32)dlsym(mc, "_ZTV12OptionsGroup");
    if (vt_grp) {
        for (int i = 0; i < 38; i++) grp_vt[i] = ((u32 *)vt_grp)[i];
        o_grp_render = (void *)grp_vt[2 + 3];
        grp_vt[2 + 3] = (u32)h_grp_render;
        grp_vt[2 + 5] = (u32)h_grp_setup;
        grp_vt_ok = 1;
    }
    aniso_init(base);
    menus_ok = 1;
}
