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
static void (*ef_particle)(void *level, int type, float *pos, float *vel, int data);
static void (*ef_sound)(void *level, float *pos, void *name, float vol, float pitch);

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
        unsigned char want[256];                              /* id -> list index + 1; packed blocks (code >= 65536): by data */
        signed char wdata[256];
        unsigned char wsub[256][16];
        int anydata = 0;
        for (int i = 0; i < 256; i++) { want[i] = 0; wdata[i] = 0; }
        for (int i = 0; i < k; i++) {
            long code = (long)a[6 + i];
            int id = (int)(code & 0xffff), dt = (int)(code >> 16);
            if (id <= 0 || id >= 256) continue;
            if (code >= 65536 || (mblock_packed[id] && dt == 0)) {
                if (!wdata[id]) { wdata[id] = 1; for (int q = 0; q < 16; q++) wsub[id][q] = 0; }
                if (dt < 16 && !wsub[id][dt]) wsub[id][dt] = (unsigned char)(i + 1);
                anydata = 1;
            } else if (!want[id]) want[id] = (unsigned char)(i + 1);
        }
        for (int x = x1; x <= x2; x++)
            for (int z = z1; z <= z2; z++)
                for (int y = y1; y <= y2; y++) {
                    int id = mm_blockid(bs, x, y, z);
                    if (id <= 0 || id >= 256) continue;
                    if (want[id]) out[want[id] - 1] += 1;
                    else if (anydata && wdata[id] && mf_getdata) {
                        int dt = mf_getdata(bs, x, y, z) & 15;
                        if (wsub[id][dt]) out[wsub[id][dt] - 1] += 1;
                    }
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
    if (op == OP_WORLD_PARTICLE) {                                    /* type x y z vx vy vz data: only on this phone */
        void *lvl = mf_getlevel ? mf_getlevel(bs) : 0;
        if (!lvl || !ef_particle) return 0;
        float p[3] = {(float)A(1), (float)A(2), (float)A(3)}, v[3] = {(float)A(4), (float)A(5), (float)A(6)};
        ef_particle(lvl, (int)A(0), p, v, (int)A(7));
        return 0;
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

/* world.sound(name, x, y, z, volume, pitch): Level::playSound, heard on this phone */
static int env_sound(const double *a, int n, const char *name) {
    char *pl = mm_player();
    void *bs = pl ? mm_region(pl) : 0;
    void *lvl = bs && mf_getlevel ? mf_getlevel(bs) : 0;
    if (!lvl || !ef_sound || !name || !*name) return 0;
    float p[3] = {(float)(n > 0 ? a[0] : 0), (float)(n > 1 ? a[1] : 0), (float)(n > 2 ? a[2] : 0)};
    u32 str[1];
    gstr(str, name);
    ef_sound(lvl, p, str, (float)(n > 3 ? a[3] : 1), (float)(n > 4 ? a[4] : 1));
    f_strdtor(str);
    return 0;
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
    S(ef_particle, "_ZN5Level11addParticleE12ParticleTypeRK4Vec3S3_i");
    S(ef_sound, "_ZN5Level9playSoundERK4Vec3RKSsff");
    #undef S
}

/* ---- animated textures of mod blocks: the game's FlipbookTexture (0.14 plays still lava / water / portal / fire
 * with it): a vertical strip of square frames is drawn over the tile in the block atlas every frametime ticks.
 * FlipbookTexture(TextureGroup&, TextureUVCoordinateSet const&, std::string const& file, int 1, int ticksPerFrame),
 * TextureGroup::addTickingTexture(unique_ptr). MinecraftClient+0x78 TextureGroup (MinecraftClient::init). ---- */
#define FLIP_MAX 48
static struct { float uv[4]; int ticks; char path[120]; } flips[FLIP_MAX];
static int flip_count, flip_done;
static void (*ef_flip_ctor)(void *self, void *group, void *uv, void *name, int one, int ticks);
static void (*ef_add_ticking)(void *group, void **uptr);
static void *(*ef_new)(unsigned n);

int Java_com_nova_client_ModBridge_nativeBlockAnims(void **env, void *cls, void *darr, void *jpaths) {
    void **f = *(void ***)env;
    if (!darr || flip_done) return -1;
    int n = ((int (*)(void *, void *))f[171])(env, darr);
    double *d = f_new(n * 8 + 8);
    ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, d);
    static char paths[FLIP_MAX * 120];
    jstr_copy(env, jpaths, paths, sizeof paths);
    char *pp = paths;
    flip_count = 0;
    for (int i = 0; i + 5 <= n && flip_count < FLIP_MAX; i += 5) {
        int k = flip_count++;
        flips[k].uv[0] = (float)(d[i] / 512.0);                    /* ModBlocks.ATLAS_W / ATLAS_H */
        flips[k].uv[1] = (float)(d[i + 1] / 512.0);
        flips[k].uv[2] = (float)((d[i] + d[i + 2]) / 512.0);
        flips[k].uv[3] = (float)((d[i + 1] + d[i + 3]) / 512.0);
        flips[k].ticks = (int)d[i + 4];
        int j = 0;
        while (*pp && *pp != '\x1f' && j < 119) flips[k].path[j++] = *pp++;
        flips[k].path[j] = 0;
        if (*pp == '\x1f') pp++;
    }
    return flip_count;
}

static void flip_frame(void *client) {
    if (flip_done || !flip_count || !client) return;
    flip_done = 1;
    if (!ef_flip_ctor || !ef_add_ticking || !ef_new || !mf_blocks || !mf_blocks[1]) { LOGE("flipbook: unavailable"); return; }
    void *group = f_textures ? f_textures(client) : 0;              /* MinecraftClient::getTextures */
    if (!group) { flip_done = 0; return; }
    TexSet *stone = (TexSet *)((char *)mf_blocks[1] + 16);         /* a terrain uv set: size / atlas file fields */
    int ok = 0;
    for (int i = 0; i < flip_count; i++) {
        static TexSet uv[FLIP_MAX];
        uv[i] = *stone;
        for (int k = 0; k < 4; k++) uv[i].b[k] = flips[i].uv[k];
        u32 name[1];
        gstr(name, flips[i].path);
        void *fb = ef_new(0x64);
        memset(fb, 0, 0x64);
        ef_flip_ctor(fb, group, &uv[i], name, 1, flips[i].ticks);
        f_strdtor(name);
        void *up = fb;
        ef_add_ticking(group, &up);
        if (up) { void **vt = *(void ***)up; ((void (*)(void *))vt[1])(up); }   /* not taken: delete */
        else ok++;
    }
    LOG("flipbook: %d animated texture(s)", ok);
}

static void flip_init(void *mc) {
    *(void **)&ef_flip_ctor = dlsym(mc, "_ZN15FlipbookTextureC1ERN3mce12TextureGroupERK22TextureUVCoordinateSetRKSsii");
    if (!ef_flip_ctor) *(void **)&ef_flip_ctor = dlsym(mc, "_ZN15FlipbookTextureC2ERN3mce12TextureGroupERK22TextureUVCoordinateSetRKSsii");
    *(void **)&ef_add_ticking = dlsym(mc, "_ZN3mce12TextureGroup17addTickingTextureESt10unique_ptrI14TickingTextureSt14default_deleteIS2_EE");
    *(void **)&ef_new = dlsym(mc, "_Znwj");
    if (!ef_flip_ctor || !ef_add_ticking || !ef_new) LOGE("flipbook: missing symbols");
}
