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

static void (*ct_changed)(void *model, int slot);       /* ContainerModel::_onItemChanged: the screen re-reads the slot */
static void ct_notify(char *m, int before) {
    int n = *(int *)(m + 0xc);
    if (before > n) n = before;
    if (ct_changed) for (int k = 0; k < n; k++) ct_changed(m, k);
}
static void ct_put(int i, int page) {
    char *m = ct_cm[i];
    if (!m) return;
    int before = *(int *)(m + 0xc);
    if (page == 0) {
        if (ct_swapped[i]) {
            *(u32 *)(m + 0xc) = ct_saved[i][0];
            for (int k = 0; k < 3; k++) *(u32 *)(m + 0x14 + 4 * k) = ct_saved[i][1 + k];
            ct_swapped[i] = 0;
            ct_notify(m, before);
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
    ct_notify(m, before);
}
static void ct_hid_forget(void);
static int ct_fix_tab;                                 /* after a page switch: leave a tab the page does not have */
static void ct_apply(int page) {
    for (int i = 0; i < 4; i++) ct_put(i, page);
    ct_page = page;
    ct_fix_tab = 1;
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
    ct_hid_forget();
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

/* the tab pictures: update() runs only when the game thinks something changed, so a page switch would keep the old
 * picture until a tap - our tab icons are updated on every frame they are drawn */
static void (*o_iir_render)(void *self, void *client, void **ctrl, int pass);
static void h_iir_render(void *self, void *client, void **ctrl, int pass) {
    char *cc = ctrl ? *(char **)ctrl : 0;
    const char *nm = cc ? sdata(cc + 0xc) : 0;
    if (pass == 0 && nm && ct_count && nm[0] == 'n' && !memcmp(nm, "nova_ct_icon_", 13)) h_iir_update(self, client, ctrl);
    o_iir_render(self, client, ctrl, pass);
}

static float ct_rect[2][4];                          /* the two arrow controls, gui units */
static void *ct_tb[2];
static u32 ct_arrow_tex[2][16];
static int ct_arrow_ok[2];
static const char *ct_keys[4] = {"craftingScreen.tab.buildingBlocks", "craftingScreen.tab.decorations",
                                 "craftingScreen.tab.tools", "craftingScreen.tab.miscellaneous"};

/* tabs a page does not have: the pictures (parents of nova_ct_icon_<k>) and the buttons (interactive_tabs/<name>) */
static const char *ct_toggles[4] = {"building_blocks", "decorations", "tools", "miscellaneous"};
/* hiding a control the game also shows/hides itself (front/back pictures of a tab): remember what it was and give it
 * back when the tab is on the page again (forcing "visible" would show both pictures of a tab) */
#define CT_HID 64
static char *ct_hid[CT_HID];
static char ct_hid_was[CT_HID];
static int ct_hid_n;
static float ct_block[4][4];                           /* rects of hidden tab buttons: taps there are swallowed */
static int ct_block_n;
static void ct_vis(char *c, int on) {
    int i;
    for (i = 0; i < ct_hid_n; i++) if (ct_hid[i] == c) break;
    if (!on) {
        if (i == ct_hid_n && ct_hid_n < CT_HID) { ct_hid[ct_hid_n] = c; ct_hid_was[ct_hid_n] = c[0x30] & 4; ct_hid_n++; }
        c[0x30] &= ~4;
    } else if (i < ct_hid_n) {
        c[0x30] = (char)((c[0x30] & ~4) | ct_hid_was[i]);
        ct_hid[i] = ct_hid[--ct_hid_n]; ct_hid_was[i] = ct_hid_was[ct_hid_n];
    }
}
static void ct_hid_forget(void) { ct_hid_n = 0; ct_block_n = 0; }
static const char *ct_panels[4] = {"creative_screen_building_blocks", "creative_screen_decorations",
                                   "creative_screen_tools", "creative_screen_miscellaneous"};
static char *ct_panel[4];
static void ct_tabs_walk(char *c, int depth, int in_tabs) {
    if (!c || depth > 24) return;
    char **it = *(char ***)(c + 0x40), **end = *(char ***)(c + 0x44);
    for (; it && it < end; it += 2) {
        char *ch = it[0];
        if (!ch) continue;
        const char *nm = sdata(ch + 0xc);
        if (nm && nm[0] == 'n' && !memcmp(nm, "nova_ct_icon_", 13)) {
            int k = nm[13] - '0';
            if (k >= 0 && k < 4) ct_vis(c, ct_page == 0 || ct_tab(ct_page, k) != 0);
            continue;
        }
        if (in_tabs && nm) for (int k = 0; k < 4; k++) if (streq(nm, ct_toggles[k])) {
            int on = ct_page == 0 || ct_tab(ct_page, k) != 0;
            ct_vis(ch, on);
            if (!on && ct_block_n < 4) {
                float *r = ct_block[ct_block_n++];
                r[0] = *(float *)(ch + 0x10); r[1] = *(float *)(ch + 0x14); r[2] = *(float *)(ch + 0x18); r[3] = *(float *)(ch + 0x1c);
            }
        }
        if (nm && nm[0] == 'c') for (int k = 0; k < 4; k++) if (streq(nm, ct_panels[k])) ct_panel[k] = ch;
        ct_tabs_walk(ch, depth + 1, nm && streq(nm, "interactive_tabs"));
    }
}

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
        float col[4] = {0.4f, 0.95f, 0.88f, 1.f}, grey[4] = {0.75f, 0.78f, 0.85f, 1.f};
        if (font && txt[0]) draw_text_col(font, x, y, txt, col);
        if (font && ct_count) {                            /* page number at the right end of the row */
            char pg[16];
            snprintf(pg, sizeof pg, "%d/%d", ct_page + 1, ct_pages());
            int lw = 18;
            if (f_fontwidth) { u32 st[1]; gstr(st, pg); lw = f_fontwidth(font, st, 1.f, 0); f_strdtor(st); }
            draw_text_col(font, x + w - lw, y, pg, grey);
        }
        return 1;
    }
    int b = !memcmp(nm + 8, "next", 4) ? 1 : !memcmp(nm + 8, "prev", 4) ? 0 : -1;
    if (b < 0 || !ct_count) return 1;
    int want = ct_want;
    if (want >= 0) { ct_want = -1; if (want != ct_page) ct_apply(want); }
    if (b == 0) {                                          /* once per frame: tabs this page has */
        char *root = cc;
        for (int d = 0; d < 40; d++) { char *p = *(char **)(root + 0x38); if (!p) break; root = p; }
        for (int k = 0; k < 4; k++) ct_panel[k] = 0;
        ct_block_n = 0;
        ct_tabs_walk(root, 0, 0);
        if (ct_fix_tab) {                                  /* the open tab is not on this page: show the first one */
            ct_fix_tab = 0;
            for (int k = 1; k < 4; k++)
                if (ct_panel[k] && (ct_panel[k][0x30] & 4) && ct_page > 0 && !ct_tab(ct_page, k) && ct_panel[0]) {
                    ct_panel[k][0x30] &= ~4;                  /* the game's own tab switch shows them again */
                    ct_panel[0][0x30] |= 4;
                }
        }
    }
    ct_rect[b][0] = x; ct_rect[b][1] = y; ct_rect[b][2] = w; ct_rect[b][3] = h;
    ct_drawn_at = now_ms();
    if (!ct_tb[b]) ct_tb[b] = tb_new(client, "", 0);
    if (ct_tb[b]) {
        int *g = gel(ct_tb[b]);
        g[0] = (int)x; g[1] = (int)y; g[2] = (int)w; g[3] = (int)h;
        tb_render(ct_tb[b], client, -1, -1);
    }
    if (f_gettex && f_blit && f_textures && f_sr) {
        if (!ct_arrow_ok[b]) {
            u32 st[1];
            gstr(st, b ? "gui/newgui/arrowRight.png" : "gui/newgui/arrowLeft.png");
            for (int j = 0; j < 16; j++) ct_arrow_tex[b][j] = 0;
            f_gettex(ct_arrow_tex[b], f_textures(client), st, 0);
            f_strdtor(st);
            ct_arrow_ok[b] = 1;
        }
        int s8 = h >= 18 ? 12 : 8;
        int rect[4] = {(int)(x + (w - s8) / 2), (int)(y + (h - s8) / 2), s8, s8};
        f_blit(f_sr(), ct_arrow_tex[b], rect);
    }
    return 1;
}

/* taps on the arrows (Multitouch::feed: w[1] state, w[2] x, w[3] y in pixels) */
static int ct_touch(u32 *w, float scale) {
    if (!ct_count || now_ms() - ct_drawn_at > 300) return 0;          /* only while the screen shows them */
    float x = (short)w[2] / scale, y = (short)w[3] / scale;
    for (int i = 0; i < ct_block_n; i++) {
        float *r = ct_block[i];
        if (x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]) return 1;
    }
    for (int b = 0; b < 2; b++) {
        float *r = ct_rect[b];
        if (x >= r[0] - 3 && x < r[0] + r[2] + 3 && y >= r[1] - 3 && y < r[1] + r[3] + 3) {
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
    ct_changed = dlsym(mc, "_ZN14ContainerModel14_onItemChangedEi");
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
    if (vt) { patch_slot(vt, 4, h_iir_update, (void **)&o_iir_update); patch_slot(vt, 5, h_iir_render, (void **)&o_iir_render); }
    LOG("creative tabs: %s, icons %s", ok ? "ok" : "unavailable", vt ? "ok" : "-");
}
