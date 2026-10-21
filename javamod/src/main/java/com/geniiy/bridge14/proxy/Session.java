package com.geniiy.bridge14.proxy;

import com.geniiy.bridge14.pe.Buf;
import com.geniiy.bridge14.pe.Proto;
import com.geniiy.bridge14.pe.RakClient;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Одна игровая сессия: Java-клиент 1.20.1 ↔ сервер MCPE 0.14.
 * Для Java-клиента мы — обычный сервер без шифрования, для сервера 0.14 — обычный игрок.
 */
public final class Session {

    public interface Listener {
        void status(String s);
        void log(String s);
        void ended(String reason);
        /** Надпись сервера: popup (над хотбаром) или tip (середина экрана). */
        default void popup(String text, boolean tip) {}
        /** Скин игрока (RGBA 64×64 / 64×32). */
        default void skin(UUID javaUuid, byte[] rgba, boolean slim) {}
    }

    public static final class Config {
        public String nick = "Steve";      // null/"" — взять ник, с которым зашёл Java-клиент
        public byte[] skin;                // RGBA 64×64 или 64×32
        public boolean slim;
        public String host = "127.0.0.1";
        public int port = 19132;
        public int chunkRadius = 6;
        /** Тело пакета тегов; null — не отправлять. */
        public java.util.function.Supplier<byte[]> tags;
        /** Текст для списка серверов Java (только для отдельного сервера). */
        public String motd = "Сервер 0.14";
    }

    // ---- Java пакеты (1.20.1, протокол 763) ----
    static final int C_SPAWN_ENTITY = 0x01, C_SPAWN_PLAYER = 0x03, C_ANIMATION = 0x04, C_ACK_DIG = 0x06, C_TILE_DATA = 0x08,
            C_BLOCK_CHANGE = 0x0a, C_CLOSE_WINDOW = 0x11, C_WINDOW_ITEMS = 0x12, C_PROGRESS = 0x13, C_SET_SLOT = 0x14,
            C_KICK = 0x1a, C_ENTITY_STATUS = 0x1c, C_EXPLOSION = 0x1d, C_UNLOAD = 0x1e, C_GAME_EVENT = 0x1f,
            C_HURT = 0x21, C_KEEP_ALIVE = 0x23, C_CHUNK = 0x24, C_LOGIN = 0x28, C_MAP = 0x29, C_OPEN_WINDOW = 0x30,
            C_OPEN_SIGN = 0x31, C_ABILITIES = 0x34, C_DEATH = 0x38, C_PLAYER_REMOVE = 0x39, C_PLAYER_INFO = 0x3a,
            C_POSITION = 0x3c, C_DESTROY = 0x3e, C_REMOVE_EFFECT = 0x3f, C_RESPAWN = 0x41, C_HEAD_ROT = 0x42,
            C_SERVER_DATA = 0x45, C_HELD_SLOT = 0x4d, C_VIEW_POS = 0x4e, C_SPAWN_POS = 0x50, C_METADATA = 0x52,
            C_VELOCITY = 0x54, C_EQUIPMENT = 0x55, C_EXPERIENCE = 0x56, C_HEALTH = 0x57, C_PASSENGERS = 0x59, C_TEAMS = 0x5a,
            C_TIME = 0x5e, C_SYSTEM_CHAT = 0x64, C_COLLECT = 0x67, C_TELEPORT = 0x68, C_FEATURES = 0x6b, C_EFFECT = 0x6c,
            C_TAGS = 0x6e;
    static final int S_TELEPORT_CONFIRM = 0x00, S_CHAT_COMMAND = 0x04, S_CHAT = 0x05, S_CLIENT_COMMAND = 0x07,
            S_WINDOW_CLICK = 0x0b, S_CLOSE_WINDOW = 0x0c, S_USE_ENTITY = 0x10, S_KEEP_ALIVE = 0x12, S_POS = 0x14,
            S_POS_LOOK = 0x15, S_LOOK = 0x16, S_FLYING = 0x17, S_DIG = 0x1d, S_ACTION = 0x1e, S_HELD_SLOT = 0x28,
            S_CREATIVE_SLOT = 0x2b, S_UPDATE_SIGN = 0x2e, S_SWING = 0x2f, S_PLACE = 0x31, S_USE_ITEM = 0x32;

    static final int TEXT_DISPLAY_TYPE = 100, ITEM_FRAME_TYPE = 56, PAINTING_TYPE = 68;
    static final int PE_ARMOR_WINDOW = 0x78, CRAFT_WIN = 127;
    static final String HIDE_TEAM = "b14hide";
    static final String WORLD_A = "minecraft:overworld", WORLD_B = "bridge14:world";

    private final Socket sock;
    private final Config cfg;
    private final Listener L;
    private OutputStream out;
    private volatile boolean alive = true;
    private RakClient rak;
    private int rakGen;
    private int peProtocol = Proto.PROTOCOL, serverProtocol;
    private boolean retried;
    private final Random rnd = new Random();
    private final Map<Integer, Integer> peStats = new TreeMap<>();

    private int jState;        // 0 рукопожатие, 1 статус, 2 вход, 3 игра
    private boolean peSpawned, joined;
    private String javaName;
    private UUID selfUuid, peSelfUuid;

    // состояние мира
    private long selfEid = -1;
    private int gamemode, advFlags;
    private double x, y, z; private float yaw, pitch;
    private int health = 20, food = 20; private float saturation = 5, xpBar; private int xpLevel;
    private boolean dead;
    private double rx, ry, rz; // куда воскресит сервер
    private int spawnX, spawnY = 64, spawnZ;
    private long time = 6000;
    private boolean raining, thunder;
    private final Map<Long, ChunkConv.Chunk> chunks = new HashMap<>();
    private final Map<Long, Ent> ents = new HashMap<>();
    private final Map<Integer, Long> javaToPe = new HashMap<>();
    private final Map<Integer, int[]> effects = new LinkedHashMap<>();
    private final Map<UUID, String> tabList = new HashMap<>();
    private final Set<String> hiddenNames = new HashSet<>();
    private int nextJavaId = 2, teleportId = 1, lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private boolean worldB;
    private boolean sneaking;

    // телепортация
    private int awaitTp = -1; private long awaitTpSince;
    private boolean tpPending; private long tpStart;
    private double tpX, tpY, tpZ; private float tpYaw, tpPitch;

    // предсказания блоков клиентом: подтверждаем, только когда сервер ответил
    private static final class Pend { int seq; long[] pos; long at; boolean done; }
    private final ArrayDeque<Pend> pend = new ArrayDeque<>();
    // отложенные действия (сервер 0.14 не даёт сломать блок быстрее, чем через ~0.1 с после начала)
    private static final class Later { long at; Runnable r; }
    private final List<Later> later = new ArrayList<>();
    private long breakPos = Long.MIN_VALUE, breakAt; private boolean breakRetried;

    // ---- инвентарь: предметы 0.14 {id, count, meta, ref NBT} ----
    private final int[][] inv = new int[36][];      // как на сервере
    private final int[][] armor = new int[4][];
    private int[][] cont = new int[0][];
    private final int[][] vInv = new int[36][];     // как видит Java-клиент
    private final int[][] vArmor = new int[4][];
    private int[][] vCont = new int[0][];
    private final int[][] grid = new int[9][];      // сетка крафта (на сервере эти предметы ещё в инвентаре)
    private int[] jCursor;                          // предмет «на курсоре» у Java
    private int[] craftResult; private Crafting.Match craftMatch;
    private final Crafting crafting = new Crafting();
    private final int[] hotbar = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private int held;
    private long lastKeepAlive;
    private int stateId = 1;
    private int openWin = -1, openType = -1, openSlots;
    private boolean peCraftWin;                      // окно верстака открыл сам сервер
    private int burnMax = 200;

    private int[] pendingSign; private long pendingSignAt;

    // рамки, карты
    private static final class Frame { int jid; int x, y, z, dir, rot; int[] item; UUID uuid = UUID.randomUUID(); }
    private final Map<Long, Frame> frames = new HashMap<>();
    private final Map<Integer, Frame> frameByJid = new HashMap<>();
    private final Map<Long, Integer> mapIds = new HashMap<>();
    private final Set<Long> mapRequested = new HashSet<>();
    private final Map<Integer, byte[]> mapPackets = new HashMap<>();
    private int nextMapId = 1;

    static final class Ent {
        long pe; int jid, jtype; boolean player; UUID uuid; String name = "";
        float x, y, z, yaw, pitch; int[] item; int peType = -1;
        String nametag = ""; boolean showTag, invisible, hologram; int flags;
        int labelJid = -1;
        float eye = 0.225f, height = 0.25f;
        int[][] gear;
        int paintingDir = -1, paintingVariant;
    }

    public Session(Socket sock, Config cfg, Listener l) {
        this.sock = sock; this.cfg = cfg; this.L = l;
        for (int i = 0; i < 36; i++) { inv[i] = empty(); vInv[i] = empty(); }
        for (int i = 0; i < 4; i++) { armor[i] = empty(); vArmor[i] = empty(); }
    }

    static int[] empty() { return new int[4]; }

    public void start() {
        Thread t = new Thread(this::readLoop, "j-read");
        t.setDaemon(true);
        t.start();
        Thread k = new Thread(this::tickLoop, "j-tick");
        k.setDaemon(true);
        k.start();
    }

    public boolean alive() { return alive; }

    public void close(String reason) {
        if (!alive) return;
        alive = false;
        try { if (jState == 3) send(C_KICK, new JBuf().str(chat(reason))); } catch (Exception ignored) {}
        try { sock.close(); } catch (Exception ignored) {}
        RakClient r = rak; rak = null;
        if (r != null) r.close("Выход");
        if (!peStats.isEmpty()) {
            StringBuilder sb = new StringBuilder("Пакеты 0.14 за сессию:");
            for (Map.Entry<Integer, Integer> e : peStats.entrySet()) sb.append(String.format(" %02x×%d", e.getKey(), e.getValue()));
            L.log(sb.toString());
        }
        L.ended(reason);
    }

    // ======================= Java: чтение =======================
    private void readLoop() {
        try {
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            out = sock.getOutputStream();
            DataInputStream din = new DataInputStream(in);
            while (alive) {
                int len = readVarInt(din);
                if (len <= 0 || len > 2_000_000) throw new IOException("bad frame " + len);
                byte[] data = new byte[len];
                din.readFully(data);
                JBuf b = new JBuf(data, 0, len);
                int id = b.varint();
                try { onJava(id, b); }
                catch (Exception e) { L.log("Ошибка Java-пакета 0x" + Integer.toHexString(id) + ": " + e); }
            }
        } catch (Exception e) {
            if (alive) close("Клиент отключился");
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int v = 0, s = 0;
        while (true) {
            int k = in.readUnsignedByte();
            v |= (k & 0x7F) << s;
            if ((k & 0x80) == 0) return v;
            s += 7;
            if (s > 35) throw new IOException("varint");
        }
    }

    private void tickLoop() {
        while (alive) {
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
            if (jState != 3 || !joined) continue;
            long now = System.currentTimeMillis();
            if (now - lastKeepAlive > 10000) {
                lastKeepAlive = now;
                try { send(C_KEEP_ALIVE, new JBuf().i64(lastKeepAlive)); } catch (Exception ignored) {}
            }
            synchronized (this) {
                if (tpPending && now - tpStart > 4000) finishTeleport();
                if (awaitTp >= 0 && now - awaitTpSince > 3000) awaitTp = -1;
                if (pendingSign != null && now - pendingSignAt > 3000) pendingSign = null;
                for (Iterator<Later> it = later.iterator(); it.hasNext(); ) { Later l = it.next(); if (l.at <= now) { it.remove(); l.r.run(); } }
                flushAcks(now);
            }
        }
    }

    // ======================= Java: запись =======================
    private synchronized void send(int id, JBuf body) {
        if (out == null) return;
        try {
            int idLen = JBuf.varintSize(id);
            JBuf f = new JBuf();
            f.varint(idLen + body.len).varint(id).raw(body.b, 0, body.len);
            out.write(f.b, 0, f.len);
            out.flush();
        } catch (IOException e) {
            if (alive) close("Связь с игрой потеряна");
        }
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                default: if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.toString();
    }

    static String chat(String s) { return "{\"text\":\"" + esc(s) + "\"}"; }

    private void systemChat(String json, boolean actionBar) { send(C_SYSTEM_CHAT, new JBuf().str(json).bool(actionBar)); }

    // ======================= Java → обработка =======================
    private void onJava(int id, JBuf b) {
        if (jState == 0) {
            if (id == 0x00) {
                b.varint(); b.str(); b.u16();
                int next = b.varint();
                jState = next == 1 ? 1 : 2;
            }
            return;
        }
        if (jState == 1) {
            if (id == 0x00) {
                String st = "{\"version\":{\"name\":\"Bridge0_14\",\"protocol\":763},\"players\":{\"max\":20,\"online\":0},\"description\":"
                        + chat(cfg.motd == null ? "Сервер 0.14" : cfg.motd) + "}";
                send(0x00, new JBuf().str(st));
            } else if (id == 0x01) {
                send(0x01, new JBuf().i64(b.i64()));
            }
            return;
        }
        if (jState == 2) {
            if (id == 0x00) { // login_start
                javaName = b.str();
                String nick = peNick();
                selfUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
                L.status("Подключаюсь к серверу 0.14…");
                startPe();
            }
            return;
        }
        synchronized (this) { onPlay(id, b); }
    }

    private void onPlay(int id, JBuf b) {
        switch (id) {
            case S_TELEPORT_CONFIRM: {
                int tid = b.varint();
                if (tid == awaitTp) awaitTp = -1;
                break;
            }
            case S_POS: { double nx = b.f64(), ny = b.f64(), nz = b.f64(); boolean g = b.bool(); javaMove(nx, ny, nz, yaw, pitch, g); break; }
            case S_POS_LOOK: { double nx = b.f64(), ny = b.f64(), nz = b.f64(); float yw = b.f32(), pt = b.f32(); boolean g = b.bool(); javaMove(nx, ny, nz, yw, pt, g); break; }
            case S_LOOK: { float yw = b.f32(), pt = b.f32(); boolean g = b.bool(); javaMove(x, y, z, yw, pt, g); break; }
            case S_FLYING: { boolean g = b.bool(); javaMove(x, y, z, yaw, pitch, g); break; }
            case S_CHAT: peSend(Proto.text(peNick(), b.str())); break;
            case S_CHAT_COMMAND: peSend(Proto.text(peNick(), "/" + b.str())); break;
            case S_CLIENT_COMMAND: {
                int a = b.varint();
                if (a == 0) respawnRequest();
                break;
            }
            case S_SWING: peSend(Proto.animate(selfEid, 1)); break;
            case S_DIG: {
                int status = b.varint(); int[] p = b.position(); int face = b.u8(); int seq = b.varint();
                switch (status) {
                    case 0: { // начал ломать
                        peSend(Proto.action(selfEid, Proto.ACTION_START_BREAK, p[0], p[1], p[2], face));
                        // в творческом и для «мгновенных» блоков (трава, цветы, факелы) Java больше ничего не пришлёт
                        if (Shapes.instant(stateAt(p[0], p[1], p[2]))) breakBlock(p);
                        else if (gamemode == 1) { final int[] q = p; later(130, () -> breakBlock(q)); }
                        pending(seq, p[0], p[1], p[2]);
                        break;
                    }
                    case 1: peSend(Proto.action(selfEid, Proto.ACTION_ABORT_BREAK, p[0], p[1], p[2], face)); ackNow(seq); break;
                    case 2: // доломал
                        peSend(Proto.action(selfEid, Proto.ACTION_STOP_BREAK, p[0], p[1], p[2], face));
                        breakBlock(p);
                        pending(seq, p[0], p[1], p[2]);
                        break;
                    case 3: case 4: { // выбросить из руки (в 0.14 выбрасывается вся стопка)
                        dropFrom(new int[]{0, heldPeSlot()});
                        ackNow(seq);
                        break;
                    }
                    case 5: peSend(Proto.action(selfEid, 5, 0, 0, 0, 0)); ackNow(seq); break; // отпустить (лук, еда)
                    default: ackNow(seq); break;
                }
                break;
            }
            case S_PLACE: {
                b.varint(); int[] p = b.position(); int face = b.varint();
                float cx = b.f32(), cy = b.f32(), cz = b.f32(); b.bool(); int seq = b.varint();
                int[] it = heldItem();
                int target = peIdAt(p[0], p[1], p[2]);
                peSend(Proto.useItem(p[0], p[1], p[2], face, cx, cy, cz, (float) x, (float) (y + 1.62), (float) z, it, peProtocol, held));
                int[] d = FACE[Math.min(5, Math.max(0, face))];
                if (it[0] == 323) { // табличка: откроем редактор, когда сервер её поставит
                    pendingSign = new int[]{p[0] + d[0], p[1] + d[1], p[2] + d[2]};
                    pendingSignAt = System.currentTimeMillis();
                }
                if (target == 58 && !sneaking) openCraftingTable();
                pending(seq, p[0], p[1], p[2], p[0] + d[0], p[1] + d[1], p[2] + d[2]);
                break;
            }
            case S_USE_ITEM: {
                b.varint(); int seq = b.varint();
                peSend(Proto.useItem(0, 0, 0, 255, 0, 0, 0, (float) x, (float) (y + 1.62), (float) z, heldItem(), peProtocol, held));
                ackNow(seq);
                break;
            }
            case S_USE_ENTITY: {
                int target = b.varint(); int mouse = b.varint();
                Frame fr = frameByJid.get(target);
                if (fr != null) {
                    if (mouse == 1) peSend(Proto.itemFrameDrop(fr.x, fr.y, fr.z, fr.item == null ? empty() : fr.item));
                    else if (mouse == 0) {
                        int face = new int[]{3, 2, 5, 4}[Math.max(0, Math.min(3, peFrameMeta(fr.dir)))];
                        peSend(Proto.useItem(fr.x, fr.y, fr.z, face, 0.5f, 0.5f, 0.5f, (float) x, (float) (y + 1.62), (float) z, heldItem(), peProtocol, held));
                    }
                    break;
                }
                Long pe = javaToPe.get(target);
                if (pe != null) {
                    if (mouse == 1) peSend(Proto.interact(2, pe));       // удар
                    else if (mouse == 0) peSend(Proto.interact(1, pe));  // ПКМ по сущности
                }
                break;
            }
            case S_HELD_SLOT: {
                int s = b.i16();
                if (s >= 0 && s < 9) { held = s; equipHeld(); }
                break;
            }
            case S_CREATIVE_SLOT: { int slot = b.i16(); int[] it = b.slot(); creativeSlot(slot, it); break; }
            case S_WINDOW_CLICK: windowClick(b); break;
            case S_CLOSE_WINDOW: closeWindowFromJava(b.u8()); break;
            case S_UPDATE_SIGN: {
                int[] p = b.position(); b.bool();
                String[] lines = {b.str(), b.str(), b.str(), b.str()};
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", "Sign");
                for (int i = 0; i < 4; i++) t.put("Text" + (i + 1), lines[i]);
                t.put("x", p[0]); t.put("y", p[1]); t.put("z", p[2]);
                peSend(Proto.blockEntityData(p[0], p[1], p[2], Nbt.writeLE(t)));
                ChunkConv.Chunk c = chunks.get(ck(p[0] >> 4, p[2] >> 4));
                if (c != null && p[1] >= 0 && p[1] < 128) c.tiles.put(ChunkConv.idx(p[0] & 15, p[1], p[2] & 15), t);
                break;
            }
            case S_ACTION: {
                b.varint(); int a = b.varint();
                switch (a) {
                    case 0: sneaking = true; peSend(Proto.action(selfEid, Proto.ACTION_START_SNEAK, 0, 0, 0, 0)); break;
                    case 1: sneaking = false; peSend(Proto.action(selfEid, Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); break;
                    case 3: peSend(Proto.action(selfEid, Proto.ACTION_START_SPRINT, 0, 0, 0, 0)); break;
                    case 4: peSend(Proto.action(selfEid, Proto.ACTION_STOP_SPRINT, 0, 0, 0, 0)); break;
                    default: break;
                }
                break;
            }
            default: break;
        }
    }

    static final int[][] FACE = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private void breakBlock(int[] p) {
        peSend(Proto.removeBlock(selfEid, p[0], p[1], p[2]));
        long k = pk(p[0], p[1], p[2]);
        if (k != breakPos) breakRetried = false;
        breakPos = k; breakAt = System.currentTimeMillis();
    }

    private void later(long ms, Runnable r) { Later l = new Later(); l.at = System.currentTimeMillis() + ms; l.r = r; later.add(l); }

    private int[] heldItem() { return inv[heldPeSlot()]; }

    private int heldPeSlot() { int s = hotbar[held]; return s >= 0 && s < 36 ? s : held; }

    private void equipHeld() {
        int s = heldPeSlot();
        peSend(Proto.mobEquipment(selfEid, inv[s], s + 9, held));
    }

    // ---------------- подтверждения действий с блоками ----------------
    private static long pk(int bx, int by, int bz) { return ((long) (bx & 0x3FFFFFF) << 38) | ((long) (bz & 0x3FFFFFF) << 12) | (by & 0xFFF); }

    private void pending(int seq, int... xyz) {
        Pend p = new Pend(); p.seq = seq; p.at = System.currentTimeMillis();
        p.pos = new long[xyz.length / 3];
        for (int i = 0; i < p.pos.length; i++) p.pos[i] = pk(xyz[i * 3], xyz[i * 3 + 1], xyz[i * 3 + 2]);
        pend.add(p);
    }

    private void ackNow(int seq) {
        Pend p = new Pend(); p.seq = seq; p.pos = new long[0]; p.done = true; p.at = System.currentTimeMillis();
        pend.add(p);
        flushAcks(p.at);
    }

    private void blockAnswered(int bx, int by, int bz) {
        long k = pk(bx, by, bz);
        for (Pend p : pend) for (long q : p.pos) if (q == k) p.done = true;
    }

    private void flushAcks(long now) {
        int ack = -1;
        while (!pend.isEmpty()) {
            Pend h = pend.peek();
            if (!h.done && now - h.at < 1000) break;
            ack = Math.max(ack, h.seq);
            pend.poll();
        }
        if (ack >= 0 && joined) send(C_ACK_DIG, new JBuf().varint(ack));
    }

    // ---------------- движение и телепорты ----------------
    private long lastMoveSent; private double sentX, sentY, sentZ; private float sentYaw, sentPitch;

    private void javaMove(double nx, double ny, double nz, float nyaw, float npitch, boolean onGround) {
        if (awaitTp >= 0 || tpPending) return; // старые координаты до подтверждения телепорта серверу не нужны
        x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch;
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx != lastCx || cz != lastCz) { lastCx = cx; lastCz = cz; send(C_VIEW_POS, new JBuf().varint(cx).varint(cz)); pruneChunks(); }
        if (selfEid < 0 || dead) return;
        long now = System.currentTimeMillis();
        boolean changed = Math.abs(x - sentX) > 1e-4 || Math.abs(y - sentY) > 1e-4 || Math.abs(z - sentZ) > 1e-4
                || Math.abs(yaw - sentYaw) > 0.01f || Math.abs(pitch - sentPitch) > 0.01f;
        if (!changed && now - lastMoveSent < 1000) return;
        lastMoveSent = now; sentX = x; sentY = y; sentZ = z; sentYaw = yaw; sentPitch = pitch;
        peSend(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
    }

    /** Сервер прислал нам нашу же позицию. mode 1 — телепорт; mode 0 — обычно эхо (так делает мир, открытый на телефоне). */
    private void serverMove(double nx, double ny, double nz, float nyaw, float npitch, int mode) {
        double dist = Math.sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y) + (nz - z) * (nz - z));
        if (!joined) { x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch; return; }
        if (mode != 1 && dist < 3 && !tpPending) return; // эхо своего же движения — не дёргаем игрока
        int far = Math.max(48, cfg.chunkRadius * 16 + 8);
        double hdist = Math.hypot(nx - x, nz - z);
        if (tpPending || hdist > far) {
            if (!tpPending) { tpPending = true; tpStart = System.currentTimeMillis(); }
            tpX = nx; tpY = ny; tpZ = nz; tpYaw = nyaw; tpPitch = npitch;
            x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch;
            if (freshChunkAt(nx, nz)) finishTeleport();
            return;
        }
        boolean same = dist < 0.05;
        x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch;
        if (same && awaitTp >= 0) return;
        sendPosition();
    }

    private boolean freshChunkAt(double px, double pz) {
        ChunkConv.Chunk c = chunks.get(ck((int) Math.floor(px) >> 4, (int) Math.floor(pz) >> 4));
        return c != null && c.received >= tpStart - 2000;
    }

    private void finishTeleport() {
        if (!tpPending) return;
        tpPending = false;
        long cutoff = tpStart - 2000;
        Iterator<Map.Entry<Long, ChunkConv.Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) { Map.Entry<Long, ChunkConv.Chunk> e = it.next(); if (e.getValue().received < cutoff) { dropFramesIn(e.getKey(), false); it.remove(); } }
        worldB = !worldB;
        send(C_RESPAWN, respawnPacket());
        x = tpX; y = tpY; z = tpZ; yaw = tpYaw; pitch = tpPitch;
        afterWorldReset(true);
        L.log("Переход: " + (int) x + " " + (int) y + " " + (int) z);
    }

    private JBuf respawnPacket() {
        return new JBuf().str("minecraft:overworld").str(worldB ? WORLD_B : WORLD_A).i64(0).u8(gamemode).u8(255)
                .bool(false).bool(false).u8(0).bool(false).varint(0);
    }

    /** После respawn-пакета клиент всё забыл — отдаём заново. */
    private void afterWorldReset(boolean newWorld) {
        sendAbilities();
        send(C_HELD_SLOT, new JBuf().u8(held));
        sendSpawn();
        sendTime();
        sendWeather();
        lastCx = (int) Math.floor(x) >> 4; lastCz = (int) Math.floor(z) >> 4;
        send(C_VIEW_POS, new JBuf().varint(lastCx).varint(lastCz));
        if (newWorld) {
            for (Map.Entry<Long, ChunkConv.Chunk> c : chunks.entrySet()) sendChunk(c.getKey(), c.getValue());
            for (byte[] m : mapPackets.values()) send(C_MAP, new JBuf().raw(m));
        }
        sendPosition();
        sendHealth();
        sendXp();
        selfMeta();
        resetView();
        sendInventory();
        resendEffects();
        if (newWorld) {
            for (Ent e : ents.values()) spawn(e);
            for (Frame f : frames.values()) spawnFrame(f);
        }
    }

    private void pruneChunks() {
        int r = Math.max(2, cfg.chunkRadius) + 3;
        Iterator<Map.Entry<Long, ChunkConv.Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            int cx = (int) (k >> 32), cz = (int) k;
            if (Math.abs(cx - lastCx) > r || Math.abs(cz - lastCz) > r) {
                dropFramesIn(k, true);
                it.remove();
                if (joined) send(C_UNLOAD, new JBuf().i32(cx).i32(cz));
            }
        }
    }

    private String peNick() { return cfg.nick == null || cfg.nick.isEmpty() ? javaName : cfg.nick; }

    // ---------------- смерть ----------------
    private void setHealth(int h) {
        int old = health;
        health = h;
        if (!joined) return;
        sendHealth();
        if (health <= 0 && !dead) {
            dead = true;
            send(C_DEATH, new JBuf().varint(1).str("{\"translate\":\"deathScreen.title\"}"));
        } else if (health > 0 && dead && old <= 0) {
            dead = false;
        }
    }

    private void respawnRequest() {
        peSend(Proto.action(selfEid, Proto.ACTION_RESPAWN, 0, 0, 0, -1));
        if (!dead) return;
        dead = false;
        health = 20;
        if (rx != 0 || ry != 0 || rz != 0) { x = rx; y = ry; z = rz; }
        send(C_RESPAWN, respawnPacket());
        effects.clear();
        afterWorldReset(false);
    }

    // ======================= Сервер 0.14 =======================
    private void startPe() {
        final int gen = ++rakGen;
        try {
            rak = new RakClient(cfg.host, cfg.port, new RakClient.Listener() {
                public void onConnected() { if (gen == rakGen) peConnected(); }
                public void onPayload(byte[] d) { if (gen == rakGen) payload(d); }
                public void onClosed(String reason) { if (gen == rakGen) peClosed(reason); }
                public void log(String msg) { L.log(msg); }
            });
            rak.start();
        } catch (Exception e) {
            loginFail("Не удалось подключиться: " + e.getMessage());
        }
    }

    private void loginFail(String reason) {
        try { if (jState < 3) send(0x00, new JBuf().str(chat(reason))); } catch (Exception ignored) {}
        close(reason);
    }

    private void peSend(byte[] pk) { RakClient r = rak; if (r != null && peSpawned) r.send(pk); }

    private void peConnected() {
        L.status("Вход на сервер 0.14…");
        RakClient r = rak;
        serverProtocol = r != null ? r.gameProtocol : 0;
        // как телефон 0.14.1; если сервер не примет — попробуем его «родной» протокол
        if (!retried) peProtocol = serverProtocol == 45 ? 45 : Proto.PROTOCOL;
        L.log("Протокол сервера " + serverProtocol + ", входим с " + peProtocol);
        peSelfUuid = UUID.nameUUIDFromBytes(("Bridge:" + peNick()).getBytes());
        byte[] skin = cfg.skin != null ? cfg.skin : DefaultSkin.make();
        if (r != null) r.send(Proto.login(peProtocol, peNick(), rnd.nextLong(), peSelfUuid, cfg.host + ":" + cfg.port, skin,
                cfg.slim ? "Standard_CustomSlim" : "Standard_Custom"));
    }

    private void peClosed(String reason) {
        if (!alive) return;
        if (jState == 3) close("Сервер 0.14: " + reason);
        else loginFail("Сервер 0.14: " + reason);
    }

    private void payload(byte[] d) {
        if (d.length < 2) return;
        try {
            handlePe(new Buf(d, (d[0] & 0xff) == 0x8e ? 1 : 0, d.length));
        } catch (Exception e) {
            L.log("Пакет 0.14 0x" + Integer.toHexString(d[(d[0] & 0xff) == 0x8e ? 1 : 0] & 0xff) + ": " + e);
        }
    }

    private void handlePe(Buf b) throws Exception {
        int id = b.u8();
        if (id == Proto.BATCH) {
            int n = b.i32();
            byte[] raw = Proto.inflate(b.take(Math.min(n, b.left())));
            Buf in = new Buf(raw);
            while (in.left() >= 4) {
                int len = in.i32();
                if (len <= 0 || len > in.left()) break;
                int start = in.pos;
                Buf one = new Buf(raw, start, start + len);
                if ((raw[start] & 0xff) == 0x8e && len > 1) one.pos++;
                try { handlePe(one); } catch (Exception e) { L.log("В пачке 0.14: " + e); }
                in.pos = start + len;
            }
            return;
        }
        synchronized (this) {
            peStats.merge(id, 1, Integer::sum);
            pe(id, b);
        }
    }

    static long ck(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private boolean isSelf(long e) { return e == selfEid || e == 0; }

    private int jid(long pe) {
        if (isSelf(pe)) return 1;
        Ent e = ents.get(pe);
        return e != null ? e.jid : -1;
    }

    private void pe(int id, Buf b) {
        switch (id) {
            case Proto.PLAY_STATUS: {
                int st = b.i32();
                if ((st == 1 || st == 2) && !peSpawned) {
                    if (!retried && serverProtocol != 0 && serverProtocol != peProtocol) {
                        retried = true; peProtocol = serverProtocol;
                        L.log("Сервер не принял протокол, пробую " + peProtocol);
                        RakClient old = rak; rakGen++; rak = null;
                        if (old != null) old.close("retry");
                        startPe();
                        return;
                    }
                    loginFail(st == 1 ? "Сервер новее этой версии 0.14" : "Сервер старее этой версии 0.14");
                    return;
                }
                if (st == 3 && !peSpawned) { peSpawned = true; join(); }
                break;
            }
            case Proto.DISCONNECT: {
                String m = b.str();
                if (jState == 3 && joined) close(m.replaceAll("§.", ""));
                else loginFail(m.replaceAll("§.", ""));
                break;
            }
            case Proto.START_GAME: {
                b.i32(); b.u8(); b.i32();
                gamemode = b.i32();
                selfEid = b.i64();
                spawnX = b.i32(); spawnY = b.i32(); spawnZ = b.i32();
                x = b.f32(); y = b.f32(); z = b.f32();
                RakClient r = rak;
                if (r != null) r.send(Proto.requestChunkRadius(cfg.chunkRadius));
                break;
            }
            case Proto.SET_TIME: { time = b.i32(); if (joined) sendTime(); break; }
            case Proto.SET_SPAWN_POSITION: { spawnX = b.i32(); spawnY = b.i32(); spawnZ = b.i32(); if (joined) sendSpawn(); break; }
            case Proto.ADVENTURE_SETTINGS: { advFlags = b.i32(); if (joined) sendAbilities(); break; }
            case Proto.CRAFTING_DATA: { crafting.parse(b); L.log("Рецептов с сервера: " + crafting.size()); break; }
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32(); int order = b.u8(); int n = b.i32();
                ChunkConv.Chunk c = ChunkConv.fromPe(b.take(Math.min(n, b.left())), order);
                long k = ck(cx, cz);
                if (chunks.containsKey(k)) dropFramesIn(k, true);
                chunks.put(k, c);
                fixShapes(cx, cz, c);
                if (joined && !tpPending) {
                    sendChunk(k, c);
                    refreshEdges(cx, cz);
                }
                scanFrames(cx, cz, c);
                if (tpPending && (int) Math.floor(tpX) >> 4 == cx && (int) Math.floor(tpZ) >> 4 == cz) finishTeleport();
                break;
            }
            case Proto.UPDATE_BLOCK: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 11; i++) {
                    int bx = b.i32(), bz = b.i32(), by = b.u8(), bid = b.u8(), fm = b.u8();
                    // сервер вернул блок, который мы только что сломали (сломали «слишком быстро» для 0.14) — повторим один раз
                    if (bid != 0 && pk(bx, by, bz) == breakPos && !breakRetried && System.currentTimeMillis() - breakAt < 600) {
                        breakRetried = true;
                        final int[] q = {bx, by, bz};
                        later(200, () -> { if (peIdAt(q[0], q[1], q[2]) != 0) peSend(Proto.removeBlock(selfEid, q[0], q[1], q[2])); });
                    }
                    setBlockLocal(bx, by, bz, bid, fm & 15, true);
                    blockAnswered(bx, by, bz);
                    if (pendingSign != null && pendingSign[0] == bx && pendingSign[1] == by && pendingSign[2] == bz && (bid == 63 || bid == 68)) {
                        if (joined) send(C_OPEN_SIGN, new JBuf().position(bx, by, bz).bool(true));
                        pendingSign = null;
                    }
                }
                flushAcks(System.currentTimeMillis());
                break;
            }
            case Proto.BLOCK_ENTITY_DATA: {
                int bx = b.i32(), by = b.i32(), bz = b.i32();
                Object o = Nbt.readLE(b);
                if (!(o instanceof Map) || by < 0 || by >= 128) break;
                @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) o;
                ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
                if (c == null) break;
                int ix = ChunkConv.idx(bx & 15, by, bz & 15);
                c.tiles.put(ix, t);
                int pid = c.pe[ix] & 0xff;
                if (joined && (pid == 63 || pid == 68))
                    send(C_TILE_DATA, new JBuf().position(bx, by, bz).varint(ChunkConv.BE_SIGN).raw(ChunkConv.beNbt(c, ix)));
                if (pid == 199) updateFrame(bx, by, bz, c.state[ix], t);
                break;
            }
            case Proto.EXPLODE: {
                float ex = b.f32(), ey = b.f32(), ez = b.f32(), r = b.f32(); int n = b.i32();
                int bx = (int) Math.floor(ex), by = (int) Math.floor(ey), bz = (int) Math.floor(ez);
                for (int i = 0; i < n && b.left() >= 3; i++) {
                    int dx = (byte) b.u8(), dy = (byte) b.u8(), dz = (byte) b.u8();
                    setBlockLocal(bx + dx, by + dy, bz + dz, 0, 0, true);
                }
                if (joined) send(C_EXPLOSION, new JBuf().f64(ex).f64(ey).f64(ez).f32(r).varint(0).f32(0).f32(0).f32(0));
                break;
            }
            case Proto.LEVEL_EVENT: {
                int ev = b.s16();
                switch (ev) {
                    case 3001: raining = true; break;
                    case 3002: thunder = true; break;
                    case 3003: raining = false; break;
                    case 3004: thunder = false; break;
                    default: return;
                }
                if (joined) sendWeather();
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float pp = b.f32();
                int mode = b.left() > 0 ? b.u8() : 0;
                if (isSelf(e)) {
                    serverMove(px, py - 1.62, pz, pyaw, pp, mode);
                } else {
                    Ent en = ents.get(e);
                    if (en == null) break;
                    en.x = px; en.y = py - en.eye + holoLift(en); en.z = pz; en.yaw = pyaw; en.pitch = pp;
                    if (joined) teleport(en);
                }
                break;
            }
            case Proto.ADD_PLAYER: {
                UUID u = new UUID(b.i64(), b.i64());
                String name = b.str();
                long e = b.i64();
                if (isSelf(e)) break;
                Ent en = new Ent();
                en.pe = e; en.player = true; en.uuid = u; en.name = name; en.jtype = Tables.PLAYER_TYPE;
                en.eye = 1.62f; en.height = 1.8f;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                b.f32(); b.f32(); b.f32();
                en.yaw = b.f32(); b.f32(); en.pitch = b.f32();
                try { int[] hand = Proto.readSlot(b); en.gear = new int[5][]; en.gear[0] = hand; applyMeta(en, readMeta(b), false); } catch (Exception ignored) {}
                if (en.hologram) { en.jtype = TEXT_DISPLAY_TYPE; en.uuid = UUID.randomUUID(); en.y += holoLift(en); }
                en.jid = nextJavaId++;
                addEnt(en);
                break;
            }
            case Proto.ADD_ENTITY: {
                long e = b.i64();
                int type = b.i32() & 0xff;
                Ent en = new Ent();
                en.pe = e; en.uuid = UUID.randomUUID(); en.peType = type;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                b.f32(); b.f32(); b.f32();
                en.yaw = b.f32(); en.pitch = b.f32();
                en.height = height(type); en.eye = en.height / 2 + 0.1f;
                try { applyMeta(en, readMeta(b), false); } catch (Exception ignored) {}
                if (en.hologram) {
                    en.jtype = TEXT_DISPLAY_TYPE;
                    en.y += holoLift(en);
                } else {
                    int jt = Tables.entity(type);
                    if (jt < 0) break;
                    en.jtype = jt;
                }
                en.jid = nextJavaId++;
                addEnt(en);
                break;
            }
            case Proto.ADD_PAINTING: {
                long e = b.i64(); int px = b.i32(), py = b.i32(), pz = b.i32(), dir = b.i32(); String title = b.str();
                Ent en = new Ent();
                en.pe = e; en.uuid = UUID.randomUUID(); en.jtype = PAINTING_TYPE; en.jid = nextJavaId++;
                en.x = px + 0.5f; en.y = py + 0.5f; en.z = pz + 0.5f;
                en.paintingDir = new int[]{3, 4, 2, 5}[dir & 3];
                en.paintingVariant = paintingVariant(title);
                addEnt(en);
                break;
            }
            case Proto.ADD_ITEM_ENTITY: {
                long e = b.i64();
                int[] it = Proto.readSlot(b);
                Ent en = new Ent();
                en.pe = e; en.jid = nextJavaId++; en.jtype = Tables.ITEM_ENTITY_TYPE; en.uuid = UUID.randomUUID(); en.item = it;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                addEnt(en);
                break;
            }
            case Proto.SET_ENTITY_DATA: {
                long e = b.i64();
                Map<Integer, Object> m = readMeta(b);
                Ent en = ents.get(e);
                if (en != null) applyMeta(en, m, joined);
                break;
            }
            case Proto.REMOVE_PLAYER: case Proto.REMOVE_ENTITY: { removeEnt(b.i64()); break; }
            case Proto.TAKE_ITEM_ENTITY: {
                long target = b.i64(), e = b.i64();
                Ent en = ents.get(e);
                if (en != null && joined) send(C_COLLECT, new JBuf().varint(en.jid).varint(jid(target) < 0 ? 1 : jid(target)).varint(en.item != null ? en.item[1] : 1));
                removeEnt(e);
                break;
            }
            case Proto.MOVE_ENTITY: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 32; i++) {
                    long e = b.i64();
                    float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(), hy = b.f32(), pp = b.f32();
                    Ent en = ents.get(e);
                    if (en == null) continue;
                    en.x = px; en.y = py - en.eye + holoLift(en); en.z = pz; en.yaw = pyaw; en.pitch = pp;
                    if (joined) teleport(en);
                }
                break;
            }
            case Proto.SET_ENTITY_MOTION: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 20; i++) {
                    long e = b.i64(); float mx = b.f32(), my = b.f32(), mz = b.f32();
                    int j = jid(e);
                    if (j > 0 && joined) send(C_VELOCITY, new JBuf().varint(j).i16(v(mx)).i16(v(my)).i16(v(mz)));
                }
                break;
            }
            case Proto.ANIMATE: {
                int action = b.u8(); long e = b.i64();
                int j = jid(e);
                if (j > 1 && action == 1 && joined) send(C_ANIMATION, new JBuf().varint(j).u8(0));
                break;
            }
            case Proto.ENTITY_EVENT: {
                long e = b.i64(); int ev = b.u8();
                int j = jid(e);
                if (j > 0 && joined) {
                    if (ev == 2) send(C_HURT, new JBuf().varint(j).f32(0));
                    else if (ev == 3) { if (j > 1) send(C_ENTITY_STATUS, new JBuf().i32(j).u8(3)); else setHealth(0); }
                }
                break;
            }
            case Proto.MOB_EFFECT: {
                long e = b.i64(); int ev = b.u8(), eff = b.u8(), amp = b.u8(); boolean particles = b.u8() != 0; int dur = b.i32();
                int j = jid(e);
                if (eff < 1 || eff > 33) break;
                if (isSelf(e)) {
                    if (ev == 3) effects.remove(eff);
                    else effects.put(eff, new int[]{amp, dur, particles ? 1 : 0, (int) (System.currentTimeMillis() / 1000)});
                }
                if (j > 0 && joined) {
                    if (ev == 3) send(C_REMOVE_EFFECT, new JBuf().varint(j).varint(eff));
                    else sendEffect(j, eff, amp, dur, particles);
                }
                break;
            }
            case Proto.UPDATE_ATTRIBUTES: {
                long e = b.i64(); int n = b.u16();
                boolean hp = false, xp = false; int newHealth = health;
                for (int i = 0; i < n && b.left() > 12; i++) {
                    b.f32(); b.f32(); float val = b.f32(); String name = b.str();
                    if (!isSelf(e)) continue;
                    switch (name) {
                        case "minecraft:health": case "generic.health": newHealth = (int) Math.ceil(val); hp = true; break;
                        case "minecraft:player.hunger": case "player.hunger": food = (int) val; hp = true; break;
                        case "minecraft:player.saturation": case "player.saturation": saturation = val; hp = true; break;
                        case "minecraft:player.experience": case "player.experience": xpBar = val; xp = true; break;
                        case "minecraft:player.level": case "player.level": xpLevel = (int) val; xp = true; break;
                        default: break;
                    }
                }
                if (hp) setHealth(newHealth);
                if (joined && xp) sendXp();
                break;
            }
            case Proto.SET_HEALTH: { setHealth(b.i32()); break; }
            case Proto.RESPAWN: {
                double nx = b.f32(), ny = b.f32() - 1.62, nz = b.f32();
                rx = nx; ry = ny; rz = nz;
                if (!dead && joined && health > 0) serverMove(nx, ny, nz, yaw, pitch, 1);
                break;
            }
            case Proto.SET_PLAYER_GAMETYPE: {
                gamemode = b.i32();
                if (joined) { send(C_GAME_EVENT, new JBuf().u8(3).f32(gamemode)); sendAbilities(); }
                break;
            }
            case Proto.PLAYER_LIST: {
                int type = b.u8(); int n = b.i32();
                for (int i = 0; i < n && b.left() >= 16; i++) {
                    UUID u = new UUID(b.i64(), b.i64());
                    if (type == 0) {
                        b.i64(); String name = b.str(); String skinName = b.str(); byte[] skin = b.take(b.u16());
                        boolean slim = skinName.contains("Slim") || skinName.contains("Alex");
                        boolean self = u.equals(peSelfUuid) || name.replaceAll("§.", "").trim().equalsIgnoreCase(peNick());
                        boolean okSkin = skin.length == 8192 || skin.length == 16384;
                        if (self) {
                            if (cfg.skin == null && okSkin) L.skin(selfUuid, skin, slim);
                            if (joined) send(C_PLAYER_INFO, new JBuf().u8(0x20).varint(1).uuid(selfUuid).bool(true).str(chat(name)));
                            continue;
                        }
                        if (okSkin) L.skin(u, skin, slim);
                        tabList.put(u, name);
                        if (joined) sendInfo(u, name, true);
                    } else {
                        tabList.remove(u);
                        if (joined) {
                            boolean spawned = false;
                            for (Ent e : ents.values()) if (u.equals(e.uuid)) spawned = true;
                            if (spawned) send(C_PLAYER_INFO, new JBuf().u8(0x08).varint(1).uuid(u).bool(false));
                            else send(C_PLAYER_REMOVE, new JBuf().varint(1).uuid(u));
                        }
                    }
                }
                break;
            }
            case Proto.TEXT: {
                int type = b.u8();
                String src = "", msg;
                List<String> params = new ArrayList<>();
                if (type == Proto.TEXT_CHAT || type == Proto.TEXT_POPUP) { src = b.str(); msg = b.str(); }
                else if (type == Proto.TEXT_TRANSLATION) {
                    msg = b.str(); int c = b.u8();
                    for (int i = 0; i < c; i++) params.add(b.str());
                } else msg = b.str();
                if (!joined) break;
                if (type == Proto.TEXT_CHAT) systemChat(chat(src.isEmpty() ? msg : "<" + src + "> " + msg), false);
                else if (type == Proto.TEXT_TRANSLATION) {
                    String key = msg.startsWith("%") ? msg.substring(1) : msg;
                    StringBuilder js = new StringBuilder("{\"translate\":\"" + esc(key) + "\",\"with\":[");
                    for (int i = 0; i < params.size(); i++) { if (i > 0) js.append(','); String p = params.get(i); js.append('"').append(esc(p.startsWith("%") ? p.substring(1) : p)).append('"'); }
                    js.append("]}");
                    systemChat(js.toString(), false);
                } else if (type == Proto.TEXT_POPUP || type == Proto.TEXT_TIP) L.popup(msg, type == Proto.TEXT_TIP);
                else systemChat(chat(msg), false);
                break;
            }
            case Proto.CONTAINER_OPEN: {
                int w = b.u8(), type = b.u8(), slots = b.u16();
                openContainer(w, type, slots);
                break;
            }
            case Proto.CONTAINER_CLOSE: {
                int w = b.u8();
                if (w == openWin) { closeWindowLocal(); if (joined) send(C_CLOSE_WINDOW, new JBuf().u8(w)); }
                break;
            }
            case Proto.CONTAINER_SET_DATA: {
                int w = b.u8(), prop = b.s16(), val = b.s16();
                if (!joined || w != openWin) break;
                if (openType == 2) { // печь
                    if (prop == 0) { send(C_PROGRESS, new JBuf().u8(w).i16(2).i16(val)); send(C_PROGRESS, new JBuf().u8(w).i16(3).i16(200)); }
                    else if (prop == 1) { send(C_PROGRESS, new JBuf().u8(w).i16(0).i16(val)); send(C_PROGRESS, new JBuf().u8(w).i16(1).i16(burnMax)); }
                } else if (openType == 4 && prop == 0) send(C_PROGRESS, new JBuf().u8(w).i16(0).i16(val));
                break;
            }
            case Proto.CONTAINER_SET_CONTENT: {
                int win = b.u8();
                int n = b.u16();
                List<int[]> slots = new ArrayList<>();
                for (int i = 0; i < n; i++) slots.add(Proto.readSlot(b));
                if (win == 0) {
                    for (int i = 0; i < 36; i++) inv[i] = i < slots.size() ? slots.get(i) : empty();
                    if (b.left() >= 2) {
                        int hn = b.u16();
                        for (int i = 0; i < hn && b.left() >= 4; i++) { int v = b.i32(); if (i < 9) hotbar[i] = v >= 9 ? v - 9 : (v >= 0 ? v : i); }
                    }
                    serverChanged(true);
                } else if (win == PE_ARMOR_WINDOW) {
                    for (int i = 0; i < 4; i++) armor[i] = i < slots.size() ? slots.get(i) : empty();
                    serverChanged(false);
                } else if (win == openWin && !isCraftWindow(win)) {
                    int[][] c = new int[Math.max(openSlots, slots.size())][];
                    for (int i = 0; i < c.length; i++) c[i] = i < slots.size() ? slots.get(i) : empty();
                    cont = c;
                    serverChanged(true);
                }
                break;
            }
            case Proto.CONTAINER_SET_SLOT: {
                int win = b.u8(); int slot = b.s16(); b.s16();
                int[] it = Proto.readSlot(b);
                if (win == 0 && slot >= 0 && slot < 36) { inv[slot] = it; serverChanged(false); }
                else if (win == PE_ARMOR_WINDOW && slot >= 0 && slot < 4) { armor[slot] = it; serverChanged(false); }
                else if (win == openWin && slot >= 0 && slot < cont.length) { cont[slot] = it; serverChanged(false); }
                break;
            }
            case Proto.MOB_EQUIPMENT: {
                long e = b.i64(); int[] it = Proto.readSlot(b); int slot = b.u8(), sel = b.u8();
                if (isSelf(e)) {
                    if (sel < 9) {
                        held = sel;
                        if (slot >= 9 && slot - 9 < 36) hotbar[sel] = slot - 9;
                        if (joined) send(C_HELD_SLOT, new JBuf().u8(held));
                    }
                } else {
                    Ent en = ents.get(e);
                    if (en == null) break;
                    if (en.gear == null) en.gear = new int[5][];
                    en.gear[0] = it;
                    if (joined) sendGear(en);
                }
                break;
            }
            case Proto.MOB_ARMOR_EQUIPMENT: {
                long e = b.i64();
                int[][] a = new int[4][];
                for (int i = 0; i < 4; i++) a[i] = Proto.readSlot(b);
                if (isSelf(e)) {
                    for (int i = 0; i < 4; i++) armor[i] = a[i];
                    serverChanged(false);
                } else {
                    Ent en = ents.get(e);
                    if (en == null) break;
                    if (en.gear == null) en.gear = new int[5][];
                    for (int i = 0; i < 4; i++) en.gear[i + 1] = a[i];
                    if (joined) sendGear(en);
                }
                break;
            }
            case Proto.MAP_ITEM_DATA: mapData(b); break;
            default: break;
        }
    }

    private static int v(float m) { return (int) Math.max(-32768, Math.min(32767, m * 8000)); }

    /** Рост сущности 0.14 по типу. */
    private static float height(int t) {
        switch (t) {
            case 10: return 0.7f; case 11: case 16: return 1.4f; case 12: return 0.9f; case 13: return 1.3f;
            case 14: return 0.85f; case 15: case 32: case 36: case 43: case 44: return 1.8f; case 17: return 0.8f;
            case 18: return 0.5f; case 19: return 0.9f; case 20: return 2.7f; case 21: return 1.9f;
            case 22: return 0.7f; case 33: return 1.7f; case 34: return 1.99f; case 35: return 0.9f;
            case 37: case 42: return 0.51f; case 38: return 2.9f; case 39: return 0.3f; case 40: return 0.5f;
            case 41: return 4f; case 45: return 1.95f; case 63: return 1.8f; case 64: return 0.25f; case 65: case 66: return 0.98f;
            case 84: return 0.7f; case 90: return 0.6f;
            default: return 0.25f;
        }
    }

    /** Где 0.14 рисует подпись: над головой сущности. Для «плавающего текста» на предмете это ровно его точка. */
    private static float holoLift(Ent e) {
        if (!e.hologram) return 0;
        if (e.peType == 64) return 0.75f;
        return e.height + 0.25f;
    }

    private static int paintingVariant(String t) {
        String[] names = {"kebab", "aztec", "alban", "aztec2", "bomb", "plant", "wasteland", "pool", "courbet", "sea", "sunset",
                "creebet", "wanderer", "graham", "match", "bust", "stage", "void", "skullandroses", "wither", "fighters", "pointer",
                "pigscene", "burningskull", "skeleton", "donkeykong"};
        String k = t.toLowerCase().replace("_", "").replace(" ", "");
        for (int i = 0; i < names.length; i++) if (names[i].equals(k)) return i;
        return 0;
    }

    // ======================= блоки =======================
    private int stateAt(int bx, int by, int bz) {
        if (by < 0 || by >= 128) return 0;
        ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
        return c == null ? 0 : c.state[ChunkConv.idx(bx & 15, by, bz & 15)];
    }

    private int peIdAt(int bx, int by, int bz) {
        if (by < 0 || by >= 128) return 0;
        ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
        return c == null ? 0 : c.pe[ChunkConv.idx(bx & 15, by, bz & 15)] & 0xff;
    }

    private final Shapes.Lookup lookup = this::stateAt;

    private void setBlockLocal(int bx, int by, int bz, int bid, int meta, boolean notify) {
        if (by < 0 || by >= 128) return;
        ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
        int st = Tables.block(bid, meta);
        if (c != null) {
            int ix = ChunkConv.idx(bx & 15, by, bz & 15);
            int oldPe = c.pe[ix] & 0xff;
            c.state[ix] = st; c.pe[ix] = (byte) bid;
            if (bid != 63 && bid != 68 && bid != 199) c.tiles.remove(ix);
            st = Shapes.fix(st, bx, by, bz, lookup);
            c.state[ix] = st;
            if (oldPe == 199 && bid != 199) removeFrame(bx, by, bz);
            if (bid == 199) updateFrame(bx, by, bz, meta, c.tiles.get(ix));
        }
        if (notify && joined) {
            send(C_BLOCK_CHANGE, new JBuf().position(bx, by, bz).varint(st));
            int be = ChunkConv.beType(bid);
            if (be == ChunkConv.BE_SIGN && c != null)
                send(C_TILE_DATA, new JBuf().position(bx, by, bz).varint(be).raw(ChunkConv.beNbt(c, ChunkConv.idx(bx & 15, by, bz & 15))));
        }
        for (int[] d : FACE) {
            if (d[1] < 0) continue;
            refreshShape(bx + d[0], by + d[1], bz + d[2], notify);
        }
    }

    private void refreshShape(int bx, int by, int bz, boolean notify) {
        if (by < 0 || by >= 128) return;
        ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
        if (c == null) return;
        int ix = ChunkConv.idx(bx & 15, by, bz & 15);
        int old = c.state[ix];
        if (!Shapes.connectable(old)) return;
        int nw = Shapes.fix(old, bx, by, bz, lookup);
        if (nw != old) {
            c.state[ix] = nw;
            if (notify && joined && !tpPending) send(C_BLOCK_CHANGE, new JBuf().position(bx, by, bz).varint(nw));
        }
    }

    private void fixShapes(int cx, int cz, ChunkConv.Chunk c) {
        for (int i = 0; i < 32768; i++) {
            int st = c.state[i];
            if (!Shapes.connectable(st)) continue;
            int lx = i >> 11, lz = (i >> 7) & 15, ly = i & 127;
            c.state[i] = Shapes.fix(st, (cx << 4) | lx, ly, (cz << 4) | lz, lookup);
        }
    }

    private void refreshEdges(int cx, int cz) {
        int bx0 = cx << 4, bz0 = cz << 4;
        for (int i = 0; i < 16; i++) for (int y0 = 0; y0 < 128; y0++) {
            refreshShape(bx0 - 1, y0, bz0 + i, true);
            refreshShape(bx0 + 16, y0, bz0 + i, true);
            refreshShape(bx0 + i, y0, bz0 - 1, true);
            refreshShape(bx0 + i, y0, bz0 + 16, true);
        }
    }

    private void sendChunk(long k, ChunkConv.Chunk c) {
        int cx = (int) (k >> 32), cz = (int) k;
        send(C_CHUNK, new JBuf().raw(ChunkConv.toJava(cx, cz, c)));
    }

    // ======================= рамки и карты =======================
    private static int javaFrameDir(int meta) { return new int[]{3, 2, 4, 5}[meta & 3]; }
    private static int peFrameMeta(int javaDir) { switch (javaDir) { case 3: return 0; case 2: return 1; case 4: return 2; default: return 3; } }

    private void scanFrames(int cx, int cz, ChunkConv.Chunk c) {
        for (int i = 0; i < 32768; i++) {
            if ((c.pe[i] & 0xff) != 199) continue;
            int lx = i >> 11, lz = (i >> 7) & 15, ly = i & 127;
            updateFrame((cx << 4) | lx, ly, (cz << 4) | lz, -1, c.tiles.get(i));
        }
    }

    /** meta — meta блока рамки (или -1 — взять из чанка). */
    @SuppressWarnings("unchecked")
    private void updateFrame(int bx, int by, int bz, int meta, Map<String, Object> tile) {
        long k = pk(bx, by, bz);
        Frame f = frames.get(k);
        boolean isNew = f == null;
        if (isNew) {
            f = new Frame(); f.x = bx; f.y = by; f.z = bz; f.jid = nextJavaId++;
            frames.put(k, f); frameByJid.put(f.jid, f);
        }
        if (meta < 0) {
            ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
            meta = 0;
            if (c != null) for (int m = 0; m < 4; m++) if (Tables.block(199, m) == c.state[ChunkConv.idx(bx & 15, by, bz & 15)]) meta = m;
        }
        int dir = javaFrameDir(meta);
        int[] item = null; int rot = 0;
        if (tile != null && tile.get("Item") instanceof Map) {
            Map<String, Object> im = (Map<String, Object>) tile.get("Item");
            int id = Nbt.intOf(im.get("id"), 0), cnt = Nbt.intOf(im.get("Count"), 1), dmg = Nbt.intOf(im.get("Damage"), 0);
            int ref = 0;
            if (im.get("tag") instanceof Map) ref = ItemNbt.ref(Nbt.writeLEAny((Map<String, Object>) im.get("tag")));
            if (id > 0) item = new int[]{id, Math.max(1, cnt), dmg, ref};
            rot = Nbt.intOf(tile.get("ItemRotation"), 0);
        }
        boolean changed = isNew || f.dir != dir || f.rot != rot || !same(f.item, item);
        if (!isNew && f.dir != dir && joined) { send(C_DESTROY, new JBuf().varint(1).varint(f.jid)); isNew = true; }
        f.dir = dir; f.item = item; f.rot = rot;
        if (!joined || tpPending || !changed) return;
        if (isNew) spawnFrame(f); else frameMeta(f);
    }

    private void spawnFrame(Frame f) {
        send(C_SPAWN_ENTITY, new JBuf().varint(f.jid).uuid(f.uuid).varint(ITEM_FRAME_TYPE).f64(f.x + 0.5).f64(f.y + 0.5).f64(f.z + 0.5)
                .angle(0).angle(0).angle(0).varint(f.dir).i16(0).i16(0).i16(0));
        frameMeta(f);
    }

    private void frameMeta(Frame f) {
        JBuf m = new JBuf().varint(f.jid);
        m.u8(8).varint(7); writeSlot(m, f.item);
        m.u8(9).varint(1).varint(f.rot & 7);
        m.u8(0xff);
        send(C_METADATA, m);
    }

    private void removeFrame(int bx, int by, int bz) {
        Frame f = frames.remove(pk(bx, by, bz));
        if (f == null) return;
        frameByJid.remove(f.jid);
        if (joined) send(C_DESTROY, new JBuf().varint(1).varint(f.jid));
    }

    private void dropFramesIn(long chunkKey, boolean notify) {
        int cx = (int) (chunkKey >> 32), cz = (int) chunkKey;
        Iterator<Frame> it = frames.values().iterator();
        while (it.hasNext()) {
            Frame f = it.next();
            if (f.x >> 4 == cx && f.z >> 4 == cz) {
                it.remove(); frameByJid.remove(f.jid);
                if (notify && joined) send(C_DESTROY, new JBuf().varint(1).varint(f.jid));
            }
        }
    }

    /** Номер карты Java для карты 0.14 (и запрос картинки у сервера, если её ещё нет). */
    private int javaMapId(long peMap) {
        Integer id = mapIds.get(peMap);
        if (id == null) { id = nextMapId++; mapIds.put(peMap, id); }
        if (mapRequested.add(peMap)) peSend(Proto.mapInfoRequest(peMap));
        return id;
    }

    private void mapData(Buf b) {
        long peMap = b.i64();
        int upd = b.i32();
        b.skip(6); b.skip(3);
        if ((upd & 2) == 0 || b.left() < 16) return;
        int cols = b.i32(), rows = b.i32(), xo = b.i32(), zo = b.i32();
        if (cols <= 0 || rows <= 0 || cols > 128 || rows > 128) return;
        int n = Math.min(cols * rows, b.left() / 4);
        byte[] colors = new byte[cols * rows];
        for (int i = 0; i < n; i++) {
            int r = b.u8(), g = b.u8(), bl = b.u8(), a = b.u8();
            colors[i] = MapColors.nearest(r, g, bl, a);
        }
        Integer jidMap = mapIds.get(peMap);
        if (jidMap == null) { jidMap = nextMapId++; mapIds.put(peMap, jidMap); }
        mapRequested.add(peMap);
        JBuf p = new JBuf().varint(jidMap).u8(0).bool(true).bool(false).u8(cols).u8(rows).u8(Math.max(0, Math.min(127, xo))).u8(Math.max(0, Math.min(127, zo)))
                .varint(colors.length).raw(colors);
        byte[] body = p.bytes();
        mapPackets.put(jidMap, body);
        if (joined) send(C_MAP, new JBuf().raw(body));
    }

    // ======================= вход в мир для Java =======================
    private void join() {
        if (joined || out == null) return;
        String nick = peNick();
        send(0x02, new JBuf().uuid(selfUuid).str(nick).varint(0));
        jState = 3;
        JBuf lg = new JBuf();
        lg.i32(1).bool(false).u8(gamemode).u8(255);
        lg.varint(2).str(WORLD_A).str(WORLD_B);
        lg.raw(Tables.CODEC);
        lg.str("minecraft:overworld").str(WORLD_A).i64(0);
        lg.varint(20).varint(Math.max(2, cfg.chunkRadius)).varint(Math.max(2, cfg.chunkRadius));
        lg.bool(false).bool(true).bool(false).bool(false).bool(false).varint(0);
        send(C_LOGIN, lg);
        send(C_FEATURES, new JBuf().varint(1).str("minecraft:vanilla"));
        if (cfg.tags != null) {
            try { byte[] t = cfg.tags.get(); if (t != null) send(C_TAGS, new JBuf().raw(t)); }
            catch (Exception e) { L.log("Теги: " + e); }
        }
        send(C_SERVER_DATA, new JBuf().str(chat("Сервер 0.14")).bool(false).bool(false));
        send(C_TEAMS, new JBuf().str(HIDE_TEAM).u8(0).str(chat("")).u8(0).str("never").str("always").varint(21)
                .str(chat("")).str(chat("")).varint(0));
        joined = true;
        if (cfg.skin != null) L.skin(selfUuid, cfg.skin, cfg.slim);
        send(C_PLAYER_INFO, new JBuf().u8(0x1D).varint(1).uuid(selfUuid).str(trim16(nick)).varint(0).varint(gamemode).u8(1).varint(0));
        for (Map.Entry<UUID, String> e : tabList.entrySet()) sendInfo(e.getKey(), e.getValue(), true);
        afterWorldReset(true);
        L.status("В игре: " + cfg.host + ":" + cfg.port);
        L.log("Вошли на сервер 0.14 как " + nick + " (протокол " + peProtocol + ")");
    }

    private void sendInfo(UUID u, String name, boolean listed) {
        send(C_PLAYER_INFO, new JBuf().u8(0x3D).varint(1).uuid(u).str(trim16(name)).varint(0).varint(0).bool(listed).varint(0)
                .bool(true).str(chat(name)));
    }

    private void sendAbilities() {
        boolean fly = gamemode == 1 || (advFlags & 0x80) != 0;
        int flags = (gamemode == 1 ? (0x01 | 0x08) : 0) | (fly ? 0x04 : 0);
        send(C_ABILITIES, new JBuf().u8(flags).f32(0.05f).f32(0.1f));
    }

    private void sendSpawn() { send(C_SPAWN_POS, new JBuf().position(spawnX, spawnY, spawnZ).f32(0)); }

    private void sendPosition() {
        awaitTp = teleportId; awaitTpSince = System.currentTimeMillis();
        send(C_POSITION, new JBuf().f64(x).f64(y).f64(z).f32(yaw).f32(pitch).u8(0).varint(teleportId++));
        sentX = x; sentY = y; sentZ = z; sentYaw = yaw; sentPitch = pitch;
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx != lastCx || cz != lastCz) { lastCx = cx; lastCz = cz; send(C_VIEW_POS, new JBuf().varint(cx).varint(cz)); }
    }

    private void sendHealth() { send(C_HEALTH, new JBuf().f32(Math.max(0, health)).varint(Math.max(0, Math.min(20, food))).f32(saturation)); }

    private void sendXp() { send(C_EXPERIENCE, new JBuf().f32(Math.max(0, Math.min(1, xpBar))).varint(Math.max(0, xpLevel)).varint(0)); }

    /** Свой второй слой скина (шляпа, куртка, рукава) — включён. */
    private void selfMeta() { send(C_METADATA, new JBuf().varint(1).u8(17).varint(0).u8(0x7F).u8(0xff)); }

    private void sendTime() {
        long t = time * 24000L / 19200L;
        send(C_TIME, new JBuf().i64(t).i64(t % 24000));
    }

    private void sendWeather() {
        send(C_GAME_EVENT, new JBuf().u8(raining ? 1 : 2).f32(0));
        send(C_GAME_EVENT, new JBuf().u8(7).f32(raining ? 1 : 0));
        send(C_GAME_EVENT, new JBuf().u8(8).f32(thunder ? 1 : 0));
    }

    private void sendEffect(int jid, int eff, int amp, int dur, boolean particles) {
        send(C_EFFECT, new JBuf().varint(jid).varint(eff).u8(amp).varint(dur < 0 ? -1 : dur).u8((particles ? 0x02 : 0) | 0x04).bool(false));
    }

    private void resendEffects() {
        long nowS = System.currentTimeMillis() / 1000;
        for (Map.Entry<Integer, int[]> e : effects.entrySet()) {
            int[] v = e.getValue();
            long left = v[1] - (nowS - v[3]) * 20L;
            if (left <= 0) continue;
            sendEffect(1, e.getKey(), v[0], (int) Math.min(Integer.MAX_VALUE, left), v[2] != 0);
        }
    }

    // ======================= сущности =======================
    private void addEnt(Ent en) {
        removeEnt(en.pe);
        ents.put(en.pe, en); javaToPe.put(en.jid, en.pe);
        if (joined) spawn(en);
    }

    private static Map<Integer, Object> readMeta(Buf b) {
        Map<Integer, Object> m = new HashMap<>();
        while (b.left() > 0) {
            int h = b.u8();
            if (h == 0x7f) break;
            int type = h >> 5, key = h & 0x1f;
            switch (type) {
                case 0: m.put(key, b.u8()); break;
                case 1: m.put(key, b.lu16()); break;
                case 2: m.put(key, b.li32()); break;
                case 3: m.put(key, b.lf32()); break;
                case 4: { int n = b.lu16(); m.put(key, new String(b.take(n), StandardCharsets.UTF_8)); break; }
                case 5: b.skip(5); break;
                case 6: b.skip(12); break;
                case 7: b.skip(12); break;
                case 8: m.put(key, b.li64()); break;
                default: return m;
            }
        }
        return m;
    }

    private void applyMeta(Ent en, Map<Integer, Object> m, boolean update) {
        boolean changed = false;
        if (m.get(0) instanceof Integer) { en.flags = (Integer) m.get(0); en.invisible = (en.flags & (1 << 5)) != 0; changed = true; }
        if (m.get(2) instanceof String) { en.nametag = (String) m.get(2); changed = true; }
        if (m.get(3) instanceof Integer) { en.showTag = (Integer) m.get(3) != 0; changed = true; }
        if (!update) {
            en.hologram = en.invisible && !en.nametag.trim().isEmpty() && en.jtype != PAINTING_TYPE;
            return;
        }
        if (changed && joined) sendMeta(en);
    }

    private static String clean(String s) { return s.replaceAll("§.", "").trim(); }

    private boolean fancyName(Ent e) {
        if (!e.player || e.nametag.isEmpty()) return false;
        return e.nametag.contains("\n") || !clean(e.nametag).equals(trim16(e.name)) || e.nametag.contains("§");
    }

    private void holoMeta(JBuf m, String text) {
        m.u8(14).varint(0).u8(3);                       // всегда лицом к камере
        m.u8(19).varint(3).f32(4f);                     // размеры для отсечения (иначе текст пропадает у края экрана)
        m.u8(20).varint(3).f32(3f);
        m.u8(22).varint(5).str(chat(text));
        m.u8(23).varint(1).varint(1000);
        m.u8(24).varint(1).varint(0x40000000);
    }

    private void sendMeta(Ent e) {
        if (e.hologram) {
            JBuf m = new JBuf().varint(e.jid);
            m.u8(22).varint(5).str(chat(e.nametag));
            m.u8(0xff);
            send(C_METADATA, m);
            return;
        }
        int jf = 0;
        if ((e.flags & 1) != 0) jf |= 0x01;
        if ((e.flags & 2) != 0) jf |= 0x02;
        if ((e.flags & 8) != 0) jf |= 0x08;
        if (e.invisible) jf |= 0x20;
        JBuf m = new JBuf().varint(e.jid);
        m.u8(0).varint(0).u8(jf);
        if (e.player) {
            m.u8(6).varint(20).varint((e.flags & 2) != 0 ? 5 : 0); // присел
            m.u8(17).varint(0).u8(0x7F);                          // второй слой скина
        } else if (e.jtype != PAINTING_TYPE) {
            boolean named = !e.nametag.isEmpty();
            m.u8(2).varint(6).bool(named); if (named) m.str(chat(e.nametag));
            m.u8(3).varint(8).bool(named && e.showTag);
        }
        m.u8(0xff);
        send(C_METADATA, m);
        if (e.player) updateLabel(e);
    }

    private void updateLabel(Ent e) {
        String profile = trim16(e.name);
        if (!fancyName(e) || e.invisible) {
            if (e.labelJid > 0) {
                send(C_DESTROY, new JBuf().varint(1).varint(e.labelJid));
                e.labelJid = -1;
                if (hiddenNames.remove(profile)) send(C_TEAMS, new JBuf().str(HIDE_TEAM).u8(4).varint(1).str(profile));
            }
            return;
        }
        if (hiddenNames.add(profile)) send(C_TEAMS, new JBuf().str(HIDE_TEAM).u8(3).varint(1).str(profile));
        if (e.labelJid < 0) {
            e.labelJid = nextJavaId++;
            send(C_SPAWN_ENTITY, new JBuf().varint(e.labelJid).uuid(UUID.randomUUID()).varint(TEXT_DISPLAY_TYPE)
                    .f64(e.x).f64(e.y + 2.1).f64(e.z).angle(0).angle(0).angle(0).varint(0).i16(0).i16(0).i16(0));
            send(C_PASSENGERS, new JBuf().varint(e.jid).varint(1).varint(e.labelJid));
        }
        JBuf m = new JBuf().varint(e.labelJid);
        m.u8(10).varint(26).f32(0).f32(0.75f).f32(0);
        holoMeta(m, e.nametag);
        m.u8(0xff);
        send(C_METADATA, m);
    }

    private void spawn(Ent e) {
        if (e.hologram) {
            send(C_SPAWN_ENTITY, new JBuf().varint(e.jid).uuid(e.uuid).varint(TEXT_DISPLAY_TYPE).f64(e.x).f64(e.y).f64(e.z)
                    .angle(0).angle(0).angle(0).varint(0).i16(0).i16(0).i16(0));
            JBuf m = new JBuf().varint(e.jid);
            holoMeta(m, e.nametag);
            m.u8(0xff);
            send(C_METADATA, m);
            return;
        }
        if (e.player) {
            if (!tabList.containsKey(e.uuid)) sendInfo(e.uuid, e.name, false);
            send(C_SPAWN_PLAYER, new JBuf().varint(e.jid).uuid(e.uuid).f64(e.x).f64(e.y).f64(e.z).angle(e.yaw).angle(e.pitch));
            e.labelJid = -1;
            sendMeta(e);
            if (e.gear != null) sendGear(e);
        } else if (e.jtype == PAINTING_TYPE) {
            send(C_SPAWN_ENTITY, new JBuf().varint(e.jid).uuid(e.uuid).varint(PAINTING_TYPE).f64(e.x).f64(e.y).f64(e.z)
                    .angle(0).angle(0).angle(0).varint(e.paintingDir).i16(0).i16(0).i16(0));
            send(C_METADATA, new JBuf().varint(e.jid).u8(8).varint(24).varint(e.paintingVariant).u8(0xff));
            return;
        } else {
            send(C_SPAWN_ENTITY, new JBuf().varint(e.jid).uuid(e.uuid).varint(e.jtype).f64(e.x).f64(e.y).f64(e.z)
                    .angle(e.pitch).angle(e.yaw).angle(e.yaw).varint(0).i16(0).i16(0).i16(0));
            if (e.item != null) {
                JBuf m = new JBuf().varint(e.jid).u8(8).varint(7);
                writeSlot(m, e.item);
                m.u8(0xff);
                send(C_METADATA, m);
            }
            if (e.flags != 0 || !e.nametag.isEmpty()) sendMeta(e);
            if (e.gear != null) sendGear(e);
        }
        send(C_HEAD_ROT, new JBuf().varint(e.jid).angle(e.yaw));
    }

    private void sendGear(Ent e) {
        if (e.gear == null || e.hologram) return;
        int[] javaSlot = {0, 5, 4, 3, 2};
        JBuf b = new JBuf().varint(e.jid);
        for (int i = 0; i < 5; i++) {
            b.u8(javaSlot[i] | (i < 4 ? 0x80 : 0));
            writeSlot(b, e.gear[i]);
        }
        send(C_EQUIPMENT, b);
    }

    static String trim16(String s) { s = s.replaceAll("§.", "").replace("\n", " ").trim(); return s.length() > 16 ? s.substring(0, 16) : (s.isEmpty() ? "?" : s); }

    private void teleport(Ent e) {
        if (e.jtype == PAINTING_TYPE) return;
        send(C_TELEPORT, new JBuf().varint(e.jid).f64(e.x).f64(e.y).f64(e.z).angle(e.yaw).angle(e.pitch).bool(!e.hologram));
        if (!e.hologram) send(C_HEAD_ROT, new JBuf().varint(e.jid).angle(e.yaw));
    }

    private void removeEnt(long pe) {
        Ent e = ents.remove(pe);
        if (e == null) return;
        javaToPe.remove(e.jid);
        if (!joined) return;
        if (e.labelJid > 0) send(C_DESTROY, new JBuf().varint(1).varint(e.labelJid));
        send(C_DESTROY, new JBuf().varint(1).varint(e.jid));
        if (e.player && !e.hologram && !tabList.containsKey(e.uuid)) send(C_PLAYER_REMOVE, new JBuf().varint(1).uuid(e.uuid));
    }

    // ======================= инвентарь =======================
    private static boolean damageable(int id) {
        return (id >= 256 && id <= 259) || id == 261 || (id >= 267 && id <= 279) || (id >= 283 && id <= 286)
                || (id >= 290 && id <= 294) || (id >= 298 && id <= 317) || id == 346 || id == 359;
    }

    private static int maxStack(int[] it) {
        if (it == null) return 64;
        if (damageable(it[0])) return 1;
        switch (it[0]) {
            case 323: case 332: case 344: case 368: return 16;
            case 325: case 354: case 355: case 358: case 373: case 282: case 459: return it[0] == 325 && it[2] == 0 ? 16 : 1;
            default: return 64;
        }
    }

    private void writeSlot(JBuf w, int[] it) {
        if (isEmpty(it)) { w.bool(false); return; }
        int j = Tables.item(it[0], it[2]);
        if (j < 0) { w.bool(false); return; }
        w.bool(true).varint(j).u8(Math.min(127, it[1]));
        int dmg = damageable(it[0]) ? it[2] : 0;
        w.raw(ItemNbt.javaNbt(it, dmg, this::javaMapId));
    }

    static boolean isEmpty(int[] it) { return it == null || it[0] == 0 || it[1] <= 0; }

    private static boolean sameItem(int[] a, int[] b) {
        return !isEmpty(a) && !isEmpty(b) && a[0] == b[0] && a[2] == b[2] && ItemNbt.refOf(a) == ItemNbt.refOf(b);
    }

    static boolean same(int[] a, int[] b) {
        boolean ea = isEmpty(a), eb = isEmpty(b);
        if (ea || eb) return ea == eb;
        return sameItem(a, b) && a[1] == b[1];
    }

    private static int[] copyOf(int[] it) { if (isEmpty(it)) return empty(); int[] c = new int[4]; System.arraycopy(it, 0, c, 0, Math.min(4, it.length)); return c; }

    private static int[] withCount(int[] it, int n) { if (n <= 0) return empty(); int[] c = copyOf(it); c[1] = n; return c; }

    private boolean isCraftWindow(int w) { return w == 0 || (w == openWin && openType == 1); }

    private int gridSize(int w) { return w == 0 ? 2 : 3; }

    /** Слоты окна Java → {окно 0.14, слот}; null — сетка/результат/лишнее. */
    private int[] peOf(int win, int j) {
        if (win == 0) {
            if (j >= 5 && j <= 8) return new int[]{PE_ARMOR_WINDOW, j - 5};
            if (j >= 9 && j <= 35) return new int[]{0, j};
            if (j >= 36 && j <= 44) { int s = hotbar[j - 36]; return new int[]{0, s >= 0 && s < 36 ? s : j - 36}; }
            return null;
        }
        if (win != openWin) return null;
        int n = javaContainerSize();
        if (openType == 1) n = 10;
        else if (j >= 0 && j < n) { int s = toPeContainerSlot(j); return s < 0 ? null : new int[]{win, s}; }
        int r = j - n;
        if (r >= 0 && r < 27) return new int[]{0, r + 9};
        if (r >= 27 && r < 36) { int s = hotbar[r - 27]; return new int[]{0, s >= 0 && s < 36 ? s : r - 27}; }
        return null;
    }

    private int gridIndex(int win, int j) {
        if (!isCraftWindow(win)) return -1;
        if (win == 0) { if (j >= 1 && j <= 4) { int k = j - 1; return (k / 2) * 3 + (k % 2); } return -1; }
        return j >= 1 && j <= 9 ? j - 1 : -1;
    }

    private int windowSize(int win) { return win == 0 ? 46 : (openType == 1 ? 46 : javaContainerSize() + 36); }

    private int activeWindow() { return openWin >= 0 ? openWin : 0; }

    private int[] truthAt(int[] ps) {
        if (ps == null) return null;
        if (ps[0] == 0) return inv[ps[1]];
        if (ps[0] == PE_ARMOR_WINDOW) return armor[ps[1]];
        return ps[1] < cont.length ? cont[ps[1]] : null;
    }

    private int[] viewAt(int[] ps) {
        if (ps == null) return null;
        if (ps[0] == 0) return vInv[ps[1]];
        if (ps[0] == PE_ARMOR_WINDOW) return vArmor[ps[1]];
        return ps[1] < vCont.length ? vCont[ps[1]] : null;
    }

    private void setView(int[] ps, int[] it) {
        if (ps == null) return;
        if (ps[0] == 0) vInv[ps[1]] = copyOf(it);
        else if (ps[0] == PE_ARMOR_WINDOW) vArmor[ps[1]] = copyOf(it);
        else if (ps[1] < vCont.length) vCont[ps[1]] = copyOf(it);
    }

    /** Сколько слотов «склада» (инвентарь + открытый сундук) — общий массив для расчётов. */
    private int[][] joinTruth() {
        int[][] a = new int[36 + cont.length][];
        for (int i = 0; i < 36; i++) a[i] = inv[i];
        for (int i = 0; i < cont.length; i++) a[36 + i] = cont[i];
        return a;
    }

    private int[][] joinView() {
        int[][] a = new int[36 + vCont.length][];
        for (int i = 0; i < 36; i++) a[i] = vInv[i];
        for (int i = 0; i < vCont.length; i++) a[36 + i] = vCont[i];
        return a;
    }

    /** Предметы, которые на сервере ещё лежат в инвентаре, а у Java — в сетке крафта или на курсоре. */
    private List<int[]> held() {
        List<int[]> l = new ArrayList<>();
        for (int[] g : grid) if (!isEmpty(g)) l.add(g);
        if (!isEmpty(jCursor)) l.add(jCursor);
        return l;
    }

    /** Что показать Java: сервер минус то, что в сетке/на курсоре (вычитаем там, где раньше и не хватало). */
    private int[][] display(int[][] t, int[][] oldV, List<int[]> extra) {
        int[][] d = new int[t.length][];
        for (int i = 0; i < t.length; i++) d[i] = copyOf(t[i]);
        for (int[] ex : extra) {
            int left = ex[1];
            for (int pass = 0; pass < 2 && left > 0; pass++) {
                for (int i = t.length - 1; i >= 0 && left > 0; i--) {
                    if (!sameItem(d[i], ex)) continue;
                    int deficit = oldV != null && i < oldV.length ? t[i][1] - (sameItem(oldV[i], ex) ? oldV[i][1] : 0) : 0;
                    if (pass == 0 && deficit <= 0) continue;
                    int take = Math.min(left, pass == 0 ? Math.min(deficit, d[i][1]) : d[i][1]);
                    d[i] = withCount(d[i], d[i][1] - take);
                    left -= take;
                }
            }
        }
        return d;
    }

    /** Раскладка для сервера: то, что видит Java, плюс предметы сетки — туда, откуда они взяты. */
    private int[][] park(int[][] t, int[][] v, List<int[]> extra) {
        int[][] d = new int[v.length][];
        for (int i = 0; i < v.length; i++) d[i] = copyOf(v[i]);
        for (int[] ex : extra) {
            int left = ex[1];
            for (int i = 0; i < d.length && left > 0; i++) {
                if (!sameItem(t[i], ex) || !(isEmpty(d[i]) || sameItem(d[i], ex))) continue;
                int have = isEmpty(d[i]) ? 0 : d[i][1];
                int add = Math.min(left, t[i][1] - have);
                if (add <= 0) continue;
                d[i] = withCount(ex, have + add); left -= add;
            }
            for (int i = 0; i < d.length && left > 0; i++) {
                if (!sameItem(d[i], ex)) continue;
                int add = Math.min(left, maxStack(ex) - d[i][1]);
                if (add <= 0) continue;
                d[i] = withCount(ex, d[i][1] + add); left -= add;
            }
            for (int i = 0; i < d.length && left > 0; i++) {
                if (!isEmpty(d[i])) continue;
                int add = Math.min(left, maxStack(ex));
                d[i] = withCount(ex, add); left -= add;
            }
        }
        return d;
    }

    private void resetView() {
        for (int i = 0; i < 36; i++) vInv[i] = copyOf(inv[i]);
        for (int i = 0; i < 4; i++) vArmor[i] = copyOf(armor[i]);
        vCont = new int[cont.length][];
        for (int i = 0; i < cont.length; i++) vCont[i] = copyOf(cont[i]);
    }

    /** Сервер поменял инвентарь/сундук: пересчитать то, что видит Java, и отправить разницу. */
    private void serverChanged(boolean full) {
        int[][] oldJoin = joinView();
        int[][] oldArmor = new int[4][];
        for (int i = 0; i < 4; i++) oldArmor[i] = vArmor[i];
        int[][] nd = display(joinTruth(), oldJoin, held());
        if (vCont.length != cont.length) { vCont = new int[cont.length][]; for (int i = 0; i < cont.length; i++) vCont[i] = empty(); full = true; }
        for (int i = 0; i < 36; i++) vInv[i] = nd[i];
        for (int i = 0; i < cont.length; i++) vCont[i] = nd[36 + i];
        for (int i = 0; i < 4; i++) vArmor[i] = copyOf(armor[i]);
        if (!joined) return;
        int w = activeWindow();
        if (full) { sendWindow(w); if (w != 0) sendWindow(0); return; }
        int n = windowSize(w);
        for (int j = 0; j < n; j++) {
            int[] ps = peOf(w, j);
            if (ps == null) continue;
            int[] old = ps[0] == 0 ? oldJoin[ps[1]] : ps[0] == PE_ARMOR_WINDOW ? oldArmor[ps[1]] : (ps[1] < oldJoin.length - 36 ? oldJoin[36 + ps[1]] : null);
            int[] now = viewAt(ps);
            if (same(old, now)) continue;
            JBuf b = new JBuf().u8(w).varint(stateId++).i16(j);
            writeSlot(b, now);
            send(C_SET_SLOT, b);
        }
    }

    private void sendInventory() { sendWindow(0); }

    private void sendWindow(int w) {
        if (w != 0 && w != openWin) return;
        int n = windowSize(w);
        JBuf b = new JBuf();
        b.u8(w).varint(stateId++).varint(n);
        for (int j = 0; j < n; j++) {
            int g = gridIndex(w, j);
            if (g >= 0 && w == activeWindow()) writeSlot(b, grid[g]);
            else if (j == 0 && isCraftWindow(w)) writeSlot(b, w == activeWindow() ? craftResult : null);
            else writeSlot(b, viewAt(peOf(w, j)));
        }
        writeSlot(b, w == activeWindow() ? jCursor : null);
        send(C_WINDOW_ITEMS, b);
    }

    /** Предмет Java → 0.14: берём точный id/meta/NBT у похожего предмета, который уже есть (сохраняется прочность и имя). */
    private int[] toPe(int[] jit, int[][] known) {
        if (jit == null || jit[1] <= 0) return empty();
        if (known != null) for (int[] k : known)
            if (!isEmpty(k) && Tables.item(k[0], k[2]) == jit[0]) return new int[]{k[0], jit[1], k[2], ItemNbt.refOf(k)};
        for (int[] k : inv) if (!isEmpty(k) && Tables.item(k[0], k[2]) == jit[0]) return new int[]{k[0], jit[1], k[2], ItemNbt.refOf(k)};
        for (int[] k : grid) if (!isEmpty(k) && Tables.item(k[0], k[2]) == jit[0]) return new int[]{k[0], jit[1], k[2], ItemNbt.refOf(k)};
        if (!isEmpty(jCursor) && Tables.item(jCursor[0], jCursor[2]) == jit[0]) return new int[]{jCursor[0], jit[1], jCursor[2], ItemNbt.refOf(jCursor)};
        int[] p = Tables.itemBack(jit[0]);
        return p == null ? empty() : new int[]{p[0], jit[1], p[1], 0};
    }

    private int[][] allKnown() {
        List<int[]> l = new ArrayList<>();
        for (int[] a : inv) l.add(a);
        for (int[] a : cont) l.add(a);
        for (int[] a : armor) l.add(a);
        return l.toArray(new int[0][]);
    }

    private void windowClick(JBuf b) {
        int win = b.u8(); b.varint(); int slot = b.i16(); int button = b.u8(); int mode = b.varint();
        int n = b.varint();
        int[] slots = new int[n]; int[][] items = new int[n][];
        for (int i = 0; i < n; i++) { slots[i] = b.i16(); items[i] = b.slot(); }
        int[] jcur = b.slot();
        if (win != 0 && win != openWin) { sendWindow(activeWindow()); return; }
        int[][] known = allKnown();
        int[] cursorBefore = jCursor;

        // результат крафта
        if (slot == 0 && isCraftWindow(win)) { craftClick(win, mode, cursorBefore); return; }

        // выбросить: Q над слотом
        if (mode == 4 && slot >= 0) {
            int[] ps = peOf(win, slot);
            int g = gridIndex(win, slot);
            if (ps != null) dropFrom(ps);
            else if (g >= 0 && !isEmpty(grid[g])) { int[] it = grid[g]; grid[g] = empty(); dropStack(it); updateCraft(win); }
            sendWindow(win);
            return;
        }
        // за край окна с предметом на курсоре
        if (slot == -999 && (mode == 0 || mode == 5)) {
            if (!isEmpty(cursorBefore)) {
                int[] it = cursorBefore;
                jCursor = jcur == null ? null : toPe(jcur, known);
                int drop = it[1] - (isEmpty(jCursor) ? 0 : jCursor[1]);
                if (drop > 0) dropStack(withCount(it, drop));
            }
            sendWindow(win);
            return;
        }
        // «тап» по предмету в сундуке, как на телефоне: сразу в инвентарь (магазины в мини-играх ждут именно этого)
        if (win != 0 && !isCraftWindow(win) && (mode == 0 || mode == 1) && slot >= 0 && slot < javaContainerSize() && isEmpty(cursorBefore)) {
            int[] ps = peOf(win, slot);
            int[] it = truthAt(ps);
            if (ps != null && !isEmpty(it)) { tapFromContainer(win, ps, it); return; }
        }

        for (int i = 0; i < n; i++) {
            int j = slots[i];
            int[] pe = toPe(items[i], known);
            int g = gridIndex(win, j);
            if (g >= 0) grid[g] = pe;
            else setView(peOf(win, j), pe);
        }
        jCursor = jcur == null ? null : toPe(jcur, known);
        if (isCraftWindow(win)) updateCraft(win);
        if (isEmpty(jCursor)) syncToServer();
    }

    /** Отправить серверу итог перекладываний (одной сбалансированной транзакцией). */
    private void syncToServer() {
        int[][] t = joinTruth();
        int[][] d = park(t, joinView(), held());
        for (int i = 0; i < d.length; i++) {
            if (same(d[i], t[i])) continue;
            if (i < 36) { peSend(Proto.containerSetSlot(0, i, 0, d[i])); inv[i] = copyOf(d[i]); }
            else { peSend(Proto.containerSetSlot(openWin, i - 36, 0, d[i])); cont[i - 36] = copyOf(d[i]); }
        }
        for (int i = 0; i < 4; i++) {
            if (same(vArmor[i], armor[i])) continue;
            peSend(Proto.containerSetSlot(PE_ARMOR_WINDOW, i, 0, vArmor[i]));
            armor[i] = copyOf(vArmor[i]);
        }
    }

    /** Как PocketMine кладёт предмет в инвентарь (сначала докладывает в стопки, потом в пустые слоты). */
    private static int addItem(int[][] a, int[] it) {
        int left = it[1];
        for (int i = 0; i < a.length && left > 0; i++) {
            if (!sameItem(a[i], it)) continue;
            int add = Math.min(left, maxStack(it) - a[i][1]);
            if (add <= 0) continue;
            a[i] = withCount(it, a[i][1] + add); left -= add;
        }
        for (int i = 0; i < a.length && left > 0; i++) {
            if (!isEmpty(a[i])) continue;
            int add = Math.min(left, maxStack(it));
            a[i] = withCount(it, add); left -= add;
        }
        return left;
    }

    private void tapFromContainer(int win, int[] ps, int[] it) {
        int[][] d = new int[36][];
        for (int i = 0; i < 36; i++) d[i] = copyOf(inv[i]);
        int left = addItem(d, it);
        int moved = it[1] - left;
        if (moved > 0) {
            int[] rest = withCount(it, left);
            peSend(Proto.containerSetSlot(ps[0], ps[1], 0, rest));
            cont[ps[1]] = rest;
            for (int i = 0; i < 36; i++) if (!same(d[i], inv[i])) { peSend(Proto.containerSetSlot(0, i, 0, d[i])); inv[i] = d[i]; }
        }
        jCursor = null;
        resetView();
        sendWindow(win);
    }

    // ---- крафт ----
    private void updateCraft(int win) {
        int size = gridSize(win);
        int[][] g = new int[size * size][];
        for (int r = 0; r < size; r++) for (int c = 0; c < size; c++) g[r * size + c] = grid[r * 3 + c];
        Crafting.Match m = crafting.match(g, size);
        if (m != null && m.big && win == 0) m = null;
        int[] res = m == null ? null : m.recipe.result;
        craftMatch = m;
        if (!same(res, craftResult)) {
            craftResult = res;
            JBuf b = new JBuf().u8(win).varint(stateId++).i16(0);
            writeSlot(b, res);
            send(C_SET_SLOT, b);
        }
    }

    private void craftClick(int win, int mode, int[] cursorBefore) {
        updateCraft(win);
        if (craftMatch == null || (mode != 0 && mode != 1)) { sendWindow(win); return; }
        int times = mode == 1 ? 64 : 1;
        int done = 0;
        while (done < times && craftMatch != null) {
            int[] res = craftMatch.recipe.result;
            if (mode == 0) {
                if (!isEmpty(jCursor) && !(sameItem(jCursor, res) && jCursor[1] + res[1] <= maxStack(res))) break;
            } else {
                int[][] probe = new int[36][];
                for (int i = 0; i < 36; i++) probe[i] = copyOf(inv[i]);
                if (addItem(probe, res) > 0) break;
            }
            peSend(Proto.craftingEvent(0, craftMatch.recipe.type, craftMatch.recipe.id, craftMatch.input, res));
            // как сделает сервер: снимет ингредиенты с первых подходящих слотов и положит результат в инвентарь
            int[] used = new int[36];
            for (int[] in : craftMatch.input) {
                if (isEmpty(in)) continue;
                for (int i = 0; i < 36; i++) {
                    if (!isEmpty(inv[i]) && inv[i][0] == in[0] && inv[i][2] == in[2] && inv[i][1] - used[i] >= 1) { used[i]++; break; }
                }
            }
            for (int i = 0; i < 36; i++) if (used[i] > 0) inv[i] = withCount(inv[i], inv[i][1] - used[i]);
            addItem(inv, res);
            for (int k = 0; k < 9; k++) if (!isEmpty(grid[k])) grid[k] = withCount(grid[k], grid[k][1] - 1);
            if (mode == 0) jCursor = isEmpty(jCursor) ? copyOf(res) : withCount(jCursor, jCursor[1] + res[1]);
            done++;
            updateCraft(win);
        }
        int[][] nd = display(joinTruth(), joinView(), held());
        for (int i = 0; i < 36; i++) vInv[i] = nd[i];
        sendWindow(win);
    }

    private void openCraftingTable() {
        if (openWin >= 0) return;
        openWin = CRAFT_WIN; openType = 1; openSlots = 0; peCraftWin = false;
        cont = new int[0][]; vCont = new int[0][];
        returnGrid();
        if (joined) send(C_OPEN_WINDOW, new JBuf().varint(CRAFT_WIN).varint(11).str("{\"translate\":\"container.crafting\"}"));
        craftResult = null;
        sendWindow(CRAFT_WIN);
    }

    /** Предметы из сетки и с курсора «возвращаются» (на сервере они и не уходили из инвентаря). */
    private void returnGrid() {
        for (int k = 0; k < 9; k++) grid[k] = empty();
        jCursor = null;
        craftResult = null; craftMatch = null;
        resetView();
    }

    private void closeWindowLocal() {
        openWin = -1; openType = -1; openSlots = 0; peCraftWin = false;
        cont = new int[0][]; vCont = new int[0][];
        returnGrid();
    }

    private void closeWindowFromJava(int w) {
        boolean wasCraftPe = peCraftWin;
        if (w != 0 && w == openWin && (w != CRAFT_WIN || wasCraftPe)) peSend(Proto.containerClose(w));
        if (w == openWin || w == 0) {
            if (w == openWin) closeWindowLocal(); else returnGrid();
            sendInventory();
        }
    }

    // ---- выбросить ----
    /** Выбросить стопку из любого слота: в 0.14 выбрасывается только то, что в руке, — поэтому меняем местами. */
    private void dropFrom(int[] ps) {
        int[] it = truthAt(ps);
        if (isEmpty(it)) { sendWindow(activeWindow()); return; }
        int hs = heldPeSlot();
        int[] hand = copyOf(inv[hs]);
        boolean isHand = ps[0] == 0 && ps[1] == hs;
        if (!isHand) {
            peSend(Proto.containerSetSlot(0, hs, 0, it));
            peSend(Proto.containerSetSlot(ps[0], ps[1], 0, hand));
        }
        peSend(Proto.dropItem(it));
        // сервер очистит руку; вернём то, что было в руке
        if (!isHand) {
            peSend(Proto.containerSetSlot(ps[0], ps[1], 0, empty()));
            peSend(Proto.containerSetSlot(0, hs, 0, hand));
            if (ps[0] == 0) inv[ps[1]] = empty(); else if (ps[0] == PE_ARMOR_WINDOW) armor[ps[1]] = empty(); else if (ps[1] < cont.length) cont[ps[1]] = empty();
        } else inv[hs] = empty();
        resetView();
    }

    /** Выбросить предметы, которых нет ни в одном слоте у Java (курсор/сетка): кладём в слот, потом выбрасываем. */
    private void dropStack(int[] it) {
        // сначала синхронизируем раскладку без этой стопки, затем выбрасываем её из того слота, где она на сервере
        List<int[]> rest = held();
        int[][] t = joinTruth();
        int[][] d = park(t, joinView(), rest);
        int slot = -1;
        int hs = heldPeSlot();
        if (isEmpty(d[hs])) slot = hs;
        else for (int i = 0; i < 36; i++) if (isEmpty(d[i])) { slot = i; break; }
        if (slot < 0) { resetView(); return; }
        d[slot] = copyOf(it);
        for (int i = 0; i < d.length; i++) {
            if (same(d[i], t[i])) continue;
            if (i < 36) { peSend(Proto.containerSetSlot(0, i, 0, d[i])); inv[i] = copyOf(d[i]); }
            else { peSend(Proto.containerSetSlot(openWin, i - 36, 0, d[i])); cont[i - 36] = copyOf(d[i]); }
        }
        dropFrom(new int[]{0, slot});
    }

    private void creativeSlot(int slot, int[] it) {
        int[] ps = peOf(0, slot);
        if (ps == null) return;
        int[] pe;
        if (it == null || it[1] <= 0) pe = empty();
        else {
            pe = toPe(it, allKnown());
            if (pe[0] == 0) { systemChat(chat("§eЭтого предмета нет в 0.14"), true); sendInventory(); return; }
            pe[1] = Math.max(1, it[1]);
        }
        if (ps[0] == 0) inv[ps[1]] = copyOf(pe); else armor[ps[1]] = copyOf(pe);
        setView(ps, pe);
        peSend(Proto.containerSetSlot(ps[0], ps[1], ps[0] == 0 ? ps[1] : 0, pe));
        if (slot >= 36 && slot <= 44 && slot - 36 == held) equipHeld();
    }

    // ---- сундуки, печи и т.п. ----
    private static int[] javaMenu(int peType, int slots) {
        switch (peType) {
            case 0: return slots > 27 ? new int[]{5, 54} : new int[]{2, 27};
            case 1: return new int[]{11, 10};
            case 2: return new int[]{13, 3};
            case 3: return new int[]{12, 2};
            case 4: return new int[]{10, 5};
            case 5: return new int[]{7, 3};
            case 6: case 7: return new int[]{6, 9};
            case 8: return new int[]{15, 5};
            default: return null;
        }
    }

    private int javaContainerSize() { int[] m = javaMenu(openType, openSlots); return m == null ? 0 : m[1]; }

    private int toPeContainerSlot(int j) {
        if (openType == 4) {
            if (j <= 2) return j + 1;
            if (j == 3) return 0;
            return -1;
        }
        return j < openSlots ? j : -1;
    }

    private void openContainer(int w, int type, int slots) {
        int[] m = javaMenu(type, slots);
        if (m == null) { peSend(Proto.containerClose(w)); return; }
        returnGrid();
        openWin = w; openType = type; openSlots = slots; peCraftWin = type == 1;
        cont = new int[type == 1 ? 0 : Math.max(slots, m[1])][];
        for (int i = 0; i < cont.length; i++) cont[i] = empty();
        resetView();
        String[] titles = {"container.chest", "container.crafting", "container.furnace", "container.enchant", "container.brewing",
                "container.repair", "container.dispenser", "container.dropper", "container.hopper"};
        String key = type == 0 && slots > 27 ? "container.chestDouble" : titles[type];
        if (joined) {
            send(C_OPEN_WINDOW, new JBuf().varint(w).varint(m[0]).str("{\"translate\":\"" + key + "\"}"));
            sendWindow(w);
        }
    }
}
