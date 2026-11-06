/* ---------------- mod items ----------------
 * nativeDefineItems(double[] data, String names, String actions): per item
 *   id, u0 v0 u1 v1 (item atlas 0..1), handScale, tool, stack, durability, category
 * Registered in the initCreativeItems gate after the blocks: new(0x40), Item::Item(name, id - 256),
 * icon = own TextureUVCoordinateSet (atlas pointer copied from the apple's icon, Item+0x30),
 * setHandEquipped (tools), setMaxStackSize, setMaxDamage, setCategory, Item::mItems[id], creative list.
 * Hand scale ("pixel" textures bigger than 16): ItemInHandRenderer::renderItem marks the item,
 * _renderObject scales MatrixStack::World around the draw. */
#define MI_MAX 96
#define MI_REC 19
/* kind: 0 plain, 1 pickaxe, 2 axe, 3 shovel, 4 sword, 5 hoe, 6 helmet, 7 chestplate, 8 leggings, 9 boots
 * tools: Item::Tier {level, uses, speed, damage, enchant} (DiggerItem keeps a pointer: allocated, never freed)
 * armor: ArmorItem::ArmorMaterial {durability multiplier, defense x4, enchant}, render index 5 + armor texture */
typedef struct { int level, uses; float speed; int damage, ench; } MTier;
typedef struct {
    int id, tool, stack, dur, cat;
    int kind, level, uses, damage, ench, def, arm, durmul;
    float speed;
    float hand;
    TexSet tex;
    char name[64], action[64];
    void *item;
} ModItem;
#define MV_MAX 256
typedef struct { int id, aux; TexSet tex; char name[64]; } MVar;
static MVar mvars[MV_MAX];
static int mvar_count;
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
    static char names[8192], acts[8192];
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
        m->kind = (int)d[p + 10]; m->level = (int)d[p + 11]; m->uses = (int)d[p + 12]; m->speed = (float)d[p + 13];
        m->damage = (int)d[p + 14]; m->ench = (int)d[p + 15]; m->def = (int)d[p + 16]; m->arm = (int)d[p + 17];
        m->durmul = (int)d[p + 18];
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

static int mv_has(int id);
static void mv_setup(char *it);
static void (*mi_digger_ctor[4])(void *it, void *name, int id, MTier *t);   /* pickaxe, axe, shovel, - */
static void (*mi_hoe_ctor)(void *it, void *name, int id, MTier t);
static void (*mi_armor_ctor)(void *it, void *name, int id, int *mat, int render, int slot);
static u32 *mi_weapon_vt, *mi_shears_vt;
static void (*mi_bow_ctor)(void *it, void *name, int id);
static void (*mi_bucket_ctor)(void *it, void *name, int id);
static void (*mi_door_ctor)(void *it, void *name, int id, void *mat, int type);
static MVar *mv_find_exact(int id, int aux) {
    for (int i = 0; i < mvar_count; i++) if (mvars[i].id == id && mvars[i].aux == aux) return &mvars[i];
    return 0;
}

static char *mi_make(ModItem *m) {
    u32 nm[1];
    gstr(nm, m->name);
    char *it = 0;
    int id = m->id - 256;
    MTier *t = 0;
    if (m->kind >= 1 && m->kind <= 5) {
        t = f_new(sizeof *t);
        t->level = m->level; t->uses = m->uses; t->speed = m->speed; t->damage = m->damage; t->ench = m->ench;
    }
    if (m->kind == 0) {
        it = f_new(0x40); memset(it, 0, 0x40);
        mi_ctor(it, nm, (short)id);
    } else if (m->kind >= 1 && m->kind <= 3 && mi_digger_ctor[m->kind - 1]) {
        it = f_new(0x158); memset(it, 0, 0x158);
        mi_digger_ctor[m->kind - 1](it, nm, id, t);
    } else if (m->kind == 4 && mi_weapon_vt) {              /* registerItem<WeaponItem> builds it inline */
        it = f_new(0x48); memset(it, 0, 0x48);
        mi_ctor(it, nm, (short)id);
        it[4] = 1;
        *(u32 *)it = (u32)(mi_weapon_vt + 2);
        *(MTier **)(it + 0x44) = t;
        if (mi_damage) mi_damage(it, t->uses);
        *(int *)(it + 0x1c) = 72000;                         /* use duration (block with the sword) */
        *(int *)(it + 0x40) = 4 + t->damage;
    } else if (m->kind == 5 && mi_hoe_ctor) {
        it = f_new(0x54); memset(it, 0, 0x54);
        mi_hoe_ctor(it, nm, id, *t);
    } else if (m->kind == 10 && mi_shears_vt) {              /* registerItem<ShearsItem>: Item + vtable, stack 1, uses */
        it = f_new(0x40); memset(it, 0, 0x40);
        mi_ctor(it, nm, (short)id);
        *(u32 *)it = (u32)(mi_shears_vt + 2);
        it[4] = 1;
        if (mi_damage) mi_damage(it, m->uses > 0 ? m->uses : 238);
    } else if (m->kind == 11 && mi_bow_ctor) {
        it = f_new(0x88); memset(it, 0, 0x88);
        mi_bow_ctor(it, nm, id);
        if (mi_damage && m->uses > 0) mi_damage(it, m->uses);
        for (int k = 0; k < 3; k++) {                         /* pulling pictures: +0x40, +0x58, +0x70 */
            MVar *v = mv_find_exact(m->id, 1000 + k);
            if (v) memcpy(it + 0x40 + 0x18 * k, &v->tex, 0x18);
        }
    } else if (m->kind == 12 && mi_bucket_ctor) {
        it = f_new(0xa0); memset(it, 0, 0xa0);
        mi_bucket_ctor(it, nm, id);
        static const int off[4] = {0x40, 0x88, 0x58, 0x70};   /* empty, water, lava, milk */
        for (int k = 0; k < 4; k++) {
            MVar *v = mv_find_exact(m->id, 1000 + k);
            if (v) memcpy(it + off[k], &v->tex, 0x18);
        }
    } else if (m->kind == 13 && mi_door_ctor) {               /* places the mod door block m->level */
        void *db = m->level > 0 && m->level < 256 ? mf_blocks[m->level] : 0;
        if (db) {
            it = f_new(0x48); memset(it, 0, 0x48);
            mi_door_ctor(it, nm, id, *(void **)((char *)db + 84), 0);
        }
    } else if (m->kind >= 6 && m->kind <= 9 && mi_armor_ctor) {
        int *mat = f_new(24);
        mat[0] = m->durmul > 0 ? m->durmul : 15;
        for (int k = 1; k <= 4; k++) mat[k] = m->def;
        mat[5] = m->ench;
        it = f_new(0x50); memset(it, 0, 0x50);
        mi_armor_ctor(it, nm, id, mat, 5 + (m->arm < 0 ? 0 : m->arm), m->kind - 6);
    }
    f_strdtor(nm);
    return it;
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
        if (ref) for (int v = 0; v < mvar_count; v++) if (mvars[v].id == m->id) {
            TexSet *t = &mvars[v].tex; t->size[0] = ref->size[0]; t->size[1] = ref->size[1]; t->filler = ref->filler; t->file = ref->file;
        }
        char *it = mi_make(m);
        if (!it) { LOGE("mcmod: item %s: kind %d unavailable", m->name, m->kind); continue; }
        mi_set_icon(it, &m->tex);
        if (m->kind == 0 && mv_has(m->id)) mv_setup(it);
        if (m->kind == 0) {
            if (m->tool && mi_hand_eq) mi_hand_eq(it);
            if (mi_stack) mi_stack(it, (unsigned char)m->stack);
            if (m->dur > 0 && mi_damage) mi_damage(it, m->dur);
        }
        if (mf_item_setcat) mf_item_setcat(it, m->cat);
        mf_items[m->id] = it;
        m->item = it;
        mitem_of[m->id] = m;
        if (mf_add_creative && !ct_has(m->id)) mf_add_creative((short)m->id, 0);
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
    S(mi_digger_ctor[0], "_ZN11PickaxeItemC1ERKSsiRKN4Item4TierE");
    S(mi_digger_ctor[1], "_ZN11HatchetItemC1ERKSsiRKN4Item4TierE");
    S(mi_digger_ctor[2], "_ZN10ShovelItemC1ERKSsiRKN4Item4TierE");
    S(mi_hoe_ctor, "_ZN7HoeItemC1ERKSsiN4Item4TierE");
    S(mi_armor_ctor, "_ZN9ArmorItemC1ERKSsiRKNS_13ArmorMaterialEi9ArmorSlot");
    S(mi_weapon_vt, "_ZTV10WeaponItem");
    S(mi_shears_vt, "_ZTV10ShearsItem");
    S(mi_bow_ctor, "_ZN7BowItemC1ERKSsi");
    S(mi_bucket_ctor, "_ZN10BucketItemC1ERKSsi");
    S(mi_door_ctor, "_ZN8DoorItemC1ERKSsiRK8MaterialN9DoorBlock8DoorTypeE");
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

/* ---- mod armor on players and mobs: HumanoidMobRenderer::prepareArmor picks this+0xd0 [render index * 2 (+1 for
 * leggings)] (TexturePtr, 16 bytes). Mod armor has render index 5 + k: for those calls this+0xd0 points so that the
 * index lands in our own array (layer 1 / layer 2 of mod armor k, from the mod: nativeArmorTextures). ---- */
#define MA_MAX 16
static char ma_path[MA_MAX * 2][96];
static int ma_count;
static u32 *ma_tex;                                    /* MA_MAX * 2 TexturePtr */
static int ma_ok[MA_MAX * 2];

int Java_com_nova_client_ModBridge_nativeArmorTextures(void **env, void *cls, void *jspec) {
    static char spec[4096];
    jstr_copy(env, jspec, spec, sizeof spec);
    char *p = spec;
    int n = 0;
    while (*p && n < MA_MAX * 2) {
        int k = 0;
        while (*p && *p != '|') { if (k < 95) ma_path[n][k++] = *p; p++; }
        ma_path[n][k] = 0;
        n++;
        if (*p == '|') p++;
    }
    ma_count = n / 2;
    return ma_count;
}

static u64 hc_prepare_armor(u32 orig, u32 *w) {     /* prepareArmor(this, Mob&, ArmorSlot, float) */
    char *self = (char *)w[0], *mob = (char *)w[1];
    int slot = (int)w[2];
    if (!ma_count || !mob || !g_client || !f_gettex || !f_textures) return nh_callw(orig, w);
    char *ii = ((char *(*)(void *, int))((*(void ***)mob)[184]))(mob, slot);   /* Mob::getArmor (vtable +0x2e0) */
    char *item = ii ? *(char **)(ii + 0xc) : 0;
    int iid = item ? ev_item_id(ii) : 0;
    int ri = iid >= 256 && iid < 512 && mitem_of[iid] && mitem_of[iid]->kind >= 6 && mitem_of[iid]->kind <= 9 ? *(int *)(item + 0x48) : -1;
    if (ri < 5 || ri >= 5 + ma_count) return nh_callw(orig, w);
    int k = (ri - 5) * 2 + (slot == 2);
    if (!ma_tex) { ma_tex = f_new(MA_MAX * 2 * 16); memset(ma_tex, 0, MA_MAX * 2 * 16); }
    if (!ma_ok[k]) {
        u32 s[1];
        gstr(s, ma_path[k]);
        f_gettex(ma_tex + k * 4, f_textures(g_client), s, 0);
        f_strdtor(s);
        ma_ok[k] = 1;
    }
    u32 keep = *(u32 *)(self + 0xd0);
    *(u32 *)(self + 0xd0) = (u32)ma_tex - 5 * 2 * 16;      /* [ri * 2 (+1)] -> ma_tex[(ri - 5) * 2 (+1)] */
    u64 r = nh_callw(orig, w);
    *(u32 *)(self + 0xd0) = keep;
    return r;
}

/* mod blocks that need a better pickaxe (block "harvest": 1 stone, 2 iron, 3 diamond):
 * PickaxeItem::canDestroySpecial(this, Block const*); the tier pointer is at +0x44 (level first) */
static u64 hc_pick_special(u32 orig, u32 *w) {
    char *b = (char *)w[1];
    ModBlock *m = b ? mblock_of[*(unsigned char *)(b + 4)] : 0;
    if (m && m->block == b && m->harvest > 0) {
        int *t = *(int **)((char *)w[0] + 0x44);
        return t && t[0] >= m->harvest;
    }
    return nh_callw(orig, w);
}

static void doors_init(void *mc);
static void armor_init(void *mc) {
    doors_init(mc);
    void *pa = dlsym(mc, "_ZN19HumanoidMobRenderer12prepareArmorER3Mob9ArmorSlotf");
    void *ps = dlsym(mc, "_ZNK11PickaxeItem17canDestroySpecialEPK5Block");
    LOG("armor: %d, harvest: %d", pa ? nh_hook_c((u32)pa, hc_prepare_armor) : -1, ps ? nh_hook_c((u32)ps, hc_pick_special) : -1);
}

/* ---- items by data (variants): one item number, a picture and a name per data value, like dyes.
 * Item::getIcon(aux, frame, inInventory) (slot 50) and Item::buildDescriptionName(ItemInstance const&) (slot 40)
 * of a vtable copy; stacked by data. ---- */
static u32 *mv_vtable;

int Java_com_nova_client_ModBridge_nativeItemVariants(void **env, void *cls, void *darr, void *jnames) {
    void **f = *(void ***)env;
    if (!darr || mitems_done) return -1;
    int n = ((int (*)(void *, void *))f[171])(env, darr);
    double *d = f_new(n * 8 + 8);
    ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, d);
    static char names[8192];
    jstr_copy(env, jnames, names, sizeof names);
    char *np = names;
    mvar_count = 0;
    for (int p = 0; p + 6 <= n && mvar_count < MV_MAX; p += 6) {
        MVar *v = &mvars[mvar_count++];
        v->id = (int)d[p]; v->aux = (int)d[p + 1];
        for (int k = 0; k < 4; k++) v->tex.b[k] = (float)d[p + 2 + k];
        int k = 0;
        while (*np && *np != '\x1f' && k < 63) v->name[k++] = *np++;
        v->name[k] = 0;
        if (*np == '\x1f') np++;
    }
    return mvar_count;
}

static MVar *mv_find(int id, int aux) {
    MVar *first = 0;
    for (int i = 0; i < mvar_count; i++)
        if (mvars[i].id == id) { if (mvars[i].aux == aux) return &mvars[i]; if (!first) first = &mvars[i]; }
    return first;
}
static void *mv_get_icon(char *it, int aux, int frame, int inv) {
    MVar *v = mv_find(*(unsigned short *)(it + 0x12), aux);
    return v ? (void *)&v->tex : *(void **)(it + 0x30);         /* Item+0x30: the item's own icon */
}
static void *mv_desc(void *out, char *it, char *ii) {
    MVar *v = mv_find(*(unsigned short *)(it + 0x12), ii ? *(short *)(ii + 2) : 0);
    gstr(out, v ? v->name : "?");
    return out;
}
static int mv_has(int id) { for (int i = 0; i < mvar_count; i++) if (mvars[i].id == id && mvars[i].aux < 1000) return 1; return 0; }

static void mv_setup(char *it) {
    if (!mv_vtable) {
        u32 *vp = *(u32 **)it;                                 /* vtable + 8 */
        mv_vtable = f_new(64 * 4);
        for (int i = 0; i < 64; i++) mv_vtable[i] = (vp - 2)[i];
        mv_vtable[2 + 50] = (u32)mv_get_icon;
        mv_vtable[2 + 40] = (u32)mv_desc;
    }
    *(u32 **)it = mv_vtable + 2;
    ((void (*)(void *, int))(mv_vtable[2 + 6]))(it, 1);        /* setStackedByData(true) */
}

/* a mod door item: DoorItem::useOn picks the game's door block by DoorType and calls DoorItem::place(BlockSource*,
 * x, y, z, dir, Block*); while a mod door item is used, place() gets the mod's door block */
static void *mi_door_block;
static u64 hc_door_useon(u32 orig, u32 *w) {          /* DoorItem::useOn(this, ItemInstance*, Player*, x, y, z, face, ...) */
    char *it = (char *)w[0];
    int id = it ? *(unsigned short *)(it + 0x12) : 0;
    ModItem *m = id >= 256 && id < 512 ? mitem_of[id] : 0;
    void *keep = mi_door_block;
    mi_door_block = m && m->kind == 13 && m->level > 0 && m->level < 256 ? mf_blocks[m->level] : 0;
    u64 r = nh_callw(orig, w);
    mi_door_block = keep;
    return r;
}
static u64 hc_door_place(u32 orig, u32 *w) {          /* place(BlockSource*, x, y, z, dir, Block*): w[5] the block */
    if (mi_door_block) w[5] = (u32)mi_door_block;
    return nh_callw(orig, w);
}
static void doors_init(void *mc) {
    void *u = dlsym(mc, "_ZN8DoorItem5useOnEP12ItemInstanceP6Playeriiiafff");
    void *p = dlsym(mc, "_ZN8DoorItem5placeEP11BlockSourceiiiiP5Block");
    LOG("doors: %d %d", u ? nh_hook_c((u32)u, hc_door_useon) : -1, p ? nh_hook_c((u32)p, hc_door_place) : -1);
}
