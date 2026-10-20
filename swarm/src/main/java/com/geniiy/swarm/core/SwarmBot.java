package com.geniiy.swarm.core;

import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Лёгкий бот для нагрузочного теста сервера: заходит, регистрируется,
 * бегает рядом со спавном и прыгает. Без загрузки чанков и физики —
 * чтобы можно было держать много ботов одновременно. Падений нет
 * (движение в 0.14 на стороне клиента), на всякий случай авто-возрождение.
 */
public final class SwarmBot implements RakClient.Listener {
    public interface Ui { void log(String s); }

    public final int id;
    public final String name;
    private final String password;
    private final String host;
    private final int port;
    private final String registerTemplate;
    private final Ui ui;
    private final Random rnd;

    private volatile RakClient rak;
    private volatile boolean running, userStopped;
    public volatile String status = "ожидание";
    public volatile boolean online;

    // состояние в мире
    private long eid = -1;
    private boolean spawned;
    private double x, y, z, homeX, homeZ, spawnY;
    private float yaw;
    private int health = 20;
    private long deadSince, spawnedAt, registeredAt;
    private boolean registered;

    // поведение
    private double heading;
    private int headingLeft, jumpBob, sinceMove;
    private final double radius, speed;
    private final int jumpEvery;
    private long tick;

    public SwarmBot(int id, String name, String password, String host, int port, String registerTemplate, long seed, Ui ui) {
        this.id = id; this.name = name; this.password = password;
        this.host = host; this.port = port; this.registerTemplate = registerTemplate; this.ui = ui;
        this.rnd = new Random(seed);
        this.radius = 5 + rnd.nextInt(6);         // гуляет в 5–10 блоках от спавна
        this.speed = 0.10 + rnd.nextDouble() * 0.06;
        this.jumpEvery = 25 + rnd.nextInt(45);
        this.heading = rnd.nextDouble() * Math.PI * 2;
    }

    public void start() throws Exception {
        userStopped = false;
        status = "подключение";
        rak = new RakClient(host, port, this);
        rak.start();
        running = true;
    }

    public void stop() {
        userStopped = true;
        running = false;
        online = false;
        status = "отключён";
        final RakClient r = rak; rak = null;
        if (r != null) new Thread(() -> r.close("Стоп")).start();
    }

    private void send(byte[] pk) { RakClient r = rak; if (r != null) r.send(pk); }

    // ---- RakNet ----
    @Override public void onConnected() {
        status = "вход в мир";
        UUID uuid = UUID.nameUUIDFromBytes(("Swarm:" + name).getBytes());
        RakClient r = rak;
        int proto = r != null && r.gameProtocol != 0 ? r.gameProtocol : Proto.PROTOCOL;
        send(Proto.login(proto, name, rnd.nextLong(), uuid, host + ":" + port, Skin.make()));
    }

    @Override public void onClosed(String reason) {
        running = false; online = false;
        if (!userStopped) { status = "разорвано: " + reason; ui.log(name + ": " + reason); }
    }

    @Override public void log(String msg) { /* тихо, чтобы не засорять при многих ботах */ }

    @Override public void onPayload(byte[] d) {
        if (d.length < 2) return;
        try { handle(new Buf(d, (d[0] & 0xff) == 0x8e ? 1 : 0, d.length)); }
        catch (Exception ignored) {}
    }

    private void handle(Buf b) throws Exception {
        int gid = b.u8();
        if (gid == Proto.BATCH) {
            int n = b.i32();
            byte[] raw = Proto.inflate(b.take(Math.min(n, b.left())));
            Buf in = new Buf(raw);
            while (in.left() >= 4) {
                int len = in.i32();
                if (len <= 0 || len > in.left()) break;
                int start = in.pos;
                Buf one = new Buf(raw, start, start + len);
                if ((raw[start] & 0xff) == 0x8e && len > 1) one.pos++;
                try { handle(one); } catch (Exception ignored) {}
                in.pos = start + len;
            }
            return;
        }
        switch (gid) {
            case Proto.PLAY_STATUS: {
                int st = b.i32();
                if (st == 1 || st == 2) { status = "отказ (версия)"; stop(); }
                else if (st == 3) {
                    spawned = true; online = true; spawnedAt = now();
                    homeX = x; homeZ = z; spawnY = y;
                    status = registered ? "играет" : "регистрация";
                }
                break;
            }
            case Proto.DISCONNECT: {
                String m = b.str();
                status = "кикнут: " + m.replaceAll("§.", "");
                online = false;
                final RakClient r = rak;
                new Thread(() -> { if (r != null) r.close("kick"); }).start();
                break;
            }
            case Proto.START_GAME: {
                b.i32(); b.u8(); b.i32(); b.i32();
                eid = b.i64();
                b.i32(); b.i32(); b.i32();
                x = b.f32(); y = b.f32(); z = b.f32();
                homeX = x; homeZ = z; spawnY = y;
                send(Proto.requestChunkRadius(1)); // просим минимум, чтобы не грузить телефон
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32();
                if (e == eid && ok(px) && ok(py) && ok(pz)) { x = px; y = py - 1.62; z = pz; }
                break;
            }
            case Proto.SET_HEALTH: {
                health = b.i32();
                if (health <= 0 && deadSince == 0) deadSince = now();
                if (health > 0) deadSince = 0;
                break;
            }
            case Proto.RESPAWN: {
                float rx = b.f32(), ry = b.f32(), rz = b.f32();
                if (ok(rx) && ok(ry) && ok(rz)) { x = rx; y = ry; z = rz; }
                deadSince = 0; health = 20;
                break;
            }
            default: break;
        }
    }

    private static boolean ok(double v) { return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) < 3.0e7; }
    private static long now() { return System.currentTimeMillis(); }

    /** Шаг ИИ, вызывается менеджером ~20 раз в секунду. */
    public void aiTick() {
        RakClient r = rak;
        if (r == null || !r.isConnected() || !spawned) return;
        tick++;

        // авто-возрождение
        if (health <= 0) {
            if (deadSince > 0 && now() - deadSince > 1500) { send(Proto.action(eid, Proto.ACTION_RESPAWN, 0, 0, 0, -1)); deadSince = now(); }
            return;
        }

        // регистрация один раз
        if (!registered) {
            if (now() - spawnedAt > 1200) {
                String cmd = registerTemplate.replace("{pass}", password);
                send(Proto.text(name, cmd));
                registered = true; registeredAt = now(); status = "играет";
                ui.log(name + " → " + cmd);
            }
            // пока не зарегистрирован — стоим на месте
            sendMove();
            return;
        }
        if (now() - registeredAt < 1500) { sendMove(); return; } // дать серверу принять регистрацию

        // смена направления
        if (--headingLeft <= 0) { heading += (rnd.nextDouble() - 0.5) * Math.PI; headingLeft = 40 + rnd.nextInt(60); }
        // не уходим далеко от спавна
        double dx0 = x - homeX, dz0 = z - homeZ;
        if (dx0 * dx0 + dz0 * dz0 > radius * radius) heading = Math.atan2(homeZ - z, homeX - x) + (rnd.nextDouble() - 0.5) * 0.6;

        double mx = Math.cos(heading) * speed, mz = Math.sin(heading) * speed;
        x += mx; z += mz;
        yaw = (float) (Math.toDegrees(Math.atan2(-mx, mz)));

        // прыжки (имитация: небольшой скачок по Y + пакет прыжка)
        if (jumpBob > 0) { jumpBob--; }
        else if (tick % jumpEvery == 0) { jumpBob = 11; send(Proto.action(eid, Proto.ACTION_JUMP, 0, 0, 0, 0)); }
        double jy = jumpBob > 0 ? Math.sin((11 - jumpBob) / 11.0 * Math.PI) * 1.05 : 0;
        y = spawnY + jy;

        sendMove();
    }

    private void sendMove() {
        if (++sinceMove < 2) return; // ~10 пакетов/сек
        sinceMove = 0;
        boolean ground = jumpBob == 0;
        send(Proto.movePlayer(eid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, 0, 0, ground));
    }

    // ---------- генерация имени и пароля ----------
    private static final char[] VOW = "aeiou".toCharArray();
    private static final char[] CON = "bcdfghjklmnpqrstvwxyz".toCharArray();

    /** 8 символов: 4 буквы с чередованием гласная/согласная + 4 цифры. */
    public static String randomName(Random r) {
        StringBuilder sb = new StringBuilder(8);
        boolean vowelFirst = r.nextBoolean();
        for (int i = 0; i < 4; i++) {
            boolean vowel = (i % 2 == 0) == vowelFirst;
            char c = vowel ? VOW[r.nextInt(VOW.length)] : CON[r.nextInt(CON.length)];
            sb.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        for (int i = 0; i < 4; i++) sb.append((char) ('0' + r.nextInt(10)));
        return sb.toString();
    }

    public static String randomPassword(Random r) {
        String al = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append(al.charAt(r.nextInt(al.length())));
        return sb.toString();
    }
}
