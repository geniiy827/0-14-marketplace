/* ---------------- mod items ----------------
 * nativeDefineItems(double[] data, String names, String actions): per item
 *   id, u0 v0 u1 v1 (item atlas 0..1), handScale, tool, stack, durability, category
 * Registered in the initCreativeItems gate after the blocks: new(0x40), Item::Item(name, id - 256),
 * icon = own TextureUVCoordinateSet (atlas pointer copied from the apple's icon, Item+0x30),
 * setHandEquipped (tools), setMaxStackSize, setMaxDamage, setCategory, Item::mItems[id], creative list.
 * Hand scale ("pixel" textures bigger than 16): ItemInHandRenderer::renderItem marks the item,
 * _renderObject scales MatrixStack::World around the draw. */
#define MI_MAX 64
#define MI_REC 10
typedef struct {
    int id, tool, stack, dur, cat;
    float hand;
    TexSet tex;
    char name[64], action[64];
    void *item;
} ModItem;
static ModItem mitems[MI_MAX];
static int mitem_count, mitems_done, mitem_registered;
static ModItem *mitem_of[512];

static void (*mi_ctor)(void *it, void *name, short id);
static void (*mi_set_icon)(void *it, TexSet *t);
static void (*mi_hand_eq)(void *it);
static void (*mi_stack)(void *it, unsigned char n);
static void (*mi_damage)(void *it, int n);
static void *mi_world_stack;
static float *(*mi_get_top)(void *stack);

int Java_com_nova_client_ModBridge_nativeDefineItems(void **env, void *cls, void *darr, void *jnames, void *jact) {
    void **f = *(void ***)env;
    if (!darr || mitems_done) return -1;
    int n = ((int (*)(void *, void *))f[171])(env, darr);
    double *d = f_new(n * 8 + 8);
    ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, d);
    static char names[4096], acts[4096];
    jstr_copy(env, jnames, names, sizeof names);
    jstr_copy(env, jact, acts, sizeof acts);
    char *np = names, *ap = acts;
    mitem_count = 0;
    for (int p = 0; p + MI_REC <= n && mitem_count < MI_MAX; p += MI_REC) {
        ModItem *m = &mitems[mitem_count];
        m->id = (int)d[p];
        for (int k = 0; k < 4; k++) m->tex.b[k] = (float)d[p + 1 + k];
        m->hand = (float)d[p + 5]; m->tool = (int)d[p + 6]; m->stack = (int)d[p + 7];
        m->dur = (int)d[p + 8]; m->cat = (int)d[p + 9];
        int k = 0;
        while (*np && *np != '\x1f' && k < 63) m->name[k++] = *np++;
        m->name[k] = 0;
        if (*np == '\x1f') np++;
        k = 0;
        while (*ap && *ap != '\x1f' && k < 63) m->action[k++] = *ap++;
        m->action[k] = 0;
        if (*ap == '\x1f') ap++;
        if (m->id >= 256 && m->id < 512) mitem_count++;
    }
    LOG("mcmod: %d item definition(s)", mitem_count);
    return mitem_count;
}

static void mcmod_register_items(void) {
    if (mitems_done || !mitem_count) return;
    mitems_done = 1;
    if (!mf_items || !mi_ctor || !mi_set_icon) { LOGE("mcmod: item registration unavailable"); return; }
    TexSet *ref = mf_items[260] ? *(TexSet **)((char *)mf_items[260] + 0x30) : 0;   /* apple's icon: atlas pointer */
    int done = 0;
    for (int i = 0; i < mitem_count; i++) {
        ModItem *m = &mitems[i];
        if (mf_items[m->id]) { LOGE("mcmod: item id %d is taken, %s skipped", m->id, m->name); continue; }
        if (ref) { m->tex.size[0] = ref->size[0]; m->tex.size[1] = ref->size[1]; m->tex.filler = ref->filler; m->tex.file = ref->file; }
        char *it = f_new(0x40);
        for (int k = 0; k < 0x40; k++) it[k] = 0;
        u32 nm[1];
        gstr(nm, m->name);
        mi_ctor(it, nm, (short)(m->id - 256));
        f_strdtor(nm);
        mi_set_icon(it, &m->tex);
        if (m->tool && mi_hand_eq) mi_hand_eq(it);
        if (mi_stack) mi_stack(it, (unsigned char)m->stack);
        if (m->dur > 0 && mi_damage) mi_damage(it, m->dur);
        if (mf_item_setcat) mf_item_setcat(it, m->cat);
        mf_items[m->id] = it;
        m->item = it;
        mitem_of[m->id] = m;
        if (mf_add_creative) mf_add_creative((short)m->id, 0);
        done++;
    }
    mitem_registered = done;
    LOG("mcmod: %d item(s) registered", done);
}

/* the held item's own action button (item property "action") */
static ModItem *mi_held(void) {
    char *pl = mm_player();
    char *ii = pl && ef_selected ? ef_selected(pl) : 0;
    int id = ev_item_id(ii);
    return id > 0 && id < 512 ? mitem_of[id] : 0;
}
static const char *mi_action_text(void) {
    ModItem *m = mitem_count ? mi_held() : 0;
    return m && m->action[0] ? m->action : 0;
}

/* hand scale */
static float mi_cur_scale = 1.f;
static void (*o_render_item)(void *r, void *e, char *ii, float a);
static void h_render_item(void *r, void *e, char *ii, float a) {
    int id = ev_item_id(ii);
    ModItem *m = id > 0 && id < 512 ? mitem_of[id] : 0;
    float keep = mi_cur_scale;
    mi_cur_scale = m ? m->hand : 1.f;
    o_render_item(r, e, ii, a);
    mi_cur_scale = keep;
}
static void (*o_render_obj)(void *r, void *call, int b);
static void h_render_obj(void *r, void *call, int b) {
    float s = mi_cur_scale;
    if (s == 1.f || !mi_get_top || !mi_world_stack || !sf_mscale) { o_render_obj(r, call, b); return; }
    float *top = mi_get_top(mi_world_stack);
    SVec3 v = {s, s, s}, iv = {1.f / s, 1.f / s, 1.f / s};
    sf_mscale(top, &v);
    o_render_obj(r, call, b);
    sf_mscale(top, &iv);
}

static void items_init(void *mc, u32 base) {
    #define S(var, name) do { *(void **)&var = dlsym(mc, name); if (!var) LOGE("items: missing %s", name); } while (0)
    S(mi_ctor, "_ZN4ItemC2ERKSss");
    S(mi_set_icon, "_ZN4Item7setIconERK22TextureUVCoordinateSet");
    S(mi_hand_eq, "_ZN4Item15setHandEquippedEv");
    S(mi_stack, "_ZN4Item15setMaxStackSizeEh");
    S(mi_damage, "_ZN4Item12setMaxDamageEi");
    S(mi_world_stack, "_ZN11MatrixStack5WorldE");
    S(mi_get_top, "_ZN11MatrixStack6getTopEv");
    #undef S
    GATE(RENDER_ITEM, h_render_item, o_render_item);
    GATE(RENDER_OBJ, h_render_obj, o_render_obj);
}

/* on world join: say so when mod items could not be registered (no logcat on the phone) */
static void mi_report(void) {
    if (!mitem_count || mitem_registered == mitem_count || !g_client || !mf_gd_msg) return;
    void *gd = *(void **)((char *)g_client + 0x130);
    if (!gd) return;
    char msg[160];
    const char *a = "\xc2\xa7" "c[NOVA] mod items: ";
    int n = 0;
    while (a[n]) { msg[n] = a[n]; n++; }
    msg[n++] = '0' + (mitem_registered / 10) % 10; msg[n++] = '0' + mitem_registered % 10; msg[n++] = '/';
    msg[n++] = '0' + (mitem_count / 10) % 10; msg[n++] = '0' + mitem_count % 10;
    const char *why = mitems_done ? " (id taken / no ctor)" : " (initCreativeItems not reached)";
    while (*why && n < 150) msg[n++] = *why++;
    msg[n] = 0;
    u32 st[1];
    gstr(st, msg);
    mf_gd_msg(gd, st);
    f_strdtor(st);
}
