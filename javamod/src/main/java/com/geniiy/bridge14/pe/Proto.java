package com.geniiy.bridge14.pe;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Пакеты MCPE 0.14.0/0.14.1 (протокол 45). */
public final class Proto {
    public static final int PROTOCOL = 46; // 0.14.1 (0.14.0 = 45)

    public static final int LOGIN = 0x8f, PLAY_STATUS = 0x90, DISCONNECT = 0x91, BATCH = 0x92, TEXT = 0x93,
            SET_TIME = 0x94, START_GAME = 0x95, ADD_PLAYER = 0x96, REMOVE_PLAYER = 0x97, ADD_ENTITY = 0x98,
            REMOVE_ENTITY = 0x99, ADD_ITEM_ENTITY = 0x9a, TAKE_ITEM_ENTITY = 0x9b, MOVE_ENTITY = 0x9c,
            MOVE_PLAYER = 0x9d, REMOVE_BLOCK = 0x9e, UPDATE_BLOCK = 0x9f, EXPLODE = 0xa1, LEVEL_EVENT = 0xa2,
            ENTITY_EVENT = 0xa4, MOB_EFFECT = 0xa5, UPDATE_ATTRIBUTES = 0xa6, MOB_EQUIPMENT = 0xa7, MOB_ARMOR_EQUIPMENT = 0xa8, INTERACT = 0xa9, USE_ITEM = 0xaa, PLAYER_ACTION = 0xab,
            SET_ENTITY_DATA = 0xad, SET_ENTITY_MOTION = 0xae, SET_HEALTH = 0xb0, SET_SPAWN_POSITION = 0xb1,
            ANIMATE = 0xb2, RESPAWN = 0xb3, DROP_ITEM = 0xb4, CONTAINER_OPEN = 0xb5, CONTAINER_CLOSE = 0xb6,
            CONTAINER_SET_SLOT = 0xb7, CONTAINER_SET_DATA = 0xb8, CONTAINER_SET_CONTENT = 0xb9, CRAFTING_DATA = 0xba, CRAFTING_EVENT = 0xbb, ADVENTURE_SETTINGS = 0xbc, BLOCK_ENTITY_DATA = 0xbd,
            ADD_PAINTING = 0xa0, SET_ENTITY_LINK = 0xaf, MAP_ITEM_DATA = 0xc6, MAP_INFO_REQUEST = 0xc7, ITEM_FRAME_DROP = 0xca,
            FULL_CHUNK_DATA = 0xbf, SET_PLAYER_GAMETYPE = 0xc2, PLAYER_LIST = 0xc3,
            REQUEST_CHUNK_RADIUS = 0xc8, CHUNK_RADIUS_UPDATE = 0xc9;

    public static final int TEXT_RAW = 0, TEXT_CHAT = 1, TEXT_TRANSLATION = 2, TEXT_POPUP = 3, TEXT_TIP = 4, TEXT_SYSTEM = 5;

    public static final int ACTION_START_BREAK = 0, ACTION_ABORT_BREAK = 1, ACTION_STOP_BREAK = 2,
            ACTION_RESPAWN = 7, ACTION_JUMP = 8, ACTION_START_SPRINT = 9, ACTION_STOP_SPRINT = 10,
            ACTION_START_SNEAK = 11, ACTION_STOP_SNEAK = 12;

    static Buf pk(int id) { return new Buf().u8(0x8e).u8(id); }

    // ---------------- клиент → сервер ----------------
    public static byte[] login(int protocol, String name, long clientId, UUID uuid, String serverAddr, byte[] skin) {
        return login(protocol, name, clientId, uuid, serverAddr, skin, "Standard_Custom");
    }

    public static byte[] login(int protocol, String name, long clientId, UUID uuid, String serverAddr, byte[] skin, String skinName) {
        Buf b = pk(LOGIN);
        b.str(name);
        b.i32(protocol).i32(protocol);
        b.i64(clientId);
        b.i64(uuid.getMostSignificantBits()).i64(uuid.getLeastSignificantBits());
        b.str(serverAddr);
        b.str(Long.toHexString(new java.util.Random().nextLong()));
        b.str(skinName);
        b.strBytes(skin);
        return b.bytes();
    }

    public static byte[] text(String source, String msg) {
        return pk(TEXT).u8(TEXT_CHAT).str(source).str(msg).bytes();
    }

    public static byte[] movePlayer(long eid, float x, float yEyes, float z, float yaw, float headYaw, float pitch, int mode, boolean onGround) {
        return pk(MOVE_PLAYER).i64(eid).f32(x).f32(yEyes).f32(z).f32(yaw).f32(headYaw).f32(pitch).u8(mode).bool(onGround).bytes();
    }

    public static byte[] requestChunkRadius(int r) { return pk(REQUEST_CHUNK_RADIUS).i32(r).bytes(); }

    public static byte[] action(long eid, int action, int x, int y, int z, int face) {
        return pk(PLAYER_ACTION).i64(eid).i32(action).i32(x).i32(y).i32(z).i32(face).bytes();
    }

    public static byte[] removeBlock(long eid, int x, int y, int z) {
        return pk(REMOVE_BLOCK).i64(eid).i32(x).i32(z).u8(y).bytes();
    }

    public static byte[] animate(long eid, int action) { return pk(ANIMATE).u8(action).i64(eid).bytes(); }

    public static byte[] interact(int action, long target) { return pk(INTERACT).u8(action).i64(target).bytes(); }

    /** UseItem; с протокола 70 (0.14.3) перед предметом идёт номер слота. */
    public static byte[] useItem(int x, int y, int z, int face, float fx, float fy, float fz, float px, float py, float pz, int[] item, int protocol, int slot) {
        Buf b = pk(USE_ITEM).i32(x).i32(y).i32(z).u8(face).f32(fx).f32(fy).f32(fz).f32(px).f32(py).f32(pz);
        if (protocol >= 70) b.i32(slot);
        slot(b, item);
        return b.bytes();
    }

    public static byte[] mobEquipment(long eid, int[] item, int slot, int selected) {
        Buf b = pk(MOB_EQUIPMENT).i64(eid);
        slot(b, item);
        return b.u8(slot).u8(selected).bytes();
    }

    /** Предмет: {id, count, meta, ref NBT}. */
    public static void slot(Buf b, int[] it) {
        if (it == null || it[0] == 0 || it[1] <= 0) { b.i16(0); return; }
        b.i16(it[0]).u8(it[1]).i16(it[2]);
        byte[] nbt = it.length > 3 ? com.geniiy.bridge14.proxy.ItemNbt.get(it[3]) : null;
        if (nbt == null) b.li16(0); else { b.li16(nbt.length); b.raw(nbt); }
    }

    public static byte[] entityEvent(long eid, int event) { return pk(ENTITY_EVENT).i64(eid).u8(event).bytes(); }

    public static byte[] dropItem(int[] item) {
        Buf b = pk(DROP_ITEM).u8(0);
        slot(b, item);
        return b.bytes();
    }

    public static byte[] containerSetSlot(int window, int slot, int hotbarSlot, int[] item) {
        Buf b = pk(CONTAINER_SET_SLOT).u8(window).i16(slot).i16(hotbarSlot);
        slot(b, item);
        return b.bytes();
    }

    public static byte[] containerClose(int window) { return pk(CONTAINER_CLOSE).u8(window).bytes(); }

    public static byte[] blockEntityData(int x, int y, int z, byte[] leNbt) {
        return pk(BLOCK_ENTITY_DATA).i32(x).i32(y).i32(z).raw(leNbt).bytes();
    }

    /** Крафт: 9 клеток сетки 3×3 (y*3+x) и результат. */
    public static byte[] craftingEvent(int window, int type, UUID recipe, int[][] input, int[] output) {
        Buf b = pk(CRAFTING_EVENT).u8(window).i32(type).i64(recipe.getMostSignificantBits()).i64(recipe.getLeastSignificantBits());
        b.i32(input.length);
        for (int[] it : input) slot(b, it);
        b.i32(1);
        slot(b, output);
        return b.bytes();
    }

    public static byte[] mapInfoRequest(long mapId) { return pk(MAP_INFO_REQUEST).i64(mapId).bytes(); }

    public static byte[] itemFrameDrop(int x, int y, int z, int[] item) {
        Buf b = pk(ITEM_FRAME_DROP).i32(x).i32(y).i32(z);
        slot(b, item);
        return b.bytes();
    }

    public static byte[] respawn(float x, float y, float z) { return pk(RESPAWN).f32(x).f32(y).f32(z).bytes(); }

    // ---------------- разбор ----------------
    /** Читает предмет (slot). Возвращает {id, count, meta, ref NBT}. */
    public static int[] readSlot(Buf b) {
        int id = b.s16();
        if (id <= 0) return new int[]{0, 0, 0, 0};
        int cnt = b.u8();
        int meta = b.s16();
        int nbt = b.lu16();
        int ref = 0;
        if (nbt > 0) ref = com.geniiy.bridge14.proxy.ItemNbt.ref(b.take(nbt));
        return new int[]{id, cnt, meta, ref};
    }

    /** Пропускает метаданные сущности, вытаскивая имя (nametag), если есть. */
    public static String readMetadata(Buf b) {
        String name = null;
        while (b.left() > 0) {
            int h = b.u8();
            if (h == 0x7f) break;
            int type = h >> 5, key = h & 0x1f;
            switch (type) {
                case 0: b.skip(1); break;
                case 1: b.skip(2); break;
                case 2: b.skip(4); break;
                case 3: b.skip(4); break;
                case 4: { int n = b.lu16(); byte[] s = b.take(n); if (key == 2) name = new String(s, java.nio.charset.StandardCharsets.UTF_8); break; }
                case 5: b.skip(5); break;
                case 6: b.skip(12); break;
                case 7: b.skip(12); break;
                case 8: b.skip(8); break;
                default: return name;
            }
        }
        return name;
    }

    public static byte[] inflate(byte[] data) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
        byte[] tmp = new byte[65536];
        while (!inf.finished()) {
            int n = inf.inflate(tmp);
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) break;
            }
            out.write(tmp, 0, n);
            if (out.size() > 64 * 1024 * 1024) throw new Exception("batch too big");
        }
        inf.end();
        return out.toByteArray();
    }

    public static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(7);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] tmp = new byte[65536];
        while (!d.finished()) { int n = d.deflate(tmp); out.write(tmp, 0, n); }
        d.end();
        return out.toByteArray();
    }
}
