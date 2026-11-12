/* recipes.inc - crafting and furnace recipes of mods (recipe.shaped / recipe.smelt).
 *
 * The client's recipe book is cleared and refilled from the server's CraftingDataPacket
 * (ClientNetworkHandler::handle -> Recipes::clearRecipes) after the world is entered, so recipes a script added on
 * world.join were gone. Recipes of mods live here and are put back (as Recipes::_addFenceRecipes does it:
 * vector<Recipes::Type> + Recipes::addShapedRecipe) whenever that packet has been handled, and when a mod adds them.
 * Recipes::Type: +0 Item*, +4 Block*, +8 ItemInstance, +0x1c letter (32 bytes). */
#define RC_MAX 512
typedef struct { int kind, out, n, aux, gen; char rows[3][4]; int nk; char key[9]; short kid[9], kaux[9]; } MRecipe;
static MRecipe rc_list[RC_MAX];
static int rc_count;
static void *(*rc_book)(void);
static void *(*rc_furnace)(void);
static void (*rc_add)(void *book, void *out, void *r0, void *r1, void *r2, void *vec);
static void (*rc_add1)(void *book, void *out, void *r0, void *vec);
static void (*rc_add2)(void *book, void *out, void *r0, void *r1, void *vec);
static void (*rc_add_furnace)(void *fr, short id, short aux, void *out);
static int rc_gen = 1;                                 /* +1 each time the book was refilled from the server */

static u32 *(*rc_list_of)(void *book);              /* Recipes::getRecipes() -> vector<Recipe*> */
static int rc_count_book(void *book) {
    u32 *v = rc_list_of && book ? rc_list_of(book) : 0;
    return v ? (int)((v[1] - v[0]) / 4) : -1;
}
static void rc_apply(MRecipe *r) {
    if (!sf_item_ctor || !sf_items) return;
    if (r->gen == rc_gen) return;                       /* in the book already */
    if (r->out <= 0 || r->out >= 512 || !sf_items[r->out]) return;
    char out[24];
    memset(out, 0, sizeof out);
    sf_item_ctor(out, r->out, r->n, r->aux);
    if (r->kind == 1) {
        void *fr = rc_furnace ? rc_furnace() : 0;
        if (fr && rc_add_furnace && r->nk == 1) { r->gen = rc_gen; rc_add_furnace(fr, r->kid[0], r->kaux[0], out); }
        return;
    }
    void *book = rc_book ? rc_book() : 0;
    if (!book || !rc_add || r->nk <= 0) return;
    for (int i = 0; i < r->nk; i++) if (r->kid[i] <= 0 || r->kid[i] >= 512 || !sf_items[r->kid[i]]) return;
    r->gen = rc_gen;
    char *types = f_new(r->nk * 32);
    memset(types, 0, r->nk * 32);
    for (int i = 0; i < r->nk; i++) {
        sf_item_ctor(types + i * 32 + 8, r->kid[i], 1, r->kaux[i]);
        types[i * 32 + 0x1c] = r->key[i];
    }
    u32 vec[3] = {(u32)types, (u32)(types + r->nk * 32), (u32)(types + r->nk * 32)};
    /* as many rows as given, all as wide as the widest (spaces = empty cells) */
    int nr = r->rows[2][0] ? 3 : r->rows[1][0] ? 2 : 1, wd = 1;
    char rows[3][4];
    for (int i = 0; i < 3; i++) { int l = 0; while (r->rows[i][l]) l++; if (l > wd) wd = l; }
    for (int i = 0; i < 3; i++) {
        int l = 0;
        while (r->rows[i][l] && l < 3) { rows[i][l] = r->rows[i][l]; l++; }
        while (l < wd) rows[i][l++] = ' ';
        rows[i][wd] = 0;
    }
    u32 s0[1], s1[1], s2[1];
    gstr(s0, rows[0]); gstr(s1, rows[1]); gstr(s2, rows[2]);
    int before = rc_count_book(book);
    if (crash_path[0] && sigsetjmp(nova_guard_jb, 1)) {   /* the game crashed inside: skip this recipe, keep playing */
        LOGE("recipe: %d x%d:%d crashed the game's Recipes (pc rel 0x%x), skipped", r->out, r->n, r->aux, nova_guard_pc);
        return;
    }
    nova_guard = crash_path[0] != 0;                       /* only once the crash handler is installed */
    if (nr == 1 && rc_add1) rc_add1(book, out, s0, vec);
    else if (nr == 2 && rc_add2) rc_add2(book, out, s0, s1, vec);
    else rc_add(book, out, s0, s1, s2, vec);
    nova_guard = 0;
    f_strdtor(s0); f_strdtor(s1); f_strdtor(s2);
    LOG("recipe: %d x%d:%d rows %d [%s|%s|%s] keys %d (%c %d:%d) book %d -> %d", r->out, r->n, r->aux, nr,
        rows[0], rows[1], rows[2], r->nk, r->key[0], r->kid[0], r->kaux[0], before, rc_count_book(book));
}

static void rc_apply_all(void) {
    for (int i = 0; i < rc_count; i++) rc_apply(&rc_list[i]);
}

/* Recipes are never added from inside a script call or a packet handler: rc_op and the CraftingDataPacket hook only
 * mark them, and the client loop adds them on a later frame (mcmod_frame -> rc_frame), when the game is not walking
 * its recipe book (world start, CraftingDataPacket refill, the crafting screen's list). alpha33 crashed while adding
 * them from world.join; this keeps the game's own work and ours apart. */
static volatile int rc_dirty;
static long rc_dirty_at;
static void rc_mark(void) { if (!rc_dirty) rc_dirty_at = now_ms(); rc_dirty = 1; }
static void rc_frame(int in_world) {
    if (!rc_dirty || !in_world) return;
    if (now_ms() - rc_dirty_at < 400) return;             /* let the world start / the packet settle first */
    rc_dirty = 0;
    int before = rc_count_book(rc_book ? rc_book() : 0);
    rc_apply_all();
    LOG("recipes: mods' recipes added on the client loop (%d known), book %d -> %d", rc_count, before,
        rc_count_book(rc_book ? rc_book() : 0));
}

/* OP_RECIPE: a = [out, count, aux], s = "row0|row1|row2|K:id:aux|..."; OP_SMELT: a = [from, fromAux, to, count, toAux] */
static int rc_num(char **pp) {
    char *p = *pp; int neg = 0, v = 0;
    if (*p == '-') { neg = 1; p++; }
    while (*p >= '0' && *p <= '9') v = v * 10 + (*p++ - '0');
    *pp = p;
    return neg ? -v : v;
}
static int rc_op(int op, double *a, int n, char *s) {
    if (rc_count >= RC_MAX) return 0;
    MRecipe *r = &rc_list[rc_count];
    memset(r, 0, sizeof *r);
    if (op == OP_SMELT) {
        if (n < 5) return 0;
        r->kind = 1; r->nk = 1; r->kid[0] = (short)a[0]; r->kaux[0] = (short)a[1];
        r->out = (int)a[2]; r->n = (int)a[3]; r->aux = (int)a[4];
    } else {
        if (n < 3) return 0;
        r->out = (int)a[0]; r->n = (int)a[1]; r->aux = (int)a[2];
        char *p = s;
        for (int row = 0; row < 3; row++) {
            int k = 0;
            while (*p && *p != '|') { if (k < 3) r->rows[row][k++] = *p; p++; }
            r->rows[row][k] = 0;
            if (*p == '|') p++;
        }
        while (*p && r->nk < 9) {
            r->key[r->nk] = *p++;
            if (*p == ':') p++;
            r->kid[r->nk] = (short)rc_num(&p);
            if (*p == ':') { p++; r->kaux[r->nk] = (short)rc_num(&p); }
            r->nk++;
            while (*p && *p != '|') p++;
            if (*p == '|') p++;
        }
    }
    /* the same recipe twice (a mod reloaded, world entered again): keep one */
    for (int i = 0; i < rc_count; i++) {
        MRecipe *o = &rc_list[i];
        if (o->kind == r->kind && o->out == r->out && o->aux == r->aux && o->n == r->n &&
            !memcmp(o->rows, r->rows, sizeof r->rows) && o->nk == r->nk && !memcmp(o->kid, r->kid, sizeof r->kid) &&
            !memcmp(o->kaux, r->kaux, sizeof r->kaux) && !memcmp(o->key, r->key, sizeof r->key)) {
            rc_mark();
            return 0;
        }
    }
    rc_count++;
    rc_mark();
    return 0;
}

static u64 hc_crafting_data(u32 orig, u32 *w) {   /* ClientNetworkHandler::handle(this, guid, CraftingDataPacket*) */
    u64 ret = nh_callw(orig, w);
    rc_gen++;                                            /* the book was cleared and refilled */
    LOG("recipes: book refilled by the server (%d), mods' recipes come back next: %d", rc_count_book(rc_book ? rc_book() : 0), rc_count);
    rc_mark();
    return ret;
}

static void recipes_init(void *mc) {
    rc_book = dlsym(mc, "_ZN7Recipes11getInstanceEv");
    rc_furnace = dlsym(mc, "_ZN14FurnaceRecipes11getInstanceEv");
    rc_add = dlsym(mc, "_ZN7Recipes15addShapedRecipeERK12ItemInstanceRKSsS4_S4_RKSt6vectorINS_4TypeESaIS6_EE");
    rc_list_of = dlsym(mc, "_ZN7Recipes10getRecipesEv");
    rc_add1 = dlsym(mc, "_ZN7Recipes15addShapedRecipeERK12ItemInstanceRKSsRKSt6vectorINS_4TypeESaIS6_EE");
    rc_add2 = dlsym(mc, "_ZN7Recipes15addShapedRecipeERK12ItemInstanceRKSsS4_RKSt6vectorINS_4TypeESaIS6_EE");
    rc_add_furnace = dlsym(mc, "_ZN14FurnaceRecipes23addFurnaceRecipeAuxDataEssRK12ItemInstance");
    void *h = dlsym(mc, "_ZN20ClientNetworkHandler6handleERKN6RakNet10RakNetGUIDEP18CraftingDataPacket");
    LOG("recipes: %d", h && rc_book && rc_add ? nh_hook_c((u32)h, hc_crafting_data) : -1);
}
