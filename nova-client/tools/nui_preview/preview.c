/* PC preview of NOVA UI: builds a 1.1.5 screen with native/nui.inc and prints draw commands for render.py.
 * usage: preview <pack root> <widths.txt> <lang file> <screen e.g. start.start_screen> <w> <h> [hover x y] */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
typedef unsigned int u32;
#include "../../native/nui.inc"

static char ROOT[512];
static const char *f_file(const char *path, int *len) {
    char full[1024];
    snprintf(full, sizeof full, "%s/%s", ROOT, path);
    FILE *f = fopen(full, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    char *b = malloc(n + 1); fread(b, 1, n, f); b[n] = 0; fclose(f);
    *len = (int)n;
    return b;
}
/* textures: PNG/JPG size from the header */
static char *tex_names[4096]; static int tex_w[4096], tex_h[4096], tex_n;
static int f_texture(const char *path, int *w, int *h) {
    for (int i = 0; i < tex_n; i++) if (!strcmp(tex_names[i], path)) { *w = tex_w[i]; *h = tex_h[i]; return tex_w[i] ? i + 1 : 0; }
    char full[1024]; unsigned char hd[64]; int W = 0, H = 0;
    snprintf(full, sizeof full, "%s/%s.png", ROOT, path);
    FILE *f = fopen(full, "rb");
    if (f) { fread(hd, 1, 24, f); W = hd[16] << 24 | hd[17] << 16 | hd[18] << 8 | hd[19]; H = hd[20] << 24 | hd[21] << 16 | hd[22] << 8 | hd[23]; fclose(f); }
    else {
        snprintf(full, sizeof full, "%s/%s.jpg", ROOT, path);
        f = fopen(full, "rb");
        if (f) {                        /* JPEG: find SOF0/2 */
            int c, prev = 0;
            while ((c = fgetc(f)) != EOF) {
                if (prev == 0xff && (c == 0xc0 || c == 0xc2)) { unsigned char s[7]; fread(s, 1, 7, f); H = s[3] << 8 | s[4]; W = s[5] << 8 | s[6]; break; }
                prev = c;
            }
            fclose(f);
        }
    }
    tex_names[tex_n] = strdup(path); tex_w[tex_n] = W; tex_h[tex_n] = H; tex_n++;
    *w = W; *h = H;
    return W ? tex_n : 0;
}
static void f_image(int tex, float x, float y, float w, float h, float u0, float v0, float u1, float v1, const float *c) {
    printf("I\t%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.5f\t%.5f\t%.5f\t%.5f\t%.3f\t%.3f\t%.3f\t%.3f\n", tex_names[tex - 1], x, y, w, h, u0, v0, u1, v1, c[0], c[1], c[2], c[3]);
}
static int W8[65536];
static float f_text_width(const char *s, float sc) {
    float w = 0;
    const unsigned char *p = (const unsigned char *)s;
    while (*p) {
        int cp = *p++;
        if (cp >= 0xc0 && (*p & 0xc0) == 0x80) { if (cp < 0xe0) cp = (cp & 31) << 6 | (*p++ & 63); else { cp = (cp & 15) << 12 | (p[0] & 63) << 6 | (p[1] & 63); p += 2; } }
        if (cp == 0xa7 && *p) { p++; continue; }          /* § colour codes */
        w += W8[cp & 0xffff] ? W8[cp & 0xffff] : 6;
    }
    return w * sc;
}
static void f_text(const char *s, float x, float y, float sc, const float *c, int sh) {
    printf("T\t%.2f\t%.2f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%s\n", x, y, sc, c[0], c[1], c[2], c[3], sh, s);
}
static char **lk, **lv; static int ln;
static const char *f_loc(const char *k) {
    for (int i = 0; i < ln; i++) if (!strcmp(lk[i], k)) return lv[i];
    return 0;
}
static void f_custom(const char *r, float x, float y, float w, float h, void *c) {
    printf("C\t%s\t%.2f\t%.2f\t%.2f\t%.2f\n", r ? r : "?", x, y, w, h);
}
static JV *ctl_get(void *self, const char *b, NCtl *c) {
    if (!strcmp(b, "#version")) return jstr("v1.1.5 NOVA");
    if (!strcmp(b, "#development_version")) return jstr("");
    /* things this client does not have (gamepad hints, Xbox Live, Realms invites) */
    static const char *off[] = {"#gamepad_helper_visible", "#realms_pending_invite_visible", "#xbl_signin_visible",
        "#new_offers", "#achievements_visible", "#is_signed_in", "#realms_invite_visible", "#sign_in_visible",
        "#signingin_visible", "#realms_notification_visible", "#feedback_visible", "#trial_game_mode", 0};
    for (int i = 0; off[i]; i++) if (!strcmp(b, off[i])) return &nui_jfalse;
    return 0;
}
int main(int argc, char **argv) {
    strcpy(ROOT, argv[1]);
    FILE *wf = fopen(argv[2], "r"); int a, b;
    while (wf && fscanf(wf, "%d %d", &a, &b) == 2) if (a < 65536) W8[a] = b;
    FILE *lf = fopen(argv[3], "r");
    char line[4096];
    lk = malloc(sizeof(char *) * 40000); lv = malloc(sizeof(char *) * 40000);
    while (lf && fgets(line, sizeof line, lf)) {
        char *eq = strchr(line, '=');
        if (!eq || line[0] == '#') continue;
        *eq = 0;
        char *v = eq + 1, *e = v + strlen(v);
        while (e > v && (e[-1] == '\n' || e[-1] == '\r')) *--e = 0;
        char *t = strstr(v, "\t#"); if (t) *t = 0;
        lk[ln] = strdup(line); lv[ln] = strdup(v); ln++;
    }
    nui_p.file = f_file; nui_p.texture = f_texture; nui_p.image = f_image; nui_p.text = f_text;
    nui_p.text_width = f_text_width; nui_p.loc = f_loc; nui_p.custom = f_custom;
    nui_init_globals();
    int n = nui_load_all();
    fprintf(stderr, "files %d\n", n);
    NuiController ctl = {ctl_get, 0, 0, 0};
    nui_ctl = &ctl;
    NCtl *s = nui_screen(argv[4]);
    if (!s) { fprintf(stderr, "no screen %s\n", argv[4]); return 1; }
    float W = atof(argv[5]), H = atof(argv[6]);
    nui_bind(s);
    nui_frame_begin();                     /* as on the phone: layout once, draw every frame from the arena */
    nui_layout(s, 0, 0, W, H);
    nui_layout_kids(s);
    if (getenv("NUI_FRAMES")) {            /* a few frames first: nothing may depend on an earlier frame's arena */
        for (int i = 0; i < 3; i++) { fflush(stdout); FILE *keep = stdout; stdout = fopen("/dev/null", "w"); nui_render(s); fclose(stdout); stdout = keep; nui_frame_begin(); }
    }
    if (argc > 9) nui_touch(s, atof(argv[8]), atof(argv[9]), 1);
    if (getenv("NUI_TREE")) {
        void dump(NCtl *c, int d) {
            fprintf(stderr, "%*s%s t%d [%.1f %.1f %.1f %.1f] L%d%s\n", d * 2, "", c->name, c->type, c->x, c->y, c->w, c->h, c->layer, nui_visible(c) ? "" : " hidden");
            if (d < atoi(getenv("NUI_TREE"))) for (int i = 0; i < c->nk; i++) dump(c->kids[i], d + 1);
        }
        dump(s, 0);
    }
    nui_render(s);
    return 0;
}
