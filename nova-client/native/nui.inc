/* nui.inc - NOVA UI: a JSON UI engine that reads Minecraft 1.1.5 UI files (resource_packs/vanilla/ui/*.json) as
 * they are and draws them with the game's own renderer.
 *
 * The 0.14 UI engine lacks what the 1.1.5 UI is built on (stack_panel, scroll_view, slider, dropdown, many screens
 * are C++ there), so NOVA has its own: the same file format, the same rules.
 *   files      { "namespace": "ns", "name": {..}, "name@ns.base": {..}, ... } - comments and trailing commas allowed
 *   inherit    "child@ns.base" - the base's properties, then the child's; "controls" of the child replace the base's
 *   variables  "$var": value, "$var|default": value (only when not set yet), "variables": [{"requires": "$x", ...}]
 *              a property "$var" / ["$a", 10] / "(not $x and $y)" takes the variable's value / the expression's
 *   layout     size/offset/min_size/max_size: number, "N%", "Npx", "N%c" (children), "N%x"/"N%y" (own width /
 *              height), "default", "fill", sums like "100% - 4px"; anchor_from / anchor_to (9 points); layer
 *              stack_panel: children one after another (orientation vertical/horizontal), "fill" takes the rest
 *   controls   panel, stack_panel, image (texture, uv, uv_size, nineslice_size, tiled, color, alpha, keep_ratio),
 *              label (text, color, shadow, font_size, font_scale_factor, text_alignment), button / toggle (state
 *              controls by name), input_panel, custom (renderer callback), screen; bindings (#name from the
 *              screen's controller, binding_name_override, "global" / "view")
 * The platform gives: files, textures, drawing, text, localization (nui_platform below). This file is plain C so the
 * same engine runs on a PC (tools/nui_preview) to check screens without a phone. */

/* ------------------------------------------------------------------------------------------------ platform ---- */
typedef struct {
    const char *(*file)(const char *path, int *len);                 /* "ui/start_screen.json" ... (0 = none) */
    int (*texture)(const char *path, int *w, int *h);                /* "textures/gui/newgui/X" (no extension) -> id, 0 = none */
    void (*image)(int tex, float x, float y, float w, float h, float u0, float v0, float u1, float v1, const float *rgba);
    void (*text)(const char *s, float x, float y, float scale, const float *rgba, int shadow);
    float (*text_width)(const char *s, float scale);
    const char *(*loc)(const char *key);                              /* localized text or 0 */
    void (*custom)(const char *renderer, float x, float y, float w, float h, void *ctrl);
} NuiPlatform;
static NuiPlatform nui_p;

/* ---------------------------------------------------------------------------------------------- allocation ---- */
/* While a frame is drawn (layout, render, touch) the values expressions make live only for that frame: they come
 * from an arena that is emptied at the next frame (nui_frame_begin), so nothing leaks however long a screen is open.
 * Everything built for good (files, controls, bound values) is made outside of frames. */
#define NUI_ARENA (512 * 1024)
static char *nui_arena;
static int nui_arena_used, nui_in_frame;
static void *nui_alloc(int n) {
    void *p = 0;
    if (nui_in_frame) {
        if (!nui_arena) nui_arena = malloc(NUI_ARENA);
        int need = (n + 7) & ~7;
        if (nui_arena && nui_arena_used + need <= NUI_ARENA) { p = nui_arena + nui_arena_used; nui_arena_used += need; }
    }
    if (!p) p = malloc(n);
    if (p) memset(p, 0, n);
    return p;
}
static void nui_free(void *p) {
    if (!p || (nui_arena && (char *)p >= nui_arena && (char *)p < nui_arena + NUI_ARENA)) return;
    free(p);
}
static void *nui_alloc_keep(int n) {           /* never from the arena: arrays that outlive a frame */
    int f = nui_in_frame; nui_in_frame = 0;
    void *p = nui_alloc(n);
    nui_in_frame = f;
    return p;
}
static void nui_frame_begin(void) { nui_in_frame = 1; nui_arena_used = 0; }
static void nui_frame_end(void) { nui_in_frame = 0; }
static int nui_len(const char *s) { int n = 0; while (s && s[n]) n++; return n; }
static int nui_eq(const char *a, const char *b) {
    if (!a || !b) return a == b;
    while (*a && *a == *b) a++, b++;
    return *a == *b;
}
static int nui_starts(const char *s, const char *p) { while (*p) if (*s++ != *p++) return 0; return 1; }
static char *nui_dup(const char *s, int n) {
    if (n < 0) n = nui_len(s);
    char *d = nui_alloc(n + 1);
    if (d) memcpy(d, s, n);
    return d;
}

/* ----------------------------------------------------------------------------------------------------- JSON ---- */
enum { JN_NULL, JN_FALSE, JN_TRUE, JN_NUM, JN_STR, JN_ARR, JN_OBJ };
typedef struct JV { int t; double n; char *s; int count; struct JV **v; char **k; } JV;
static JV nui_jnull = {JN_NULL}, nui_jtrue = {JN_TRUE}, nui_jfalse = {JN_FALSE};

static const char *jp_ws(const char *p, const char *e) {
    for (;;) {
        while (p < e && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r' || *p == '+')) {
            if (*p == '+' && !(p + 1 < e && p[1] == '*')) break;            /* "+*" lines of the copyright banner */
            p++;
        }
        if (p + 1 < e && p[0] == '/' && p[1] == '/') { while (p < e && *p != '\n') p++; continue; }
        if (p + 1 < e && p[0] == '/' && p[1] == '*') {
            p += 2;
            while (p + 1 < e && !(p[0] == '*' && p[1] == '/')) p++;
            p += 2;
            continue;
        }
        if (p < e && *p == '+' && p + 1 < e && p[1] == '*') { while (p < e && *p != '\n') p++; continue; }
        return p;
    }
}
static double nui_atof(const char *s, const char **end) {
    double v = 0, f = 0.1;
    int neg = 0;
    if (*s == '-') { neg = 1; s++; } else if (*s == '+') s++;
    while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0');
    if (*s == '.') { s++; while (*s >= '0' && *s <= '9') { v += (*s++ - '0') * f; f *= 0.1; } }
    if (*s == 'e' || *s == 'E') {
        s++;
        int en = 0, eneg = 0;
        if (*s == '-') { eneg = 1; s++; } else if (*s == '+') s++;
        while (*s >= '0' && *s <= '9') en = en * 10 + (*s++ - '0');
        while (en--) v = eneg ? v / 10 : v * 10;
    }
    if (end) *end = s;
    return neg ? -v : v;
}
static JV *jp_value(const char **pp, const char *e);
static char *jp_string(const char **pp, const char *e) {
    const char *p = *pp + 1;
    char *buf = nui_alloc((int)(e - p) + 1), *o = buf;
    while (p < e && *p != '"') {
        if (*p == '\\' && p + 1 < e) {
            p++;
            char c = *p++;
            if (c == 'n') *o++ = '\n'; else if (c == 't') *o++ = '\t';
            else if (c == 'u' && p + 4 <= e) {
                unsigned u = 0;
                for (int i = 0; i < 4; i++) { char h = *p++; u = u * 16 + (h <= '9' ? h - '0' : (h | 32) - 'a' + 10); }
                if (u < 0x80) *o++ = (char)u;
                else if (u < 0x800) { *o++ = (char)(0xc0 | u >> 6); *o++ = (char)(0x80 | (u & 63)); }
                else { *o++ = (char)(0xe0 | u >> 12); *o++ = (char)(0x80 | (u >> 6 & 63)); *o++ = (char)(0x80 | (u & 63)); }
            } else *o++ = c;
        } else *o++ = *p++;
    }
    *pp = p + 1;
    return buf;
}
static JV *jp_value(const char **pp, const char *e) {
    const char *p = jp_ws(*pp, e);
    JV *v = nui_alloc(sizeof *v);
    if (p >= e) { *pp = p; return v; }
    if (*p == '{' || *p == '[') {
        int obj = *p == '{', cap = 8;
        v->t = obj ? JN_OBJ : JN_ARR;
        v->v = nui_alloc(cap * sizeof(JV *));
        if (obj) v->k = nui_alloc(cap * sizeof(char *));
        p++;
        for (;;) {
            p = jp_ws(p, e);
            if (p >= e) break;
            if (*p == (obj ? '}' : ']')) { p++; break; }
            if (*p == ',') { p++; continue; }
            char *key = 0;
            if (obj) {
                if (*p != '"') { p++; continue; }
                key = jp_string(&p, e);
                p = jp_ws(p, e);
                if (p < e && *p == ':') p++;
            }
            JV *item = jp_value(&p, e);
            if (v->count == cap) {
                cap *= 2;
                JV **nv = nui_alloc(cap * sizeof(JV *));
                memcpy(nv, v->v, v->count * sizeof(JV *));
                nui_free(v->v); v->v = nv;
                if (obj) { char **nk = nui_alloc(cap * sizeof(char *)); memcpy(nk, v->k, v->count * sizeof(char *)); nui_free(v->k); v->k = nk; }
            }
            if (obj) v->k[v->count] = key;
            v->v[v->count++] = item;
        }
    } else if (*p == '"') { v->t = JN_STR; v->s = jp_string(&p, e); }
    else if (*p == 't') { v->t = JN_TRUE; p += 4; }
    else if (*p == 'f') { v->t = JN_FALSE; p += 5; }
    else if (*p == 'n') { v->t = JN_NULL; p += 4; }
    else { v->t = JN_NUM; v->n = nui_atof(p, &p); }
    *pp = p;
    return v;
}
static JV *nui_parse(const char *s, int len) { const char *p = s; return jp_value(&p, s + len); }
static JV *jget(JV *o, const char *k) {
    if (!o || o->t != JN_OBJ) return 0;
    for (int i = o->count - 1; i >= 0; i--) if (nui_eq(o->k[i], k)) return o->v[i];
    return 0;
}
static int jtruthy(JV *v) {
    if (!v) return 0;
    if (v->t == JN_TRUE) return 1;
    if (v->t == JN_NUM) return v->n != 0;
    if (v->t == JN_STR) return v->s[0] != 0 && !nui_eq(v->s, "false");
    return v->t == JN_ARR || v->t == JN_OBJ;
}
static JV *jstr(const char *s) { JV *v = nui_alloc(sizeof *v); v->t = JN_STR; v->s = nui_dup(s, -1); return v; }
static JV *jnum(double n) { JV *v = nui_alloc(sizeof *v); v->t = JN_NUM; v->n = n; return v; }

/* --------------------------------------------------------------------------------------- definitions table ---- */
typedef struct NDef { char *full; char *ns; char *name; char *base; JV *obj; struct NDef *next; } NDef;
#define NUI_HASH 4096
static NDef *nui_defs[NUI_HASH];
static unsigned nui_hash(const char *s) { unsigned h = 2166136261u; while (*s) h = (h ^ (unsigned char)*s++) * 16777619u; return h & (NUI_HASH - 1); }
static NDef *nui_def(const char *full) {
    for (NDef *d = nui_defs[nui_hash(full)]; d; d = d->next) if (nui_eq(d->full, full)) return d;
    return 0;
}
static void nui_add_def(const char *ns, const char *key, JV *obj) {
    const char *at = key;
    while (*at && *at != '@') at++;
    NDef *d = nui_alloc(sizeof *d);
    d->ns = nui_dup(ns, -1);
    d->name = nui_dup(key, (int)(at - key));
    if (*at) {
        const char *b = at + 1;
        int dot = 0;
        for (const char *q = b; *q; q++) if (*q == '.') dot = 1;
        if (dot) d->base = nui_dup(b, -1);
        else { int n1 = nui_len(ns), n2 = nui_len(b); d->base = nui_alloc(n1 + n2 + 2); memcpy(d->base, ns, n1); d->base[n1] = '.'; memcpy(d->base + n1 + 1, b, n2); }
    }
    int n1 = nui_len(ns), n2 = nui_len(d->name);
    d->full = nui_alloc(n1 + n2 + 2);
    memcpy(d->full, ns, n1); d->full[n1] = '.'; memcpy(d->full + n1 + 1, d->name, n2);
    d->obj = obj;
    unsigned h = nui_hash(d->full);
    d->next = nui_defs[h];                    /* a later file with the same name wins (resource packs on top) */
    nui_defs[h] = d;
}
static int nui_load_file(const char *path) {
    int len = 0;
    const char *s = nui_p.file ? nui_p.file(path, &len) : 0;
    if (!s) return 0;
    JV *root = nui_parse(s, len);
    JV *ns = jget(root, "namespace");
    if (!ns || ns->t != JN_STR) return 0;
    for (int i = 0; i < root->count; i++)
        if (root->v[i]->t == JN_OBJ && !nui_eq(root->k[i], "namespace")) nui_add_def(ns->s, root->k[i], root->v[i]);
    return 1;
}
/* ui/_ui_defs.json lists the files */
static int nui_load_all(void) {
    int len = 0, n = 0;
    const char *s = nui_p.file ? nui_p.file("ui/_ui_defs.json", &len) : 0;
    if (!s) return 0;
    JV *defs = jget(nui_parse(s, len), "ui_defs");
    for (int i = 0; defs && i < defs->count; i++) if (defs->v[i]->t == JN_STR) n += nui_load_file(defs->v[i]->s);
    return n;
}

/* ------------------------------------------------------------------------------------- variables, expressions ---- */
typedef struct { char *name; JV *val; } NVar;
typedef struct { NVar *v; int n, cap; } NScope;
static JV *nui_globals_get(const char *name);
static JV *scope_get(NScope *s, const char *name) {
    for (int i = s->n - 1; i >= 0; i--) if (nui_eq(s->v[i].name, name)) return s->v[i].val;
    return nui_globals_get(name);
}
static void scope_set(NScope *s, const char *name, JV *val) {
    for (int i = s->n - 1; i >= 0; i--) if (nui_eq(s->v[i].name, name)) { s->v[i].val = val; return; }
    if (s->n == s->cap) {
        int cap = s->cap ? s->cap * 2 : 16;
        NVar *nv = nui_alloc_keep(cap * sizeof(NVar));
        if (s->n) memcpy(nv, s->v, s->n * sizeof(NVar));
        nui_free(s->v); s->v = nv; s->cap = cap;
    }
    s->v[s->n].name = (char *)name; s->v[s->n].val = val; s->n++;
}
static void scope_copy(NScope *to, NScope *from) {
    to->n = 0; to->cap = from->n + 8;
    to->v = nui_alloc(to->cap * sizeof(NVar));
    if (from->n) memcpy(to->v, from->v, from->n * sizeof(NVar));
    to->n = from->n;
}

/* globals the screens test ($pocket_edition, $touch...) and what the controller binds (#name) */
static NScope nui_globals;
static JV *nui_globals_get(const char *name) {
    for (int i = nui_globals.n - 1; i >= 0; i--) if (nui_eq(nui_globals.v[i].name, name)) return nui_globals.v[i].val;
    return 0;
}

/* expressions: ( a and b ), not, or, =, +, 'text', $var, #binding, bare words (concatenated as text) */
typedef struct NCtl NCtl;
static JV *nui_binding(NCtl *c, const char *name);
typedef struct { const char *p; NScope *sc; NCtl *ctl; } NExpr;
static JV *ex_or(NExpr *x);
static void ex_ws(NExpr *x) { while (*x->p == ' ') x->p++; }
static int ex_word(NExpr *x, const char *w) {
    ex_ws(x);
    int n = nui_len(w);
    for (int i = 0; i < n; i++) if (x->p[i] != w[i]) return 0;
    char c = x->p[n];
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || (c >= '0' && c <= '9')) return 0;
    x->p += n;
    return 1;
}
static JV *ex_atom(NExpr *x) {
    ex_ws(x);
    if (*x->p == '(') { x->p++; JV *v = ex_or(x); ex_ws(x); if (*x->p == ')') x->p++; return v; }
    if (ex_word(x, "not")) { JV *v = ex_atom(x); return jtruthy(v) ? &nui_jfalse : &nui_jtrue; }
    if (*x->p == '\'') {
        const char *s = ++x->p;
        while (*x->p && *x->p != '\'') x->p++;
        char *t = nui_dup(s, (int)(x->p - s));
        if (*x->p) x->p++;
        JV *v = jstr(t); nui_free(t);
        return v;
    }
    const char *s = x->p;
    while (*x->p && *x->p != ' ' && *x->p != ')' && *x->p != '(' && *x->p != '=' && *x->p != '+' && *x->p != '\'') x->p++;
    char *w = nui_dup(s, (int)(x->p - s));
    JV *v;
    if (w[0] == '$') { v = scope_get(x->sc, w); if (!v) v = &nui_jnull; }
    else if (w[0] == '#') { v = nui_binding(x->ctl, w); if (!v) v = &nui_jnull; }
    else if (nui_eq(w, "true")) v = &nui_jtrue;
    else if (nui_eq(w, "false")) v = &nui_jfalse;
    else if ((w[0] >= '0' && w[0] <= '9') || w[0] == '-') v = jnum(nui_atof(w, 0));
    else v = jstr(w);
    nui_free(w);
    return v;
}
static char *jv_text(JV *v) {                  /* a value as text (for +) */
    char b[64];
    if (!v || v->t == JN_NULL) return nui_dup("", 0);
    if (v->t == JN_STR) return nui_dup(v->s, -1);
    if (v->t == JN_TRUE) return nui_dup("true", -1);
    if (v->t == JN_FALSE) return nui_dup("false", -1);
    if (v->t == JN_NUM) {
        int i = (int)v->n;                     /* no 64-bit division in libnova */
        int k = 0, neg = i < 0;
        if (neg) i = -i;
        do { int q = i / 10; b[k++] = (char)('0' + (i - q * 10)); i = q; } while (i);
        char *o = nui_alloc(k + 2); int j = 0;
        if (neg) o[j++] = '-';
        while (k) o[j++] = b[--k];
        return o;
    }
    return nui_dup("", 0);
}
static JV *ex_sum(NExpr *x) {
    JV *a = ex_atom(x);
    for (;;) {
        ex_ws(x);
        if (*x->p != '+') return a;
        x->p++;
        JV *b = ex_atom(x);
        if (a->t == JN_NUM && b->t == JN_NUM) { a = jnum(a->n + b->n); continue; }
        char *s1 = jv_text(a), *s2 = jv_text(b);
        int n1 = nui_len(s1), n2 = nui_len(s2);
        char *t = nui_alloc(n1 + n2 + 1);
        memcpy(t, s1, n1); memcpy(t + n1, s2, n2);
        a = nui_alloc(sizeof *a); a->t = JN_STR; a->s = t;
        nui_free(s1); nui_free(s2);
    }
}
static int jv_same(JV *a, JV *b) {
    if (a->t == JN_NUM && b->t == JN_NUM) return a->n == b->n;
    if ((a->t == JN_TRUE || a->t == JN_FALSE) && (b->t == JN_TRUE || b->t == JN_FALSE)) return a->t == b->t;
    char *s1 = jv_text(a), *s2 = jv_text(b);
    int r = nui_eq(s1, s2);
    nui_free(s1); nui_free(s2);
    return r;
}
static JV *ex_cmp(NExpr *x) {
    JV *a = ex_sum(x);
    ex_ws(x);
    if (*x->p == '=') { x->p++; if (*x->p == '=') x->p++; JV *b = ex_sum(x); return jv_same(a, b) ? &nui_jtrue : &nui_jfalse; }
    return a;
}
static JV *ex_and(NExpr *x) {
    JV *a = ex_cmp(x);
    while (ex_word(x, "and")) { JV *b = ex_cmp(x); a = jtruthy(a) && jtruthy(b) ? &nui_jtrue : &nui_jfalse; }
    return a;
}
static JV *ex_or(NExpr *x) {
    JV *a = ex_and(x);
    while (ex_word(x, "or")) { JV *b = ex_and(x); a = jtruthy(a) || jtruthy(b) ? &nui_jtrue : &nui_jfalse; }
    return a;
}
/* a property's value: "$var" -> its value, "(expr)" -> evaluated, arrays element by element */
static JV *nui_resolve(JV *v, NScope *sc, NCtl *c, int depth) {
    if (!v || depth > 16) return v;
    if (v->t == JN_STR) {
        if (v->s[0] == '$') { JV *r = scope_get(sc, v->s); return r ? nui_resolve(r, sc, c, depth + 1) : v; }
        if (v->s[0] == '(') { NExpr x = {v->s, sc, c}; return ex_or(&x); }
        return v;
    }
    if (v->t == JN_ARR) {
        int need = 0;
        for (int i = 0; i < v->count; i++) if (v->v[i]->t == JN_STR && (v->v[i]->s[0] == '$' || v->v[i]->s[0] == '(')) need = 1;
        if (!need) return v;
        JV *o = nui_alloc(sizeof *o);
        o->t = JN_ARR; o->count = v->count; o->v = nui_alloc(v->count * sizeof(JV *));
        for (int i = 0; i < v->count; i++) o->v[i] = nui_resolve(v->v[i], sc, c, depth + 1);
        return o;
    }
    return v;
}

/* -------------------------------------------------------------------------------------------------- controls ---- */
enum { NT_PANEL, NT_STACK, NT_IMAGE, NT_LABEL, NT_BUTTON, NT_TOGGLE, NT_INPUT, NT_CUSTOM, NT_SCREEN, NT_GRID,
       NT_FACTORY, NT_SCROLL, NT_OTHER };
typedef struct NProp { char *k; JV *v; } NProp;
struct NCtl {
    char *name;
    int type;
    NProp *p; int np;
    NScope sc;
    NCtl **kids; int nk;
    NCtl *parent;
    float x, y, w, h;                 /* layout, gui units */
    int layer;
    int state;                        /* buttons: 0 default 1 hover 2 pressed 3 locked; toggles: + 4 checked */
    void *user;
};
static JV *cp(NCtl *c, const char *k) {
    for (int i = c->np - 1; i >= 0; i--) if (nui_eq(c->p[i].k, k)) return nui_resolve(c->p[i].v, &c->sc, c, 0);
    return 0;
}
static void cp_set(NCtl *c, const char *k, JV *v) {
    for (int i = c->np - 1; i >= 0; i--) if (nui_eq(c->p[i].k, k)) { c->p[i].v = v; return; }
    NProp *np = nui_alloc_keep((c->np + 1) * sizeof(NProp));
    if (c->np) memcpy(np, c->p, c->np * sizeof(NProp));
    nui_free(c->p); c->p = np;
    c->p[c->np].k = (char *)k; c->p[c->np].v = v; c->np++;
}
static double cpn(NCtl *c, const char *k, double d) { JV *v = cp(c, k); return v && v->t == JN_NUM ? v->n : d; }
static const char *cps(NCtl *c, const char *k) { JV *v = cp(c, k); return v && v->t == JN_STR ? v->s : 0; }

/* the controller of the screen being built: bindings and buttons */
typedef struct {
    JV *(*get)(void *self, const char *binding, NCtl *c);             /* "#version" -> value or 0 */
    void (*button)(void *self, const char *button_name, NCtl *c);    /* "button.menu_play" pressed */
    int (*toggle)(void *self, const char *toggle_name, int set);      /* set -1 = read */
    void *self;
} NuiController;
static NuiController *nui_ctl;
static JV *nui_binding(NCtl *c, const char *name) { return nui_ctl && nui_ctl->get ? nui_ctl->get(nui_ctl->self, name, c) : 0; }

static int nui_type_of(const char *t) {
    static const char *names[] = {"panel", "stack_panel", "image", "label", "button", "toggle", "input_panel", "custom",
                                  "screen", "grid", "factory", "scroll_view"};
    for (int i = 0; i < 12; i++) if (t && nui_eq(t, names[i])) return i;
    return NT_OTHER;
}

/* the chain of objects a control is made of: base first, the control's own, the reference's overrides last */
#define NUI_CHAIN 32
static int nui_chain(const char *full, JV **out, int n, int depth) {
    NDef *d = full ? nui_def(full) : 0;
    if (!d || depth > 24) return n;
    if (d->base) n = nui_chain(d->base, out, n, depth + 1);
    if (n < NUI_CHAIN) out[n++] = d->obj;
    return n;
}
static void nui_apply_vars(JV *o, NScope *sc, NCtl *c) {
    for (int i = 0; i < o->count; i++) {
        const char *k = o->k[i];
        if (k[0] != '$') continue;
        int bar = 0;
        while (k[bar] && k[bar] != '|') bar++;
        if (k[bar]) {                                         /* "$x|default" */
            char *nm = nui_dup(k, bar);
            if (!scope_get(sc, nm)) scope_set(sc, nm, nui_resolve(o->v[i], sc, c, 0));
            else nui_free(nm);
        } else scope_set(sc, (char *)k, nui_resolve(o->v[i], sc, c, 0));
    }
    JV *vars = jget(o, "variables");                          /* [{"requires": "$x", "$y": ..}] */
    for (int i = 0; vars && vars->t == JN_ARR && i < vars->count; i++) {
        JV *r = jget(vars->v[i], "requires");
        if (r && jtruthy(nui_resolve(r, sc, c, 0))) nui_apply_vars(vars->v[i], sc, c);
    }
}
static NCtl *nui_build(const char *name, const char *ref, JV *own, NScope *parent, const char *ns, NCtl *up, int depth);

/* "name@ref" of a child: either part may be a variable */
static void nui_children(NCtl *c, JV *controls, const char *ns, int depth) {
    if (!controls || controls->t != JN_ARR) return;
    c->kids = nui_alloc((controls->count + 1) * sizeof(NCtl *));
    for (int i = 0; i < controls->count; i++) {
        JV *e = controls->v[i];
        if (e->t != JN_OBJ || !e->count) continue;
        const char *key = e->k[0];
        const char *at = key;
        while (*at && *at != '@') at++;
        char *nm = nui_dup(key, (int)(at - key));
        char *ref = *at ? nui_dup(at + 1, -1) : 0;
        if (nm[0] == '$') { JV *v = scope_get(&c->sc, nm); if (v && v->t == JN_STR) { nui_free(nm); nm = nui_dup(v->s, -1); } }
        if (ref && ref[0] == '$') {
            JV *v = scope_get(&c->sc, ref);
            nui_free(ref);
            ref = v && v->t == JN_STR ? nui_dup(v->s, -1) : 0;
            if (!ref) { nui_free(nm); continue; }
        }
        if (ref) {                                            /* "name" alone means this namespace */
            int dot = 0;
            for (const char *q = ref; *q; q++) if (*q == '.') dot = 1;
            if (!dot) {
                int n1 = nui_len(ns), n2 = nui_len(ref);
                char *f = nui_alloc(n1 + n2 + 2);
                memcpy(f, ns, n1); f[n1] = '.'; memcpy(f + n1 + 1, ref, n2);
                nui_free(ref); ref = f;
            }
        }
        NCtl *k = nui_build(nm, ref, e->v[0], &c->sc, ns, c, depth + 1);
        if (k) c->kids[c->nk++] = k;
    }
}
static NCtl *nui_build(const char *name, const char *ref, JV *own, NScope *parent, const char *ns, NCtl *up, int depth) {
    if (depth > 64) return 0;
    JV *chain[NUI_CHAIN + 1];
    int n = ref ? nui_chain(ref, chain, 0, 0) : 0;
    if (ref && !n) return 0;                                  /* unknown reference: nothing (like the game) */
    if (own && n < NUI_CHAIN + 1) chain[n++] = own;
    NCtl *c = nui_alloc(sizeof *c);
    c->name = (char *)name;
    c->parent = up;
    scope_copy(&c->sc, parent);
    const char *my_ns = ns;
    if (ref) { NDef *d = nui_def(ref); if (d) my_ns = d->ns; }
    for (int i = 0; i < n; i++) nui_apply_vars(chain[i], &c->sc, c);
    JV *controls = 0;
    for (int i = 0; i < n; i++) {
        JV *o = chain[i];
        for (int j = 0; j < o->count; j++) {
            const char *k = o->k[j];
            if (k[0] == '$' || nui_eq(k, "variables")) continue;
            if (nui_eq(k, "controls")) { controls = o->v[j]; continue; }
            cp_set(c, k, o->v[j]);
        }
    }
    JV *ig = cp(c, "ignored");
    if (ig && jtruthy(ig)) { nui_free(c); return 0; }
    c->type = nui_type_of(cps(c, "type"));
    c->layer = (int)cpn(c, "layer", 0);
    nui_children(c, controls, my_ns, depth);
    return c;
}
/* a screen: "start.start_screen" */
static NCtl *nui_screen(const char *full) {
    NScope root = {0};
    return nui_build("screen", full, 0, &root, "", 0, 0);
}
static NCtl *nui_find(NCtl *c, const char *name) {
    if (!c) return 0;
    if (nui_eq(c->name, name)) return c;
    for (int i = 0; i < c->nk; i++) { NCtl *r = nui_find(c->kids[i], name); if (r) return r; }
    return 0;
}

/* ---------------------------------------------------------------------------------------------------- layout ---- */
/* one axis of a size: sum of terms "N%", "N%c", "N%x", "N%y", "Npx", "default", "fill", plain numbers */
typedef struct { float pct, pct_c, pct_x, pct_y, px; int def, fill; } NSize;
static NSize nui_size_term(JV *v) {
    NSize s = {0};
    if (!v) { s.pct = 100; return s; }
    if (v->t == JN_NUM) { s.px = (float)v->n; return s; }
    if (v->t != JN_STR || v->s[0] == '$') { s.pct = 100; return s; }    /* unset variable: as if not given */
    const char *p = v->s;
    int sign = 1;
    while (*p) {
        while (*p == ' ') p++;
        if (*p == '+') { sign = 1; p++; continue; }
        if (*p == '-' && !(p[1] >= '0' && p[1] <= '9' && (p == v->s))) {
            if (p == v->s) { sign = -1; p++; continue; }
            sign = -1; p++; continue;
        }
        if (nui_starts(p, "default")) { s.def = 1; p += 7; continue; }
        if (nui_starts(p, "fill")) { s.fill = 1; p += 4; continue; }
        if (!((*p >= '0' && *p <= '9') || *p == '.' || *p == '-')) { p++; continue; }
        float n = (float)nui_atof(p, &p) * sign;
        sign = 1;
        while (*p == ' ') p++;
        if (*p == '%') {
            p++;
            if (*p == 'c') { s.pct_c += n; p++; }
            else if (*p == 'x') { s.pct_x += n; p++; }
            else if (*p == 'y') { s.pct_y += n; p++; }
            else if (*p == 's' && p[1] == 'm') { s.pct += n; p += 2; }
            else s.pct += n;
        } else if (*p == 'c' && p[1] == '%') { s.pct_c += n; p += 2; }
        else { if (*p == 'p' && p[1] == 'x') p += 2; s.px += n; }
    }
    return s;
}
static JV *nui_axis(JV *pair, int axis) {
    if (!pair) return 0;
    if (pair->t == JN_ARR) return pair->count > axis ? pair->v[axis] : 0;
    return pair;
}
static int nui_font_line = 10;
static float nui_text_scale(NCtl *c) {
    float s = (float)cpn(c, "font_scale_factor", 1);
    const char *fs = cps(c, "font_size");
    if (fs && nui_eq(fs, "large")) s *= 1.5f;
    else if (fs && nui_eq(fs, "extra_large")) s *= 2.f;
    else if (fs && nui_eq(fs, "small")) s *= 0.8f;
    return s;
}
static const char *nui_label_text(NCtl *c);
static float nui_default(NCtl *c, int axis, float parent) {
    if (c->type == NT_LABEL) {
        const char *t = nui_label_text(c);
        float sc = nui_text_scale(c);
        if (axis == 0) return nui_p.text_width ? nui_p.text_width(t, sc) : nui_len(t) * 6 * sc;
        int lines = 1;
        for (const char *q = t; *q; q++) if (*q == '\n') lines++;
        return lines * nui_font_line * sc;
    }
    if (c->type == NT_IMAGE) {
        JV *uvs = cp(c, "uv_size");
        JV *a = nui_axis(uvs, axis);
        if (a && a->t == JN_NUM) return (float)a->n;
    }
    return parent;
}
static float nui_children_extent(NCtl *c, int axis);
static float nui_eval(NCtl *c, JV *v, int axis, float parent, float other, int kids_ok) {
    NSize s = nui_size_term(v);
    float r = s.px + s.pct * parent / 100 + (axis == 0 ? s.pct_y : s.pct_x) * other / 100;
    if (axis == 0 && s.pct_x) r += s.pct_x * parent / 100;
    if (axis == 1 && s.pct_y) r += s.pct_y * parent / 100;
    if (s.pct_c) r += s.pct_c * (kids_ok ? nui_children_extent(c, axis) : 0) / 100;
    if (s.def) r += nui_default(c, axis, parent);
    if (s.fill) r += parent;
    return r;
}
/* the control's own size along an axis inside a parent of size `parent` */
static float nui_measure(NCtl *c, int axis, float parent, float other) {
    JV *v = nui_axis(cp(c, "size"), axis);
    float r;
    if (!v) r = c->type == NT_LABEL ? nui_default(c, axis, parent) : parent;       /* no size: the parent's (labels: the text) */
    else r = nui_eval(c, v, axis, parent, other, 1);
    JV *mx = nui_axis(cp(c, "max_size"), axis), *mn = nui_axis(cp(c, "min_size"), axis);
    if (mx) { float m = nui_eval(c, mx, axis, parent, other, 1); if (r > m) r = m; }
    if (mn) { float m = nui_eval(c, mn, axis, parent, other, 1); if (r < m) r = m; }
    return r < 0 ? 0 : r;
}
static int nui_stack_axis(NCtl *c) {
    const char *o = cps(c, "orientation");
    return o && nui_eq(o, "horizontal") ? 0 : 1;
}
/* children's size, for "%c": children measured without the parent (their "%" terms against 0) */
static float nui_children_extent(NCtl *c, int axis) {
    float sum = 0, mx = 0;
    int stack = c->type == NT_STACK && nui_stack_axis(c) == axis;
    for (int i = 0; i < c->nk; i++) {
        NCtl *k = c->kids[i];
        JV *vis = cp(k, "visible");
        if (vis && !jtruthy(vis)) continue;
        JV *v = nui_axis(cp(k, "size"), axis);
        float s;
        NSize t = nui_size_term(v);
        if (t.fill || (t.pct && !t.pct_c && !t.def && !t.px && !stack)) s = 0;
        else s = v ? nui_eval(k, v, axis, 0, 0, 1) : k->type == NT_LABEL ? nui_default(k, axis, 0) : 0;
        JV *off = nui_axis(cp(k, "offset"), axis);
        float o = off ? nui_eval(k, off, axis, 0, 0, 0) : 0;
        if (stack) sum += s;
        else if (s + o > mx) mx = s + o;
    }
    return stack ? sum : mx;
}
static void nui_anchor(const char *a, float *fx, float *fy) {
    *fx = 0.5f; *fy = 0.5f;
    if (!a) return;
    if (nui_starts(a, "top")) *fy = 0; else if (nui_starts(a, "bottom")) *fy = 1;
    int n = nui_len(a);
    if (n >= 4 && nui_eq(a + n - 4, "left")) *fx = 0;
    else if (n >= 5 && nui_eq(a + n - 5, "right")) *fx = 1;
}
static int nui_visible(NCtl *c) {
    JV *v = cp(c, "visible");
    return !v || jtruthy(v);
}
static void nui_layout(NCtl *c, float px, float py, float pw, float ph);
static void nui_layout_kids(NCtl *c) {
    if (c->type == NT_STACK) {
        int ax = nui_stack_axis(c);
        float total = ax ? c->h : c->w, used = 0;
        int fills = 0;
        float *sz = nui_alloc((c->nk + 1) * sizeof(float));
        for (int i = 0; i < c->nk; i++) {
            NCtl *k = c->kids[i];
            if (!nui_visible(k)) continue;
            JV *v = nui_axis(cp(k, "size"), ax);
            NSize t = nui_size_term(v);
            if (t.fill) { fills++; continue; }
            sz[i] = nui_measure(k, ax, total, ax ? c->w : c->h);
            used += sz[i];
        }
        float rest = total - used;
        if (rest < 0) rest = 0;
        float cur = ax ? c->y : c->x;
        for (int i = 0; i < c->nk; i++) {
            NCtl *k = c->kids[i];
            if (!nui_visible(k)) continue;
            JV *v = nui_axis(cp(k, "size"), ax);
            NSize t = nui_size_term(v);
            float s = t.fill ? rest / fills + t.px : sz[i];
            /* the cross axis anchors as usual inside the stack; the stack axis takes the next free place */
            float cw = ax ? c->w : s, ch = ax ? s : c->h;
            float cx = ax ? c->x : cur, cy = ax ? cur : c->y;
            nui_layout(k, cx, cy, cw, ch);
            if (ax) { k->y = cur; k->h = s; } else { k->x = cur; k->w = s; }
            if (k->nk) nui_layout_kids(k);
            cur += s;
        }
        nui_free(sz);
        return;
    }
    for (int i = 0; i < c->nk; i++) {
        NCtl *k = c->kids[i];
        nui_layout(k, c->x, c->y, c->w, c->h);
        if (k->nk) nui_layout_kids(k);
    }
}
static void nui_layout(NCtl *c, float px, float py, float pw, float ph) {
    float w = nui_measure(c, 0, pw, ph);
    float h = nui_measure(c, 1, ph, w);
    JV *sv = cp(c, "size");
    if (sv && sv->t == JN_ARR && sv->count > 0) {        /* "N%y" in the width needs the height */
        NSize t = nui_size_term(sv->v[0]);
        if (t.pct_y) w = nui_eval(c, sv->v[0], 0, pw, h, 1);
    }
    float fx, fy, tx, ty;
    nui_anchor(cps(c, "anchor_from"), &fx, &fy);
    nui_anchor(cps(c, "anchor_to"), &tx, &ty);
    JV *off = cp(c, "offset");
    float ox = 0, oy = 0;
    if (off) {
        JV *a = nui_axis(off, 0), *b = nui_axis(off, 1);
        if (a) ox = nui_eval(c, a, 0, pw, ph, 0);
        if (b) oy = nui_eval(c, b, 1, ph, pw, 0);
    }
    c->w = w; c->h = h;
    c->x = px + pw * fx - w * tx + ox;
    c->y = py + ph * fy - h * ty + oy;
}

/* ------------------------------------------------------------------------------------------------ bindings ---- */
/* "bindings": [{"binding_name": "#x", "binding_name_override": "#visible"}] -> properties of the control */
static void nui_bind(NCtl *c) {
    JV *b = cp(c, "bindings");
    for (int i = 0; b && b->t == JN_ARR && i < b->count; i++) {
        JV *e = b->v[i];
        JV *type = jget(e, "binding_type");
        if (type && type->t == JN_STR && nui_eq(type->s, "view")) {
            JV *src = jget(e, "source_property_name"), *dst = jget(e, "target_property_name");
            if (src && dst && dst->t == JN_STR && src->t == JN_STR) {
                NExpr x = {src->s, &c->sc, c};
                JV *v = src->s[0] == '(' ? ex_or(&x) : nui_binding(c, src->s);
                if (v) cp_set(c, dst->s + (dst->s[0] == '#'), v);
            }
            continue;
        }
        JV *nm = nui_resolve(jget(e, "binding_name"), &c->sc, c, 0);
        if (!nm || nm->t != JN_STR) continue;
        JV *v = nui_binding(c, nm->s);
        if (!v) continue;
        JV *ov = nui_resolve(jget(e, "binding_name_override"), &c->sc, c, 0);
        const char *prop = ov && ov->t == JN_STR ? ov->s : nm->s;
        cp_set(c, prop + (prop[0] == '#'), v);
    }
    for (int i = 0; i < c->nk; i++) nui_bind(c->kids[i]);
}

/* ------------------------------------------------------------------------------------------------- drawing ---- */
typedef struct { NCtl *c; int z; int order; float alpha; } NDraw;
static NDraw *nui_dl;
static int nui_dn, nui_dcap;
static const char *nui_label_text(NCtl *c) {
    JV *t = cp(c, "text");
    static char num[32];
    if (!t) return "";
    if (t->t == JN_NUM) { char *s = jv_text(t); int n = nui_len(s); if (n > 31) n = 31; memcpy(num, s, n); num[n] = 0; nui_free(s); return num; }
    if (t->t != JN_STR) return "";
    const char *s = t->s;
    if (s[0] == '#') { JV *b = nui_binding(c, s); if (b && b->t == JN_STR) return b->s; return ""; }
    const char *l = nui_p.loc ? nui_p.loc(s) : 0;
    return l ? l : s;
}
/* buttons and toggles show one of their state controls */
static int nui_state_hidden(NCtl *c) {
    NCtl *b = c->parent;
    if (!b || (b->type != NT_BUTTON && b->type != NT_TOGGLE)) return 0;
    if (b->type == NT_BUTTON) {
        static const char *keys[4] = {"default_control", "hover_control", "pressed_control", "locked_control"};
        int is_state = 0, mine = 0;
        for (int i = 0; i < 4; i++) {
            const char *n = cps(b, keys[i]);
            if (n && n[0] && nui_eq(n, c->name)) { is_state = 1; if (i == (b->state & 3)) mine = 1; }
        }
        return is_state && !mine;
    }
    /* a toggle: its visuals child holds unchecked / checked / *_hover / *_locked */
    return 0;
}
static int nui_toggle_hidden(NCtl *c) {
    NCtl *v = c->parent, *t = v ? v->parent : 0;
    if (!t || t->type != NT_TOGGLE) return 0;
    static const char *names[8] = {"unchecked", "checked", "unchecked_hover", "checked_hover", "unchecked_locked",
                                   "checked_locked", "unchecked_locked_hover", "checked_locked_hover"};
    int which = -1;
    for (int i = 0; i < 8; i++) if (nui_eq(c->name, names[i])) which = i;
    if (which < 0) return 0;
    int checked = (t->state & 4) != 0, hover = (t->state & 3) == 1, locked = (t->state & 3) == 3;
    int want = locked ? (hover ? 6 : 4) : hover ? 2 : 0;
    return which != want + checked;
}
static void nui_collect(NCtl *c, int z, float alpha) {
    if (!nui_visible(c) || nui_state_hidden(c) || nui_toggle_hidden(c)) return;
    float a = (float)cpn(c, "alpha", 1);
    JV *pa = cp(c, "propagate_alpha");
    z += c->layer;
    if (c->type == NT_IMAGE || c->type == NT_LABEL || c->type == NT_CUSTOM) {
        if (nui_dn == nui_dcap) {
            int cap = nui_dcap ? nui_dcap * 2 : 256;
            NDraw *n = nui_alloc_keep(cap * sizeof(NDraw));
            if (nui_dn) memcpy(n, nui_dl, nui_dn * sizeof(NDraw));
            nui_free(nui_dl); nui_dl = n; nui_dcap = cap;
        }
        nui_dl[nui_dn].c = c; nui_dl[nui_dn].z = z; nui_dl[nui_dn].order = nui_dn; nui_dl[nui_dn].alpha = alpha * a;
        nui_dn++;
    }
    for (int i = 0; i < c->nk; i++) nui_collect(c->kids[i], z, pa && jtruthy(pa) ? alpha * a : alpha);
}
static void nui_color(NCtl *c, float *rgba, float alpha) {
    rgba[0] = rgba[1] = rgba[2] = 1; rgba[3] = alpha;
    JV *col = cp(c, "color");
    if (col && col->t == JN_ARR)
        for (int i = 0; i < col->count && i < 4; i++) if (col->v[i]->t == JN_NUM) rgba[i] = (float)col->v[i]->n * (i == 3 ? alpha : 1);
}
static void nui_draw_image(NCtl *c, float alpha) {
    const char *tex = cps(c, "texture");
    if (!tex || !tex[0] || !nui_p.texture || !nui_p.image) return;
    int tw = 0, th = 0, id = nui_p.texture(tex, &tw, &th);
    if (!id || tw <= 0 || th <= 0) return;
    float u = 0, v = 0, us = (float)tw, vs = (float)th;
    JV *uv = cp(c, "uv"), *uvs = cp(c, "uv_size");
    if (uv && uv->t == JN_ARR && uv->count == 2) { u = (float)uv->v[0]->n; v = (float)uv->v[1]->n; }
    if (uvs && uvs->t == JN_ARR && uvs->count == 2) { us = (float)uvs->v[0]->n; vs = (float)uvs->v[1]->n; }
    float rgba[4];
    nui_color(c, rgba, alpha);
    float x = c->x, y = c->y, w = c->w, h = c->h;
    JV *keep = cp(c, "keep_ratio");
    if (keep && jtruthy(keep) && us > 0 && vs > 0) {
        float r = us / vs;
        if (w / h > r) { float nw = h * r; x += (w - nw) / 2; w = nw; } else { float nh = w / r; y += (h - nh) / 2; h = nh; }
    }
    JV *clip = cp(c, "clip_ratio");                         /* progress bars */
    if (clip && clip->t == JN_NUM) {
        const char *dir = cps(c, "clip_direction");
        float r = (float)clip->n;
        if (r < 0) r = 0;
        if (r > 1) r = 1;
        if (!dir || nui_eq(dir, "left")) { w *= (1 - r); us *= (1 - r); }
        else if (nui_eq(dir, "right")) { x += w * r; u += us * r; w *= 1 - r; us *= 1 - r; }
        else if (nui_eq(dir, "up")) { h *= 1 - r; vs *= 1 - r; }
        else if (nui_eq(dir, "down")) { y += h * r; v += vs * r; h *= 1 - r; vs *= 1 - r; }
    }
    JV *nine = cp(c, "nineslice_size");
    float n4[4] = {0, 0, 0, 0};
    int has9 = 0;
    if (nine && nine->t == JN_NUM && nine->n > 0) { n4[0] = n4[1] = n4[2] = n4[3] = (float)nine->n; has9 = 1; }
    else if (nine && nine->t == JN_ARR && nine->count == 4) { for (int i = 0; i < 4; i++) n4[i] = (float)nine->v[i]->n; has9 = 1; }
    JV *tiled = cp(c, "tiled");
    if (has9) {
        /* corners keep their size (1 texture pixel = 1 gui unit), edges and middle stretch */
        float L = n4[0], T = n4[1], R = n4[2], B = n4[3];
        if (L + R > w) { float k = w / (L + R); L *= k; R *= k; }
        if (T + B > h) { float k = h / (T + B); T *= k; B *= k; }
        float xs[4] = {x, x + L, x + w - R, x + w}, ys[4] = {y, y + T, y + h - B, y + h};
        float uu[4] = {u, u + n4[0], u + us - n4[2], u + us}, vv[4] = {v, v + n4[1], v + vs - n4[3], v + vs};
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++)
            if (xs[i + 1] > xs[i] && ys[j + 1] > ys[j])
                nui_p.image(id, xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j],
                            uu[i] / tw, vv[j] / th, uu[i + 1] / tw, vv[j + 1] / th, rgba);
        return;
    }
    if (tiled && jtruthy(tiled) && us > 0 && vs > 0) {
        for (float yy = y; yy < y + h; yy += vs)
            for (float xx = x; xx < x + w; xx += us) {
                float cw = x + w - xx < us ? x + w - xx : us, ch = y + h - yy < vs ? y + h - yy : vs;
                nui_p.image(id, xx, yy, cw, ch, u / tw, v / th, (u + cw) / tw, (v + ch) / th, rgba);
            }
        return;
    }
    nui_p.image(id, x, y, w, h, u / tw, v / th, (u + us) / tw, (v + vs) / th, rgba);
}
static void nui_draw_label(NCtl *c, float alpha) {
    const char *t = nui_label_text(c);
    if (!t[0] || !nui_p.text) return;
    float rgba[4], sc = nui_text_scale(c);
    nui_color(c, rgba, alpha);
    JV *sh = cp(c, "shadow");
    const char *al = cps(c, "text_alignment");
    /* line by line, each aligned in the control */
    const char *s = t;
    float y = c->y;
    char line[512];
    while (*s) {
        int n = 0;
        while (s[n] && s[n] != '\n' && n < 511) n++;
        memcpy(line, s, n); line[n] = 0;
        float lw = nui_p.text_width ? nui_p.text_width(line, sc) : n * 6 * sc, x = c->x;
        if (al && nui_eq(al, "center")) x += (c->w - lw) / 2;
        else if (al && nui_eq(al, "right")) x += c->w - lw;
        nui_p.text(line, x, y, sc, rgba, sh && jtruthy(sh));
        y += nui_font_line * sc;
        s += n;
        if (*s == '\n') s++;
    }
}
static int nui_dcmp(const NDraw *a, const NDraw *b) { return a->z != b->z ? a->z - b->z : a->order - b->order; }
static void nui_render(NCtl *root) {
    nui_dn = 0;
    nui_collect(root, 0, 1);
    /* insertion sort: stable, lists are short */
    for (int i = 1; i < nui_dn; i++) {
        NDraw d = nui_dl[i];
        int j = i - 1;
        while (j >= 0 && nui_dcmp(&nui_dl[j], &d) > 0) { nui_dl[j + 1] = nui_dl[j]; j--; }
        nui_dl[j + 1] = d;
    }
    for (int i = 0; i < nui_dn; i++) {
        NCtl *c = nui_dl[i].c;
        if (c->type == NT_IMAGE) nui_draw_image(c, nui_dl[i].alpha);
        else if (c->type == NT_LABEL) nui_draw_label(c, nui_dl[i].alpha);
        else if (c->type == NT_CUSTOM && nui_p.custom) nui_p.custom(cps(c, "renderer"), c->x, c->y, c->w, c->h, c);
    }
}

/* ---------------------------------------------------------------------------------------------------- input ---- */
static NCtl *nui_hit(NCtl *c, float x, float y, int z, int *best_z, NCtl **best) {
    if (!nui_visible(c) || nui_state_hidden(c)) return *best;
    z += c->layer;
    if ((c->type == NT_BUTTON || c->type == NT_TOGGLE) && x >= c->x && x < c->x + c->w && y >= c->y && y < c->y + c->h) {
        JV *en = cp(c, "enabled");
        if ((!en || jtruthy(en)) && z >= *best_z) { *best_z = z; *best = c; }
    }
    for (int i = 0; i < c->nk; i++) nui_hit(c->kids[i], x, y, z, best_z, best);
    return *best;
}
static NCtl *nui_pressed;
static void nui_clear_hover(NCtl *c) {
    if (c->type == NT_BUTTON || c->type == NT_TOGGLE) c->state &= ~3;
    for (int i = 0; i < c->nk; i++) nui_clear_hover(c->kids[i]);
}
/* down: 1 press, 0 release, 2 move; returns 1 when a control took it */
static int nui_touch(NCtl *root, float x, float y, int down) {
    int bz = -100000;
    NCtl *b = 0;
    nui_hit(root, x, y, 0, &bz, &b);
    if (down == 1) {
        nui_clear_hover(root);
        nui_pressed = b;
        if (b) b->state = (b->state & ~3) | 2;
        return b != 0;
    }
    if (down == 2) {
        if (nui_pressed) nui_pressed->state = (nui_pressed->state & ~3) | (b == nui_pressed ? 2 : 0);
        return nui_pressed != 0;
    }
    NCtl *p = nui_pressed;
    nui_pressed = 0;
    if (!p) return 0;
    p->state &= ~3;
    if (b != p) return 1;
    if (p->type == NT_TOGGLE) {
        const char *tn = cps(p, "toggle_name");
        int on = !(p->state & 4);
        p->state = (p->state & ~4) | (on ? 4 : 0);
        if (nui_ctl && nui_ctl->toggle && tn) nui_ctl->toggle(nui_ctl->self, tn, on);
        return 1;
    }
    /* button_mappings: button.menu_select -> the button's own name */
    const char *name = 0;
    JV *maps = cp(p, "button_mappings");
    for (int i = 0; maps && maps->t == JN_ARR && i < maps->count; i++) {
        JV *to = nui_resolve(jget(maps->v[i], "to_button_id"), &p->sc, p, 0);
        JV *ty = jget(maps->v[i], "mapping_type");
        if (to && to->t == JN_STR && to->s[0] && (!ty || (ty->t == JN_STR && nui_eq(ty->s, "pressed")))) { name = to->s; break; }
    }
    if (!name) { JV *v = scope_get(&p->sc, "$pressed_button_name"); if (v && v->t == JN_STR) name = v->s; }
    if (name && nui_ctl && nui_ctl->button) nui_ctl->button(nui_ctl->self, name, p);
    return 1;
}

/* globals of this client: what the 1.1.5 screens ask about the platform */
static void nui_set_global(const char *name, JV *v) { scope_set(&nui_globals, (char *)name, v); }
static void nui_init_globals(void) {
    static const char *on[] = {"$pocket_edition", "$touch", "$is_pregame", "$is_publish", 0};
    static const char *off[] = {"$education_edition", "$is_holographic", "$gear_vr", "$fire_tv", "$apple_tv", "$trial",
        "$win10_edition", "$is_living_room_mode", "$is_mobile_vr", "$desktop_screen", "$pre_release", "$is_reality_mode",
        "$education_ios", "$is_xboxlive_enabled", "$osx_edition", "$console_edition", "$is_mashup", "$build_platform_UWP",
        "$is_ios", 0};
    for (int i = 0; on[i]; i++) nui_set_global(on[i], &nui_jtrue);
    for (int i = 0; off[i]; i++) nui_set_global(off[i], &nui_jfalse);
    JV *full = nui_parse("[\"100%\", \"100%\"]", 16);
    nui_set_global("$safezone_size", full);
}
