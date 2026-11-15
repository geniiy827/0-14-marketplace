/* env.inc - world.* / player.* of McMod Script for climate mods (Adventure Craft temperature, a port of SimpleDifficulty):
 *   world.biomeTemp/biomeSnow/snowTemp/sky/rainingAt/rainTop (x, y, z)   biome and weather at a block
 *   world.raining()                    Weather::isRaining of the player's dimension
 *   world.count(box, [ids])            how many of each block id in a box, in C (a script loop over 400 blocks is slow)
 *   world.drop(x, y, z, item, count)   Block::popResource: an item pops out of a block (the host's world)
 *   player.armor(slot)                 item id in an armor slot (0 head, 1 chest, 2 legs, 3 feet), 0 = none
 *   player.has(item)                   FillingContainer::hasResource: the item is somewhere in the inventory */
static void *(*ef_biome)(void *bs, MPos *p);
static int (*ef_sky)(void *bs, int x, int y, int z);
static int (*ef_snowtemp)(void *bs, MPos *p);
static int (*ef_raining)(void *w);
static int (*ef_raining_at)(void *w, MPos *p);
static void (*ef_top_rain)(MPos *out, void *bs, MPos *p);
static void (*ef_pop)(void *self, void *bs, MPos *p, void *ii);      /* member, "this" unused */
static int (*ef_has)(void *inv, int id);

static void *env_weather(void *bs) {
    return bs && mf_bs_dim && wt_get_weather ? wt_get_weather(mf_bs_dim(bs)) : 0;
}

static int env_op(int op, const double *a, int n, double *out) {
    char *pl = mm_player();
    void *bs = pl ? mm_region(pl) : 0;
    if (!pl || !bs) return -1;
    #define A(i) ((i) < n ? a[i] : 0.0)
    #define FL(i) ((int)floorf((float)A(i)))
    if (op == OP_WORLD_ENV) {
        MPos p = {FL(0), FL(1), FL(2)};
        char *b = ef_biome ? ef_biome(bs, &p) : 0;
        out[0] = b ? *(float *)(b + 0x4c) : 0.5;                      /* Biome::getTemperature (getDefaultTemperature in Java) */
        out[1] = b && *(float *)(b + 0x54) > 0 ? 1 : 0;              /* Biome::isSnowCovered */
        out[2] = ef_snowtemp ? ef_snowtemp(bs, &p) != 0 : 0;         /* BlockSource::isSnowTemperature */
        out[3] = ef_sky ? ef_sky(bs, p.x, p.y, p.z) != 0 : 1;        /* BlockSource::canSeeSky */
        void *w = env_weather(bs);
        out[4] = w && ef_raining_at ? ef_raining_at(w, &p) != 0 : 0; /* Weather::isRainingAt */
        MPos q = {0, 0, 0};
        if (ef_top_rain) ef_top_rain(&q, bs, &p);
        out[5] = q.y;                                                 /* BlockSource::getTopRainBlockPos */
        return 6;
    }
    if (op == OP_WORLD_RAIN) {
        void *w = env_weather(bs);
        out[0] = w && ef_raining ? ef_raining(w) != 0 : 0;
        return 1;
    }
    if (op == OP_WORLD_COUNT) {                                       /* a: x1 y1 z1 x2 y2 z2 id... */
        int x1 = FL(0), y1 = FL(1), z1 = FL(2), x2 = FL(3), y2 = FL(4), z2 = FL(5), t;
        if (x1 > x2) { t = x1; x1 = x2; x2 = t; }
        if (y1 > y2) { t = y1; y1 = y2; y2 = t; }
        if (z1 > z2) { t = z1; z1 = z2; z2 = t; }
        if (y1 < 0) y1 = 0;
        if (y2 > 127) y2 = 127;
        int k = n - 6;
        if (k < 1) return 0;
        if (k > 10) k = 10;
        for (int i = 0; i < k; i++) out[i] = 0;
        if ((long)(x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1) > 65536) return k;
        unsigned char want[256];
        for (int i = 0; i < 256; i++) want[i] = 0;
        for (int i = 0; i < k; i++) { int id = (int)a[6 + i]; if (id > 0 && id < 256 && !want[id]) want[id] = (unsigned char)(i + 1); }
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++)
                for (int y = y1; y <= y2; y++) {
                    int id = mm_blockid(bs, x, y, z);
                    if (id > 0 && id < 256 && want[id]) out[want[id] - 1] += 1;
                }
        return k;
    }
    if (op == OP_WORLD_DROP) {                                        /* x y z item count (item = id + 65536 * aux) */
        if (!mf_isclient || !mf_getlevel || mf_isclient(mf_getlevel(bs)) || !ef_pop || !sf_item_ctor || !sf_items) { out[0] = 0; return 1; }
        long code = (long)A(3);
        int id = (int)(code & 0xffff), aux = (int)(code >> 16), cnt = (int)A(4);
        out[0] = 0;
        if (id <= 0 || id >= 512 || cnt <= 0) return 1;
        if (id >= 256 && !sf_items[id]) return 1;
        MPos p = {FL(0), FL(1), FL(2)};
        while (cnt > 0) {
            char ii[24];
            memset(ii, 0, sizeof ii);
            int c = cnt > 64 ? 64 : cnt;
            sf_item_ctor(ii, id, c, aux);
            ef_pop(0, bs, &p, ii);
            cnt -= c;
        }
        out[0] = 1;
        return 1;
    }
    if (op == OP_PLAYER_ARMOR) {
        int slot = (int)A(0);
        out[0] = 0;
        if (slot < 0 || slot > 3) return 1;
        char *ii = pl + 0xb8c + slot * 20;                            /* Mob::getArmor: ItemInstance[4] */
        if (*(unsigned char *)ii == 0) return 1;                      /* count 0 = empty */
        out[0] = ev_item_id(ii);
        return 1;
    }
    if (op == OP_PLAYER_HAS) {
        long code = (long)A(0);
        char *inv = *(char **)(pl + 0xda0);
        out[0] = inv && ef_has ? ef_has(inv, (int)(code & 0xffff)) != 0 : 0;
        return 1;
    }
    #undef A
    #undef FL
    return -1;
}

static void env_init(void *mc) {
    #define S(var, name) do { *(void **)&var = dlsym(mc, name); if (!var) LOGE("env: missing %s", name); } while (0)
    S(ef_biome, "_ZN11BlockSource8getBiomeERK8BlockPos");
    S(ef_sky, "_ZN11BlockSource9canSeeSkyEiii");
    S(ef_snowtemp, "_ZN11BlockSource17isSnowTemperatureERK8BlockPos");
    S(ef_raining, "_ZNK7Weather9isRainingEv");
    S(ef_raining_at, "_ZNK7Weather11isRainingAtERK8BlockPos");
    S(ef_top_rain, "_ZN11BlockSource18getTopRainBlockPosERK8BlockPos");
    S(ef_pop, "_ZN5Block11popResourceER11BlockSourceRK8BlockPosRK12ItemInstance");
    S(ef_has, "_ZN16FillingContainer11hasResourceEi");
    #undef S
}
