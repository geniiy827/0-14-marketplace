/* winter.inc - weather.* of McMod Script (mcmod/api/weather.tsv): endless precipitation, every biome cold, snow that
 * keeps piling up (the "Infinity Winter" mod).
 *   rain    Weather (Dimension::getWeather) +0x18 / +0x1c: the rain level getRainLevel() lerps between; set every frame
 *   freeze  every Biome (Biome::mBiomes) gets temperature 0 (+0x4c, below RAIN_TEMP_THRESHOLD: snow, ice) and may
 *           have precipitation (+0x8c, cleared by setNoRain in deserts); the old values come back with freeze(0)
 *   snow    the game's own snow (Weather::tryToPlaceTopSnow) stops at the biome's few layers; here, on the host, around
 *           every player, random columns get one more layer (TopSnow 78, data = layers - 1); 8 layers become a snow
 *           block (80) and the next layer starts on top of it. No limit unless one is set.
 * Settings come from the script on every machine; the blocks only from the host (its changes reach the players). */
static float wt_rain = -1.f;
static int wt_freeze;
static float wt_speed;                                   /* layers per minute per column */
static int wt_limit;                                     /* snow height in blocks, 0 = endless */
static float wt_saved_temp[256];
static unsigned char wt_saved_rain[256];
static int wt_saved;
static float wt_acc;
static u32 wt_seed = 0x9e3779b9u;
static void **wt_biomes;                                 /* Biome::mBiomes */
static void *(*wt_get_weather)(void *dim);
static void (*wt_top_rain)(MPos *out, void *bs, MPos *pos);

static u32 wt_rand(void) { wt_seed ^= wt_seed << 13; wt_seed ^= wt_seed >> 17; wt_seed ^= wt_seed << 5; return wt_seed; }

static void wt_apply_freeze(int on) {
    if (!wt_biomes) return;
    if (on && !wt_saved) {
        for (int i = 0; i < 256; i++) {
            char *b = wt_biomes[i];
            if (!b) continue;
            wt_saved_temp[i] = *(float *)(b + 0x4c);
            wt_saved_rain[i] = (unsigned char)b[0x8c];
            *(float *)(b + 0x4c) = 0.f;
            b[0x8c] = 1;
        }
        wt_saved = 1;
    } else if (!on && wt_saved) {
        for (int i = 0; i < 256; i++) {
            char *b = wt_biomes[i];
            if (!b) continue;
            *(float *)(b + 0x4c) = wt_saved_temp[i];
            b[0x8c] = (char)wt_saved_rain[i];
        }
        wt_saved = 0;
    }
}

static int wt_op(int op, double *a, int n) {
    if (op == OP_WEATHER_RAIN) { wt_rain = n > 0 ? (float)a[0] : -1.f; if (wt_rain > 1.f) wt_rain = 1.f; return 0; }
    if (op == OP_WEATHER_FREEZE) { wt_freeze = n > 0 && a[0] != 0; wt_apply_freeze(wt_freeze); return 0; }
    if (op == OP_WEATHER_SNOW) {
        wt_speed = n > 0 ? (float)a[0] : 0.f;
        wt_limit = n > 1 ? (int)a[1] : 0;
        if (wt_speed < 0) wt_speed = 0;
        if (wt_speed > 600) wt_speed = 600;
        return 0;
    }
    return -1;
}

/* every frame, on every machine: the rain stays */
static void wt_frame(void *bs) {
    if (wt_rain < 0 || !bs || !mf_bs_dim || !wt_get_weather) return;
    char *w = wt_get_weather(mf_bs_dim(bs));
    if (!w) return;
    *(float *)(w + 0x18) = wt_rain;
    *(float *)(w + 0x1c) = wt_rain;
}

/* one more layer of snow at (x, z) on top of whatever is there */
static int wt_snowable(int id) {
    if (id == 80 || id == 18 || id == 161 || id == 79 || id == 174) return 1;     /* snow, leaves, ice, packed ice */
    return id > 0 && id < 256 && mf_solid && mf_solid[id];
}
static int wt_replaceable(int id) {
    return id == 6 || id == 31 || id == 32 || id == 37 || id == 38 || id == 39 || id == 40 || id == 175;   /* plants under snow */
}
static void wt_place(void *bs, int x, int z, char **pls, int npl) {
    MPos q = {x, 64, z}, p = {0, 0, 0};
    wt_top_rain(&p, bs, &q);
    if (p.y <= 0 || p.y > 127) return;
    for (int i = 0; i < npl; i++) {                           /* not where a player stands: nobody gets walled in */
        float *pp = (float *)(pls[i] + 8);
        int px = (int)floorf(pp[0]), pz = (int)floorf(pp[2]), py = (int)floorf(pp[1] - 1.62f);
        if (px - x >= -1 && px - x <= 1 && pz - z >= -1 && pz - z <= 1 && p.y >= py - 1 && p.y <= py + 2) return;
    }
    int id = mm_blockid(bs, x, p.y, z), y = p.y;
    if (id != 78) {                                           /* rain stops above the top layer: look under it */
        int below = mm_blockid(bs, x, p.y - 1, z);
        if (below == 78) { id = 78; y = p.y - 1; }
    }
    if (wt_limit > 0) {                                       /* how deep the snow already is here */
        int depth = 0, yy = id == 78 ? y - 1 : y - 1;
        while (yy > 0 && depth <= wt_limit && mm_blockid(bs, x, yy, z) == 80) { depth++; yy--; }
        if (depth >= wt_limit) return;
    }
    MPos at = {x, y, z};
    if (id == 78) {
        int d = mf_getdata ? mf_getdata(bs, x, y, z) : 0;
        MBlockID b = {d >= 6 ? 80 : 78};
        mf_setblock_pos(bs, &at, &b, d >= 6 ? 0 : (unsigned char)(d + 1), 3);
        return;
    }
    if (id != 0 && !wt_replaceable(id)) return;
    int under = mm_blockid(bs, x, y - 1, z);
    if (!wt_snowable(under) && !wt_replaceable(id)) return;
    MBlockID b = {78};
    mf_setblock_pos(bs, &at, &b, 0, 3);
}

/* every game tick on the host: the snow piles up around the players */
static void wt_tick(void *level) {
    if (wt_speed <= 0 || !level || !mf_isclient || mf_isclient(level) || !nf_players || !wt_top_rain || !wt_region) return;
    u32 *v = nf_players(level);
    if (!v) return;
    char *pls[16];
    int npl = 0;
    for (char **it = (char **)v[0]; it < (char **)v[1] && npl < 16; it++) if (*it) pls[npl++] = *it;
    const int R = 32;
    float per_tick = wt_speed * (2 * R + 1) * (2 * R + 1) / 1200.f;   /* columns around one player, 20 ticks a second */
    for (int i = 0; i < npl; i++) {
        void *bs = wt_region(pls[i]);
        if (!bs) continue;
        float *pp = (float *)(pls[i] + 8);
        int cx = (int)floorf(pp[0]), cz = (int)floorf(pp[2]);
        wt_acc += per_tick;
        int n = (int)wt_acc;
        wt_acc -= n;
        if (n > 400) n = 400;
        for (int k = 0; k < n; k++) {
            u32 span = 2 * R + 1;                             /* no division in libnova: scale the random number */
            int x = cx + (int)(((unsigned long long)wt_rand() * span) >> 32) - R;
            int z = cz + (int)(((unsigned long long)wt_rand() * span) >> 32) - R;
            wt_place(bs, x, z, pls, npl);
        }
    }
}

static void winter_init(void *mc) {
    wt_biomes = dlsym(mc, "_ZN5Biome7mBiomesE");
    wt_get_weather = dlsym(mc, "_ZNK9Dimension10getWeatherEv");
    wt_top_rain = dlsym(mc, "_ZN11BlockSource18getTopRainBlockPosERK8BlockPos");
    wt_region = dlsym(mc, "_ZNK6Entity9getRegionEv");
    LOG("winter: %s", wt_biomes && wt_get_weather && wt_top_rain && wt_region ? "ok" : "symbols missing");
}

/* a world was left: the next one starts as the game makes it (mods set it again on world.join) */
static void wt_reset(void) { wt_rain = -1.f; wt_speed = 0; wt_limit = 0; wt_freeze = 0; wt_apply_freeze(0); }
