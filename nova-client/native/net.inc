/* net.inc - playing with friends: real buttons in NOVA screens, the "Network" button of the create-world screen,
 * joining a friend's world, the invite banner, and what Java needs to know about the world this phone hosts.
 * Included after hook.inc (C hooks: nh_hook_c).
 */

static void (*f_tb_ctor)(void *, int, void *str, void *client, int, int);
static int *gel(void *e) { return (int *)((char *)e + 8); }    /* GuiElement x, y, w, h */
/* virtual call: the object's vptr points at vtable + 8, so slot n is vptr[n] (as patch_slot counts) */
#define VSLOT(obj, n) ((*(void ***)(obj))[n])

/* a Touch::TButton as the game builds them (DeathScreen, ConfirmScreen): the ctor with a client calls init() */
static void *tb_new(void *client, const char *text, int id) {
    if (!f_tb_ctor || !client) return 0;
    void *b = f_new(0x74);
    memset(b, 0, 0x74);
    u32 s[1];
    gstr(s, text);
    f_tb_ctor(b, id, s, client, 0, 0x7fffffff);
    f_strdtor(s);
    return b;
}
static void tb_msg(void *b, const char *text) {
    u32 s[1];
    gstr(s, text);
    ((void (*)(void *, void *))VSLOT(b, 31))(b, s);            /* Button::setMsg */
    f_strdtor(s);
}
static void tb_render(void *b, void *client, int mx, int my) {
    ((void (*)(void *, void *, int, int))VSLOT(b, 3))(b, client, mx, my);
}

/* ---------------- NOVA buttons -----------------------------------------------------------------------------
 * A NOVA option with nopt_button set is created as a toggle (OptionsGroup::createToggle -> OptionsItem holding an
 * OptionButton). The row's OptionsItem lays the OptionButton over the whole row (its caption is empty), and the
 * OptionButton is drawn as a TButton with the option's text. Clicks still go OptionButton -> Options::toggle ->
 * h_toggle -> nopt_on_change. */
static void (*o_item_setup)(void *);
static void (*o_ob_render)(void *, void *, int, int);
static void *nb_tb;

static int nova_button_opt(void *opt) { return opt && is_nopt(opt) && nopt_button[nidx(opt)]; }

static void h_item_setup(void *item) {
    void *opt = *(void **)((char *)item + 0x44);               /* OptionsItem: the option it shows */
    u32 *b = *(u32 **)((char *)item + 0x30), *e = *(u32 **)((char *)item + 0x34);
    int btn = nova_button_opt(opt) && b != e && b[0];
    if (btn) gel((void *)b[0])[3] = 22;                         /* a button is a bit taller than a switch */
    o_item_setup(item);
    if (!btn) return;
    int *g = gel(item), *c = gel((void *)b[0]);
    c[0] = g[0];
    c[2] = g[2] - 15;                                           /* same right edge as the switches */
}

static void h_ob_render(void *btn, void *client, int mx, int my) {
    void *opt = *(void **)((char *)btn + 0xb8);
    if (!nova_button_opt(opt)) { o_ob_render(btn, client, mx, my); return; }
    if (!nb_tb) nb_tb = tb_new(client, "", 0);
    if (!nb_tb) { o_ob_render(btn, client, mx, my); return; }
    int *g = gel(btn), *t = gel(nb_tb);
    t[0] = g[0]; t[1] = g[1]; t[2] = g[2]; t[3] = g[3];
    tb_msg(nb_tb, nopt_text[nidx(opt)]);
    tb_render(nb_tb, client, mx, my);
}

/* ---------------- create-world screen: "Network" header button, next to "Advanced" ---------------------------
 * Built like the game builds "Advanced" (Touch::HeaderButton in CreateWorldScreen::init) and put into the screen's
 * button list after every setupPositions (which clears that list). Pressed: Java opens the network settings. */
static void (*f_hb_ctor)(void *, int, void *client, void *str, int, int);
static void (*f_cw_name)(void *out, void *scr);
static void (*o_cw_setup)(void *), (*o_cw_click)(void *, void *), (*o_cw_d0)(void *), (*o_cw_d1)(void *);
static void *cw_scr, *cw_btn;
static u32 *cw_cb;
#define SP_HEADERBUTTON_VT 0x6eca78     /* _Sp_counted_deleter<Touch::HeaderButton*> (the game's own control block) */
#define VEC_SPBUTTON_EMPLACE 0x326614   /* vector<shared_ptr<Button>>::emplace_back(shared_ptr&&) */
#define CW_ADVANCED 0xe4                /* CreateWorldScreen: shared_ptr<HeaderButton> "Advanced" */
#define CW_OTHER_HEADER 0xfc

static void cw_forget(void *scr) {
    if (!scr || scr != cw_scr) return;
    if (cw_cb) f_release(cw_cb);                                /* our own reference */
    cw_cb = 0; cw_btn = 0; cw_scr = 0;
}
static void h_cw_d0(void *scr) { cw_forget(scr); o_cw_d0(scr); }
static void h_cw_d1(void *scr) { cw_forget(scr); o_cw_d1(scr); }

static void h_cw_setup(void *scr) {
    o_cw_setup(scr);
    char *S = scr;
    void *adv = *(void **)(S + CW_ADVANCED);
    void *client = *(void **)(S + 0x14);
    if (!adv || !client || !f_hb_ctor) return;
    if (cw_scr != scr) {
        cw_forget(cw_scr);
        char cap[48];
        loc_into("nova.net.button", cap, sizeof cap);
        void *b = f_new(0x80);
        memset(b, 0, 0x80);
        u32 str[1];
        gstr(str, cap);
        f_hb_ctor(b, 9901, client, str, 1, 1);
        f_strdtor(str);
        cw_cb = f_new(0x14);
        cw_cb[0] = mcbase + SP_HEADERBUTTON_VT + 8; cw_cb[1] = 1; cw_cb[2] = 1; cw_cb[3] = (u32)b; cw_cb[4] = 0;
        cw_btn = b; cw_scr = scr;
    }
    int *a = gel(adv), *g = gel(cw_btn);
    int left = a[0];
    void *other = *(void **)(S + CW_OTHER_HEADER);
    if (other) { int *o = gel(other); if (o[1] < a[1] + a[3] && o[0] < left && o[0] > a[0] - 200) left = o[0]; }
    g[0] = left - 4 - g[2];
    g[1] = a[1];
    g[3] = a[3];
    cw_cb[1]++;                                                  /* the list's reference */
    u32 sp[2] = {(u32)cw_btn, (u32)cw_cb};
    ((void (*)(void *, void *))((mcbase + VEC_SPBUTTON_EMPLACE) | 1))(S + 0x18, sp);
}

static void h_cw_click(void *scr, void *btn) {
    if (btn && btn == cw_btn) {
        u32 name[1] = {0};
        if (f_cw_name) f_cw_name(name, scr);
        int p = append(nova_result, 0, sizeof nova_result, "W|");
        if (name[0]) { append(nova_result, p, sizeof nova_result, sdata(name)); f_strdtor(name); }
        nova_result_ready = 1;
        return;
    }
    o_cw_click(scr, btn);
}

/* ---------------- joining a friend's world (what MinecraftClient::joinLiveGame does) --------------------- */
static void (*f_pushprogress)(void *chooser, int, void *str, void *f1, void *f2);
static void (*f_joinmp)(void *client, const char *ip, int port);
static char join_ip[64];
static volatile int join_port;

int Java_com_nova_client_NovaActivity_nativeJoin(void **env, void *cls, void *ip, int port) {
    if (!f_pushprogress || !f_joinmp) return -1;
    jstr_copy(env, ip, join_ip, sizeof join_ip);
    join_port = port;
    return 0;
}

static void join_frame(void *client) {
    if (!join_port || nova_screen || nova_pending || nova_creating) return;
    int port = join_port;
    join_port = 0;
    void *chooser = f_getchooser(client);
    if (!chooser) return;
    u32 str[1];
    gstr(str, "");
    u32 fz1[4] = {0, 0, 0, 0}, fz2[4] = {0, 0, 0, 0};          /* empty std::function<void()> */
    f_pushprogress(chooser, 0, str, fz1, fz2);
    f_strdtor(str);
    f_joinmp(client, join_ip, port);
}

/* ---------------- UI definitions reload (start / pause screens are served by Java per state) ------------- */
static void (*f_reload_ui)(void *client);
static void (*f_set_start)(void *chooser);
static volatile int ui_reload;          /* 1 reload, 2 reload + new start screen */

int Java_com_nova_client_NovaActivity_nativeReloadUi(void **env, void *cls, int startScreen) {
    if (startScreen || !ui_reload) ui_reload = startScreen ? 2 : 1;
    return 0;
}

static void ui_frame(void *client) {
    if (!ui_reload || nova_screen || nova_pending || nova_creating || join_port) return;
    int k = ui_reload;
    ui_reload = 0;
    if (f_reload_ui) f_reload_ui(client);
    LOG("ui definitions reloaded (%d)", k);
    if (k == 2 && f_set_start && !mm_player()) {
        void *chooser = f_getchooser(client);
        if (chooser) f_set_start(chooser);
    }
}

/* ---------------- hosting: the port the game hosts on, more players allowed ------------------------------ */
static volatile int host_port;
static u64 hc_host(u32 orig, u32 *w) {          /* RakNetInstance::host(this, name, port, maxConnections) */
    if ((int)w[3] < 10) w[3] = 10;              /* the limit of a NOVA world is kept by the NOVA server */
    u64 r = nh_callw(orig, w);
    host_port = (int)w[2];
    return r;
}
static u64 hc_disconnect(u32 orig, u32 *w) {
    host_port = 0;
    return nh_callw(orig, w);
}

static void (*f_levelid)(void *out, void *level);
static void *(*f_leveldata)(void *level);
static void *(*f_levelname)(void *ld);

/* "port\tlevel id\tlevel name" while this phone hosts a world, "" otherwise. Built on the game thread (the level
 * may be going away while Java asks), Java reads the last finished copy. */
static char net_state[2][256];
static volatile int net_state_cur;
static int net_state_tick;

static void net_state_frame(void) {
    if (++net_state_tick < 20) return;                     /* a few times a second is plenty */
    net_state_tick = 0;
    int nb = net_state_cur ^ 1;
    char *out = net_state[nb];
    out[0] = 0;
    char *pl = mm_player();
    void *bs = pl ? mm_region(pl) : 0;
    void *level = bs && mf_getlevel ? mf_getlevel(bs) : 0;
    if (host_port && level && f_levelid && f_leveldata && f_levelname) {
        u32 id[1] = {0};
        f_levelid(id, level);
        void *ld = f_leveldata(level);
        void *nm = ld ? f_levelname(ld) : 0;
        int p = 0, v = host_port;
        char num[8]; int k = 0;
        do { num[k++] = '0' + v % 10; v /= 10; } while (v && k < 7);
        while (k) out[p++] = num[--k];
        out[p] = 0;
        p = append(out, p, 256, "\t");
        p = append(out, p, 256, id[0] ? sdata(id) : "");
        p = append(out, p, 256, "\t");
        append(out, p, 256, nm ? sdata(nm) : "");
        if (id[0]) f_strdtor(id);
        for (char *c = out; *c; c++) if (*c == '\n') *c = ' ';
        mm_clean_utf(out);
    }
    net_state_cur = nb;
}

void *Java_com_nova_client_NovaActivity_nativeNetState(void **env, void *cls) {
    void **fns = *(void ***)env;
    char out[256];
    const char *src = net_state[net_state_cur];
    int i = 0;
    while (i < 255 && src[i]) { out[i] = src[i]; i++; }
    out[i] = 0;
    return ((void *(*)(void **, const char *))fns[167])(env, out);
}

/* ---------------- invite banner: like the toast, with "Join" / "No" buttons ------------------------------ */
static char ban_title[96], ban_sub[96], ban_yes[32], ban_no[32];
static volatile int ban_on, ban_answer;          /* answer: 1 join, 2 no / timed out */
static long ban_t0, ban_drawn_at;
static volatile int ban_rect[8];                 /* GUI units: join x y w h, no x y w h */
static void *ban_tb1, *ban_tb2;
static float (*f_guiscale)(void *gd);

static void at_key(char *s, int cap) {           /* "@key": localized here */
    if (s[0] != '@') return;
    char k[96];
    int i = 0;
    while (i < 95 && (k[i] = s[i + 1])) i++;
    k[i] = 0;
    loc_into(k, s, cap);
}

int Java_com_nova_client_NovaActivity_nativeBanner(void **env, void *cls, void *t, void *sub, void *yes, void *no) {
    jstr_copy(env, t, ban_title, sizeof ban_title);
    jstr_copy(env, sub, ban_sub, sizeof ban_sub);
    jstr_copy(env, yes, ban_yes, sizeof ban_yes);
    jstr_copy(env, no, ban_no, sizeof ban_no);
    at_key(ban_title, sizeof ban_title);
    at_key(ban_sub, sizeof ban_sub);
    at_key(ban_yes, sizeof ban_yes);
    at_key(ban_no, sizeof ban_no);
    ban_answer = 0;
    ban_t0 = 0;
    for (int i = 0; i < 8; i++) ban_rect[i] = -1000;
    ban_on = ban_title[0] != 0;
    return 0;
}
int Java_com_nova_client_NovaActivity_nativeBannerAnswer(void **env, void *cls) {
    int a = ban_answer;
    ban_answer = 0;
    return a;
}

static void draw_banner(void *client) {
    if (!ban_on || !f_fill || !f_sr || !f_guidata || !f_screenw) return;
    long now = now_ms();
    if (ban_drawn_at == now) return;                         /* once per frame */
    ban_drawn_at = now;
    if (!ban_t0) ban_t0 = now;
    float t = (now - ban_t0) / 1000.f;
    if (t > 25.f) { ban_on = 0; ban_answer = 2; return; }
    float k = t < 0.25f ? t / 0.25f : 1.f;
    void *gd = f_guidata(client);
    int W = gd ? f_screenw(gd) : 320;
    float w = 230.f, h = 52.f;
    float x0 = (W - w) / 2.f, y0 = -h - 2.f + (h + 6.f) * k;
    void *sr = f_sr();
    float border[4] = {0.55f, 0.6f, 0.75f, 0.95f}, bg[4] = {0.09f, 0.1f, 0.15f, 0.92f};
    f_fill(sr, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, border);
    f_fill(sr, x0, y0, x0 + w, y0 + h, bg);
    float yellow[4] = {1.f, 1.f, 0.4f, 1.f}, white[4] = {1, 1, 1, 1};
    void *font = *(void **)((char *)client + 0x7c);
    draw_text_col(font, x0 + 6, y0 + 5, ban_title, yellow);
    draw_text_col(font, x0 + 6, y0 + 16, ban_sub, white);
    if (!ban_tb1) ban_tb1 = tb_new(client, "", 0);
    if (!ban_tb2) ban_tb2 = tb_new(client, "", 0);
    int by = (int)(y0 + h - 24), bw = 74;
    int r[8] = {(int)(x0 + w - 2 * bw - 10), by, bw, 20, (int)(x0 + w - bw - 4), by, bw, 20};
    for (int i = 0; i < 8; i++) ban_rect[i] = r[i];
    void *tbs[2] = {ban_tb1, ban_tb2};
    const char *txt[2] = {ban_yes, ban_no};
    for (int b = 0; b < 2; b++) {
        if (!tbs[b]) continue;
        int *g = gel(tbs[b]);
        g[0] = r[b * 4]; g[1] = r[b * 4 + 1]; g[2] = r[b * 4 + 2]; g[3] = r[b * 4 + 3];
        tb_msg(tbs[b], txt[b]);
        tb_render(tbs[b], client, -1, -1);
    }
}

/* touches on the banner buttons never reach the game */
static int ct_touch(u32 *w, float scale);          /* creative.inc: page arrows */
static u64 hc_touch(u32 orig, u32 *w) {          /* Multitouch::feed(button, state, x, y, pointer) */
    if (g_client && f_guidata && f_guiscale) {
        void *gd = f_guidata(g_client);
        float s = gd ? f_guiscale(gd) : 1.f;
        if (s <= 0) s = 1.f;
        if (ct_touch(w, s)) return 0;
    }
    if (ban_on && (w[1] & 0xff) == 1 && g_client && f_guidata && f_guiscale) {
        void *gd = f_guidata(g_client);
        float s = gd ? f_guiscale(gd) : 1.f;
        if (s <= 0) s = 1.f;
        int x = (int)((short)w[2] / s), y = (int)((short)w[3] / s);
        for (int b = 0; b < 2; b++) {
            const volatile int *r = ban_rect + b * 4;
            if (x >= r[0] && x < r[0] + r[2] && y >= r[1] - 4 && y < r[1] + r[3] + 4) {
                ban_answer = b + 1;
                ban_on = 0;
                return 0;
            }
        }
    }
    return nh_callw(orig, w);
}

/* ---------------- per frame ------------------------------------------------------------------------------ */
static void net_frame(void *client) {
    net_state_frame();
    join_frame(client);
    ui_frame(client);
}
static void net_draw(void *client) { draw_banner(client); }

/* ---------------- setup --------------------------------------------------------------------------------- */
static void net_init(void *mc) {
    if (!menus_ok) return;
    f_tb_ctor = dlsym(mc, "_ZN5Touch7TButtonC1EiRKSsP15MinecraftClientbi");
    f_hb_ctor = dlsym(mc, "_ZN5Touch12HeaderButtonC1EiR15MinecraftClientRKSsbb");
    f_cw_name = dlsym(mc, "_ZNK17CreateWorldScreen12getLevelNameEv");
    f_pushprogress = dlsym(mc, "_ZN13ScreenChooser18pushProgressScreenEbRKSsSt8functionIFvvEES4_");
    f_joinmp = dlsym(mc, "_ZN15MinecraftClient15joinMultiplayerEPKci");
    f_reload_ui = dlsym(mc, "_ZN15MinecraftClient25handleReloadUIDefinitionsEv");
    f_set_start = dlsym(mc, "_ZN13ScreenChooser18setStartMenuScreenEv");
    f_levelid = dlsym(mc, "_ZNK5Level10getLevelIdEv");
    f_leveldata = dlsym(mc, "_ZN5Level12getLevelDataEv");
    f_levelname = dlsym(mc, "_ZNK9LevelData12getLevelNameEv");
    f_guiscale = dlsym(mc, "_ZN7GuiData11getGuiScaleEv");
    u32 vt_ob = (u32)dlsym(mc, "_ZTV12OptionButton");
    u32 vt_it = (u32)dlsym(mc, "_ZTV11OptionsItem");
    if (vt_ob && vt_it && f_tb_ctor) {
        patch_slot(vt_ob, 3, h_ob_render, (void **)&o_ob_render);
        patch_slot(vt_it, 5, h_item_setup, (void **)&o_item_setup);
    }
    u32 vt_cw = (u32)dlsym(mc, "_ZTV17CreateWorldScreen");
    if (vt_cw && f_hb_ctor) {
        patch_slot(vt_cw, 0, h_cw_d1, (void **)&o_cw_d1);
        patch_slot(vt_cw, 1, h_cw_d0, (void **)&o_cw_d0);
        patch_slot(vt_cw, 53, h_cw_setup, (void **)&o_cw_setup);
        patch_slot(vt_cw, 67, h_cw_click, (void **)&o_cw_click);
    }
    void *h = dlsym(mc, "_ZN14RakNetInstance4hostERKSsii");
    void *d = dlsym(mc, "_ZN14RakNetInstance10disconnectEv");
    void *t = dlsym(mc, "_ZN10Multitouch4feedEccssi");
    int ih = h ? nh_hook_c((u32)h, hc_host) : -1;
    int id = d ? nh_hook_c((u32)d, hc_disconnect) : -1;
    int it = t ? nh_hook_c((u32)t, hc_touch) : -1;
    LOG("net: host %d disconnect %d touch %d", ih, id, it);
}
