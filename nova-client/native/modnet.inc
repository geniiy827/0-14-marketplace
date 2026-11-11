/* modnet.inc - host <-> players link for mods (host.send / players.send, actions of the host on players).
 *
 * Transport: the game's own TextPacket over the game's own connection (LAN, NovaNet, anything), so no second channel.
 *   player -> host: type 1 (chat), message "/nv!" mod 0x1f channel 0x1f data. ServerNetworkHandler::handle(TextPacket)
 *     drops chat that starts with '/' (a host without NOVA just ignores it); our hook takes it first.
 *   host -> player: type 0 (raw), message 0x02 "nv!" mod 0x1f channel 0x1f data, sent to that player's RakNetGUID
 *     (Player +0xd08) with PacketSender::send(guid, packet); ClientNetworkHandler::handle(TextPacket) is hooked.
 * The host's own player never goes through packets: delivered in-process.
 * TextPacket: +0 vtable, +4 2, +8 1, +0xc 0, +0xd type, +0x10 source, +0x14 message, +0x18 vector<string> (empty).
 * Minecraft (MinecraftClient::getServer): getLevel, getPacketSender (+0x50; slots 2 send(packet), 3 send(guid, packet)).
 * Host = the level is not client side. Mod "nova" is ours: "tp" = move yourself (movement is the client's in 0.14). */
typedef struct { u32 vt; int a, b; unsigned char c, type; short pad; u32 src, msg; u32 v[3]; } NTextPacket;
static void *(*nf_server)(void *client);
static char *(*nf_level)(void *mc);
static void **(*nf_sender)(void *mc);
static int (*nf_client_side)(void *level);
static u32 *(*nf_players)(void *level);              /* vector<Player*> */
static u32 *(*nf_nametag)(void *e);                   /* std::string const& */
static void (*nf_dmg_ctor)(void *src, int cause);
static void (*nf_heal)(void *mob, int n);
static int (*nf_health)(void *mob);
static void *(*nf_spawn_item)(void *e, void *ii, float dy);
static u32 *nf_text_vt;
static void *(*nf_srv_player)(void *snh, void *guid);

static char *net_mc(void) { return g_client && nf_server ? nf_server(g_client) : 0; }
static char *net_level(void) { char *m = net_mc(); return m && nf_level ? nf_level(m) : 0; }
static int net_is_host(void) { char *l = net_level(); return l && nf_client_side && !nf_client_side(l); }
static const char *net_name(void *pl) { u32 *s = pl && nf_nametag ? nf_nametag(pl) : 0; return s ? (const char *)*s : ""; }
static int net_same(const char *a, const char *b) {          /* names: case does not matter */
    for (;; a++, b++) {
        char x = *a, y = *b;
        if (x >= 'A' && x <= 'Z') x += 32;
        if (y >= 'A' && y <= 'Z') y += 32;
        if (x != y) return 0;
        if (!x) return 1;
    }
}
static char *net_find(const char *name) {
    char *l = net_level();
    u32 *v = l && nf_players ? nf_players(l) : 0;
    if (!v) return 0;
    for (char **it = (char **)v[0]; it < (char **)v[1]; it++) if (*it && net_same(net_name(*it), name)) return *it;
    return 0;
}

static void net_packet(NTextPacket *p, int type, const char *msg) {
    memset(p, 0, sizeof *p);
    p->vt = (u32)(nf_text_vt + 2); p->a = 2; p->b = 1; p->type = (unsigned char)type;
    gstr(&p->src, ""); gstr(&p->msg, msg);
}
static void net_packet_free(NTextPacket *p) { f_strdtor(&p->src); f_strdtor(&p->msg); }

/* in-process delivery / what a packet brings: "mod 0x1f channel 0x1f data" */
static void net_client_got(char *body) {
    if (body[0] == 'n' && !memcmp(body, "nova\x1f", 5)) {                 /* ours */
        char *p = body + 5;
        if (!memcmp(p, "tp\x1f", 3)) {
            float v[3] = {0, 0, 0};
            p += 3;
            for (int i = 0; i < 3; i++) {
                int neg = 0; float n = 0, frac = 0.1f;
                while (*p == ' ') p++;
                if (*p == '-') { neg = 1; p++; }
                while (*p >= '0' && *p <= '9') n = n * 10 + (*p++ - '0');
                if (*p == '.') { p++; while (*p >= '0' && *p <= '9') { n += (*p++ - '0') * frac; frac *= 0.1f; } }
                v[i] = neg ? -n : n;
            }
            char *pl = mm_player();
            SVec3 to = {v[0], v[1] + 1.62f, v[2]};
            if (pl && sf_setpos) sf_setpos(pl, &to);
        }
        return;
    }
    mm_fire(EV_CLIENT_RECEIVE, body, 0, 0);
}
static void net_host_got(char *body, const char *player) {      /* body: mod 0x1f channel 0x1f data -> + player */
    char buf[6144];
    char *c = body, *d;
    while (*c && *c != '\x1f') c++;
    if (!*c) return;
    d = c + 1;
    while (*d && *d != '\x1f') d++;
    if (!*d) return;
    int k = 0;
    for (char *q = body; q <= d && k < 6000; q++) buf[k++] = *q;                /* mod 1f channel 1f */
    for (const char *q = player; *q && k < 6040; q++) if (*q != '\x1f') buf[k++] = *q;
    buf[k++] = '\x1f';
    for (char *q = d + 1; *q && k < 6143; q++) buf[k++] = *q;
    buf[k] = 0;
    mm_fire(EV_HOST_RECEIVE, buf, 0, 0);
}

/* flood guard on the host: 60 messages a second per sender */
static struct { u32 key; long at; int n; } net_rate[32];
static int net_allowed(u32 key) {
    long now = now_ms();
    int free_i = 0;
    for (int i = 0; i < 32; i++) {
        if (net_rate[i].key == key) {
            if (now - net_rate[i].at > 1000) { net_rate[i].at = now; net_rate[i].n = 0; }
            return ++net_rate[i].n <= 60;
        }
        if (now - net_rate[i].at > 5000) free_i = i;
    }
    net_rate[free_i].key = key; net_rate[free_i].at = now; net_rate[free_i].n = 1;
    return 1;
}

static u64 hc_net_server_text(u32 orig, u32 *w) {  /* ServerNetworkHandler::handle(this, guid, TextPacket*) */
    char *pk = (char *)w[2];
    const char *m = pk ? *(const char **)(pk + 0x14) : 0;
    if (pk && pk[0xd] == 1 && m && m[0] == '/' && !memcmp(m, "/nv!", 4)) {
        u32 *g = (u32 *)w[1];
        if (g && net_allowed(g[0] ^ g[1]) && nf_srv_player) {
            char *pl = nf_srv_player((void *)w[0], g);
            if (pl) {
                char body[6144];
                int k = 0;
                for (const char *q = m + 4; *q && k < 6143; q++) body[k++] = *q;
                body[k] = 0;
                net_host_got(body, net_name(pl));
            }
        }
        return 0;
    }
    return nh_callw(orig, w);
}
static u64 hc_net_client_text(u32 orig, u32 *w) {  /* ClientNetworkHandler::handle(this, guid, TextPacket*) */
    char *pk = (char *)w[2];
    const char *m = pk ? *(const char **)(pk + 0x14) : 0;
    if (pk && pk[0xd] == 0 && m && m[0] == 2 && !memcmp(m, "\x02nv!", 4)) {
        char body[6144];
        int k = 0;
        for (const char *q = m + 4; *q && k < 6143; q++) body[k++] = *q;
        body[k] = 0;
        net_client_got(body);
        return 0;
    }
    return nh_callw(orig, w);
}

/* body = mod 0x1f channel 0x1f data */
static int net_to_host(const char *body) {
    char *pl = mm_player();
    if (!pl) return 0;
    if (net_is_host()) { char b[6144]; int k = 0; while (body[k] && k < 6143) { b[k] = body[k]; k++; } b[k] = 0; net_host_got(b, net_name(pl)); return 1; }
    char *mc = net_mc();
    void **snd = mc && nf_sender ? nf_sender(mc) : 0;
    if (!snd || !nf_text_vt) return 0;
    char msg[6200] = "/nv!";
    int k = 4;
    while (*body && k < 6190) msg[k++] = *body++;
    msg[k] = 0;
    NTextPacket p;
    net_packet(&p, 1, msg);
    ((void (*)(void *, void *))(*(void ***)snd)[2])(snd, &p);
    net_packet_free(&p);
    return 1;
}
static int net_raw_to(char *pl, int type, const char *msg) {          /* host -> one remote player */
    char *mc = net_mc();
    void **snd = mc && nf_sender ? nf_sender(mc) : 0;
    if (!snd || !nf_text_vt) return 0;
    NTextPacket p;
    net_packet(&p, type, msg);
    ((void (*)(void *, void *, void *))(*(void ***)snd)[3])(snd, pl + 0xd08, &p);
    net_packet_free(&p);
    return 1;
}
static int net_to_player(char *pl, const char *body) {
    if (pl == mm_player()) { char b[6144]; int k = 0; while (body[k] && k < 6143) { b[k] = body[k]; k++; } b[k] = 0; net_client_got(b); return 1; }
    char msg[6200] = "\x02nv!";
    int k = 4;
    while (*body && k < 6190) msg[k++] = *body++;
    msg[k] = 0;
    return net_raw_to(pl, 0, msg);
}

/* Java: nativeApiStr(1000 + op, numbers, text) -> text or null (not possible here: not the host, no such player) */
enum { NET_IS_HOST = 1, NET_TO_HOST, NET_TO_PLAYER, NET_PLAYERS, NET_HURT, NET_HEAL, NET_TELEPORT, NET_GIVE, NET_INFO, NET_TELL, NET_MY_NAME };
static void *net_api(void **env, int op, void *darr, void *jstr) {
    void **f = *(void ***)env;
    double a[8] = {0};
    int n = 0;
    if (darr) {
        n = ((int (*)(void *, void *))f[171])(env, darr);
        if (n > 8) n = 8;
        if (n) ((void (*)(void *, void *, int, int, double *))f[206])(env, darr, 0, n, a);
    }
    static char s[6400], out[4096];
    jstr_copy(env, jstr, s, sizeof s);
    const char *ok = "1";
    out[0] = 0;
    if (!mm_player()) return 0;
    int host = net_is_host();
    if (op == NET_IS_HOST) ok = host ? "1" : "0";
    else if (op == NET_MY_NAME) { const char *nm = net_name(mm_player()); int k = 0; while (nm[k] && k < 200) { out[k] = nm[k]; k++; } out[k] = 0; ok = out; }
    else if (op == NET_TO_HOST) { if (!net_to_host(s)) return 0; }
    else if (!host) return 0;                                             /* everything below is the host's */
    else if (op == NET_PLAYERS) {
        u32 *v = nf_players ? nf_players(net_level()) : 0;
        int k = 0;
        if (v) for (char **it = (char **)v[0]; it < (char **)v[1]; it++) {
            const char *nm = *it ? net_name(*it) : "";
            if (!*nm) continue;
            if (k) out[k++] = '\x1f';
            while (*nm && k < 4000) out[k++] = *nm++;
        }
        out[k] = 0;
        ok = out;
    } else {
        char *rest = mm_second(s);                                        /* s = player, rest = the rest */
        if (op == NET_TO_PLAYER && s[0] == '*' && !s[1]) {
            u32 *v = nf_players ? nf_players(net_level()) : 0;
            if (v) for (char **it = (char **)v[0]; it < (char **)v[1]; it++) if (*it) net_to_player(*it, rest);
        } else {
            char *pl = net_find(s);
            if (!pl) return 0;
            if (op == NET_TO_PLAYER) { if (!net_to_player(pl, rest)) return 0; }
            else if (op == NET_HURT && nf_dmg_ctor) {
                u32 src[8];
                nf_dmg_ctor(src, (int)a[1]);
                ((int (*)(void *, void *, int))(*(void ***)pl)[66])(pl, src, (int)a[0]);       /* Entity::hurt */
            } else if (op == NET_HEAL && nf_heal) nf_heal(pl, (int)a[0]);
            else if (op == NET_TELEPORT) {
                char body[96];
                /* fixed point by hand (no %f here) */
                int k = 0;
                const char *head = "nova\x1ftp\x1f";
                while (*head) body[k++] = *head++;
                for (int i = 0; i < 3; i++) {
                    double v = a[i];
                    if (v < 0) { body[k++] = '-'; v = -v; }
                    int whole = (int)v, fr = (int)((v - whole) * 1000);
                    char t[16]; int tn = 0;
                    do { t[tn++] = (char)('0' + whole % 10); whole /= 10; } while (whole && tn < 12);
                    while (tn) body[k++] = t[--tn];
                    body[k++] = '.';
                    body[k++] = (char)('0' + fr / 100); body[k++] = (char)('0' + fr / 10 % 10); body[k++] = (char)('0' + fr % 10);
                    body[k++] = ' ';
                }
                body[k] = 0;
                if (!net_to_player(pl, body)) return 0;
            } else if (op == NET_GIVE && nf_spawn_item && sf_item_ctor && sf_items) {
                int id = (int)a[0];
                if (id <= 0 || id >= 512 || !sf_items[id]) return 0;
                char ii[24];
                memset(ii, 0, sizeof ii);
                sf_item_ctor(ii, id, (int)a[1] > 0 ? (int)a[1] : 1, (int)a[2]);
                nf_spawn_item(pl, ii, 0.f);                               /* drops at the player's feet: picked up at once */
            } else if (op == NET_INFO) {
                float *p = (float *)(pl + 8);
                int v[4] = {(int)(p[0] * 100), (int)((p[1] - 1.62f) * 100), (int)(p[2] * 100), nf_health ? nf_health(pl) : 0};
                snprintf(out, sizeof out, "%d\x1f%d\x1f%d\x1f%d", v[0], v[1], v[2], v[3]);     /* x y z in 1/100 */
                ok = out;
            } else if (op == NET_TELL) {
                if (pl == mm_player()) { if (mf_gd_msg && f_guidata && g_client) { u32 st[1]; gstr(st, rest); mf_gd_msg(f_guidata(g_client), st); f_strdtor(st); } }
                else if (!net_raw_to(pl, 0, rest)) return 0;
            } else return 0;
        }
    }
    return ((void *(*)(void *, const char *))f[167])(env, ok);
}

/* players coming and going, for the host's mods */
static u64 hc_net_ready(u32 orig, u32 *w) {        /* ServerNetworkHandler::onReady_ClientGeneration(this, Player*, guid) */
    u64 r = nh_callw(orig, w);
    if (w[1]) mm_fire(EV_PLAYERS_JOIN, net_name((void *)w[1]), 0, 0);
    return r;
}
static u64 hc_net_gone(u32 orig, u32 *w) {         /* ServerNetworkHandler::onDisconnect(this, guid, reason) */
    char *pl = nf_srv_player && w[1] ? nf_srv_player((void *)w[0], (void *)w[1]) : 0;
    char name[64];
    name[0] = 0;
    if (pl) { const char *nm = net_name(pl); int k = 0; while (nm[k] && k < 63) { name[k] = nm[k]; k++; } name[k] = 0; }
    u64 r = nh_callw(orig, w);
    if (name[0]) mm_fire(EV_PLAYERS_LEAVE, name, 0, 0);
    return r;
}

static void modnet_init(void *mc) {
    nf_server = dlsym(mc, "_ZN15MinecraftClient9getServerEv");
    nf_level = dlsym(mc, "_ZN9Minecraft8getLevelEv");
    nf_sender = dlsym(mc, "_ZN9Minecraft15getPacketSenderEv");
    nf_client_side = dlsym(mc, "_ZNK5Level12isClientSideEv");
    nf_players = dlsym(mc, "_ZN5Level10getPlayersEv");
    nf_nametag = dlsym(mc, "_ZNK6Entity10getNameTagEv");
    nf_dmg_ctor = dlsym(mc, "_ZN18EntityDamageSourceC2E17EntityDamageCause");
    nf_heal = dlsym(mc, "_ZN3Mob4healEi");
    nf_health = dlsym(mc, "_ZNK3Mob9getHealthEv");
    nf_spawn_item = dlsym(mc, "_ZN6Entity15spawnAtLocationERK12ItemInstancef");
    nf_text_vt = dlsym(mc, "_ZTV10TextPacket");
    nf_srv_player = dlsym(mc, "_ZN20ServerNetworkHandler10_getPlayerERKN6RakNet10RakNetGUIDE");
    void *hs = dlsym(mc, "_ZN20ServerNetworkHandler6handleERKN6RakNet10RakNetGUIDEP10TextPacket");
    void *hcl = dlsym(mc, "_ZN20ClientNetworkHandler6handleERKN6RakNet10RakNetGUIDEP10TextPacket");
    void *rd = dlsym(mc, "_ZN20ServerNetworkHandler24onReady_ClientGenerationEP6PlayerRKN6RakNet10RakNetGUIDE");
    void *dc = dlsym(mc, "_ZN20ServerNetworkHandler12onDisconnectERKN6RakNet10RakNetGUIDERKSs");
    int ok = nf_server && nf_level && nf_sender && nf_client_side && nf_players && nf_nametag && nf_text_vt && hs && hcl;
    if (ok) { nh_hook_c((u32)hs, hc_net_server_text); nh_hook_c((u32)hcl, hc_net_client_text); }
    if (ok && rd) nh_hook_c((u32)rd, hc_net_ready);
    if (ok && dc && nf_srv_player) nh_hook_c((u32)dc, hc_net_gone);
    LOG("mod link (host <-> players): %s", ok ? "ok" : "unavailable");
}
