/* skinpacks.inc - skin packs in the Bedrock format (octa3)
 *
 * The game's own skin packs (built into SkinRepository's constructor) are taken out; the packs the user imported
 * (.mcpack with a "skin_pack" module, unpacked by Java SkinPacks into files/nova/skinpacks/<id>/) are put in.
 * Java sends them as text (nativeSkinPacks):  "P\t<id>\t<pack name>\n"  then  "S\t<skin id>\t<name>\t<geometry>\t<path>\n"
 * per skin. Paths are "nova_skins/<id>/<file>.png": Java getImageData serves them from the pack folder.
 *
 * SkinRepository: +0x20 vector<SkinPack*> (begin, end, cap), +0x2c the selected Skin*.
 * SkinPack (0x20): +0 serializable, +4 name, +8 price, +0xc premium unlocked, +0xd premium, +0x10 vector<Skin>,
 *                  +0x1c type (0 = the standard pack: Steve/Alex/custom; others are listed under "Skin packs").
 * Skin (0x18): +0 SkinPack*, +4 serializable (pack + "_" + skin), +8 name (a lang key, or the text itself), +0xc geometry,
 *              +0x10 texture path, +0x14 type (0 custom, 1 free, 2 premium).
 * Our packs: serializable "nova_<id>", type 1, not premium. Their names are shown as they are (I18n returns unknown keys).
 * The selected skin of a pack is remembered in files/nova_skin_sel (the game's options can't keep it: at start the
 * repository doesn't know our packs yet and falls back to Steve).
 * ShowSkinPackScreen: its "Restore" button (+0xa0) deletes our pack ("skins.restore.button" says "Delete"); Java removes
 * the folder at the next start (marker files/nova/skinpacks/<id>.deleted).
 * SkinsPaneStandard::render: with no packs, a line under the "Skin packs" header (+0xa0, GuiElement x/y/w/h at +8..+0x14)
 * says how to add one. */

static int sp_strlen(const char *s) { int n = 0; while (s[n]) n++; return n; }
static char *sp_strchr(char *s, int c) { for (; *s; s++) if (*s == c) return s; return 0; }
static void (*sp_skin_ctor)(void *skin, void *ser, void *name, void *geom, void *path, int type);
static void (*sp_pack_ctor)(void *pack, int type, void *ser, void *name, int premium, void *ilist);
static void (*sp_setskin)(void *repo, void *skin);
static void *(*sp_default)(void *repo, int which);
static void (*sp_o_click)(void *self, void *button);
static void (*sp_o_back)(void *self, int b);
static void (*sp_o_pane_render)(void *self, void *client, int x, int y);
static char *sp_repo;
static int sp_ready;                     /* the repository has been set up (vanilla packs out, ours in, selection back) */
static char *volatile sp_pending;        /* packs sent by Java, not added yet */
static char sp_sel[200];                 /* the remembered pack skin */

static void sp_file(char *out, int n, const char *rel) {
    int k = 0;
    while (cfg_path[k] && k < n - 64) { out[k] = cfg_path[k]; k++; }
    while (k > 0 && out[k - 1] != '/') k--;
    for (int i = 0; rel[i] && k < n - 1; i++) out[k++] = rel[i];
    out[k] = 0;
}

int Java_com_nova_client_NovaActivity_nativeSkinPacks(void **env, void *cls, void *jtext) {
    typedef const char *(*utf_fn)(void **, void *, void *);
    typedef void (*rel_fn)(void **, void *, const char *);
    void **fns = *(void ***)env;
    const char *t = ((utf_fn)fns[169])(env, jtext, 0);
    if (!t) return 0;
    int n = (int)sp_strlen(t);
    char *old = sp_pending;
    int o = old ? (int)sp_strlen(old) : 0;
    char *b = malloc(o + n + 2);
    if (b) {
        if (old) memcpy(b, old, o);
        memcpy(b + o, t, n);
        b[o + n] = 0;
        sp_pending = b;
        if (old) free(old);
    }
    ((rel_fn)fns[170])(env, jtext, t);
    return 1;
}

static int sp_ours(const char *ser) { return ser && ser[0] == 'n' && !memcmp(ser, "nova_", 5); }
static const char *sp_str(const char *base, int off) { return *(const char **)(base + off); }

static int sp_user_packs(void) {
    if (!sp_repo) return 0;
    char **b = *(char ***)(sp_repo + 0x20), **e = *(char ***)(sp_repo + 0x24);
    int n = 0;
    for (; b && b < e; b++) if (*b && *(int *)(*b + 0x1c) != 0) n++;
    return n;
}

static void sp_push(char *pack) {
    char ***v = (char ***)(sp_repo + 0x20);       /* begin, end, cap */
    if (v[1] == v[2]) {
        int n = (int)(v[1] - v[0]), cap = n * 2 + 8;
        char **nb = malloc(cap * sizeof(char *));
        if (!nb) return;
        for (int i = 0; i < n; i++) nb[i] = v[0][i];
        v[0] = nb; v[1] = nb + n; v[2] = nb + cap;    /* the old buffer is left (a few bytes) */
    }
    *v[1]++ = pack;
}

/* one "P" line and its "S" lines -> a SkinPack in the repository */
static char *sp_add_pack(char *line) {
    char *f[5];
    int nf = 0;
    char *p = line;
    char *end = sp_strchr(p, '\n');
    if (end) *end = 0;
    while (nf < 3) { f[nf++] = p; p = sp_strchr(p, '\t'); if (!p) break; *p++ = 0; }
    char *next = end ? end + 1 : 0;
    if (nf < 3) return next;
    char id[96], name[160];
    snprintf(id, sizeof id, "nova_%s", f[1]);
    snprintf(name, sizeof name, "%s", f[2]);
    /* skins */
    int cap = 16, n = 0;
    char *skins = malloc(cap * 0x18);
    while (skins && next && next[0] == 'S' && next[1] == '\t') {
        char *l = next;
        char *le = sp_strchr(l, '\n');
        if (le) *le = 0;
        next = le ? le + 1 : 0;
        char *g[5];
        int ng = 0;
        char *q = l;
        while (ng < 5) { g[ng++] = q; q = sp_strchr(q, '\t'); if (!q) break; *q++ = 0; }
        if (ng < 5) continue;
        if (n == cap) { char *nb = malloc(cap * 2 * 0x18); if (!nb) break; memcpy(nb, skins, n * 0x18); free(skins); skins = nb; cap *= 2; }
        u32 s1[1], s2[1], s3[1], s4[1];
        gstr(s1, g[1]); gstr(s2, g[2]); gstr(s3, g[3]); gstr(s4, g[4]);
        sp_skin_ctor(skins + n * 0x18, s1, s2, s3, s4, 1);
        f_strdtor(s1); f_strdtor(s2); f_strdtor(s3); f_strdtor(s4);
        n++;
    }
    if (!skins || n == 0) { if (skins) free(skins); return next; }
    /* the same pack again (re-imported): take the old one out */
    char **b = *(char ***)(sp_repo + 0x20), **e = *(char ***)(sp_repo + 0x24);
    for (char **it = b; it < e; it++)
        if (*it && streq(sp_str(*it, 0), id)) {
            for (char **jt = it; jt + 1 < e; jt++) jt[0] = jt[1];
            *(char ***)(sp_repo + 0x24) = e - 1;
            break;
        }
    char *pack = malloc(0x40);
    if (!pack) return next;
    memset(pack, 0, 0x40);
    u32 sid[1], snm[1];
    gstr(sid, id); gstr(snm, name);
    struct { void *p; u32 n; } il = {skins, (u32)n};
    sp_pack_ctor(pack, 1, sid, snm, 0, &il);
    f_strdtor(sid); f_strdtor(snm);
    sp_push(pack);
    LOG("skin packs: \"%s\" (%s), %d skins", name, id, n);
    return next;
}

static void sp_add_text(char *t) {
    char *p = t;
    while (p && *p) {
        if (p[0] == 'P' && p[1] == '\t') p = sp_add_pack(p);
        else { char *e = sp_strchr(p, '\n'); p = e ? e + 1 : 0; }
    }
}

static char *sp_find_skin(const char *ser) {
    char **b = *(char ***)(sp_repo + 0x20), **e = *(char ***)(sp_repo + 0x24);
    for (; b < e; b++) {
        if (!*b) continue;
        char *sk = *(char **)(*b + 0x10), *se = *(char **)(*b + 0x14);
        for (; sk < se; sk += 0x18) if (streq(sp_str(sk, 4), ser)) return sk;
    }
    return 0;
}

/* once, on the first menu frame: out with the game's packs, in with ours, the remembered skin back */
static void sp_setup(void *client) {
    char *repo = *(char **)((char *)client + 0x158);
    if (!repo || !sp_skin_ctor || !sp_pack_ctor || !sp_setskin) return;
    sp_repo = repo;
    char path[300];
    sp_file(path, sizeof path, "nova_skin_sel");
    int fd = open(path, 0);
    if (fd >= 0) { int r = read(fd, sp_sel, sizeof sp_sel - 1); sp_sel[r > 0 ? r : 0] = 0; close(fd); }
    char **b = *(char ***)(repo + 0x20), **e = *(char ***)(repo + 0x24), **w = b;
    int gone = 0;
    for (char **it = b; it < e; it++) { if (*it && *(int *)(*it + 0x1c) == 0) *w++ = *it; else gone++; }
    *(char ***)(repo + 0x24) = w;
    char *t = sp_pending;
    sp_pending = 0;
    if (t) { sp_add_text(t); free(t); }
    char *sel = *(char **)(repo + 0x2c);
    char *want = sp_sel[0] ? sp_find_skin(sp_sel) : 0;
    sp_ready = 1;
    if (want) sp_setskin(repo, want);
    else if (sel && *(char **)sel && *(int *)(*(char **)sel + 0x1c) != 0 && sp_default) {
        void *st = sp_default(repo, 2);                  /* a skin of a pack that is gone: Steve */
        if (st) sp_setskin(repo, st);
    }
    LOG("skin packs: %d built-in packs out, %d packs of the user, skin %s", gone, sp_user_packs(), want ? sp_sel : "as is");
}

static void sp_frame(void *client) {
    if (!sp_ready) { sp_setup(client); return; }
    if (sp_pending && sp_repo) {
        char *t = sp_pending;
        sp_pending = 0;
        sp_add_text(t);
        free(t);
    }
}

/* SkinRepository::setSkin: remember a pack skin ourselves */
static u64 hc_sp_setskin(u32 orig, u32 *w) {
    u64 r = nh_callw(orig, w);
    char *skin = (char *)w[1];
    if (sp_ready && skin) {
        char *pack = *(char **)skin;
        const char *ser = sp_str(skin, 4);
        int ours = pack && sp_ours(sp_str(pack, 0));
        snprintf(sp_sel, sizeof sp_sel, "%s", ours && ser ? ser : "");
        char path[300];
        sp_file(path, sizeof path, "nova_skin_sel");
        int fd = open(path, 01 | 0100 | 01000, 0644);
        if (fd >= 0) { write(fd, sp_sel, sp_strlen(sp_sel)); close(fd); }
    }
    return r;
}

/* ShowSkinPackScreen::_buttonClicked: "Restore" -> delete our pack */
static void sp_click(void *self, void *button) {
    char *s = (char *)self;
    char *rb = *(char **)(s + 0xa0), *pack = *(char **)(s + 0x8c);
    if (sp_repo && rb && button && *(int *)((char *)button + 0x64) == *(int *)(rb + 0x64) && pack && sp_ours(sp_str(pack, 0))) {
        char **b = *(char ***)(sp_repo + 0x20), **e = *(char ***)(sp_repo + 0x24);
        for (char **it = b; it < e; it++)
            if (*it == pack) {
                for (char **jt = it; jt + 1 < e; jt++) jt[0] = jt[1];
                *(char ***)(sp_repo + 0x24) = e - 1;
                break;
            }
        char rel[160], path[300];
        snprintf(rel, sizeof rel, "nova/skinpacks/%s.deleted", sp_str(pack, 0) + 5);
        sp_file(path, sizeof path, rel);
        int fd = open(path, 01 | 0100 | 01000, 0644);
        if (fd >= 0) close(fd);
        char *sel = *(char **)(sp_repo + 0x2c);
        if (sel && *(char **)sel == pack && sp_default) {
            void *st = sp_default(sp_repo, 2);
            if (st) sp_setskin(sp_repo, st);
        }
        LOG("skin packs: deleted %s", sp_str(pack, 0));
        if (sp_o_back) sp_o_back(self, 0);              /* back to the skins screen (it refreshes on focus) */
        return;
    }
    sp_o_click(self, button);
}

/* SkinsPaneStandard::render: no packs yet -> how to add one */
static void sp_pane_render(void *self, void *client, int x, int y) {
    sp_o_pane_render(self, client, x, y);
    char *hdr = *(char **)((char *)self + 0xa0);
    if (!hdr || !sp_ready || sp_user_packs() > 0 || !client) return;
    void *font = *(void **)((char *)client + 0x7c);
    if (!font) return;
    int hx = *(int *)(hdr + 8), hy = *(int *)(hdr + 0xc), hh = *(int *)(hdr + 0x14);
    char l1[160], l2[200];
    loc_into("nova.skins.empty1", l1, sizeof l1);
    loc_into("nova.skins.empty2", l2, sizeof l2);
    float c1[4] = {0.85f, 0.88f, 0.95f, 1.f}, c2[4] = {0.55f, 0.6f, 0.7f, 1.f};
    rb_text(font, (float)hx, (float)(hy + hh + 8), l1, c1);
    rb_text(font, (float)hx, (float)(hy + hh + 20), l2, c2);
}

static void skinpacks_init(void *mc) {
    sp_skin_ctor = dlsym(mc, "_ZN4SkinC1ERKSsS1_S1_S1_NS_8SkinTypeE");
    sp_pack_ctor = dlsym(mc, "_ZN8SkinPackC1ENS_12SkinPackTypeERKSsSsbRKSt16initializer_listI4SkinE");
    sp_setskin = dlsym(mc, "_ZN14SkinRepository7setSkinERK4Skin");
    sp_default = dlsym(mc, "_ZNK14SkinRepository14getDefaultSkinENS_11DefaultSkinE");
    sp_o_back = dlsym(mc, "_ZN18ShowSkinPackScreen15handleBackEventEb");
    u32 vss = (u32)dlsym(mc, "_ZTV18ShowSkinPackScreen"), vsp = (u32)dlsym(mc, "_ZTV17SkinsPaneStandard");
    if (!sp_skin_ctor || !sp_pack_ctor || !sp_setskin) { LOGE("skin packs: symbols missing"); return; }
    if (nh_hook_c((u32)sp_setskin, hc_sp_setskin) < 0) LOGE("skin packs: setSkin hook failed");
    if (vss) patch_slot(vss, 67, sp_click, (void **)&sp_o_click);
    if (vsp) patch_slot(vsp, 3, sp_pane_render, (void **)&sp_o_pane_render);
}
