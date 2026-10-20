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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Одна игровая сессия: Java-клиент 1.20.1 ↔ сервер MCPE 0.14.
 * Для Java-клиента мы — обычный сервер без шифрования, для сервера 0.14 — обычный игрок.
 */
public final class Session implements RakClient.Listener {

    public interface Listener {
        void status(String s);
        void log(String s);
        void ended(String reason);
        /** Надпись над хотбаром (popup) или выше (tip). */
        default void popup(String text, boolean tip) {}
        /** Скин игрока (RGBA 64×64 / 64×32). */
        default void skin(UUID javaUuid, byte[] rgba, boolean slim) {}
    }

    public static final class Config {
        public String nick = "Steve";
        public byte[] skin;              // RGBA 64×64 или 64×32
        public boolean slim;
        public String host = "127.0.0.1";
        public int port = 19132;
        public int chunkRadius = 6;
        /** Тело пакета тегов (из ванильных данных игры); null — не отправлять. */
        public java.util.function.Supplier<byte[]> tags;
    }

    // ---- Java пакеты (1.20.1, протокол 763) ----
    static final int C_SPAWN_ENTITY = 0x01, C_SPAWN_PLAYER = 0x03, C_ANIMATION = 0x04, C_ACK_DIG = 0x06, C_TILE_DATA = 0x08,
            C_BLOCK_CHANGE = 0x0a, C_CLOSE_WINDOW = 0x11, C_WINDOW_ITEMS = 0x12, C_PROGRESS = 0x13, C_SET_SLOT = 0x14,
            C_KICK = 0x1a, C_ENTITY_STATUS = 0x1c, C_EXPLOSION = 0x1d, C_UNLOAD = 0x1e, C_GAME_EVENT = 0x1f,
            C_HURT = 0x21, C_KEEP_ALIVE = 0x23, C_CHUNK = 0x24, C_WORLD_EVENT = 0x25, C_LOGIN = 0x28, C_OPEN_WINDOW = 0x30,
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

    static final int TEXT_DISPLAY_TYPE = 100;
    static final int PE_ARMOR_WINDOW = 0x78;
    static final String HIDE_TEAM = "b14hide";
    static final String WORLD_A = "minecraft:overworld", WORLD_B = "bridge14:world";

    private final Socket sock;
    private final Config cfg;
    private final Listener L;
    private OutputStream out;
    private volatile boolean alive = true;
    private RakClient rak;
    private final Random rnd = new Random();

    private int jState;        // 0 рукопожатие, 1 статус, 2 вход, 3 игра
    private boolean peSpawned, joined;
    private String javaName;
    private UUID selfUuid, peSelfUuid;

    // состояние мира (храним, чтобы отдать клиенту при входе и для логики)
    private long selfEid = -1;
    private int gamemode, advFlags;
    private double x, y, z; private float yaw, pitch;
    private int health = 20, food = 20; private float saturation = 5, xpBar; private int xpLevel;
    private int spawnX, spawnY = 64, spawnZ;
    private long time = 6000;
    private boolean raining, thunder;
    private final Map<Long, ChunkConv.Chunk> chunks = new HashMap<>();
    private final Map<Long, Ent> ents = new HashMap<>();
    private final Map<Integer, Long> javaToPe = new HashMap<>();
    private final Map<Integer, int[]> effects = new LinkedHashMap<>(); // свои эффекты: id → {amp, конец(мс), частицы}
    private final Map<UUID, String> tabList = new HashMap<>();          // кто в списке игроков 0.14
    private final Set<String> hiddenNames = new HashSet<>();
    private int nextJavaId = 2, teleportId = 1, lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private boolean worldB;    // текущий «мир» у Java-клиента (меняем при переходе между мирами сервера)

    // телепортация
    private int awaitTp = -1; private long awaitTpSince;
    private boolean tpPending; private long tpStart;
    private double tpX, tpY, tpZ; private float tpYaw, tpPitch;

    // инвентарь: всё в виде предметов 0.14 {id, count, meta}
    private final int[][] inv = new int[36][3];
    private final int[][] armor = new int[4][3];
    private final int[] hotbar = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private int held;
    private long lastKeepAlive;
    private int stateId = 1;
    // открытое окно (сундук и т.п.)
    private int openWin = -1, openType = -1, openSlots;
    private final Map<Integer, int[][]> containers = new HashMap<>();
    private int burnMax = 200;
    // то, что видит Java-клиент в текущем окне (после его кликов); null — совпадает с сервером
    private int[][] view; private int viewWin = -1;
    private boolean cursorFull;

    // табличка, которую мы только что поставили
    private int[] pendingSign; private long pendingSignAt;

    static final class Ent {
        long pe; int jid, jtype; boolean player; UUID uuid; String name = "";
        float x, y, z, yaw, pitch; int[] item;
        String nametag = ""; boolean showTag, invisible, hologram; int flags;
        int labelJid = -1; // текст над игроком (ранги и т.п.)
        float eye = 0.225f; // сервер 0.14 шлёт движение с высотой глаз
        int[][] gear;      // 0 рука, 1..4 броня 0.14 (шлем..ботинки)
    }

    public Session(Socket sock, Config cfg, Listener l) { this.sock = sock; this.cfg = cfg; this.L = l; }

    public void start() {
        Thread t = new Thread(this::readLoop, "j-read");
        t.setDaemon(true);
        t.start();
        Thread k = new Thread(this::tickLoop, "j-tick");
        k.setDaemon(true);
        k.start();
    }

    public void close(String reason) {
        if (!alive) return;
        alive = false;
        try { if (jState == 3) send(C_KICK, new JBuf().str(chat(reason))); } catch (Exception ignored) {}
        try { sock.close(); } catch (Exception ignored) {}
        RakClient r = rak; rak = null;
        if (r != null) r.close("Выход");
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
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
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
                String st = "{\"version\":{\"name\":\"0.14 мост\",\"protocol\":763},\"players\":{\"max\":20,\"online\":0},\"description\":{\"text\":\"Сервер 0.14\"}}";
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
            case S_CHAT: {
                String m = b.str();
                peSend(Proto.text(peNick(), m));
                break;
            }
            case S_CHAT_COMMAND: {
                String m = b.str();
                peSend(Proto.text(peNick(), "/" + m));
                break;
            }
            case S_CLIENT_COMMAND: {
                int a = b.varint();
                if (a == 0) peSend(Proto.action(selfEid, Proto.ACTION_RESPAWN, 0, 0, 0, -1));
                break;
            }
            case S_SWING: peSend(Proto.animate(selfEid, 1)); break;
            case S_DIG: {
                int status = b.varint(); int[] p = b.position(); int face = b.u8(); int seq = b.varint();
                switch (status) {
                    case 0: // начал ломать
                        peSend(Proto.action(selfEid, Proto.ACTION_START_BREAK, p[0], p[1], p[2], face));
                        if (gamemode == 1) breakBlock(p);
                        break;
                    case 1: peSend(Proto.action(selfEid, Proto.ACTION_ABORT_BREAK, p[0], p[1], p[2], face)); break;
                    case 2: // доломал
                        peSend(Proto.action(selfEid, Proto.ACTION_STOP_BREAK, p[0], p[1], p[2], face));
                        breakBlock(p);
                        break;
                    case 3: case 4: { // выбросить предмет из руки (в 0.14 выбрасывается вся стопка)
                        int[] it = heldItem();
                        if (it[0] != 0) peSend(Proto.dropItem(it[0], it[1], it[2]));
                        break;
                    }
                    case 5: peSend(Proto.action(selfEid, 5, 0, 0, 0, 0)); break; // отпустить (лук)
                    default: break;
                }
                send(C_ACK_DIG, new JBuf().varint(seq));
                break;
            }
            case S_PLACE: {
                b.varint(); int[] p = b.position(); int face = b.varint();
                float cx = b.f32(), cy = b.f32(), cz = b.f32(); b.bool(); int seq = b.varint();
                int[] it = heldItem();
                peSend(Proto.useItem(p[0], p[1], p[2], face, cx, cy, cz, (float) x, (float) (y + 1.62), (float) z, it[0], it[1], it[2]));
                if (it[0] == 323) { // табличка: откроем редактор, когда сервер её поставит
                    int[] d = FACE[Math.min(5, Math.max(0, face))];
                    pendingSign = new int[]{p[0] + d[0], p[1] + d[1], p[2] + d[2]};
                    pendingSignAt = System.currentTimeMillis();
                }
                send(C_ACK_DIG, new JBuf().varint(seq));
                break;
            }
            case S_USE_ITEM: {
                b.varint(); int seq = b.varint();
                int[] it = heldItem();
                peSend(Proto.useItem(0, 0, 0, 255, 0, 0, 0, (float) x, (float) (y + 1.62), (float) z, it[0], it[1], it[2]));
                send(C_ACK_DIG, new JBuf().varint(seq));
                break;
            }
            case S_USE_ENTITY: {
                int target = b.varint(); int mouse = b.varint();
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
            case S_CREATIVE_SLOT: {
                int slot = b.i16(); int[] it = b.slot();
                creativeSlot(slot, it);
                break;
            }
            case S_WINDOW_CLICK: windowClick(b); break;
            case S_CLOSE_WINDOW: {
                int w = b.u8();
                if (cursorFull) { cursorFull = false; view = null; resync(w); }
                if (w != 0) {
                    peSend(Proto.containerClose(w));
                    if (w == openWin) { openWin = -1; containers.remove(w); }
                }
                view = null; viewWin = -1;
                break;
            }
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
                    case 0: peSend(Proto.action(selfEid, Proto.ACTION_START_SNEAK, 0, 0, 0, 0)); break;
                    case 1: peSend(Proto.action(selfEid, Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); break;
                    case 3: peSend(Proto.action(selfEid, Proto.ACTION_START_SPRINT, 0, 0, 0, 0)); break;
                    case 4: peSend(Proto.action(selfEid, Proto.ACTION_STOP_SPRINT, 0, 0, 0, 0)); break;
                    default: break;
                }
                break;
            }
            default: break; // настройки клиента, keep-alive и прочее — не нужны серверу 0.14
        }
    }

    static final int[][] FACE = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    private void breakBlock(int[] p) {
        peSend(Proto.removeBlock(selfEid, p[0], p[1], p[2]));
        setBlockLocal(p[0], p[1], p[2], 0, 0, false);
    }

    private int[] heldItem() { int s = hotbar[held]; return s >= 0 && s < 36 ? inv[s] : new int[3]; }

    private int heldPeSlot() { int s = hotbar[held]; return s >= 0 && s < 36 ? s : held; }

    private void equipHeld() {
        int s = heldPeSlot();
        int[] it = inv[s];
        peSend(Proto.mobEquipment(selfEid, it[0], it[1], it[2], s + 9, held));
    }

    // ---------------- движение и телепорты ----------------
    private long lastMoveSent; private double sentX, sentY, sentZ; private float sentYaw, sentPitch;

    private void javaMove(double nx, double ny, double nz, float nyaw, float npitch, boolean onGround) {
        // пока клиент не подтвердил телепорт — его старые координаты серверу не нужны (иначе «швыряет туда-сюда»)
        if (awaitTp >= 0 || tpPending) return;
        x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch;
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx != lastCx || cz != lastCz) { lastCx = cx; lastCz = cz; send(C_VIEW_POS, new JBuf().varint(cx).varint(cz)); pruneChunks(); }
        if (selfEid < 0) return;
        long now = System.currentTimeMillis();
        boolean changed = Math.abs(x - sentX) > 1e-4 || Math.abs(y - sentY) > 1e-4 || Math.abs(z - sentZ) > 1e-4
                || Math.abs(yaw - sentYaw) > 0.01f || Math.abs(pitch - sentPitch) > 0.01f;
        if (!changed && now - lastMoveSent < 1000) return;
        lastMoveSent = now; sentX = x; sentY = y; sentZ = z; sentYaw = yaw; sentPitch = pitch;
        peSend(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
    }

    /** Сервер сам переместил нас. */
    private void serverMove(double nx, double ny, double nz, float nyaw, float npitch) {
        double dist = Math.hypot(nx - x, nz - z);
        int far = Math.max(48, cfg.chunkRadius * 16 + 8);
        if (!joined) { x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch; return; }
        if (tpPending || dist > far) {
            // дальний телепорт или переход в другой мир: ждём чанки места назначения, потом «переезжаем» начисто
            if (!tpPending) { tpPending = true; tpStart = System.currentTimeMillis(); }
            tpX = nx; tpY = ny; tpZ = nz; tpYaw = nyaw; tpPitch = npitch;
            x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch;
            if (freshChunkAt(nx, nz)) finishTeleport();
            return;
        }
        // близкий телепорт: просто переместить (и не слушать старые координаты до подтверждения)
        boolean same = Math.abs(nx - x) < 0.05 && Math.abs(ny - y) < 0.05 && Math.abs(nz - z) < 0.05;
        x = nx; y = ny; z = nz; yaw = nyaw; pitch = npitch;
        if (same && awaitTp >= 0) return; // тот же самый телепорт ещё раз — не дёргаем клиента
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
        // старые чанки (прошлый мир / прошлое место) выкидываем
        chunks.values().removeIf(c -> c.received < cutoff);
        worldB = !worldB;
        String w = worldB ? WORLD_B : WORLD_A;
        send(C_RESPAWN, new JBuf().str("minecraft:overworld").str(w).i64(0).u8(gamemode).u8(255)
                .bool(false).bool(false).u8(0).bool(false).varint(0));
        x = tpX; y = tpY; z = tpZ; yaw = tpYaw; pitch = tpPitch;
        afterWorldReset();
        L.log("Переход: " + (int) x + " " + (int) y + " " + (int) z);
    }

    /** После respawn-пакета клиент всё забыл — отдаём заново. */
    private void afterWorldReset() {
        sendAbilities();
        send(C_HELD_SLOT, new JBuf().u8(held));
        sendSpawn();
        sendTime();
        sendWeather();
        lastCx = (int) Math.floor(x) >> 4; lastCz = (int) Math.floor(z) >> 4;
        send(C_VIEW_POS, new JBuf().varint(lastCx).varint(lastCz));
        for (Map.Entry<Long, ChunkConv.Chunk> c : chunks.entrySet()) sendChunk(c.getKey(), c.getValue());
        sendPosition();
        sendHealth();
        sendXp();
        sendInventory();
        resendEffects();
        for (Ent e : ents.values()) spawn(e);
    }

    private void pruneChunks() {
        int r = Math.max(2, cfg.chunkRadius) + 3;
        Iterator<Map.Entry<Long, ChunkConv.Chunk>> it = chunks.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            int cx = (int) (k >> 32), cz = (int) k;
            if (Math.abs(cx - lastCx) > r || Math.abs(cz - lastCz) > r) {
                it.remove();
                if (joined) send(C_UNLOAD, new JBuf().i32(cx).i32(cz));
            }
        }
    }

    private String peNick() { return cfg.nick == null || cfg.nick.isEmpty() ? javaName : cfg.nick; }

    // ======================= Сервер 0.14 =======================
    private void startPe() {
        try {
            rak = new RakClient(cfg.host, cfg.port, this);
            rak.start();
        } catch (Exception e) {
            loginFail("Не удалось подключиться: " + e.getMessage());
        }
    }

    private void loginFail(String reason) {
        try { send(0x00, new JBuf().str(chat(reason))); } catch (Exception ignored) {}
        close(reason);
    }

    private void peSend(byte[] pk) { RakClient r = rak; if (r != null && peSpawned) r.send(pk); }

    @Override public void onConnected() {
        L.status("Вход на сервер 0.14…");
        RakClient r = rak;
        int proto = r != null && r.gameProtocol != 0 ? r.gameProtocol : Proto.PROTOCOL;
        peSelfUuid = UUID.nameUUIDFromBytes(("Bridge:" + peNick()).getBytes());
        byte[] skin = cfg.skin != null ? cfg.skin : DefaultSkin.make();
        if (r != null) r.send(Proto.login(proto, peNick(), rnd.nextLong(), peSelfUuid, cfg.host + ":" + cfg.port, skin,
                cfg.slim ? "Standard_CustomSlim" : "Standard_Custom"));
    }

    @Override public void onClosed(String reason) {
        if (!alive) return;
        if (jState == 3) close("Сервер 0.14: " + reason);
        else loginFail("Сервер 0.14: " + reason);
    }

    @Override public void log(String msg) { L.log(msg); }

    @Override public void onPayload(byte[] d) {
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
        synchronized (this) { pe(id, b); }
    }

    static long ck(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

    private int jid(long pe) {
        if (pe == selfEid) return 1;
        Ent e = ents.get(pe);
        return e != null ? e.jid : -1;
    }

    private void pe(int id, Buf b) {
        switch (id) {
            case Proto.PLAY_STATUS: {
                int st = b.i32();
                if (st == 1 || st == 2) { loginFail(st == 1 ? "Сервер новее этой версии (0.14.0/0.14.1)" : "Сервер старее этой версии"); return; }
                if (st == 3 && !peSpawned) {
                    peSpawned = true;
                    join();
                }
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
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32(); int order = b.u8(); int n = b.i32();
                ChunkConv.Chunk c = ChunkConv.fromPe(b.take(Math.min(n, b.left())), order);
                long k = ck(cx, cz);
                chunks.put(k, c);
                fixShapes(cx, cz, c);
                if (joined && !tpPending) {
                    sendChunk(k, c);
                    refreshEdges(cx, cz);
                }
                if (tpPending && (int) Math.floor(tpX) >> 4 == cx && (int) Math.floor(tpZ) >> 4 == cz) finishTeleport();
                break;
            }
            case Proto.UPDATE_BLOCK: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 11; i++) {
                    int bx = b.i32(), bz = b.i32(), by = b.u8(), bid = b.u8(), fm = b.u8();
                    setBlockLocal(bx, by, bz, bid, fm & 15, true);
                    if (pendingSign != null && pendingSign[0] == bx && pendingSign[1] == by && pendingSign[2] == bz && (bid == 63 || bid == 68)) {
                        if (joined) send(C_OPEN_SIGN, new JBuf().position(bx, by, bz).bool(true));
                        pendingSign = null;
                    }
                }
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
                if (e == selfEid) {
                    serverMove(px, py - 1.62, pz, pyaw, pp);
                } else {
                    Ent en = ents.get(e);
                    if (en == null) break;
                    en.x = px; en.y = py - 1.62f; en.z = pz; en.yaw = pyaw; en.pitch = pp;
                    if (joined) teleport(en);
                }
                break;
            }
            case Proto.ADD_PLAYER: {
                UUID u = new UUID(b.i64(), b.i64());
                String name = b.str();
                long e = b.i64();
                if (e == selfEid) break;
                Ent en = new Ent();
                en.pe = e; en.jid = nextJavaId++; en.player = true; en.uuid = u; en.name = name; en.jtype = Tables.PLAYER_TYPE;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                b.f32(); b.f32(); b.f32();
                en.yaw = b.f32(); b.f32(); en.pitch = b.f32();
                try { int[] hand = Proto.readSlot(b); en.gear = new int[5][]; en.gear[0] = hand; applyMeta(en, readMeta(b), false); } catch (Exception ignored) {}
                en.eye = 1.62f;
                addEnt(en);
                break;
            }
            case Proto.ADD_ENTITY: {
                long e = b.i64();
                int type = b.i32() & 0xff;
                Ent en = new Ent();
                en.pe = e; en.uuid = UUID.randomUUID();
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                b.f32(); b.f32(); b.f32();
                en.yaw = b.f32(); en.pitch = b.f32();
                try { applyMeta(en, readMeta(b), false); } catch (Exception ignored) {}
                if (en.hologram) {
                    en.jtype = TEXT_DISPLAY_TYPE;
                    if (type == 64) en.y += 0.75f;
                } else {
                    int jt = Tables.entity(type);
                    if (jt < 0) break;
                    en.jtype = jt;
                }
                en.eye = eyeHeight(type);
                en.jid = nextJavaId++;
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
            case Proto.REMOVE_PLAYER: case Proto.REMOVE_ENTITY: {
                long e = b.i64();
                removeEnt(e);
                break;
            }
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
                    en.x = px; en.y = py - en.eye + (en.hologram ? 0.75f : 0); en.z = pz; en.yaw = pyaw; en.pitch = pp;
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
                    else if (ev == 3 && j > 1) send(C_ENTITY_STATUS, new JBuf().i32(j).u8(3));
                }
                break;
            }
            case Proto.MOB_EFFECT: {
                long e = b.i64(); int ev = b.u8(), eff = b.u8(), amp = b.u8(); boolean particles = b.u8() != 0; int dur = b.i32();
                int j = jid(e);
                if (eff < 1 || eff > 33) break;
                if (e == selfEid) {
                    if (ev == 3) effects.remove(eff);
                    else effects.put(eff, new int[]{amp, (int) Math.min(Integer.MAX_VALUE, dur), particles ? 1 : 0, (int) (System.currentTimeMillis() / 1000)});
                }
                if (j > 0 && joined) {
                    if (ev == 3) send(C_REMOVE_EFFECT, new JBuf().varint(j).varint(eff));
                    else sendEffect(j, eff, amp, dur, particles);
                }
                break;
            }
            case Proto.UPDATE_ATTRIBUTES: {
                long e = b.i64(); int n = b.u16();
                boolean hp = false, xp = false;
                for (int i = 0; i < n && b.left() > 12; i++) {
                    b.f32(); b.f32(); float val = b.f32(); String name = b.str();
                    if (e != selfEid) continue;
                    switch (name) {
                        case "minecraft:health": health = (int) Math.ceil(val); hp = true; break;
                        case "minecraft:player.hunger": food = (int) val; hp = true; break;
                        case "minecraft:player.saturation": saturation = val; hp = true; break;
                        case "minecraft:player.experience": xpBar = val; xp = true; break;
                        case "minecraft:player.level": xpLevel = (int) val; xp = true; break;
                        default: break;
                    }
                }
                if (joined && hp) sendHealth();
                if (joined && xp) sendXp();
                break;
            }
            case Proto.SET_HEALTH: {
                int old = health;
                health = b.i32();
                if (joined) {
                    sendHealth();
                    if (health <= 0 && old > 0) send(C_DEATH, new JBuf().varint(1).str("{\"translate\":\"deathScreen.title\"}"));
                }
                break;
            }
            case Proto.RESPAWN: {
                x = b.f32(); y = b.f32() - 1.62; z = b.f32();
                if (y < 0) y += 1.62;
                health = 20;
                if (joined) {
                    tpPending = false;
                    send(C_RESPAWN, new JBuf().str("minecraft:overworld").str(worldB ? WORLD_B : WORLD_A).i64(0).u8(gamemode).u8(255)
                            .bool(false).bool(false).u8(0).bool(false).varint(0));
                    effects.clear();
                    afterWorldReset();
                }
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
                        boolean self = u.equals(peSelfUuid) || name.replaceAll("§.", "").equalsIgnoreCase(peNick());
                        if (self) { if (cfg.skin == null && (skin.length == 8192 || skin.length == 16384)) L.skin(selfUuid, skin, slim); continue; }
                        if (skin.length == 8192 || skin.length == 16384) L.skin(u, skin, slim);
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
                if (w == openWin) { openWin = -1; containers.remove(w); view = null; cursorFull = false; if (joined) send(C_CLOSE_WINDOW, new JBuf().u8(w)); }
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
                    for (int i = 0; i < 36; i++) { int[] s = i < slots.size() ? slots.get(i) : new int[3]; inv[i] = s.clone(); }
                    if (b.left() >= 2) {
                        int hn = b.u16();
                        for (int i = 0; i < hn && b.left() >= 4; i++) { int v = b.i32(); if (i < 9) hotbar[i] = v >= 9 ? v - 9 : (v >= 0 ? v : i); }
                    }
                    if (joined) { view = null; cursorFull = false; sendInventory(); if (openWin >= 0) sendContainer(); }
                } else if (win == PE_ARMOR_WINDOW) {
                    for (int i = 0; i < 4; i++) armor[i] = i < slots.size() ? slots.get(i).clone() : new int[3];
                    if (joined) sendInventory();
                } else if (win == openWin) {
                    int[][] c = new int[Math.max(openSlots, slots.size())][];
                    for (int i = 0; i < c.length; i++) c[i] = i < slots.size() ? slots.get(i).clone() : new int[3];
                    containers.put(win, c);
                    if (joined) { view = null; cursorFull = false; sendContainer(); }
                }
                break;
            }
            case Proto.CONTAINER_SET_SLOT: {
                int win = b.u8(); int slot = b.s16(); b.s16();
                int[] it = Proto.readSlot(b);
                if (win == 0 && slot >= 0 && slot < 36) {
                    inv[slot] = it.clone();
                    if (joined) peSlotChanged(0, slot);
                } else if (win == PE_ARMOR_WINDOW && slot >= 0 && slot < 4) {
                    armor[slot] = it.clone();
                    if (joined) peSlotChanged(PE_ARMOR_WINDOW, slot);
                } else if (win == openWin && containers.containsKey(win)) {
                    int[][] c = containers.get(win);
                    if (slot >= 0 && slot < c.length) { c[slot] = it.clone(); if (joined) peSlotChanged(win, slot); }
                }
                break;
            }
            case Proto.MOB_EQUIPMENT: {
                long e = b.i64(); int[] it = Proto.readSlot(b); int slot = b.u8(), sel = b.u8();
                if (e == selfEid) {
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
                if (e == selfEid) {
                    for (int i = 0; i < 4; i++) armor[i] = a[i];
                    if (joined) sendInventory();
                } else {
                    Ent en = ents.get(e);
                    if (en == null) break;
                    if (en.gear == null) en.gear = new int[5][];
                    for (int i = 0; i < 4; i++) en.gear[i + 1] = a[i];
                    if (joined) sendGear(en);
                }
                break;
            }
            default: break;
        }
    }

    /** Высота глаз (рост/2 + 0.1, как в PocketMine) по типу 0.14. */
    private static float eyeHeight(int t) {
        float h;
        switch (t) {
            case 10: h = 0.7f; break; case 11: case 16: h = 1.4f; break; case 12: h = 0.9f; break; case 13: h = 1.3f; break;
            case 14: h = 0.85f; break; case 15: case 32: case 36: case 43: case 44: h = 1.8f; break; case 17: h = 0.8f; break;
            case 18: h = 0.5f; break; case 19: h = 0.9f; break; case 20: h = 2.7f; break; case 21: h = 1.9f; break;
            case 22: h = 0.7f; break; case 33: h = 1.7f; break; case 34: h = 1.99f; break; case 35: h = 0.9f; break;
            case 37: case 42: h = 0.51f; break; case 38: h = 2.9f; break; case 39: h = 0.3f; break; case 40: h = 0.5f; break;
            case 41: h = 4f; break; case 45: h = 1.95f; break; case 64: h = 0.25f; break; case 65: case 66: h = 0.98f; break;
            case 84: h = 0.7f; break; case 90: h = 0.6f; break;
            default: h = 0.25f;
        }
        return h / 2 + 0.1f;
    }

    private static int v(float m) { return (int) Math.max(-32768, Math.min(32767, m * 8000)); }

    // ======================= блоки =======================
    private int stateAt(int bx, int by, int bz) {
        if (by < 0 || by >= 128) return 0;
        ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
        return c == null ? 0 : c.state[ChunkConv.idx(bx & 15, by, bz & 15)];
    }

    private final Shapes.Lookup lookup = this::stateAt;

    private void setBlockLocal(int bx, int by, int bz, int bid, int meta, boolean notify) {
        if (by < 0 || by >= 128) return;
        ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
        int st = Tables.block(bid, meta);
        if (c != null) {
            int ix = ChunkConv.idx(bx & 15, by, bz & 15);
            c.state[ix] = st; c.pe[ix] = (byte) bid;
            if (bid != 63 && bid != 68) c.tiles.remove(ix);
            st = Shapes.fix(st, bx, by, bz, lookup);
            c.state[ix] = st;
        }
        if (notify && joined) {
            send(C_BLOCK_CHANGE, new JBuf().position(bx, by, bz).varint(st));
            int be = ChunkConv.beType(bid);
            if (be == ChunkConv.BE_SIGN && c != null)
                send(C_TILE_DATA, new JBuf().position(bx, by, bz).varint(be).raw(ChunkConv.beNbt(c, ChunkConv.idx(bx & 15, by, bz & 15))));
        }
        // соседние заборы/стены/панели перестраиваются
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

    /** Пришёл новый чанк — у соседей могли поменяться соединения на границе. */
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

    // ======================= вход в мир для Java =======================
    private void join() {
        if (joined || out == null) return;
        String nick = peNick();
        send(0x02, new JBuf().uuid(selfUuid).str(nick).varint(0)); // LoginSuccess → состояние «игра»
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
        // команда, у которой скрыты стандартные ники (у кого ник на сервере «украшен» — рисуем свой текст)
        send(C_TEAMS, new JBuf().str(HIDE_TEAM).u8(0).str(chat("")).u8(0).str("never").str("always").varint(21)
                .str(chat("")).str(chat("")).varint(0));
        joined = true;
        if (cfg.skin != null) L.skin(selfUuid, cfg.skin, cfg.slim);
        send(C_PLAYER_INFO, new JBuf().u8(0x1D).varint(1).uuid(selfUuid).str(trim16(nick)).varint(0).varint(gamemode).u8(1).varint(0));
        for (Map.Entry<UUID, String> e : tabList.entrySet()) sendInfo(e.getKey(), e.getValue(), true);
        afterWorldReset();
        L.status("В игре: " + cfg.host + ":" + cfg.port);
        L.log("Вошли на сервер 0.14 как " + nick);
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

    private void sendTime() {
        long t = time * 24000L / 19200L; // в 0.14 сутки 19200 тиков
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
            sendEffect(1, e.getKey(), v[0], (int) left, v[2] != 0);
        }
    }

    // ======================= сущности =======================
    private void addEnt(Ent en) {
        removeEnt(en.pe);
        ents.put(en.pe, en); javaToPe.put(en.jid, en.pe);
        if (joined) spawn(en);
    }

    /** Метаданные 0.14: ключ → значение. */
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
            // «плавающий текст»: невидимая сущность с подписью
            en.hologram = !en.player && en.invisible && !en.nametag.isEmpty();
            return;
        }
        if (changed && joined) sendMeta(en);
    }

    private static String clean(String s) { return s.replaceAll("§.", "").trim(); }

    private boolean fancyName(Ent e) {
        if (!e.player || e.nametag.isEmpty()) return false;
        return e.nametag.contains("\n") || !clean(e.nametag).equals(trim16(e.name)) || e.nametag.contains("§");
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
        if (!e.player) {
            boolean named = !e.nametag.isEmpty();
            m.u8(2).varint(6).bool(named); if (named) m.str(chat(e.nametag));
            m.u8(3).varint(8).bool(named && (e.showTag || e.jtype == Tables.PLAYER_TYPE));
        }
        m.u8(0xff);
        send(C_METADATA, m);
        if (e.player) updateLabel(e);
    }

    /** Текст над игроком, если ник на сервере цветной / многострочный (ранги, кланы). */
    private void updateLabel(Ent e) {
        String profile = trim16(e.name);
        if (!fancyName(e) || e.invisible) {
            if (e.labelJid > 0) {
                send(C_DESTROY, new JBuf().varint(1).varint(e.labelJid));
                javaToPe.remove(e.labelJid);
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
        m.u8(10).varint(26).f32(0).f32(0.75f).f32(0);   // смещение над головой
        m.u8(14).varint(0).u8(3);                       // всегда лицом к камере
        m.u8(22).varint(5).str(chat(e.nametag));
        m.u8(24).varint(1).varint(0x40000000);          // полупрозрачный фон, как у ника
        m.u8(0xff);
        send(C_METADATA, m);
    }

    private void spawn(Ent e) {
        if (e.player) {
            if (!tabList.containsKey(e.uuid)) sendInfo(e.uuid, e.name, false);
            send(C_SPAWN_PLAYER, new JBuf().varint(e.jid).uuid(e.uuid).f64(e.x).f64(e.y).f64(e.z).angle(e.yaw).angle(e.pitch));
            e.labelJid = -1;
            sendMeta(e);
            if (e.gear != null) sendGear(e);
        } else if (e.hologram) {
            send(C_SPAWN_ENTITY, new JBuf().varint(e.jid).uuid(e.uuid).varint(TEXT_DISPLAY_TYPE).f64(e.x).f64(e.y).f64(e.z)
                    .angle(0).angle(0).angle(0).varint(0).i16(0).i16(0).i16(0));
            JBuf m = new JBuf().varint(e.jid);
            m.u8(14).varint(0).u8(3);                   // всегда лицом к камере
            m.u8(22).varint(5).str(chat(e.nametag));
            m.u8(23).varint(1).varint(400);             // ширина строки
            m.u8(24).varint(1).varint(0x40000000);
            m.u8(0xff);
            send(C_METADATA, m);
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
        // Java: 0 рука, 2 ботинки, 3 штаны, 4 нагрудник, 5 шлем; 0.14: шлем, нагрудник, штаны, ботинки
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
        send(C_TELEPORT, new JBuf().varint(e.jid).f64(e.x).f64(e.y).f64(e.z).angle(e.yaw).angle(e.pitch).bool(true));
        if (!e.hologram) send(C_HEAD_ROT, new JBuf().varint(e.jid).angle(e.yaw));
    }

    private void removeEnt(long pe) {
        Ent e = ents.remove(pe);
        if (e == null) return;
        javaToPe.remove(e.jid);
        if (!joined) return;
        if (e.labelJid > 0) send(C_DESTROY, new JBuf().varint(1).varint(e.labelJid));
        send(C_DESTROY, new JBuf().varint(1).varint(e.jid));
        if (e.player && !tabList.containsKey(e.uuid)) send(C_PLAYER_REMOVE, new JBuf().varint(1).uuid(e.uuid));
    }

    // ======================= инвентарь и окна =======================
    private static boolean damageable(int id) {
        return (id >= 256 && id <= 259) || id == 261 || (id >= 267 && id <= 279) || (id >= 283 && id <= 286)
                || (id >= 290 && id <= 294) || (id >= 298 && id <= 317) || id == 346 || id == 359;
    }

    private void writeSlot(JBuf w, int[] it) {
        if (it == null || it[0] == 0 || it[1] <= 0) { w.bool(false); return; }
        int j = Tables.item(it[0], it[2]);
        if (j < 0) { w.bool(false); return; }
        w.bool(true).varint(j).u8(Math.min(127, it[1]));
        if (damageable(it[0]) && it[2] > 0) {
            w.u8(10).i16(0).u8(3).i16(6).raw("Damage".getBytes()).i32(it[2]).u8(0);
        } else w.u8(0);
    }

    private static boolean same(int[] a, int[] b) {
        boolean ea = a == null || a[0] == 0 || a[1] <= 0, eb = b == null || b[0] == 0 || b[1] <= 0;
        if (ea || eb) return ea == eb;
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    /** Слоты окна Java ↔ (окно 0.14, слот). */
    private int[] peOf(int win, int jslot) {
        if (win == 0) {
            if (jslot >= 5 && jslot <= 8) return new int[]{PE_ARMOR_WINDOW, jslot - 5};
            if (jslot >= 9 && jslot <= 35) return new int[]{0, jslot};
            if (jslot >= 36 && jslot <= 44) { int s = hotbar[jslot - 36]; return new int[]{0, s >= 0 && s < 36 ? s : jslot - 36}; }
            return null;
        }
        if (win != openWin) return null;
        int n = javaContainerSize();
        if (jslot >= 0 && jslot < n) { int s = toPeContainerSlot(jslot); return s < 0 ? null : new int[]{win, s}; }
        int r = jslot - n;
        if (r >= 0 && r < 27) return new int[]{0, r + 9};
        if (r >= 27 && r < 36) { int s = hotbar[r - 27]; return new int[]{0, s >= 0 && s < 36 ? s : r - 27}; }
        return null;
    }

    private int[] peItem(int[] ps) {
        if (ps == null) return null;
        if (ps[0] == 0) return inv[ps[1]];
        if (ps[0] == PE_ARMOR_WINDOW) return armor[ps[1]];
        int[][] c = containers.get(ps[0]);
        return c != null && ps[1] < c.length ? c[ps[1]] : null;
    }

    private int windowSize(int win) { return win == 0 ? 46 : javaContainerSize() + 36; }

    /** Текущее «серверное» содержимое окна в номерах слотов Java. */
    private int[][] truth(int win) {
        int n = windowSize(win);
        int[][] t = new int[n][];
        for (int j = 0; j < n; j++) { int[] it = peItem(peOf(win, j)); t[j] = it == null ? null : it.clone(); }
        return t;
    }

    /** Java-предмет → предмет 0.14 (берём точный id/meta у того, что уже есть в окне, — так сохраняется прочность). */
    private int[] toPe(int[] jit, int[][] known) {
        if (jit == null || jit[1] <= 0) return new int[3];
        if (known != null) for (int[] k : known)
            if (k != null && k[0] != 0 && Tables.item(k[0], k[2]) == jit[0]) return new int[]{k[0], jit[1], k[2]};
        for (int[] k : inv) if (k[0] != 0 && Tables.item(k[0], k[2]) == jit[0]) return new int[]{k[0], jit[1], k[2]};
        int[] p = Tables.itemBack(jit[0]);
        return p == null ? new int[3] : new int[]{p[0], jit[1], p[1]};
    }

    private void windowClick(JBuf b) {
        int win = b.u8(); b.varint(); int slot = b.i16(); int button = b.u8(); int mode = b.varint();
        int n = b.varint();
        int[] slots = new int[n]; int[][] items = new int[n][];
        for (int i = 0; i < n; i++) { slots[i] = b.i16(); items[i] = b.slot(); }
        int[] cursor = b.slot();
        if (win != 0 && win != openWin) return;
        int[][] t = truth(win);
        if (view == null || viewWin != win) { view = t; viewWin = win; view = copy(t); }
        int[][] before = copy(view);
        for (int i = 0; i < n; i++) if (slots[i] >= 0 && slots[i] < view.length) view[slots[i]] = toPe(items[i], t);

        // выбросить: Q над слотом (mode 4) или за край окна с предметом в руке (slot -999)
        if (mode == 4 && slot >= 0) {
            int[] ps = peOf(win, slot);
            if (ps != null) dropFrom(ps);
            view = null; cursorFull = false;
            return;
        }
        if (slot == -999 && (mode == 0 || mode == 5)) {
            int[] src = null;
            for (int j = 0; j < before.length && j < t.length; j++)
                if (t[j] != null && t[j][0] != 0 && (before[j] == null || before[j][1] < t[j][1])) { src = peOf(win, j); break; }
            if (src != null) dropFrom(src);
            view = null; cursorFull = false;
            return;
        }
        cursorFull = cursor != null && cursor[1] > 0;
        if (cursorFull) return; // предмет «на курсоре» — ждём, куда положат
        // всё легло по местам — сообщаем серверу итоговое содержимое изменившихся слотов (одной транзакцией)
        for (int j = 0; j < view.length && j < t.length; j++) {
            if (same(view[j], t[j])) continue;
            int[] ps = peOf(win, j);
            if (ps == null) continue;
            int[] it = view[j] == null ? new int[3] : view[j];
            peSend(Proto.containerSetSlot(ps[0], ps[1], it[0], it[1], it[2]));
        }
        view = null;
    }

    private static int[][] copy(int[][] a) {
        int[][] r = new int[a.length][];
        for (int i = 0; i < a.length; i++) r[i] = a[i] == null ? null : a[i].clone();
        return r;
    }

    /** Выбросить стопку из любого слота: в 0.14 можно выбросить только то, что в руке, — поэтому сперва меняем местами. */
    private void dropFrom(int[] ps) {
        int[] it = peItem(ps);
        if (it == null || it[0] == 0) { resync(viewWin); return; }
        int hs = heldPeSlot();
        int[] hand = inv[hs].clone();
        if (!(ps[0] == 0 && ps[1] == hs)) {
            peSend(Proto.containerSetSlot(0, hs, it[0], it[1], it[2]));
            peSend(Proto.containerSetSlot(ps[0], ps[1], hand[0], hand[1], hand[2]));
        }
        peSend(Proto.dropItem(it[0], it[1], it[2]));
    }

    private void resync(int win) {
        if (win == 0 || win < 0) sendInventory(); else sendContainer();
    }

    private void creativeSlot(int slot, int[] it) {
        int[] ps = peOf(0, slot);
        if (ps == null) return;
        int[] pe;
        if (it == null || it[1] <= 0) pe = new int[3];
        else {
            pe = toPe(it, null);
            if (pe[0] == 0) { systemChat(chat("§eЭтого предмета нет в 0.14"), true); sendInventory(); return; }
            pe[1] = Math.max(1, it[1]);
        }
        if (ps[0] == 0) inv[ps[1]] = pe.clone(); else armor[ps[1]] = pe.clone();
        peSend(Proto.containerSetSlot(ps[0], ps[1], pe[0], pe[1], pe[2]));
        if (slot >= 36 && slot <= 44 && slot - 36 == held) equipHeld();
    }

    /** Сервер поменял слот — обновляем только его (не трогая предмет на курсоре). */
    private void peSlotChanged(int peWin, int peSlot) {
        int win = openWin >= 0 ? openWin : 0;
        for (int w : new int[]{0, win}) {
            if (w != 0 && w != openWin) continue;
            int n = windowSize(w);
            for (int j = 0; j < n; j++) {
                int[] ps = peOf(w, j);
                if (ps == null || ps[0] != peWin || ps[1] != peSlot) continue;
                JBuf b = new JBuf().u8(w).varint(stateId++).i16(j);
                writeSlot(b, peItem(ps));
                send(C_SET_SLOT, b);
            }
            if (w == win) break;
        }
    }

    private void sendInventory() {
        JBuf w = new JBuf();
        w.u8(0).varint(stateId++).varint(46);
        for (int j = 0; j < 46; j++) writeSlot(w, peItem(peOf(0, j)));
        writeSlot(w, null);
        send(C_WINDOW_ITEMS, w);
    }

    // ---- сундуки, печи и т.п. ----
    /** Тип окна 0.14 → (тип меню Java, слотов в Java). */
    private static int[] javaMenu(int peType, int slots) {
        switch (peType) {
            case 0: return slots > 27 ? new int[]{5, 54} : new int[]{2, 27}; // сундук / двойной
            case 2: return new int[]{13, 3};                                     // печь
            case 3: return new int[]{12, 2};                                     // стол зачарований
            case 4: return new int[]{10, 5};                                     // варочная стойка
            case 5: return new int[]{7, 3};                                      // наковальня
            case 6: case 7: return new int[]{6, 9};                              // раздатчик / выбрасыватель
            case 8: return new int[]{15, 5};                                     // воронка
            default: return null;
        }
    }

    private int javaContainerSize() { int[] m = javaMenu(openType, openSlots); return m == null ? 0 : m[1]; }

    /** Java-слот контейнера → слот 0.14 (у варочной стойки другой порядок). */
    private int toPeContainerSlot(int j) {
        if (openType == 4) { // Java: 0-2 бутылки, 3 ингредиент, 4 топливо; 0.14: 0 ингредиент, 1-3 бутылки
            if (j <= 2) return j + 1;
            if (j == 3) return 0;
            return -1;
        }
        return j < openSlots ? j : -1;
    }

    private void openContainer(int w, int type, int slots) {
        int[] m = javaMenu(type, slots);
        if (m == null) {
            if (type == 1 && joined) systemChat(chat("§eВерстак 0.14 пока не поддерживается мостом"), true);
            peSend(Proto.containerClose(w));
            return;
        }
        openWin = w; openType = type; openSlots = slots;
        containers.put(w, new int[Math.max(slots, m[1])][3]);
        view = null; cursorFull = false;
        String[] titles = {"container.chest", "container.crafting", "container.furnace", "container.enchant", "container.brewing",
                "container.repair", "container.dispenser", "container.dropper", "container.hopper"};
        String key = type == 0 && slots > 27 ? "container.chestDouble" : titles[type];
        if (joined) send(C_OPEN_WINDOW, new JBuf().varint(w).varint(m[0]).str("{\"translate\":\"" + key + "\"}"));
    }

    private void sendContainer() {
        if (openWin < 0) return;
        int n = windowSize(openWin);
        JBuf w = new JBuf();
        w.u8(openWin).varint(stateId++).varint(n);
        for (int j = 0; j < n; j++) writeSlot(w, peItem(peOf(openWin, j)));
        writeSlot(w, null);
        send(C_WINDOW_ITEMS, w);
    }
}
