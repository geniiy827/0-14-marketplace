package com.geniiy.minebot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Бот-игрок для MCPE 0.14.0/0.14.1. Подключается к миру по локальной сети,
 * ходит по миру с физикой, ищет путь и выполняет команды из чата.
 */
public final class Bot implements RakClient.Listener {
    public interface Ui {
        void status(String s);
        void log(String s);
    }

    // ---------- настройки ----------
    public String name = "MineBot";
    public String host = "127.0.0.1";
    public int port = 19132;
    public String owner = "";          // если не пусто — слушается только этого ника
    public boolean answerInChat = true;

    private final Ui ui;
    private RakClient rak;
    public final World world = new World();
    private final PathFinder pf = new PathFinder(world);
    private final Random rnd = new Random();
    private Thread ticker;
    private volatile boolean running;

    // ---------- состояние игрока ----------
    private long eid = -1;
    private boolean spawned, started;
    private double x, y, z, vy;
    private float yaw, pitch;
    private boolean onGround, sneaking;
    private int health = 20;
    private long deadSince;
    private int lastSentHash, sinceSent;

    // ---------- поведение ----------
    private enum Mode { IDLE, GOTO, FOLLOW, GUARD, ATTACK, DIG }
    private Mode mode = Mode.IDLE;
    private String followName;
    private long attackTarget = -1;
    private int[] goal;
    private List<int[]> path = new ArrayList<>();
    private int pathIdx, repathIn, stuckTicks, attackCd;
    private double lastProgX, lastProgZ;
    private boolean jumpReq;
    private int[] digPos; private int digTicks, digTotal;
    private int lookTicks; private World.Ent lookAt;
    private long tickNo;

    public Bot(Ui ui) { this.ui = ui; }

    // ======================= запуск/остановка =======================
    public synchronized void connect() throws Exception {
        disconnect();
        world.clear();
        spawned = started = false; eid = -1; mode = Mode.IDLE; path.clear();
        ui.status("Подключаюсь к " + host + ":" + port + "…");
        rak = new RakClient(host, port, this);
        rak.start();
        running = true;
        ticker = new Thread(this::tickLoop, "bot-tick");
        ticker.setDaemon(true);
        ticker.start();
    }

    public synchronized void disconnect() {
        running = false;
        if (rak != null) { rak.close("Отключено"); rak = null; }
    }

    private void tickLoop() {
        long next = System.currentTimeMillis();
        while (running) {
            next += 50;
            try { synchronized (this) { tick(); } } catch (Exception e) { ui.log("Ошибка: " + e); }
            long sl = next - System.currentTimeMillis();
            if (sl > 0) { try { Thread.sleep(sl); } catch (InterruptedException ie) { return; } }
            else if (sl < -1000) next = System.currentTimeMillis();
        }
    }

    // ======================= RakNet → игра =======================
    @Override public void onConnected() {
        ui.status("Соединение есть, вхожу в мир…");
        UUID uuid = UUID.nameUUIDFromBytes(("MineBot:" + name).getBytes());
        rak.send(Proto.login(name, rnd.nextLong(), uuid, host + ":" + port, Skin.make()));
    }

    @Override public void onClosed(String reason) {
        ui.status("Отключён: " + reason);
        ui.log("Отключён: " + reason);
        running = false;
    }

    @Override public void log(String msg) { ui.log(msg); }

    @Override public void onPayload(byte[] d) {
        if (d.length < 2 || (d[0] & 0xff) != 0x8e) return;
        try {
            handleGame(new Buf(d, 1, d.length));
        } catch (Exception e) {
            ui.log("Пакет 0x" + Integer.toHexString(d[1] & 0xff) + ": " + e);
        }
    }

    private void handleGame(Buf b) throws Exception {
        int id = b.u8();
        if (id == Proto.BATCH) {
            int n = b.i32();
            byte[] raw = Proto.inflate(b.take(n));
            Buf in = new Buf(raw);
            while (in.left() >= 4) {
                int len = in.i32();
                if (len <= 0 || len > in.left()) break;
                int start = in.pos;
                Buf one = new Buf(raw, start, start + len);
                if ((raw[start] & 0xff) == 0x8e && len > 1) one.pos++;
                try { handleGame(one); } catch (Exception e) { ui.log("В пачке: " + e); }
                in.pos = start + len;
            }
            return;
        }
        synchronized (this) { handle(id, b); }
    }

    private void handle(int id, Buf b) {
        switch (id) {
            case Proto.PLAY_STATUS: {
                int st = b.i32();
                if (st == 0) ui.log("Вход принят");
                else if (st == 1) { ui.status("Сервер отклонил вход: старая версия клиента"); disconnect(); }
                else if (st == 2) { ui.status("Сервер отклонил вход: другая версия игры"); disconnect(); }
                else if (st == 3) {
                    spawned = true;
                    ui.status("В мире! Позиция " + fmtPos());
                    ui.log("Заспавнился");
                    say("Привет! Я " + name + ". Напиши !помощь");
                }
                break;
            }
            case Proto.DISCONNECT: {
                String m = b.str();
                ui.status("Кикнут: " + m);
                disconnect();
                break;
            }
            case Proto.START_GAME: {
                b.i32(); b.u8(); b.i32(); b.i32();
                eid = b.i64();
                b.i32(); b.i32(); b.i32();
                x = b.f32(); y = b.f32(); z = b.f32();
                started = true;
                ui.log("StartGame: eid=" + eid + " pos " + fmtPos());
                rak.send(Proto.requestChunkRadius(5));
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float ppitch = b.f32();
                if (e == eid || e == 0) {
                    x = px; y = py - 1.62; z = pz; vy = 0;
                    ui.log("Сервер переместил бота: " + fmtPos());
                } else {
                    World.Ent en = world.ents.get(e);
                    if (en == null) { en = new World.Ent(e); en.type = 63; world.ents.put(e, en); }
                    en.x = px; en.y = py - 1.62f; en.z = pz; en.yaw = pyaw; en.pitch = ppitch; en.seen = System.currentTimeMillis();
                }
                break;
            }
            case Proto.ADD_PLAYER: {
                b.skip(16);
                String uname = b.str();
                long e = b.i64();
                World.Ent en = new World.Ent(e);
                en.type = 63; en.name = uname;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                world.ents.put(e, en);
                ui.log("Игрок рядом: " + uname);
                break;
            }
            case Proto.ADD_ENTITY: {
                long e = b.i64();
                World.Ent en = new World.Ent(e);
                en.type = b.i32() & 0xff;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                world.ents.put(e, en);
                break;
            }
            case Proto.REMOVE_PLAYER: case Proto.REMOVE_ENTITY: {
                world.ents.remove(b.i64());
                break;
            }
            case Proto.MOVE_ENTITY: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 32; i++) {
                    long e = b.i64();
                    float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float pp = b.f32();
                    World.Ent en = world.ents.get(e);
                    if (en == null) continue;
                    en.x = px; en.y = en.isPlayer() ? py - 1.62f : py; en.z = pz; en.yaw = pyaw; en.pitch = pp;
                    en.seen = System.currentTimeMillis();
                }
                break;
            }
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32();
                b.u8();
                int n = b.i32();
                world.putChunk(cx, cz, b.take(Math.min(n, b.left())));
                break;
            }
            case Proto.UPDATE_BLOCK: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 11; i++) {
                    int bx = b.i32(), bz = b.i32(), by = b.u8(), bid = b.u8(), fm = b.u8();
                    world.set(bx, by, bz, bid, fm & 15);
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
                onChat(type, src, msg, params);
                break;
            }
            case Proto.SET_HEALTH: {
                health = b.i32();
                if (health <= 0 && deadSince == 0) { deadSince = System.currentTimeMillis(); ui.log("Бот погиб"); }
                break;
            }
            case Proto.RESPAWN: {
                x = b.f32(); y = b.f32(); z = b.f32();
                if (y > 0) y -= 0; // позиция ног
                vy = 0; deadSince = 0; health = 20;
                ui.log("Возрождение: " + fmtPos());
                break;
            }
            case Proto.SET_SPAWN_POSITION:
            case Proto.SET_TIME:
            default:
                break;
        }
    }

    // ======================= чат и команды =======================
    private void onChat(int type, String src, String msg, List<String> params) {
        String sender = src, text = msg;
        if (type == Proto.TEXT_TRANSLATION && params.size() >= 2 && msg.contains("chat.type")) {
            sender = params.get(0); text = params.get(1);
        } else if (type != Proto.TEXT_CHAT) {
            String clean = msg.replaceAll("§.", "");
            if (clean.startsWith("<") && clean.indexOf('>') > 0) {
                sender = clean.substring(1, clean.indexOf('>'));
                text = clean.substring(clean.indexOf('>') + 1).trim();
            } else { ui.log("[чат] " + clean); return; }
        }
        sender = sender.replaceAll("§.", "").trim();
        text = text.replaceAll("§.", "").trim();
        ui.log("<" + sender + "> " + text);
        if (sender.equalsIgnoreCase(name)) return;
        if (!owner.isEmpty() && !sender.equalsIgnoreCase(owner)) return;
        String low = text.toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        if (low.startsWith("!")) low = low.substring(1).trim();
        else if (low.startsWith(n + " ") || low.startsWith(n + ",")) low = low.substring(n.length() + 1).trim();
        else return;
        command(sender, low);
    }

    private void command(String sender, String c) {
        String[] a = c.split("\\s+");
        String cmd = a[0];
        World.Ent who = world.findPlayer(sender);
        switch (cmd) {
            case "help": case "помощь": case "команды":
                say("Команды: !ко мне, !за мной [ник], !стоп, !иди x y z, !охрана, !атакуй, !копай, !прыг, !сядь, !смотри, !где, !скажи текст, !уйди");
                break;
            case "come": case "ко": case "сюда": case "комне":
                if (who == null) { say("Не вижу тебя, " + sender); break; }
                setGoal(feet(who)); mode = Mode.GOTO; say("Иду к тебе!");
                break;
            case "follow": case "за": case "следуй": case "иди_за": {
                String target = sender;
                if (a.length > 1 && !a[1].equals("мной")) target = a[1];
                World.Ent t = world.findPlayer(target);
                if (t == null) { say("Не вижу игрока " + target); break; }
                followName = t.name; mode = Mode.FOLLOW; repathIn = 0; say("Следую за " + t.name);
                break;
            }
            case "stop": case "стоп": case "стой":
                mode = Mode.IDLE; path.clear(); digPos = null; say("Стою.");
                break;
            case "goto": case "иди": {
                if (a.length >= 4) {
                    try {
                        int gx = (int) Math.floor(Double.parseDouble(a[1]));
                        int gy = (int) Math.floor(Double.parseDouble(a[2]));
                        int gz = (int) Math.floor(Double.parseDouble(a[3]));
                        setGoal(new int[]{gx, gy, gz}); mode = Mode.GOTO; say("Иду к " + gx + " " + gy + " " + gz);
                    } catch (NumberFormatException e) { say("Пример: !иди 10 64 -5"); }
                } else if (a.length >= 2 && (a[1].equals("за") || a[1].equals("ко"))) {
                    command(sender, a[1].equals("за") ? "за" : "ко");
                } else say("Пример: !иди 10 64 -5");
                break;
            }
            case "guard": case "охрана": case "охраняй":
                followName = sender; mode = Mode.GUARD; repathIn = 0; say("Охраняю тебя, " + sender + "!");
                break;
            case "attack": case "атакуй": case "атака": case "бей": {
                World.Ent m = nearestHostile(x, y, z, 24);
                if (m == null) { say("Мобов рядом нет."); break; }
                attackTarget = m.eid; mode = Mode.ATTACK; say("В атаку на " + mobName(m.type) + "!");
                break;
            }
            case "dig": case "копай": case "сломай": {
                int[] p;
                if (a.length >= 4) {
                    try { p = new int[]{Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])}; }
                    catch (NumberFormatException e) { say("Пример: !копай 10 64 -5"); break; }
                } else p = blockInFront();
                if (p == null || world.id(p[0], p[1], p[2]) <= 0) { say("Передо мной нечего копать."); break; }
                startDig(p);
                break;
            }
            case "jump": case "прыг": case "прыгай":
                jumpReq = true; break;
            case "sit": case "сядь": case "присядь":
                sneaking = !sneaking;
                rak.send(Proto.action(eid, sneaking ? Proto.ACTION_START_SNEAK : Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0));
                break;
            case "look": case "смотри": case "посмотри":
                if (who != null) { lookAt = who; lookTicks = 100; }
                break;
            case "where": case "где":
                say("Я тут: " + fmtPos());
                break;
            case "say": case "скажи": {
                int i = c.indexOf(' ');
                if (i > 0) chat(c.substring(i + 1));
                break;
            }
            case "bye": case "уйди": case "пока":
                say("Пока!");
                new Thread(() -> { try { Thread.sleep(500); } catch (InterruptedException ignored) {} disconnect(); }).start();
                break;
            default:
                say("Не знаю команду «" + cmd + "». Напиши !помощь");
        }
    }

    private void say(String s) { if (answerInChat) chat(s); ui.log("[бот] " + s); }

    public synchronized void chat(String s) {
        if (rak != null && spawned) rak.send(Proto.text(name, s));
    }

    // ======================= такт =======================
    private void tick() {
        if (rak == null || !rak.isConnected() || !started || eid < 0) return;
        tickNo++;
        if (health <= 0) {
            if (deadSince > 0 && System.currentTimeMillis() - deadSince > 1500) {
                rak.send(Proto.action(eid, Proto.ACTION_RESPAWN, 0, 0, 0, -1));
                deadSince = System.currentTimeMillis();
            }
            return;
        }
        if (!spawned) return;
        if (!world.loaded((int) Math.floor(x), (int) Math.floor(z))) { sendMove(); return; }

        double mx = 0, mz = 0;
        boolean wantJump = jumpReq; jumpReq = false;
        double speed = 0.2;

        switch (mode) {
            case FOLLOW: case GUARD: {
                World.Ent t = followName == null ? null : world.findPlayer(followName);
                if (t == null) break;
                if (mode == Mode.GUARD) {
                    World.Ent m = nearestHostile(t.x, t.y, t.z, 10);
                    if (m != null) { attackTarget = m.eid; doAttack(m); break; }
                }
                double d = Math.sqrt(t.dist2(x, y, z));
                if (d > 3.0) {
                    if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                        int[] g = feet(t);
                        path = pf.find(bx(), by(), bz(), g[0], g[1], g[2], 2.0, 3000);
                        pathIdx = 0; repathIn = 15;
                    }
                    speed = d > 8 ? 0.28 : 0.2;
                } else { path.clear(); lookAt = t; lookTicks = 2; }
                break;
            }
            case GOTO:
                if (goal != null && (path.isEmpty() || pathIdx >= path.size())) {
                    double gd = Math.sqrt(Math.pow(goal[0] + 0.5 - x, 2) + Math.pow(goal[2] + 0.5 - z, 2));
                    if (gd < 1.2 || --repathIn < -3) { mode = Mode.IDLE; say("Пришёл."); goal = null; }
                    else { path = pf.find(bx(), by(), bz(), goal[0], goal[1], goal[2], 0.9, 6000); pathIdx = 0; }
                }
                break;
            case ATTACK: {
                World.Ent m = world.ents.get(attackTarget);
                if (m == null) { mode = Mode.IDLE; say("Готово!"); break; }
                doAttack(m);
                break;
            }
            case DIG:
                digTick();
                break;
            default:
                break;
        }

        // следуем по пути
        if (!path.isEmpty() && pathIdx < path.size()) {
            int[] wp = path.get(pathIdx);
            double tx = wp[0] + 0.5, tz = wp[2] + 0.5;
            double dx = tx - x, dz = tz - z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            if (hd < 0.3 && Math.abs(wp[1] - y) < 1.2) {
                pathIdx++;
            } else {
                mx = dx / hd * Math.min(speed, hd); mz = dz / hd * Math.min(speed, hd);
                if (wp[1] > Math.floor(y + 0.01) && onGround && hd < 1.6) wantJump = true;
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = 0;
            }
            // застревание
            if (tickNo % 20 == 0) {
                double prog = Math.abs(x - lastProgX) + Math.abs(z - lastProgZ);
                if (prog < 0.3) { stuckTicks++; wantJump = true; } else stuckTicks = 0;
                if (stuckTicks >= 2) { path.clear(); repathIn = 0; stuckTicks = 0; }
                lastProgX = x; lastProgZ = z;
            }
        }

        if (lookAt != null && lookTicks > 0) {
            lookTicks--;
            double dx = lookAt.x - x, dy = (lookAt.y + 1.62) - (y + 1.62), dz = lookAt.z - z;
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }

        physics(mx, mz, wantJump);
        sendMove();
    }

    private void doAttack(World.Ent m) {
        double d = Math.sqrt(m.dist2(x, y, z));
        lookAt = m; lookTicks = 2;
        if (d > 2.6) {
            if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                int[] g = feet(m);
                path = pf.find(bx(), by(), bz(), g[0], g[1], g[2], 1.5, 2000);
                pathIdx = 0; repathIn = 10;
            }
        } else {
            path.clear();
            if (attackCd-- <= 0) {
                rak.send(Proto.animate(eid, 1));
                rak.send(Proto.interact(2, m.eid));
                attackCd = 10;
            }
        }
        if (d > 40) { world.ents.remove(m.eid); }
    }

    // ---------- копание ----------
    private void startDig(int[] p) {
        digPos = p; digTicks = 0;
        int id = world.id(p[0], p[1], p[2]);
        digTotal = breakTicks(id);
        if (digTotal < 0) { say("Этот блок не сломать."); digPos = null; return; }
        mode = Mode.DIG;
        double dx = p[0] + 0.5 - x, dz = p[2] + 0.5 - z;
        if (dx * dx + dz * dz > 16) { goal = p; path = pf.find(bx(), by(), bz(), p[0], p[1], p[2], 2.5, 3000); pathIdx = 0; }
        say("Копаю " + p[0] + " " + p[1] + " " + p[2]);
    }

    private void digTick() {
        if (digPos == null) { mode = Mode.IDLE; return; }
        double dx = digPos[0] + 0.5 - x, dy = digPos[1] + 0.5 - (y + 1.62), dz = digPos[2] + 0.5 - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > 4.5) {
            if (path.isEmpty() || pathIdx >= path.size()) {
                path = pf.find(bx(), by(), bz(), digPos[0], digPos[1], digPos[2], 2.5, 3000); pathIdx = 0;
                if (path.size() <= 1) { say("Не могу подойти к блоку."); digPos = null; mode = Mode.IDLE; }
            }
            return;
        }
        path.clear();
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        if (digTicks == 0) rak.send(Proto.action(eid, Proto.ACTION_START_BREAK, digPos[0], digPos[1], digPos[2], 1));
        if (digTicks % 5 == 0) rak.send(Proto.animate(eid, 1));
        digTicks++;
        if (digTicks >= digTotal) {
            rak.send(Proto.action(eid, Proto.ACTION_STOP_BREAK, digPos[0], digPos[1], digPos[2], 1));
            rak.send(Proto.removeBlock(eid, digPos[0], digPos[1], digPos[2]));
            world.set(digPos[0], digPos[1], digPos[2], 0, 0);
            say("Сломал!");
            digPos = null; mode = Mode.IDLE;
        }
    }

    /** Примерное время ломания рукой в тиках (-1 — нельзя). */
    static int breakTicks(int id) {
        switch (id) {
            case 7: case 95: case 120: return -1;
            case 2: case 3: case 12: case 13: case 60: case 88: case 198: return 16;
            case 1: case 4: case 24: case 45: case 48: case 98: case 139: case 179: return 150;
            case 14: case 15: case 16: case 21: case 56: case 73: case 74: case 129: return 300;
            case 49: return 5000;
            case 17: case 162: case 5: case 54: case 58: case 47: case 85: case 107: return 60;
            case 18: case 161: return 6;
            case 20: case 102: return 6;
            case 80: case 78: return 2;
            case 35: return 16;
            case 87: return 8;
            default: return 30;
        }
    }

    // ======================= физика =======================
    private static final double W = 0.3, H = 1.8;

    private boolean collides(double px, double py, double pz) {
        int x0 = (int) Math.floor(px - W + 1e-4), x1 = (int) Math.floor(px + W - 1e-4);
        int z0 = (int) Math.floor(pz - W + 1e-4), z1 = (int) Math.floor(pz + W - 1e-4);
        int y0 = (int) Math.floor(py) - 1, y1 = (int) Math.floor(py + H - 1e-4);
        for (int bx = x0; bx <= x1; bx++) for (int bz = z0; bz <= z1; bz++) for (int by = y0; by <= y1; by++) {
            double top = world.topHeight(bx, by, bz);
            if (top <= 0) continue;
            if (py < by + top - 1e-4 && py + H > by + 1e-4) return true;
        }
        return false;
    }

    private void physics(double mx, double mz, boolean jump) {
        boolean inWater = world.liquid((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z));
        if (inWater) {
            vy = Math.min(vy + 0.06, 0.12);
            mx *= 0.5; mz *= 0.5;
        } else if (jump && onGround) {
            vy = 0.42; onGround = false; rak.send(Proto.action(eid, Proto.ACTION_JUMP, 0, 0, 0, 0));
        }
        // горизонталь: по осям, со ступенькой на полблока
        if (mx != 0) {
            if (!collides(x + mx, y, z)) x += mx;
            else if (onGround && !collides(x + mx, y + 0.55, z) && stepUp(x + mx, z)) { x += mx; }
        }
        if (mz != 0) {
            if (!collides(x, y, z + mz)) z += mz;
            else if (onGround && !collides(x, y + 0.55, z + mz) && stepUp(x, z + mz)) { z += mz; }
        }
        // вертикаль
        double ny = y + vy;
        if (!collides(x, ny, z)) { y = ny; onGround = false; }
        else {
            if (vy < 0) {
                // встаём на поверхность
                double lo = ny, hi = y;
                for (int i = 0; i < 12; i++) { double mid = (lo + hi) / 2; if (collides(x, mid, z)) lo = mid; else hi = mid; }
                y = hi; onGround = true;
            }
            vy = 0;
        }
        if (!inWater) { vy -= 0.08; vy *= 0.98; if (vy < -3.9) vy = -3.9; }
        if (y < -10) { y = 80; vy = 0; }
    }

    private boolean stepUp(double px, double pz) {
        // поднимаемся на высоту препятствия (до 0.55)
        double lo = y, hi = y + 0.55;
        if (collides(px, hi, pz)) return false;
        for (int i = 0; i < 10; i++) { double mid = (lo + hi) / 2; if (collides(px, mid, pz)) lo = mid; else hi = mid; }
        y = hi;
        return true;
    }

    private void sendMove() {
        int hsh = (int) (x * 64) * 31 + (int) (y * 64) * 17 + (int) (z * 64) * 7 + (int) yaw * 3 + (int) pitch;
        sinceSent++;
        if (hsh == lastSentHash && sinceSent < 10) return;
        lastSentHash = hsh; sinceSent = 0;
        rak.send(Proto.movePlayer(eid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
    }

    // ======================= утилиты =======================
    private int bx() { return (int) Math.floor(x); }
    private int by() { return (int) Math.floor(y + 0.01); }
    private int bz() { return (int) Math.floor(z); }

    /** Блок, на котором стоят ноги сущности (ищем пол вниз). */
    private int[] feet(World.Ent e) {
        int fx = (int) Math.floor(e.x), fz = (int) Math.floor(e.z);
        int fy = (int) Math.floor(e.y + 0.2);
        for (int i = 0; i < 4; i++) {
            if (world.passable(fx, fy, fz) && world.solid(fx, fy - 1, fz)) break;
            if (!world.passable(fx, fy, fz)) fy++; else fy--;
        }
        return new int[]{fx, fy, fz};
    }

    private void setGoal(int[] g) { goal = g; path.clear(); pathIdx = 0; repathIn = 0; }

    private int[] blockInFront() {
        double r = Math.toRadians(yaw);
        double dx = -Math.sin(r), dz = Math.cos(r);
        for (double t = 0.5; t <= 3; t += 0.25) {
            for (double h : new double[]{1.0, 0.2}) {
                int px = (int) Math.floor(x + dx * t), py = (int) Math.floor(y + h), pz = (int) Math.floor(z + dz * t);
                int id = world.id(px, py, pz);
                if (id > 0 && id != 8 && id != 9 && id != 10 && id != 11) return new int[]{px, py, pz};
            }
        }
        return null;
    }

    private static boolean hostile(int type) { return type >= 32 && type <= 45; }

    private World.Ent nearestHostile(double px, double py, double pz, double r) {
        World.Ent best = null; double bd = r * r;
        for (World.Ent e : world.ents.values()) {
            if (!hostile(e.type)) continue;
            double d = e.dist2(px, py, pz);
            if (d < bd) { bd = d; best = e; }
        }
        return best;
    }

    static String mobName(int t) {
        switch (t) {
            case 32: return "зомби"; case 33: return "крипера"; case 34: return "скелета"; case 35: return "паука";
            case 36: return "свинозомби"; case 37: return "слизня"; case 38: return "эндермена"; case 39: return "чешуйницу";
            case 40: return "пещерного паука"; case 41: return "гаста"; case 42: return "магмового куба"; case 43: return "ифрита";
            case 44: return "зомби-жителя"; case 45: return "ведьму";
            default: return "моба";
        }
    }

    private String fmtPos() { return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z); }

    public synchronized String info() {
        return "Режим: " + mode + ", чанков: " + world.chunkCount() + ", сущностей: " + world.ents.size() + ", поз: " + fmtPos();
    }
}
