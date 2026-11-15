/* hud.inc - ui.hud of mods: rows of 9x9 icons over the hotbar, like hearts and hunger (hud_screen.json:
 * centered_gui_elements is 180 wide, 20 above the bottom; hunger at -18, bubbles one row (10) higher).
 * Script: ui.hud("thirst", ["textures/drop_full.png", ...], 1, "right"). Pictures come from Java (nova/mod/...). */
static int hud_streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
#define HUD_SLOTS 16
#define HUD_ICONS 20
#define HUD_TEX 192
/* kind 0: row of icons (ui.hud); kind 1: one picture (ui.image) at x (from the screen center), y (from the top, or the
 * bottom with anchor 1), size x size GUI pixels. Slots keep their place, so pictures are drawn in the order the slots
 * were made (an empty picture hides the slot without freeing it). */
typedef struct { char slot[24]; int kind, row, side, shake, n, x, y, size, anchor; u32 last; short tex[HUD_ICONS]; } HudRow;
static HudRow hud_rows[HUD_SLOTS];
static struct { char path[96]; u32 tex[16]; int ok; } hud_tex[HUD_TEX];
static int hud_ntex;
static long hud_drawn_at;
static u32 hud_seed = 1;
static int (*f_showingmenu)(void *client);
static int (*f_screenh)(void *gd);

static int hud_texture(const char *p) {
    if (!*p) return -1;
    for (int i = 0; i < hud_ntex; i++) if (hud_streq(hud_tex[i].path, p)) return i;
    if (hud_ntex >= HUD_TEX) return -1;
    int i = hud_ntex++;
    int k = 0;
    while (p[k] && k < 95) { hud_tex[i].path[k] = p[k]; k++; }
    hud_tex[i].path[k] = 0;
    hud_tex[i].ok = 0;
    return i;
}

/* OP_HUD: a[0] row, a[1] side (0 right, 1 left); s = "slot|icon|icon|..." */
static int hud_op(double *a, int n, char *s) {
    char *bar = s;
    while (*bar && *bar != '|') bar++;
    int has = *bar == '|';
    *bar = 0;
    int r = -1, free_ = -1;
    for (int i = 0; i < HUD_SLOTS; i++) {
        if (hud_rows[i].slot[0] && hud_streq(hud_rows[i].slot, s)) r = i;
        if (!hud_rows[i].slot[0] && free_ < 0) free_ = i;
    }
    if (r < 0) r = free_;
    if (r < 0) return 0;
    HudRow *h = &hud_rows[r];
    int k = 0;
    while (s[k] && k < 23) { h->slot[k] = s[k]; k++; }
    h->slot[k] = 0;
    h->kind = 0;
    h->row = n > 0 ? (int)a[0] : 1;
    h->side = n > 1 ? (int)a[1] : 0;
    h->shake = n > 2 ? (int)a[2] : 0;
    h->n = 0;
    char *p = has ? bar + 1 : 0;
    while (p && h->n < HUD_ICONS) {
        char *e = p;
        while (*e && *e != '|') e++;
        int last = !*e;
        *e = 0;
        h->tex[h->n++] = (short)hud_texture(p);
        p = last ? 0 : e + 1;
    }
    if (!h->n) h->slot[0] = 0;                       /* [] = remove the row */
    return 0;
}

/* OP_HUD_IMAGE: a = x, y, size, anchor (0 top, 1 bottom); s = "slot|picture" ("" picture = hidden) */
static int hud_image_op(double *a, int n, char *s) {
    char *bar = s;
    while (*bar && *bar != '|') bar++;
    char *pic = *bar == '|' ? bar + 1 : bar;
    *bar = 0;
    int r = -1, free_ = -1;
    for (int i = 0; i < HUD_SLOTS; i++) {
        if (hud_rows[i].slot[0] && hud_streq(hud_rows[i].slot, s)) r = i;
        if (!hud_rows[i].slot[0] && free_ < 0) free_ = i;
    }
    if (r < 0) r = free_;
    if (r < 0) return 0;
    HudRow *h = &hud_rows[r];
    int k = 0;
    while (s[k] && k < 23) { h->slot[k] = s[k]; k++; }
    h->slot[k] = 0;
    h->kind = 1;
    h->x = n > 0 ? (int)a[0] : 0;
    h->y = n > 1 ? (int)a[1] : 0;
    h->size = n > 2 ? (int)a[2] : 16;
    if (h->size < 1) h->size = 1;
    if (h->size > 256) h->size = 256;
    h->anchor = n > 3 ? (int)a[3] : 0;
    h->shake = 0;
    h->n = *pic ? 1 : 0;
    h->tex[0] = *pic ? (short)hud_texture(pic) : -1;
    if (!h->slot[0]) h->n = 0;
    return 0;
}

static void hud_load(void *client, int t) {
    if (hud_tex[t].ok) return;
    u32 str[1];
    gstr(str, hud_tex[t].path);
    for (int j = 0; j < 16; j++) hud_tex[t].tex[j] = 0;
    f_gettex(hud_tex[t].tex, f_textures(client), str, 0);
    f_strdtor(str);
    hud_tex[t].ok = 1;
}

static void hud_draw(void *client) {
    if (!mm_player() || !f_guidata || !f_screenw || !f_sr || !f_blit || !f_gettex || !f_textures) return;
    long now = now_ms();
    if (hud_drawn_at == now) return;
    hud_drawn_at = now;
    if (f_showingmenu && f_showingmenu(client)) return;
    int any = 0;
    for (int i = 0; i < HUD_SLOTS; i++) if (hud_rows[i].slot[0]) any = 1;
    if (!any) return;
    void *gd = f_guidata(client);
    if (!gd) return;
    int W = f_screenw(gd);
    int H = f_screenh ? f_screenh(gd) : 0;
    void *sr = f_sr();
    for (int i = 0; i < HUD_SLOTS; i++) {
        HudRow *h = &hud_rows[i];
        if (!h->slot[0]) continue;
        if (h->kind == 1) {
            int t = h->n ? h->tex[0] : -1;
            if (t < 0) continue;
            hud_load(client, t);
            int x = (W >> 1) + h->x, y = h->anchor ? H - h->y - h->size : h->y;
            int rect[4] = {x, y, h->size, h->size};
            f_blit(sr, hud_tex[t].tex, rect);
            continue;
        }
        int y = 12 + 10 * h->row;      /* touch layout: hearts top-left, hunger top-right at y 2; row 0 = under them */
        int jit = 0;                    /* like hunger / ThirstGui: shake on ticks where tick % shake == 0 */
        if (h->shake > 0) {             /* no division in libnova: count ticks since the last shake */
            u32 tick = (u32)((unsigned long long)now * 1311 >> 16);   /* ~ now / 50 */
            if (tick - h->last >= (u32)h->shake) h->last = tick;
            jit = tick == h->last;      /* the whole tick, like updateCounter */
        }
        for (int k = 0; k < h->n; k++) {
            int t = h->tex[k];
            if (t < 0) continue;
            hud_load(client, t);
            int x = h->side ? 2 + 8 * k : W - 2 - 9 - 8 * (h->n - 1 - k);
            int yy = y;
            if (jit) { hud_seed = hud_seed * 1103515245u + 12345u; int v = (hud_seed >> 16) & 3; if (v == 3) v = 1; yy += v - 1; }
            int rect[4] = {x, yy, 16, 16};               /* pictures are 16x16 (power of two), the icon in its top-left 9x9 */
            f_blit(sr, hud_tex[t].tex, rect);
        }
    }
}

static void hud_init(void *mc) {
    f_showingmenu = dlsym(mc, "_ZNK15MinecraftClient13isShowingMenuEv");
    f_screenh = dlsym(mc, "_ZNK7GuiData15getScreenHeightEv");
}
