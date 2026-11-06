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
static char *ct_cm[4];                                /* the creative ContainerModels of the open screen */
static u32 ct_saved[4][4];                            /* their own size + items vector while ours are in */
static int ct_swapped[4];
static const char *(*ct_cname)(void *model);          /* ContainerModel::getContainerStringName (std::string const&) */
static long ct_drawn_at;

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

static int ct_ok;                                      /* the pages work: mod items leave the game's tabs */
static int ct_has(int id) {
    if (!ct_ok) return 0;
    for (int i = 0; i < ct_count; i++) for (int k = 0; k < ct_tabs[i].n; k++) if (ct_tabs[i].id[k] == id) return 1;
    return 0;
}
static int ct_pages(void) { return ct_count ? 1 + (ct_count + 3) / 4 : 1; }



/* category tab i (0..3) of a page */
static CTab *ct_tab(int page, int i) {
    int k = (page - 1) * 4 + i;
    return page > 0 && k >= 0 && k < ct_count ? &ct_tabs[k] : 0;
}

/* JSON creative screen (crafting.creative_inventory_screen): CraftingContainerManagerModel::init makes 4 ContainerModels
 * "creative_building_blocks" / "_decorations" / "_tools" / "_miscellaneous" (size +0xc, std::vector<ItemInstance> +0x14).
 * A mod page puts our items in (a new vector each time: the model frees what it holds when the screen closes) */
static const char *ct_names[4] = {"creative_building_blocks", "creative_decorations", "creative_tools", "creative_miscellaneous"};

static void ct_put(int i, int page) {
    char *m = ct_cm[i];
    if (!m) return;
    if (page == 0) {
        if (ct_swapped[i]) {
            *(u32 *)(m + 0xc) = ct_saved[i][0];
            for (int k = 0; k < 3; k++) *(u32 *)(m + 0x14 + 4 * k) = ct_saved[i][1 + k];
            ct_swapped[i] = 0;
        }
        return;
    }
    if (!ct_swapped[i]) {
        ct_saved[i][0] = *(u32 *)(m + 0xc);
        for (int k = 0; k < 3; k++) ct_saved[i][1 + k] = *(u32 *)(m + 0x14 + 4 * k);
        ct_swapped[i] = 1;
    }
    CTab *t = ct_tab(page, i);
    int n = 0;
    char *arr = 0;
    if (t && sf_item_ctor && sf_items) {
        arr = f_new(t->n * 20 + 20);
        memset(arr, 0, t->n * 20 + 20);
        for (int k = 0; k < t->n; k++) {
            if (!sf_items[t->id[k]]) continue;
            sf_item_ctor(arr + n * 20, t->id[k], 1, t->aux[k]);
            n++;
        }
    }
    *(u32 *)(m + 0xc) = n;
    *(u32 *)(m + 0x14) = (u32)arr;
    *(u32 *)(m + 0x18) = (u32)(arr + n * 20);
    *(u32 *)(m + 0x1c) = (u32)(arr + n * 20);
}
static void ct_apply(int page) {
    for (int i = 0; i < 4; i++) ct_put(i, page);
    ct_page = page;
}

static u64 hc_ct_add(u32 orig, u32 *w) {           /* ContainerManagerModel::_addContainer(this, shared_ptr<ContainerModel>) */
    char *m = w[1] ? *(char **)w[1] : 0;
    u64 r = nh_callw(orig, w);
    if (m && ct_cname && ct_count) {
        const char *nm = sdata(ct_cname(m));
        for (int i = 0; i < 4; i++)
            if (nm && streq(nm, ct_names[i])) {
                ct_cm[i] = m; ct_swapped[i] = 0;
                if (ct_page > 0) ct_put(i, ct_page);              /* opened again on a mod page */
            }
    }
    return r;
}
static u64 hc_ct_mgr_dtor(u32 orig, u32 *w) {      /* ~CraftingContainerManagerModel: the game's vectors back first */
    for (int i = 0; i < 4; i++) { if (ct_cm[i]) ct_put(i, 0); ct_cm[i] = 0; }
    return nh_callw(orig, w);
}

/* tab icons: the item renderers nova_ct_icon_<k> (property #item_id_aux = id << 16 | aux) */
static const int ct_icon0[4] = {2949120, 3080192, 17498112, 19333120};
static void *(*ct_bag)(void *ctrl);
static void *(*ct_json_at)(void *v, const char *k);
static void (*ct_json_int)(void *v, int i);
static void (*ct_json_set)(void *v, void *from);
static void (*ct_json_dtor)(void *v);
static void (*o_iir_update)(void *self, void *client, void **ctrl);
static void h_iir_update(void *self, void *client, void **ctrl) {
    char *cc = ctrl ? *(char **)ctrl : 0;
    const char *nm = cc ? sdata(cc + 0xc) : 0;
    if (nm && ct_count && ct_bag && ct_json_at && ct_json_int && ct_json_set && ct_json_dtor &&
        nm[0] == 'n' && !memcmp(nm, "nova_ct_icon_", 13) && nm[13] >= '0' && nm[13] <= '3') {
        int k = nm[13] - '0', v = ct_icon0[k];
        if (ct_page > 0) {
            CTab *t = ct_tab(ct_page, k);
            v = t && t->icon > 0 ? (t->icon << 16) | (t->icon_aux & 0xffff) : -1;
        }
        char *bag = ct_bag(cc);
        if (bag) {
            u32 tmp[8];
            ct_json_int(tmp, v);
            ct_json_set(ct_json_at(bag + 0x18, "#item_id_aux"), tmp);
            ct_json_dtor(tmp);
        }
    }
    o_iir_update(self, client, ctrl);
}

static float ct_rect[4];                             /* arrows control, gui units */
static void *ct_tb[2];
static u32 ct_arrow_tex[2][16];
static int ct_arrow_ok[2];
static const char *ct_keys[4] = {"craftingScreen.tab.buildingBlocks", "craftingScreen.tab.decorations",
                                 "craftingScreen.tab.tools", "craftingScreen.tab.miscellaneous"};

/* debug_overlay_renderer controls of inventory_screen.json: returns 1 when it was one of ours */
static int ct_ui_render(void *client, char *cc) {
    const char *nm = sdata(cc + 0xc);
    if (!nm || nm[0] != 'n' || memcmp(nm, "nova_ct_", 8)) return 0;
    float x = *(float *)(cc + 0x10), y = *(float *)(cc + 0x14), w = *(float *)(cc + 0x18), h = *(float *)(cc + 0x1c);
    void *font = *(void **)((char *)client + 0x7c);
    if (!memcmp(nm + 8, "label_", 6)) {
        int k = nm[14] - '0';
        if (k < 0 || k > 3) return 1;
        char txt[96];
        CTab *t = ct_page > 0 ? ct_tab(ct_page, k) : 0;
        if (ct_page > 0) snprintf(txt, sizeof txt, "%s", t ? t->name : "");
        else loc_into(ct_keys[k], txt, sizeof txt);
        float col[4] = {0.4f, 0.95f, 0.88f, 1.f};
        if (font && txt[0]) draw_text_col(font, x, y, txt, col);
        return 1;
    }
    if (memcmp(nm + 8, "arrows", 6)) return 1;
    if (!ct_count) return 1;
    int want = ct_want;
    if (want >= 0) { ct_want = -1; if (want != ct_page) ct_apply(want); }
    ct_rect[0] = x; ct_rect[1] = y; ct_rect[2] = w; ct_rect[3] = h;
    ct_drawn_at = now_ms();
    int bh = (int)h, bw = bh + 4;
    int r[8] = {(int)x, (int)y, bw, bh, (int)(x + w) - bw, (int)y, bw, bh};
    for (int b = 0; b < 2; b++) {
        if (!ct_tb[b]) ct_tb[b] = tb_new(client, "", 0);
        if (ct_tb[b]) {
            int *g = gel(ct_tb[b]);
            g[0] = r[b * 4]; g[1] = r[b * 4 + 1]; g[2] = r[b * 4 + 2]; g[3] = r[b * 4 + 3];
            tb_render(ct_tb[b], client, -1, -1);
        }
        if (f_gettex && f_blit && f_textures && f_sr) {
            if (!ct_arrow_ok[b]) {
                u32 s[1];
                gstr(s, b ? "gui/newgui/arrowRight.png" : "gui/newgui/arrowLeft.png");
                for (int j = 0; j < 16; j++) ct_arrow_tex[b][j] = 0;
                f_gettex(ct_arrow_tex[b], f_textures(client), s, 0);
                f_strdtor(s);
                ct_arrow_ok[b] = 1;
            }
            int s8 = bh >= 16 ? 8 : 6;
            int rect[4] = {r[b * 4] + (bw - s8) / 2, r[b * 4 + 1] + (bh - s8) / 2, s8, s8};
            f_blit(f_sr(), ct_arrow_tex[b], rect);
        }
    }
    char label[16];
    snprintf(label, sizeof label, "%d/%d", ct_page + 1, ct_pages());
    float white[4] = {1, 1, 1, 1};
    int lw = 18;
    if (font && f_fontwidth) { u32 s[1]; gstr(s, label); lw = f_fontwidth(font, s, 1.f, 0); f_strdtor(s); }
    if (font) draw_text_col(font, x + w / 2 - lw / 2, y + (h - 8) / 2, label, white);
    return 1;
}

/* taps on the arrows (Multitouch::feed: w[1] state, w[2] x, w[3] y in pixels) */
static int ct_touch(u32 *w, float scale) {
    if (!ct_count || now_ms() - ct_drawn_at > 300) return 0;          /* only while the screen shows them */
    float x = (short)w[2] / scale, y = (short)w[3] / scale;
    float bw = ct_rect[3] + 4;
    for (int b = 0; b < 2; b++) {
        float x0 = b ? ct_rect[0] + ct_rect[2] - bw : ct_rect[0];
        if (x >= x0 - 3 && x < x0 + bw + 3 && y >= ct_rect[1] - 4 && y < ct_rect[1] + ct_rect[3] + 4) {
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

static int patch_slot(u32 vt, int idx, void *fn, void **orig);
static void creative_init(void *mc) {
    f_fontwidth = dlsym(mc, "_ZNK4Font13getLineLengthERKSsfb");
    ct_cname = dlsym(mc, "_ZNK14ContainerModel22getContainerStringNameEv");
    ct_bag = dlsym(mc, "_ZN9UIControl14getPropertyBagEv");
    ct_json_at = dlsym(mc, "_ZN4Json5ValueixEPKc");
    ct_json_int = dlsym(mc, "_ZN4Json5ValueC1Ei");
    ct_json_set = dlsym(mc, "_ZN4Json5ValueaSERKS0_");
    ct_json_dtor = dlsym(mc, "_ZN4Json5ValueD2Ev");
    void *add = dlsym(mc, "_ZN21ContainerManagerModel13_addContainerESt10shared_ptrI14ContainerModelE");
    void *dtor = dlsym(mc, "_ZN29CraftingContainerManagerModelD2Ev");
    if (!dtor) dtor = dlsym(mc, "_ZN29CraftingContainerManagerModelD1Ev");
    u32 vt = (u32)dlsym(mc, "_ZTV21InventoryItemRenderer");
    int ok = add && dtor && ct_cname;
    if (ok) ok = nh_hook_c((u32)add, hc_ct_add) >= 0 && nh_hook_c((u32)dtor, hc_ct_mgr_dtor) >= 0;
    ct_ok = ok;
    if (vt) patch_slot(vt, 4, h_iir_update, (void **)&o_iir_update);
    LOG("creative tabs: %s, icons %s", ok ? "ok" : "unavailable", vt ? "ok" : "-");
}
