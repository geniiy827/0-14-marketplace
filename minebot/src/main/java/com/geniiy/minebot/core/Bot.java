package com.geniiy.minebot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Бот-игрок для MCPE 0.14.x. Подключается к миру по сети,
 * ходит с физикой, ищет путь и выполняет команды из чата или из приложения.
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
    public boolean debug = false;

    private final Ui ui;
    private volatile RakClient rak;
    public final World world = new World();
    private final PathFinder pf = new PathFinder(world);
    private final Random rnd = new Random();
    private volatile boolean running;

    // ---------- состояние игрока ----------
    private long eid = -1;
    private boolean spawned, started;
    private double x, y, z, vy, kx, kz; // kx/kz — отталкивание
    private float yaw, pitch;
    private boolean onGround, sneaking;
    private int health = 20;
    private long deadSince;
    private int lastSentHash, sinceSent;
    private final Map<Integer, Integer> pktCount = new HashMap<>();
    private int chunkOrder = -1;
    private String blocked = "";  // почему бот не двигается

    // ---------- поведение ----------
    public enum Mode { IDLE, GOTO, FOLLOW, GUARD, ATTACK, DIG, TUNNEL, WANDER, COLLECT, DANCE }
    private Mode mode = Mode.IDLE;
    private String followName;
    private double followDist = 2.5;
    private long attackTarget = -1;
    private int[] goal;
    private List<int[]> path = new ArrayList<>();
    private int pathIdx, repathIn, stuckTicks, attackCd, wanderIn, danceTicks;
    private double lastProgX, lastProgZ;
    private boolean jumpReq, blindMove;
    private int[] digPos; private int digTicks, digTotal;
    private final ArrayDeque<int[]> digQueue = new ArrayDeque<>();
    private int tunnelDx, tunnelDz, tunnelLeft;
    private int lookTicks; private World.Ent lookAt;
    private long tickNo;
    private final ArrayDeque<String> recentSaid = new ArrayDeque<>();

    public Bot(Ui ui) { this.ui = ui; }

    // ======================= запуск/остановка =======================
    public void connect() throws Exception {
        disconnect();
        synchronized (this) {
            world.clear();
            spawned = started = false; eid = -1; mode = Mode.IDLE; path.clear(); pktCount.clear();
        }
        ui.status("Подключаюсь к " + host + ":" + port + "…");
        rak = new RakClient(host, port, this);
        rak.start();
        running = true;
        Thread t = new Thread(this::tickLoop, "bot-tick");
        t.setDaemon(true);
        t.start();
    }

    /** Можно вызывать из любого потока (сеть уходит в фоновый поток). */
    public void disconnect() {
        running = false;
        final RakClient r = rak;
        rak = null;
        if (r != null) {
            Thread t = new Thread(() -> r.close("Отключено"), "rak-close");
            t.start();
            try { t.join(1000); } catch (InterruptedException ignored) {}
        }
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

    private void send(byte[] pk) { RakClient r = rak; if (r != null) r.send(pk); }

    // ======================= RakNet → игра =======================
    @Override public void onConnected() {
        ui.status("Соединение есть, вхожу в мир…");
        UUID uuid = UUID.nameUUIDFromBytes(("MineBot:" + name).getBytes());
        RakClient r = rak;
        int proto = r != null && r.gameProtocol != 0 ? r.gameProtocol : Proto.PROTOCOL;
        ui.log("Вход: протокол " + proto);
        send(Proto.login(proto, name, rnd.nextLong(), uuid, host + ":" + port, Skin.make()));
    }

    @Override public void onClosed(String reason) {
        ui.status("Отключён: " + reason);
        ui.log("Отключён: " + reason);
        running = false;
    }

    @Override public void log(String msg) { ui.log(msg); }

    @Override public void onPayload(byte[] d) {
        if (d.length < 2) return;
        try {
            if ((d[0] & 0xff) == 0x8e) handleGame(new Buf(d, 1, d.length));
            else handleGame(new Buf(d, 0, d.length)); // на случай сервера без префикса
        } catch (Exception e) {
            ui.log("Пакет 0x" + Integer.toHexString(d[1] & 0xff) + ": " + e);
        }
    }

    private void handleGame(Buf b) throws Exception {
        int id = b.u8();
        boolean first;
        synchronized (pktCount) { first = !pktCount.containsKey(id); pktCount.merge(id, 1, Integer::sum); }
        if (first && debug) ui.log("Первый пакет 0x" + Integer.toHexString(id) + " (" + b.left() + " байт)");
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
                else if (st == 1 || st == 2) {
                    String m = "Сервер отклонил вход: " + (st == 1 ? "у бота старая версия протокола" : "у бота новее версия протокола");
                    ui.log(m); ui.status(m); new Thread(this::disconnect).start();
                } else if (st == 3) {
                    spawned = true;
                    if (!started) { started = true; ui.log("StartGame не получен — продолжаю без него"); send(Proto.requestChunkRadius(5)); }
                    ui.status("В мире! Позиция " + fmtPos());
                    ui.log("Заспавнился");
                    say("Привет! Я " + name + ". Команды: напиши " + name + " помощь");
                }
                break;
            }
            case Proto.DISCONNECT: {
                String m = b.str();
                ui.log("Кикнут: " + m);
                ui.status("Кикнут: " + m);
                new Thread(this::disconnect).start();
                break;
            }
            case Proto.START_GAME: {
                if (debug) ui.log("StartGame: " + b.left() + " байт");
                b.i32(); b.u8(); b.i32(); b.i32();
                eid = b.i64();
                b.i32(); b.i32(); b.i32();
                x = b.f32(); y = b.f32(); z = b.f32();
                started = true;
                ui.log("StartGame: eid=" + eid + " поз " + fmtPos());
                send(Proto.requestChunkRadius(5));
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float ppitch = b.f32();
                if (e == eid) {
                    x = px; y = py - 1.62; z = pz; vy = 0;
                    if (debug) ui.log("Сервер переместил бота: " + fmtPos());
                } else {
                    World.Ent en = world.ents.get(e);
                    if (en == null) { en = new World.Ent(e); en.type = 63; en.name = world.nameOf(e); world.ents.put(e, en); }
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
                world.names.put(e, uname);
                ui.log("Игрок рядом: " + uname);
                break;
            }
            case Proto.PLAYER_LIST: {
                int type = b.u8();
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 16; i++) {
                    b.skip(16);
                    if (type == 0) {
                        long e = b.i64();
                        String nm = b.str();
                        b.str(); b.str();
                        world.names.put(e, nm);
                        World.Ent en = world.ents.get(e);
                        if (en != null && en.name.isEmpty()) en.name = nm;
                    }
                }
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
            case Proto.ADD_ITEM_ENTITY: {
                long e = b.i64();
                Proto.readSlot(b);
                World.Ent en = new World.Ent(e);
                en.type = 64;
                en.x = b.f32(); en.y = b.f32(); en.z = b.f32();
                world.ents.put(e, en);
                break;
            }
            case Proto.REMOVE_PLAYER: case Proto.REMOVE_ENTITY: case Proto.TAKE_ITEM_ENTITY: {
                long e = b.i64();
                if (id == Proto.TAKE_ITEM_ENTITY) e = b.i64(); // target, eid
                world.ents.remove(e);
                break;
            }
            case Proto.MOVE_ENTITY: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 32; i++) {
                    long e = b.i64();
                    float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float pp = b.f32();
                    if (e == eid) continue;
                    World.Ent en = world.ents.get(e);
                    if (en == null) continue;
                    en.x = px; en.y = en.isPlayer() ? py - 1.62f : py; en.z = pz; en.yaw = pyaw; en.pitch = pp;
                    en.seen = System.currentTimeMillis();
                }
                break;
            }
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32();
                int order = b.u8();
                int n = b.i32();
                byte[] data = b.take(Math.min(n, b.left()));
                if (chunkOrder != order) { chunkOrder = order; ui.log("Чанки: формат " + order + ", " + data.length + " байт"); }
                world.putChunk(cx, cz, data, order);
                break;
            }
            case Proto.SET_ENTITY_MOTION: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 20; i++) {
                    long e = b.i64();
                    float mx = b.f32(), my = b.f32(), mz = b.f32();
                    if (e == eid) { kx = mx; kz = mz; vy = my; onGround = false; }
                }
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
                if (health <= 0 && deadSince == 0) { deadSince = System.currentTimeMillis(); ui.log("Бот погиб (здоровье " + health + ")"); }
                if (health > 0) deadSince = 0;
                break;
            }
            case Proto.RESPAWN: {
                x = b.f32(); y = b.f32(); z = b.f32();
                vy = 0; deadSince = 0; health = 20;
                ui.log("Возрождение: " + fmtPos());
                break;
            }
            default:
                break;
        }
    }

    // ======================= чат и команды =======================
    private static final String[] KNOWN = {
            "help", "помощь", "команды", "come", "ко", "сюда", "follow", "за", "следуй", "stop", "стоп", "стой",
            "goto", "иди", "guard", "охрана", "охраняй", "attack", "атакуй", "атака", "бей", "dig", "копай", "сломай",
            "jump", "прыг", "прыгай", "sit", "сядь", "присядь", "look", "смотри", "where", "где", "say", "скажи",
            "bye", "уйди", "пока", "tp", "тп", "телепорт", "wander", "гуляй", "collect", "собирай", "подбери",
            "tunnel", "туннель", "dance", "танцуй", "info", "инфо", "статус", "ближе", "дальше", "вниз"};

    private static String clean(String s) { return s.replaceAll("§.", "").replace(' ', ' ').trim(); }

    private void onChat(int type, String src, String msg, List<String> params) {
        String sender = clean(src), text;
        if (type == Proto.TEXT_TRANSLATION && params.size() >= 2) { sender = clean(params.get(0)); text = clean(params.get(1)); }
        else text = clean(msg);
        if (sender.isEmpty() && text.startsWith("<") && text.indexOf('>') > 0) {
            sender = text.substring(1, text.indexOf('>')).trim();
            text = text.substring(text.indexOf('>') + 1).trim();
        }
        ui.log("[чат] " + (sender.isEmpty() ? "" : "<" + sender + "> ") + text);

        // Ищем команду: «!команда», «! команда» или «ИмяБота команда» в любом месте сообщения
        String lowAll = text.toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        int cmdStart = -1;
        for (int i = 0; i < lowAll.length(); i++) {
            char c = lowAll.charAt(i);
            if (c == '!' && (i == 0 || !Character.isLetterOrDigit(lowAll.charAt(i - 1)))) {
                String rest = lowAll.substring(i + 1).trim();
                if (startsWithKnown(rest)) { cmdStart = i + 1; break; }
            }
        }
        String prefix;
        if (cmdStart >= 0) {
            prefix = text.substring(0, cmdStart - 1);
        } else {
            int at = lowAll.lastIndexOf(n);
            if (at < 0) return;
            String rest = lowAll.substring(at + n.length()).replaceFirst("^[\\s,:!]+", "");
            if (!startsWithKnown(rest)) return;
            prefix = text.substring(0, at);
            cmdStart = lowAll.length() - rest.length();
            if (!sender.isEmpty() && sender.equalsIgnoreCase(name)) return;
            // если имя бота — это автор сообщения в префиксе («[Player] MineBot => ...»), не реагируем
            if (prefix.trim().isEmpty() && sender.isEmpty() && lowAll.substring(at + n.length()).matches("^\\s*(=>|>|:|»).*")) return;
        }
        // автор: явный источник, иначе — ник известного игрока в префиксе, иначе последнее слово перед разделителем
        if (sender.isEmpty()) sender = guessSender(prefix);
        if (sender.equalsIgnoreCase(name) || prefix.toLowerCase(Locale.ROOT).contains(n + " =>") || prefix.toLowerCase(Locale.ROOT).matches(".*\\b" + java.util.regex.Pattern.quote(n) + "\\s*[:>»].*")) return;
        for (String said : recentSaid) if (text.contains(said)) return; // наше же эхо
        if (!owner.isEmpty() && !sender.equalsIgnoreCase(owner) && !prefix.toLowerCase(Locale.ROOT).contains(owner.toLowerCase(Locale.ROOT))) return;
        command(sender, text.substring(cmdStart).trim().toLowerCase(Locale.ROOT));
    }

    private static boolean startsWithKnown(String rest) {
        String w = rest.split("\\s+", 2)[0];
        for (String k : KNOWN) if (w.equals(k)) return true;
        return false;
    }

    private String guessSender(String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        String best = "";
        for (String nm : world.names.values()) {
            if (nm.length() > best.length() && p.contains(nm.toLowerCase(Locale.ROOT)) && !nm.equalsIgnoreCase(name)) best = nm;
        }
        for (World.Ent e : world.ents.values()) {
            if (e.isPlayer() && !e.name.isEmpty() && e.name.length() > best.length() && p.contains(e.name.toLowerCase(Locale.ROOT))) best = e.name;
        }
        if (!best.isEmpty()) return best;
        String[] words = prefix.replaceAll("[\\[\\]()<>:=»|]", " ").trim().split("\\s+");
        return words.length > 0 ? words[words.length - 1] : "";
    }

    /** Команда из приложения (от имени владельца или ближайшего игрока). */
    public synchronized void appCommand(String c) {
        if (!spawned) { ui.log("Бот ещё не в мире"); return; }
        String who = owner;
        if (who.isEmpty()) { World.Ent p = world.nearestPlayer(x, y, z, eid); who = p != null ? p.name : ""; }
        command(who, c.toLowerCase(Locale.ROOT));
    }

    private void command(String sender, String c) {
        String[] a = c.split("\\s+");
        String cmd = a[0];
        World.Ent who = world.findPlayer(sender);
        if (who == null && !sender.isEmpty()) who = world.nearestPlayer(x, y, z, eid);
        ui.log("Команда от " + sender + ": " + c);
        switch (cmd) {
            case "help": case "помощь": case "команды":
                say("Движение: ко мне, за мной [ник], стоп, иди x y z, тп, гуляй, ближе/дальше");
                say("Дело: охрана, атакуй, копай [x y z], туннель N, вниз N, собирай");
                say("Прочее: прыг, сядь, смотри, танцуй, где, инфо, скажи текст, уйди. Пиши ! или имя бота перед командой");
                break;
            case "come": case "ко": case "сюда":
                if (who == null) { say("Не вижу тебя, " + sender); break; }
                setGoal(feet(who)); mode = Mode.GOTO; say("Иду к тебе!");
                break;
            case "follow": case "за": case "следуй": {
                String target = sender;
                if (a.length > 1 && !a[1].equals("мной") && !a[1].equals("me")) target = a[1];
                World.Ent t = world.findPlayer(target);
                if (t == null && target.equals(sender)) t = who;
                if (t == null) { say("Не вижу игрока " + target); break; }
                followName = t.name.isEmpty() ? null : t.name; followEid = t.eid;
                mode = Mode.FOLLOW; repathIn = 0; say("Следую за " + (t.name.isEmpty() ? "тобой" : t.name));
                break;
            }
            case "ближе": followDist = Math.max(1.5, followDist - 1); say("Держусь в " + fmt1(followDist) + " бл."); break;
            case "дальше": followDist = Math.min(8, followDist + 1); say("Держусь в " + fmt1(followDist) + " бл."); break;
            case "stop": case "стоп": case "стой":
                mode = Mode.IDLE; path.clear(); digPos = null; digQueue.clear(); say("Стою.");
                break;
            case "goto": case "иди": {
                if (a.length >= 4) {
                    try {
                        int gx = (int) Math.floor(Double.parseDouble(a[1]));
                        int gy = (int) Math.floor(Double.parseDouble(a[2]));
                        int gz = (int) Math.floor(Double.parseDouble(a[3]));
                        setGoal(new int[]{gx, gy, gz}); mode = Mode.GOTO; say("Иду к " + gx + " " + gy + " " + gz);
                    } catch (NumberFormatException e) { say("Пример: иди 10 64 -5"); }
                } else if (a.length >= 2 && (a[1].equals("за") || a[1].equals("ко") || a[1].equals("сюда"))) {
                    command(sender, a[1]);
                } else say("Пример: иди 10 64 -5");
                break;
            }
            case "tp": case "тп": case "телепорт":
                if (who == null) { say("Не вижу тебя"); break; }
                x = who.x + 1; y = who.y; z = who.z + 1; vy = 0; path.clear();
                sinceSent = 99; sendMove();
                say("Я рядом!");
                break;
            case "guard": case "охрана": case "охраняй":
                if (who == null) { say("Не вижу тебя"); break; }
                followName = who.name.isEmpty() ? null : who.name; followEid = who.eid;
                mode = Mode.GUARD; repathIn = 0; say("Охраняю тебя!");
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
                    catch (NumberFormatException e) { say("Пример: копай 10 64 -5"); break; }
                } else p = blockInFront();
                if (p == null || world.id(p[0], p[1], p[2]) == 0) { say("Передо мной нечего копать."); break; }
                digQueue.clear();
                startDig(p);
                break;
            }
            case "tunnel": case "туннель": {
                int len = a.length > 1 ? parseInt(a[1], 8) : 8;
                len = Math.max(1, Math.min(64, len));
                faceDirection(who);
                tunnelLeft = len; mode = Mode.TUNNEL; digQueue.clear(); digPos = null;
                say("Копаю туннель на " + len + " блоков!");
                break;
            }
            case "вниз": {
                int len = a.length > 1 ? parseInt(a[1], 5) : 5;
                len = Math.max(1, Math.min(30, len));
                digQueue.clear();
                int bx = bx(), by = by(), bz = bz();
                for (int i = 1; i <= len; i++) digQueue.add(new int[]{bx, by - i, bz});
                mode = Mode.DIG; digPos = null;
                say("Копаю вниз на " + len + "!");
                break;
            }
            case "wander": case "гуляй":
                mode = Mode.WANDER; wanderIn = 0; say("Пойду погуляю.");
                break;
            case "collect": case "собирай": case "подбери":
                mode = Mode.COLLECT; say("Собираю предметы вокруг!");
                break;
            case "dance": case "танцуй":
                mode = Mode.DANCE; danceTicks = 100; say("♪♫");
                break;
            case "jump": case "прыг": case "прыгай":
                jumpReq = true; break;
            case "sit": case "сядь": case "присядь":
                sneaking = !sneaking;
                send(Proto.action(eid, sneaking ? Proto.ACTION_START_SNEAK : Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0));
                break;
            case "look": case "смотри":
                if (who != null) { lookAt = who; lookTicks = 200; }
                break;
            case "where": case "где":
                say("Я тут: " + fmtPos());
                break;
            case "info": case "инфо": case "статус":
                say("Здоровье " + health + "/20, " + fmtPos() + ", режим " + modeName());
                break;
            case "say": case "скажи": {
                int i = c.indexOf(' ');
                if (i > 0) chat(c.substring(i + 1));
                break;
            }
            case "bye": case "уйди": case "пока":
                say("Пока!");
                new Thread(() -> { try { Thread.sleep(600); } catch (InterruptedException ignored) {} disconnect(); }).start();
                break;
            default:
                say("Не знаю «" + cmd + "». Напиши " + name + " помощь");
        }
    }

    private long followEid = -1;

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }

    private void say(String s) {
        if (answerInChat) chat(s);
        ui.log("[бот] " + s);
    }

    public void chat(String s) {
        if (rak != null && spawned) {
            synchronized (recentSaid) { recentSaid.add(s); if (recentSaid.size() > 8) recentSaid.poll(); }
            send(Proto.text(name, s));
        }
    }

    // ======================= такт =======================
    private void tick() {
        RakClient r = rak;
        if (r == null || !r.isConnected() || !spawned) return;
        tickNo++;
        if (health <= 0 && deadSince > 0) {
            long dt = System.currentTimeMillis() - deadSince;
            if (dt > 1500 && dt < 1600) send(Proto.action(eid, Proto.ACTION_RESPAWN, 0, 0, 0, -1));
            if (dt > 6000) { health = 20; deadSince = 0; } // сервер не подтвердил смерть — считаем живым
        }

        if (world.chunkCount() == 0 && tickNo % 100 == 0) send(Proto.requestChunkRadius(5));
        boolean known = world.loaded(bx(), bz());
        double mx = 0, mz = 0;
        boolean wantJump = jumpReq; jumpReq = false;
        double speed = 0.2;
        blindMove = false;
        double[] direct = null; // прямое движение к точке (если нет карты)

        switch (mode) {
            case FOLLOW: case GUARD: {
                World.Ent t = followTarget();
                if (t == null) { blocked = "не вижу, за кем идти"; break; }
                if (mode == Mode.GUARD) {
                    World.Ent m = nearestHostile(t.x, t.y, t.z, 10);
                    if (m != null) { attackTarget = m.eid; direct = doAttack(m); break; }
                }
                double d = Math.sqrt(t.dist2(x, y, z));
                if (d > 48) { x = t.x + 1; y = t.y; z = t.z + 1; vy = 0; path.clear(); sinceSent = 99; break; } // слишком отстал — догоняем рывком
                if (d > followDist + 0.5) {
                    direct = new double[]{t.x, t.y, t.z};
                    if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                        int[] g = feet(t);
                        path = pf.find(bx(), by(), bz(), g[0], g[1], g[2], Math.max(1.2, followDist - 0.5), 3000);
                        pathIdx = 0; repathIn = 15;
                    }
                    speed = d > 8 ? 0.28 : 0.215;
                } else { path.clear(); lookAt = t; lookTicks = 2; }
                break;
            }
            case GOTO:
                if (goal != null) {
                    double gd = Math.sqrt(Math.pow(goal[0] + 0.5 - x, 2) + Math.pow(goal[2] + 0.5 - z, 2));
                    if (gd < 1.3) { mode = Mode.IDLE; say("Пришёл."); goal = null; path.clear(); break; }
                    direct = new double[]{goal[0] + 0.5, goal[1], goal[2] + 0.5};
                    if (path.isEmpty() || pathIdx >= path.size()) {
                        if (--repathIn < -6 && path.size() <= 1 && known) { mode = Mode.IDLE; say("Не могу пройти туда."); goal = null; break; }
                        path = pf.find(bx(), by(), bz(), goal[0], goal[1], goal[2], 0.9, 6000); pathIdx = 0;
                    }
                }
                break;
            case ATTACK: {
                World.Ent m = world.ents.get(attackTarget);
                if (m == null) { mode = Mode.IDLE; say("Готово!"); break; }
                direct = doAttack(m);
                break;
            }
            case DIG:
                digTick();
                break;
            case TUNNEL:
                tunnelTick();
                break;
            case WANDER:
                if (--wanderIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                    wanderIn = 100 + rnd.nextInt(100);
                    int tx = bx() + rnd.nextInt(17) - 8, tz = bz() + rnd.nextInt(17) - 8;
                    path = pf.find(bx(), by(), bz(), tx, by(), tz, 1.5, 1500); pathIdx = 0;
                }
                speed = 0.12;
                break;
            case COLLECT: {
                World.Ent it = nearestOfType(64, 16);
                if (it == null) { if (tickNo % 100 == 0) { mode = Mode.IDLE; say("Всё собрал."); } break; }
                direct = new double[]{it.x, it.y, it.z};
                if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                    path = pf.find(bx(), by(), bz(), (int) Math.floor(it.x), (int) Math.floor(it.y), (int) Math.floor(it.z), 0.5, 2000);
                    pathIdx = 0; repathIn = 20;
                }
                break;
            }
            case DANCE:
                danceTicks--;
                yaw += 18;
                if (danceTicks % 10 == 0) wantJump = true;
                if (danceTicks % 20 == 0) {
                    sneaking = !sneaking;
                    send(Proto.action(eid, sneaking ? Proto.ACTION_START_SNEAK : Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0));
                }
                if (danceTicks % 6 == 0) send(Proto.animate(eid, 1));
                if (danceTicks <= 0) { mode = Mode.IDLE; if (sneaking) { sneaking = false; send(Proto.action(eid, Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); } }
                break;
            default:
                break;
        }

        // следуем по пути (если карта есть и путь нашёлся)
        boolean usePath = known && path.size() > 1 && pathIdx < path.size();
        if (usePath) {
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
            if (tickNo % 20 == 0) {
                double prog = Math.abs(x - lastProgX) + Math.abs(z - lastProgZ);
                if (prog < 0.3) { stuckTicks++; wantJump = true; } else stuckTicks = 0;
                if (stuckTicks >= 2) { path.clear(); repathIn = 0; stuckTicks = 0; }
                lastProgX = x; lastProgZ = z;
            }
            blocked = "";
        } else if (direct != null) {
            // Запасной режим: карта неизвестна или путь не найден — идём напрямую
            double dx = direct[0] - x, dz = direct[2] - z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            if (hd > 0.2) {
                mx = dx / hd * Math.min(speed, hd); mz = dz / hd * Math.min(speed, hd);
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            if (!known) {
                blindMove = true;
                // без карты держим высоту цели плавно
                y += Math.max(-0.4, Math.min(0.4, direct[1] - y));
                blocked = "нет данных о блоках здесь — иду напрямую";
            } else {
                blocked = "путь не найден — иду напрямую";
                if (tickNo % 10 == 0) wantJump = true;
            }
        }

        if (lookAt != null && lookTicks > 0) {
            lookTicks--;
            double dx = lookAt.x - x, dy = lookAt.y - y, dz = lookAt.z - z;
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }

        if (blindMove || !known) { x += mx; z += mz; onGround = true; }
        else physics(mx, mz, wantJump);
        sendMove();
    }

    private World.Ent followTarget() {
        World.Ent t = followEid >= 0 ? world.ents.get(followEid) : null;
        if (t == null && followName != null) t = world.findPlayer(followName);
        return t;
    }

    /** Подходит и бьёт моба. Возвращает точку для прямого движения. */
    private double[] doAttack(World.Ent m) {
        double d = Math.sqrt(m.dist2(x, y, z));
        lookAt = m; lookTicks = 2;
        if (d > 2.6) {
            if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                int[] g = feet(m);
                path = pf.find(bx(), by(), bz(), g[0], g[1], g[2], 1.5, 2000);
                pathIdx = 0; repathIn = 10;
            }
            return new double[]{m.x, m.y, m.z};
        }
        path.clear();
        if (attackCd-- <= 0) {
            send(Proto.animate(eid, 1));
            send(Proto.interact(2, m.eid));
            attackCd = 10;
        }
        if (d > 40) world.ents.remove(m.eid);
        return null;
    }

    // ---------- копание ----------
    private void startDig(int[] p) {
        digPos = p; digTicks = 0;
        int id = world.id(p[0], p[1], p[2]);
        digTotal = breakTicks(id);
        if (digTotal < 0) { say("Этот блок не сломать."); digPos = null; return; }
        mode = mode == Mode.TUNNEL ? Mode.TUNNEL : Mode.DIG;
        double dx = p[0] + 0.5 - x, dz = p[2] + 0.5 - z;
        if (dx * dx + dz * dz > 16) { path = pf.find(bx(), by(), bz(), p[0], p[1], p[2], 2.5, 3000); pathIdx = 0; }
        if (mode == Mode.DIG && digQueue.isEmpty()) say("Копаю " + p[0] + " " + p[1] + " " + p[2]);
    }

    /** true — блок сломан или копать нечего. */
    private boolean digStep() {
        if (digPos == null) return true;
        double dx = digPos[0] + 0.5 - x, dy = digPos[1] + 0.5 - (y + 1.62), dz = digPos[2] + 0.5 - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > 5) {
            if (path.isEmpty() || pathIdx >= path.size()) {
                path = pf.find(bx(), by(), bz(), digPos[0], digPos[1], digPos[2], 2.5, 3000); pathIdx = 0;
                if (path.size() <= 1) { say("Не могу подойти к блоку."); digPos = null; return true; }
            }
            return false;
        }
        path.clear();
        yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        if (digTicks == 0) send(Proto.action(eid, Proto.ACTION_START_BREAK, digPos[0], digPos[1], digPos[2], 1));
        if (digTicks % 5 == 0) send(Proto.animate(eid, 1));
        digTicks++;
        if (digTicks >= digTotal) {
            send(Proto.action(eid, Proto.ACTION_STOP_BREAK, digPos[0], digPos[1], digPos[2], 1));
            send(Proto.removeBlock(eid, digPos[0], digPos[1], digPos[2]));
            world.set(digPos[0], digPos[1], digPos[2], 0, 0);
            digPos = null;
            return true;
        }
        return false;
    }

    private void digTick() {
        if (digPos == null) {
            int[] nx = null;
            while (!digQueue.isEmpty()) {
                int[] q = digQueue.poll();
                int id = world.id(q[0], q[1], q[2]);
                if (id != 0 && breakTicks(id) >= 0) { nx = q; break; }
            }
            if (nx == null) { mode = Mode.IDLE; say("Готово!"); return; }
            startDig(nx);
            if (digPos == null) return;
        }
        digStep();
    }

    private void tunnelTick() {
        if (digPos != null) { digStep(); return; }
        int fx = bx() + tunnelDx, fz = bz() + tunnelDz, fy = by();
        int lower = world.id(fx, fy, fz), upper = world.id(fx, fy + 1, fz);
        if (upper > 0 && !world.liquid(fx, fy + 1, fz) && breakTicks(upper) >= 0) { startDig(new int[]{fx, fy + 1, fz}); return; }
        if (lower > 0 && !world.liquid(fx, fy, fz) && breakTicks(lower) >= 0) { startDig(new int[]{fx, fy, fz}); return; }
        if (world.liquid(fx, fy, fz) || world.liquid(fx, fy + 1, fz) || world.dangerous(fx, fy, fz)) { mode = Mode.IDLE; say("Впереди вода или лава, остановился."); return; }
        if (upper > 0 || lower > 0) { mode = Mode.IDLE; say("Впереди неразрушимый блок."); return; }
        // шагаем вперёд на один блок
        if (path.isEmpty() || pathIdx >= path.size()) {
            if (tunnelLeft-- <= 0) { mode = Mode.IDLE; say("Туннель готов!"); return; }
            path = new ArrayList<>();
            path.add(new int[]{bx(), by(), bz()});
            path.add(new int[]{fx, fy, fz});
            pathIdx = 1;
        }
    }

    private void faceDirection(World.Ent who) {
        double r = Math.toRadians(who != null ? who.yaw : yaw);
        double dx = -Math.sin(r), dz = Math.cos(r);
        if (Math.abs(dx) > Math.abs(dz)) { tunnelDx = dx > 0 ? 1 : -1; tunnelDz = 0; }
        else { tunnelDx = 0; tunnelDz = dz > 0 ? 1 : -1; }
        yaw = (float) Math.toDegrees(Math.atan2(-tunnelDx, tunnelDz));
    }

    /** Примерное время ломания рукой в тиках (-1 — нельзя). */
    static int breakTicks(int id) {
        switch (id) {
            case 7: case 95: case 120: case 8: case 9: case 10: case 11: return -1;
            case 2: case 3: case 12: case 13: case 60: case 88: case 198: case 82: return 16;
            case 1: case 4: case 24: case 45: case 48: case 98: case 139: case 179: case 67: case 109: case 44: return 150;
            case 14: case 15: case 16: case 21: case 56: case 73: case 74: case 129: case 153: return 300;
            case 49: return 5000;
            case 17: case 162: case 5: case 54: case 58: case 47: case 85: case 107: case 53: case 134: case 135: case 136: case 158: return 60;
            case 18: case 161: case 20: case 102: return 6;
            case 80: case 78: case 50: case 31: case 37: case 38: case 6: case 175: return 2;
            case 35: case 171: return 16;
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
        // если бот застрял внутри блока (например, после рывка) — выталкиваем вверх
        if (collides(x, y, z)) {
            for (int i = 1; i <= 4; i++) if (!collides(x, y + i, z)) { y = Math.floor(y) + i; vy = 0; break; }
        }
        // отталкивание: удар/взрыв и толкание игроками
        for (World.Ent e : world.ents.values()) {
            if (!e.isPlayer() && !(e.type >= 10 && e.type <= 45)) continue;
            double dx = x - e.x, dz = z - e.z, d2 = dx * dx + dz * dz;
            if (d2 < 0.36 && d2 > 1e-6 && Math.abs(e.y - y) < 1.8) { double d = Math.sqrt(d2); kx += dx / d * 0.05; kz += dz / d * 0.05; }
        }
        mx += kx; mz += kz;
        double fr = onGround ? 0.546 : 0.91;
        kx *= fr; kz *= fr;
        if (Math.abs(kx) < 0.003) kx = 0;
        if (Math.abs(kz) < 0.003) kz = 0;
        boolean inWater = world.liquid((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z));
        if (inWater) {
            vy = Math.min(vy + 0.06, 0.12);
            mx *= 0.5; mz *= 0.5;
        } else if (jump && onGround) {
            vy = 0.42; onGround = false; send(Proto.action(eid, Proto.ACTION_JUMP, 0, 0, 0, 0));
        }
        if (mx != 0) {
            if (!collides(x + mx, y, z)) x += mx;
            else if (onGround && !collides(x + mx, y + 0.55, z) && stepUp(x + mx, z)) x += mx;
        }
        if (mz != 0) {
            if (!collides(x, y, z + mz)) z += mz;
            else if (onGround && !collides(x, y + 0.55, z + mz) && stepUp(x, z + mz)) z += mz;
        }
        double ny = y + vy;
        if (!collides(x, ny, z)) { y = ny; onGround = false; }
        else {
            if (vy < 0) {
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
        send(Proto.movePlayer(eid, (float) x, (float) (y + 1.62), (float) z, yaw, yaw, pitch, 0, onGround));
    }

    // ======================= утилиты =======================
    private int bx() { return (int) Math.floor(x); }
    private int by() { return (int) Math.floor(y + 0.01); }
    private int bz() { return (int) Math.floor(z); }

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
                if (id > 0 && !world.liquid(px, py, pz)) return new int[]{px, py, pz};
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

    private World.Ent nearestOfType(int type, double r) {
        World.Ent best = null; double bd = r * r;
        for (World.Ent e : world.ents.values()) {
            if (e.type != type) continue;
            double d = e.dist2(x, y, z);
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

    private String modeName() {
        switch (mode) {
            case IDLE: return "отдых"; case GOTO: return "иду к точке"; case FOLLOW: return "следую";
            case GUARD: return "охрана"; case ATTACK: return "атака"; case DIG: return "копаю"; case TUNNEL: return "туннель";
            case WANDER: return "гуляю"; case COLLECT: return "собираю"; case DANCE: return "танцую";
        }
        return mode.name();
    }

    private static String fmt1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private String fmtPos() { return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z); }

    /** Строка состояния для приложения. */
    public synchronized String info() {
        if (rak == null || !spawned) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Режим: ").append(modeName()).append("  ♥").append(health).append("  id ").append(eid)
                .append("\nПозиция: ").append(fmtPos())
                .append("\nЧанков: ").append(world.chunkCount()).append(world.loaded(bx(), bz()) ? " (под ботом есть)" : " (под ботом НЕТ)")
                .append(", игроков: ").append(countPlayers()).append(", сущностей: ").append(world.ents.size());
        if (!blocked.isEmpty() && mode != Mode.IDLE) sb.append("\n⚠ ").append(blocked);
        if (debug) {
            sb.append("\nПакеты: ");
            synchronized (pktCount) { for (Map.Entry<Integer, Integer> e : pktCount.entrySet()) sb.append(Integer.toHexString(e.getKey())).append('×').append(e.getValue()).append(' '); }
        }
        return sb.toString();
    }

    private int countPlayers() { int n = 0; for (World.Ent e : world.ents.values()) if (e.isPlayer()) n++; return n; }

    public boolean isOnline() { return rak != null && spawned; }
}
