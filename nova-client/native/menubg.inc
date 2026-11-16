/* menubg.inc - the menu background (octa1): a snowy night instead of the panorama, and quiet menu music
 *
 * CubemapBackgroundScreen::render (vtable slot 15, behind every menu screen) is replaced by:
 *   clearDepthStencil, helper(self+4)->vfunc[2] (begin), our scene, vfunc[3] (end)   (as the game's render,
 *   without its white 35% gradient).
 * The scene is made of the game's own pictures (resource packs apply):
 *   sky        dark gradient (ScreenRenderer::fillGradient)
 *   stars      quads with the game's "stars" material (as LevelRenderer::_renderStars), twinkling
 *   moon       environment/moon_phases.png, full moon, "sun_moon" material (adds light, like the real sky),
 *              with a soft glow (gui/nova/menu/glow.png)
 *   hills      two rows of snowy block hills with a few spruces (flat colours)
 *   snow       environment/snow.png in three layers (far/middle/near) falling at different speeds
 * Music: an original slow piece for note-block harp + bass (tools/gen_menumusic.py -> menumusic.h), played with
 * MinecraftClient::playUI("note.harp"/"note.bass", volume, pitch) while a menu is on screen. NOVA settings:
 * "Menu music" (file files/nova_menumusic). The background itself can't be turned off; on any crash inside it the
 * panorama comes back for the rest of the run.
 * Also watches the selected skin for the face on the "Skins" button (gui/nova/skin_face.png, made in Java). */
#include "menumusic.h"

static int mb_ready, mb_broken;
static void (*mb_o_render)(void *self, void *sc);
static void (*mb_clear_ds)(void *rc);
static void (*mb_grad)(void *sr, int x0, int y0, int x1, int y1, const float *c0, const float *c1);
static int (*mb_getw)(void *client), (*mb_geth)(void *client);
static float (*mb_invscale)(void);
static void (*mb_tbegin)(void *t, int n);
static void (*mb_tcolor)(void *t, float r, float g, float b, float a);
static void (*mb_tvuv)(void *t, float x, float y, float z, float u, float v);
static void (*mb_tdraw)(void *t, void *mat);
static void (*mb_tdrawtex)(void *t, void *mat, void *tex);
static void (*mb_setcolor)(void *sc, const float *c);
static void (*mb_play)(void *client, void *name, float vol, float pitch);
static void (*mb_matptr)(void *mat, void *group, void *name);
static char *mb_tess, *mb_screenmats, *mb_common, *mb_shadercolor;
static u32 mb_mat_stars[4], mb_mat_moon[4];
static int mb_mats;
static u32 mb_rng = 0x9e3779b9u;
static long mb_last;
static float mb_time;

static u32 mb_rand(void) { mb_rng ^= mb_rng << 13; mb_rng ^= mb_rng >> 17; mb_rng ^= mb_rng << 5; return mb_rng; }
static float mb_frand(float a, float b) { return a + (b - a) * (float)(mb_rand() & 0xffffff) / 16777216.f; }

/* ---- textures (path under assets/images; packs and new textures go through Java getImageData) ---- */
#define MB_TEX 4
static struct { const char *path; u32 tex[16]; int ok; } mbg_tex[MB_TEX] = {
    {"environment/moon_phases.png"}, {"environment/snow.png"}, {"gui/nova/menu/glow.png"}, {"gui/nova/menu/white.png"}};
enum { T_MOON, T_SNOW, T_GLOW, T_WHITE };
static void *mb_texture(void *client, int i) {
    if (!mbg_tex[i].ok) {
        u32 st[1];
        gstr(st, mbg_tex[i].path);
        f_gettex(mbg_tex[i].tex, f_textures(client), st, 0);
        f_strdtor(st);
        mbg_tex[i].ok = 1;
    }
    return mbg_tex[i].tex;
}

/* quads in the game's order: (x0,y0) (x1,y0) (x1,y1) (x0,y1), as ScreenRenderer::blitRaw */
static void mbg_quad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1) {
    mb_tvuv(mb_tess, x0, y0, 0.f, u0, v0);
    mb_tvuv(mb_tess, x1, y0, 0.f, u1, v0);
    mb_tvuv(mb_tess, x1, y1, 0.f, u1, v1);
    mb_tvuv(mb_tess, x0, y1, 0.f, u0, v1);
}
static void mb_shader_color(float r, float g, float b, float a) {
    float c[4] = {r, g, b, a};
    mb_setcolor(mb_shadercolor, c);
}

/* ---- the scene, made once per run (a different night every start) ---- */
#define MB_STARS 190
static float mb_star[MB_STARS][6];                    /* x, y (0..1), size, brightness, twinkle speed, phase */
#define MB_COLS 160
static float mb_hill[2][MB_COLS];                     /* heights (fraction of H) of the far and near hills */
static unsigned char mb_tree[MB_COLS];
static int mb_made;

static void mb_make(void) {
    mb_made = 1;
    for (int i = 0; i < MB_STARS; i++) {
        float *s = mb_star[i];
        s[0] = mb_frand(0.f, 1.f);
        s[1] = mb_frand(0.f, 0.78f);
        s[1] = s[1] * s[1] / 0.78f;                    /* more stars high up */
        float r = mb_frand(0.f, 1.f);
        s[2] = r < 0.82f ? 0.55f : (r < 0.97f ? 0.85f : 1.25f);
        s[3] = mb_frand(0.35f, 1.f);
        s[4] = mb_frand(0.6f, 2.4f);
        s[5] = mb_frand(0.f, 6.283f);
    }
    float ph[6];
    for (int i = 0; i < 6; i++) ph[i] = mb_frand(0.f, 6.283f);
    for (int c = 0; c < MB_COLS; c++) {
        float x = (float)c;
        mb_hill[0][c] = 0.20f + 0.07f * sinf(x * 0.07f + ph[0]) + 0.035f * sinf(x * 0.19f + ph[1]) + 0.012f * sinf(x * 0.47f + ph[2]);
        mb_hill[1][c] = 0.10f + 0.045f * sinf(x * 0.11f + ph[3]) + 0.025f * sinf(x * 0.29f + ph[4]) + 0.01f * sinf(x * 0.71f + ph[5]);
        mb_tree[c] = (mb_rand() % 100) < 9 && c > 1 ? (unsigned char)(1 + mb_rand() % 2) : 0;
    }
}

static void mbg_rect(float x0, float y0, float x1, float y1, float r, float g, float b, float a) {
    mb_tcolor(mb_tess, r, g, b, a);
    mbg_quad(x0, y0, x1, y1, 0.f, 0.f, 1.f, 1.f);
}

static void mb_hills(void *client, float W, float H, int row) {
    const float B = 5.f;                               /* one "block" of the hills, GUI units */
    int cols = (int)(W / B) + 2;
    if (cols > MB_COLS) cols = MB_COLS;
    float base[3], cap[3];
    if (row == 0) { base[0] = 0.07f; base[1] = 0.09f; base[2] = 0.18f; cap[0] = 0.36f; cap[1] = 0.42f; cap[2] = 0.58f; }
    else { base[0] = 0.025f; base[1] = 0.03f; base[2] = 0.06f; cap[0] = 0.62f; cap[1] = 0.68f; cap[2] = 0.82f; }
    int trees = 0;
    for (int c = 0; c < cols; c++) if (row == 1 && mb_tree[c]) trees++;
    mb_tbegin(mb_tess, (cols * 2 + trees * 7) * 4);
    for (int c = 0; c < cols; c++) {
        float h = mb_hill[row][c] * H;
        h = (float)(int)(h / B) * B + B;               /* blocky */
        float x0 = c * B, x1 = x0 + B, y0 = H - h;
        mbg_rect(x0, y0, x1, H, base[0], base[1], base[2], 1.f);
        mbg_rect(x0, y0, x1, y0 + 2.f, cap[0], cap[1], cap[2], 1.f);
        if (row == 1 && mb_tree[c]) {                  /* a spruce: trunk and three snowy tiers */
            float cx = x0 + B / 2, t = mb_tree[c] == 2 ? 1.25f : 1.f;
            mbg_rect(cx - 1.f, y0 - 4.f * t, cx + 1.f, y0, base[0], base[1], base[2], 1.f);
            for (int k = 0; k < 3; k++) {
                float w = (6.f - 1.6f * k) * t, ty = y0 - (4.f + 5.f * k) * t;
                mbg_rect(cx - w, ty - 5.f * t, cx + w, ty, base[0], base[1], base[2], 1.f);
                mbg_rect(cx - w, ty - 5.f * t, cx + w, ty - 4.f * t, cap[0] * 0.9f, cap[1] * 0.9f, cap[2] * 0.9f, 1.f);
            }
        }
    }
    mb_tdrawtex(mb_tess, mb_screenmats + 0x24, mb_texture(client, T_WHITE));
}

static void mb_snow(void *client, float W, float H, float s, float speed, float wind, float alpha, float ph) {
    float tw = 64.f * s, th = 256.f * s;
    float oy = fmodf(mb_time * speed, th);
    float ox = fmodf(mb_time * wind + 6.f * s * sinf(mb_time * 0.35f + ph), tw);
    if (ox < 0) ox += tw;
    int nx = (int)(W / tw) + 2, ny = (int)(H / th) + 2;
    mb_tbegin(mb_tess, (nx + 1) * (ny + 1) * 4);
    mb_tcolor(mb_tess, 1.f, 1.f, 1.f, alpha);
    for (int j = -1; j < ny; j++)
        for (int i = -1; i < nx; i++) {
            float x0 = i * tw + ox, y0 = j * th + oy;
            mbg_quad(x0, y0, x0 + tw, y0 + th, 0.f, 0.f, 1.f, 1.f);
        }
    mb_tdrawtex(mb_tess, mb_screenmats + 0x24, mb_texture(client, T_SNOW));
}

static void mb_draw_scene(void *client, float W, float H, float dt) {
    if (!mb_made) mb_make();
    mb_time += dt;
    /* sky */
    float top[4] = {0.006f, 0.009f, 0.035f, 1.f}, bot[4] = {0.045f, 0.065f, 0.15f, 1.f};
    mb_grad(f_sr(), 0, 0, (int)W + 1, (int)H + 1, top, bot);
    /* stars */
    mb_shader_color(1.f, 1.f, 1.f, 1.f);
    mb_tbegin(mb_tess, MB_STARS * 4);
    for (int i = 0; i < MB_STARS; i++) {
        float *s = mb_star[i];
        float b = s[3] * (0.55f + 0.45f * sinf(mb_time * s[4] + s[5]));
        float x = s[0] * W, y = s[1] * H, r = s[2] * 0.5f;
        mb_tcolor(mb_tess, 0.85f * b, 0.9f * b, b, 1.f);
        mbg_quad(x - r, y - r, x + r, y + r, 0.f, 0.f, 0.f, 0.f);
    }
    mb_tdraw(mb_tess, mb_mat_stars);
    /* moon: in the open half of the screen */
    float ms = H * 0.42f, mx = W * 0.69f, my = H * 0.30f;   /* the 20 middle texels: the 8x8 moon and its whole halo */
    float gl = ms * 1.6f;
    mb_shader_color(0.45f, 0.55f, 0.85f, 0.42f);
    mb_tbegin(mb_tess, 4);
    mbg_quad(mx - gl / 2, my - gl / 2, mx + gl / 2, my + gl / 2, 0.f, 0.f, 1.f, 1.f);
    mb_tdrawtex(mb_tess, mb_mat_moon, mb_texture(client, T_GLOW));
    mb_shader_color(1.f, 1.f, 1.f, 1.f);
    mb_tbegin(mb_tess, 4);
    mbg_quad(mx - ms / 2, my - ms / 2, mx + ms / 2, my + ms / 2, 6.f / 128.f, 6.f / 64.f, 26.f / 128.f, 26.f / 64.f);   /* phase 0: full */
    mb_tdrawtex(mb_tess, mb_mat_moon, mb_texture(client, T_MOON));
    /* far snow, far hills, more snow, near hills, near snow */
    mb_snow(client, W, H, 0.55f, 6.f, 2.f, 0.22f, 0.f);
    mb_hills(client, W, H, 0);
    mb_snow(client, W, H, 0.9f, 12.f, 3.5f, 0.32f, 2.1f);
    mb_hills(client, W, H, 1);
    mb_snow(client, W, H, 1.5f, 21.f, 5.f, 0.45f, 4.2f);
}

/* ---- music ---- */
static float mm_t;
static int mm_i;
static long mm_seen;
static void mm_note(void *client, int inst, int idx, float vol) {
    if (!mb_play) return;
    u32 s[1];
    gstr(s, inst ? "note.bass" : "note.harp");
    mb_play(client, s, vol, powf(2.f, (float)(idx - 12) / 12.f));
    f_strdtor(s);
}
static void mm_tick(void *client, long now, float dt) {
    if (now - mm_seen > 4000) { mm_t = -1.2f; mm_i = 0; }   /* back in the menus: from the start */
    mm_seen = now;
    if (!mb_on) return;
    const float E = 60.f / 66.f / 2.f;                   /* an eighth at 66 bpm */
    mm_t += dt;
    int guard = 0;
    while (mm_i < MM_N && MM_EV[mm_i][0] * E <= mm_t && guard++ < 16) {
        const unsigned char *e = MM_EV[mm_i++];
        if ((e[4] & 1) && (mb_rand() % 100) < 12) continue;
        mm_note(client, e[1], e[2], (float)e[3] / 100.f * mb_frand(0.88f, 1.05f));
    }
    if (mm_t >= MM_EIGHTHS * E) { mm_t -= MM_EIGHTHS * E; mm_i = 0; }
}

/* ---- the selected skin, for the face on the Skins button ---- */
static char mb_skin[260] = "S";
static int mb_skin_frames;
static void mb_skin_check(void *client) {
    if (++mb_skin_frames < 30) return;
    mb_skin_frames = 0;
    char *repo = *(char **)((char *)client + 0x158);   /* MinecraftClient::getSkinRepository */
    if (!repo) return;
    char *skin = *(char **)(repo + 0x2c);               /* the selected skin, without getSelectedSkin's side effects */
    char cur[260] = "S";
    if (skin && *(int *)(skin + 0x14) == 0) {           /* Skin::isCustomSkin */
        const char *p = *(const char **)(skin + 0x10);   /* Skin::getPath */
        if (!p || !p[0]) p = *(const char **)(repo + 0x34);   /* SkinRepository custom skin path */
        if (p && p[0]) snprintf(cur, sizeof cur, "C:%s", p);
    }
    if (!streq(cur, mb_skin)) {
        memcpy(mb_skin, cur, sizeof cur);
        snprintf(tex_reload, sizeof tex_reload, "gui/nova/skin_face.png");
        tex_reload_on = 1;
    }
}
void *Java_com_nova_client_NovaActivity_nativeSkinPath(void **env, void *cls) {
    typedef void *(*newutf_fn)(void **, const char *);
    void **fns = *(void ***)env;
    return ((newutf_fn)fns[167])(env, mb_skin);
}

static void mb_render(void *self, void *sc) {
    mb_load();
    if (mb_broken || !mb_ready || !sc) { mb_o_render(self, sc); return; }
    void *client = *(void **)((char *)self + 0x14);
    void *rc = *(void **)((char *)sc + 0x10);
    void **helper = *(void ***)((char *)self + 4);
    if (!client || !rc || !helper) { mb_o_render(self, sc); return; }
    struct timespec_ ts;
    clock_gettime(1, &ts);
    long now = ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    float dt = mb_last ? (float)(now - mb_last) / 1000.f : 0.f;
    if (dt < 0.f || dt > 2.f) dt = 0.f;
    if (dt > 0.1f) dt = 0.1f;
    if (!mb_last) { mb_rng ^= (u32)now * 2654435761u ^ (u32)ts.tv_nsec; if (!mb_rng) mb_rng = 1; }
    mb_last = now;

    mb_clear_ds(rc);
    ((void (*)(void *, void *))((void **)*helper)[2])(helper, sc);   /* as the game's render: begin */
    static long outer[128];
    int g = nova_guard;
    memcpy(outer, nova_guard_jb, sizeof outer);
    if (crash_path[0]) {
        if (sigsetjmp(nova_guard_jb, 1)) {
            memcpy(nova_guard_jb, outer, sizeof outer);
            nova_guard = g;
            mb_broken = 1;
            LOGE("menu background: crashed (pc rel 0x%x), the panorama is back", nova_guard_pc);
            ((void (*)(void *, void *))((void **)*helper)[3])(helper, sc);
            return;
        }
        nova_guard = 1;
    }
    if (!mb_mats) {
        u32 s[1];
        gstr(s, "stars"); mb_matptr(mb_mat_stars, mb_common, s); f_strdtor(s);
        gstr(s, "sun_moon"); mb_matptr(mb_mat_moon, mb_common, s); f_strdtor(s);
        mb_mats = 1;
    }
    float inv = mb_invscale();
    float W = (float)mb_getw(client) * inv, H = (float)mb_geth(client) * inv;
    if (W > 8.f && H > 8.f) mb_draw_scene(client, W, H, dt);
    mm_tick(client, now, dt);
    mb_skin_check(client);
    memcpy(nova_guard_jb, outer, sizeof outer);
    nova_guard = g;
    ((void (*)(void *, void *))((void **)*helper)[3])(helper, sc);   /* end */
}

static void menubg_init(void *mc) {
    u32 vt = (u32)dlsym(mc, "_ZTV23CubemapBackgroundScreen");
    mb_clear_ds = dlsym(mc, "_ZN3mce13RenderContext23clearDepthStencilBufferEv");
    mb_grad = dlsym(mc, "_ZN14ScreenRenderer12fillGradientEiiiiRK5ColorS2_");
    mb_getw = dlsym(mc, "_ZNK15MinecraftClient8getWidthEv");
    mb_geth = dlsym(mc, "_ZNK15MinecraftClient9getHeightEv");
    mb_invscale = dlsym(mc, "_ZN7GuiData14getInvGuiScaleEv");
    mb_tess = dlsym(mc, "_ZN11Tessellator8instanceE");
    mb_tbegin = dlsym(mc, "_ZN11Tessellator5beginEi");
    mb_tcolor = dlsym(mc, "_ZN11Tessellator5colorEffff");
    mb_tvuv = dlsym(mc, "_ZN11Tessellator8vertexUVEfffff");
    mb_tdraw = dlsym(mc, "_ZN11Tessellator4drawERKN3mce11MaterialPtrE");
    mb_tdrawtex = dlsym(mc, "_ZN11Tessellator4drawERKN3mce11MaterialPtrERKNS0_10TexturePtrE");
    mb_setcolor = dlsym(mc, "_ZN11ShaderColor8setColorERK5Color");
    mb_shadercolor = dlsym(mc, "currentShaderColor");
    mb_screenmats = dlsym(mc, "_ZN14ScreenRenderer16mScreenMaterialsE");
    mb_common = dlsym(mc, "_ZN3mce19RenderMaterialGroup6commonE");
    mb_matptr = dlsym(mc, "_ZN3mce11MaterialPtrC1ERNS_19RenderMaterialGroupERKSs");
    mb_play = dlsym(mc, "_ZN15MinecraftClient6playUIERKSsff");
    if (!vt || !mb_clear_ds || !mb_grad || !mb_getw || !mb_geth || !mb_invscale || !mb_tess || !mb_tbegin || !mb_tcolor ||
        !mb_tvuv || !mb_tdraw || !mb_tdrawtex || !mb_setcolor || !mb_shadercolor || !mb_screenmats || !mb_common ||
        !mb_matptr || !f_gettex || !f_textures || !f_sr) {
        LOGE("menu background: unavailable, the panorama stays");
        return;
    }
    if (patch_slot(vt, 15, mb_render, (void **)&mb_o_render) != 0) { LOGE("menu background: hook failed"); return; }
    mb_ready = 1;
    LOG("menu background: ready (snowy night, %d music notes)", MM_N);
}
