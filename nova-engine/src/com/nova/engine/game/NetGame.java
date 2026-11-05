package com.nova.engine.game;

import com.nova.engine.net.RakNet;
import com.nova.engine.world.Chunk;
import com.nova.engine.world.World;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Inflater;

/**
 * Playing on a 0.14 server (network protocol 70, SharedConstants::NetworkProtocolVersion).
 * Wire id = Packet::getId() + 0x8e (MinecraftPackets::createPacket subtracts 0x8f): Login 0x8f, PlayStatus 0x90,
 * Disconnect 0x91, Batch 0x92, Text 0x93, SetTime 0x94, StartGame 0x95, AddPlayer 0x96, RemovePlayer 0x97,
 * RemoveEntity 0x99, MoveEntity 0x9c, MovePlayer 0x9d, RemoveBlock 0x9e, UpdateBlock 0x9f, UseItem 0xaa,
 * PlayerAction 0xab, SetHealth 0xb0, SetSpawnPosition 0xb1, Animate 0xb2, Respawn 0xb3, FullChunkData 0xbf,
 * RequestChunkRadius 0xc8, ChunkRadiusUpdated 0xc9. Field layouts from the packets' read/write (big endian,
 * strings = u16 length + bytes). Chunks: LevelChunk::deserializeTerrain = ids 32768 (x << 11 | z << 7 | y),
 * data 16384, sky 16384, block light 16384, heightmap 256, biome colours 256 ints (id << 24 | rgb).
 */
public final class NetGame implements RakNet.Listener {
    public static final int PROTOCOL = 70;

    public final String host, name;
    public final int port;
    private RakNet rak;
    private final ConcurrentLinkedQueue<byte[]> in = new ConcurrentLinkedQueue<byte[]>();
    public volatile String disconnect;
    public long eid;
    public int gamemode;
    public boolean started, spawned;
    public final java.util.ArrayList<String[]> chat = new java.util.ArrayList<String[]>();   // {text, time}
    private final byte[] skin;

    public NetGame(String host, int port, String name, byte[] skin) {
        this.host = host; this.port = port; this.name = name; this.skin = skin;
    }

    public void connect() {
        try { rak = new RakNet(host, port, this); } catch (Exception e) { disconnect = "disconnectionScreen.noReason"; }
    }

    public void close() { if (rak != null) rak.close(); }

    // ------------------------------------------------------------------ RakNet thread
    private boolean loginSent;

    public void onPacket(byte[] d) { in.add(d); }

    public void onDisconnect(String reason) { if (disconnect == null) disconnect = reason; }

    private void send(RakNet.Buf b) { if (rak != null) rak.send(b.copy()); }

    private void login() {
        RakNet.Buf b = new RakNet.Buf();
        java.util.Random r = new java.util.Random();
        b.u8(0x8f); b.str(name); b.i32(PROTOCOL); b.i32(PROTOCOL); b.i64(r.nextLong());
        b.i64(r.nextLong()); b.i64(r.nextLong());                     // mce::UUID
        b.str(host + ":" + port); b.str(Long.toString(r.nextLong()));  // server address, client secret
        b.str("Standard_Custom");
        b.i16(skin.length); b.raw(skin);
        send(b);
    }

    // ------------------------------------------------------------------ GL thread
    /** handle queued packets; returns false when the session ended */
    public boolean tick(Game g, World w, Player p) {
        if (!loginSent && rak != null && rak.connected()) { loginSent = true; login(); }
        byte[] d;
        int n = 0;
        while ((d = in.poll()) != null && n++ < 200) handle(d, g, w, p);
        if (spawned) sendMove(p);
        return disconnect == null;
    }

    private void handle(byte[] d, Game g, World w, Player p) {
        RakNet.Rd r = new RakNet.Rd(d, 0, d.length);
        int id = r.u8();
        try {
            switch (id) {
                case 0x92: {                                        // BatchPacket: int length + zlib(int len + packet)*
                    int len = r.i32();
                    Inflater inf = new Inflater();
                    inf.setInput(d, r.p, Math.min(len, r.left()));
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] buf = new byte[65536];
                    while (!inf.finished()) { int k = inf.inflate(buf); if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break; o.write(buf, 0, k); }
                    inf.end();
                    byte[] all = o.toByteArray();
                    RakNet.Rd q = new RakNet.Rd(all, 0, all.length);
                    while (q.left() >= 4) {
                        int l = q.i32();
                        if (l <= 0 || l > q.left()) break;
                        handle(q.bytes(l), g, w, p);
                    }
                    break;
                }
                case 0x90: {                                        // PlayStatus: 0 login ok, 1 client old, 2 server old, 3 spawn
                    int s = r.i32();
                    if (s == 3) spawned = true;
                    else if (s == 1) disconnect = "disconnectionScreen.outdatedClient";
                    else if (s == 2) disconnect = "disconnectionScreen.outdatedServer";
                    break;
                }
                case 0x91: disconnect = r.str(); break;
                case 0x93: text(r); break;
                case 0x94: w.time = r.i32(); break;
                case 0x95: {                                        // StartGame
                    r.i32(); r.u8(); r.i32(); gamemode = r.i32(); eid = r.i64();
                    r.i32(); r.i32(); r.i32();
                    float x = r.f32(), y = r.f32(), z = r.f32();
                    p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z;
                    p.mx = p.my = p.mz = 0;
                    p.flying = false;
                    started = true;
                    RakNet.Buf b = new RakNet.Buf(); b.u8(0xc8); b.i32(g.radius()); send(b);
                    break;
                }
                case 0x9d: {                                        // MovePlayer
                    long e = r.i64();
                    float x = r.f32(), y = r.f32(), z = r.f32(), yaw = r.f32(); r.f32(); float pitch = r.f32();
                    if (e == eid || e == 0) {
                        p.x = p.px = x; p.y = p.py = y - Player.EYE; p.z = p.pz = z;
                        p.yaw = yaw - 180f; p.pitch = pitch; p.mx = p.my = p.mz = 0;
                    }
                    break;
                }
                case 0xb3: {                                        // Respawn
                    float x = r.f32(), y = r.f32(), z = r.f32();
                    p.x = p.px = x; p.y = p.py = y; p.z = p.pz = z; p.mx = p.my = p.mz = 0;
                    break;
                }
                case 0x9f: {                                        // UpdateBlock: records of x z y id (flags << 4 | data)
                    int count = r.i32();
                    for (int i = 0; i < count && r.left() >= 11; i++) {
                        int x = r.i32(), z = r.i32(), y = r.u8(), bid = r.u8(), fd = r.u8();
                        setBlock(w, x, y, z, bid, fd & 15);
                    }
                    break;
                }
                case 0xbf: chunk(r, w); break;
                default: break;
            }
        } catch (Exception ex) { android.util.Log.w("NOVA", "packet " + Integer.toHexString(id), ex); }
    }

    private void text(RakNet.Rd r) {
        int type = r.u8();
        String msg;
        switch (type) {
            case 1: { String src = r.str(); msg = "<" + src + "> " + r.str(); break; }
            case 3: { r.str(); msg = r.str(); break; }
            case 2: { msg = r.str(); int c = r.u8(); for (int i = 0; i < c; i++) msg += " " + r.str(); break; }
            default: msg = r.str(); break;
        }
        synchronized (chat) {
            chat.add(new String[]{msg, Long.toString(System.currentTimeMillis())});
            while (chat.size() > 100) chat.remove(0);
        }
    }

    private static void setBlock(World w, int x, int y, int z, int id, int data) {
        if (y < 0 || y >= Chunk.H) return;
        Chunk c = w.chunk(x >> 4, z >> 4);
        if (c == null) return;
        if (c.generated) w.set(x, y, z, id, data);
        else c.set(x & 15, y, z & 15, id, data);
    }

    private void chunk(RakNet.Rd r, World w) {
        int cx = r.i32(), cz = r.i32();
        int order = r.u8();                                         // 0 columns (x << 11 | z << 7 | y), 1 layered (y << 8 | z << 4 | x)
        int len = r.i32();
        if (len < 32768 + 16384 * 3 || r.left() < 32768 + 16384) return;
        Chunk old = w.chunk(cx, cz);
        if (old != null && old.generated) return;                  // already installed: ignore resends
        Chunk c = new Chunk(cx, cz);
        int base = r.p;
        byte[] a = r.a;
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < Chunk.H; y++) {
            int i = order == 1 ? (y << 8) | (z << 4) | x : (x << 11) | (z << 7) | y;
            int id = a[base + i] & 255;
            int nb = a[base + 32768 + (i >> 1)] & 255;
            int dv = (i & 1) == 0 ? nb & 15 : nb >> 4;
            int k = Chunk.idx(x, y, z);
            c.blocks[k] = (byte) id; c.data[k] = (byte) dv;
        }
        int bo = base + 32768 + 16384 * 3 + 256;
        if (bo + 1024 <= r.end) for (int i = 0; i < 256; i++) {
            int v = (a[bo + i * 4] & 255) << 24 | (a[bo + i * 4 + 1] & 255) << 16 | (a[bo + i * 4 + 2] & 255) << 8 | (a[bo + i * 4 + 3] & 255);
            c.biome[i] = (byte) (v >>> 24);
            c.grass[i] = v & 0xFFFFFF;
        }
        c.stage = 2;
        w.chunks.put(World.key(cx, cz), c);
    }

    // ------------------------------------------------------------------ to the server
    private double lx, ly, lz; private float lyaw, lpitch; private int moveTick;

    private void sendMove(Player p) {
        moveTick++;
        if (p.x == lx && p.y == ly && p.z == lz && p.yaw == lyaw && p.pitch == lpitch && moveTick < 20) return;
        moveTick = 0;
        lx = p.x; ly = p.y; lz = p.z; lyaw = p.yaw; lpitch = p.pitch;
        RakNet.Buf b = new RakNet.Buf();
        float yaw = p.yaw + 180f;
        b.u8(0x9d); b.i64(eid); b.f32((float) p.x); b.f32((float) p.y + Player.EYE); b.f32((float) p.z);
        b.f32(yaw); b.f32(yaw); b.f32(p.pitch); b.u8(0); b.u8(p.onGround ? 1 : 0);
        send(b);
    }

    /** RemoveBlockPacket: eid, x, z, y */
    public void breakBlock(int x, int y, int z) {
        RakNet.Buf b = new RakNet.Buf();
        b.u8(0xab); b.i64(eid); b.i32(0); b.i32(x); b.i32(y); b.i32(z); b.i32(1); send(b);   // PlayerAction START_BREAK
        b = new RakNet.Buf();
        b.u8(0x9e); b.i64(eid); b.i32(x); b.i32(z); b.u8(y); send(b);
    }

    /** UseItemPacket: block x y z, face, click fx fy fz, player position, slot, item */
    public void useItem(int x, int y, int z, int face, Player p, int itemId, int itemData) {
        // MobEquipmentPacket first (eid, item, slot, selected slot): the server checks the held item
        RakNet.Buf e = new RakNet.Buf();
        e.u8(0xa7); e.i64(eid); e.i16(itemId);
        if (itemId != 0) { e.u8(1); e.i16(itemData); e.i16(0); }
        e.u8(9); e.u8(0);
        send(e);
        RakNet.Buf b = new RakNet.Buf();
        b.u8(0xaa); b.i32(x); b.i32(y); b.i32(z); b.u8(face);
        b.f32(0.5f); b.f32(0.5f); b.f32(0.5f);
        b.f32((float) p.x); b.f32((float) p.y + Player.EYE); b.f32((float) p.z);
        b.i32(0);
        b.i16(itemId);
        if (itemId != 0) { b.u8(1); b.i16(itemData); b.i16(0); }
        send(b);
    }

    public void swing() { RakNet.Buf b = new RakNet.Buf(); b.u8(0xb2); b.u8(1); b.i64(eid); send(b); }

    public void say(String msg) { RakNet.Buf b = new RakNet.Buf(); b.u8(0x93); b.u8(1); b.str(name); b.str(msg); send(b); }
}
