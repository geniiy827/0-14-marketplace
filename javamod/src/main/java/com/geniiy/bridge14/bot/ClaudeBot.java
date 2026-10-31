package com.geniiy.bridge14.bot;

import com.geniiy.bridge14.pe.Buf;
import com.geniiy.bridge14.pe.Proto;
import com.geniiy.bridge14.pe.RakClient;
import com.geniiy.bridge14.proxy.DefaultSkin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Бот-игрок для MCPE 0.14 с локальным HTTP API (только 127.0.0.1).
 *   java -cp Bridge0_14-server.jar com.geniiy.bridge14.bot.ClaudeBot host[:port] [--nick Claude] [--http 8780]
 * GET /state, GET /view?r=8, GET /chat?since=N, GET /cmd?c=<команда>  (или POST /cmd, тело — команды построчно)
 */
public final class ClaudeBot {
    // ------------------------------------------------------------ настройки
    final String host; final int port; final String nick; final int httpPort;

    ClaudeBot(String host, int port, String nick, int httpPort) { this.host = host; this.port = port; this.nick = nick; this.httpPort = httpPort; }

    public static void main(String[] a) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        String host = "127.0.0.1"; int port = 19132; String nick = "Claude"; int http = 8780; String dir = null;
        for (int i = 0; i < a.length; i++) {
            if (a[i].equals("--nick") && i + 1 < a.length) nick = a[++i];
            else if (a[i].equals("--http") && i + 1 < a.length) http = Integer.parseInt(a[++i]);
            else if (a[i].equals("--dir") && i + 1 < a.length) dir = a[++i];
            else if (a[i].equals("--skin") && i + 1 < a.length) ClaudeSkin.style = a[++i];
            else if (a[i].equals("--parent") && i + 1 < a.length) parentPid = Long.parseLong(a[++i]);
            else if (!a[i].startsWith("--")) {
                String s = a[i]; int c = s.lastIndexOf(':');
                if (c > 0) { host = s.substring(0, c); port = Integer.parseInt(s.substring(c + 1)); } else host = s;
            }
        }
        ClaudeBot b = new ClaudeBot(host, port, nick, http);
        try { b.startHttp(); } catch (Exception e) { log("HTTP не запустился: " + e); }
        if (dir != null) b.startFiles(new java.io.File(dir));
        b.connect();
        Thread t = new Thread(b::tickLoop, "bot-tick"); t.setDaemon(false); t.start();
        if (parentPid > 0) {   // запущен из GeminiBrain: окно закрыли — выходим из игры
            final long pp = parentPid;
            Thread w = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    if (!ProcessHandle.of(pp).map(ProcessHandle::isAlive).orElse(false)) {
                        log("родитель закрыт — выхожу из игры");
                        RakClient rq = b.rak; b.rakGen++; if (rq != null) try { rq.close("quit"); } catch (Exception ignored) {}
                        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                        System.exit(0);
                    }
                }
            }, "parent-watch");
            w.setDaemon(true); w.start();
        }
    }

    static long parentPid;

    static synchronized void log(String m) { System.out.println(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + m); }

    // ------------------------------------------------------------ сеть
    volatile RakClient rak; int rakGen; int serverProtocol, peProtocol = Proto.PROTOCOL; boolean retried;
    volatile boolean spawned, connected; volatile String status = "подключаюсь";
    long selfEid; UUID selfUuid;

    void connect() {
        final int gen = ++rakGen;
        spawned = false; status = "подключаюсь к " + host + ":" + port;
        try {
            rak = new RakClient(host, port, new RakClient.Listener() {
                public void onConnected() { if (gen == rakGen) onRakConnected(); }
                public void onPayload(byte[] d) { if (gen == rakGen) payload(d); }
                public void onClosed(String r) { if (gen == rakGen) { connected = false; spawned = false; status = "отключён: " + r; event("[бот] отключён: " + r); } }
                public void log(String m) { ClaudeBot.log(m); }
            });
            rak.start();
        } catch (Exception e) { status = "ошибка: " + e; }
    }

    void onRakConnected() {
        connected = true;
        serverProtocol = rak.gameProtocol;
        if (!retried) peProtocol = serverProtocol == 45 ? 45 : Proto.PROTOCOL;
        selfUuid = UUID.nameUUIDFromBytes(("ClaudeBot:" + nick).getBytes());
        log("RakNet ок, протокол сервера " + serverProtocol + ", входим с " + peProtocol);
        rak.send(Proto.login(peProtocol, nick, new Random().nextLong(), selfUuid, host + ":" + port, ClaudeSkin.make()));
    }

    void send(byte[] pk) { RakClient r = rak; if (r != null && spawned) r.send(pk); }

    void payload(byte[] d) {
        if (d.length < 2) return;
        try { handle(new Buf(d, (d[0] & 0xff) == 0x8e ? 1 : 0, d.length)); }
        catch (Exception e) { log("пакет: " + e); }
    }

    void handle(Buf b) throws Exception {
        int id = b.u8();
        if (id == Proto.BATCH) {
            int n = b.i32();
            byte[] raw = Proto.inflate(b.take(Math.min(n, b.left())));
            Buf in = new Buf(raw);
            while (in.left() >= 4) {
                int len = in.i32();
                if (len <= 0 || len > in.left()) break;
                int s = in.pos;
                Buf one = new Buf(raw, s, s + len);
                if ((raw[s] & 0xff) == 0x8e && len > 1) one.pos++;
                try { handle(one); } catch (Exception e) { log("в пачке: " + e); }
                in.pos = s + len;
            }
            return;
        }
        synchronized (this) { pe(id, b); }
    }

    // ------------------------------------------------------------ мир
    static final class Chunk { final byte[] id = new byte[32768]; final byte[] meta = new byte[32768]; }
    final Map<Long, Chunk> chunks = new ConcurrentHashMap<>();
    final Map<Long, String> signs = new ConcurrentHashMap<>();
    String signText(int[] q) { String t = signs.get(key(q[0], q[1], q[2])); return t == null ? "" : " «" + t + "»"; }
    static long ck(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }
    static int idx(int x, int y, int z) { return (x << 11) | (z << 7) | y; }

    /** id блока, -1 — чанк не загружен. */
    int blockAt(int x, int y, int z) {
        if (y < 0) return 7; if (y > 127) return 0;
        Chunk c = chunks.get(ck(x >> 4, z >> 4));
        return c == null ? -1 : c.id[idx(x & 15, y, z & 15)] & 0xff;
    }
    int metaAt(int x, int y, int z) {
        if (y < 0 || y > 127) return 0;
        Chunk c = chunks.get(ck(x >> 4, z >> 4));
        return c == null ? 0 : c.meta[idx(x & 15, y, z & 15)];
    }
    void setBlock(int x, int y, int z, int id, int meta) {
        if (y < 0 || y > 127) return;
        Chunk c = chunks.get(ck(x >> 4, z >> 4));
        if (c == null) return;
        int i = idx(x & 15, y, z & 15); c.id[i] = (byte) id; c.meta[i] = (byte) meta;
    }

    static final Set<Integer> PASSABLE = new HashSet<>(Arrays.asList(0, 6, 8, 9, 10, 11, 27, 28, 30, 31, 32, 37, 38, 39, 40, 50, 51, 55, 59,
            63, 66, 68, 69, 70, 72, 75, 76, 77, 78, 83, 90, 104, 105, 106, 111, 115, 126, 131, 132, 141, 142, 143, 147, 148, 157, 171, 175, 244, 323));
    boolean solid(int id) { return id != 0 && !PASSABLE.contains(id); }
    boolean solidAt(int x, int y, int z) { int id = blockAt(x, y, z); return id < 0 || solid(id); }   // незагруженное = стена
    static boolean liquid(int id) { return id >= 8 && id <= 11; }
    static boolean dangerous(int id) { return id == 10 || id == 11 || id == 51 || id == 81 || id == 30; }

    // ------------------------------------------------------------ состояние игрока
    double x, y, z, vy; float yaw, pitch; boolean onGround; int corrections, bigCorrections; long lastCorrection; final List<String> lastFix = new ArrayList<>(); String password; int slowTicks, nickRetries;
    int health = 20, food = 20, gamemode, time; float xpLevel; boolean dead;
    final int[][] inv = new int[36][]; final int[] hotbar = {0, 1, 2, 3, 4, 5, 6, 7, 8}; int held;
    final int[][] armor = new int[4][];
    { for (int i = 0; i < 36; i++) inv[i] = new int[]{0, 0, 0, 0}; for (int i = 0; i < 4; i++) armor[i] = new int[]{0, 0, 0, 0}; }

    static final class Ent { long id; int type; String name; boolean player, item; int[] it; double x, y, z; float yaw; long seen; }
    final Map<Long, Ent> ents = new ConcurrentHashMap<>();

    // чат и события
    final List<String> events = new ArrayList<>(); int eventBase = 0;
    synchronized void event(String s) {
        log(s); events.add(s);
        if (events.size() > 500) { events.remove(0); eventBase++; }
    }

    void pe(int id, Buf b) {
        switch (id) {
            case Proto.PLAY_STATUS: {
                int st = b.i32();
                if ((st == 1 || st == 2) && !spawned) {
                    if (!retried && serverProtocol != 0 && serverProtocol != peProtocol) {
                        retried = true; peProtocol = serverProtocol;
                        log("сервер не принял протокол, пробую " + peProtocol);
                        RakClient old = rak; rakGen++; rak = null; if (old != null) old.close("retry");
                        connect(); return;
                    }
                    status = st == 1 ? "сервер новее клиента" : "сервер старее клиента"; return;
                }
                if (st == 3 && !spawned) {
                    spawned = true; status = "в игре";
                    RakClient r = rak; if (r != null) r.send(Proto.requestChunkRadius(6));
                    event("[бот] зашёл на сервер как " + nick);
                    if (password != null) later(1500, () -> send(Proto.text(nick, "/login " + password)));
                }
                break;
            }
            case Proto.DISCONNECT: {
                String m = b.str().replaceAll("§.", ""); status = "кикнут: " + m; event("[бот] кикнут: " + m);
                if (m.contains("Смените ник") && nickRetries++ < 6) later(8000, () -> { RakClient r = rak; rakGen++; rak = null; if (r != null) r.close("retry"); retried = false; connect(); });
                break;
            }
            case Proto.START_GAME: {
                b.i32(); b.u8(); b.i32();
                gamemode = b.i32(); selfEid = b.i64();
                b.i32(); b.i32(); b.i32();
                x = b.f32(); y = b.f32(); z = b.f32();   // у StartGame — ноги
                RakClient r = rak; if (r != null) r.send(Proto.requestChunkRadius(6));
                break;
            }
            case Proto.SET_TIME: time = b.i32(); break;
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32(); int order = b.u8(); int n = b.i32();
                byte[] d = b.take(Math.min(n, b.left()));
                if (d.length < 32768 + 16384) break;
                Chunk c = new Chunk();
                for (int xx = 0; xx < 16; xx++) for (int zz = 0; zz < 16; zz++) for (int yy = 0; yy < 128; yy++) {
                    int src = order == 1 ? ((yy << 8) | (zz << 4) | xx) : idx(xx, yy, zz);
                    int dst = idx(xx, yy, zz);
                    c.id[dst] = d[src];
                    int mv = d[32768 + (src >> 1)] & 0xff;
                    c.meta[dst] = (byte) ((src & 1) == 0 ? mv & 15 : mv >> 4);
                }
                chunks.put(ck(cx, cz), c);
                int off = 32768 + 16384 * 3 + 256 + 1024;
                if (d.length >= off + 4) try {
                    Buf tb = new Buf(d, off, d.length); int extra = tb.li32();
                    if (extra >= 0 && extra < 100000) { tb.skip(extra * 6);
                        for (Map<String, Object> tt : com.geniiy.bridge14.proxy.Nbt.readAllLE(tb))
                            sign(intOf(tt.get("x"), 0), intOf(tt.get("y"), 0), intOf(tt.get("z"), 0), tt); }
                } catch (Exception ignored) {}
                break;
            }
            case Proto.BLOCK_ENTITY_DATA: {
                int bx = b.i32(), by = b.i32(), bz = b.i32();
                try { Object o = com.geniiy.bridge14.proxy.Nbt.readLE(b); if (o instanceof Map) sign(bx, by, bz, (Map<?, ?>) o); } catch (Exception ignored) {}
                break;
            }
            case Proto.UPDATE_BLOCK: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 11; i++) {
                    int bx = b.i32(), bz = b.i32(), by = b.u8(), bid = b.u8(), fm = b.u8();
                    if (bid != 0 && digAt != null && digAt[0] == bx && digAt[1] == by && digAt[2] == bz
                            && !digRetried && System.currentTimeMillis() - digDoneAt < 700) {
                        digRetried = true;
                        final int[] q = digAt.clone();
                        later(200, () -> { if (blockAt(q[0], q[1], q[2]) > 0) send(Proto.removeBlock(selfEid, q[0], q[1], q[2])); });
                    }
                    if (bp && bid == 0 && bpRoundStart > 0 && blockAt(bx, by, bz) == 35) bpRoundEnd(System.currentTimeMillis());
                    setBlock(bx, by, bz, bid, fm & 15);
                    long[] ow = orientWait.get(key(bx, by, bz));
                    if (ow != null && bid == (int) ow[3]) {
                        yawLearn.computeIfAbsent(orientGroup(bid), q -> new HashMap<>()).put((int) ow[2], fm & 15);
                        orientWait.remove(key(bx, by, bz));
                    }
                }
                break;
            }
            case Proto.EXPLODE: {
                float ex = b.f32(), ey = b.f32(), ez = b.f32(); b.f32(); int n = b.i32();
                int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
                for (int i = 0; i < n && b.left() >= 3; i++) setBlock(bx + (byte) b.u8(), by + (byte) b.u8(), bz + (byte) b.u8(), 0, 0);
                event("[мир] взрыв в " + bx + " " + by + " " + bz);
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float pp = b.f32();
                if (e == selfEid || e == 0) {
                    corrections++; lastCorrection = now();
                    lastFix.add(String.format(Locale.ROOT, "%.2f,%.2f,%.2f->%.2f,%.2f,%.2f", x, y, z, px, py - 1.62, pz)); if (lastFix.size() > 6) lastFix.remove(0);
                    if (Math.abs(px - x) + Math.abs(py - 1.62 - y) + Math.abs(pz - z) > 0.3) bigCorrections++;
                    x = px; y = py - 1.62; z = pz; vy = 0;
                    if (Math.abs(pyaw) > 0.001f) { yaw = pyaw; pitch = pp; }
                } else {
                    Ent en = ents.get(e);
                    if (en != null) { en.x = px; en.y = py - 1.62; en.z = pz; en.yaw = pyaw; en.seen = now(); }
                }
                break;
            }
            case Proto.ADD_PLAYER: {
                UUID u = new UUID(b.i64(), b.i64());
                String name = b.str().replaceAll("§.", "");
                long e = b.i64();
                if (e == selfEid) break;
                Ent en = new Ent(); en.id = e; en.player = true; en.name = name; en.type = 63;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32(); b.f32(); b.f32(); b.f32(); en.yaw = b.f32();
                en.seen = now(); ents.put(e, en);
                break;
            }
            case Proto.ADD_ENTITY: {
                long e = b.i64(); int type = b.i32() & 0xff;
                Ent en = new Ent(); en.id = e; en.type = type;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32(); b.f32(); b.f32(); b.f32(); en.yaw = b.f32(); b.f32();
                try { en.name = Proto.readMetadata(b); } catch (Exception ignored) {}
                en.seen = now(); ents.put(e, en);
                break;
            }
            case Proto.ADD_ITEM_ENTITY: {
                long e = b.i64(); int[] it = Proto.readSlot(b);
                Ent en = new Ent(); en.id = e; en.type = 64; en.item = true; en.it = it;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32(); en.seen = now(); ents.put(e, en);
                break;
            }
            case Proto.REMOVE_PLAYER: case Proto.REMOVE_ENTITY: ents.remove(b.i64()); break;
            case Proto.TAKE_ITEM_ENTITY: { b.i64(); ents.remove(b.i64()); break; }
            case Proto.MOVE_ENTITY: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 32; i++) {
                    long e = b.i64(); float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); b.f32();
                    Ent en = ents.get(e);
                    if (en != null) { en.x = px; en.y = en.player ? py - 1.62 : py; en.z = pz; en.yaw = pyaw; en.seen = now(); }
                }
                break;
            }
            case Proto.TEXT: {
                int type = b.u8(); String src = "", msg;
                if (type == Proto.TEXT_CHAT || type == Proto.TEXT_POPUP) { src = b.str(); msg = b.str(); }
                else if (type == Proto.TEXT_TRANSLATION) {
                    msg = b.str(); int c = b.u8(); StringBuilder sb = new StringBuilder(msg);
                    for (int i = 0; i < c; i++) sb.append(i == 0 ? " [" : ", ").append(b.str());
                    if (c > 0) sb.append(']');
                    msg = sb.toString();
                } else msg = b.str();
                if (type == Proto.TEXT_TIP || type == Proto.TEXT_POPUP) break;
                src = src.replaceAll("§.", ""); msg = msg.replaceAll("§.", "");
                if (src.equalsIgnoreCase(nick)) break;
                if (password != null && src.isEmpty() && msg.contains("не зарегистрирован")) later(500, () -> send(Proto.text(nick, "/reg " + password)));
                event(src.isEmpty() ? "[сервер] " + msg : "<" + src + "> " + msg);
                if (sbAuto && src.isEmpty() && msg.contains("запомни")) later(2500, () -> event("[стройка] " + snapCmd(8, 9).replaceAll("\\(выше пола.*", "")));
                if (sbAuto && src.isEmpty() && msg.contains("Строительство началось") && !building) later(100, () -> { if (!building && !snap.isEmpty()) event("[стройка] " + command("build")); });
                if (building && src.isEmpty() && (msg.contains("Постройка совпала") || msg.contains("наблюдател"))) { building = false; flying = false; flyY = Double.NaN; scaffold.clear(); task = "раунд окончен"; }
                if (bp && src.isEmpty() && (msg.contains("победа остаётся") || msg.contains("Вы покинули игру") || msg.contains("наблюдатель"))) { bp = false; path = null; walkUntil = 0; task = "игра окончена"; event("[игра] конец игры — режим Block Party выключен"); }
                break;
            }
            case Proto.SET_HEALTH: {
                int h = b.i32();
                if (h < health) event("[бот] получил урон: здоровье " + h);
                health = h; if (h <= 0 && !dead) { dead = true; event("[бот] умер"); stopAll(); }
                break;
            }
            case Proto.UPDATE_ATTRIBUTES: {
                long e = b.i64(); int n = b.u16();
                for (int i = 0; i < n && b.left() > 12; i++) {
                    b.f32(); b.f32(); float val = b.f32(); String name = b.str();
                    if (e != selfEid && e != 0) continue;
                    if (name.endsWith("health")) {
                        int h = (int) Math.ceil(val);
                        if (h < health) event("[бот] получил урон: здоровье " + h);
                        health = h; if (h <= 0 && !dead) { dead = true; event("[бот] умер"); stopAll(); }
                    } else if (name.endsWith("hunger")) food = (int) val;
                    else if (name.endsWith("level")) xpLevel = val;
                }
                break;
            }
            case Proto.RESPAWN: {
                x = b.f32(); y = b.f32() - 1.62; z = b.f32(); vy = 0;
                break;
            }
            case Proto.SET_PLAYER_GAMETYPE: gamemode = b.i32(); break;
            case Proto.CONTAINER_SET_CONTENT: {
                int win = b.u8(); int n = b.u16();
                List<int[]> sl = new ArrayList<>();
                for (int i = 0; i < n; i++) sl.add(Proto.readSlot(b));
                if (win == 0) {
                    for (int i = 0; i < 36; i++) inv[i] = i < sl.size() ? sl.get(i) : new int[]{0, 0, 0, 0};
                    if (b.left() >= 2) {
                        int hn = b.u16();
                        for (int i = 0; i < hn && b.left() >= 4; i++) { int v = b.i32(); if (i < 9) hotbar[i] = v >= 9 ? v - 9 : (v >= 0 ? v : i); }
                    }
                } else if (win == 0x78) {
                    for (int i = 0; i < 4; i++) armor[i] = i < sl.size() ? sl.get(i) : new int[]{0, 0, 0, 0};
                }
                break;
            }
            case Proto.CONTAINER_SET_SLOT: {
                int win = b.u8(); int slot = b.s16(); b.s16(); int[] it = Proto.readSlot(b);
                if (win == 0 && slot >= 0 && slot < 36) inv[slot] = it;
                break;
            }
            case Proto.MOB_EQUIPMENT: {
                long e = b.i64(); Proto.readSlot(b); int slot = b.u8(), sel = b.u8();
                if ((e == selfEid || e == 0) && sel < 9) { held = sel; if (slot >= 9 && slot < 45) hotbar[sel] = slot - 9; }
                break;
            }
            default: break;
        }
    }

    void sign(int x, int y, int z, Map<?, ?> t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) { Object v = t.get("Text" + i); if (v != null && !v.toString().isEmpty()) { if (sb.length() > 0) sb.append(" / "); sb.append(v.toString().replaceAll("§.", "")); } }
        if (sb.length() > 0) signs.put(key(x, y, z), sb.toString());
    }

    static int intOf(Object o, int d) { return o instanceof Number ? ((Number) o).intValue() : d; }

    static long now() { return System.currentTimeMillis(); }

    // ------------------------------------------------------------ отложенные действия
    static final class Later { long at; Runnable r; }
    final List<Later> laters = new ArrayList<>();
    synchronized void later(long ms, Runnable r) { Later l = new Later(); l.at = now() + ms; l.r = r; laters.add(l); }

    // ------------------------------------------------------------ задачи
    List<int[]> path; int pathI; int[] goal; long stuckSince; double lastProgX, lastProgZ; int replans;
    String followName; long followReplan; boolean direct;
    // SpeedBuilders: запомненная постройка
    final Map<Long, int[]> snap = new LinkedHashMap<>(); boolean building, allowFly = true, sbAuto; long buildNext; int buildDone, buildFail;
    final Map<Long, Integer> buildTries = new HashMap<>();
    boolean bp, bpFlip, sneaking, sprinting, runMode; double[] bpGo, bpCenter; int bpId = -1, bpMeta = -1; boolean bpHold; long bpPlan, bpWander, bpRoundStart, bpLen, bpJumped; int[] bpTarget; String bpLast = "";
    int[] digAt; long digStart, digNeed, digDoneAt; boolean digRetried;
    long attackTarget; long lastAttack;
    double walkDirX, walkDirZ; long walkUntil;
    String task = "ничего";

    synchronized void stopAll() { flyWay = null; building = false; flying = false; flyY = Double.NaN; path = null; goal = null; followName = null; digAt = null; attackTarget = 0; walkUntil = 0; task = "ничего"; }

    // ------------------------------------------------------------ тик 20 Гц
    long lastMoveSent, lastMovedAt; double sentX, sentY, sentZ; float sentYaw, sentPitch;

    void tickLoop() {
        long next = now();
        while (true) {
            long t0 = System.nanoTime();
            try { synchronized (this) { tick(); } } catch (Throwable t) { log("тик: " + t); }
            long ms = (System.nanoTime() - t0) / 1_000_000; if (ms > 40) slowTicks++;
            next += 50;
            long s = next - now();
            if (s > 0) try { Thread.sleep(s); } catch (InterruptedException ignored) {}
            else next = now();
        }
    }

    /** Для офлайн-тестов. */
    synchronized void tickForTest() { spawned = true; tick(); }

    void tick() {
        long t = now();
        List<Later> due = new ArrayList<>();
        for (Iterator<Later> it = laters.iterator(); it.hasNext(); ) { Later l = it.next(); if (l.at <= t) { it.remove(); due.add(l); } }
        for (Later l : due) try { l.r.run(); } catch (Exception e) { log("later: " + e); }
        if (!spawned || dead) return;
        if (chunks.get(ck((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) == null) return; // ждём чанк под ногами

        double mx = 0, mz = 0; boolean wantJump = false; double speed = 0.2;

        // следование за игроком
        if (followName != null) {
            Ent p = findPlayer(followName);
            if (p == null) { if (path == null) task = "ищу " + followName; }
            else {
                double d = dist(p.x, p.y, p.z);
                if (d > 3 && t - followReplan > 1500) {
                    followReplan = t;
                    List<int[]> np = findPath(new int[]{(int) Math.floor(p.x), (int) Math.floor(p.y + 0.1), (int) Math.floor(p.z)}, 2);
                    if (np != null && !np.isEmpty()) { path = np; pathI = 0; direct = false; } else { path = null; direct = true; }
                } else if (d <= 2.5) { path = null; direct = false; }
                if (direct && d > 2.5 && path == null) {           // пути нет — идём напрямую и прыгаем
                    double dx = p.x - x, dz = p.z - z, hd = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                    mx = dx / hd; mz = dz / hd;
                    if (blockedAhead(mx, mz)) wantJump = true;
                }
                lookAt(p.x, p.y + 1.5, p.z);
            }
        }

        if (bp) bpTick(t);
        if (building) buildTick(t);

        // путь
        if (path != null) {
            if (pathI >= path.size()) { path = null; if (followName == null && !bp) { task = "пришёл"; event("[бот] пришёл в " + fmt()); } }
            else {
                int[] w = path.get(pathI);
                double tx = w[0] + 0.5, tz = w[2] + 0.5;
                double dx = tx - x, dz = tz - z, hd = Math.sqrt(dx * dx + dz * dz);
                if (hd < 0.3 && Math.abs(y - w[1]) < 1.2) { pathI++; }
                else {
                    mx = dx / Math.max(hd, 1e-6); mz = dz / Math.max(hd, 1e-6);
                    if (hd < 0.2) { mx *= hd / 0.2; mz *= hd / 0.2; }
                    if (w[1] > Math.floor(y + 0.01) && hd < 1.6) wantJump = true;
                    if (blockedAhead(mx, mz)) wantJump = true;
                    if (followName == null) setYawTo(dx, dz);
                }
                // застряли?
                if (Math.abs(x - lastProgX) + Math.abs(z - lastProgZ) > 0.5) { lastProgX = x; lastProgZ = z; stuckSince = t; }
                else if (t - stuckSince > 2500) {
                    stuckSince = t;
                    if (goal != null && replans++ < 4) {
                        List<int[]> np = findPath(goal, 1);
                        if (np != null) { path = np; pathI = 0; } else { path = null; event("[бот] застрял, пути нет"); task = "застрял"; }
                    } else if (followName == null) { path = null; if (!bp) { event("[бот] застрял у " + fmt()); task = "застрял"; } }
                    else wantJump = true;
                }
            }
        } else if (walkUntil > t) {
            mx = walkDirX; mz = walkDirZ;
            if (blockedAhead(mx, mz)) wantJump = true;
        }

        // копание
        if (digAt != null) {
            lookAt(digAt[0] + 0.5, digAt[1] + 0.5, digAt[2] + 0.5);
            if (t % 250 < 50) send(Proto.animate(selfEid, 1));
            if (t - digStart >= digNeed) {
                send(Proto.action(selfEid, Proto.ACTION_STOP_BREAK, digAt[0], digAt[1], digAt[2], 1));
                send(Proto.removeBlock(selfEid, digAt[0], digAt[1], digAt[2]));
                digDoneAt = t; digRetried = false;
                int[] was = digAt;
                event("[бот] сломал " + name(blockAt(was[0], was[1], was[2])) + " в " + was[0] + " " + was[1] + " " + was[2]);
                setBlock(was[0], was[1], was[2], 0, 0);
                later(800, () -> { if (digAt == was) digAt = null; });
                digAt = new int[]{was[0], was[1], was[2]}; // для повтора, если сервер вернёт блок
                final int[] keep = digAt;
                later(900, () -> { if (digAt == keep) { digAt = null; task = "ничего"; } });
                digNeed = Long.MAX_VALUE;
            }
        }

        // атака
        if (attackTarget != 0) {
            Ent e = ents.get(attackTarget);
            if (e == null) { attackTarget = 0; task = "ничего"; event("[бот] цель пропала (убита или ушла)"); }
            else {
                double d = dist(e.x, e.y, e.z);
                lookAt(e.x, e.y + 0.9, e.z);
                if (d > 3.2) {
                    if (t - followReplan > 1500) {
                        followReplan = t;
                        List<int[]> np = findPath(new int[]{(int) Math.floor(e.x), (int) Math.floor(e.y + 0.1), (int) Math.floor(e.z)}, 2);
                        if (np != null) { path = np; pathI = 0; }
                    }
                } else {
                    path = null;
                    if (t - lastAttack > 550) { lastAttack = t; send(Proto.animate(selfEid, 1)); send(Proto.interact(2, e.id)); }
                }
            }
        }

        // бег: в Block Party, когда бежим к цвету, и по команде run
        boolean wantSprint = (bp && path != null) || (runMode && (path != null || walkUntil > t)) ;
        if (sneaking) wantSprint = false;
        if (wantSprint != sprinting) { sprinting = wantSprint; send(Proto.action(selfEid, wantSprint ? Proto.ACTION_START_SPRINT : Proto.ACTION_STOP_SPRINT, 0, 0, 0, 0)); }
        if (sprinting) speed = 0.28; else if (sneaking) speed = 0.065;
        // бег с прыжками: на прямых участках пути длиннее 3 блоков
        if (sprinting && path != null && pathI < path.size()) {
            int[] last = path.get(path.size() - 1);
            double left = Math.hypot(last[0] + 0.5 - x, last[2] + 0.5 - z);
            boolean flat = true;
            for (int i = pathI; i < Math.min(path.size(), pathI + 3); i++) if (path.get(i)[1] != (int) Math.floor(y + 0.01)) flat = false;
            if (left > 3 && flat && onGround && !bpHold && !blockedAhead(mx, mz) && !collides(x, y + 1.0, z)) wantJump = true;
            if (!onGround && left > 1.5) speed = 0.32;
        }
        // смотреть по ходу движения, если ни на что не смотрим специально
        if ((mx != 0 || mz != 0) && followName == null && attackTarget == 0 && digAt == null && (Math.abs(mx) + Math.abs(mz)) > 0.3) { setYawTo(mx, mz); pitch = 0; }
        physics(mx * speed, mz * speed, wantJump);

        boolean changed = Math.abs(x - sentX) > 1e-3 || Math.abs(y - sentY) > 1e-3 || Math.abs(z - sentZ) > 1e-3
                || Math.abs(yaw - sentYaw) > 0.5f || Math.abs(pitch - sentPitch) > 0.5f;
        if (Math.abs(x - sentX) > 0.02 || Math.abs(z - sentZ) > 0.02 || Math.abs(y - sentY) > 0.02) lastMovedAt = t;
        if (changed || t - lastMoveSent > 1000) {
            lastMoveSent = t; sentX = x; sentY = y; sentZ = z; sentYaw = yaw; sentPitch = pitch;
            send(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
        }
    }

    // ------------------------------------------------------------ SpeedBuilders
    String snapCmd(int r, int up) {
        snap.clear();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
        snapFloor = by - 1;
        for (int dy = -1; dy <= up; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int id = blockAt(bx + dx, by + dy, bz + dz);
            if (id > 0) snap.put(key(bx + dx, by + dy, bz + dz), new int[]{bx + dx, by + dy, bz + dz, id, metaAt(bx + dx, by + dy, bz + dz)});
        }
        Map<String, Integer> cnt = new TreeMap<>();
        for (int[] b : snap.values()) if (b[1] >= by - 1) cnt.merge(name(b[3]) + (b[3] == 35 || b[3] == 159 || b[3] == 171 ? "(" + COLORS[b[4] & 15] + ")" : ""), 1, Integer::sum);
        return "запомнил " + snap.size() + " блоков (выше пола: " + cnt + ")";
    }

    /** Что ещё не совпадает с запомненным. */
    List<int[]> buildTodo() {
        List<int[]> todo = new ArrayList<>();
        for (int[] b : snap.values()) {
            int cur = blockAt(b[0], b[1], b[2]);
            if (cur < 0) continue;
            if (cur == b[3] && oriented(b[3]) && !needsMeta(b[3])) continue;   // поворотный уже стоит — не ломаем (запасных нет)
            if (cur != b[3] || (needsMeta(b[3]) && metaAt(b[0], b[1], b[2]) != b[4])) {
                // пол меняем, только если нам выдали такой блок (узор на полу — часть постройки)
                if (b[1] <= snapFloor && findItem(b[3], b[4]) < 0) continue;
                todo.add(b);
            }
        }
        return todo;
    }

    static final Set<Integer> STAIRS = new HashSet<>(Arrays.asList(53, 67, 108, 109, 114, 128, 134, 135, 136, 156, 163, 164, 180, 203));
    static final Set<Integer> AXIS = new HashSet<>(Arrays.asList(17, 162, 170, 155, 216));
    static final Set<Integer> FACING = new HashSet<>(Arrays.asList(86, 91, 61, 62, 54, 146, 130, 23, 125, 33, 29, 65));
    /** Блок, у которого метадата — это поворот (ставится в зависимости от взгляда/грани). */
    static boolean oriented(int id) { return STAIRS.contains(id) || AXIS.contains(id) || FACING.contains(id); }
    final Map<Long, Integer> orientTry = new HashMap<>();
    final Map<Long, long[]> orientWait = new HashMap<>();   // {время, ожидаемая мета, yawIdx, id}
    final Map<String, Map<Integer, Integer>> yawLearn = new HashMap<>();   // группа -> (поворот взгляда -> мета, которую поставил сервер)
    static String orientGroup(int id) { return STAIRS.contains(id) ? "stairs" : (id == 86 || id == 91) ? "pumpkin" : "f" + id; }
    int yawFor(int id, int meta, int fallback) {
        Map<Integer, Integer> m = yawLearn.get(orientGroup(id));
        if (m != null) {
            for (Map.Entry<Integer, Integer> e : m.entrySet()) if ((e.getValue() & 3) == (meta & 3)) return e.getKey();
            // угадываем по выученному сдвигу: поворот на 90° меняет мету по кругу
            if (!m.isEmpty()) { for (int k = 0; k < 4; k++) if (!m.containsKey(k)) return k; }
        }
        return fallback;
    }
    long scaffoldSince, stuckAt; double stuckX, stuckY, stuckZ; int stuckDone; int lastYawIdx; String why = ""; final List<int[]> scaffold = new ArrayList<>(); int equippedSlot = -1; long equippedAt; boolean secondPass; double flyLastY; long flyStuckAt;   // поставили поворотный блок: {время, ожидаемая мета}

    static boolean needsMeta(int id) { return id == 35 || id == 159 || id == 171 || id == 95 || id == 160; }

    int findItem(int id, int meta) {
        int best = -1;
        for (int i = 0; i < 36; i++) if (inv[i][0] == id) { if ((inv[i][2] & 15) == (meta & 15)) return i; if (best < 0) best = i; }
        if (best < 0 && (id == 43)) for (int i = 0; i < 36; i++) if (inv[i][0] == 44) return i;
        return needsMeta(id) && id != 44 && id != 158 ? -1 : best;
    }

    int snapFloor = -999; int[] buildSpot; final Set<String> missing = new HashSet<>();

    /** Свободное место рядом с постройкой, но вне её. */
    int[] chooseSpot() {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE;
        for (int[] b : buildTodo()) { minX = Math.min(minX, b[0]); maxX = Math.max(maxX, b[0]); minZ = Math.min(minZ, b[2]); maxZ = Math.max(maxZ, b[2]); minY = Math.min(minY, b[1]); }
        if (minX == Integer.MAX_VALUE) return null;
        int cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2;
        int[] best = null; double bd = 1e9;
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) for (int fy = minY - 1; fy <= minY + 1; fy++) {
            int px = cx + dx, pz = cz + dz;
            if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) continue;      // внутри постройки
            if (!standable(px, fy, pz)) continue;
            if (snap.containsKey(key(px, fy, pz)) && snap.get(key(px, fy, pz))[3] != 0) continue;
            double edge = Math.max(Math.max(minX - px, px - maxX), Math.max(minZ - pz, pz - maxZ));
            double d = Math.abs(edge - 1) * 3 + Math.hypot(px - x, pz - z) * 0.3;
            if (d < bd) { bd = d; best = new int[]{px, fy, pz}; }
        }
        return best;
    }

    void buildTick(long t) {
        if (followFlyWay(t)) { why = "лечу по пути " + flyWayI + "/" + (flyWay == null ? 0 : flyWay.size()); return; }
        if (t < buildNext) { why = "ждём " + (buildNext - t); return; }
        buildNext = t + 110;
        if (buildSpot == null) {
            buildSpot = chooseSpot();
            if (buildSpot != null) {
                int fx = (int) Math.floor(x), fz = (int) Math.floor(z);
                if (fx != buildSpot[0] || fz != buildSpot[2]) {
                    List<int[]> np = findPath(buildSpot, 1);
                    path = np != null && !np.isEmpty() ? np : new ArrayList<>(Collections.singletonList(buildSpot)); pathI = 0;
                    buildNext = t + 200; why = "иду на место " + Arrays.toString(buildSpot); return;
                }
            } else buildSpot = new int[]{(int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)};
        }
        if (path != null) { why = "ещё иду, путь " + path.size(); return; }
        // общий детектор застревания: если долго не двигаемся и ничего не ставим — отлетаем от центра постройки
        if (Math.abs(x - stuckX) + Math.abs(y - stuckY) + Math.abs(z - stuckZ) > 0.3 || buildDone != stuckDone) { stuckX = x; stuckY = y; stuckZ = z; stuckDone = buildDone; stuckAt = t; }
        else if (t - stuckAt > 1800) {
            List<int[]> td = buildTodo();
            double cx = 0, cz = 0; for (int[] q : td) { cx += q[0] + 0.5; cz += q[2] + 0.5; }
            cx /= Math.max(1, td.size()); cz /= Math.max(1, td.size());
            double dx = x - cx, dz = z - cz, hd = Math.hypot(dx, dz);
            if (hd < 0.2) { double a = Math.random() * Math.PI * 2; dx = Math.cos(a); dz = Math.sin(a); hd = 1; }
            flying = allowFly; flyY = Double.NaN;
            walkDirX = dx / hd; walkDirZ = dz / hd; walkUntil = t + 600; stuckAt = t;
            buildNext = t + 600; why = "застрял, отлетаю в сторону";
            return;
        }
        List<int[]> todo = buildTodo();
        if (todo.isEmpty() && !scaffold.isEmpty()) { removeScaffoldStep(t); return; }
        if (pickupAt != null) {                              // подобрать блок от сломанной опоры
            double d = Math.hypot(pickupAt[0] + 0.5 - x, pickupAt[2] + 0.5 - z);
            if (t - pickupSince > 1500 || d < 0.4) pickupAt = null;
            else { flying = allowFly; flyY = pickupAt[1]; walkDirX = (pickupAt[0] + 0.5 - x) / Math.max(d, 0.01); walkDirZ = (pickupAt[2] + 0.5 - z) / Math.max(d, 0.01); walkUntil = t + 100; why = "подбираю блок"; return; }
        }
        if (todo.isEmpty()) { building = false; flying = false; flyY = Double.NaN; task = "построил"; event("[стройка] готово: всё совпадает с образцом"); return; }
        // сервер не прислал подтверждение поворота — считаем, что встал как надо
        for (Iterator<Map.Entry<Long, long[]>> it = orientWait.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, long[]> e = it.next();
            if (t - e.getValue()[0] < 700) continue;
            it.remove();
            int[] b = snap.get(e.getKey());
            if (b != null && blockAt(b[0], b[1], b[2]) == b[3] && metaAt(b[0], b[1], b[2]) == (b[4] ^ 15)) setBlock(b[0], b[1], b[2], b[3], b[4]);
        }
        boolean any = false;
        for (int[] b : todo) if (buildTries.getOrDefault(key(b[0], b[1], b[2]), 0) <= (oriented(b[3]) ? 14 : 8)) { any = true; break; }
        if (!any && !secondPass) { secondPass = true; buildTries.clear(); orientTry.clear(); buildSpot = null; why = "второй заход"; return; }
        if (!any) { building = false; flying = false; flyY = Double.NaN; task = "стройка: больше не могу"; event("[стройка] осталось " + todo.size() + " блоков, поставить не получилось"); return; }
        // сначала нижние, потом ближние
        todo.sort(Comparator.<int[]>comparingInt(b -> b[1]).thenComparingDouble(b -> dist(b[0] + 0.5, b[1] - 1, b[2] + 0.5)));
        int[] overlapFirst = null, farFirst = null;
        for (int[] b : todo) {
            long k = key(b[0], b[1], b[2]);
            if (buildTries.getOrDefault(k, 0) > (oriented(b[3]) ? 14 : 8)) continue;
            int cur = blockAt(b[0], b[1], b[2]);
            if (cur > 0 && !liquid(cur)) {                      // не тот блок — ломаем
                if (dist(b[0] + 0.5, b[1] + 0.5 - 1.62, b[2] + 0.5) > 5) { moveNear(b); return; }
                lookAt(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5);
                send(Proto.action(selfEid, Proto.ACTION_START_BREAK, b[0], b[1], b[2], 1));
                send(Proto.removeBlock(selfEid, b[0], b[1], b[2]));
                setBlock(b[0], b[1], b[2], 0, 0);
                buildTries.merge(k, 1, Integer::sum);
                return;
            }
            // опора
            int face = -1, nx = 0, ny = 0, nz = 0;
            int ot = orientTry.getOrDefault(k, 0);
            List<Integer> faces = new ArrayList<>();
            for (int f : new int[]{1, 2, 3, 4, 5, 0}) {
                int nid = blockAt(b[0] - FACE[f][0], b[1] - FACE[f][1], b[2] - FACE[f][2]);
                if (nid > 0 && solid(nid)) faces.add(f);
            }
            if (faces.isEmpty()) {                               // висит в воздухе — ставим временную опору рядом
                if (todoHasSupportable(todo)) continue;          // сначала то, что можно поставить сразу
                int[] sc = scaffoldFor(b);
                if (sc == null) continue;
                int sslot = scaffoldItem(todo);
                if (sslot < 0) continue;
                b = new int[]{sc[0], sc[1], sc[2], inv[sslot][0], inv[sslot][2]};
                k = key(b[0], b[1], b[2]);
                faces.clear();
                for (int f : new int[]{1, 2, 3, 4, 5, 0}) {
                    int nid = blockAt(b[0] - FACE[f][0], b[1] - FACE[f][1], b[2] - FACE[f][2]);
                    if (nid > 0 && solid(nid)) faces.add(f);
                }
                if (faces.isEmpty()) continue;
                boolean known = false; for (int[] q : scaffold) if (q[0] == sc[0] && q[1] == sc[1] && q[2] == sc[2]) known = true;
                if (!known) scaffold.add(new int[]{sc[0], sc[1], sc[2], 0});
                why = "ставлю опору " + sc[0] + "," + sc[1] + "," + sc[2];
            }
            int pick = faces.get(0);
            if (AXIS.contains(b[3])) {                           // ось бревна/сена = ось грани, по которой кликаем
                int axis = (b[4] >> 2) & 3;                      // 0 Y, 1 X, 2 Z
                int[] want = axis == 0 ? new int[]{1, 0} : axis == 1 ? new int[]{4, 5} : new int[]{2, 3};
                List<Integer> good = new ArrayList<>();
                for (int f : faces) if (f == want[0] || f == want[1]) good.add(f);
                List<Integer> use = good.isEmpty() ? faces : good;
                pick = use.get(ot % use.size());
            } else if (STAIRS.contains(b[3]) && (b[4] & 4) != 0) { // перевёрнутые ступеньки — не сверху
                for (int f : faces) if (f != 1) { pick = f; break; }
            }
            face = pick; nx = b[0] - FACE[face][0]; ny = b[1] - FACE[face][1]; nz = b[2] - FACE[face][2];
            int slot = findItem(b[3], b[4]);
            if (slot < 0) { if (missing.add(b[3] + ":" + b[4])) event("[стройка] нет блока " + name(b[3]) + ":" + b[4]); buildTries.merge(k, 9, Integer::sum); continue; }
            // не ставить блок в себя (по габаритам тела, а не по клетке)
            if (x - 0.3 < b[0] + 1 && x + 0.3 > b[0] && z - 0.3 < b[2] + 1 && z + 0.3 > b[2] && y < b[1] + 1 && y + 1.8 > b[1]) {
                if (overlapFirst == null) overlapFirst = b;          // мешаем сами себе — сначала поставим другие
                continue;
            }
            double hdist = Math.hypot(b[0] + 0.5 - x, b[2] + 0.5 - z);
            double reach = dist(b[0] + 0.5, b[1] + 0.5 - 1.62, b[2] + 0.5);
            if (hdist > 2.6) {                                   // в стороне: ставим, только если и так дотягиваемся
                if (reach > 3.8) { if (farFirst == null) farFirst = b; continue; }
            } else if (reach > 3.8) {
                if (!allowFly) { if (farFirst == null) farFirst = b; continue; }
                flying = true; flyY = Math.max(b[1] - 1.0, groundBelow());   // рядом по горизонтали — меняем высоту
                if (Math.abs(y - flyY) > 0.3) {
                    why = "лечу на " + flyY;
                    if (Math.abs(y - flyLastY) > 0.05) { flyLastY = y; flyStuckAt = t; }
                    else if (t - flyStuckAt > 700) { flyY = y; flyStuckAt = t; if (farFirst == null) farFirst = b; continue; }
                    return;
                }
                if (dist(b[0] + 0.5, b[1] + 0.5 - 1.62, b[2] + 0.5) > 3.8) { if (farFirst == null) farFirst = b; continue; }
            }
            path = null; walkUntil = 0;
            if (t - lastMovedAt < 250) { why = "жду, пока сервер увидит, где я"; return; }
            why = "ставлю " + b[0] + "," + b[1] + "," + b[2];
            if (hotbar[held] != slot || slot != equippedSlot || t - equippedAt > 3000) {   // взять в руку (сервер мог переключить сам)
                int hi = -1;
                for (int i = 0; i < 9; i++) if (hotbar[i] == slot) { hi = i; break; }
                if (hi < 0 && slot < 9) hi = slot;
                if (hi >= 0) { held = hi; hotbar[hi] = slot; } else hotbar[held] = slot;
                equip(); equippedSlot = slot; equippedAt = t;
                buildNext = t + 150; why = "беру в руку слот " + slot; return;   // дать серверу переключить
            }
            lookAt(nx + 0.5 + FACE[face][0] * 0.5, ny + 0.5 + FACE[face][1] * 0.5, nz + 0.5 + FACE[face][2] * 0.5);
            float clickY = 0.5f + FACE[face][1] * 0.5f;
            if (STAIRS.contains(b[3]) || FACING.contains(b[3])) {
                int yi = yawFor(b[3], b[4], 0);
                yaw = yi * 90f; lastYawIdx = yi;
                if (STAIRS.contains(b[3]) && face != 0 && face != 1) clickY = (b[4] & 4) != 0 ? 0.8f : 0.2f;
            }
            send(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
            send(Proto.animate(selfEid, 1));
            send(Proto.useItem(nx, ny, nz, face, 0.5f + FACE[face][0] * 0.5f, clickY, 0.5f + FACE[face][2] * 0.5f,
                    (float) x, (float) (y + 1.62), (float) z, inv[slot], peProtocol, held));
            if (oriented(b[3])) orientTry.merge(k, 1, Integer::sum);
            setBlock(b[0], b[1], b[2], b[3], oriented(b[3]) ? (b[4] ^ 15) : b[4]);   // оптимистично; у поворотных ждём ответ сервера
            if (oriented(b[3])) { buildNext = t + 450; orientWait.put(k, new long[]{t, b[4], lastYawIdx, b[3]}); }
            buildTries.merge(k, 1, Integer::sum);
            buildDone++;
            return;
        }
        // ничего не поставили: если стоят опоры — уберём их (вернём блоки), потом продолжим
        if (farFirst == null && overlapFirst == null && !scaffold.isEmpty()) { removeScaffoldStep(t); return; }
        // ничего не поставили: либо всё далеко, либо мешаем сами себе
        if (farFirst != null) { moveNear(farFirst); why = "всё рядом готово, лечу к " + farFirst[0] + "," + farFirst[1] + "," + farFirst[2]; return; }
        if (overlapFirst != null) { stepAside(overlapFirst); why = "мешаю себе, отхожу"; return; }
    }

    /** Высота ног, если стоять на земле под нами. */
    double groundBelow() {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        for (int yy = (int) Math.floor(y); yy > y - 12; yy--) { int id = blockAt(bx, yy - 1, bz); if (id != 0 && (id < 0 || solid(id))) return yy; }
        return y - 12;
    }

    List<int[]> flyWay; int flyWayI; int[] flyWayTarget;
    int[] pickupAt; long pickupSince;

    void removeScaffoldStep(long t) {
        int[] sc = scaffold.get(0);
        if (blockAt(sc[0], sc[1], sc[2]) <= 0) { scaffold.remove(0); scaffoldSince = 0; return; }
        if (scaffoldSince == 0) scaffoldSince = t;
        if (t - scaffoldSince > 4000) { scaffold.remove(0); scaffoldSince = 0; return; }   // не вышло — оставляем как есть
        if (dist(sc[0] + 0.5, sc[1] + 0.5 - 1.62, sc[2] + 0.5) > 4.5) { moveNear(sc); why = "иду ломать опору"; return; }
        lookAt(sc[0] + 0.5, sc[1] + 0.5, sc[2] + 0.5);
        send(Proto.action(selfEid, Proto.ACTION_START_BREAK, sc[0], sc[1], sc[2], 1));
        send(Proto.removeBlock(selfEid, sc[0], sc[1], sc[2]));
        setBlock(sc[0], sc[1], sc[2], 0, 0);
        pickupAt = new int[]{sc[0], sc[1], sc[2]}; pickupSince = t;
        buildTries.clear(); missing.clear();               // блок вернётся — можно снова пробовать недостающее
        buildNext = t + 300; why = "ломаю опору";
    }

    boolean airCell(int cx, int cy, int cz) { return !solidAt(cx, cy, cz) && !solidAt(cx, cy + 1, cz); }

    /** Поиск пути по воздуху (BFS по клеткам, где помещается тело) к месту, откуда дотягиваемся до блока. */
    List<int[]> flyPath(int[] b) {
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y + 0.05), sz = (int) Math.floor(z);
        int minY = snapFloor + 1, maxY = b[1] + 4;
        for (int[] q : snap.values()) if (q[1] > snapFloor && q[3] != 0 && Math.abs(q[0] - b[0]) < 8 && Math.abs(q[2] - b[2]) < 8) maxY = Math.max(maxY, q[1] + 2);
        ArrayDeque<int[]> open = new ArrayDeque<>();
        Map<Long, Long> came = new HashMap<>();
        long start = key(sx, sy, sz);
        came.put(start, start); open.add(new int[]{sx, sy, sz});
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        int n = 0;
        while (!open.isEmpty() && n++ < 6000) {
            int[] c = open.poll();
            double ex = c[0] + 0.5 - (b[0] + 0.5), ey = c[1] + 1.62 - (b[1] + 0.5), ez = c[2] + 0.5 - (b[2] + 0.5);
            boolean overlaps = c[0] == b[0] && c[2] == b[2] && (c[1] == b[1] || c[1] + 1 == b[1]);
            if (!overlaps && Math.sqrt(ex * ex + ey * ey + ez * ez) <= 3.4) {
                LinkedList<int[]> out = new LinkedList<>();
                for (long k = key(c[0], c[1], c[2]); k != start; k = came.get(k)) out.addFirst(new int[]{kx(k), ky(k), kz(k)});
                return out;
            }
            for (int[] d : dirs) {
                int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
                if (ny < minY || ny > maxY || Math.abs(nx - b[0]) > 9 || Math.abs(nz - b[2]) > 9) continue;
                long k = key(nx, ny, nz);
                if (came.containsKey(k) || !airCell(nx, ny, nz)) continue;
                came.put(k, key(c[0], c[1], c[2])); open.add(new int[]{nx, ny, nz});
            }
        }
        return null;
    }

    /** Следовать воздушному пути; true — ещё летим. */
    boolean followFlyWay(long t) {
        if (flyWay == null) return false;
        if (flyWayI >= flyWay.size()) { flyWay = null; flyWayTarget = null; return false; }
        int[] w = flyWay.get(flyWayI);
        double dx = w[0] + 0.5 - x, dz = w[2] + 0.5 - z, hd = Math.hypot(dx, dz);
        flying = true; flyY = w[1];
        if (hd < 0.2 && Math.abs(y - w[1]) < 0.25) { flyWayI++; return true; }
        if (hd > 0.05) { walkDirX = dx / hd * Math.min(1, hd * 2.5); walkDirZ = dz / hd * Math.min(1, hd * 2.5); walkUntil = t + 100; }
        if (Math.abs(x - flyLastX) + Math.abs(y - flyLastY2) + Math.abs(z - flyLastZ) > 0.05) { flyLastX = x; flyLastY2 = y; flyLastZ = z; flyWayStuck = t; }
        else if (t - flyWayStuck > 1200) { flyWay = null; flyWayTarget = null; return false; }   // упёрлись — пересчитаем
        return true;
    }
    double flyLastX, flyLastY2, flyLastZ; long flyWayStuck;

    boolean hasSupport(int x0, int y0, int z0) {
        for (int f = 0; f < 6; f++) { int nid = blockAt(x0 - FACE[f][0], y0 - FACE[f][1], z0 - FACE[f][2]); if (nid > 0 && solid(nid)) return true; }
        return false;
    }

    /** Есть ли в списке блок, который можно поставить без опоры. */
    boolean todoHasSupportable(List<int[]> todo) {
        for (int[] q : todo) if (blockAt(q[0], q[1], q[2]) == 0 && hasSupport(q[0], q[1], q[2]) && buildTries.getOrDefault(key(q[0], q[1], q[2]), 0) <= 8 && findItem(q[3], q[4]) >= 0) return true;
        return false;
    }

    /** Клетка рядом с висящим блоком, куда можно поставить временную опору. */
    int[] scaffoldFor(int[] b) {
        int[] best = null; int bestScore = Integer.MAX_VALUE;
        for (int f = 0; f < 6; f++) {
            int nx = b[0] + FACE[f][0], ny = b[1] + FACE[f][1], nz = b[2] + FACE[f][2];
            if (blockAt(nx, ny, nz) != 0) continue;
            int[] want = snap.get(key(nx, ny, nz));
            if (want != null && want[3] != 0) continue;              // это часть постройки, а не место для опоры
            if (!hasSupport(nx, ny, nz)) continue;
            int score = f == 0 ? 0 : f == 1 ? 3 : 1;                 // лучше под блоком
            if (score < bestScore) { bestScore = score; best = new int[]{nx, ny, nz}; }
        }
        if (best != null) return best;
        // рядом опереться не на что — столбик снизу: следующий пустой блок над землёй
        for (int yy = b[1] - 1; yy >= b[1] - 8; yy--) {
            int id = blockAt(b[0], yy, b[2]);
            if (id < 0) return null;
            if (id > 0 && solid(id)) {
                int ny = yy + 1;
                if (ny >= b[1]) return null;
                int[] want = snap.get(key(b[0], ny, b[2]));
                if (want != null && want[3] != 0) return null;
                return new int[]{b[0], ny, b[2]};
            }
        }
        return null;
    }

    /** Слот с лишним блоком для опоры (которого больше, чем нужно постройке). */
    int scaffoldItem(List<int[]> todo) {
        Map<String, Integer> need = new HashMap<>();
        for (int[] q : todo) need.merge(q[3] + ":" + (q[4] & 15), 1, Integer::sum);
        int fallback = -1;
        for (int i = 0; i < 36; i++) {
            int[] it = inv[i];
            if (it[0] <= 0 || it[0] > 255 || it[1] <= 0 || !solid(it[0])) continue;
            int n = need.getOrDefault(it[0] + ":" + (it[2] & 15), 0);
            if (it[1] > n) return i;                             // сначала лишние
            if (fallback < 0) fallback = i;                      // иначе нужный: потом сломаем опору и подберём
        }
        return fallback;
    }

    void moveNear(int[] b) {
        if (allowFly) {                                    // в режиме стройки: путь по воздуху вокруг постройки
            if (flyWay != null && flyWayTarget != null && flyWayTarget[0] == b[0] && flyWayTarget[1] == b[1] && flyWayTarget[2] == b[2]) return;
            List<int[]> w = flyPath(b);
            flying = true;
            if (w != null && !w.isEmpty()) { flyWay = w; flyWayI = 0; flyWayTarget = b; path = null; }
            else {                                          // пути нет — по старинке: вверх и по прямой
                double dx = b[0] + 0.5 - x, dz = b[2] + 0.5 - z, hd = Math.max(0.01, Math.hypot(dx, dz));
                flyY = y + 1; walkDirX = dx / hd; walkDirZ = dz / hd; walkUntil = now() + 200;
            }
            return;
        }
        if (path != null) return;
        List<int[]> np = findPath(new int[]{b[0], b[1], b[2]}, 3);
        if (np != null && !np.isEmpty()) { path = np; pathI = 0; }
    }

    void stepAside(int[] b) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int fx = (int) Math.floor(x) + d[0], fz = (int) Math.floor(z) + d[1], fy = (int) Math.floor(y + 0.01);
            if (standable(fx, fy, fz) && !snap.containsKey(key(fx, fy, fz))) { path = new ArrayList<>(Collections.singletonList(new int[]{fx, fy, fz})); pathI = 0; return; }
        }
    }

    // ------------------------------------------------------------ Block Party
    static final String[] COLORS = {"белый", "оранжевый", "пурпурный", "голубой", "жёлтый", "лаймовый", "розовый", "серый",
            "светло-серый", "бирюзовый", "фиолетовый", "синий", "коричневый", "зелёный", "красный", "чёрный"};

    boolean safeAhead(double dx, double dz) {
        double nx = x + dx * 0.8, nz = z + dz * 0.8;
        int bx = (int) Math.floor(nx), bz = (int) Math.floor(nz), by = (int) Math.floor(y - 0.05);
        for (int k = 0; k <= 1; k++) if (solid(Math.max(0, blockAt(bx, by - k, bz)))) return !collides(nx, y, nz);
        return false;
    }

    int seq;   // номер текущей последовательности (join/dance/fart); stop её прерывает

    void joinStep(int my, int zz, String g, int tries) {
        later(500, () -> {
            if (seq != my) return;
            boolean there = x > -245.6 && x < -242.4 && Math.abs(z - (zz + 0.5)) < 1.2;
            if (!there && tries < 40) { if (tries == 14 || tries == 28) command("goto -244 14 " + zz); joinStep(my, zz, g, tries + 1); return; }
            if (!there) { event("[бот] не дошёл до входа в " + g); return; }
            yaw = 90; pitch = 0;
            walkDirX = -1; walkDirZ = 0; walkUntil = now() + 1500; task = "захожу в " + g;
            later(3500, () -> {
                if (seq != my) return;
                if (g.equals("bp")) command("bp");
                else if (g.equals("sb")) command("sb");
                event("[бот] зашёл в " + g);
            });
        });
    }

    void bpRoundEnd(long t) {
        long len = t - bpRoundStart; bpRoundStart = 0;
        if (len < 1500 || len > 30000) return;
        // раунды становятся короче (на 0.1-0.6 с): следующий считаем на 0.2 с короче
        bpLen = len - 200;
        event("[игра] раунд длился " + String.format(java.util.Locale.ROOT, "%.1f", len / 1000.0) + " с");
    }

    void bpTick(long t) {
        boolean tnt = false;
        for (Ent e : ents.values()) if (e.type == 65 && dist(e.x, e.y, e.z) < 5) tnt = true;
        if (path != null) tnt = false;   // к цвету бежим, приседаем уже на месте
        if (tnt != sneaking) { sneaking = tnt; send(Proto.action(selfEid, tnt ? Proto.ACTION_START_SNEAK : Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); if (tnt) event("[игра] TNT рядом — присел"); }
        int[] want = null;
        int[] h = heldItem();
        if (h[0] == 35 || h[0] == 159 || h[0] == 171) want = h;
        else for (int[] it : inv) if (it[0] == 35 || it[0] == 159 || it[0] == 171) { want = it; break; }
        int fx = (int) Math.floor(x), fy = (int) Math.floor(y - 0.05), fz = (int) Math.floor(z);
        if (want == null) {
            if (bpRoundStart > 0) bpRoundEnd(t);
            bpTarget = null;
            if (!bpLast.isEmpty()) { bpLast = ""; }
            // между раундами всё время бродим (за стояние на месте бьют), не уходя с пола
            if (path == null && (t - bpWander > 900 || walkUntil < t || !safeAhead(walkDirX, walkDirZ))) {
                bpWander = t;
                for (int k = 0; k < 8; k++) {
                    double a = Math.random() * Math.PI * 2;
                    if (bpCenter != null) { double cx = bpCenter[0] - x, cz = bpCenter[1] - z; if (Math.hypot(cx, cz) > 6 && k < 6) a = Math.atan2(cz, cx) + (Math.random() - 0.5); }
                    if (safeAhead(Math.cos(a), Math.sin(a))) { walkDirX = Math.cos(a); walkDirZ = Math.sin(a); break; }
                }
                walkUntil = t + 1000;
            }
            return;
        }
        int id = want[0] == 171 ? 35 : want[0], meta = want[2] & 15;
        String key = id + ":" + meta;
        if (!key.equals(bpLast)) { bpLast = key; bpTarget = null; event("[игра] нужен цвет: " + COLORS[meta]); bpRoundStart = t; bpJumped = 0; }
        // не успеваю добежать: прыгаю в последний момент, чтобы быть в воздухе, когда пол исчезнет
        bpHold = false;
        if (bpLen > 0 && bpRoundStart > 0 && bpJumped == 0 && !(blockAt(fx, fy, fz) == id && metaAt(fx, fy, fz) == meta)) {
            long left = bpRoundStart + bpLen - t;
            bpHold = left > 300 && left < 900;     // перед концом не прыгаем на бегу, чтобы прыжок был готов
            if (onGround && left <= 300 && left > -250) { vy = 0.42; bpJumped = t; event("[игра] не успеваю, прыгаю (осталось " + left + " мс)"); }
        }
        // стою на нужном?
        if (blockAt(fx, fy, fz) == id && metaAt(fx, fy, fz) == meta) {
            path = null;
            // стоим на своём цвете, но двигаемся: ходим по соседним блокам того же цвета или туда-сюда внутри блока
            if (t - bpWander > 450) {
                bpWander = t;
                List<int[]> same = new ArrayList<>();
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                    if (blockAt(fx + d[0], fy, fz + d[1]) == id && metaAt(fx + d[0], fy, fz + d[1]) == meta && !solidAt(fx + d[0], fy + 1, fz + d[1])) same.add(d);
                double tx, tz;
                if (!same.isEmpty() && Math.random() < 0.5) { int[] d = same.get((int) (Math.random() * same.size())); tx = fx + d[0] + 0.5; tz = fz + d[1] + 0.5; }
                else { bpFlip = !bpFlip; tx = fx + (bpFlip ? 0.75 : 0.25); tz = fz + (bpFlip ? 0.25 : 0.75); }
                bpGo = new double[]{tx, tz};
            }
            if (bpGo != null) {
                double dx = bpGo[0] - x, dz = bpGo[1] - z, hd = Math.hypot(dx, dz);
                if (hd > 0.08) { walkDirX = dx / hd * Math.min(1, hd * 2); walkDirZ = dz / hd * Math.min(1, hd * 2); walkUntil = t + 60; }
            }
            return;
        }
        if (bpTarget != null && blockAt(bpTarget[0], bpTarget[1], bpTarget[2]) == id && metaAt(bpTarget[0], bpTarget[1], bpTarget[2]) == meta && t - bpPlan < 800) return;
        bpPlan = t;
        // ближайший блок нужного цвета на уровне пола
        int floorY = blockAt(fx, fy, fz) > 0 ? fy : fy - 1;
        int[] best = null; double bd = 1e9;
        for (int dy = -1; dy <= 1; dy++) for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
            int bx = fx + dx, by = floorY + dy, bz = fz + dz;
            if (blockAt(bx, by, bz) != id || metaAt(bx, by, bz) != meta) continue;
            if (solidAt(bx, by + 1, bz) || solidAt(bx, by + 2, bz)) continue;
            double d = Math.abs(dx) + Math.abs(dz) + Math.abs(dy) * 3;
            if (d < bd) { bd = d; best = new int[]{bx, by, bz}; }
        }
        if (best == null) return;
        bpTarget = best;
        walkUntil = 0;
        List<int[]> np = findPath(new int[]{best[0], best[1] + 1, best[2]}, 1);
        if (np != null && !np.isEmpty()) { path = np; pathI = 0; goal = new int[]{best[0], best[1] + 1, best[2]}; replans = 0; stuckSince = t; }
        else { // напрямую
            double dx = best[0] + 0.5 - x, dz = best[2] + 0.5 - z, hd = Math.max(1e-6, Math.hypot(dx, dz));
            walkDirX = dx / hd; walkDirZ = dz / hd; walkUntil = t + 400;
        }
    }

    // ------------------------------------------------------------ физика
    static final double HW = 0.3, H = 1.8;

    boolean collides(double px, double py, double pz) {
        int x0 = (int) Math.floor(px - HW + 1e-4), x1 = (int) Math.floor(px + HW - 1e-4);
        int y0 = (int) Math.floor(py + 1e-4), y1 = (int) Math.floor(py + H - 1e-4);
        int z0 = (int) Math.floor(pz - HW + 1e-4), z1 = (int) Math.floor(pz + HW - 1e-4);
        for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++) {
            if (!solidAt(bx, by, bz)) continue;
            double top = by + topOf(blockAt(bx, by, bz), metaAt(bx, by, bz));
            if (py + 1e-4 >= top) continue;              // стоим выше верха (полублок, грядка)
            return true;
        }
        return false;
    }

    /** Высота блока внутри клетки (1 — полный). */
    static double topOf(int id, int meta) {
        if ((id == 44 || id == 158 || id == 182) && meta < 8) return 0.5;
        if (id == 60 || id == 198) return 0.9375;
        if (id == 88) return 0.875;
        if (id == 171) return 0.0625;
        return 1.0;
    }

    boolean inLiquid() { int id = blockAt((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z)); return liquid(id); }

    void physics(double dx, double dz, boolean jump) {
        if (collides(x, y, z)) {                       // застряли внутри блока (постройку поставили на нас) — двигаемся без столкновений
            x += dx; z += dz; vy = 0; onGround = true;
            if (!collides(x, y + 1, z) && collides(x, y, z) && !collides(x, Math.floor(y) + 1, z)) y = Math.floor(y) + 1;
            return;
        }
        if (flying) {                                   // полёт (где сервер разрешает): без гравитации
            boolean blocked = false;
            if (dx != 0) { if (!collides(x + dx, y, z)) x += dx; else blocked = true; }
            if (dz != 0) { if (!collides(x, y, z + dz)) z += dz; else blocked = true; }
            if (blocked && !collides(x, y + 0.35, z)) { y += 0.35; if (!Double.isNaN(flyY) && flyY < y) flyY = y; return; }   // перелетаем препятствие
            if (!Double.isNaN(flyY)) { double d = Math.max(-0.35, Math.min(0.35, flyY - y)); if (!collides(x, y + d, z)) y += d; }
            vy = 0; onGround = false;
            return;
        }
        boolean water = inLiquid();
        if (water) {
            if (dx != 0 || dz != 0 || jump) vy = Math.min(vy + 0.04, 0.12); else vy = Math.max(vy - 0.02, -0.1);
            dx *= 0.5; dz *= 0.5;
        } else if (jump && onGround) vy = 0.42;
        // по осям
        if (dx != 0) { if (!collides(x + dx, y, z)) x += dx; else if (onGround && !collides(x + dx, y + 0.55, z) && isStep(x + dx, y, z)) { y += 0.5; x += dx; } }
        if (dz != 0) { if (!collides(x, y, z + dz)) z += dz; else if (onGround && !collides(x, y + 0.55, z + dz) && isStep(x, y, z + dz)) { y += 0.5; z += dz; } }
        double ny = y + vy;
        if (!collides(x, ny, z)) { y = ny; onGround = false; }
        else {
            if (vy < 0) {                                  // приземлиться точно на поверхность
                double cy = y;
                while (cy - 0.03125 >= ny && !collides(x, cy - 0.03125, z)) cy -= 0.03125;
                double snap = Math.floor(cy * 16) / 16;            // поверхности кратны 1/16
                if (!collides(x, snap, z)) cy = snap;
                y = cy; onGround = true;
            } else {                                        // упёрлись головой
                double cy = y;
                while (cy + 0.03125 <= ny && !collides(x, cy + 0.03125, z)) cy += 0.03125;
                y = cy;
            }
            vy = 0;
        }
        if (!water) { vy -= 0.08; vy *= 0.98; }
        if (onGround && !collides(x, y - 0.03, z)) onGround = false;
        if (y < -10) y = -10;
    }

    boolean flying; double flyDy, flyY = Double.NaN;

    /** Препятствие — полублок (ступенька), на который можно шагнуть без прыжка. */
    boolean isStep(double px, double py, double pz) {
        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
        return topOf(blockAt(bx, by, bz), metaAt(bx, by, bz)) <= 0.5;
    }

    boolean blockedAhead(double mx, double mz) { return collides(x + mx * 0.4, y, z + mz * 0.4); }

    // ------------------------------------------------------------ поиск пути (A*)
    boolean standable(int bx, int by, int bz) {
        int feet = blockAt(bx, by, bz), head = blockAt(bx, by + 1, bz), below = blockAt(bx, by - 1, bz);
        if (feet < 0 || head < 0 || below < 0) return false;
        if (solid(feet) || solid(head) || dangerous(feet) || dangerous(head)) return false;
        return solid(below) && !dangerous(below) || liquid(feet) && feet != 10 && feet != 11;
    }

    /** Путь до точки (или ближе чем near блоков). */
    List<int[]> findPath(int[] target, int near) {
        int sx = (int) Math.floor(x), sy = (int) Math.floor(y + 0.01), sz = (int) Math.floor(z);
        if (!standable(sx, sy, sz)) { if (standable(sx, sy + 1, sz)) sy++; else if (standable(sx, sy - 1, sz)) sy--; }
        final int tx = target[0], ty = target[1], tz = target[2];
        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingLong(a -> a[0]));
        Map<Long, Long> came = new HashMap<>(); Map<Long, Integer> g = new HashMap<>();
        long start = key(sx, sy, sz);
        g.put(start, 0); open.add(new long[]{h(sx, sy, sz, tx, ty, tz), start});
        long best = start; int bestH = Integer.MAX_VALUE; int expanded = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        while (!open.isEmpty() && expanded < 6000) {
            long[] cur = open.poll(); long k = cur[1];
            int cx = kx(k), cy = ky(k), cz = kz(k);
            int hh = h(cx, cy, cz, tx, ty, tz);
            if (hh < bestH) { bestH = hh; best = k; }
            if (Math.abs(cx - tx) <= near - 1 + (near > 1 ? 1 : 0) && Math.abs(cz - tz) <= Math.max(0, near - 1) + (near > 1 ? 1 : 0) && Math.abs(cy - ty) <= 1 && (near > 1 || (cx == tx && cz == tz))) { best = k; bestH = 0; break; }
            expanded++;
            int gc = g.get(k);
            for (int[] d : dirs) {
                int nx = cx + d[0], nz = cz + d[1];
                boolean diag = d[0] != 0 && d[1] != 0;
                if (diag && (!passableCol(cx + d[0], cy, cz) || !passableCol(cx, cy, cz + d[1]))) continue;
                for (int dy : new int[]{0, 1, -1, -2, -3}) {
                    int ny = cy + dy;
                    if (!standable(nx, ny, nz)) continue;
                    if (dy == 1 && solidAt(cx, cy + 2, cz)) continue;             // голова при прыжке
                    if (dy < 0) { boolean ok = true; for (int yy = ny + 1; yy <= cy + 1; yy++) if (solidAt(nx, yy, nz)) ok = false; if (!ok) continue; }
                    if (diag && dy != 0) continue;
                    long nk = key(nx, ny, nz);
                    int ng = gc + (diag ? 14 : 10) + (dy == 1 ? 8 : 0) + (dy < 0 ? -dy * 3 : 0) + (liquid(blockAt(nx, ny, nz)) ? 20 : 0);
                    Integer og = g.get(nk);
                    if (og == null || ng < og) { g.put(nk, ng); came.put(nk, k); open.add(new long[]{ng + h(nx, ny, nz, tx, ty, tz), nk}); }
                    break;
                }
            }
        }
        if (best == start && bestH > 0) return null;
        LinkedList<int[]> out = new LinkedList<>();
        for (long k = best; k != start; k = came.get(k)) out.addFirst(new int[]{kx(k), ky(k), kz(k)});
        if (bestH > 0) event("[бот] целиком путь не нашёл, иду как можно ближе (" + out.size() + " шагов)");
        return out;
    }

    boolean passableCol(int bx, int by, int bz) { return !solidAt(bx, by, bz) && !solidAt(bx, by + 1, bz); }
    static int h(int x, int y, int z, int tx, int ty, int tz) { int dx = Math.abs(x - tx), dz = Math.abs(z - tz); return 10 * Math.max(dx, dz) + 4 * Math.min(dx, dz) + 10 * Math.abs(y - ty); }
    static long key(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
    static int kx(long k) { return (int) (k >> 38) << 6 >> 6; }
    static int kz(long k) { return (int) ((k >> 12) & 0x3FFFFFF) << 6 >> 6; }
    static int ky(long k) { return (int) (k & 0xFFF); }

    // ------------------------------------------------------------ взгляд
    void lookAt(double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - (y + 1.62), dz = tz - z;
        setYawTo(dx, dz);
        pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
    }
    void setYawTo(double dx, double dz) { if (dx * dx + dz * dz > 1e-6) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); }

    double dist(double px, double py, double pz) { double dx = px - x, dy = py - y, dz = pz - z; return Math.sqrt(dx * dx + dy * dy + dz * dz); }

    Ent findPlayer(String n) {
        Ent best = null;
        for (Ent e : ents.values()) if (e.player && e.name != null && e.name.toLowerCase().contains(n.toLowerCase())) { if (best == null || dist(e.x, e.y, e.z) < dist(best.x, best.y, best.z)) best = e; }
        return best;
    }

    /** Блок в прицеле (до 6 блоков): {x,y,z,face} или null. */
    int[] raycast(double max) {
        double ox = x, oy = y + 1.62, oz = z;
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double dx = -Math.sin(yr) * Math.cos(pr), dy = -Math.sin(pr), dz = Math.cos(yr) * Math.cos(pr);
        int px = Integer.MIN_VALUE, py = 0, pz = 0;
        for (double s = 0; s <= max; s += 0.05) {
            int bx = (int) Math.floor(ox + dx * s), by = (int) Math.floor(oy + dy * s), bz = (int) Math.floor(oz + dz * s);
            if (bx == px && by == py && bz == pz) continue;
            int id = blockAt(bx, by, bz);
            if (id > 0 && !liquid(id)) {
                int face = 1;
                if (px != Integer.MIN_VALUE) {
                    if (py < by) face = 0; else if (py > by) face = 1; else if (pz < bz) face = 2; else if (pz > bz) face = 3; else if (px < bx) face = 4; else face = 5;
                }
                return new int[]{bx, by, bz, face};
            }
            px = bx; py = by; pz = bz;
        }
        return null;
    }

    // ------------------------------------------------------------ команды
    static final int[][] FACE = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    int[] heldItem() { int s = hotbar[held]; return inv[s >= 0 && s < 36 ? s : held]; }
    void equip() { int s = hotbar[held]; if (s < 0 || s >= 36) s = held; send(Proto.mobEquipment(selfEid, inv[s], s + 9, held)); }

    synchronized String command(String line) {
        String[] a = line.trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) return "пусто";
        String c = a[0].toLowerCase();
        try {
            switch (c) {
                case "say": case "chat": {
                    String msg = line.trim().substring(a[0].length()).trim();
                    send(Proto.text(nick, msg)); event("<" + nick + "> " + msg); return "ок";
                }
                case "stop": stopAll(); seq++; return "остановлен";
                case "join": {
                    // join bp|sb|creative — дойти до лужи-портала и включить автоигру (без ИИ)
                    String g = a.length > 1 ? a[1].toLowerCase() : "bp";
                    int zz = g.equals("sb") ? -137 : (g.startsWith("cr") || g.startsWith("кр")) ? -157 : g.equals("bp") ? -141 : Integer.parseInt(g);
                    bp = false; sbAuto = false; runMode = true; final int my = ++seq;
                    String r = command("goto -244 14 " + zz);
                    joinStep(my, zz, g, 0);
                    return "иду в " + g + " (" + r + ")";
                }
                case "dance": {
                    stopAll(); final int my = ++seq; long d = 0;
                    for (int r = 0; r < 3; r++) for (int yw : new int[]{0, 90, 180, 270}) {
                        final int fy = yw;
                        later(d, () -> { if (seq == my) { yaw = fy; pitch = 0; send(Proto.action(selfEid, Proto.ACTION_START_SNEAK, 0, 0, 0, 0)); } });
                        later(d + 300, () -> { if (seq == my) { send(Proto.action(selfEid, Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); if (onGround) vy = 0.42; } });
                        d += 700;
                    }
                    later(d, () -> { if (seq == my) pitch = 60; });
                    later(d + 400, () -> { if (seq == my) pitch = -60; });
                    later(d + 800, () -> { if (seq == my) pitch = 0; });
                    return "танцую";
                }
                case "fart": {
                    // fart [ник] — повернуться спиной к игроку и 3 раза присесть
                    final int my = ++seq; String who = a.length > 1 ? a[1] : null;
                    if (who != null) { Ent p = findPlayer(who); if (p == null) return "игрок не найден"; lookAt(p.x, p.y + 1.5, p.z); yaw += 180; pitch = 0; }
                    for (int k = 0; k < 3; k++) {
                        later(k * 700L, () -> { if (seq == my) send(Proto.action(selfEid, Proto.ACTION_START_SNEAK, 0, 0, 0, 0)); });
                        later(k * 700L + 350, () -> { if (seq == my) send(Proto.action(selfEid, Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); });
                    }
                    later(2200, () -> { if (seq == my) command("say Пук!" + (who != null ? " Это тебе, " + who + ")" : "")); });
                    return "пук";
                }
                case "goto": {
                    int[] tgt;
                    if (a.length >= 4) tgt = new int[]{(int) Math.floor(Double.parseDouble(a[1])), (int) Math.floor(Double.parseDouble(a[2])), (int) Math.floor(Double.parseDouble(a[3]))};
                    else { Ent p = findPlayer(a[1]); if (p == null) return "игрок не найден"; tgt = new int[]{(int) Math.floor(p.x), (int) Math.floor(p.y + 0.1), (int) Math.floor(p.z)}; }
                    stopAll();
                    List<int[]> p = findPath(tgt, 1);
                    if (p == null) return "пути нет";
                    path = p; pathI = 0; goal = tgt; replans = 0; stuckSince = now(); task = "иду к " + tgt[0] + " " + tgt[1] + " " + tgt[2];
                    return "иду, шагов " + p.size();
                }
                case "follow": { stopAll(); followName = a[1]; task = "следую за " + a[1]; return "следую"; }
                case "walk": {
                    // walk f|b|l|r <секунд>
                    double yr = Math.toRadians(yaw); double fx = -Math.sin(yr), fz = Math.cos(yr);
                    double dx = fx, dz = fz;
                    switch (a[1]) { case "b": dx = -fx; dz = -fz; break; case "l": dx = fz; dz = -fx; break; case "r": dx = -fz; dz = fx; break; default: break; }
                    stopAll(); walkDirX = dx; walkDirZ = dz; walkUntil = now() + (long) (Double.parseDouble(a.length > 2 ? a[2] : "1") * 1000); task = "иду " + a[1];
                    return "ок";
                }
                case "jump": if (onGround) vy = 0.42; return "прыг";
                case "turn": { yaw = (float) Double.parseDouble(a[1]); if (a.length > 2) pitch = (float) Double.parseDouble(a[2]); return "ок"; }
                case "look": {
                    if (a.length >= 4) lookAt(Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
                    else { Ent p = findPlayer(a[1]); if (p == null) return "игрок не найден"; lookAt(p.x, p.y + 1.62, p.z); }
                    return "смотрю, yaw " + Math.round(yaw) + " pitch " + Math.round(pitch);
                }
                case "dig": {
                    int[] p;
                    if (a.length >= 4) p = new int[]{Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])};
                    else { int[] r = raycast(5); if (r == null) return "в прицеле нет блока"; p = r; }
                    int id = blockAt(p[0], p[1], p[2]);
                    if (id <= 0) return "там пусто";
                    if (dist(p[0] + 0.5, p[1] + 0.5 - 1.62, p[2] + 0.5) > 5.5) return "далеко (" + Math.round(dist(p[0] + 0.5, p[1], p[2] + 0.5)) + " блоков), подойди: goto";
                    path = null; walkUntil = 0;
                    lookAt(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
                    send(Proto.action(selfEid, Proto.ACTION_START_BREAK, p[0], p[1], p[2], 1));
                    digAt = p; digStart = now(); digNeed = gamemode == 1 ? 50 : breakTime(id, heldItem()[0]);
                    task = "копаю " + name(id);
                    return "копаю " + name(id) + ", ~" + (digNeed / 100) / 10.0 + " с";
                }
                case "place": {
                    int px = Integer.parseInt(a[1]), py = Integer.parseInt(a[2]), pz = Integer.parseInt(a[3]);
                    int[] it = heldItem();
                    if (it[0] == 0) return "в руке пусто";
                    if (solid(Math.max(0, blockAt(px, py, pz)))) return "место занято";
                    for (int f = 0; f < 6; f++) {
                        int nx = px - FACE[f][0], ny = py - FACE[f][1], nz = pz - FACE[f][2];
                        int nid = blockAt(nx, ny, nz);
                        if (nid > 0 && solid(nid)) {
                            lookAt(nx + 0.5 + FACE[f][0] * 0.5, ny + 0.5 + FACE[f][1] * 0.5, nz + 0.5 + FACE[f][2] * 0.5);
                            send(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
                            send(Proto.animate(selfEid, 1));
                            send(Proto.useItem(nx, ny, nz, f, 0.5f + FACE[f][0] * 0.5f, 0.5f + FACE[f][1] * 0.5f, 0.5f + FACE[f][2] * 0.5f,
                                    (float) x, (float) (y + 1.62), (float) z, it, peProtocol, held));
                            return "ставлю " + itemName(it) + " (опора " + name(nid) + ")";
                        }
                    }
                    return "не к чему прислонить блок";
                }
                case "use": {
                    int[] it = heldItem();
                    int[] r = raycast(5);
                    if (r != null && (a.length < 2 || !a[1].equals("air"))) {
                        send(Proto.animate(selfEid, 1));
                        send(Proto.useItem(r[0], r[1], r[2], r[3], 0.5f, 0.5f, 0.5f, (float) x, (float) (y + 1.62), (float) z, it, peProtocol, held));
                        return "использую на " + name(blockAt(r[0], r[1], r[2]));
                    }
                    send(Proto.useItem(0, 0, 0, 255, 0, 0, 0, (float) x, (float) (y + 1.62), (float) z, it, peProtocol, held));
                    return "использую " + itemName(it);
                }
                case "eat": {
                    int[] it = heldItem();
                    if (it[0] == 0) return "в руке пусто";
                    send(Proto.useItem(0, 0, 0, 255, 0, 0, 0, (float) x, (float) (y + 1.62), (float) z, it, peProtocol, held));
                    final int item = it[0];
                    later(1610, () -> { if (heldItem()[0] == item) { send(Proto.entityEvent(selfEid, 9)); event("[бот] съел " + itemName(new int[]{item, 1, 0})); } });
                    return "ем " + itemName(it);
                }
                case "hold": {
                    // hold <0-8>  или  hold <id предмета>
                    int v = Integer.parseInt(a[1]);
                    if (v >= 0 && v <= 8) { held = v; equip(); return "в руке: " + itemName(heldItem()); }
                    for (int s = 0; s < 36; s++) if (inv[s][0] == v) { hotbar[held] = s; equip(); return "в руке: " + itemName(inv[s]); }
                    return "нет такого предмета";
                }
                case "attack": {
                    Ent e = null;
                    if (a.length < 2 || a[1].equals("nearest")) {
                        for (Ent q : ents.values()) if (!q.item && !q.player && isMob(q.type) && (e == null || dist(q.x, q.y, q.z) < dist(e.x, e.y, e.z))) e = q;
                    } else if (a[1].matches("-?\\d+")) e = ents.get(Long.parseLong(a[1]));
                    else { e = findPlayer(a[1]); if (e == null) for (Ent q : ents.values()) if (entName(q).toLowerCase().contains(a[1].toLowerCase()) && (e == null || dist(q.x, q.y, q.z) < dist(e.x, e.y, e.z))) e = q; }
                    if (e == null) return "цель не найдена";
                    stopAll(); attackTarget = e.id; task = "атакую " + entName(e);
                    return "атакую " + entName(e) + " (" + Math.round(dist(e.x, e.y, e.z)) + " бл.)";
                }
                case "sneak": send(Proto.action(selfEid, a.length > 1 && a[1].equals("off") ? Proto.ACTION_STOP_SNEAK : Proto.ACTION_START_SNEAK, 0, 0, 0, 0)); return "ок";
                case "respawn": {
                    send(Proto.action(selfEid, Proto.ACTION_RESPAWN, 0, 0, 0, -1)); dead = false; health = 20; return "возрождаюсь";
                }
                case "drop": {
                    int[] it = heldItem(); if (it[0] == 0) return "в руке пусто";
                    send(Proto.dropItem(it)); return "выбросил " + itemName(it);
                }
                case "find": {
                    // find <id[,id]> [радиус] — ближайшие блоки
                    Set<Integer> ids = new HashSet<>(); for (String v : a[1].split(",")) ids.add(Integer.parseInt(v));
                    int r = a.length > 2 ? Integer.parseInt(a[2]) : 12;
                    int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
                    List<int[]> found = new ArrayList<>();
                    for (int dx = -r; dx <= r; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -r; dz <= r; dz++)
                        if (ids.contains(blockAt(bx + dx, by + dy, bz + dz))) found.add(new int[]{bx + dx, by + dy, bz + dz});
                    found.sort(Comparator.comparingDouble(q -> dist(q[0] + 0.5, q[1], q[2] + 0.5)));
                    StringBuilder o = new StringBuilder("найдено " + found.size() + ": ");
                    for (int i = 0; i < Math.min(12, found.size()); i++) { int[] q = found.get(i); o.append(q[0]).append(' ').append(q[1]).append(' ').append(q[2]).append(signText(q)).append("; "); }
                    return o.toString();
                }
                case "tap": {
                    int px = Integer.parseInt(a[1]), py = Integer.parseInt(a[2]), pz = Integer.parseInt(a[3]);
                    if (dist(px + 0.5, py + 0.5 - 1.62, pz + 0.5) > 6) return "далеко, подойди";
                    lookAt(px + 0.5, py + 0.5, pz + 0.5);
                    int face = 1; double dx = x - (px + 0.5), dz = z - (pz + 0.5);
                    if (Math.abs(dx) > Math.abs(dz)) face = dx > 0 ? 5 : 4; else face = dz > 0 ? 3 : 2;
                    send(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
                    send(Proto.animate(selfEid, 1));
                    send(Proto.useItem(px, py, pz, face, 0.5f, 0.5f, 0.5f, (float) x, (float) (y + 1.62), (float) z, heldItem(), peProtocol, held));
                    return "тапнул по " + name(blockAt(px, py, pz)) + signText(new int[]{px, py, pz});
                }
                case "fly": { if (a.length > 1 && a[1].equals("off")) { flying = false; flyY = Double.NaN; return "лечу вниз"; } flying = true; flyY = a.length > 1 ? Double.parseDouble(a[1]) : y + 2; return "лечу на высоту " + flyY; }
                case "dbg": return "building=" + building + " why=" + why + " spot=" + Arrays.toString(buildSpot) + " path=" + (path == null ? "null" : path.size() + "@" + pathI) + " walk=" + (walkUntil - now()) + " fly=" + flying + " spawned=" + spawned + " dead=" + dead + " pos=" + fmt() + " ground=" + onGround + " inside=" + collides(x, y, z);
                case "sb": { sbAuto = a.length < 2 || !a[1].equals("off"); return sbAuto ? "SpeedBuilders: автоигра включена" : "автоигра выключена"; }
                case "give": {   // give <id> [meta] [кол-во] [слот] — в творческом режиме взять блок
                    int id = Integer.parseInt(a[1]), meta = a.length > 2 ? Integer.parseInt(a[2]) : 0, cnt = a.length > 3 ? Integer.parseInt(a[3]) : 64;
                    int sl = a.length > 4 ? Integer.parseInt(a[4]) : held;
                    int[] it = {id, cnt, meta, 0};
                    send(Proto.containerSetSlot(0, sl, sl, it));
                    inv[sl] = it; hotbar[sl < 9 ? sl : held] = sl;
                    if (sl < 9) { held = sl; }
                    equip();
                    return "взял " + itemName(it) + " в слот " + sl;
                }
                case "plan": {   // plan clear | plan box x1 y1 z1 x2 y2 z2 id [meta] | plan build
                    if (a[1].equals("clear")) { snap.clear(); return "план очищен"; }
                    if (a[1].equals("box")) {
                        int x1 = Integer.parseInt(a[2]), y1 = Integer.parseInt(a[3]), z1 = Integer.parseInt(a[4]), x2 = Integer.parseInt(a[5]), y2 = Integer.parseInt(a[6]), z2 = Integer.parseInt(a[7]);
                        int id = Integer.parseInt(a[8]), meta = a.length > 9 ? Integer.parseInt(a[9]) : 0; int n = 0;
                        for (int xx = Math.min(x1, x2); xx <= Math.max(x1, x2); xx++) for (int yy = Math.min(y1, y2); yy <= Math.max(y1, y2); yy++) for (int zz = Math.min(z1, z2); zz <= Math.max(z1, z2); zz++) { snap.put(key(xx, yy, zz), new int[]{xx, yy, zz, id, meta}); n++; }
                        snapFloor = Math.min(snapFloor == -999 ? 999 : snapFloor, Math.min(y1, y2) - 1);
                        return "в плане +" + n + " (всего " + snap.size() + ")";
                    }
                    if (a[1].equals("build")) { snapFloor = -999; for (int[] q : snap.values()) snapFloor = snapFloor == -999 ? q[1] - 1 : Math.min(snapFloor, q[1] - 1); return command("build"); }
                    return "plan clear|box|build";
                }
                case "house": {  // простой домик 5x5 перед собой
                    int fx = (int) Math.floor(x), fy = (int) Math.floor(y + 0.01), fz = (int) Math.floor(z);
                    double yr = Math.toRadians(yaw); int dxF = (int) Math.round(-Math.sin(yr)), dzF = (int) Math.round(Math.cos(yr));
                    int ox = fx + dxF * 3 - 2, oz = fz + dzF * 3 - 2;
                    snap.clear(); snapFloor = fy - 1;
                    for (int dx = 0; dx < 5; dx++) for (int dz = 0; dz < 5; dz++) for (int dy = 0; dy <= 3; dy++) {
                        boolean edge = dx == 0 || dx == 4 || dz == 0 || dz == 4, corner = (dx == 0 || dx == 4) && (dz == 0 || dz == 4);
                        int id = 0;
                        if (dy == 3) id = 5;
                        else if (corner) id = 17;
                        else if (edge) id = (dy == 1 && (dx == 2 || dz == 2)) ? 20 : 5;
                        int bx = ox + dx, bz = oz + dz;
                        // дверной проём со стороны бота
                        boolean doorSide = (dzF != 0) ? (dz == (dzF > 0 ? 0 : 4) && dx == 2) : (dx == (dxF > 0 ? 0 : 4) && dz == 2);
                        if (doorSide && dy <= 1) id = 0;
                        if (id != 0) snap.put(key(bx, fy + dy, bz), new int[]{bx, fy + dy, bz, id, 0});
                    }
                    if (gamemode == 1) {
                        int[][] items = {{5, 64}, {17, 16}, {20, 16}};
                        for (int i = 0; i < items.length; i++) { int[] it = {items[i][0], items[i][1], 0, 0}; send(Proto.containerSetSlot(0, i, i, it)); inv[i] = it; hotbar[i] = i; }
                    }
                    stopAll(); buildTries.clear(); orientTry.clear(); missing.clear(); secondPass = false; equippedSlot = -1; scaffold.clear(); scaffoldSince = 0; buildSpot = null; building = true; task = "строю домик";
                    return "строю домик 5x5 в " + ox + " " + fy + " " + oz + ", блоков " + snap.size();
                }
                case "snap": return snapCmd(a.length > 1 ? Integer.parseInt(a[1]) : 5, a.length > 2 ? Integer.parseInt(a[2]) : 7);
                case "build": {
                    if (a.length > 1 && a[1].equals("off")) { building = false; flying = false; flyY = Double.NaN; return "стройка остановлена"; }
                    if (snap.isEmpty()) return "сначала snap";
                    stopAll(); buildTries.clear(); orientTry.clear(); missing.clear(); secondPass = false; equippedSlot = -1; scaffold.clear(); scaffoldSince = 0; buildDone = 0; buildSpot = null; building = true; task = "строю"; 
                    return "строю, осталось " + buildTodo().size();
                }
                case "todo": {
                    List<int[]> td = buildTodo(); StringBuilder o = new StringBuilder("осталось " + td.size() + ": ");
                    for (int i = 0; i < Math.min(15, td.size()); i++) { int[] q = td.get(i); o.append(q[0]).append(',').append(q[1]).append(',').append(q[2]).append('=').append(name(q[3])).append(':').append(q[4]).append(" сейчас ").append(blockAt(q[0], q[1], q[2])).append("; "); }
                    return o.toString();
                }
                case "run": { runMode = a.length < 2 || !a[1].equals("off"); return runMode ? "бегаю" : "хожу шагом"; }
                case "bp": { bp = a.length < 2 || !a[1].equals("off"); bpLen = 0; bpRoundStart = 0; stopAll(); bpCenter = new double[]{x, z}; task = bp ? "играю в Block Party" : "ничего"; return bp ? "режим Block Party включён" : "выключен"; }
                case "quit": { RakClient rq = rak; rakGen++; if (rq != null) rq.close("quit"); later(500, () -> System.exit(3)); return "выхожу (код 3 — перезапуск)"; }
                case "path": {
                    if (path == null) return "пути нет";
                    StringBuilder o = new StringBuilder("шаг " + pathI + ": ");
                    for (int i = 0; i < path.size(); i++) { int[] w = path.get(i); o.append(w[0]).append(',').append(w[1]).append(',').append(w[2]).append(i == pathI ? "*" : "").append(' '); }
                    return o.toString();
                }
                case "blocks": {
                    // столбцы вокруг: для каждой клетки 3x3 — блоки от y-1 до y+3
                    int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
                    int r = a.length > 1 ? Integer.parseInt(a[1]) : 1;
                    StringBuilder o = new StringBuilder();
                    for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                        o.append("[").append(dx).append(',').append(dz).append("]");
                        for (int yy = by - 1; yy <= by + 3; yy++) o.append(' ').append(yy).append('=').append(blockAt(bx + dx, yy, bz + dz)).append(':').append(metaAt(bx + dx, yy, bz + dz));
                        o.append(" ; ");
                    }
                    return o.toString();
                }
                case "reconnect": { RakClient r = rak; rakGen++; rak = null; if (r != null) r.close("reconnect"); retried = false; connect(); return "переподключаюсь"; }
                default: return "неизвестная команда: " + c;
            }
        } catch (Exception e) {
            return "ошибка: " + e;
        }
    }

    static boolean isMob(int t) { return t >= 10 && t <= 45 && t != 63; }

    // время ломания (мс), очень приблизительно
    long breakTime(int id, int tool) {
        double hard;
        switch (id) {
            case 2: case 3: case 12: case 13: case 60: case 88: case 110: case 243: case 198: hard = id == 2 || id == 110 ? 0.6 : 0.5; break;
            case 1: case 4: case 48: case 98: case 24: case 45: case 67: case 108: case 109: case 139: case 43: case 44: hard = id == 1 || id == 98 || id == 24 ? 1.5 : 2.0; break;
            case 14: case 15: case 16: case 21: case 56: case 73: case 74: case 129: case 153: hard = 3.0; break;
            case 17: case 162: case 5: case 85: case 53: case 134: case 135: case 136: case 163: case 164: case 54: case 58: case 47: case 107: hard = id == 54 ? 2.5 : 2.0; break;
            case 18: case 161: hard = 0.2; break;
            case 20: case 102: case 89: hard = 0.3; break;
            case 35: hard = 0.8; break;
            case 49: hard = 50; break;
            case 7: return 3_600_000;
            default: hard = PASSABLE.contains(id) ? 0 : 1.0;
        }
        int cat = toolCat(id);                // 1 кирка, 2 лопата, 3 топор, 0 рука
        int tcat = 0, tier = 0;
        int[][] tools = {{270, 1, 2}, {274, 1, 4}, {257, 1, 6}, {285, 1, 12}, {278, 1, 8},
                {269, 2, 2}, {273, 2, 4}, {256, 2, 6}, {284, 2, 12}, {277, 2, 8},
                {271, 3, 2}, {275, 3, 4}, {258, 3, 6}, {286, 3, 12}, {279, 3, 8}};
        for (int[] t : tools) if (t[0] == tool) { tcat = t[1]; tier = t[2]; }
        double speed = tcat == cat && cat != 0 ? tier : 1;
        boolean needPick = cat == 1;
        boolean canHarvest = !needPick || tcat == 1;
        double sec = hard * (canHarvest ? 1.5 : 5.0) / speed;
        return (long) (sec * 1000) + 150;
    }

    static int toolCat(int id) {
        switch (id) {
            case 1: case 4: case 14: case 15: case 16: case 21: case 24: case 43: case 44: case 45: case 48: case 49: case 56: case 61: case 62:
            case 67: case 73: case 74: case 98: case 108: case 109: case 112: case 114: case 121: case 129: case 139: case 153: case 155: case 156: case 159: case 172: case 179: case 180:
                return 1;
            case 2: case 3: case 12: case 13: case 60: case 78: case 80: case 82: case 88: case 110: case 198: case 243: return 2;
            case 5: case 17: case 47: case 53: case 54: case 58: case 85: case 107: case 134: case 135: case 136: case 162: case 163: case 164: return 3;
            default: return 0;
        }
    }

    // ------------------------------------------------------------ имена
    static final Map<Integer, String> BN = new HashMap<>();
    static final Map<Integer, Character> BC = new HashMap<>();
    static void bn(int id, String n, char c) { BN.put(id, n); BC.put(id, c); }
    static {
        bn(0, "воздух", ' '); bn(1, "камень", '#'); bn(2, "трава", '"'); bn(3, "земля", 'd'); bn(4, "булыжник", 'c'); bn(5, "доски", 'p');
        bn(6, "саженец", 't'); bn(7, "бедрок", 'B'); bn(8, "вода", '~'); bn(9, "вода", '~'); bn(10, "лава", '!'); bn(11, "лава", '!');
        bn(12, "песок", ':'); bn(13, "гравий", ','); bn(14, "золотая руда", 'o'); bn(15, "железная руда", 'o'); bn(16, "угольная руда", 'o');
        bn(17, "бревно", 'L'); bn(18, "листва", '*'); bn(19, "губка", 's'); bn(20, "стекло", 'g'); bn(21, "лазуритовая руда", 'o');
        bn(24, "песчаник", 'S'); bn(26, "кровать", 'b'); bn(30, "паутина", 'w'); bn(31, "трава (высокая)", '\''); bn(32, "куст", '\'');
        bn(35, "шерсть", 'W'); bn(37, "цветок", 'f'); bn(38, "цветок", 'f'); bn(39, "гриб", 'm'); bn(40, "гриб", 'm');
        bn(41, "золотой блок", '$'); bn(42, "железный блок", '$'); bn(43, "двойная плита", '='); bn(44, "плита", '_'); bn(45, "кирпич", 'K');
        bn(46, "динамит", 'T'); bn(47, "книжная полка", 'P'); bn(48, "замшелый булыжник", 'c'); bn(49, "обсидиан", 'O'); bn(50, "факел", 'i');
        bn(51, "огонь", '^'); bn(52, "спавнер", 'M'); bn(53, "ступеньки", '/'); bn(54, "сундук", 'C'); bn(56, "алмазная руда", 'D');
        bn(57, "алмазный блок", '$'); bn(58, "верстак", 'X'); bn(59, "пшеница", 'v'); bn(60, "грядка", '-'); bn(61, "печь", 'F'); bn(62, "печь", 'F');
        bn(63, "табличка", 'n'); bn(64, "дверь", 'D'); bn(65, "лестница", 'H'); bn(66, "рельсы", 'r'); bn(67, "ступеньки", '/'); bn(68, "табличка", 'n');
        bn(71, "железная дверь", 'D'); bn(73, "редстоун руда", 'o'); bn(74, "редстоун руда", 'o'); bn(78, "снег", '.'); bn(79, "лёд", 'I'); bn(80, "снег", 'N');
        bn(81, "кактус", 'Y'); bn(82, "глина", 'y'); bn(83, "тростник", '|'); bn(85, "забор", '+'); bn(86, "тыква", 'Q'); bn(87, "незерак", 'R');
        bn(88, "песок душ", ';'); bn(89, "светокамень", 'G'); bn(91, "светильник Джека", 'Q'); bn(96, "люк", '%'); bn(98, "каменный кирпич", '#');
        bn(102, "стеклянная панель", 'g'); bn(103, "арбуз", 'Q'); bn(106, "лианы", '|'); bn(107, "калитка", '+'); bn(108, "ступеньки", '/'); bn(109, "ступеньки", '/');
        bn(110, "мицелий", '"'); bn(111, "кувшинка", 'u'); bn(112, "незер-кирпич", 'K'); bn(114, "ступеньки", '/'); bn(121, "эндерняк", 'E');
        bn(128, "ступеньки", '/'); bn(129, "изумрудная руда", 'o'); bn(134, "ступеньки", '/'); bn(135, "ступеньки", '/'); bn(136, "ступеньки", '/');
        bn(139, "булыжная стена", '+'); bn(155, "кварц", 'q'); bn(156, "ступеньки", '/'); bn(158, "плита", '_'); bn(159, "глина (крашеная)", 'y');
        bn(161, "листва", '*'); bn(162, "бревно", 'L'); bn(163, "ступеньки", '/'); bn(164, "ступеньки", '/'); bn(170, "сено", 'h'); bn(171, "ковёр", '_');
        bn(172, "терракота", 'y'); bn(173, "угольный блок", '$'); bn(174, "плотный лёд", 'I'); bn(175, "высокий цветок", 'f'); bn(179, "красный песчаник", 'S');
        bn(198, "тропинка", '-'); bn(243, "подзол", 'd');
    }
    static String name(int id) { if (id < 0) return "не загружено"; String n = BN.get(id); return n != null ? n : "блок#" + id; }
    static char ch(int id) { if (id < 0) return '?'; Character c = BC.get(id); return c != null ? c : '&'; }

    static final Map<Integer, String> IN = new HashMap<>();
    static {
        String[] s = {"256:железная лопата", "257:железная кирка", "258:железный топор", "259:огниво", "260:яблоко", "261:лук", "262:стрела", "263:уголь",
                "264:алмаз", "265:железный слиток", "266:золотой слиток", "267:железный меч", "268:деревянный меч", "269:деревянная лопата", "270:деревянная кирка",
                "271:деревянный топор", "272:каменный меч", "273:каменная лопата", "274:каменная кирка", "275:каменный топор", "276:алмазный меч", "277:алмазная лопата",
                "278:алмазная кирка", "279:алмазный топор", "280:палка", "281:миска", "282:тушёные грибы", "283:золотой меч", "287:нить", "288:перо", "289:порох",
                "290:деревянная мотыга", "295:семена", "296:пшеница", "297:хлеб", "319:сырая свинина", "320:жареная свинина", "322:золотое яблоко", "323:табличка",
                "324:дверь", "325:ведро", "326:ведро воды", "327:ведро лавы", "331:редстоун", "332:снежок", "334:кожа", "336:кирпич", "337:глина", "338:тростник",
                "339:бумага", "340:книга", "341:слизь", "344:яйцо", "345:компас", "347:часы", "348:светопыль", "349:сырая рыба", "350:жареная рыба", "351:краситель",
                "352:кость", "353:сахар", "354:торт", "355:кровать", "357:печенье", "359:ножницы", "360:арбуз", "363:сырая говядина", "364:стейк",
                "365:сырая курица", "366:жареная курица", "367:гнилая плоть", "368:жемчуг Края", "369:стержень ифрита", "373:зелье", "391:морковь",
                "392:картофель", "393:печёный картофель", "400:тыквенный пирог", "411:сырая крольчатина", "412:жареная крольчатина"};
        for (String e : s) { int c = e.indexOf(':'); IN.put(Integer.parseInt(e.substring(0, c)), e.substring(c + 1)); }
    }
    static String itemName(int[] it) {
        if (it == null || it[0] == 0) return "пусто";
        String n = it[0] < 256 ? name(it[0]) : IN.getOrDefault(it[0], "предмет#" + it[0]);
        return n + (it[1] > 1 ? " ×" + it[1] : "");
    }

    static final Map<Integer, String> EN = new HashMap<>();
    static {
        String[] s = {"10:курица", "11:корова", "12:свинья", "13:овца", "14:волк", "15:житель", "16:грибная корова", "17:спрут", "18:кролик", "19:летучая мышь",
                "20:железный голем", "21:снежный голем", "22:оцелот", "32:зомби", "33:крипер", "34:скелет", "35:паук", "36:зомби-свиночеловек", "37:слизень",
                "38:эндермен", "39:чешуйница", "40:пещерный паук", "41:гаст", "42:магмовый куб", "43:ифрит", "44:зомби-житель", "45:ведьма",
                "64:предмет", "65:TNT", "66:падающий блок", "69:опыт", "80:стрела", "81:снежок", "82:яйцо", "84:вагонетка", "90:лодка"};
        for (String e : s) { int c = e.indexOf(':'); EN.put(Integer.parseInt(e.substring(0, c)), e.substring(c + 1)); }
    }
    String entName(Ent e) {
        if (e.player) return "игрок " + e.name;
        if (e.item) return "предмет " + itemName(e.it);
        String n = EN.getOrDefault(e.type, "сущность#" + e.type);
        return e.name != null && !e.name.isEmpty() ? n + " «" + e.name.replaceAll("§.", "") + "»" : n;
    }

    String fmt() { return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z); }

    static String dir8(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz)); if (a < 0) a += 360;   // 0 = север (-Z)
        String[] n = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return n[(int) Math.round(a / 45) % 8];
    }

    // ------------------------------------------------------------ отчёты
    synchronized String state() {
        StringBuilder s = new StringBuilder();
        s.append("статус: ").append(status).append(dead ? " (мёртв — respawn)" : "").append('\n');
        s.append("позиция: ").append(fmt()).append("  взгляд: yaw ").append(Math.round(yaw)).append(" pitch ").append(Math.round(pitch))
                .append(" (смотрю на ").append(dir8(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)))).append(")").append(onGround ? "" : "  в воздухе").append(inLiquid() ? "  в жидкости" : "").append('\n');
        s.append("здоровье ").append(health).append("/20  голод ").append(food).append("/20  уровень ").append((int) xpLevel)
                .append("  режим ").append(gamemode == 1 ? "творческий" : gamemode == 0 ? "выживание" : String.valueOf(gamemode))
                .append("  время ").append(time % 24000).append(time % 24000 < 12500 ? " (день)" : " (ночь)").append('\n');
        s.append("медленных тиков: ").append(slowTicks).append("; ");
        s.append("сервер поправлял позицию: ").append(corrections).append(" раз (сильно: ").append(bigCorrections).append(")\n");
        if (!lastFix.isEmpty()) s.append("последние поправки: ").append(String.join("  ", lastFix)).append('\n');
        s.append("задача: ").append(task).append(path != null ? " (шаг " + pathI + "/" + path.size() + ")" : "").append('\n');
        s.append("в руке [").append(held).append("]: ").append(itemName(heldItem())).append('\n');
        s.append("хотбар: ");
        for (int i = 0; i < 9; i++) { int sl = hotbar[i]; s.append(i).append('=').append(itemName(inv[sl >= 0 && sl < 36 ? sl : i])).append(i < 8 ? "; " : "\n"); }
        StringBuilder rest = new StringBuilder();
        Set<Integer> hb = new HashSet<>(); for (int v : hotbar) hb.add(v);
        for (int i = 0; i < 36; i++) if (inv[i][0] != 0 && !hb.contains(i)) rest.append(itemName(inv[i])).append(" (id ").append(inv[i][0]).append("); ");
        if (rest.length() > 0) s.append("инвентарь: ").append(rest).append('\n');
        int[] r = raycast(6);
        s.append("в прицеле: ").append(r == null ? "ничего (до 6 блоков)" : name(blockAt(r[0], r[1], r[2])) + " в " + r[0] + " " + r[1] + " " + r[2]).append('\n');
        List<Ent> near = new ArrayList<>();
        for (Ent e : ents.values()) if (dist(e.x, e.y, e.z) < 40) near.add(e);
        near.sort(Comparator.comparingDouble(e -> dist(e.x, e.y, e.z)));
        s.append("рядом (").append(near.size()).append("):\n");
        int k = 0;
        for (Ent e : near) {
            if (k++ >= 25) break;
            s.append(String.format(Locale.ROOT, "  #%d %s — %.0f бл. на %s, (%.1f %.1f %.1f)%n", e.id, entName(e), dist(e.x, e.y, e.z), dir8(e.x - x, e.z - z), e.x, e.y, e.z));
        }
        return s.toString();
    }

    /** Текстовая «картинка» мира вокруг. */
    synchronized String view(int r) {
        StringBuilder s = new StringBuilder();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y + 0.01), bz = (int) Math.floor(z);
        s.append("Север — вверх (-Z), восток — вправо (+X). @ — я, буквы — игроки/мобы (список ниже). Я в ").append(bx).append(' ').append(by).append(' ').append(bz).append('\n');
        Map<Long, Character> mark = new HashMap<>();
        List<String> legendE = new ArrayList<>();
        char m = 'a';
        for (Ent e : ents.values()) {
            int ex = (int) Math.floor(e.x), ez = (int) Math.floor(e.z);
            if (Math.abs(ex - bx) > r || Math.abs(ez - bz) > r || m > 'z') continue;
            if (e.item) continue;
            mark.put(key(ex, 0, ez), Character.toUpperCase(m) == m ? m : m);
            legendE.add(m + " = " + entName(e) + " (y " + Math.round(e.y) + ")");
            m++;
        }
        // карта поверхности
        s.append("\n== Сверху (высота поверхности относительно моих ног: цифра = выше, ниже — символ блока) ==\n");
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int wx = bx + dx, wz = bz + dz;
                Character em = mark.get(key(wx, 0, wz));
                if (dx == 0 && dz == 0) { s.append('@'); continue; }
                if (em != null) { s.append(em); continue; }
                int top = -1;
                for (int yy = Math.min(127, by + 12); yy >= Math.max(0, by - 16); yy--) { int id = blockAt(wx, yy, wz); if (id < 0) { top = -2; break; } if (id != 0 && id != 31 && id != 32 && id != 37 && id != 38 && id != 175) { top = yy; break; } }
                if (top == -2) { s.append('?'); continue; }
                if (top < 0) { s.append('v'); continue; }
                int id = blockAt(wx, top, wz);
                int dh = top - (by - 1);
                if (liquid(id)) s.append(ch(id));
                else if (dh >= 1 && dh <= 9) s.append((char) ('0' + dh));
                else if (dh > 9) s.append('^');
                else if (dh < -3) s.append('v');
                else s.append(ch(id));
            }
            s.append('\n');
        }
        // срезы на уровне головы, ног и под ногами
        int rr = Math.min(r, 6);
        for (int ly : new int[]{by + 1, by, by - 1}) {
            s.append("\n== Слой y=").append(ly).append(ly == by + 1 ? " (голова)" : ly == by ? " (ноги)" : " (под ногами)").append(" ==\n");
            for (int dz = -rr; dz <= rr; dz++) {
                for (int dx = -rr; dx <= rr; dx++) {
                    if (dx == 0 && dz == 0 && ly != by - 1) { s.append('@'); continue; }
                    s.append(ch(blockAt(bx + dx, ly, bz + dz)));
                }
                s.append('\n');
            }
        }
        s.append("\nЛегенда: пробел воздух, # камень, \" трава, d земля, c булыжник, p доски, L бревно, * листва, ~ вода, ! лава, : песок, , гравий, o руда, g стекло, ")
                .append("C сундук, X верстак, F печь, / ступеньки, _ плита, + забор, i факел, D дверь, H лестница, ' трава/куст, f цветок, v обрыв, ? не загружено, & прочий блок\n");
        if (!legendE.isEmpty()) s.append("Существа: ").append(String.join("; ", legendE)).append('\n');
        return s.toString();
    }

    synchronized String chat(int since) {
        StringBuilder s = new StringBuilder();
        int from = Math.max(since, eventBase);
        for (int i = from; i < eventBase + events.size(); i++) s.append(i).append(": ").append(events.get(i - eventBase)).append('\n');
        s.append("next=").append(eventBase + events.size()).append('\n');
        return s.toString();
    }

    // ------------------------------------------------------------ HTTP (только локально)
    void startHttp() throws Exception {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", httpPort), 8);
        srv.createContext("/", ex -> {
            String resp;
            try {
                String p = ex.getRequestURI().getPath();
                Map<String, String> q = query(ex.getRequestURI().getRawQuery());
                switch (p) {
                    case "/state": resp = state(); break;
                    case "/view": resp = view(Integer.parseInt(q.getOrDefault("r", "8"))); break;
                    case "/chat": resp = chat(Integer.parseInt(q.getOrDefault("since", "0"))); break;
                    case "/all": {
                        int since = Integer.parseInt(q.getOrDefault("since", "0"));
                        resp = state() + "\n" + chat(since) + "\n" + view(Integer.parseInt(q.getOrDefault("r", "8")));
                        break;
                    }
                    case "/cmd": {
                        StringBuilder out = new StringBuilder();
                        if (q.containsKey("c")) out.append(command(q.get("c"))).append('\n');
                        if ("POST".equals(ex.getRequestMethod())) {
                            String body = new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
                            for (String l : body.split("\n")) if (!l.trim().isEmpty()) out.append(l.trim()).append(" -> ").append(command(l)).append('\n');
                        }
                        resp = out.toString();
                        break;
                    }
                    case "/": case "/panel": {
                        byte[] d = PANEL.getBytes(StandardCharsets.UTF_8);
                        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        ex.sendResponseHeaders(200, d.length);
                        try (OutputStream o = ex.getResponseBody()) { o.write(d); }
                        return;
                    }
                    default: resp = "GET /panel | /state | /view?r=8 | /chat?since=N | /all?since=N | /cmd?c=...\n";
                }
            } catch (Exception e) { resp = "ошибка: " + e; }
            byte[] d = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(200, d.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(d); }
        });
        srv.start();
        log("HTTP API: http://127.0.0.1:" + httpPort + "/state");
    }

    // ярлыки .bat: двойной клик — команда боту через HTTP (нужен запущенный run.bat)
    void writeShortcuts(java.io.File k) {
        try {
            k.mkdirs();
            String[][] list = {
                {"Панель", null},
                {"Зайти в Block Party", "join bp"}, {"Зайти в SpeedBuilders", "join sb"}, {"Зайти в креатив", "join creative"},
                {"Выключить автоигру", "bp off"}, {"На спавн", "say /spawn"}, {"Стоп", "stop"},
                {"Танец", "dance"}, {"Пук", "fart"}, {"Переподключиться", "reconnect"}};
            for (String[] e : list) {
                String body = e[1] == null
                        ? "@echo off\r\nstart \"\" http://127.0.0.1:" + httpPort + "/panel\r\n"
                        : "@echo off\r\nchcp 65001 >nul\r\ncurl -s \"http://127.0.0.1:" + httpPort + "/cmd?c=" + java.net.URLEncoder.encode(e[1], StandardCharsets.UTF_8) + "\" >nul || echo Бот не запущен (сначала run.bat) && pause\r\n";
                java.nio.file.Files.write(new java.io.File(k, e[0] + ".bat").toPath(), body.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) { log("ярлыки: " + e); }
    }

    static final String PANEL = "<!doctype html><html lang=ru><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>ClaudeBot</title><style>"
        + ":root{--bg:#f6f4ef;--fg:#222;--card:#fff;--b:#ddd;--acc:#d9772b}@media(prefers-color-scheme:dark){:root{--bg:#1c1b19;--fg:#eee;--card:#2a2926;--b:#444}}"
        + "body{margin:0;padding:16px;font:15px system-ui,sans-serif;background:var(--bg);color:var(--fg)}h1{font-size:20px;margin:0 0 12px}"
        + ".g{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:8px;margin-bottom:12px}"
        + "button{padding:12px;border:1px solid var(--b);border-radius:10px;background:var(--card);color:var(--fg);font-size:15px;cursor:pointer}button.a{background:var(--acc);color:#fff;border:0}button:active{opacity:.6}"
        + "form{display:flex;gap:8px;margin-bottom:8px}input{flex:1;min-width:0;padding:10px;border:1px solid var(--b);border-radius:10px;background:var(--card);color:var(--fg);font-size:15px}"
        + "pre{background:var(--card);border:1px solid var(--b);border-radius:10px;padding:10px;white-space:pre-wrap;word-break:break-word;font-size:13px;max-height:40vh;overflow:auto}#r{color:var(--acc);min-height:1.2em}</style></head><body>"
        + "<h1>ClaudeBot014</h1><div class=g>"
        + "<button class=a data-c='join bp'>Зайти в Block Party</button><button class=a data-c='join sb'>Зайти в SpeedBuilders</button><button data-c='join creative'>Зайти в креатив</button>"
        + "<button data-c='bp off'>Выключить автоигру</button><button data-c='say /spawn'>На спавн</button><button data-c='stop'>Стоп</button>"
        + "<button data-c='dance'>Танец</button><button data-c='fart'>Пук</button><button data-c='say /money'>Баланс</button><button data-c='reconnect'>Переподключиться</button></div>"
        + "<form id=fs><input id=s placeholder='Написать в чат'><button>Отправить</button></form>"
        + "<form id=fc><input id=c placeholder='Команда боту: goto geniiy, follow Zov_, fart Wics4urr…'><button>Выполнить</button></form>"
        + "<div id=r></div><pre id=st>…</pre><pre id=ch>…</pre><script>"
        + "const r=document.getElementById('r');async function cmd(c){try{const t=await (await fetch('/cmd?c='+encodeURIComponent(c))).text();r.textContent=c+' → '+t.trim()}catch(e){r.textContent='Бот не отвечает'}}"
        + "document.querySelectorAll('button[data-c]').forEach(b=>b.onclick=()=>cmd(b.dataset.c));"
        + "fs.onsubmit=e=>{e.preventDefault();if(s.value)cmd('say '+s.value);s.value=''};fc.onsubmit=e=>{e.preventDefault();if(c.value)cmd(c.value);c.value=''};"
        + "async function upd(){try{st.textContent=(await (await fetch('/state')).text()).split('\\n').slice(0,9).join('\\n');"
        + "const t=(await (await fetch('/chat?since=0')).text()).split('\\n').filter(l=>l&&!l.startsWith('next'));ch.textContent=t.slice(-25).reverse().join('\\n')}catch(e){st.textContent='Бот не запущен'}}"
        + "upd();setInterval(upd,1500)</script></body></html>";

    // ------------------------------------------------------------ обмен через файлы (папка --dir)
    void startFiles(java.io.File d) {
        d.mkdirs();
        try { java.io.File pw = new java.io.File(d, "password.txt"); if (pw.isFile()) password = new String(java.nio.file.Files.readAllBytes(pw.toPath()), StandardCharsets.UTF_8).trim(); } catch (Exception ignored) {}
        final java.io.File cmd = new java.io.File(d, "cmd.txt"), out = new java.io.File(d, "out.txt");
        writeShortcuts(new java.io.File(d, "Кнопки"));
        log("Обмен через папку: " + d.getAbsolutePath());
        Thread t = new Thread(() -> {
            long done = -1;
            try { if (cmd.isFile()) for (String l : java.nio.file.Files.readAllLines(cmd.toPath(), StandardCharsets.UTF_8)) { int i = l.indexOf('|'); if (i > 0) done = Math.max(done, Long.parseLong(l.substring(0, i).trim())); } } catch (Exception ignored) {}
            long lastDump = 0;
            while (true) {
                try {
                    if (cmd.isFile()) {
                        List<String> lines = java.nio.file.Files.readAllLines(cmd.toPath(), StandardCharsets.UTF_8);
                        StringBuilder res = new StringBuilder();
                        for (String l : lines) {
                            int i = l.indexOf('|'); if (i <= 0) continue;
                            long n; try { n = Long.parseLong(l.substring(0, i).trim()); } catch (Exception e) { continue; }
                            if (n <= done) continue;
                            done = n;
                            res.append(n).append('|').append(command(l.substring(i + 1)).replace('\n', ' ')).append('\n');
                        }
                        if (res.length() > 0) java.nio.file.Files.write(out.toPath(), res.toString().getBytes(StandardCharsets.UTF_8),
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                    long t2 = now();
                    if (t2 - lastDump > 400) {
                        lastDump = t2;
                        int since = Math.max(0, eventBase + events.size() - 300);
                        put(new java.io.File(d, "state.txt"), "t=" + t2 + "\n" + state());
                        put(new java.io.File(d, "view.txt"), view(10));
                        put(new java.io.File(d, "chat.txt"), chat(since));
                    }
                } catch (Exception e) { log("файлы: " + e); }
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            }
        }, "bot-files");
        t.setDaemon(true); t.start();
    }

    static void put(java.io.File f, String s) throws java.io.IOException {
        java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".tmp");
        java.nio.file.Files.write(tmp.toPath(), s.getBytes(StandardCharsets.UTF_8));
        try { java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE); }
        catch (Exception e) { java.nio.file.Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }
    }

    static byte[] readAll(InputStream in) throws java.io.IOException { return in.readAllBytes(); }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i), v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }
}
