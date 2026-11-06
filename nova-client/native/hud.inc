/* hud.inc - ui.hud of mods: rows of 9x9 icons over the hotbar, like hearts and hunger (hud_screen.json:
 * centered_gui_elements is 180 wide, 20 above the bottom; hunger at -18, bubbles one row (10) higher).
 * Script: ui.hud("thirst", ["textures/drop_full.png", ...], 1, "right"). Pictures come from Java (nova/mod/...). */
static int hud_streq(const char *a, const char *b) { while (*a && *a == *b) { a++; b++; } return *a == *b; }
#define HUD_SLOTS 8
#define HUD_ICONS 20
typedef struct { char slot[24]; int row, side, n; short tex[HUD_ICONS]; } HudRow;
static HudRow hud_rows[HUD_SLOTS];
static struct { char path[96]; u32 tex[16]; int ok; } hud_tex[48];
static int hud_ntex;
static long hud_drawn_at;
static int (*f_showingmenu)(void *client);
static int (*f_screenh)(void *gd);

static int hud_texture(const char *p) {
    if (!*p) return -1;
    for (int i = 0; i < hud_ntex; i++) if (hud_streq(hud_tex[i].path, p)) return i;
    if (hud_ntex >= 48) return -1;
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
    h->row = n > 0 ? (int)a[0] : 1;
    h->side = n > 1 ? (int)a[1] : 0;
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

static void hud_draw(void *client) {
    if (!mm_player() || !f_guidata || !f_screenw || !f_screenh || !f_sr || !f_blit || !f_gettex || !f_textures) return;
    long now = now_ms();
    if (hud_drawn_at == now) return;
    hud_drawn_at = now;
    if (f_showingmenu && f_showingmenu(client)) return;
    int any = 0;
    for (int i = 0; i < HUD_SLOTS; i++) if (hud_rows[i].slot[0]) any = 1;
    if (!any) return;
    void *gd = f_guidata(client);
    if (!gd) return;
    int W = f_screenw(gd), H = f_screenh(gd);
    void *sr = f_sr();
    for (int i = 0; i < HUD_SLOTS; i++) {
        HudRow *h = &hud_rows[i];
        if (!h->slot[0]) continue;
        int y = h->row <= 0 ? H - 30 : H - 48 - 10 * (h->row - 1);   /* 0: under hunger/hearts, 1: over them */
        for (int k = 0; k < h->n; k++) {
            int t = h->tex[k];
            if (t < 0) continue;
            if (!hud_tex[t].ok) {
                u32 str[1];
                gstr(str, hud_tex[t].path);
                for (int j = 0; j < 16; j++) hud_tex[t].tex[j] = 0;
                f_gettex(hud_tex[t].tex, f_textures(client), str, 0);
                f_strdtor(str);
                hud_tex[t].ok = 1;
            }
            int x = h->side ? W / 2 - 90 + 8 * k : W / 2 + 90 - 8 * (h->n - k) - 1;
            int rect[4] = {x, y, 16, 16};               /* pictures are 16x16 (power of two), the icon in its top-left 9x9 */
            f_blit(sr, hud_tex[t].tex, rect);
        }
    }
}

static void hud_init(void *mc) {
    f_showingmenu = dlsym(mc, "_ZNK15MinecraftClient13isShowingMenuEv");
    f_screenh = dlsym(mc, "_ZNK7GuiData15getScreenHeightEv");
}
