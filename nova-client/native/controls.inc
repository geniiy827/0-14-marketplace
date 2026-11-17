/* controls.inc - custom touch controls and their editor (octa4)
 *
 * 0.14.3 touch input: TouchMapper::tick puts every pointer into TouchControlSet (+0 vector of 0x14-byte entries:
 * id, state (0 = pressed this frame), x, y (screen px), handled (2 = free; a control that takes it writes 0)),
 * then TouchControlSet::tick runs the controls: TouchGlyphButtonControl (dpad, jump, sneak; button id +0x30,
 * pressed +0x32, area std::function +4/+0xc/+0x10 -> RectangleArea {x0, x1, y0, y1}, uv +0x54..+0x60,
 * colours pressed +0x34 / normal +0x44, scale +0x78) and TouchTurnInteractControl (camera; takes only free pointers).
 * Button events: InputEventQueue::enqueueButton(id, 1 down / 0 up), the joystick: enqueueDirection(0, x, y)
 * (MoveInputHandler: +y forward). Ids from NameRegistry::getNameId (the registry is caught in addGlyphButton).
 *
 * Custom mode (files/nova_controls.txt): the game's movement glyphs are switched off (tick/render slots of their
 * vtable), our elements take the pointers that start on them (handled = 0, so the camera doesn't get them) and
 * send the same buttons. Drawn after MinecraftInputHandler::render (the game's controls pass), in GUI units.
 * Classic pictures come from gui/gui.png (resource packs apply), new ones from gui/nova/controls.png
 * (tools/gen_controls.py). Attack/interact ("special") buttons: split controls on, and taps on the world only turn
 * the camera (the touch build/destroy buttons are dropped in enqueueButton).
 * Editor: in the game, every pointer goes to the editor; ✕ cancel, ✓ save, + the asset panel. */
#include "controls_atlas.h"

enum { K_DPAD_JUMP, K_DPAD_SNEAK, K_JOY_FIX, K_JOY_FLOAT, K_FWD, K_BACK, K_LEFT, K_RIGHT, K_UPLEFT, K_UPRIGHT,
       K_JUMP, K_SNEAK_CLASSIC, K_SNEAK_NEW, K_SPRINT, K_FLY_UP, K_FLY_DOWN, K_ATTACK, K_INTERACT, K_F5, K_DROP, K_COUNT };
/* button names; index = B_* */
enum { B_UP, B_DOWN, B_LEFT, B_RIGHT, B_UPLEFT, B_UPRIGHT, B_JUMP, B_SNEAK, B_SNEAK_TOGGLE, B_SPRINT, B_ASCEND, B_DESCEND,
       B_ATTACK, B_INTERACT, B_F5, B_DROP, B_UP_IGNORE, B_HIDE_JUMP, B_FLIGHT, B_NO_BINDING, B_TOUCH_BUILD, B_TOUCH_DESTROY, B_N };
static const char *CC_BNAME[B_N] = {
    "button.up", "button.down", "button.left", "button.right", "button.upleft", "button.upright", "button.jump",
    "button.sneak", "button.sneak_toggle", "button.sprint", "button.ascend", "button.descend",
    "button.destroy_or_attack", "button.build_or_interact", "button.third_person_view", "button.drop",
    "button.up_ignore", "button.hide_jump_button", "button.change_flight_height", "no_binding",
    "button.build_or_attack", "button.destroy_or_interact"};
static short cc_bid[B_N];
static int cc_ids_ok;

/* the asset list: lang key of the name, tab, size in "cells" (one cell = a vanilla button) */
static const struct { const char *key; int tab; float cells; } CC_ASSET[K_COUNT] = {
    {"nova.ctl.a.dpad_jump", 0, 3}, {"nova.ctl.a.dpad_sneak", 0, 3}, {"nova.ctl.a.joy_fix", 0, 2.6f}, {"nova.ctl.a.joy_float", 0, 2.6f},
    {"nova.ctl.a.fwd", 1, 1}, {"nova.ctl.a.back", 1, 1}, {"nova.ctl.a.left", 1, 1}, {"nova.ctl.a.right", 1, 1},
    {"nova.ctl.a.upleft", 1, 1}, {"nova.ctl.a.upright", 1, 1},
    {"nova.ctl.a.jump", 2, 1}, {"nova.ctl.a.sneak_classic", 2, 1}, {"nova.ctl.a.sneak_new", 2, 1}, {"nova.ctl.a.sprint", 2, 1},
    {"nova.ctl.a.fly_up", 2, 1}, {"nova.ctl.a.fly_down", 2, 1},
    {"nova.ctl.a.attack", 3, 1.15f}, {"nova.ctl.a.interact", 3, 1.15f}, {"nova.ctl.a.f5", 3, 0.85f}, {"nova.ctl.a.drop", 3, 0.85f}};
#define CC_TABS 4
static const char *CC_TAB[CC_TABS] = {"nova.ctl.tab.move", "nova.ctl.tab.arrows", "nova.ctl.tab.actions", "nova.ctl.tab.fight"};

typedef struct { int kind; float cx, cy, size, alpha; } CcEl;         /* cx, cy: of the screen size; size: of its height */
#define CC_MAX 48
static CcEl cc_el[CC_MAX], cc_ed[CC_MAX];
static int cc_n, cc_edn;
static int cc_loaded, cc_active;          /* a saved custom layout is in use */
static int cc_editing, cc_want_edit, cc_sel = -1, cc_panel, cc_tab;
static float cc_def_alpha = 0.65f, cc_cell_px;  /* the game's button transparency and button size (px) */
static float cc_W, cc_H, cc_inv;          /* screen px, GUI units per px */

/* the game's glyphs as seen this run: uv and colours by button */
typedef struct { int ok; float uv[4]; float col_n[4], col_p[4]; float area[4]; } CcGlyph;
static CcGlyph cc_g[B_N], cc_g_sneak_on;

static void *cc_registry;
static short (*cc_getnameid)(void *reg, void *name);
static void (*cc_enq_button)(void *q, int id, int state, int b);
static void (*cc_enq_dir)(void *q, int dir, float x, float y);
static int (*cc_flying)(void *abilities);
static int (*cc_sneaking)(void *mob);
static int (*cc_showingmenu)(void *client);
static void (*cc_popscreen)(void *client, int n);
static int (*cc_getbool)(void *opts, void *opt);
static void (*cc_toggle)(void *opts, void *opt, int);
static void (*cc_optsave)(void *opts);
static void *cc_opt_split;
static void (*cc_o_grender)(void *self, void *ctx);
static u64 (*cc_o_gtick)(void *self, void *q, void *res, int n);
static volatile int cc_self;              /* our own enqueueButton calls pass the filter */

/* ---- files ---- */
static void cc_path(char *out, int n) { sp_file(out, n, "nova_controls.txt"); }

static int cc_parse_f(const char **p, float *v) {
    const char *s = *p;
    while (*s == ' ') s++;
    int neg = 0;
    if (*s == '-') { neg = 1; s++; }
    float r = 0.f, d = 0.f;
    int any = 0;
    while ((*s >= '0' && *s <= '9') || *s == '.') {
        if (*s == '.') d = 1.f;
        else { if (d > 0.f) { d *= 0.1f; r += (*s - '0') * d; } else r = r * 10.f + (*s - '0'); }
        s++; any = 1;
    }
    *p = s;
    *v = neg ? -r : r;
    return any;
}

static void cc_load(void) {
    cc_loaded = 1;
    cc_n = 0;
    cc_active = 0;
    char path[300];
    cc_path(path, sizeof path);
    int fd = open(path, 0);
    if (fd < 0) return;
    static char buf[8192];
    int r = read(fd, buf, sizeof buf - 1);
    close(fd);
    if (r <= 0) return;
    buf[r] = 0;
    if (buf[0] != 'N') return;                              /* "NOVACTL 1" */
    const char *p = buf;
    while (*p && *p != '\n') p++;
    while (*p && cc_n < CC_MAX) {
        while (*p == '\n' || *p == '\r') p++;
        if (!*p) break;
        float k, x, y, s, a;
        if (cc_parse_f(&p, &k) && cc_parse_f(&p, &x) && cc_parse_f(&p, &y) && cc_parse_f(&p, &s) && cc_parse_f(&p, &a)
            && k >= 0 && k < K_COUNT) {
            CcEl *e = &cc_el[cc_n++];
            e->kind = (int)k; e->cx = x; e->cy = y; e->size = s; e->alpha = a;
        }
        while (*p && *p != '\n') p++;
    }
    cc_active = 1;
}

static int cc_fmt(char *o, float v) {                    /* 0.0000 .. 99.9999, no printf floats needed */
    int n = 0;
    if (v < 0) { o[n++] = '-'; v = -v; }
    int ip = (int)v, fp = (int)((v - ip) * 10000.f + 0.5f);
    if (fp >= 10000) { ip++; fp -= 10000; }
    n += snprintf(o + n, 16, "%d.%04d", ip, fp);
    return n;
}

static void cc_save(void) {
    char path[300];
    cc_path(path, sizeof path);
    static char buf[8192];
    int n = snprintf(buf, sizeof buf, "NOVACTL 1\n");
    for (int i = 0; i < cc_n && n < (int)sizeof buf - 100; i++) {
        CcEl *e = &cc_el[i];
        n += snprintf(buf + n, 8, "%d ", e->kind);
        n += cc_fmt(buf + n, e->cx); buf[n++] = ' ';
        n += cc_fmt(buf + n, e->cy); buf[n++] = ' ';
        n += cc_fmt(buf + n, e->size); buf[n++] = ' ';
        n += cc_fmt(buf + n, e->alpha); buf[n++] = '\n';
    }
    int fd = open(path, 01 | 0100 | 01000, 0644);
    if (fd >= 0) { write(fd, buf, n); close(fd); }
}

static void cc_reset(void) {
    char path[300];
    cc_path(path, sizeof path);
    int fd = open(path, 01 | 0100 | 01000, 0644);       /* an empty file = classic controls */
    if (fd >= 0) close(fd);
    cc_n = 0;
    cc_active = 0;
}

static int cc_special(const CcEl *e, int n) {
    for (int i = 0; i < n; i++) if (e[i].kind == K_ATTACK || e[i].kind == K_INTERACT) return 1;
    return 0;
}

/* split controls follow the attack/interact buttons */
static void cc_apply_split(int want) {
    void *opts = g_client ? f_getoptions(g_client) : 0;
    if (!opts || !cc_opt_split || !cc_getbool || !cc_toggle) return;
    if ((cc_getbool(opts, cc_opt_split) != 0) != (want != 0)) {
        cc_toggle(opts, cc_opt_split, 0);
        if (cc_optsave) cc_optsave(opts);
        LOG("controls: split controls %s", want ? "on" : "off");
    }
}

/* ---- button ids ---- */
static void cc_resolve_ids(void) {
    if (cc_ids_ok || !cc_registry || !cc_getnameid) return;
    for (int i = 0; i < B_N; i++) {
        u32 s[1];
        gstr(s, CC_BNAME[i]);
        cc_bid[i] = cc_getnameid(cc_registry, s);
        f_strdtor(s);
    }
    cc_ids_ok = 1;
    LOG("controls: button ids ready (jump %d, attack %d)", cc_bid[B_JUMP], cc_bid[B_ATTACK]);
}
static int cc_bindex(int id) {
    if (!cc_ids_ok) return -1;
    for (int i = 0; i < B_N; i++) if (cc_bid[i] == id) return i;
    return -1;
}

/* ---- the game's glyphs ---- */
static void cc_glyph_area(char *g, float *out) {
    if (!*(u32 *)(g + 0xc)) return;
    float a[8];
    ((void (*)(float *, void *))*(u32 *)(g + 0x10))(a, g + 4);
    out[0] = a[0]; out[1] = a[1]; out[2] = a[2]; out[3] = a[3];
}
static int cc_disabled_id(int b) {
    switch (b) {
    case B_UP: case B_DOWN: case B_LEFT: case B_RIGHT: case B_UPLEFT: case B_UPRIGHT: case B_JUMP: case B_SNEAK_TOGGLE:
    case B_ASCEND: case B_DESCEND: case B_UP_IGNORE: case B_HIDE_JUMP: case B_FLIGHT: case B_NO_BINDING: return 1;
    }
    return 0;
}
static void cc_glyph_seen(char *g) {
    int b = cc_bindex(*(short *)(g + 0x30));
    if (b < 0) return;
    CcGlyph *G = &cc_g[b];
    if (b == B_SNEAK_TOGGLE && *(int *)(g + 0x58) != 64) G = &cc_g_sneak_on;   /* the sneaking picture (218,82) */
    G->ok = 1;
    G->uv[0] = (float)*(int *)(g + 0x54); G->uv[1] = (float)*(int *)(g + 0x58);
    G->uv[2] = (float)*(int *)(g + 0x5c); G->uv[3] = (float)*(int *)(g + 0x60);
    memcpy(G->col_p, g + 0x34, 16);
    memcpy(G->col_n, g + 0x44, 16);
    cc_glyph_area(g, G->area);
    if (b == B_LEFT && G->col_n[3] > 0.05f && G->col_n[3] < 0.99f) cc_def_alpha = G->col_n[3];
    if (b == B_LEFT) { float h = G->area[3] - G->area[2]; if (h > 8.f) cc_cell_px = h; }
}

static u64 cc_gtick(void *self, void *q, void *res, int n) {
    char *g = self;
    cc_resolve_ids();
    int b = cc_bindex(*(short *)(g + 0x30));
    if (b >= 0) cc_glyph_seen(g);
    if ((cc_active || cc_editing) && b >= 0 && cc_disabled_id(b)) {
        if (*(char *)(g + 0x32) == 1) {                  /* let go of what it held */
            *(char *)(g + 0x32) = 0;
            cc_self = 1; cc_enq_button(q, *(short *)(g + 0x30), 0, 0); cc_self = 0;
        }
        return 0;
    }
    return cc_o_gtick(self, q, res, n);
}
static void cc_grender(void *self, void *ctx) {
    int b = cc_bindex(*(short *)((char *)self + 0x30));
    if ((cc_active || cc_editing) && b >= 0 && cc_disabled_id(b)) return;
    cc_o_grender(self, ctx);
}

/* the default layout: what the game shows now */
static void cc_default_layout(CcEl *out, int *n) {
    float W = cc_W, H = cc_H;
    float cell = cc_cell_px > 8.f ? cc_cell_px : H * 0.12f;
    *n = 0;
    float dx, dy, dsz;
    CcGlyph *L = &cc_g[B_LEFT], *R = &cc_g[B_RIGHT], *U = &cc_g[B_UP_IGNORE], *D = &cc_g[B_DOWN];
    if (L->ok && R->ok && U->ok && D->ok) {
        float x0 = L->area[0], x1 = R->area[1], y0 = U->area[2], y1 = D->area[3];
        dx = (x0 + x1) / 2; dy = (y0 + y1) / 2; dsz = y1 - y0;
        cell = dsz / 3;
    } else {
        dsz = cell * 3; dx = cell * 0.6f + dsz / 2; dy = H - cell * 0.4f - dsz / 2;
    }
    /* which button sits in the middle of the dpad */
    int jump_mid = 0;
    CcGlyph *J = &cc_g[B_JUMP], *S = &cc_g[B_SNEAK_TOGGLE];
    if (J->ok) {
        float jx = (J->area[0] + J->area[1]) / 2, jy = (J->area[2] + J->area[3]) / 2;
        jump_mid = jx > dx - cell / 2 && jx < dx + cell / 2 && jy > dy - cell / 2 && jy < dy + cell / 2;
    }
    out[(*n)++] = (CcEl){jump_mid ? K_DPAD_JUMP : K_DPAD_SNEAK, dx / W, dy / H, dsz / H, cc_def_alpha};
    CcGlyph *O = jump_mid ? S : J;
    float ox, oy;
    if (O->ok) { ox = (O->area[0] + O->area[1]) / 2; oy = (O->area[2] + O->area[3]) / 2; }
    else { ox = W - cell * 1.6f; oy = H - cell * 2.2f; }
    out[(*n)++] = (CcEl){jump_mid ? K_SNEAK_CLASSIC : K_JUMP, ox / W, oy / H, cell / H, cc_def_alpha};
    out[(*n)++] = (CcEl){K_FLY_UP, (ox - cell * 1.25f) / W, (oy - cell * 0.6f) / H, cell / H, cc_def_alpha};
    out[(*n)++] = (CcEl){K_FLY_DOWN, (ox - cell * 1.25f) / W, (oy + cell * 0.6f) / H, cell / H, cc_def_alpha};
}

static float cc_cell_frac(void) {
    float cell = cc_cell_px > 8.f ? cc_cell_px : cc_H * 0.12f;
    return cc_H > 0 ? cell / cc_H : 0.12f;
}

/* ---- pointers ---- */
#define CC_PTR 10
typedef struct { int used, id, el, fwd, cell, seen; float x, y, sx, sy, ox, oy; } CcPtr;   /* fwd: has been on forward */
static CcPtr cc_p[CC_PTR];
static unsigned char cc_hold[B_N], cc_sent[B_N];
static float cc_dir[2], cc_dir_sent[2];
static int cc_dir_on;
static int cc_sneak_new_on, cc_sprint_armed, cc_sprint_moved;
static unsigned char cc_fwd_el[CC_MAX];      /* dpad: moving forward last frame (diagonals show) */
static float cc_joy[CC_MAX][4];              /* joystick: base centre (px) while held, knob offset (-1..1) */

typedef struct { float x0, y0, x1, y1; } CcBox;
static CcBox cc_box(const CcEl *e) {
    float s = e->size * cc_H, cx = e->cx * cc_W, cy = e->cy * cc_H;
    return (CcBox){cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2};
}
static int cc_in(CcBox b, float x, float y) { return x >= b.x0 && x < b.x1 && y >= b.y0 && y < b.y1; }

static void *cc_player(void) { return g_client ? *(void **)((char *)g_client + 0x120) : 0; }
static int cc_is_flying(void) {
    char *pl = cc_player();
    return pl && cc_flying ? cc_flying(pl + 0xcf4) : 0;
}
static int cc_is_sneaking(void) {
    void *pl = cc_player();
    return pl && cc_sneaking ? cc_sneaking(pl) : 0;
}

/* slide buttons: any pointer that is on a slide button counts on every slide button it passes */
static int cc_slide_kind(int k) {
    return k == K_DPAD_JUMP || k == K_DPAD_SNEAK || (k >= K_FWD && k <= K_UPRIGHT) || k == K_JUMP || k == K_FLY_UP || k == K_FLY_DOWN;
}
static int cc_hit(const CcEl *e, float x, float y) {
    CcBox b = cc_box(e);
    if (e->kind == K_JOY_FLOAT) {                         /* a bigger zone: the stick comes to the finger */
        float g = (b.x1 - b.x0) * 0.45f;
        b.x0 -= g; b.x1 += g; b.y0 -= g; b.y1 += g;
    }
    if ((e->kind == K_FLY_UP || e->kind == K_FLY_DOWN) && !cc_is_flying()) return 0;
    return cc_in(b, x, y);
}

/* dpad cell of a point: 0..8 (row*3+col), -1 outside */
static int cc_cell(const CcEl *e, float x, float y) {
    CcBox b = cc_box(e);
    if (!cc_in(b, x, y)) return -1;
    float c = (b.x1 - b.x0) / 3;
    int col = (int)((x - b.x0) / c), row = (int)((y - b.y0) / c);
    if (col > 2) col = 2;
    if (row > 2) row = 2;
    return row * 3 + col;
}

static void cc_run(void *q) {
    memset(cc_hold, 0, sizeof cc_hold);
    cc_dir[0] = cc_dir[1] = 0;
    int moving = 0;
    unsigned char fwd_now[CC_MAX];
    memset(fwd_now, 0, sizeof fwd_now);
    /* slide buttons */
    for (int pi = 0; pi < CC_PTR; pi++) {
        CcPtr *P = &cc_p[pi];
        if (!P->used || P->el < 0 || P->el >= cc_n || !cc_slide_kind(cc_el[P->el].kind)) continue;
        int fwd_seen = 0;
        for (int i = 0; i < cc_n; i++) {
            CcEl *e = &cc_el[i];
            if (!cc_slide_kind(e->kind)) continue;
            if ((e->kind == K_FLY_UP || e->kind == K_FLY_DOWN)) {
                if (!cc_is_flying() || !cc_in(cc_box(e), P->x, P->y)) continue;
                cc_hold[e->kind == K_FLY_UP ? B_ASCEND : B_DESCEND] = 1;
                continue;
            }
            if (e->kind == K_DPAD_JUMP || e->kind == K_DPAD_SNEAK) {
                int c = cc_cell(e, P->x, P->y);
                switch (c) {
                case 1: cc_hold[B_UP] = 1; fwd_seen = 1; fwd_now[i] = 1; break;
                case 0: if (cc_fwd_el[i]) { cc_hold[B_UPLEFT] = 1; fwd_seen = 1; fwd_now[i] = 1; } break;
                case 2: if (cc_fwd_el[i]) { cc_hold[B_UPRIGHT] = 1; fwd_seen = 1; fwd_now[i] = 1; } break;
                case 3: cc_hold[B_LEFT] = 1; break;
                case 5: cc_hold[B_RIGHT] = 1; break;
                case 7: cc_hold[B_DOWN] = 1; break;
                case 4:
                    if (e->kind == K_DPAD_JUMP) {
                        cc_hold[B_JUMP] = 1;
                        if (P->fwd) { cc_hold[B_UP] = 1; fwd_seen = 1; fwd_now[i] = 1; }   /* forward and jump: slide down */
                    } else cc_hold[B_SNEAK_TOGGLE] = 1;
                    break;
                }
                continue;
            }
            if (!cc_in(cc_box(e), P->x, P->y)) continue;
            switch (e->kind) {
            case K_FWD: cc_hold[B_UP] = 1; break;
            case K_BACK: cc_hold[B_DOWN] = 1; break;
            case K_LEFT: cc_hold[B_LEFT] = 1; break;
            case K_RIGHT: cc_hold[B_RIGHT] = 1; break;
            case K_UPLEFT: cc_hold[B_UPLEFT] = 1; break;
            case K_UPRIGHT: cc_hold[B_UPRIGHT] = 1; break;
            case K_JUMP: cc_hold[B_JUMP] = 1; break;
            }
        }
        if (fwd_seen) P->fwd = 1;
        else {
            int on_jump = 0;                                   /* forward stays while the finger is on the jump */
            for (int i = 0; i < cc_n; i++)
                if (cc_el[i].kind == K_DPAD_JUMP && cc_cell(&cc_el[i], P->x, P->y) == 4) on_jump = 1;
            if (!on_jump) P->fwd = 0;
        }
    }
    memcpy(cc_fwd_el, fwd_now, sizeof fwd_now);
    /* buttons with their own pointer */
    for (int pi = 0; pi < CC_PTR; pi++) {
        CcPtr *P = &cc_p[pi];
        if (!P->used || P->el < 0 || P->el >= cc_n) continue;
        CcEl *e = &cc_el[P->el];
        switch (e->kind) {
        case K_SNEAK_CLASSIC: cc_hold[B_SNEAK_TOGGLE] = 1; break;
        case K_ATTACK: cc_hold[B_ATTACK] = 1; break;
        case K_INTERACT: cc_hold[B_INTERACT] = 1; break;
        case K_F5: cc_hold[B_F5] = 1; break;
        case K_DROP: cc_hold[B_DROP] = 1; break;
        case K_JOY_FIX: case K_JOY_FLOAT: {
            float R = e->size * cc_H / 2;
            float bx = cc_joy[P->el][0], by = cc_joy[P->el][1];
            float vx = (P->x - bx) / (R * 0.72f), vy = (P->y - by) / (R * 0.72f);
            float l = sqrtf(vx * vx + vy * vy);
            if (l > 1.f) { vx /= l; vy /= l; }
            cc_joy[P->el][2] = vx; cc_joy[P->el][3] = vy;
            cc_dir[0] += vx; cc_dir[1] += -vy;
            break;
        }
        }
    }
    if (cc_sneak_new_on) cc_hold[B_SNEAK] = 1;
    float dl = cc_dir[0] * cc_dir[0] + cc_dir[1] * cc_dir[1];
    if (dl > 1.f) { float l = sqrtf(dl); cc_dir[0] /= l; cc_dir[1] /= l; }
    moving = cc_hold[B_UP] || cc_hold[B_DOWN] || cc_hold[B_LEFT] || cc_hold[B_RIGHT] || cc_hold[B_UPLEFT] || cc_hold[B_UPRIGHT] || dl > 0.04f;
    if (cc_sprint_armed) {                                   /* run while moving; standing still puts it out */
        if (moving) { cc_hold[B_SPRINT] = 1; cc_sprint_moved = 1; }
        else if (cc_sprint_moved) { cc_sprint_armed = 0; cc_sprint_moved = 0; }
    }
    /* send what changed */
    if (!q || !cc_ids_ok) return;
    cc_self = 1;
    for (int b = 0; b < B_N; b++) {
        if (cc_hold[b] == cc_sent[b] || cc_bid[b] < 0) continue;
        cc_enq_button(q, cc_bid[b], cc_hold[b] ? 1 : 0, 0);
        cc_sent[b] = cc_hold[b];
    }
    cc_self = 0;
    if (dl > 0.0001f || cc_dir_on) {
        if (cc_enq_dir) cc_enq_dir(q, 0, cc_dir[0], cc_dir[1]);
        cc_dir_on = dl > 0.0001f;
    }
}

static void cc_release_all(void *q) {
    for (int i = 0; i < CC_PTR; i++) cc_p[i].used = 0;
    memset(cc_hold, 0, sizeof cc_hold);
    if (q && cc_ids_ok) {
        cc_self = 1;
        for (int b = 0; b < B_N; b++) if (cc_sent[b] && cc_bid[b] >= 0) cc_enq_button(q, cc_bid[b], 0, 0);
        cc_self = 0;
        if (cc_dir_on && cc_enq_dir) cc_enq_dir(q, 0, 0, 0);
    }
    memset(cc_sent, 0, sizeof cc_sent);
    cc_dir_on = 0;
}

/* a tap on a toggle */
static void cc_press(int el) {
    CcEl *e = &cc_el[el];
    if (e->kind == K_SNEAK_NEW) cc_sneak_new_on = !cc_sneak_new_on;
    if (e->kind == K_SPRINT) { cc_sprint_armed = !cc_sprint_armed; cc_sprint_moved = 0; }
    if (e->kind == K_JOY_FIX || e->kind == K_JOY_FLOAT) {
        cc_joy[el][0] = e->cx * cc_W; cc_joy[el][1] = e->cy * cc_H;
    }
}

/* ---- the editor ---- */
static int cc_drag = -1, cc_slider = -1, cc_edptr = -1, cc_dragged;
static float cc_drag_dx, cc_drag_dy, cc_down_x, cc_down_y;
static long cc_toast_t;
static const char *cc_toast_key;

static void cc_enter_editor(void) {
    if (!cc_loaded) cc_load();
    if (cc_active && cc_n > 0) { memcpy(cc_ed, cc_el, sizeof cc_el); cc_edn = cc_n; }
    else cc_default_layout(cc_ed, &cc_edn);
    cc_editing = 1;
    cc_sel = -1; cc_panel = 0; cc_drag = -1; cc_slider = -1; cc_edptr = -1;
    LOG("controls: editor open, %d elements", cc_edn);
}
static void cc_leave_editor(int save) {
    if (save) {
        memcpy(cc_el, cc_ed, sizeof cc_ed);
        cc_n = cc_edn;
        cc_save();
        cc_active = 1;
        cc_loaded = 1;
        cc_apply_split(cc_special(cc_el, cc_n));
        cc_toast_key = "nova.ctl.saved";
        cc_toast_t = now_ms();
    }
    cc_editing = 0;
    cc_sel = -1; cc_panel = 0;
    memset(cc_fwd_el, 0, sizeof cc_fwd_el);
}

/* editor geometry in GUI units */
typedef struct { float x0, y0, x1, y1; } CcGBox;
static int g_in(CcGBox b, float x, float y) { return x >= b.x0 && x < b.x1 && y >= b.y0 && y < b.y1; }
static CcGBox cc_chrome(int i) {                           /* 0 ✕, 1 ✓, 2 + at the top right */
    float GW = cc_W * cc_inv, s = 18.f, m = 4.f;
    float x1 = GW - m - (2 - i) * (s + 4.f);
    return (CcGBox){x1 - s, m, x1, m + s};
}
static CcGBox cc_gbox(const CcEl *e) {
    CcBox b = cc_box(e);
    return (CcGBox){b.x0 * cc_inv, b.y0 * cc_inv, b.x1 * cc_inv, b.y1 * cc_inv};
}
static CcGBox cc_delbox(const CcEl *e) {
    CcGBox b = cc_gbox(e);
    return (CcGBox){b.x1 - 5.f, b.y0 - 7.f, b.x1 + 7.f, b.y0 + 5.f};
}
/* the slider panel of the selected element: two rows (size, opacity) */
static CcGBox cc_spanel(const CcEl *e) {
    CcGBox b = cc_gbox(e);
    float GW = cc_W * cc_inv, GH = cc_H * cc_inv, w = 104.f, h = 34.f;
    float x = (b.x0 + b.x1) / 2 - w / 2, y = b.y1 + 6.f;
    if (y + h > GH - 2.f) y = b.y0 - 6.f - h;
    if (y < 2.f) y = 2.f;
    if (x < 2.f) x = 2.f;
    if (x + w > GW - 2.f) x = GW - 2.f - w;
    return (CcGBox){x, y, x + w, y + h};
}
static CcGBox cc_track(CcGBox p, int row) { return (CcGBox){p.x0 + 44.f, p.y0 + 4.f + row * 15.f, p.x1 - 6.f, p.y0 + 4.f + row * 15.f + 10.f}; }
static float cc_def_size(int kind) { return CC_ASSET[kind].cells * cc_cell_frac(); }
/* size: 0.4 .. 2.5 of the default, opacity 0 .. 1 */
static float cc_sval(const CcEl *e, int row) {
    if (row == 0) { float r = e->size / cc_def_size(e->kind); return (r - 0.4f) / 2.1f; }
    return e->alpha;
}
static void cc_sset(CcEl *e, int row, float t) {
    if (t < 0) t = 0;
    if (t > 1) t = 1;
    if (row == 0) e->size = cc_def_size(e->kind) * (0.4f + 2.1f * t);
    else {
        if (t > cc_def_alpha - 0.04f && t < cc_def_alpha + 0.04f) t = cc_def_alpha;   /* the game's transparency: easy to hit */
        e->alpha = t;
    }
}

/* asset panel */
static CcGBox cc_pbox(void) {
    float GW = cc_W * cc_inv, GH = cc_H * cc_inv;
    float w = GW * 0.8f, h = GH * 0.78f;
    if (w > 330.f) w = 330.f;
    if (h > 190.f) h = 190.f;
    return (CcGBox){(GW - w) / 2, (GH - h) / 2, (GW + w) / 2, (GH + h) / 2};
}
static CcGBox cc_tabbox(int t) { CcGBox p = cc_pbox(); return (CcGBox){p.x0 + 4.f, p.y0 + 22.f + t * 22.f, p.x0 + 78.f, p.y0 + 40.f + t * 22.f}; }
static int cc_tab_items(int tab, int *out) {
    int n = 0;
    for (int k = 0; k < K_COUNT; k++) if (CC_ASSET[k].tab == tab) out[n++] = k;
    return n;
}
static CcGBox cc_tile(int i) {
    CcGBox p = cc_pbox();
    float gx = p.x0 + 84.f, gw = p.x1 - 4.f - gx;
    int cols = (int)(gw / 60.f);
    if (cols < 2) cols = 2;
    float cw = gw / cols;
    int c = i % cols, r = i / cols;
    float x = gx + c * cw, y = p.y0 + 22.f + r * 56.f;
    return (CcGBox){x + 2.f, y, x + cw - 2.f, y + 54.f};
}
static CcGBox cc_pclose(void) { CcGBox p = cc_pbox(); return (CcGBox){p.x1 - 18.f, p.y0 + 3.f, p.x1 - 3.f, p.y0 + 18.f}; }

static void cc_clamp(CcEl *e) {
    float hw = e->size * cc_H / 2 / cc_W, hh = e->size / 2;
    if (e->cx < hw) e->cx = hw;
    if (e->cx > 1 - hw) e->cx = 1 - hw;
    if (e->cy < hh) e->cy = hh;
    if (e->cy > 1 - hh) e->cy = 1 - hh;
}

static void cc_ed_down(float x, float y) {                /* GUI units */
    cc_down_x = x; cc_down_y = y; cc_dragged = 0;
    cc_drag = -1; cc_slider = -1;
    if (cc_panel) {
        if (g_in(cc_pclose(), x, y) || !g_in(cc_pbox(), x, y)) { cc_panel = 0; rb_click(); return; }
        for (int t = 0; t < CC_TABS; t++) if (g_in(cc_tabbox(t), x, y)) { cc_tab = t; rb_click(); return; }
        int it[K_COUNT], n = cc_tab_items(cc_tab, it);
        for (int i = 0; i < n; i++)
            if (g_in(cc_tile(i), x, y) && cc_edn < CC_MAX) {
                CcEl *e = &cc_ed[cc_edn];
                e->kind = it[i]; e->cx = 0.5f; e->cy = 0.5f; e->size = cc_def_size(it[i]); e->alpha = cc_def_alpha;
                cc_clamp(e);
                cc_sel = cc_edn++;
                cc_panel = 0;
                rb_click();
                return;
            }
        return;
    }
    for (int i = 0; i < 3; i++)
        if (g_in(cc_chrome(i), x, y)) {
            rb_click();
            if (i == 0) cc_leave_editor(0);
            else if (i == 1) cc_leave_editor(1);
            else { cc_panel = 1; cc_sel = -1; }
            return;
        }
    if (cc_sel >= 0 && cc_sel < cc_edn) {
        CcEl *e = &cc_ed[cc_sel];
        if (g_in(cc_delbox(e), x, y)) {
            for (int j = cc_sel; j + 1 < cc_edn; j++) cc_ed[j] = cc_ed[j + 1];
            cc_edn--; cc_sel = -1;
            rb_click();
            return;
        }
        CcGBox sp = cc_spanel(e);
        for (int r = 0; r < 2; r++) {
            CcGBox t = cc_track(sp, r);
            t.y0 -= 3.f; t.y1 += 3.f; t.x0 -= 4.f; t.x1 += 4.f;
            if (g_in(t, x, y)) { cc_slider = r; cc_sset(e, r, (x - (t.x0 + 4.f)) / (t.x1 - t.x0 - 8.f)); cc_clamp(e); return; }
        }
        if (g_in(sp, x, y)) return;
    }
    for (int i = cc_edn - 1; i >= 0; i--) {
        if (g_in(cc_gbox(&cc_ed[i]), x, y)) {
            cc_sel = i; cc_drag = i;
            cc_drag_dx = cc_ed[i].cx * cc_W * cc_inv - x;
            cc_drag_dy = cc_ed[i].cy * cc_H * cc_inv - y;
            return;
        }
    }
    cc_sel = -1;
}
static void cc_ed_move(float x, float y) {
    if (cc_slider >= 0 && cc_sel >= 0) {
        CcEl *e = &cc_ed[cc_sel];
        CcGBox t = cc_track(cc_spanel(e), cc_slider);
        cc_sset(e, cc_slider, (x - t.x0) / (t.x1 - t.x0));
        cc_clamp(e);
        return;
    }
    if (cc_drag >= 0) {
        if (!cc_dragged && (x - cc_down_x) * (x - cc_down_x) + (y - cc_down_y) * (y - cc_down_y) < 4.f) return;
        cc_dragged = 1;
        CcEl *e = &cc_ed[cc_drag];
        e->cx = (x + cc_drag_dx) / (cc_W * cc_inv);
        e->cy = (y + cc_drag_dy) / (cc_H * cc_inv);
        cc_clamp(e);
    }
}

static int cc_sent_any(void) {
    for (int b = 0; b < B_N; b++) if (cc_sent[b]) return 1;
    return cc_dir_on;
}

/* ---- TouchControlSet::tick ---- */
static u64 hc_ct_tick(u32 orig, u32 *w) {
    char *set = (char *)w[0];
    void *q = (void *)w[1];
    if (!cc_loaded) cc_load();
    cc_resolve_ids();
    if (g_client && mb_getw && mb_geth && mb_invscale) {
        cc_W = (float)mb_getw(g_client); cc_H = (float)mb_geth(g_client); cc_inv = mb_invscale();
    }
    if (cc_W < 10.f || cc_H < 10.f) return nh_callw(orig, w);
    /* only the game itself: no player, or a screen (main menu, pause, chat, inventory) on top -> hands off */
    if (!cc_player() || (cc_showingmenu && g_client && cc_showingmenu(g_client))) {
        if (cc_sent_any()) cc_release_all(q);
        for (int i = 0; i < CC_PTR; i++) cc_p[i].used = 0;
        cc_edptr = -1; cc_drag = -1; cc_slider = -1;
        return nh_callw(orig, w);
    }
    char *b = *(char **)set, *e = *(char **)(set + 4);
    for (int i = 0; i < CC_PTR; i++) cc_p[i].seen = 0;
    if (cc_editing) {                                     /* every pointer is the editor's */
        int seen = 0;
        for (char *it = b; it < e; it += 0x14) {
            int id = *(int *)it, st = *(int *)(it + 4);
            float x = *(float *)(it + 8) * cc_inv, y = *(float *)(it + 0xc) * cc_inv;
            *(int *)(it + 0x10) = 0;
            if (id == cc_edptr) { seen = 1; if (st == 2) cc_edptr = -1; else cc_ed_move(x, y); }
            else if (cc_edptr < 0 && st == 0) { cc_edptr = id; seen = 1; cc_ed_down(x, y); }
        }
        if (!seen && cc_edptr >= 0) { cc_edptr = -1; cc_drag = -1; cc_slider = -1; }
        if (cc_sent[B_UP] || cc_dir_on) cc_release_all(q);
        return nh_callw(orig, w);
    }
    if (!cc_active) return nh_callw(orig, w);
    for (char *it = b; it < e; it += 0x14) {
        int id = *(int *)it, st = *(int *)(it + 4);
        float x = *(float *)(it + 8), y = *(float *)(it + 0xc);
        CcPtr *P = 0;
        for (int i = 0; i < CC_PTR; i++) if (cc_p[i].used && cc_p[i].id == id) P = &cc_p[i];
        if (!P && st == 0) {
            int hit = -1;
            for (int i = cc_n - 1; i >= 0 && hit < 0; i--) if (cc_hit(&cc_el[i], x, y)) hit = i;
            if (hit >= 0)
                for (int i = 0; i < CC_PTR; i++)
                    if (!cc_p[i].used) {
                        P = &cc_p[i];
                        memset(P, 0, sizeof *P);
                        P->used = 1; P->id = id; P->el = hit; P->sx = x; P->sy = y;
                        if (cc_el[hit].kind == K_JOY_FLOAT) {          /* the stick comes under the finger */
                            float R = cc_el[hit].size * cc_H / 2;
                            float bx = x, by = y;
                            if (bx < R) bx = R;
                            if (by < R) by = R;
                            if (bx > cc_W - R) bx = cc_W - R;
                            if (by > cc_H - R) by = cc_H - R;
                            cc_joy[hit][0] = bx; cc_joy[hit][1] = by;
                        } else cc_press(hit);
                        break;
                    }
        }
        if (!P) continue;
        P->x = x; P->y = y; P->seen = st != 2;
        *(int *)(it + 0x10) = 0;                         /* ours: the camera and the game's controls leave it */
    }
    for (int i = 0; i < CC_PTR; i++)
        if (cc_p[i].used && !cc_p[i].seen) {
            int el = cc_p[i].el;
            if (el >= 0 && el < cc_n && (cc_el[el].kind == K_JOY_FIX || cc_el[el].kind == K_JOY_FLOAT)) cc_joy[el][2] = cc_joy[el][3] = 0;
            cc_p[i].used = 0;
        }
    cc_run(q);
    return nh_callw(orig, w);
}

/* the touch build/destroy of the world: off while attack/interact buttons are on the screen */
static u64 hc_ct_enqueue(u32 orig, u32 *w) {
    if (!cc_self && cc_active && cc_ids_ok && !cc_editing && cc_special(cc_el, cc_n)) {
        short id = (short)w[1];
        if (id == cc_bid[B_TOUCH_BUILD] || id == cc_bid[B_TOUCH_DESTROY]) return 0;
    }
    return nh_callw(orig, w);
}

static u64 hc_ct_addglyph(u32 orig, u32 *w) {
    if (!cc_registry) cc_registry = (void *)w[2];
    return nh_callw(orig, w);
}

/* ---- drawing (GUI units) ---- */
#define CC_TEX_GUI 0
#define CC_TEX_ATLAS 1
static u32 cc_tex[2][16];
static int cc_tex_ok[2];
static void *cc_texture(int i) {
    if (!cc_tex_ok[i] && g_client) {
        u32 s[1];
        gstr(s, i ? "gui/nova/controls.png" : "gui/gui.png");
        f_gettex(cc_tex[i], f_textures(g_client), s, 0);
        f_strdtor(s);
        cc_tex_ok[i] = 1;
    }
    return cc_tex[i];
}
static int cc_cur_tex = -1, cc_quads;
static void cc_flush(void) {
    if (cc_cur_tex >= 0 && cc_quads) mb_tdrawtex(mb_tess, mb_screenmats + 0x24, cc_texture(cc_cur_tex));
    cc_cur_tex = -1; cc_quads = 0;
}
static void cc_sprite(int tex, float x0, float y0, float x1, float y1, float u, float v, float uw, float vh,
                      float r, float g, float b, float a) {
    if (tex != cc_cur_tex || cc_quads >= 60) { cc_flush(); cc_cur_tex = tex; mb_tbegin(mb_tess, 64 * 4); }
    mb_tcolor(mb_tess, r, g, b, a);
    mbg_quad(x0, y0, x1, y1, u / 256.f, v / 256.f, (u + uw) / 256.f, (v + vh) / 256.f);
    cc_quads++;
}
#define CTA(name) CA_##name
static void cc_atlas(float x0, float y0, float x1, float y1, int ax, int ay, int aw, int ah, float r, float g, float b, float a) {
    cc_sprite(CC_TEX_ATLAS, x0, y0, x1, y1, (float)ax, (float)ay, (float)aw, (float)ah, r, g, b, a);
}
#define ATL(x0, y0, x1, y1, S, r, g, b, a) cc_atlas(x0, y0, x1, y1, S, r, g, b, a)
static void cc_fillr(float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
    ATL(x0, y0, x1, y1, CA_WHITE, r, g, b, a);
}
static void cc_frame(CcGBox b, float t, float r, float g, float bl, float a) {
    cc_fillr(b.x0, b.y0, b.x1, b.y0 + t, r, g, bl, a);
    cc_fillr(b.x0, b.y1 - t, b.x1, b.y1, r, g, bl, a);
    cc_fillr(b.x0, b.y0 + t, b.x0 + t, b.y1 - t, r, g, bl, a);
    cc_fillr(b.x1 - t, b.y0 + t, b.x1, b.y1 - t, r, g, bl, a);
}

/* a game glyph (by button), else the fallback cell in gui.png */
static void cc_gui_glyph(int b, float x0, float y0, float x1, float y1, int pressed, float alpha, const float *fb, int sneak_on) {
    CcGlyph *G = (b == B_SNEAK_TOGGLE && sneak_on) ? &cc_g_sneak_on : &cc_g[b];
    float uv[4];
    if (G->ok) memcpy(uv, G->uv, sizeof uv); else memcpy(uv, fb, sizeof uv);
    float c[4] = {1, 1, 1, 1};
    if (G->ok) memcpy(c, pressed ? G->col_p : G->col_n, sizeof c);
    else if (pressed) c[0] = c[1] = c[2] = 0.7f;
    cc_sprite(CC_TEX_GUI, x0, y0, x1, y1, uv[0], uv[1], uv[2], uv[3], c[0], c[1], c[2], alpha);
}
static const float FB_UP[4] = {0, 107, 26, 26}, FB_LEFT[4] = {26, 107, 26, 26}, FB_DOWN[4] = {52, 107, 26, 26},
                   FB_RIGHT[4] = {78, 107, 26, 26}, FB_JUMP[4] = {104, 107, 26, 26}, FB_UPRIGHT[4] = {0, 133, 26, 26},
                   FB_UPLEFT[4] = {26, 133, 26, 26}, FB_ASC[4] = {52, 133, 26, 26}, FB_DESC[4] = {78, 133, 26, 26},
                   FB_SNEAK[4] = {218, 64, 18, 18}, FB_SNEAK_ON[4] = {218, 82, 18, 18};

/* one element in a box (GUI units); live = the running game (pressed states shown) */
static void cc_draw_el(const CcEl *e, int idx, CcGBox B, float alpha, int live) {
    float s = B.x1 - B.x0;
    float pr = 0.72f;                                     /* our pictures, pressed */
    switch (e->kind) {
    case K_DPAD_JUMP: case K_DPAD_SNEAK: {
        float c = s / 3;
        int fw = live ? cc_fwd_el[idx] : 1;
        #define CELL(col, row) B.x0 + (col) * c, B.y0 + (row) * c, B.x0 + (col + 1) * c, B.y0 + (row + 1) * c
        cc_gui_glyph(B_UP_IGNORE, CELL(1, 0), live && cc_sent[B_UP], alpha, FB_UP, 0);
        cc_gui_glyph(B_LEFT, CELL(0, 1), live && cc_sent[B_LEFT], alpha, FB_LEFT, 0);
        cc_gui_glyph(B_RIGHT, CELL(2, 1), live && cc_sent[B_RIGHT], alpha, FB_RIGHT, 0);
        cc_gui_glyph(B_DOWN, CELL(1, 2), live && cc_sent[B_DOWN], alpha, FB_DOWN, 0);
        if (fw) {
            cc_gui_glyph(B_UPLEFT, CELL(0, 0), live && cc_sent[B_UPLEFT], alpha, FB_UPLEFT, 0);
            cc_gui_glyph(B_UPRIGHT, CELL(2, 0), live && cc_sent[B_UPRIGHT], alpha, FB_UPRIGHT, 0);
        }
        if (e->kind == K_DPAD_JUMP) cc_gui_glyph(B_JUMP, CELL(1, 1), live && cc_sent[B_JUMP], alpha, FB_JUMP, 0);
        else {
            int on = live && cc_is_sneaking();
            float m = c * 0.15f;
            cc_gui_glyph(B_SNEAK_TOGGLE, B.x0 + c + m, B.y0 + c + m, B.x0 + 2 * c - m, B.y0 + 2 * c - m, 0, alpha, on ? FB_SNEAK_ON : FB_SNEAK, on);
        }
        #undef CELL
        break;
    }
    case K_JOY_FIX: case K_JOY_FLOAT: {
        float cx = (B.x0 + B.x1) / 2, cy = (B.y0 + B.y1) / 2, R = s / 2;
        float kx = 0, ky = 0;
        int held = 0;
        if (live) for (int i = 0; i < CC_PTR; i++) if (cc_p[i].used && cc_p[i].el == idx) held = 1;
        if (live && held) {
            cx = cc_joy[idx][0] * cc_inv; cy = cc_joy[idx][1] * cc_inv;
            kx = cc_joy[idx][2]; ky = cc_joy[idx][3];
        }
        float a = (e->kind == K_JOY_FLOAT && live && !held) ? alpha * 0.6f : alpha;
        ATL(cx - R, cy - R, cx + R, cy + R, CA_JOY_BASE, 1, 1, 1, a);
        float kr = R * 0.46f, kxp = cx + kx * R * 0.6f, kyp = cy + ky * R * 0.6f;
        ATL(kxp - kr, kyp - kr, kxp + kr, kyp + kr, CA_JOY_KNOB, 1, 1, 1, alpha);
        break;
    }
    case K_FWD: cc_gui_glyph(B_UP_IGNORE, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_UP], alpha, FB_UP, 0); break;
    case K_BACK: cc_gui_glyph(B_DOWN, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_DOWN], alpha, FB_DOWN, 0); break;
    case K_LEFT: cc_gui_glyph(B_LEFT, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_LEFT], alpha, FB_LEFT, 0); break;
    case K_RIGHT: cc_gui_glyph(B_RIGHT, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_RIGHT], alpha, FB_RIGHT, 0); break;
    case K_UPLEFT: cc_gui_glyph(B_UPLEFT, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_UPLEFT], alpha, FB_UPLEFT, 0); break;
    case K_UPRIGHT: cc_gui_glyph(B_UPRIGHT, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_UPRIGHT], alpha, FB_UPRIGHT, 0); break;
    case K_JUMP: cc_gui_glyph(B_JUMP, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_JUMP], alpha, FB_JUMP, 0); break;
    case K_SNEAK_CLASSIC: {
        int on = live && cc_is_sneaking();
        float m = s * 0.12f;
        cc_gui_glyph(B_SNEAK_TOGGLE, B.x0 + m, B.y0 + m, B.x1 - m, B.y1 - m, 0, alpha, on ? FB_SNEAK_ON : FB_SNEAK, on);
        break;
    }
    case K_FLY_UP: cc_gui_glyph(B_ASCEND, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_ASCEND], alpha, FB_ASC, 0); break;
    case K_FLY_DOWN: cc_gui_glyph(B_DESCEND, B.x0, B.y0, B.x1, B.y1, live && cc_sent[B_DESCEND], alpha, FB_DESC, 0); break;
    default: {
        int on = 0, pressed = 0;
        if (live) for (int i = 0; i < CC_PTR; i++) if (cc_p[i].used && cc_p[i].el == idx) pressed = 1;
        float k = pressed ? pr : 1.f;
        switch (e->kind) {
        case K_SNEAK_NEW: on = live ? cc_sneak_new_on : 0;
            if (on) ATL(B.x0, B.y0, B.x1, B.y1, CA_SNEAK_CROUCH, k, k, k, alpha); else ATL(B.x0, B.y0, B.x1, B.y1, CA_SNEAK_STAND, k, k, k, alpha);
            break;
        case K_SPRINT: on = live ? cc_sprint_armed : 0;
            if (on) ATL(B.x0, B.y0, B.x1, B.y1, CA_SPRINT_ON, k, k, k, alpha); else ATL(B.x0, B.y0, B.x1, B.y1, CA_SPRINT_OFF, k, k, k, alpha);
            break;
        case K_ATTACK: ATL(B.x0, B.y0, B.x1, B.y1, CA_ATTACK, k, k, k, alpha); break;
        case K_INTERACT: ATL(B.x0, B.y0, B.x1, B.y1, CA_INTERACT, k, k, k, alpha); break;
        case K_F5: ATL(B.x0, B.y0, B.x1, B.y1, CA_F5, k, k, k, alpha); break;
        case K_DROP: ATL(B.x0, B.y0, B.x1, B.y1, CA_DROP, k, k, k, alpha); break;
        }
    }
    }
}

static void cc_text(float x, float y, const char *key, const float *c) {
    char t[96];
    loc_into(key, t, sizeof t);
    void *font = g_client ? *(void **)((char *)g_client + 0x7c) : 0;
    rb_text(font, x, y, t, c);
}
static void cc_text_raw(float x, float y, const char *t, const float *c) {
    void *font = g_client ? *(void **)((char *)g_client + 0x7c) : 0;
    rb_text(font, x, y, t, c);
}

static void cc_draw_editor(void) {
    float GW = cc_W * cc_inv, GH = cc_H * cc_inv;
    const float WH[4] = {1, 1, 1, 1}, GR[4] = {0.75f, 0.8f, 0.9f, 1}, MINT[4] = {0.38f, 0.87f, 0.81f, 1};
    cc_fillr(0, 0, GW, GH, 0, 0, 0.02f, 0.18f);           /* a light shade: edit mode */
    for (int i = 0; i < cc_edn; i++) {
        CcGBox b = cc_gbox(&cc_ed[i]);
        cc_draw_el(&cc_ed[i], i, b, cc_ed[i].alpha < 0.12f ? 0.12f : cc_ed[i].alpha, 0);
        if (i != cc_sel) cc_frame(b, 0.5f, 1, 1, 1, 0.22f);
    }
    if (cc_sel >= 0 && cc_sel < cc_edn) {
        CcEl *e = &cc_ed[cc_sel];
        CcGBox b = cc_gbox(e);
        cc_frame((CcGBox){b.x0 - 1, b.y0 - 1, b.x1 + 1, b.y1 + 1}, 1.f, 0.38f, 0.87f, 0.81f, 1.f);
        CcGBox d = cc_delbox(e);
        ATL(d.x0, d.y0, d.x1, d.y1, CA_IC_DOT, 0.85f, 0.25f, 0.25f, 1.f);
        ATL(d.x0 + 3, d.y0 + 3, d.x1 - 3, d.y1 - 3, CA_IC_CLOSE, 1, 1, 1, 1.f);
        CcGBox p = cc_spanel(e);
        ATL(p.x0, p.y0, p.x1, p.y1, CA_IC_ROUND, 0.05f, 0.06f, 0.1f, 0.82f);
        for (int r = 0; r < 2; r++) {
            CcGBox t = cc_track(p, r);
            float cy = (t.y0 + t.y1) / 2;
            cc_flush();
            cc_text(p.x0 + 5, t.y0 + 1, r ? "nova.ctl.opacity" : "nova.ctl.size", GR);
            cc_fillr(t.x0, cy - 0.5f, t.x1, cy + 0.5f, 1, 1, 1, 0.35f);
            if (r == 1) { float nx = t.x0 + (t.x1 - t.x0) * cc_def_alpha; cc_fillr(nx - 0.5f, cy - 3, nx + 0.5f, cy + 3, 0.38f, 0.87f, 0.81f, 0.9f); }
            float v = cc_sval(e, r), kx = t.x0 + (t.x1 - t.x0) * v;
            cc_fillr(t.x0, cy - 0.5f, kx, cy + 0.5f, 0.38f, 0.87f, 0.81f, 1);
            ATL(kx - 4, cy - 4, kx + 4, cy + 4, CA_IC_DOT, 1, 1, 1, 1);
        }
    }
    /* chrome */
    for (int i = 0; i < 3; i++) {
        CcGBox c = cc_chrome(i);
        ATL(c.x0, c.y0, c.x1, c.y1, CA_IC_ROUND, 0.05f, 0.06f, 0.1f, 0.75f);
        float m = 3.f;
        if (i == 0) ATL(c.x0 + m, c.y0 + m, c.x1 - m, c.y1 - m, CA_IC_CLOSE, 1.f, 0.55f, 0.55f, 1);
        else if (i == 1) ATL(c.x0 + m, c.y0 + m, c.x1 - m, c.y1 - m, CA_IC_CHECK, 0.45f, 0.95f, 0.75f, 1);
        else ATL(c.x0 + m, c.y0 + m, c.x1 - m, c.y1 - m, CA_IC_PLUS, 1, 1, 1, 1);
    }
    if (!cc_panel && cc_sel < 0) {
        cc_flush();
        cc_text(4, GH - 12, "nova.ctl.hint", GR);
    }
    /* asset panel */
    if (cc_panel) {
        CcGBox p = cc_pbox();
        cc_fillr(0, 0, GW, GH, 0, 0, 0, 0.45f);
        ATL(p.x0, p.y0, p.x1, p.y1, CA_IC_ROUND, 0.06f, 0.07f, 0.11f, 0.95f);
        CcGBox cl = cc_pclose();
        ATL(cl.x0 + 2, cl.y0 + 2, cl.x1 - 2, cl.y1 - 2, CA_IC_CLOSE, 1, 1, 1, 0.9f);
        for (int t = 0; t < CC_TABS; t++) {
            CcGBox tb = cc_tabbox(t);
            if (t == cc_tab) { ATL(tb.x0, tb.y0, tb.x1, tb.y1, CA_IC_ROUND, 0.38f, 0.87f, 0.81f, 0.25f); cc_fillr(tb.x0, tb.y0 + 3, tb.x0 + 1.5f, tb.y1 - 3, 0.38f, 0.87f, 0.81f, 1); }
        }
        int it[K_COUNT], n = cc_tab_items(cc_tab, it);
        for (int i = 0; i < n; i++) {
            CcGBox tl = cc_tile(i);
            ATL(tl.x0, tl.y0, tl.x1, tl.y1, CA_IC_ROUND, 1, 1, 1, 0.06f);
            float sz = 30.f, cx = (tl.x0 + tl.x1) / 2;
            CcEl pe = {it[i], 0, 0, 0, 1};
            cc_draw_el(&pe, -1, (CcGBox){cx - sz / 2, tl.y0 + 3, cx + sz / 2, tl.y0 + 3 + sz}, 1.f, 0);
        }
        cc_flush();
        cc_text(p.x0 + 8, p.y0 + 7, "nova.ctl.assets", WH);
        for (int t = 0; t < CC_TABS; t++) {
            CcGBox tb = cc_tabbox(t);
            cc_text(tb.x0 + 6, tb.y0 + 5, CC_TAB[t], t == cc_tab ? MINT : GR);
        }
        for (int i = 0; i < n; i++) {
            CcGBox tl = cc_tile(i);
            char t[96];
            loc_into(CC_ASSET[it[i]].key, t, sizeof t);
            char *nl = 0;
            for (char *q = t; *q; q++) if (*q == '|') { *q = 0; nl = q + 1; break; }
            cc_text_raw(tl.x0 + 3, tl.y0 + 35, t, WH);
            if (nl) cc_text_raw(tl.x0 + 3, tl.y0 + 44, nl, GR);
        }
    }
    cc_flush();
}

/* MinecraftInputHandler::render: the game's controls are drawn; ours on top */
static u64 hc_ct_render(u32 orig, u32 *w) {
    u64 r = nh_callw(orig, w);
    if (!(cc_active || cc_editing) || cc_W < 10.f || cc_inv <= 0.f || !mb_tess) return r;
    if (!cc_player() || (cc_showingmenu && g_client && cc_showingmenu(g_client))) return r;
    if (!cc_editing) {
        int fl = cc_is_flying();
        for (int i = 0; i < cc_n; i++) {
            CcEl *e = &cc_el[i];
            if ((e->kind == K_FLY_UP || e->kind == K_FLY_DOWN) && !fl) continue;
            cc_draw_el(e, i, cc_gbox(e), e->alpha, 1);
        }
        if (cc_toast_key && now_ms() - cc_toast_t < 1800) {
            cc_flush();
            const float c[4] = {0.38f, 0.87f, 0.81f, 1};
            cc_text(4, 4, cc_toast_key, c);
        }
        cc_flush();
    } else cc_draw_editor();
    return r;
}

/* from the settings: go to the game and open the editor */
static void cc_frame_tick(void *client) {
    if (!cc_want_edit || !client) return;
    if (!cc_player()) { cc_want_edit = 0; return; }
    if (cc_showingmenu && cc_showingmenu(client)) {
        static long last;
        long now = now_ms();
        if (now - last > 120 && cc_popscreen) { last = now; cc_popscreen(client, 1); }
        return;
    }
    cc_want_edit = 0;
    cc_enter_editor();
}

/* the settings: "Customise controls" (in a world only) and "Reset controls" (when a custom layout is on) */
static void controls_settings(void *pane, void *client) {
    if (!cc_loaded) cc_load();
    int inworld = client && *(void **)((char *)client + 0x120);
    if (!inworld && !cc_active) return;
    void *g = new_group(pane, "nova.ctl.group");
    if (inworld) add_button(g, "nova.ctl.edit", R_CTL_EDIT, "");
    if (cc_active) add_button(g, "nova.ctl.reset", R_CTL_RESET, "");
}
static void cc_settings_action(int i) {
    if (nopt_role[i] == R_CTL_EDIT) { cc_want_edit = 1; return; }
    cc_reset();
    cc_apply_split(0);
    loc_into("nova.ctl.reset_done", nopt_text[i], sizeof nopt_text[i]);
}

static void controls_init(void *mc) {
    cc_getnameid = dlsym(mc, "_ZN12NameRegistry9getNameIdERKSs");
    cc_enq_button = dlsym(mc, "_ZN15InputEventQueue13enqueueButtonEs11ButtonStateb");
    cc_enq_dir = dlsym(mc, "_ZN15InputEventQueue16enqueueDirectionE11DirectionIdff");
    cc_flying = dlsym(mc, "_ZNK9Abilities8isFlyingEv");
    cc_sneaking = dlsym(mc, "_ZNK3Mob10isSneakingEv");
    cc_showingmenu = dlsym(mc, "_ZNK15MinecraftClient13isShowingMenuEv");
    cc_popscreen = dlsym(mc, "_ZN15MinecraftClient9popScreenEi");
    cc_getbool = dlsym(mc, "_ZN7Options15getBooleanValueEPKNS_6OptionE");
    cc_toggle = dlsym(mc, "_ZN7Options6toggleEPKNS_6OptionEi");
    cc_optsave = dlsym(mc, "_ZN7Options4saveEv");
    cc_opt_split = dlsym(mc, "_ZN7Options6Option14SPLIT_CONTROLSE");
    void *tick = dlsym(mc, "_ZN15TouchControlSet4tickER15InputEventQueuei");
    void *addg = dlsym(mc, "_ZN15TouchControlSet14addGlyphButtonERK14BindingFactoryR12NameRegistryRK23TouchGlyphButtonBinding");
    void *rend = dlsym(mc, "_ZN21MinecraftInputHandler6renderEv");
    u32 vg = (u32)dlsym(mc, "_ZTV23TouchGlyphButtonControl");
    if (!cc_getnameid || !cc_enq_button || !tick || !addg || !rend || !vg) { LOGE("controls: symbols missing"); return; }
    if (nh_hook_c((u32)addg, hc_ct_addglyph) < 0 || nh_hook_c((u32)tick, hc_ct_tick) < 0 || nh_hook_c((u32)rend, hc_ct_render) < 0) {
        LOGE("controls: hooks failed");
        return;
    }
    nh_hook_c((u32)cc_enq_button, hc_ct_enqueue);
    patch_slot(vg, 2, cc_grender, (void **)&cc_o_grender);
    patch_slot(vg, 3, cc_gtick, (void **)&cc_o_gtick);
    float *bt = dlsym(mc, "_ZN7GuiData20BUTTONS_TRANSPARENCYE");   /* the game's button transparency */
    if (bt && *bt > 0.05f && *bt <= 1.f) cc_def_alpha = *bt;
    LOG("controls: ready (buttons %d%%)", (int)(cc_def_alpha * 100.f));
}
