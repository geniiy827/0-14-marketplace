/* ---------------- player triggers for mods ----------------
 * GameMode (only class, vtable _ZTV8GameMode): 3 destroyBlock, 12 useItem, 13 useItemOn, 14 interact, 15 attack.
 * Sneak: Mob::isSneaking polled per frame. Jump: Mob::jumpFromGround gate (scale.inc).
 * Custom action button: MinecraftInputHandler::canInteract / getInteractText and MinecraftClient::handleInteractButtonPress.
 * ItemInstance: +0xc Item*, +0x10 Block*; Item id +0x12. HitResult (Level::getHitResult): +0 kind (0 block, 1 entity),
 * +4 face, +8 x y z, +0x20 Entity*. Entity: +0x129 onGround. */
static int (*ef_sneaking)(void *mob);
static void *(*ef_selected)(void *player);
static int (*ef_aux)(void *ii);
static char *(*ef_hit)(void *level);
static int ev_last_sneak = -1;

static int ev_item_id(char *ii) {
    if (!ii) return 0;
    char *it = *(char **)(ii + 0xc);
    if (it) return *(short *)(it + 0x12);
    char *b = *(char **)(ii + 0x10);
    return b ? *(unsigned char *)(b + 4) : 0;
}
static int ev_item_aux(char *ii) { return ii && ef_aux ? ef_aux(ii) : 0; }
static int ev_type(char *e) {
    void **vt = *(void ***)e;
    return ((int (*)(void *))vt[83])(e) & 0xff;
}

/* GameMode hooks: only the local player's actions become events */
static int (*o_gm_use_on)(void *gm, void *pl, char *ii, MPos *pos, int face, void *hit);
static int h_gm_use_on(void *gm, void *pl, char *ii, MPos *pos, int face, void *hit) {
    if (pl == mm_player() && pos) {
        void *bs = mm_region(pl);
        double a[8] = {pos->x, pos->y, pos->z, (signed char)face,
                       bs && mf_getblock ? mf_getblock(bs, pos->x, pos->y, pos->z) : 0,
                       bs && mf_getdata ? mf_getdata(bs, pos->x, pos->y, pos->z) : 0, ev_item_id(ii), ev_item_aux(ii)};
        if (mm_fire(EV_BLOCK_USE, 0, a, 8)) return 0;
    }
    return o_gm_use_on(gm, pl, ii, pos, face, hit);
}
static int (*o_gm_use)(void *gm, void *pl, char *ii);
static int h_gm_use(void *gm, void *pl, char *ii) {
    if (pl == mm_player()) {
        double a[2] = {ev_item_id(ii), ev_item_aux(ii)};
        if (mm_fire(EV_ITEM_USE, 0, a, 2)) return 0;
    }
    return o_gm_use(gm, pl, ii);
}
static int (*o_gm_destroy)(void *gm, void *pl, MPos pos, int face);
static int h_gm_destroy(void *gm, void *pl, MPos pos, int face) {
    if (pl == mm_player()) {
        void *bs = mm_region(pl);
        double a[6] = {pos.x, pos.y, pos.z, bs && mf_getblock ? mf_getblock(bs, pos.x, pos.y, pos.z) : 0,
                       bs && mf_getdata ? mf_getdata(bs, pos.x, pos.y, pos.z) : 0,
                       ef_selected ? ev_item_id(ef_selected(pl)) : 0};
        if (mm_fire(EV_BLOCK_BREAK, 0, a, 6)) return 0;
    }
    return o_gm_destroy(gm, pl, pos, face);
}
static int (*o_gm_interact)(void *gm, void *pl, char *e);
static int h_gm_interact(void *gm, void *pl, char *e) {
    if (pl == mm_player() && e) {
        double a[3] = {(double)sf_uid(e), ev_type(e), ef_selected ? ev_item_id(ef_selected(pl)) : 0};
        if (mm_fire(EV_ENTITY_USE, 0, a, 3)) return 0;
    }
    return o_gm_interact(gm, pl, e);
}
static void (*o_gm_attack)(void *gm, void *pl, char *e);
static void h_gm_attack(void *gm, void *pl, char *e) {
    if (pl == mm_player() && e) {
        double a[3] = {(double)sf_uid(e), ev_type(e), ef_selected ? ev_item_id(ef_selected(pl)) : 0};
        if (mm_fire(EV_ENTITY_ATTACK, 0, a, 3)) return;
    }
    o_gm_attack(gm, pl, e);
}

/* custom action button: ui.action(text) or the held mod item's "action" */
static char ev_action[96];
static const char *mi_action_text(void);
static const char *ev_action_text(void) { return ev_action[0] ? ev_action : mi_action_text(); }
static int (*o_can_interact)(void *ih);
static int h_can_interact(void *ih) { return mm_player() && ev_action_text() ? 1 : o_can_interact(ih); }
static void *(*o_interact_text)(void *out, void *ih);
static void *h_interact_text(void *out, void *ih) {
    const char *t = mm_player() ? ev_action_text() : 0;
    if (!t) return o_interact_text(out, ih);
    gstr(out, t);
    return out;
}
static void (*o_interact_press)(void *client);
static void h_interact_press(void *client) {
    char *pl = mm_player();
    if (pl && ev_action_text()) {
        double a[1] = {ef_selected ? ev_item_id(ef_selected(pl)) : 0};
        mm_fire(EV_ACTION, 0, a, 1);
        return;
    }
    o_interact_press(client);
}

static void ev_frame(void) {
    char *pl = mm_player();
    if (!pl) { ev_last_sneak = -1; ev_action[0] = 0; return; }
    int s = ef_sneaking ? (ef_sneaking(pl) ? 1 : 0) : 0;
    if (ev_last_sneak >= 0 && s != ev_last_sneak) { double a[1] = {s}; mm_fire(EV_PLAYER_SNEAK, 0, a, 1); }
    ev_last_sneak = s;
}

static int ev_op(int op, const double *a, int n, char *s, double *out) {
    char *pl = mm_player();
    if (op == OP_UI_ACTION) {
        int k = 0;
        if (s) for (; s[k] && k < (int)sizeof ev_action - 1; k++) ev_action[k] = s[k];
        ev_action[k] = 0;
        return 0;
    }
    if (!pl) return -1;
    if (op == OP_PLAYER_STATE) {
        char *ii = ef_selected ? ef_selected(pl) : 0;
        out[0] = ef_sneaking && ef_sneaking(pl) ? 1 : 0;
        out[1] = *(unsigned char *)(pl + 0x129) ? 1 : 0;
        out[2] = ev_item_id(ii);
        out[3] = ev_item_aux(ii);
        return 4;
    }
    if (op == OP_PLAYER_TARGET) {
        void *lvl = *(void **)(pl + 0x78);
        char *hr = lvl && ef_hit ? ef_hit(lvl) : 0;
        int kind = hr ? *(int *)hr : -1;
        out[0] = -1; out[1] = out[2] = out[3] = out[4] = 0; out[5] = -1;
        if (kind == 0) { out[0] = 0; out[1] = *(int *)(hr + 8); out[2] = *(int *)(hr + 12); out[3] = *(int *)(hr + 16); out[4] = *(int *)(hr + 4); }
        else if (kind == 1 && *(char **)(hr + 0x20)) { out[0] = 1; out[5] = (double)sf_uid(*(char **)(hr + 0x20)); }
        return 6;
    }
    return -1;
}

static void events_init(void *mc, u32 base) {
    #define S(var, name) do { *(void **)&var = dlsym(mc, name); if (!var) LOGE("events: missing %s", name); } while (0)
    S(ef_sneaking, "_ZNK3Mob10isSneakingEv");
    S(ef_selected, "_ZNK6Player15getSelectedItemEv");
    S(ef_aux, "_ZNK12ItemInstance11getAuxValueEv");
    S(ef_hit, "_ZN5Level12getHitResultEv");
    #undef S
    u32 vt = (u32)dlsym(mc, "_ZTV8GameMode");
    if (vt) {
        patch_slot(vt, 3, h_gm_destroy, (void **)&o_gm_destroy);
        patch_slot(vt, 12, h_gm_use, (void **)&o_gm_use);
        patch_slot(vt, 13, h_gm_use_on, (void **)&o_gm_use_on);
        patch_slot(vt, 14, h_gm_interact, (void **)&o_gm_interact);
        patch_slot(vt, 15, h_gm_attack, (void **)&o_gm_attack);
    } else LOGE("events: no GameMode vtable");
    GATE(CAN_INTERACT, h_can_interact, o_can_interact);
    GATE(INTERACT_TEXT, h_interact_text, o_interact_text);
    GATE(INTERACT_PRESS, h_interact_press, o_interact_press);
}
