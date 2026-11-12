/* nui_game.inc - NUI (nui.inc) in the game: the 1.1.5 start screen drawn by the 0.14 renderer.
 *
 * Files:    "ui115/ui/*.json" in the APK (tools/ui115_assets.py), read with AppPlatform::readAssetFile (Java).
 * Pictures: "ui115/textures/gui/...png" under assets/images, loaded with TextureGroup::getTexture.
 * Drawing:  Tessellator quads with the "ui_texture_and_color" screen material (vertex colour), Font::drawTransformed.
 * Strings:  the game's I18n (the 1.1.5 strings were added to the 0.14 .lang files at build time).
 * Screen:   the game's own start screen (StartMenuScreenController) stays: it still draws the panorama behind, the
 *           paper doll, the name tag and the splash (their renderers are put where the 1.1.5 layout has them) and
 *           does what its buttons do. Its other controls are hidden; NUI draws the 1.1.5 screen and takes the touches,
 *           and a pressed 1.1.5 button is passed to the game's controller as its own button event.
 * If anything is missing (files, symbols) NUI stays off and the old screen is shown as it was. */
void free(void *);
#include "nui.inc"

typedef struct { char path[100]; int w, h; char loaded; u32 tex[16]; } NgTex;
#define NG_TEX 192
static NgTex *ng_tex;
static int ng_ntex;
static void **ng_platform;                                   /* AppPlatform::mSingleton */
static void (*ng_tbegin)(void *t, int n);
static void (*ng_tcolor)(void *t, float r, float g, float b, float a);
static void (*ng_tvuv)(void *t, float x, float y, float z, float u, float v);
static void (*ng_tdraw)(void *t, void *mat, void *tex);
static void *(*ng_getmat)(void *sr, int which);
static void (*ng_fontT)(void *font, void *str, float x, float y, const float *col, float angle, float scale, int centered, float maxw);
static float (*ng_linelen)(void *font, void *str, float scale, int b);
static short (*ng_nameid)(void *reg, void *str);
static void (*ng_launch)(void *plat, void *str);
static u32 ng_vt_start;
static int ng_state;                                         /* 0 not tried, 1 ready, -1 failed */
static NCtl *ng_root;
static float ng_w, ng_h;
static long ng_drawn_at;
static char *ng_view;                                        /* the start screen being drawn */
static volatile int ng_pending;                              /* a pressed button, done on the render thread */
static char ng_pending_name[48];
static int ng_signed = -1;
static char ng_tag[48];
static long ng_state_at;
static float ng_doll[4], ng_splash[4];
static int ng_has_doll, ng_has_splash;

/* --------------------------------------------------------------------------------------------- platform ---- */
static char *ng_read(const char *path, int *len) {             /* a file of the APK (or of Java's virtual files) */
    if (!ng_platform || !*ng_platform) return 0;
    void *plat = *ng_platform;
    void (*rd)(u32 *, void *, u32 *) = (void (*)(u32 *, void *, u32 *))(*(u32 **)plat)[46];
    u32 s[1], out[1] = {0};
    gstr(s, path);
    rd(out, plat, s);
    f_strdtor(s);
    const char *d = sdata(out);
    int n = d ? slen(d) : 0;
    char *r = 0;
    if (n > 0) {
        r = malloc(n + 1);
        if (r) { memcpy(r, d, n); r[n] = 0; }
    }
    if (d) f_strdtor(out);
    if (len) *len = r ? n : 0;
    return r;
}
static int ng_cat(char *dst, int cap, const char *a, const char *b, const char *c) {
    int n = 0;
    const char *parts[3] = {a, b, c};
    for (int i = 0; i < 3; i++) for (const char *p = parts[i]; p && *p && n < cap - 1; p++) dst[n++] = *p;
    dst[n] = 0;
    return n;
}
static const char *ng_file(const char *path, int *len) {
    char full[160];
    ng_cat(full, sizeof full, "ui115/", path, 0);
    return ng_read(full, len);
}
static int ng_texture(const char *path, int *w, int *h) {
    for (int i = 0; i < ng_ntex; i++)
        if (streq(ng_tex[i].path, path)) { *w = ng_tex[i].w; *h = ng_tex[i].h; return ng_tex[i].w ? i + 1 : 0; }
    if (ng_ntex >= NG_TEX) return 0;
    NgTex *t = &ng_tex[ng_ntex++];
    memset(t, 0, sizeof *t);
    int k = 0;
    while (path[k] && k < (int)sizeof t->path - 1) { t->path[k] = path[k]; k++; }
    char full[160];
    ng_cat(full, sizeof full, "images/ui115/", path, ".png");
    int n = 0;
    unsigned char *d = (unsigned char *)ng_read(full, &n);
    if (d && n > 24 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
        t->w = d[16] << 24 | d[17] << 16 | d[18] << 8 | d[19];
        t->h = d[20] << 24 | d[21] << 16 | d[22] << 8 | d[23];
    }
    if (d) free(d);
    *w = t->w; *h = t->h;
    return t->w ? ng_ntex : 0;
}
/* quads go out in batches: one draw per run of the same picture */
static int ng_batch_tex, ng_batch_n;
static void ng_flush(void) {
    if (ng_batch_tex && ng_batch_n) {
        NgTex *t = &ng_tex[ng_batch_tex - 1];
        ng_tdraw(g_tess, ng_getmat(f_sr(), 3), t->tex);    /* 3 = ui_texture_and_color */
    }
    ng_batch_tex = 0; ng_batch_n = 0;
}
static void ng_image(int id, float x, float y, float w, float h, float u0, float v0, float u1, float v1, const float *c) {
    if (id <= 0 || id > ng_ntex || !g_client) return;
    NgTex *t = &ng_tex[id - 1];
    if (!t->loaded) {
        char full[160];
        ng_cat(full, sizeof full, "ui115/", t->path, ".png");
        u32 st[1];
        gstr(st, full);
        f_gettex(t->tex, f_textures(g_client), st, 0);
        f_strdtor(st);
        t->loaded = 1;
    }
    if (ng_batch_tex != id || ng_batch_n >= 1000) { ng_flush(); ng_tbegin(g_tess, 1024); ng_batch_tex = id; }
    ng_tcolor(g_tess, c[0], c[1], c[2], c[3]);
    float x1 = x + w, y1 = y + h;
    ng_tvuv(g_tess, x, y, 0.f, u0, v0);
    ng_tvuv(g_tess, x1, y, 0.f, u1, v0);
    ng_tvuv(g_tess, x1, y1, 0.f, u1, v1);
    ng_tvuv(g_tess, x, y1, 0.f, u0, v1);
    ng_batch_n += 4;
}
static void ng_text(const char *s, float x, float y, float scale, const float *c, int shadow) {
    ng_flush();
    void *font = g_client ? *(void **)((char *)g_client + 0x7c) : 0;
    if (!font || !s || !*s) return;
    u32 st[1];
    gstr(st, s);
    if (shadow) {
        float sc[4] = {c[0] * 0.25f, c[1] * 0.25f, c[2] * 0.25f, c[3]};
        ng_fontT(font, st, x + scale, y + scale, sc, 0.f, scale, 0, 3.4e38f);
    }
    ng_fontT(font, st, x, y, c, 0.f, scale, 0, 3.4e38f);
    f_strdtor(st);
}
static float ng_text_width(const char *s, float scale) {
    void *font = g_client ? *(void **)((char *)g_client + 0x7c) : 0;
    if (!font || !s || !*s) return 0;
    u32 st[1];
    gstr(st, s);
    float w = ng_linelen(font, st, 1.f, 0);
    f_strdtor(st);
    return w * scale;
}
static char ng_locbuf[8][256];
static int ng_locn;
static const char *ng_loc(const char *key) {
    if (ng_signed == 1 && streq(key, "xbox.signin") && ng_tag[0]) return ng_tag;   /* the sign-in button shows who it is */
    if (streq(key, "menu.store")) key = "nova.storage.title";                      /* "store" opens the NOVA storage */
    char *b = ng_locbuf[ng_locn++ & 7];
    loc_into(key, b, sizeof ng_locbuf[0]);
    return b;
}
static void ng_custom(const char *r, float x, float y, float w, float h, void *ctrl) {
    if (!r) return;
    if (streq(r, "paper_doll_renderer")) { ng_doll[0] = x; ng_doll[1] = y; ng_doll[2] = w; ng_doll[3] = h; ng_has_doll = 1; }
    else if (streq(r, "splash_text_renderer")) { ng_splash[0] = x; ng_splash[1] = y; ng_splash[2] = w; ng_splash[3] = h; ng_has_splash = 1; }
}

/* ------------------------------------------------------------------------------------------- controller ---- */
static JV ng_jstr_version, ng_jstr_tag;
static JV *ng_get(void *self, const char *b, NCtl *c) {
    if (streq(b, "#version")) return &ng_jstr_version;
    if (streq(b, "#development_version")) return &ng_jstr_tag;   /* ignored ($is_publish) */
    if (streq(b, "#sign_in_visible")) return &nui_jtrue;
    if (streq(b, "#playername")) return &ng_jstr_tag;
    static const char *off[] = {"#gamepad_helper_visible", "#realms_pending_invite_visible", "#xbl_signin_visible",
        "#new_offers", "#achievements_visible", "#is_signed_in", "#realms_invite_visible", "#signingin_visible",
        "#realms_notification_visible", "#feedback_visible", "#trial_game_mode", 0};
    for (int i = 0; off[i]; i++) if (streq(b, off[i])) return &nui_jfalse;
    return 0;
}
static void ng_button(void *self, const char *name, NCtl *c) {
    if (ng_pending) return;
    int n = 0;
    while (name[n] && n < (int)sizeof ng_pending_name - 1) { ng_pending_name[n] = name[n]; n++; }
    ng_pending_name[n] = 0;
    ng_pending = 1;
}
static NuiController ng_ctl = {ng_get, ng_button, 0, 0};

static void ng_uri(const char *uri) {
    if (!ng_launch || !ng_platform || !*ng_platform) return;
    u32 s[1];
    gstr(s, uri);
    ng_launch(*ng_platform, s);
    f_strdtor(s);
}
/* the game's controller does what its own button of that name did */
static void ng_fire(char *view, const char *name) {
    char *ctl = view ? *(char **)(view + 0x4c) : 0;
    void *reg = ctl ? *(void **)(ctl + 0x14) : 0;
    if (!ctl || !reg || !ng_nameid) return;
    u32 s[1];
    gstr(s, name);
    short id = ng_nameid(reg, s);
    f_strdtor(s);
    /* ScreenEvent {type 1 = button, +4 id, +8 previous state Down, +9 state Up, +0xc PropertyBag*} by value */
    u32 (*handle)(void *, u32, u32, u32, u32) = (u32 (*)(void *, u32, u32, u32, u32))(*(u32 **)ctl)[4];
    LOG("nui: button %s -> id %d", name, id);
    handle(ctl, 1, (u32)(unsigned short)id, 0x0001, 0);
}
static void ng_do_pending(char *view) {
    if (!ng_pending) return;
    const char *b = ng_pending_name;
    if (streq(b, "button.menu_settings")) ng_fire(view, "button.menu_options");
    else if (streq(b, "button.menu_store")) ng_uri("nova://packs");
    else if (streq(b, "button.xbl_signin")) ng_uri("nova://account");
    else if (streq(b, "button.menu_play") || streq(b, "button.menu_skins") || streq(b, "button.menu_exit") ||
             streq(b, "button.menu_achievements") || streq(b, "button.menu_buy_game"))
        ng_fire(view, b);
    else LOG("nui: button %s has no action", b);
    ng_pending = 0;
}

/* ---------------------------------------------------------------------------------------------- the screen ---- */
static void ng_set_str(JV *v, const char *s) {
    int n = nui_len(s);
    char *d = nui_alloc_keep(n + 1);
    memcpy(d, s, n);
    v->t = JN_STR; v->s = d;
}
static void ng_poll_state(void) {
    long now = now_ms();
    if (ng_signed >= 0 && now - ng_state_at < 1000) return;
    ng_state_at = now;
    int n = 0;
    char *d = ng_read("nova/state.txt", &n);
    if (!d) return;
    int signed_in = 0;
    char tag[48]; tag[0] = 0;
    for (char *p = d; *p; ) {
        char *e = p;
        while (*e && *e != '\n') e++;
        if (e - p > 7 && !memcmp(p, "signed=", 7)) signed_in = p[7] == '1';
        if (e - p > 4 && !memcmp(p, "tag=", 4)) {
            int k = 0;
            for (char *q = p + 4; q < e && k < 47; q++) tag[k++] = *q;
            tag[k] = 0;
        }
        p = *e ? e + 1 : e;
    }
    free(d);
    if (signed_in != ng_signed || !streq(tag, ng_tag)) {
        ng_signed = signed_in;
        for (int i = 0; i < 48; i++) ng_tag[i] = tag[i];
        if (ng_root) { ng_w = 0; }                          /* text changed: lay the screen out again */
    }
}
static int ng_load(void) {
    if (ng_state) return ng_state > 0;
    ng_state = -1;
    if (!ng_tbegin || !ng_tcolor || !ng_tvuv || !ng_tdraw || !ng_getmat || !ng_fontT || !ng_linelen || !ng_nameid ||
        !g_tess || !f_gettex || !f_textures || !f_sr || !ng_platform || !ng_vt_start) { LOGE("nui: symbols missing"); return 0; }
    ng_tex = malloc(NG_TEX * sizeof(NgTex));
    if (!ng_tex) return 0;
    nui_p.file = ng_file; nui_p.texture = ng_texture; nui_p.image = ng_image; nui_p.text = ng_text;
    nui_p.text_width = ng_text_width; nui_p.loc = ng_loc; nui_p.custom = ng_custom;
    nui_init_globals();
    int n = nui_load_all();
    char ver[64];
    loc_into("nova.version", ver, sizeof ver);
    char v2[96];
    ng_cat(v2, sizeof v2, "v1.1.5 ", ver, 0);
    ng_set_str(&ng_jstr_version, v2);
    ng_set_str(&ng_jstr_tag, "");
    nui_ctl = &ng_ctl;
    ng_root = n > 0 ? nui_screen("start.start_screen") : 0;
    LOG("nui: %d files, start screen %s", n, ng_root ? "ok" : "missing");
    if (!ng_root) return 0;
    nui_bind(ng_root);
    ng_state = 1;
    return 1;
}

/* the old screen: keep the doll, its name tag and the splash, hide the rest */
static int ng_keep_walk(char *c, int depth, int *changed) {
    if (!c || depth > 30) return 0;
    const char *nm = sdata(c + 0xc);
    if (nm && (streq(nm, "paper_doll") || streq(nm, "paper_doll_name_tag") || streq(nm, "splash_text"))) return 1;
    int keep = 0;
    char **it = *(char ***)(c + 0x40), **end = *(char ***)(c + 0x44);
    for (char **k = it; k && k < end; k += 2) {
        if (!k[0]) continue;
        if (ng_keep_walk(k[0], depth + 1, changed)) keep = 1;
        else if (k[0][0x30] & 4) { k[0][0x30] &= ~4; *changed = 1; }
    }
    return keep;
}
static int ng_is_start(char *view) {
    char *ctl = view ? *(char **)(view + 0x4c) : 0;
    return ctl && *(u32 *)ctl == ng_vt_start + 8;
}
/* called from ScreenView::setupAndRender (creative.inc hook) before the game draws the screen */
static void ng_view_frame(char *view) {
    if (!ng_is_start(view) || !g_client || !f_guidata || !f_screenw || !f_screenh) return;
    if (!ng_load()) return;
    void *gd = f_guidata(g_client);
    if (!gd) return;
    float W = (float)f_screenw(gd), H = (float)f_screenh(gd);
    ng_view = view;
    ng_do_pending(view);
    ng_poll_state();
    char *tree = *(char **)(view + 0x50);
    char *root = tree ? *(char **)tree : 0;
    int changed = 0;
    ng_keep_walk(root, 0, &changed);
    if (changed && tree) *(u32 *)(tree + 8) |= 7;              /* VisualTree::setDirty */
    nui_frame_begin();
    if (W != ng_w || H != ng_h) {
        nui_layout(ng_root, 0, 0, W, H);
        nui_layout_kids(ng_root);
        ng_w = W; ng_h = H;
    }
    ng_has_doll = ng_has_splash = 0;
    ng_batch_tex = ng_batch_n = 0;
    nui_render(ng_root);
    ng_flush();
    nui_frame_end();
    float white[4] = {1, 1, 1, 1};
    if (f_setcolor && g_shadercolor) f_setcolor(g_shadercolor, white);
    ng_drawn_at = now_ms();
}
/* touches while the 1.1.5 start screen is up go to it (Multitouch::feed: w[0] button, w[1] state, w[2] x, w[3] y) */
static int ng_touch(u32 *w, float scale) {
    if (ng_state <= 0 || !ng_root || !ng_drawn_at || now_ms() - ng_drawn_at > 300) return 0;
    float x = (short)w[2] / scale, y = (short)w[3] / scale;
    int btn = w[0] & 0xff, st = w[1] & 0xff;
    int down = btn == 0 ? 2 : st == 1 ? 1 : 0;
    nui_frame_begin();
    nui_touch(ng_root, x, y, down);
    nui_frame_end();
    return 1;
}

/* the renderers of the old screen, put where the 1.1.5 layout has them */
static void (*o_ng_doll)(void *, void *, void **, int), (*o_ng_tag)(void *, void *, void **, int), (*o_ng_splash)(void *, void *, void **, int);
static int ng_owns(void **ctrl) {
    return ng_state > 0 && ng_view && ctrl && *ctrl && ng_drawn_at && now_ms() - ng_drawn_at < 300;
}
/* the doll: drawn from its neck (control position + size / 2), one model pixel = size.x / 2; 32 pixels tall */
static float ng_doll_s, ng_doll_cx, ng_doll_neck;
static void ng_doll_place(void) {
    float h = ng_doll[3] > 0 ? ng_doll[3] : 80.f;
    float s = h * 0.9f / 32.f;
    ng_doll_s = s;
    ng_doll_cx = ng_doll[0] + ng_doll[2] / 2;
    ng_doll_neck = ng_doll[1] + (h - 32.f * s) / 2 + 8.f * s;
}
static void h_ng_doll(void *self, void *client, void **ctrl, int pass) {
    if (ng_owns(ctrl) && ng_has_doll) {
        char *c = *(char **)ctrl;
        float *pos = (float *)(c + 0x10), *size = (float *)(c + 0x18);
        ng_doll_place();
        size[0] = 2.f * ng_doll_s; size[1] = 0.f;
        pos[0] = ng_doll_cx - ng_doll_s; pos[1] = ng_doll_neck;
    }
    o_ng_doll(self, client, ctrl, pass);
}
static void h_ng_tag(void *self, void *client, void **ctrl, int pass) {
    if (ng_owns(ctrl) && ng_has_doll) {
        char *c = *(char **)ctrl;
        float *pos = (float *)(c + 0x10), *size = (float *)(c + 0x18);
        ng_doll_place();
        size[0] = 48.f; size[1] = 0.f;
        pos[0] = ng_doll_cx - 24.f; pos[1] = ng_doll_neck - 8.f * ng_doll_s - 9.f;
    }
    o_ng_tag(self, client, ctrl, pass);
}
static void h_ng_splash(void *self, void *client, void **ctrl, int pass) {
    if (ng_owns(ctrl) && ng_has_splash) {
        char *c = *(char **)ctrl;
        float *pos = (float *)(c + 0x10), *size = (float *)(c + 0x18);
        pos[0] = ng_splash[0]; pos[1] = ng_splash[1]; size[0] = ng_splash[2]; size[1] = ng_splash[3];
    }
    o_ng_splash(self, client, ctrl, pass);
}

static void nui_game_init(void *mc) {
    ng_platform = dlsym(mc, "_ZN11AppPlatform10mSingletonE");
    ng_tbegin = dlsym(mc, "_ZN11Tessellator5beginEi");
    ng_tcolor = dlsym(mc, "_ZN11Tessellator5colorEffff");
    ng_tvuv = dlsym(mc, "_ZN11Tessellator8vertexUVEfffff");
    ng_tdraw = dlsym(mc, "_ZN11Tessellator4drawERKN3mce11MaterialPtrERKNS0_10TexturePtrE");
    ng_getmat = dlsym(mc, "_ZNK14ScreenRenderer11getMaterialENS_14ScreenMaterialE");
    ng_fontT = dlsym(mc, "_ZN4Font15drawTransformedERKSsffRK5Colorffbf");
    ng_linelen = dlsym(mc, "_ZNK4Font13getLineLengthERKSsfb");
    ng_nameid = dlsym(mc, "_ZN12NameRegistry9getNameIdERKSs");
    ng_launch = dlsym(mc, "_ZN19AppPlatform_android9launchUriERKSs");
    ng_vt_start = (u32)dlsym(mc, "_ZTV25StartMenuScreenController");
    u32 vd = (u32)dlsym(mc, "_ZTV17PaperDollRenderer"), vn = (u32)dlsym(mc, "_ZTV15NameTagRenderer");
    u32 vs = (u32)dlsym(mc, "_ZTV18SplashTextRenderer");
    if (vd) patch_slot(vd, 5, h_ng_doll, (void **)&o_ng_doll);
    if (vn) patch_slot(vn, 5, h_ng_tag, (void **)&o_ng_tag);
    if (vs) patch_slot(vs, 5, h_ng_splash, (void **)&o_ng_splash);
    LOG("nui: %s", ng_tbegin && ng_tvuv && ng_tdraw && ng_fontT && ng_vt_start && vd && vn && vs ? "ready" : "symbols missing");
}
