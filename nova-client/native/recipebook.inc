/* recipebook.inc - NOVA recipe book (like Java's recipe book / JEI), drawn by the game in the survival inventory and
 * at the crafting table: a small book button above the inventory's top-left corner opens a curtain to the left of it.
 *
 *   tabs (top): all craftable items of the game | search | one per mod that has recipes (icon of its creative tab)
 *   panel: pages of items (25 px buttons), page arrows; tap = a window in the middle with the recipe (crafting grid or
 *   furnace), several recipes -> arrows, tap an ingredient = its recipes, X closes; hold a finger = the name.
 *
 * Recipes are read from the game itself: Recipes::getRecipes() (ShapedRecipe: +0x30 w, +0x34 h, +0x38 vector of
 * ItemInstance (20 bytes), result vector +0x44; ShapelessRecipe: +0x30 ingredients, +0x3c result; vtable slot 7
 * isShapeless) and FurnaceRecipes (two std::map<int, ItemInstance>: by id at +0, by id << 16 | aux at +0x18; tree
 * node: +8 left, +0xc right, +0x10 key, +0x14 value). On a server the game's book is what the server sent
 * (CraftingDataPacket), plugins' recipes included. Recipes of mods are told apart by recipes.inc (rc_list[].mod).
 * Pictures: gui/nova/rb/<name>.png (tools/gen_recipebook.py, Java 1.19.4 GUI) and gui/newgui/X.png of the game.
 * The UI: control "nova_rb" (full screen, ui_new/inventory_screen.json), touches from Multitouch::feed (net.inc). */

typedef struct { short id, aux; } RBIt;
static int rb_strlen(const char *s) { int n = 0; while (s && s[n]) n++; return n; }
#define RB_MOD_MAX 24
static struct { char id[32], title[48]; int icon, icon_aux; } rb_mods[RB_MOD_MAX];
static int rb_mod_n;

int Java_com_nova_client_ModBridge_nativeRecipeMods(void **env, void *cls, void *jspec) {
    static char spec[4096];
    jstr_copy(env, jspec, spec, sizeof spec);
    rb_mod_n = 0;
    char *p = spec;
    while (*p && rb_mod_n < RB_MOD_MAX) {
        int k = 0;
        while (*p && *p != '\x1f' && *p != '\x1e') { if (k < 31) rb_mods[rb_mod_n].id[k++] = *p; p++; }
        rb_mods[rb_mod_n].id[k] = 0;
        if (*p == '\x1f') p++;
        k = 0;
        while (*p && *p != '\x1f' && *p != '\x1e') { if (k < 47) rb_mods[rb_mod_n].title[k++] = *p; p++; }
        rb_mods[rb_mod_n].title[k] = 0;
        if (*p == '\x1f') p++;
        rb_mods[rb_mod_n].icon = ct_num(&p); if (*p == '\x1f') p++;
        rb_mods[rb_mod_n].icon_aux = ct_num(&p);
        while (*p && *p != '\x1e') p++;
        if (*p == '\x1e') p++;
        if (rb_mods[rb_mod_n].id[0]) rb_mod_n++;
    }
    return rb_mod_n;
}

static int rb_mod_index(const char *id) {
    for (int i = 0; i < rb_mod_n; i++) if (streq(rb_mods[i].id, id)) return i;
    return -1;
}

/* ---------------- game functions ---------------- */
static void *(*rb_ir_instance)(void);
static void (*rb_ir_item)(void *ir, void *ii, int frame, float x, float y, float a, float b, float c, int flag);
static void (*rb_ii_name)(void *out, void *ii);
static const char *rb_x_tex_name = "gui/newgui/X.png";

/* ---------------- state ---------------- */
static volatile int rb_open, rb_tab, rb_page, rb_tab_off;
static long rb_drawn_at;
static int rb_gen_built = -1, rb_built_mods = -1;
#define RB_MAX 1500
static RBIt rb_items[RB_MAX];             /* every item with a recipe, sorted by id, aux */
static unsigned int rb_cat[RB_MAX];       /* bit 0: the game's recipes, bit 1 + k: mod k */
static char *rb_lname[RB_MAX];            /* lower-case names (search), made on first search */
static int rb_n;
static char rb_query[64];
static volatile int rb_query_dirty;
static int rb_view[RB_MAX], rb_view_n;    /* indices of the open tab (after search) */
/* recipe window */
static volatile int rb_win;               /* 0 closed */
static RBIt rb_win_item;
static int rb_win_k;                       /* which of its recipes */
static RBIt rb_hist[16];
static int rb_hist_n;
/* touch */
static volatile int rb_down, rb_moved;
static volatile float rb_dx, rb_dy, rb_mx, rb_my;
static volatile long rb_down_at;
static volatile int rb_tap_pending;
static volatile float rb_tap_x, rb_tap_y;
static int rb_kbd;                         /* the search keyboard is up */

/* geometry of the last frame (gui units) */
static float rb_W, rb_H;
static float rb_btn[4], rb_panel[4], rb_win_rect[4], rb_search[4];
static int rb_cols, rb_rows, rb_gx, rb_gy;
/* hit boxes of item pictures drawn this frame: x, y, size, id, aux */
#define RB_HITS 128
static float rb_hit[RB_HITS][3];
static RBIt rb_hit_it[RB_HITS];
static int rb_hit_n;

/* ---------------- drawing helpers ---------------- */
#define RB_TEX 40
static struct { const char *name; u32 tex[16]; int ok; } rb_tex[RB_TEX];
static u32 *rb_texture(void *client, const char *name) {
    int i;
    for (i = 0; i < RB_TEX && rb_tex[i].name; i++) if (rb_tex[i].name == name || streq(rb_tex[i].name, name)) break;
    if (i >= RB_TEX) return 0;
    if (!rb_tex[i].name) rb_tex[i].name = name;
    if (!rb_tex[i].ok) {
        char path[96];
        snprintf(path, sizeof path, name[0] == 'g' ? "%s" : "gui/nova/rb/%s.png", name);
        u32 st[1];
        gstr(st, path);
        for (int j = 0; j < 16; j++) rb_tex[i].tex[j] = 0;
        f_gettex(rb_tex[i].tex, f_textures(client), st, 0);
        f_strdtor(st);
        rb_tex[i].ok = 1;
    }
    return rb_tex[i].tex;
}
static void rb_blit(void *client, const char *name, float x, float y, float w, float h) {
    u32 *t = rb_texture(client, name);
    if (!t) return;
    int r[4] = {(int)x, (int)y, (int)w, (int)h};
    f_blit(f_sr(), t, r);
}
static void rb_fill(float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
    float c[4] = {r, g, b, a};
    if (f_fill) f_fill(f_sr(), x0, y0, x1, y1, c);
}
static char rb_ii[2][24];
static void *rb_make(int slot, int id, int aux, int count) {
    char *ii = rb_ii[slot & 1];
    memset(ii, 0, 24);
    if (sf_item_ctor && id > 0 && id < 512 && sf_items && (id < 256 ? (mf_blocks && mf_blocks[id]) : sf_items[id] != 0))
        sf_item_ctor(ii, id, count, aux);
    return ii;
}
static void rb_item(int id, int aux, float x, float y) {
    if (!rb_ir_instance || !rb_ir_item || id <= 0) return;
    void *ii = rb_make(0, id, aux < 0 ? 0 : aux, 1);
    if (!*(void **)(ii + 0xc) && !*(void **)(ii + 0x10)) return;
    rb_ir_item(rb_ir_instance(), ii, 0, x, y, 1.f, 1.f, 1.f, 0);
}
static void rb_hit_add(float x, float y, float s, int id, int aux) {
    if (rb_hit_n >= RB_HITS) return;
    rb_hit[rb_hit_n][0] = x; rb_hit[rb_hit_n][1] = y; rb_hit[rb_hit_n][2] = s;
    rb_hit_it[rb_hit_n].id = (short)id; rb_hit_it[rb_hit_n].aux = (short)aux;
    rb_hit_n++;
}
static void rb_name(int id, int aux, char *out, int n) {
    out[0] = 0;
    if (!rb_ii_name) return;
    void *ii = rb_make(1, id, aux < 0 ? 0 : aux, 1);
    if (!*(void **)(ii + 0xc) && !*(void **)(ii + 0x10)) return;
    u32 s[1] = {0};
    rb_ii_name(s, ii);
    const char *c = sdata(s);
    snprintf(out, n, "%s", c ? c : "");
    f_strdtor(s);
}
static int rb_text_w(void *font, const char *t) {
    if (!f_fontwidth || !font) return (int)rb_strlen(t) * 6;
    u32 st[1];
    gstr(st, t);
    int w = f_fontwidth(font, st, 1.f, 0);
    f_strdtor(st);
    return w;
}

/* UTF-8 lower case for Latin and Cyrillic (search) */
static void rb_lower(const char *s, char *out, int n) {
    int k = 0;
    const unsigned char *p = (const unsigned char *)s;
    while (*p && k < n - 3) {
        if (*p >= 'A' && *p <= 'Z') { out[k++] = (char)(*p++ + 32); continue; }
        if (p[0] == 0xD0 && p[1] >= 0x90 && p[1] <= 0x9F) { out[k++] = (char)0xD0; out[k++] = (char)(p[1] + 0x20); p += 2; continue; }   /* А-П */
        if (p[0] == 0xD0 && p[1] >= 0xA0 && p[1] <= 0xAF) { out[k++] = (char)0xD1; out[k++] = (char)(p[1] - 0x20); p += 2; continue; }   /* Р-Я */
        if (p[0] == 0xD0 && p[1] == 0x81) { out[k++] = (char)0xD1; out[k++] = (char)0x91; p += 2; continue; }                          /* Ё */
        out[k++] = (char)*p++;
    }
    out[k] = 0;
}
static int rb_contains(const char *hay, const char *needle) {
    if (!needle[0]) return 1;
    for (const char *h = hay; *h; h++) {
        const char *a = h, *b = needle;
        while (*a && *b && *a == *b) { a++; b++; }
        if (!*b) return 1;
    }
    return 0;
}

/* ---------------- recipes of the game ---------------- */
static int rb_wild(int aux) { return aux == -1 || aux == 32767 || aux == 0x7fff; }
static int rb_ii_id(char *ii) {
    if (!ii || !*(unsigned char *)ii) return 0;
    return ev_item_id(ii);
}
static int rb_ii_aux(char *ii) { return ii ? *(short *)(ii + 2) : 0; }

static u32 *rb_book_vec(void) {
    void *book = rc_book ? rc_book() : 0;
    return book && rc_list_of ? rc_list_of(book) : 0;
}
static int rb_shapeless(char *r) {
    void **vt = *(void ***)r;
    return ((int (*)(void *))vt[7])(r);
}
static char *rb_result(char *r) {                        /* first result ItemInstance */
    char **v = (char **)(r + (rb_shapeless(r) ? 0x3c : 0x44));
    return v[0] && v[1] > v[0] ? v[0] : 0;
}

/* a recipe of the book that a mod added (recipes.inc keeps them): its mod, else -1 */
static int rb_recipe_mod(int out, int aux, int furnace, int in, int inaux) {
    for (int i = 0; i < rc_count; i++) {
        MRecipe *m = &rc_list[i];
        if (m->kind != furnace || m->out != out || (m->aux != aux && !rb_wild(m->aux))) continue;
        if (furnace && (m->kid[0] != in || (m->kaux[0] != inaux && !rb_wild(m->kaux[0])))) continue;
        return m->mod;
    }
    return -1;
}

static void rb_add(int id, int aux, int cat) {
    if (id <= 0 || id >= 512) return;
    if (rb_wild(aux)) aux = 0;
    for (int i = 0; i < rb_n; i++) if (rb_items[i].id == id && rb_items[i].aux == aux) { rb_cat[i] |= 1u << cat; return; }
    if (rb_n >= RB_MAX) return;
    rb_items[rb_n].id = (short)id; rb_items[rb_n].aux = (short)aux; rb_cat[rb_n] = 1u << cat; rb_lname[rb_n] = 0;
    rb_n++;
}

/* furnace map walk: fn(node key, result) */
static void rb_tree(char *node, char *header, int byaux, int depth, void (*fn)(int key, char *res, int byaux)) {
    if (!node || node == header || depth > 40) return;
    rb_tree(*(char **)(node + 8), header, byaux, depth + 1, fn);
    fn(*(int *)(node + 0x10), node + 0x14, byaux);
    rb_tree(*(char **)(node + 0xc), header, byaux, depth + 1, fn);
}
static void rb_furnace_walk(void (*fn)(int key, char *res, int byaux)) {
    char *fr = rc_furnace ? rc_furnace() : 0;
    if (!fr) return;
    rb_tree(*(char **)(fr + 8), fr + 4, 0, 0, fn);
    rb_tree(*(char **)(fr + 0x20), fr + 0x1c, 1, 0, fn);
}
static void rb_index_furnace(int key, char *res, int byaux) {
    int in = byaux ? key >> 16 : key, inaux = byaux ? key & 0xffff : 0;
    int id = rb_ii_id(res), aux = rb_ii_aux(res);
    int m = rb_recipe_mod(id, aux, 1, in, inaux);
    rb_add(id, aux, m >= 0 && m < 30 ? m + 1 : 0);
}

static int rb_cmp(int i, int j) {
    if (rb_items[i].id != rb_items[j].id) return rb_items[i].id - rb_items[j].id;
    return rb_items[i].aux - rb_items[j].aux;
}
static void rb_build(void) {
    rb_n = 0;
    u32 *v = rb_book_vec();
    if (v) for (char **p = (char **)v[0]; p < (char **)v[1]; p++) {
        char *r = *p, *res = r ? rb_result(r) : 0;
        if (!res) continue;
        int id = rb_ii_id(res), aux = rb_ii_aux(res);
        int m = rb_recipe_mod(id, aux, 0, 0, 0);
        rb_add(id, aux, m >= 0 && m < 30 ? m + 1 : 0);
    }
    rb_furnace_walk(rb_index_furnace);
    for (int i = 1; i < rb_n; i++) {                       /* insertion sort: id, aux */
        RBIt t = rb_items[i]; unsigned c = rb_cat[i]; int j = i - 1;
        while (j >= 0 && (rb_items[j].id > t.id || (rb_items[j].id == t.id && rb_items[j].aux > t.aux))) {
            rb_items[j + 1] = rb_items[j]; rb_cat[j + 1] = rb_cat[j]; j--;
        }
        rb_items[j + 1] = t; rb_cat[j + 1] = c;
    }
    for (int i = 0; i < rb_n; i++) rb_lname[i] = 0;
    rb_gen_built = rc_gen;
    rb_built_mods = rc_count;
    LOG("recipe book: %d item(s) with recipes", rb_n);
}

/* tabs: 0 the game, 1 search, 2.. mods that have recipes */
static int rb_tab_mod[RB_MOD_MAX], rb_tab_n;
static void rb_tabs(void) {
    rb_tab_n = 2;
    for (int k = 0; k < rb_mod_n && k < 30; k++) {
        int any = 0;
        for (int i = 0; i < rb_n && !any; i++) if (rb_cat[i] & (1u << (k + 1))) any = 1;
        if (any) rb_tab_mod[rb_tab_n++ - 2] = k;
    }
}
static void rb_filter(void) {
    rb_view_n = 0;
    if (rb_tab == 1) {
        char q[64];
        rb_lower(rb_query, q, sizeof q);
        for (int i = 0; i < rb_n; i++) {
            if (!rb_lname[i]) {
                char nm[64], lo[64];
                rb_name(rb_items[i].id, rb_items[i].aux, nm, sizeof nm);
                rb_lower(nm, lo, sizeof lo);
                rb_lname[i] = f_new(rb_strlen(lo) + 1);
                memcpy(rb_lname[i], lo, rb_strlen(lo) + 1);
            }
            if (rb_contains(rb_lname[i], q)) rb_view[rb_view_n++] = i;
        }
        return;
    }
    unsigned bit = rb_tab == 0 ? 1u : rb_tab - 2 < rb_tab_n - 2 ? 1u << (rb_tab_mod[rb_tab - 2] + 1) : 0;
    for (int i = 0; i < rb_n; i++) if (rb_cat[i] & bit) rb_view[rb_view_n++] = i;
}

/* recipes that make one item: crafting (book order) then furnace */
#define RB_REC 32
static char *rb_rec[RB_REC];               /* Recipe* or furnace pseudo (low bit set: index into rb_furn) */
static struct { int in, inaux; char res[24]; } rb_furn[RB_REC];
static int rb_rec_n, rb_furn_n;
static RBIt rb_rec_for;
static void rb_find_furnace(int key, char *res, int byaux) {
    if (rb_rec_n >= RB_REC) return;
    if (rb_ii_id(res) != rb_rec_for.id || (rb_ii_aux(res) != rb_rec_for.aux && !rb_wild(rb_ii_aux(res)))) return;
    int k = rb_furn_n++;
    rb_furn[k].in = byaux ? key >> 16 : key;
    rb_furn[k].inaux = byaux ? key & 0xffff : -1;
    memcpy(rb_furn[k].res, res, 20);
    rb_rec[rb_rec_n++] = (char *)(long)(k * 2 + 1);
}
static void rb_find(RBIt it) {
    rb_rec_n = 0; rb_furn_n = 0; rb_rec_for = it;
    u32 *v = rb_book_vec();
    if (v) for (char **p = (char **)v[0]; p < (char **)v[1] && rb_rec_n < RB_REC; p++) {
        char *r = *p, *res = r ? rb_result(r) : 0;
        if (!res) continue;
        int aux = rb_ii_aux(res);
        if (rb_ii_id(res) == it.id && (aux == it.aux || rb_wild(aux))) rb_rec[rb_rec_n++] = r;
    }
    rb_furnace_walk(rb_find_furnace);
}
static int rb_has_recipe(int id, int aux) {
    for (int i = 0; i < rb_n; i++) if (rb_items[i].id == id && (rb_items[i].aux == aux || rb_wild(aux))) return 1;
    return 0;
}

/* a wildcard ingredient (any data): show the variants the creative list has, one a second (like JEI) */
static int rb_cycle_aux(int id, int aux) {
    if (!rb_wild(aux)) return aux;
    char **list = mf_creative_list;
    short seen[16]; int n = 0;
    if (list) for (char *p = list[0]; p < list[1] && n < 16; p += 20) if (rb_ii_id(p) == id) seen[n++] = (short)rb_ii_aux(p);
    if (!n) return 0;
    return seen[(now_ms() / 1000) % n];
}

/* ---------------- drawing ---------------- */
static void rb_panel_bg(void *client, float x, float y, float w, float h) {
    rb_blit(client, "bg_tl", x, y, 4, 4);
    rb_blit(client, "bg_t", x + 4, y, w - 8, 4);
    rb_blit(client, "bg_tr", x + w - 4, y, 4, 4);
    rb_blit(client, "bg_l", x, y + 4, 4, h - 8);
    rb_blit(client, "bg_c", x + 4, y + 4, w - 8, h - 8);
    rb_blit(client, "bg_r", x + w - 4, y + 4, 4, h - 8);
    rb_blit(client, "bg_bl", x, y + h - 4, 4, 4);
    rb_blit(client, "bg_b", x + 4, y + h - 4, w - 8, 4);
    rb_blit(client, "bg_br", x + w - 4, y + h - 4, 4, 4);
}
static void rb_tab_icon(int t, int *id, int *aux) {
    if (t == 0) { *id = 2; *aux = 0; return; }                  /* the game: grass block */
    if (t == 1) { *id = 345; *aux = 0; return; }                /* search: compass, as Java's search tab */
    int k = rb_tab_mod[t - 2];
    *id = rb_mods[k].icon; *aux = rb_mods[k].icon_aux;
}

static void rb_draw_window(void *client, void *font) {
    float W = rb_W, H = rb_H;
    rb_fill(0, 0, W, H, 0, 0, 0, 0.55f);
    float wx = (float)(int)(W / 2 - 88), wy = (float)(int)(H / 2 - 42);
    rb_win_rect[0] = wx; rb_win_rect[1] = wy; rb_win_rect[2] = 176; rb_win_rect[3] = 84;
    if (rb_win_k >= rb_rec_n) rb_win_k = 0;
    char *r = rb_rec_n ? rb_rec[rb_win_k] : 0;
    int furnace = r && ((long)r & 1);
    rb_blit(client, furnace ? "furnace" : "craft", wx, wy, 176, 84);
    char nm[64];
    rb_name(rb_win_item.id, rb_win_item.aux, nm, sizeof nm);
    float dark[4] = {0.25f, 0.25f, 0.25f, 1.f};
    if (font) draw_text_col(font, wx + 8, wy + 5, nm, dark);
    rb_blit(client, rb_x_tex_name, wx + 176 - 15, wy + 3, 12, 12);
    if (!r) return;
    if (furnace) {
        int k = (int)((long)r >> 1);
        int in = rb_furn[k].in, inaux = rb_furn[k].inaux < 0 ? -1 : rb_furn[k].inaux;
        int a = rb_cycle_aux(in, inaux);
        rb_item(in, a, wx + 56, wy + 17); rb_hit_add(wx + 56, wy + 17, 16, in, a);
        char *res = rb_furn[k].res;
        rb_item(rb_ii_id(res), rb_ii_aux(res), wx + 116, wy + 35); rb_hit_add(wx + 116, wy + 35, 16, rb_ii_id(res), rb_ii_aux(res));
    } else {
        int sl = rb_shapeless(r);
        char *res = rb_result(r);
        if (sl) {
            char **v = (char **)(r + 0x30);
            int i = 0;
            for (char *p = v[0]; p && p < v[1] && i < 9; p += 20, i++) {
                int id = rb_ii_id(p), a = rb_cycle_aux(id, rb_ii_aux(p));
                float x = wx + 30 + (i % 3) * 18, y = wy + 17 + (i / 3) * 18;
                rb_item(id, a, x, y); rb_hit_add(x, y, 16, id, a);
            }
            float grey[4] = {0.35f, 0.35f, 0.35f, 1.f};
            if (font) draw_text_col(font, wx + 90, wy + 55, "~", grey);           /* any order */
        } else {
            int w = *(int *)(r + 0x30), h = *(int *)(r + 0x34);
            char **v = (char **)(r + 0x38);
            for (int y = 0; y < h && y < 3; y++) for (int x = 0; x < w && x < 3; x++) {
                char *p = v[0] + (y * w + x) * 20;
                if (!v[0] || p >= v[1]) continue;
                int id = rb_ii_id(p);
                if (!id) continue;
                int a = rb_cycle_aux(id, rb_ii_aux(p));
                float ix = wx + 30 + x * 18, iy = wy + 17 + y * 18;
                rb_item(id, a, ix, iy); rb_hit_add(ix, iy, 16, id, a);
            }
        }
        if (res) {
            rb_item(rb_ii_id(res), rb_ii_aux(res), wx + 124, wy + 35); rb_hit_add(wx + 124, wy + 35, 16, rb_ii_id(res), rb_ii_aux(res));
            int cnt = *(unsigned char *)res;
            if (cnt > 1 && font) {
                char c[8]; snprintf(c, sizeof c, "%d", cnt);
                float white[4] = {1, 1, 1, 1};
                draw_text_col(font, wx + 141 - rb_text_w(font, c), wy + 44, c, white);
            }
        }
    }
    if (rb_rec_n > 1) {                                        /* several recipes: 1/3 and arrows under the result */
        char pg[16]; snprintf(pg, sizeof pg, "%d/%d", rb_win_k + 1, rb_rec_n);
        float grey[4] = {0.25f, 0.25f, 0.25f, 1.f};
        int tw = rb_text_w(font, pg);
        if (font) draw_text_col(font, wx + 132 - tw / 2, wy + 64, pg, grey);
        rb_blit(client, "arrow_prev", wx + 110, wy + 59, 12, 17);
        rb_blit(client, "arrow_next", wx + 144, wy + 59, 12, 17);
    }
}

static void rb_tooltip(void *client, void *font) {
    if (!rb_down || now_ms() - rb_down_at < 350) return;
    for (int i = 0; i < rb_hit_n; i++) {
        float *h = rb_hit[i];
        if (rb_mx < h[0] - 2 || rb_mx >= h[0] + h[2] + 2 || rb_my < h[1] - 2 || rb_my >= h[1] + h[2] + 2) continue;
        char nm[64];
        rb_name(rb_hit_it[i].id, rb_hit_it[i].aux, nm, sizeof nm);
        if (!nm[0] || !font) return;
        int tw = rb_text_w(font, nm);
        float x = rb_mx - tw / 2.f, y = rb_my - 28;                /* above the finger, as Java's tooltip */
        if (x < 4) x = 4;
        if (x + tw + 4 > rb_W) x = rb_W - tw - 4;
        if (y < 4) y = rb_my + 14;
        rb_fill(x - 4, y - 4, x + tw + 4, y + 12, 0.06f, 0.0f, 0.06f, 0.94f);
        rb_fill(x - 3, y - 3, x + tw + 3, y - 2, 0.31f, 0.0f, 1.0f, 0.5f);
        rb_fill(x - 3, y + 10, x + tw + 3, y + 11, 0.16f, 0.0f, 0.5f, 0.5f);
        float white[4] = {1, 1, 1, 1};
        draw_text_col(font, x, y, nm, white);
        return;
    }
}

static void rb_handle_tap(float x, float y);

static int rb_ui_render(void *client, char *cc) {
    const char *nm = sdata(cc + 0xc);
    if (!nm || !streq(nm, "nova_rb")) return 0;
    if (!f_blit || !f_gettex || !f_textures || !f_sr) return 1;
    float W = *(float *)(cc + 0x18), H = *(float *)(cc + 0x1c);
    rb_W = W; rb_H = H;
    rb_drawn_at = now_ms();
    void *font = *(void **)((char *)client + 0x7c);
    rb_hit_n = 0;
    if (rb_tap_pending) { rb_tap_pending = 0; rb_handle_tap(rb_tap_x, rb_tap_y); }
    float rx = (float)(int)(W / 2 - 78), ry = (float)(int)(H / 2 - 74);      /* common.root_panel: 156 x 148, centred */
    /* the book button: above the inventory's top-left corner */
    rb_btn[0] = rx + 1; rb_btn[1] = ry - 20; rb_btn[2] = 20; rb_btn[3] = 18;
    rb_blit(client, rb_open ? "button_on" : "button", rb_btn[0], rb_btn[1], 20, 18);
    if (!rb_open) { rb_tooltip(client, font); return 1; }
    if (rb_gen_built != rc_gen || rb_built_mods != rc_count) { rb_build(); rb_tabs(); rb_query_dirty = 1; }
    /* the curtain: left of the inventory, as wide as fits (Java's book is 147 x 166) */
    float pw = rx - 7;
    if (pw > 147) pw = 147;
    if (pw < 80) pw = 80;
    float ph = H - 36 - 6;
    if (ph > 166) ph = 166;
    float px = rx - 3 - pw, py = (float)(int)((H - ph - 26) / 2 + 26);
    if (px < 2) px = 2;
    rb_panel[0] = px; rb_panel[1] = py - 28; rb_panel[2] = pw; rb_panel[3] = ph + 28;
    /* tabs on top: the open one drawn over the panel's edge */
    int fit = (int)((pw - 4) / 27);
    if (fit < 2) fit = 2;
    if (rb_tab_off > rb_tab_n - fit) rb_tab_off = rb_tab_n - fit;
    if (rb_tab_off < 0) rb_tab_off = 0;
    for (int pass = 0; pass < 2; pass++) {
        if (pass == 1) rb_panel_bg(client, px, py, pw, ph);
        for (int i = 0; i < fit && rb_tab_off + i < rb_tab_n; i++) {
            int t = rb_tab_off + i, on = t == rb_tab;
            if (on != pass) continue;
            float tx = px + 3 + i * 27, ty = py - 28;
            rb_blit(client, on ? "tab_on" : "tab", tx, ty, 26, on ? 32 : 30);
            int id, aux;
            rb_tab_icon(t, &id, &aux);
            rb_item(id, aux, tx + 5, ty + 9);
        }
    }
    if (rb_tab_n > fit) {                                      /* more tabs than fit: arrows on the tab row */
        float ay = py - 26;
        rb_blit(client, "arrow_prev", px - 13 < 0 ? 0 : px - 13, ay, 12, 17);
        rb_blit(client, "arrow_next", px + pw + 1, ay, 12, 17);
    }
    if (rb_query_dirty) { rb_query_dirty = 0; rb_filter(); }
    /* search field */
    float gy0 = py + 8;
    rb_search[2] = 0;
    if (rb_tab == 1) {
        rb_search[0] = px + 8; rb_search[1] = py + 7; rb_search[2] = pw - 16; rb_search[3] = 14;
        rb_fill(px + 8, py + 7, px + pw - 8, py + 21, 0.63f, 0.63f, 0.63f, 1.f);
        rb_fill(px + 9, py + 8, px + pw - 9, py + 20, 0, 0, 0, 1.f);
        float col[4] = {0.88f, 0.88f, 0.88f, 1.f}, hint[4] = {0.5f, 0.5f, 0.5f, 1.f};
        if (font) {
            if (rb_query[0]) draw_text_col(font, px + 12, py + 10, rb_query, col);
            else { char hs[48]; loc_into("nova.rb.search", hs, sizeof hs); draw_text_col(font, px + 12, py + 10, rb_kbd ? "_" : hs, hint); }
        }
        gy0 = py + 25;
    }
    /* item pages */
    rb_cols = (int)((pw - 12) / 25); if (rb_cols < 1) rb_cols = 1;
    rb_rows = (int)((py + ph - 26 - gy0) / 25); if (rb_rows < 1) rb_rows = 1;
    int per = rb_cols * rb_rows, pages = (rb_view_n + per - 1) / per;
    if (pages < 1) pages = 1;
    if (rb_page >= pages) rb_page = pages - 1;
    if (rb_page < 0) rb_page = 0;
    rb_gx = (int)(px + (pw - rb_cols * 25) / 2); rb_gy = (int)gy0;
    for (int k = 0; k < per; k++) {
        int vi = rb_page * per + k;
        if (vi >= rb_view_n) break;
        float x = rb_gx + (k % rb_cols) * 25, y = rb_gy + (k / rb_cols) * 25;
        rb_blit(client, "slot", x, y, 25, 25);
        RBIt it = rb_items[rb_view[vi]];
        rb_item(it.id, it.aux, x + 4, y + 4);
        rb_hit_add(x + 4, y + 4, 17, it.id, it.aux);
    }
    if (!rb_view_n && font) {
        float grey[4] = {0.6f, 0.6f, 0.6f, 1.f};
        draw_text_col(font, px + 10, gy0 + 4, "-", grey);
    }
    char pg[16];
    snprintf(pg, sizeof pg, "%d/%d", rb_page + 1, pages);
    float white[4] = {1, 1, 1, 1};
    float by = py + ph - 23;
    if (font) draw_text_col(font, px + pw / 2 - rb_text_w(font, pg) / 2.f, by + 5, pg, white);
    if (pages > 1) {
        rb_blit(client, "arrow_prev", px + pw / 2 - 34, by, 12, 17);
        rb_blit(client, "arrow_next", px + pw / 2 + 22, by, 12, 17);
    }
    if (rb_win) rb_draw_window(client, font);
    rb_tooltip(client, font);
    return 1;
}

/* ---------------- input ---------------- */
static void rb_keyboard(int show);
static int rb_in(float x, float y, float x0, float y0, float w, float h) { return x >= x0 && x < x0 + w && y >= y0 && y < y0 + h; }
static void rb_open_item(RBIt it, int push) {
    rb_find(it);
    if (!rb_rec_n) return;
    if (push && rb_win && rb_hist_n < 16) rb_hist[rb_hist_n++] = rb_win_item;
    rb_win_item = it; rb_win_k = 0; rb_win = 1;
}
static void rb_handle_tap(float x, float y) {
    if (rb_in(x, y, rb_btn[0] - 3, rb_btn[1] - 3, rb_btn[2] + 6, rb_btn[3] + 6) && !rb_win) {
        rb_open = !rb_open;
        if (!rb_open && rb_kbd) rb_keyboard(0);
        return;
    }
    if (!rb_open) return;
    if (rb_win) {
        float *w = rb_win_rect;
        if (rb_in(x, y, w[0] + 176 - 20, w[1], 20, 18) || !rb_in(x, y, w[0], w[1], w[2], w[3])) {   /* X or outside */
            if (rb_hist_n > 0 && rb_in(x, y, w[0], w[1], w[2], w[3])) { rb_open_item(rb_hist[--rb_hist_n], 0); return; }
            rb_win = 0; rb_hist_n = 0;
            return;
        }
        if (rb_rec_n > 1 && rb_in(x, y, w[0] + 106, w[1] + 56, 20, 24)) { rb_win_k = (rb_win_k + rb_rec_n - 1) % rb_rec_n; return; }
        if (rb_rec_n > 1 && rb_in(x, y, w[0] + 140, w[1] + 56, 20, 24)) { rb_win_k = (rb_win_k + 1) % rb_rec_n; return; }
        for (int i = 0; i < rb_hit_n; i++) {
            float *h = rb_hit[i];
            if (!rb_in(x, y, h[0] - 1, h[1] - 1, h[2] + 2, h[2] + 2)) continue;
            RBIt it = rb_hit_it[i];
            if ((it.id != rb_win_item.id || it.aux != rb_win_item.aux) && rb_has_recipe(it.id, it.aux)) rb_open_item(it, 1);
            return;
        }
        return;
    }
    float px = rb_panel[0], py = rb_panel[1] + 28, pw = rb_panel[2], ph = rb_panel[3] - 28;
    /* tabs */
    int fit = (int)((pw - 4) / 27);
    if (fit < 2) fit = 2;
    for (int i = 0; i < fit && rb_tab_off + i < rb_tab_n; i++)
        if (rb_in(x, y, px + 3 + i * 27, py - 28, 26, 30)) {
            int t = rb_tab_off + i;
            if (t != rb_tab) { rb_tab = t; rb_page = 0; rb_query_dirty = 1; }
            if (t == 1 && !rb_kbd) rb_keyboard(1);
            if (t != 1 && rb_kbd) rb_keyboard(0);
            return;
        }
    if (rb_tab_n > fit) {
        if (rb_in(x, y, px - 16, py - 28, 16, 22)) { if (rb_tab_off > 0) rb_tab_off--; return; }
        if (rb_in(x, y, px + pw, py - 28, 16, 22)) { if (rb_tab_off < rb_tab_n - fit) rb_tab_off++; return; }
    }
    if (rb_search[2] > 0 && rb_in(x, y, rb_search[0], rb_search[1] - 2, rb_search[2], rb_search[3] + 4)) { rb_keyboard(1); return; }
    float by = py + ph - 23;
    if (rb_in(x, y, px + pw / 2 - 40, by - 3, 22, 23)) { rb_page--; if (rb_page < 0) rb_page = 1 << 20; return; }   /* wraps */
    if (rb_in(x, y, px + pw / 2 + 18, by - 3, 22, 23)) {
        int per = rb_cols * rb_rows, pages = (rb_view_n + per - 1) / per;
        rb_page = rb_page + 1 >= pages ? 0 : rb_page + 1;
        return;
    }
    for (int i = 0; i < rb_hit_n; i++) {
        float *h = rb_hit[i];
        if (rb_in(x, y, h[0] - 4, h[1] - 4, 25, 25)) { rb_open_item(rb_hit_it[i], 0); return; }
    }
}

/* Multitouch::feed(button, state, x, y, pointer): 1 = the screen shows the book (the touch is ours) */
static int rb_touch(u32 *w, float scale) {
    if (now_ms() - rb_drawn_at > 300) { rb_down = 0; return 0; }
    float x = (short)w[2] / scale, y = (short)w[3] / scale;
    int button = w[0] & 0xff, state = w[1] & 0xff;
    int ours = rb_in(x, y, rb_btn[0] - 3, rb_btn[1] - 3, rb_btn[2] + 6, rb_btn[3] + 6) ||
               (rb_open && (rb_win || rb_in(x, y, rb_panel[0] - 16, rb_panel[1], rb_panel[2] + 32, rb_panel[3])));
    if (button == 0) {                                         /* finger moves */
        if (rb_down) { rb_mx = x; rb_my = y; if ((x - rb_dx) * (x - rb_dx) + (y - rb_dy) * (y - rb_dy) > 36) rb_moved = 1; }
        return rb_down;
    }
    if (state == 1) {
        if (!ours) return 0;
        rb_down = 1; rb_moved = 0; rb_dx = rb_mx = x; rb_dy = rb_my = y; rb_down_at = now_ms();
        return 1;
    }
    if (!rb_down) return 0;                                     /* released */
    rb_down = 0;
    if (!rb_moved && now_ms() - rb_down_at < 350) { rb_tap_x = x; rb_tap_y = y; rb_tap_pending = 1; }
    return 1;
}

/* ---------------- search keyboard (the game's own: MainActivity.showKeyboard, text back via ModBridge) ---------------- */
static void *rb_kbd_mid;
static void rb_keyboard(int show) {
    rb_kbd = show;
    void **env = mm_env();
    if (!env || !mm_cls) return;
    void **f = *(void ***)env;
    if (!rb_kbd_mid) rb_kbd_mid = ((void *(*)(void *, void *, const char *, const char *))f[113])(env, mm_cls, "recipeKeyboard", "(ILjava/lang/String;)V");
    if (!rb_kbd_mid) { ((void (*)(void *))f[17])(env); return; }
    void *js = ((void *(*)(void *, const char *))f[167])(env, rb_query);
    MJValue args[2];
    args[0].i = show; args[1].l = js;
    ((void (*)(void *, void *, void *, MJValue *))f[143])(env, mm_cls, rb_kbd_mid, args);   /* CallStaticVoidMethodA */
    if (((int (*)(void *))f[228])(env)) { ((void (*)(void *))f[16])(env); ((void (*)(void *))f[17])(env); }
    if (js) ((void (*)(void *, void *))f[23])(env, js);                                     /* DeleteLocalRef */
}
void Java_com_nova_client_ModBridge_nativeRecipeSearch(void **env, void *cls, void *jtext, int done) {
    if (jtext) jstr_copy(env, jtext, rb_query, sizeof rb_query);
    rb_page = 0;
    rb_query_dirty = 1;
    if (done) rb_kbd = 0;
}

/* every frame: the inventory closed with the keyboard up - give the keyboard back to the game */
static void rb_frame(void) {
    if (rb_kbd && now_ms() - rb_drawn_at > 500) rb_keyboard(0);
    if (!rb_kbd && rb_win && now_ms() - rb_drawn_at > 500) { rb_win = 0; rb_hist_n = 0; }
}

static void recipebook_init(void *mc) {
    rb_ir_instance = dlsym(mc, "_ZN12ItemRenderer11getInstanceEv");
    rb_ir_item = dlsym(mc, "_ZN12ItemRenderer16renderGuiItemNewEPK12ItemInstanceifffffb");
    rb_ii_name = dlsym(mc, "_ZNK12ItemInstance7getNameEv");
    LOG("recipe book: %s", rb_ir_instance && rb_ir_item && rb_ii_name ? "ok" : "unavailable");
}
