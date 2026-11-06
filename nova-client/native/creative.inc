/* creative.inc - creative inventory tabs of mods, in pages (like Forge's creative tab pages).
 *
 * The 0.14 creative screen has 4 category tabs (blocks, decoration, tools, items). Their item lists are
 * InventoryScreen::mFilteredItems[4] (std::vector<ItemInstance const*>), read by getItems (what the pane shows) and
 * _addItemCreativeScreen (what a tap takes). Page 0 is the game's own; page N shows mod tabs 4(N-1)..4(N-1)+3 in
 * the same 4 tab buttons: the vectors are swapped for ours, the panes refreshed (Touch::InventoryPane::refreshItems),
 * the tab icons come from getItemFromType (hooked) and the selected tab's name is drawn in the header between two
 * arrow buttons (TButton + the game's gui/newgui/arrowLeft|Right.png).
 * Java: nativeCreativeTabs("name\x1ficonId\x1ficonAux\x1fid:aux,id:aux,...\x1e..."): one tab per mod unless the mod
 * declares its own tabs. Mod items in a tab are not added to the game's tabs. */
#define CT_MAX 32
#define CT_ITEMS 160
typedef struct { char name[48]; int icon, icon_aux, n; short id[CT_ITEMS], aux[CT_ITEMS]; char *inst; } CTab;
static CTab ct_tabs[CT_MAX];
static int ct_count;
static volatile int ct_page, ct_want = -1;          /* shown page; page asked by a touch (applied on the render thread) */
static u32 ct_saved[12];                              /* the game's own 4 vectors while ours are in */
static int ct_swapped;
static u32 *ct_filtered;                              /* InventoryScreen::mFilteredItems */
static void (*ct_refresh)(void *pane);
static void (*ct_ii_ctor0)(void *ii);
static void *ct_screen;                               /* the creative InventoryScreen being drawn */
static long ct_drawn_at;
static volatile int ct_rect[8];                       /* arrow buttons, gui units */
static void *ct_tb[2];
static u32 ct_arrow_tex[2][16];
static int ct_arrow_ok[2];

static int (*f_fontwidth)(void *font, void *str, float scale, int b);
static int ct_num(char **pp) {
    char *p = *pp; int neg = 0, v = 0;
    if (*p == '-') { neg = 1; p++; }
    while (*p >= '0' && *p <= '9') v = v * 10 + (*p++ - '0');
    *pp = p;
    return neg ? -v : v;
}

int Java_com_nova_client_ModBridge_nativeCreativeTabs(void **env, void *cls, void *jspec) {
    static char spec[16384];
    jstr_copy(env, jspec, spec, sizeof spec);
    ct_count = 0;
    char *p = spec;
    while (*p && ct_count < CT_MAX) {
        CTab *t = &ct_tabs[ct_count];
        memset(t, 0, sizeof *t);
        int k = 0;
        while (*p && *p != '\x1f' && *p != '\x1e') { if (k < 47) t->name[k++] = *p; p++; }
        if (*p == '\x1f') p++;
        t->icon = ct_num(&p); if (*p == '\x1f') p++;
        t->icon_aux = ct_num(&p); if (*p == '\x1f') p++;
        while (*p && *p != '\x1e') {
            int id = ct_num(&p), aux = 0;
            if (*p == ':') { p++; aux = ct_num(&p); }
            if (id > 0 && id < 512 && t->n < CT_ITEMS) { t->id[t->n] = (short)id; t->aux[t->n] = (short)aux; t->n++; }
            if (*p == ',') p++; else if (*p && *p != '\x1e') p++;
        }
        if (*p == '\x1e') p++;
        if (t->n) ct_count++;
    }
    LOG("creative: %d mod tab(s)", ct_count);
    return ct_count;
}

static int ct_has(int id) {
    for (int i = 0; i < ct_count; i++) for (int k = 0; k < ct_tabs[i].n; k++) if (ct_tabs[i].id[k] == id) return 1;
    return 0;
}
static int ct_pages(void) { return ct_count ? 1 + (ct_count + 3) / 4 : 1; }

/* the ItemInstances of a tab: made once the items exist (only items the game has: Item::mItems / Block::mBlocks) */
static void ct_build(CTab *t) {
    if (t->inst || !sf_item_ctor || !sf_items) return;
    t->inst = f_new(t->n * 20 + 20);
    memset(t->inst, 0, t->n * 20 + 20);
    int m = 0;
    for (int k = 0; k < t->n; k++) {
        int id = t->id[k];
        if (!sf_items[id]) continue;
        sf_item_ctor(t->inst + m * 20, id, 1, t->aux[k]);
        m++;
    }
    t->n = m;
}

/* category tab i (0..3) of a page */
static CTab *ct_tab(int page, int i) {
    int k = (page - 1) * 4 + i;
    return page > 0 && k >= 0 && k < ct_count ? &ct_tabs[k] : 0;
}

/* swap the 4 vectors: page 0 = the game's own */
static void ct_apply(void *scr, int page) {
    if (!ct_filtered) return;
    if (page > 0 && !ct_swapped) { for (int i = 0; i < 12; i++) ct_saved[i] = ct_filtered[i]; ct_swapped = 1; }
    if (page == 0) {
        if (ct_swapped) { for (int i = 0; i < 12; i++) ct_filtered[i] = ct_saved[i]; ct_swapped = 0; }
    } else {
        for (int i = 0; i < 4; i++) {
            CTab *t = ct_tab(page, i);
            u32 *v = ct_filtered + 3 * i;
            if (t) ct_build(t);
            int n = t ? t->n : 0;
            /* a fresh pointer array every time: the game may free it (clearPopulatedItems) */
            u32 *arr = n ? f_new(n * 4) : 0;
            for (int k = 0; k < n; k++) arr[k] = (u32)(t->inst + k * 20);
            v[0] = (u32)arr; v[1] = (u32)(arr + n); v[2] = (u32)(arr + n);
        }
    }
    ct_page = page;
    if (scr && ct_refresh)
        for (int i = 0; i < 4; i++) {
            void *pane = *(void **)((char *)scr + 0x164 + 8 * i);
            if (pane) ct_refresh(pane);
        }
}

/* InventoryPaneType -> creative category 0..3 (or -1): the screen's pane table (this+0x200, 12-byte entries,
 * the pane at +4) against the 4 creative panes (this+0x164, shared_ptr) */
static int ct_cat_of(void *scr, int type) {
    char *tb = *(char **)((char *)scr + 0x200), *te = *(char **)((char *)scr + 0x204);
    if (!tb || type < 0 || (te - tb) / 12 <= type) return -1;
    void *pane = *(void **)(tb + type * 12 + 4);
    for (int i = 0; i < 4 && pane; i++) if (*(void **)((char *)scr + 0x164 + 8 * i) == pane) return i;
    return -1;
}

static u64 hc_ct_icon(u32 orig, u32 *w) {           /* getItemFromType(ItemInstance* sret, this, type) */
    void *out = (void *)w[0], *scr = (void *)w[1];
    if (ct_page > 0 && scr && sf_item_ctor) {
        int cat = ct_cat_of(scr, (int)w[2]);
        if (cat >= 0) {
            CTab *t = ct_tab(ct_page, cat);
            if (t && t->icon > 0 && t->icon < 512 && sf_items[t->icon]) sf_item_ctor(out, t->icon, 1, t->icon_aux);
            else if (ct_ii_ctor0) ct_ii_ctor0(out);
            else sf_item_ctor(out, 0, 0, 0);
            return (u32)out;
        }
    }
    return nh_callw(orig, w);
}

static void ct_draw(void *scr, void *client) {
    if (!f_guidata || !f_screenw || !f_sr || !client) return;
    void *gd = f_guidata(client);
    if (!gd) return;
    int W = f_screenw(gd);
    /* the tab column: Screen tab buttons (this+0x30, shared_ptr<Button>) */
    char **tb = *(char ***)((char *)scr + 0x30), **te = *(char ***)((char *)scr + 0x34);
    int tx = 2, ty = 26;
    if (tb && te > tb && (u32)tb[0] > 0x10000) { int *g = gel(tb[0]); if (g[0] >= 0 && g[0] < W && g[1] > 0 && g[1] < 200) { tx = g[0]; ty = g[1]; } }
    int h = ty - 6; if (h > 20) h = 20; if (h < 14) h = 14;
    int y = (ty - h) / 2; if (y < 1) y = 1;
    int bw = h + 4;
    int r[8] = {tx, y, bw, h, tx + bw + 2 + 120, y, bw, h};
    /* page label + the selected tab's name between the arrows */
    char label[96];
    int sel = ct_cat_of(scr, *(int *)((char *)scr + 0x268));
    CTab *t = sel >= 0 ? ct_tab(ct_page, sel) : 0;
    if (ct_page == 0) snprintf(label, sizeof label, "%d/%d", 1, ct_pages());
    else snprintf(label, sizeof label, "%d/%d %s", ct_page + 1, ct_pages(), t ? t->name : "");
    void *font = *(void **)((char *)client + 0x7c);
    int lw = 0;
    if (font && f_fontwidth) { u32 s[1]; gstr(s, label); lw = f_fontwidth(font, s, 1.f, 0); f_strdtor(s); }
    if (lw < 24) lw = 24;
    if (lw > W / 3) lw = W / 3;
    r[4] = tx + bw + 6 + lw + 6;
    for (int i = 0; i < 8; i++) ct_rect[i] = r[i];
    for (int b = 0; b < 2; b++) {
        if (!ct_tb[b]) ct_tb[b] = tb_new(client, "", 0);
        if (!ct_tb[b]) continue;
        int *g = gel(ct_tb[b]);
        g[0] = r[b * 4]; g[1] = r[b * 4 + 1]; g[2] = r[b * 4 + 2]; g[3] = r[b * 4 + 3];
        tb_render(ct_tb[b], client, -1, -1);
        if (f_gettex && f_blit && f_textures) {
            if (!ct_arrow_ok[b]) {
                u32 s[1];
                gstr(s, b ? "gui/newgui/arrowRight.png" : "gui/newgui/arrowLeft.png");
                for (int j = 0; j < 16; j++) ct_arrow_tex[b][j] = 0;
                f_gettex(ct_arrow_tex[b], f_textures(client), s, 0);
                f_strdtor(s);
                ct_arrow_ok[b] = 1;
            }
            int s8 = h >= 18 ? 12 : 8;
            int rect[4] = {r[b * 4] + (bw - s8) / 2, y + (h - s8) / 2, s8, s8};
            f_blit(f_sr(), ct_arrow_tex[b], rect);
        }
    }
    float white[4] = {1, 1, 1, 1};
    if (font) draw_text_col(font, (float)(tx + bw + 6), (float)(y + (h - 8) / 2), label, white);
}

static u64 hc_ct_render(u32 orig, u32 *w) {         /* InventoryScreen::render(this, mx, my, a) */
    void *scr = (void *)w[0];
    int creative = scr && *(void **)((char *)scr + 0x164) != 0;
    if (creative && ct_count) {
        int want = ct_want;
        if (want >= 0) { ct_want = -1; if (want != ct_page) ct_apply(scr, want); }
        else if (ct_page > 0 && !ct_swapped) ct_apply(scr, ct_page);     /* reopened on a mod page */
    }
    u64 ret = nh_callw(orig, w);
    if (creative && ct_count) {
        ct_screen = scr;
        ct_drawn_at = now_ms();
        ct_draw(scr, *(void **)((char *)scr + 0x14));
    }
    return ret;
}

/* leaving the screen: the game's vectors back before it frees anything (the page is remembered) */
static u64 hc_ct_dtor(u32 orig, u32 *w) {
    if ((void *)w[0] == ct_screen) ct_screen = 0;
    if (ct_swapped) { int keep = ct_page; ct_apply(0, 0); ct_page = keep; }
    return nh_callw(orig, w);
}
static u64 hc_ct_ctor(u32 orig, u32 *w) {
    if (ct_swapped) { int keep = ct_page; ct_apply(0, 0); ct_page = keep; }
    return nh_callw(orig, w);
}

/* touches on the arrows (Multitouch::feed: w[1] state, w[2] x, w[3] y in pixels) */
static int ct_touch(u32 *w, float scale) {
    if (!ct_screen || !ct_count || now_ms() - ct_drawn_at > 300) return 0;     /* only while it is on screen */
    int x = (int)((short)w[2] / scale), y = (int)((short)w[3] / scale);
    for (int b = 0; b < 2; b++) {
        const volatile int *r = ct_rect + b * 4;
        if (x >= r[0] - 2 && x < r[0] + r[2] + 2 && y >= r[1] - 4 && y < r[1] + r[3] + 4) {
            if ((w[1] & 0xff) == 1) {
                int n = ct_pages(), p = ct_page + (b ? 1 : -1);
                if (p < 0) p = n - 1;
                if (p >= n) p = 0;
                ct_want = p;
            }
            return 1;
        }
    }
    return 0;
}

static void creative_init(void *mc) {
    ct_filtered = dlsym(mc, "_ZN15InventoryScreen14mFilteredItemsE");
    ct_refresh = dlsym(mc, "_ZN5Touch13InventoryPane12refreshItemsEv");
    ct_ii_ctor0 = dlsym(mc, "_ZN12ItemInstanceC1Ev");
    void *icon = dlsym(mc, "_ZN15InventoryScreen15getItemFromTypeENS_17InventoryPaneTypeE");
    void *rend = dlsym(mc, "_ZN15InventoryScreen6renderEiif");
    void *dtor = dlsym(mc, "_ZN15InventoryScreenD1Ev");
    f_fontwidth = dlsym(mc, "_ZNK4Font13getLineLengthERKSsfb");
    void *ctor = dlsym(mc, "_ZN15InventoryScreenC1ER15MinecraftClient12CraftingTypeRK8BlockPos");
    int ok = ct_filtered && ct_refresh && icon && rend && dtor && ctor;
    if (ok) {
        nh_hook_c((u32)icon, hc_ct_icon);
        nh_hook_c((u32)rend, hc_ct_render);
        nh_hook_c((u32)dtor, hc_ct_dtor);
        nh_hook_c((u32)ctor, hc_ct_ctor);
    }
    LOG("creative tabs: %s", ok ? "ok" : "unavailable");
}
