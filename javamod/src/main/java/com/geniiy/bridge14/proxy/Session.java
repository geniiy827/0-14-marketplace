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
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Одна игровая сессия: Java-клиент 1.20.1 ↔ сервер MCPE 0.14.
 * Для Java-клиента мы — обычный сервер без шифрования, для сервера 0.14 — обычный игрок.
 */
public final class Session implements RakClient.Listener {

    public interface Listener { void status(String s); void log(String s); void ended(String reason); }

    public static final class Config {
        public String nick = "Steve";
        public byte[] skin;              // RGBA 64×64 или 64×32
        public String host = "127.0.0.1";
        public int port = 19132;
        public int chunkRadius = 6;
    }

    // ---- Java пакеты (1.20.1, протокол 763) ----
    static final int C_SPAWN_ENTITY = 0x01, C_SPAWN_PLAYER = 0x03, C_ANIMATION = 0x04, C_ACK_DIG = 0x06, C_BLOCK_CHANGE = 0x0a,
            C_WINDOW_ITEMS = 0x12, C_SET_SLOT = 0x14, C_KICK = 0x1a, C_ENTITY_STATUS = 0x1c, C_GAME_EVENT = 0x1f,
            C_HURT = 0x21, C_KEEP_ALIVE = 0x23, C_CHUNK = 0x24, C_LOGIN = 0x28, C_ABILITIES = 0x34, C_PLAYER_REMOVE = 0x39,
            C_PLAYER_INFO = 0x3a, C_POSITION = 0x3c, C_DESTROY = 0x3e, C_RESPAWN = 0x41, C_HEAD_ROT = 0x42, C_SERVER_DATA = 0x45,
            C_HELD_SLOT = 0x4d, C_VIEW_POS = 0x4e, C_SPAWN_POS = 0x50, C_METADATA = 0x52, C_VELOCITY = 0x54, C_HEALTH = 0x57,
            C_TIME = 0x5e, C_SYSTEM_CHAT = 0x64, C_COLLECT = 0x67, C_TELEPORT = 0x68;
    static final int S_TELEPORT_CONFIRM = 0x00, S_CHAT_COMMAND = 0x04, S_CHAT = 0x05, S_CLIENT_COMMAND = 0x07, S_USE_ENTITY = 0x10,
            S_KEEP_ALIVE = 0x12, S_POS = 0x14, S_POS_LOOK = 0x15, S_LOOK = 0x16, S_FLYING = 0x17, S_DIG = 0x1d, S_ACTION = 0x1e,
            S_HELD_SLOT = 0x28, S_CREATIVE_SLOT = 0x2b, S_SWING = 0x2f, S_PLACE = 0x31, S_USE_ITEM = 0x32;

    private final Socket sock;
    private final Config cfg;
    private final Listener L;
    private OutputStream out;
    private volatile boolean alive = true;
    private RakClient rak;
    private final Random rnd = new Random();

    private int jState;        // 0 рукопожатие, 1 статус, 2 вход, 3 игра
    private boolean peSpawned, peStarted, joined;
    private String javaName;
    private UUID selfUuid;

    // состояние мира (храним, чтобы отдать клиенту при входе и для логики)
    private long selfEid = -1;
    private int gamemode;
    private double x, y, z; private float yaw, pitch;
    private int health = 20;
    private int spawnX, spawnY = 64, spawnZ;
    private long time = 6000;
    private final Map<Long, ChunkConv.Chunk> chunks = new HashMap<>();
    private final Map<Long, Ent> ents = new HashMap<>();
    private final Map<Long, String> peNames = new HashMap<>();
    private final Map<Integer, Long> javaToPe = new HashMap<>();
    private int nextJavaId = 2, teleportId = 1, lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private final int[][] inv = new int[36][3];
    private final int[] hotbar = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private int held;
    private long lastKeepAlive;

    static final class Ent {
        long pe; int jid, jtype; boolean player; UUID uuid; String name = "";
        float x, y, z, yaw, pitch; int[] item;
    }

    public Session(Socket sock, Config cfg, Listener l) { this.sock = sock; this.cfg = cfg; this.L = l; }

    public void start() {
        Thread t = new Thread(this::readLoop, "j-read");
        t.setDaemon(true);
        t.start();
        Thread k = new Thread(this::keepLoop, "j-keep");
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

    private void keepLoop() {
        while (alive) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            if (jState == 3 && joined && System.currentTimeMillis() - lastKeepAlive > 10000) {
                lastKeepAlive = System.currentTimeMillis();
                try { send(C_KEEP_ALIVE, new JBuf().i64(lastKeepAlive)); } catch (Exception ignored) {}
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
                String nick = cfg.nick == null || cfg.nick.isEmpty() ? javaName : cfg.nick;
                selfUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nick).getBytes(StandardCharsets.UTF_8));
                L.status("Подключаюсь к серверу 0.14…");
                startPe(nick);
            }
            return;
        }
        synchronized (this) { onPlay(id, b); }
    }

    private void onPlay(int id, JBuf b) {
        switch (id) {
            case S_POS: { x = b.f64(); y = b.f64(); z = b.f64(); boolean g = b.bool(); moved(g); break; }
            case S_POS_LOOK: { x = b.f64(); y = b.f64(); z = b.f64(); yaw = b.f32(); pitch = b.f32(); boolean g = b.bool(); moved(g); break; }
            case S_LOOK: { yaw = b.f32(); pitch = b.f32(); boolean g = b.bool(); moved(g); break; }
            case S_FLYING: break;
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
                    case 3: case 4: { // выбросить предмет
                        int[] it = heldItem();
                        if (it[0] != 0) peSend(Proto.dropItem(it[0], status == 3 ? it[1] : 1, it[2]));
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
                int peSlot = javaSlotToPe(slot);
                if (peSlot < 0) break;
                if (it == null) { inv[peSlot][0] = 0; inv[peSlot][1] = 0; inv[peSlot][2] = 0; }
                else {
                    int[] pe = Tables.itemBack(it[0]);
                    if (pe == null) { systemChat(chat("§eЭтого предмета нет в 0.14"), true); resendSlot(slot); break; }
                    inv[peSlot][0] = pe[0]; inv[peSlot][1] = Math.max(1, it[1]); inv[peSlot][2] = pe[1];
                }
                if (slot >= 36 && slot <= 44 && slot - 36 == held) equipHeld();
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
            default: break; // подтверждения, настройки клиента, keep-alive и прочее — не нужны серверу 0.14
        }
    }

    private void breakBlock(int[] p) {
        peSend(Proto.removeBlock(selfEid, p[0], p[1], p[2]));
        ChunkConv.Chunk c = chunks.get(ck(p[0] >> 4, p[2] >> 4));
        if (c != null && p[1] >= 0 && p[1] < 128) { int i = ChunkConv.idx(p[0] & 15, p[1], p[2] & 15); c.state[i] = 0; c.pe[i] = 0; }
    }

    private int[] heldItem() { int s = hotbar[held]; return s >= 0 && s < 36 ? inv[s] : new int[3]; }

    private void equipHeld() {
        int s = hotbar[held];
        if (s < 0 || s >= 36) s = held;
        int[] it = inv[s];
        peSend(Proto.mobEquipment(selfEid, it[0], it[1], it[2], s + 9, held));
    }

    private int javaSlotToPe(int j) {
        if (j >= 36 && j <= 44) { int s = hotbar[j - 36]; return s >= 0 && s < 36 ? s : j - 36; }
        if (j >= 9 && j <= 35) return j;
        return -1;
    }

    private long lastMoveSent;
    private void moved(boolean onGround) {
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx != lastCx || cz != lastCz) { lastCx = cx; lastCz = cz; send(C_VIEW_POS, new JBuf().varint(cx).varint(cz)); }
        if (selfEid < 0) return;
        peSend(Proto.movePlayer(selfEid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
    }

    private String peNick() { return cfg.nick == null || cfg.nick.isEmpty() ? javaName : cfg.nick; }

    // ======================= Сервер 0.14 =======================
    private void startPe(String nick) {
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
        UUID u = UUID.nameUUIDFromBytes(("Bridge:" + peNick()).getBytes());
        byte[] skin = cfg.skin != null ? cfg.skin : DefaultSkin.make();
        if (r != null) r.send(Proto.login(proto, peNick(), rnd.nextLong(), u, cfg.host + ":" + cfg.port, skin));
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

    private static long ck(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }

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
                    if (!peStarted) peStarted = true;
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
                peStarted = true;
                RakClient r = rak;
                if (r != null) r.send(Proto.requestChunkRadius(cfg.chunkRadius));
                break;
            }
            case Proto.SET_TIME: { time = b.i32(); if (joined) sendTime(); break; }
            case Proto.SET_SPAWN_POSITION: { spawnX = b.i32(); spawnY = b.i32(); spawnZ = b.i32(); if (joined) sendSpawn(); break; }
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32(); int order = b.u8(); int n = b.i32();
                ChunkConv.Chunk c = ChunkConv.fromPe(b.take(Math.min(n, b.left())), order);
                chunks.put(ck(cx, cz), c);
                if (joined) send(C_CHUNK, new JBuf().raw(ChunkConv.toJava(cx, cz, c)));
                break;
            }
            case Proto.UPDATE_BLOCK: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 11; i++) {
                    int bx = b.i32(), bz = b.i32(), by = b.u8(), bid = b.u8(), fm = b.u8();
                    int st = Tables.block(bid, fm & 15);
                    ChunkConv.Chunk c = chunks.get(ck(bx >> 4, bz >> 4));
                    if (c != null && by < 128) { int ix = ChunkConv.idx(bx & 15, by, bz & 15); c.state[ix] = st; c.pe[ix] = (byte) bid; }
                    if (joined) send(C_BLOCK_CHANGE, new JBuf().position(bx, by, bz).varint(st));
                }
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float pp = b.f32();
                if (e == selfEid) {
                    x = px; y = py - 1.62; z = pz; yaw = pyaw; pitch = pp;
                    if (joined) sendPosition();
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
                removeEnt(e);
                ents.put(e, en); javaToPe.put(en.jid, e); peNames.put(e, name);
                if (joined) spawn(en);
                break;
            }
            case Proto.ADD_ENTITY: {
                long e = b.i64();
                int type = b.i32() & 0xff;
                int jt = Tables.entity(type);
                if (jt < 0) break;
                Ent en = new Ent();
                en.pe = e; en.jid = nextJavaId++; en.jtype = jt; en.uuid = UUID.randomUUID();
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                b.f32(); b.f32(); b.f32();
                en.yaw = b.f32(); en.pitch = b.f32();
                removeEnt(e);
                ents.put(e, en); javaToPe.put(en.jid, e);
                if (joined) spawn(en);
                break;
            }
            case Proto.ADD_ITEM_ENTITY: {
                long e = b.i64();
                int[] it = Proto.readSlot(b);
                Ent en = new Ent();
                en.pe = e; en.jid = nextJavaId++; en.jtype = Tables.ITEM_ENTITY_TYPE; en.uuid = UUID.randomUUID(); en.item = it;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                removeEnt(e);
                ents.put(e, en); javaToPe.put(en.jid, e);
                if (joined) spawn(en);
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
                    en.x = px; en.y = en.player ? py - 1.62f : py; en.z = pz; en.yaw = pyaw; en.pitch = pp;
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
                    else if (ev == 3) send(C_ENTITY_STATUS, new JBuf().i32(j).u8(3));
                }
                break;
            }
            case Proto.SET_HEALTH: {
                health = b.i32();
                if (joined) sendHealth();
                break;
            }
            case Proto.RESPAWN: {
                x = b.f32(); y = b.f32(); z = b.f32();
                health = 20;
                if (joined) {
                    send(C_RESPAWN, new JBuf().str("minecraft:overworld").str("minecraft:overworld").i64(0).u8(gamemode).u8(255)
                            .bool(false).bool(false).u8(0).bool(false).varint(0));
                    sendPosition(); sendHealth(); sendAbilities();
                    for (Map.Entry<Long, ChunkConv.Chunk> c : chunks.entrySet()) {
                        long k = c.getKey(); int cx = (int) (k >> 32), cz = (int) k;
                        send(C_CHUNK, new JBuf().raw(ChunkConv.toJava(cx, cz, c.getValue())));
                    }
                }
                break;
            }
            case Proto.SET_PLAYER_GAMETYPE: {
                gamemode = b.i32();
                if (joined) { send(C_GAME_EVENT, new JBuf().u8(3).f32(gamemode)); sendAbilities(); }
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
                } else systemChat(chat(msg), type == Proto.TEXT_POPUP || type == Proto.TEXT_TIP);
                break;
            }
            case Proto.CONTAINER_SET_CONTENT: {
                int win = b.u8();
                int n = b.u16();
                List<int[]> slots = new ArrayList<>();
                for (int i = 0; i < n; i++) slots.add(Proto.readSlot(b));
                if (win == 0) {
                    for (int i = 0; i < 36; i++) { int[] s = i < slots.size() ? slots.get(i) : new int[3]; inv[i][0] = s[0]; inv[i][1] = s[1]; inv[i][2] = s[2]; }
                    if (b.left() >= 2) {
                        int hn = b.u16();
                        for (int i = 0; i < hn && b.left() >= 4; i++) { int v = b.i32(); if (i < 9) hotbar[i] = v >= 9 ? v - 9 : (v >= 0 ? v : i); }
                    }
                    if (joined) sendInventory();
                }
                break;
            }
            case Proto.CONTAINER_SET_SLOT: {
                int win = b.u8(); int slot = b.s16(); b.s16();
                int[] it = Proto.readSlot(b);
                if (win == 0 && slot >= 0 && slot < 36) {
                    inv[slot][0] = it[0]; inv[slot][1] = it[1]; inv[slot][2] = it[2];
                    if (joined) sendInventory();
                }
                break;
            }
            case Proto.MOB_EQUIPMENT: {
                long e = b.i64(); int[] it = Proto.readSlot(b); int slot = b.u8(), sel = b.u8();
                if (e == selfEid && sel < 9) {
                    held = sel;
                    if (slot >= 9 && slot - 9 < 36) hotbar[sel] = slot - 9;
                    if (joined) send(C_HELD_SLOT, new JBuf().u8(held));
                }
                break;
            }
            default: break;
        }
    }

    private static int v(float m) { return (int) Math.max(-32768, Math.min(32767, m * 8000)); }

    // ======================= вход в мир для Java =======================
    private void join() {
        if (joined || out == null) return;
        String nick = peNick();
        // LoginSuccess → состояние «игра»
        send(0x02, new JBuf().uuid(selfUuid).str(nick).varint(0));
        jState = 3;
        JBuf lg = new JBuf();
        lg.i32(1).bool(false).u8(gamemode).u8(255);
        lg.varint(1).str("minecraft:overworld");
        lg.raw(Tables.CODEC);
        lg.str("minecraft:overworld").str("minecraft:overworld").i64(0);
        lg.varint(20).varint(Math.max(2, cfg.chunkRadius)).varint(Math.max(2, cfg.chunkRadius));
        lg.bool(false).bool(true).bool(false).bool(false).bool(false).varint(0);
        send(C_LOGIN, lg);
        send(C_SERVER_DATA, new JBuf().str(chat("Сервер 0.14")).bool(false).bool(false));
        sendAbilities();
        send(C_HELD_SLOT, new JBuf().u8(held));
        sendSpawn();
        // список игроков: сами
        send(C_PLAYER_INFO, new JBuf().u8(0x1D).varint(1).uuid(selfUuid).str(nick).varint(0).varint(gamemode).u8(1).varint(0));
        joined = true;
        lastCx = (int) Math.floor(x) >> 4; lastCz = (int) Math.floor(z) >> 4;
        send(C_VIEW_POS, new JBuf().varint(lastCx).varint(lastCz));
        for (Map.Entry<Long, ChunkConv.Chunk> c : chunks.entrySet()) {
            long k = c.getKey(); int cx = (int) (k >> 32), cz = (int) k;
            send(C_CHUNK, new JBuf().raw(ChunkConv.toJava(cx, cz, c.getValue())));
        }
        sendPosition();
        sendTime();
        sendHealth();
        sendInventory();
        for (Ent e : ents.values()) spawn(e);
        L.status("В игре: " + cfg.host + ":" + cfg.port);
        L.log("Вошли на сервер 0.14 как " + nick);
    }

    private void sendAbilities() {
        int flags = gamemode == 1 ? (0x01 | 0x04 | 0x08) : 0;
        send(C_ABILITIES, new JBuf().u8(flags).f32(0.05f).f32(0.1f));
    }

    private void sendSpawn() { send(C_SPAWN_POS, new JBuf().position(spawnX, spawnY, spawnZ).f32(0)); }

    private void sendPosition() {
        send(C_POSITION, new JBuf().f64(x).f64(y).f64(z).f32(yaw).f32(pitch).u8(0).varint(teleportId++));
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        if (cx != lastCx || cz != lastCz) { lastCx = cx; lastCz = cz; send(C_VIEW_POS, new JBuf().varint(cx).varint(cz)); }
    }

    private void sendHealth() { send(C_HEALTH, new JBuf().f32(Math.max(0, health)).varint(20).f32(5)); }

    private void sendTime() {
        long t = time * 24000L / 19200L; // в 0.14 сутки 19200 тиков
        send(C_TIME, new JBuf().i64(t).i64(t % 24000));
    }

    private int stateId = 1;
    private void sendInventory() {
        JBuf w = new JBuf();
        w.u8(0).varint(stateId++).varint(46);
        for (int j = 0; j < 46; j++) {
            int pe = -1;
            if (j >= 36 && j <= 44) { int s = hotbar[j - 36]; pe = s >= 0 && s < 36 ? s : j - 36; }
            else if (j >= 9 && j <= 35) pe = j;
            writeSlot(w, pe >= 0 ? inv[pe] : null);
        }
        writeSlot(w, null);
        send(C_WINDOW_ITEMS, w);
    }

    private void resendSlot(int slot) { sendInventory(); }

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

    private void spawn(Ent e) {
        if (e.player) {
            send(C_PLAYER_INFO, new JBuf().u8(0x1D).varint(1).uuid(e.uuid).str(trim16(e.name)).varint(0).varint(0).u8(1).varint(0));
            send(C_SPAWN_PLAYER, new JBuf().varint(e.jid).uuid(e.uuid).f64(e.x).f64(e.y).f64(e.z).angle(e.yaw).angle(e.pitch));
        } else {
            send(C_SPAWN_ENTITY, new JBuf().varint(e.jid).uuid(e.uuid).varint(e.jtype).f64(e.x).f64(e.y).f64(e.z)
                    .angle(e.pitch).angle(e.yaw).angle(e.yaw).varint(0).i16(0).i16(0).i16(0));
            if (e.item != null) {
                JBuf m = new JBuf().varint(e.jid).u8(8).varint(7);
                writeSlot(m, e.item);
                m.u8(0xff);
                send(C_METADATA, m);
            }
        }
        send(C_HEAD_ROT, new JBuf().varint(e.jid).angle(e.yaw));
    }

    private static String trim16(String s) { s = s.replaceAll("§.", ""); return s.length() > 16 ? s.substring(0, 16) : (s.isEmpty() ? "?" : s); }

    private void teleport(Ent e) {
        send(C_TELEPORT, new JBuf().varint(e.jid).f64(e.x).f64(e.y).f64(e.z).angle(e.yaw).angle(e.pitch).bool(true));
        send(C_HEAD_ROT, new JBuf().varint(e.jid).angle(e.yaw));
    }

    private void removeEnt(long pe) {
        Ent e = ents.remove(pe);
        if (e == null) return;
        javaToPe.remove(e.jid);
        if (!joined) return;
        send(C_DESTROY, new JBuf().varint(1).varint(e.jid));
        if (e.player) send(C_PLAYER_REMOVE, new JBuf().varint(1).uuid(e.uuid));
    }
}
