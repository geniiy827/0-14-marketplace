package com.geniiy.minebot.core;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Бот-игрок для MCPE 0.14.x: сеть, физика, поиск пути с паркуром, охрана,
 * обучаемый ПвП, инвентарь, стройка и команды из чата или приложения.
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
    public String owner = "";
    public boolean answerInChat = true;
    public boolean debug = false;
    public boolean allowTp = true, allowBuild = true, allowPvp = true, allowFun = true;
    public String loginCommand = "";
    public boolean autoReconnect = false;

    private final Ui ui;
    private volatile RakClient rak;
    public final World world = new World();
    private final PathFinder pf = new PathFinder(world);
    public final Combat combat = new Combat();
    private final Fun fun = new Fun();
    private final Random rnd = new Random();
    private volatile boolean running, userStopped;

    // ---------- состояние ----------
    private long eid = -1;
    private int gamemode;
    private boolean spawned, started;
    private double x, y, z, vy, kx, kz;
    private double goodX, goodY, goodZ, airMx, airMz;
    private boolean gapFlight, edgeAllowed;
    private float yaw, pitch;
    private boolean onGround, sneaking, sprinting;
    private int health = 20;
    private long deadSince;
    private int lastSentHash, sinceSent;
    private final Map<Integer, Integer> pktCount = new HashMap<>();
    private int chunkOrder = -1;
    private String blocked = "";
    private long selfHurtTick = -100, ownerHurtTick = -100;
    private final Map<Long, Long> lastSwing = new HashMap<>();
    private int[] home;

    // ---------- инвентарь ----------
    private final int[][] inv = new int[36][3];
    private final int[] hotbar = {0, 1, 2, 3, 4, 5, 6, 7, 8};
    private int heldSlot = 0;   // индекс в хотбаре
    private int pendingDrop = -1;

    // ---------- поведение ----------
    public enum Mode { IDLE, GOTO, FOLLOW, GUARD, ATTACK, PVP, DIG, TUNNEL, WANDER, COLLECT, DANCE, BUILD, GIVE, EAT }
    private Mode mode = Mode.IDLE;
    private String followName; private long followEid = -1;
    private double followDist = 2.5;
    private long attackTarget = -1;
    private int[] goal;
    private List<int[]> path = new ArrayList<>();
    private int pathIdx, repathIn, stuckTicks, wanderIn, danceTicks, noProgress;
    private double lastProgX, lastProgZ;
    private boolean jumpReq;
    private int[] digPos; private int digTicks, digTotal;
    private final ArrayDeque<int[]> digQueue = new ArrayDeque<>();
    private int tunnelDx, tunnelDz, tunnelLeft;
    private int buildKind, buildLeft, buildDx, buildDz, buildStep; private double buildBaseY; // 1 столб, 2 мост
    private int giveItem = -1, giveTicks; private String giveTo;
    private int eatTicks;
    private int lookTicks; private World.Ent lookAt;
    private long tickNo;
    private final ArrayDeque<String> recentSaid = new ArrayDeque<>();
    private boolean lastStuckSaid;

    public Bot(Ui ui) { this.ui = ui; }

    // ======================= запуск/остановка =======================
    public void connect() throws Exception {
        userStopped = false;
        RakClient old = rak; rak = null; running = false;
        if (old != null) old.close("Переподключение");
        synchronized (this) {
            world.clear();
            spawned = started = false; eid = -1; mode = Mode.IDLE; path.clear(); pktCount.clear();
            for (int[] s : inv) { s[0] = 0; s[1] = 0; s[2] = 0; }
        }
        ui.status("Подключаюсь к " + host + ":" + port + "…");
        rak = new RakClient(host, port, this);
        rak.start();
        running = true;
        Thread t = new Thread(this::tickLoop, "bot-tick");
        t.setDaemon(true);
        t.start();
    }

    public void disconnect() {
        userStopped = true;
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
        running = false;
        if (autoReconnect && !userStopped) {
            ui.status("Связь потеряна (" + reason + "). Переподключаюсь через 5 с…");
            ui.log("Переподключение: " + reason);
            new Thread(() -> {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                if (!userStopped) { try { connect(); } catch (Exception e) { ui.status("Отключён: " + e.getMessage()); } }
            }).start();
            return;
        }
        ui.status("Отключён: " + reason);
        ui.log("Отключён: " + reason);
    }

    @Override public void log(String msg) { ui.log(msg); }

    @Override public void onPayload(byte[] d) {
        if (d.length < 2) return;
        try {
            if ((d[0] & 0xff) == 0x8e) handleGame(new Buf(d, 1, d.length));
            else handleGame(new Buf(d, 0, d.length));
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
                try { handleGame(one); } catch (Exception e) { if (debug) ui.log("В пачке: " + e); }
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
                    ui.log(m); ui.status(m); userStopped = true; new Thread(this::disconnect).start();
                } else if (st == 3) {
                    spawned = true;
                    if (!started) { started = true; ui.log("StartGame не получен — продолжаю без него"); send(Proto.requestChunkRadius(5)); }
                    goodX = x; goodY = y; goodZ = z;
                    ui.status("В мире! Позиция " + fmtPos());
                    ui.log("Заспавнился");
                    if (!loginCommand.trim().isEmpty()) {
                        final String lc = loginCommand.trim();
                        new Thread(() -> { try { Thread.sleep(1500); } catch (InterruptedException ignored) {} send(Proto.text(name, lc)); ui.log("Отправлено: " + lc); }).start();
                    }
                    say("Привет! Я " + name + ". Команды: " + name + " хелп");
                }
                break;
            }
            case Proto.DISCONNECT: {
                String m = clean(b.str());
                ui.log("Кикнут: " + m);
                if (!autoReconnect) { ui.status("Кикнут: " + m); userStopped = true; }
                new Thread(() -> { RakClient r = rak; if (r != null) r.close("Кикнут: " + m); }).start();
                break;
            }
            case Proto.START_GAME: {
                if (debug) ui.log("StartGame: " + b.left() + " байт");
                b.i32(); b.u8(); b.i32();
                gamemode = b.i32();
                eid = b.i64();
                b.i32(); b.i32(); b.i32();
                x = b.f32(); y = b.f32(); z = b.f32();
                started = true;
                ui.log("StartGame: id=" + eid + " режим " + (gamemode == 1 ? "творческий" : "выживание") + " поз " + fmtPos());
                send(Proto.requestChunkRadius(5));
                break;
            }
            case Proto.MOVE_PLAYER: {
                long e = b.i64();
                float px = b.f32(), py = b.f32(), pz = b.f32(), pyaw = b.f32(); b.f32(); float ppitch = b.f32();
                if (e == eid) {
                    if (ok(px) && ok(py) && ok(pz)) { x = px; y = py - 1.62; z = pz; vy = 0; kx = kz = 0; }
                    if (debug) ui.log("Сервер переместил бота: " + fmtPos());
                } else {
                    World.Ent en = world.ents.get(e);
                    if (en == null) { en = new World.Ent(e); en.type = 63; en.name = world.nameOf(e); world.ents.put(e, en); }
                    en.moveTo(px, py - 1.62f, pz); en.yaw = pyaw; en.pitch = ppitch;
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
                if (id == Proto.TAKE_ITEM_ENTITY) e = b.i64();
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
                    en.moveTo(px, en.isPlayer() ? py - 1.62f : py, pz); en.yaw = pyaw; en.pitch = pp;
                }
                break;
            }
            case Proto.SET_ENTITY_MOTION: {
                int n = b.i32();
                for (int i = 0; i < n && b.left() >= 20; i++) {
                    long e = b.i64();
                    float mx = b.f32(), my = b.f32(), mz = b.f32();
                    if (e != eid || !ok(mx) || !ok(my) || !ok(mz)) continue;
                    // принимаем только правдоподобное отталкивание
                    if (Math.abs(mx) > 2 || Math.abs(my) > 2 || Math.abs(mz) > 2) continue;
                    kx = clamp(mx, 0.8); kz = clamp(mz, 0.8);
                    if (tickNo - selfHurtTick < 10 && my > 0) { vy = Math.min(my, 0.45); onGround = false; }
                }
                break;
            }
            case Proto.ENTITY_EVENT: {
                long e = b.i64();
                int ev = b.u8();
                if (ev == 2) {
                    if (e == eid) { selfHurtTick = tickNo; }
                    if (e == combat.foeEid) combat.onFoeHurt();
                    if (e == followEid) ownerHurtTick = tickNo;
                } else if (ev == 3) {
                    if (e == combat.foeEid && mode == Mode.PVP) { combat.endFight(true); say("GG! Победа. " + combat.fightScore()); mode = Mode.IDLE; }
                    if (e == eid) health = 0;
                }
                break;
            }
            case Proto.ANIMATE: {
                int action = b.u8();
                long e = b.i64();
                if (action == 1 && e != eid) lastSwing.put(e, tickNo);
                break;
            }
            case Proto.FULL_CHUNK_DATA: {
                int cx = b.i32(), cz = b.i32();
                int order = b.u8();
                int n = b.i32();
                byte[] data = b.take(Math.min(n, b.left()));
                if (chunkOrder != order) { chunkOrder = order; if (debug) ui.log("Чанки: формат " + order + ", " + data.length + " байт"); }
                world.putChunk(cx, cz, data, order);
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
                    if (debug) ui.log("Инвентарь обновлён: " + countItems() + " стаков");
                }
                break;
            }
            case Proto.CONTAINER_SET_SLOT: {
                int win = b.u8();
                int slot = b.s16();
                b.s16();
                int[] it = Proto.readSlot(b);
                if (win == 0 && slot >= 0 && slot < 36) { inv[slot][0] = it[0]; inv[slot][1] = it[1]; inv[slot][2] = it[2]; }
                break;
            }
            case Proto.MOB_EQUIPMENT: {
                long e = b.i64();
                Proto.readSlot(b);
                int slot = b.u8(), sel = b.u8();
                if (e == eid && sel < 9) { heldSlot = sel; if (slot >= 9 && slot - 9 < 36) hotbar[sel] = slot - 9; }
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
                int h = b.i32();
                if (h < health && h > 0) { selfHurtTick = tickNo; combat.onSelfHurt(health - h); }
                health = h;
                if (health <= 0 && deadSince == 0) {
                    deadSince = System.currentTimeMillis(); ui.log("Бот погиб");
                    if (mode == Mode.PVP) { combat.endFight(false); say("Ты победил... Реванш? " + combat.fightScore()); mode = Mode.IDLE; }
                }
                if (health > 0) deadSince = 0;
                break;
            }
            case Proto.RESPAWN: {
                float rx = b.f32(), ry = b.f32(), rz = b.f32();
                if (ok(rx) && ok(ry) && ok(rz)) { x = rx; y = ry; z = rz; }
                vy = 0; kx = kz = 0; deadSince = 0; health = 20;
                ui.log("Возрождение: " + fmtPos());
                break;
            }
            default:
                break;
        }
    }

    private static boolean ok(double v) { return !Double.isNaN(v) && !Double.isInfinite(v) && Math.abs(v) < 3.0e7; }
    private static double clamp(double v, double m) { return Math.max(-m, Math.min(m, v)); }

    // ======================= чат и команды =======================
    private static final String[] KNOWN = {
            "help", "хелп", "помощь", "команды", "come", "ко", "сюда", "follow", "за", "следуй", "иди_за", "stop", "стоп", "стой", "жди",
            "goto", "иди", "guard", "охрана", "охраняй", "attack", "атакуй", "атака", "бей", "pvp", "пвп", "дуэль",
            "dig", "копай", "сломай", "jump", "прыг", "прыгай", "sit", "сядь", "присядь", "look", "смотри", "where", "где",
            "say", "скажи", "эхо", "bye", "уйди", "пока", "tp", "тп", "телепорт", "wander", "гуляй", "collect", "собирай", "подбери",
            "tunnel", "туннель", "dance", "танцуй", "info", "инфо", "статус", "ближе", "дальше", "вниз",
            "инв", "инвентарь", "возьми", "дай", "выбрось", "ешь", "поешь", "мост", "столб", "поставь",
            "дом", "домой", "кто", "время", "компас", "шутка", "анекдот", "кубик", "монетка", "шар", "комплимент", "викторина"};

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
        for (String said : recentSaid) if (text.contains(said)) return; // наше же эхо

        String lowAll = text.toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        int cmdStart = -1;
        for (int i = 0; i < lowAll.length(); i++) {
            char c = lowAll.charAt(i);
            if (c == '!' && (i == 0 || !Character.isLetterOrDigit(lowAll.charAt(i - 1)))) {
                String rest = lowAll.substring(i + 1).trim();
                if (startsWithKnown(rest)) { cmdStart = lowAll.length() - rest.length(); break; }
            }
        }
        String prefix;
        if (cmdStart >= 0) {
            prefix = text.substring(0, Math.max(0, text.lastIndexOf('!', cmdStart)));
        } else {
            int at = lowAll.lastIndexOf(n);
            if (at < 0) {
                // ответ на викторину
                String who = sender.isEmpty() ? guessSender(text) : sender;
                if (allowFun) { String r = fun.check(who, text.substring(Math.max(0, text.lastIndexOf('>') + 1))); if (r != null) say(r); }
                return;
            }
            String rest = lowAll.substring(at + n.length()).replaceFirst("^[\\s,:!]+", "");
            if (!startsWithKnown(rest)) return;
            prefix = text.substring(0, at);
            cmdStart = lowAll.length() - rest.length();
            if (!sender.isEmpty() && sender.equalsIgnoreCase(name)) return;
            if (lowAll.substring(at + n.length()).matches("^\\s*(=>|>|:|»).*")) return; // это бот — автор сообщения
        }
        if (sender.isEmpty()) sender = guessSender(prefix);
        String pl = prefix.toLowerCase(Locale.ROOT);
        if (sender.equalsIgnoreCase(name) || pl.matches(".*(^|[^a-z0-9_])" + java.util.regex.Pattern.quote(n) + "\\s*(=>|>|:|»).*")) return;
        if (!owner.isEmpty() && !sender.equalsIgnoreCase(owner) && !pl.contains(owner.toLowerCase(Locale.ROOT))) return;
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
        for (String nm : world.names.values())
            if (nm.length() > best.length() && p.contains(nm.toLowerCase(Locale.ROOT)) && !nm.equalsIgnoreCase(name)) best = nm;
        for (World.Ent e : world.ents.values())
            if (e.isPlayer() && !e.name.isEmpty() && e.name.length() > best.length() && p.contains(e.name.toLowerCase(Locale.ROOT))) best = e.name;
        if (!best.isEmpty()) return best;
        String[] words = prefix.replaceAll("[\\[\\]()<>:=»|]", " ").trim().split("\\s+");
        return words.length > 0 ? words[words.length - 1] : "";
    }

    /** Команда из приложения: от имени владельца или ближайшего игрока. */
    public synchronized void appCommand(String c) {
        if (!spawned) { ui.log("Бот ещё не в мире"); return; }
        String who = owner;
        if (who.isEmpty()) { World.Ent p = world.nearestPlayer(x, y, z, eid); who = p != null ? p.name : ""; }
        command(who, c.toLowerCase(Locale.ROOT));
    }

    /** Игрок по нику (точно, затем по началу ника). */
    private World.Ent player(String nick) {
        if (nick == null || nick.isEmpty()) return null;
        World.Ent t = world.findPlayer(nick);
        if (t == null) for (Map.Entry<Long, String> e : world.names.entrySet())
            if (e.getValue().equalsIgnoreCase(nick)) { t = world.ents.get(e.getKey()); if (t != null) { t.name = e.getValue(); break; } }
        return t;
    }

    /** Ник из аргументов команды (пропуская служебные слова). */
    private static String nickArg(String[] a, int from) {
        for (int i = from; i < a.length; i++) {
            String w = a[i];
            if (w.equals("мной") || w.equals("меня") || w.equals("me") || w.equals("за") || w.equals("мне") || w.equals("ко")) continue;
            return w;
        }
        return null;
    }

    private boolean need(boolean allowed, String what) {
        if (!allowed) say(what + " выключено в настройках бота.");
        return allowed;
    }

    private void command(String sender, String c) {
        String[] a = c.split("\\s+");
        String cmd = a[0];
        World.Ent who = player(sender);
        ui.log("Команда от " + (sender.isEmpty() ? "?" : sender) + ": " + c);
        switch (cmd) {
            case "help": case "хелп": case "помощь": case "команды": {
                int page = a.length > 1 ? parseInt(a[1], 1) : 0;
                help(page);
                break;
            }
            case "come": case "ко": case "сюда":
                if (who == null) { say("Не вижу тебя, " + sender); break; }
                setGoal(feet(who)); mode = Mode.GOTO; say("Иду к тебе!");
                break;
            case "follow": case "за": case "следуй": case "иди_за": {
                String nick = nickArg(a, 1);
                World.Ent t = nick != null ? player(nick) : who;
                if (t == null) { say("Не вижу игрока " + (nick != null ? nick : sender)); break; }
                followName = t.name.isEmpty() ? null : t.name; followEid = t.eid;
                mode = Mode.FOLLOW; repathIn = 0; noProgress = 0; say("Следую за " + (t.name.isEmpty() ? "тобой" : t.name));
                break;
            }
            case "ближе": followDist = Math.max(1.5, followDist - 1); say("Держусь в " + fmt1(followDist) + " бл."); break;
            case "дальше": followDist = Math.min(8, followDist + 1); say("Держусь в " + fmt1(followDist) + " бл."); break;
            case "stop": case "стоп": case "стой": case "жди":
                if (mode == Mode.PVP) combat.endFight(false);
                mode = Mode.IDLE; path.clear(); digPos = null; digQueue.clear(); buildKind = 0;
                say("Стою.");
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
                    command(sender, c.substring(c.indexOf(' ') + 1));
                } else say("Пример: иди 10 64 -5");
                break;
            }
            case "дом": case "домой":
                if (a.length > 1 && (a[1].equals("тут") || a[1].equals("здесь"))) { home = new int[]{bx(), by(), bz()}; say("Запомнил дом: " + home[0] + " " + home[1] + " " + home[2]); }
                else if (home == null) say("Дом не задан. Встань там и напиши: дом тут");
                else { setGoal(home); mode = Mode.GOTO; say("Иду домой."); }
                break;
            case "tp": case "тп": case "телепорт":
                if (!need(allowTp, "Телепорт")) break;
                if (who == null) { say("Не вижу тебя"); break; }
                x = who.x + 1; y = who.y; z = who.z + 1; vy = 0; path.clear();
                sinceSent = 99; sendMove();
                say("Я рядом!");
                break;
            case "guard": case "охрана": case "охраняй": {
                String nick = nickArg(a, 1);
                World.Ent t = nick != null ? player(nick) : who;
                if (t == null) { say("Не вижу, кого охранять"); break; }
                followName = t.name.isEmpty() ? null : t.name; followEid = t.eid;
                mode = Mode.GUARD; repathIn = 0; attackTarget = -1; equipBestWeapon();
                say("Охраняю " + (t.name.isEmpty() ? "тебя" : t.name) + "! Бью мобов и тех, кто нападает.");
                break;
            }
            case "attack": case "атакуй": case "атака": case "бей": {
                World.Ent m = nearestHostile(x, y, z, 24);
                if (m == null) { say("Мобов рядом нет."); break; }
                attackTarget = m.eid; mode = Mode.ATTACK; equipBestWeapon();
                combat.start(mobName(m.type), m.eid, false, yaw);
                say("В атаку на " + mobName(m.type) + "!");
                break;
            }
            case "pvp": case "пвп": case "дуэль": {
                if (a.length > 1 && (a[1].equals("стат") || a[1].equals("статистика"))) {
                    String nick = a.length > 2 ? a[2] : sender;
                    say(combat.stats(nick)); break;
                }
                if (a.length > 1 && a[1].equals("сброс")) { combat.reset(a.length > 2 ? a[2] : null); say("Память боёв очищена."); break; }
                if (!need(allowPvp, "ПвП")) break;
                String nick = nickArg(a, 1);
                World.Ent t = nick != null ? player(nick) : world.nearestPlayer(x, y, z, eid);
                if (t == null) { say("Не вижу соперника " + (nick != null ? nick : "")); break; }
                equipBestWeapon();
                combat.start(t.name, t.eid, true, yaw);
                attackTarget = t.eid; mode = Mode.PVP;
                say("Бой с " + t.name + "! Тактика: " + combat.tacticName() + ". Остановить: стоп");
                break;
            }
            case "dig": case "копай": case "сломай": {
                if (!need(allowBuild, "Ломание блоков")) break;
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
                if (!need(allowBuild, "Ломание блоков")) break;
                int len = Math.max(1, Math.min(64, a.length > 1 ? parseInt(a[1], 8) : 8));
                faceDirection(who);
                tunnelLeft = len; mode = Mode.TUNNEL; digQueue.clear(); digPos = null;
                say("Копаю туннель на " + len + " блоков!");
                break;
            }
            case "вниз": {
                if (!need(allowBuild, "Ломание блоков")) break;
                int len = Math.max(1, Math.min(30, a.length > 1 ? parseInt(a[1], 5) : 5));
                digQueue.clear();
                for (int i = 1; i <= len; i++) digQueue.add(new int[]{bx(), by() - i, bz()});
                mode = Mode.DIG; digPos = null;
                say("Копаю вниз на " + len + "!");
                break;
            }
            case "мост": case "столб": case "поставь": {
                if (!need(allowBuild, "Стройка")) break;
                int blk = heldItem()[0];
                if (!Items.isBlock(blk) && gamemode != 1) {
                    int s = findSlot(it -> Items.isBlock(it[0]));
                    if (s < 0) { say("Нет блоков в инвентаре. Дай мне блоки!"); break; }
                    equip(s);
                }
                if (cmd.equals("поставь")) { faceDirection(who); placeFront(); break; }
                buildKind = cmd.equals("столб") ? 1 : 2;
                buildLeft = Math.max(1, Math.min(32, a.length > 1 ? parseInt(a[1], 5) : 5));
                faceDirection(who);
                buildDx = tunnelDx; buildDz = tunnelDz; buildStep = 0; buildBaseY = y;
                mode = Mode.BUILD; path.clear();
                say(buildKind == 1 ? "Строю столб на " + buildLeft + "!" : "Строю мост на " + buildLeft + " блоков!");
                break;
            }
            case "инв": case "инвентарь":
                say(invSummary());
                break;
            case "возьми": {
                if (a.length < 2) { say("Что взять? Например: возьми меч"); break; }
                String q = c.substring(c.indexOf(' ') + 1);
                int s = findByName(q);
                if (s < 0) { say("У меня нет «" + q + "»"); break; }
                equip(s); say("Взял " + Items.name(inv[s][0]));
                break;
            }
            case "дай": case "выбрось": {
                if (a.length < 2) { say(cmd.equals("дай") ? "Что дать? Например: дай хлеб" : "Что выбросить?"); break; }
                String q = c.substring(c.indexOf(' ') + 1);
                int s = findByName(q);
                if (s < 0) { say("У меня нет «" + q + "»"); break; }
                giveItem = s; giveTo = cmd.equals("дай") ? sender : null; giveTicks = 0;
                mode = Mode.GIVE; equip(s);
                say((cmd.equals("дай") ? "Несу " : "Выбрасываю ") + Items.name(inv[s][0]));
                break;
            }
            case "ешь": case "поешь": {
                int s = findSlot(it -> Items.food(it[0]) > 0);
                if (s < 0) { say("Нет еды."); break; }
                equip(s); eatTicks = 0; mode = Mode.EAT; say("Ням: " + Items.name(inv[s][0]));
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
                say("♥" + health + "/20, " + fmtPos() + ", " + modeName() + ", в руке: " + heldName());
                break;
            case "кто": {
                StringBuilder sb = new StringBuilder("Вижу игроков: ");
                int cnt = 0;
                for (World.Ent e : world.ents.values()) if (e.isPlayer()) { sb.append(e.name.isEmpty() ? "?" : e.name).append(" (").append((int) Math.sqrt(e.dist2(x, y, z))).append(" бл.) "); cnt++; }
                say(cnt == 0 ? "Никого не вижу рядом." : sb.toString());
                break;
            }
            case "компас": {
                World.Ent t = a.length > 1 ? player(a[1]) : who;
                if (t == null) { say("Не вижу такого игрока"); break; }
                double dx = t.x - x, dz = t.z - z;
                say((t.name.isEmpty() ? "Игрок" : t.name) + ": " + (int) Math.sqrt(dx * dx + dz * dz) + " бл., " + dirName(dx, dz) + ", коорд " + (int) t.x + " " + (int) t.y + " " + (int) t.z);
                break;
            }
            case "время": say("⏰ " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date())); break;
            case "шутка": case "анекдот": if (need(allowFun, "Развлечения")) say(Fun.joke()); break;
            case "кубик": if (need(allowFun, "Развлечения")) say(Fun.dice(a.length > 1 ? parseInt(a[1], 6) : 6)); break;
            case "монетка": if (need(allowFun, "Развлечения")) say(Fun.coin()); break;
            case "шар": if (need(allowFun, "Развлечения")) say(Fun.ball()); break;
            case "комплимент": if (need(allowFun, "Развлечения")) say((sender.isEmpty() ? "" : sender + ", ") + Fun.compliment()); break;
            case "викторина": if (need(allowFun, "Развлечения")) say(fun.newQuestion()); break;
            case "say": case "скажи": case "эхо": {
                int i = c.indexOf(' ');
                if (i > 0) chat(c.substring(i + 1));
                break;
            }
            case "bye": case "уйди": case "пока":
                say("Пока!");
                new Thread(() -> { try { Thread.sleep(600); } catch (InterruptedException ignored) {} disconnect(); ui.status("Отключён: по команде"); }).start();
                break;
            default:
                say("Не знаю «" + cmd + "». Напиши " + name + " хелп");
        }
    }

    private void help(int page) {
        switch (page) {
            case 1: say("Хелп 1 · Движение: ко мне, за мной [ник], стоп, жди, иди x y z, тп, дом / дом тут, гуляй, ближе, дальше, прыг, сядь, смотри"); break;
            case 2: say("Хелп 2 · Бой: охрана [ник], атакуй, пвп [ник], пвп стат [ник], пвп сброс"); break;
            case 3: say("Хелп 3 · Работа: копай [x y z], туннель N, вниз N, мост N, столб N, поставь, собирай"); break;
            case 4: say("Хелп 4 · Вещи: инв, возьми <что>, дай <что>, выбрось <что>, ешь"); break;
            case 5: say("Хелп 5 · Прочее: где, инфо, кто, компас [ник], время, шутка, кубик [N], монетка, шар, комплимент, викторина, танцуй, скажи <текст>, уйди"); break;
            default: say("Команды: «!команда» или «" + name + " команда». Разделы: хелп 1 (движение), хелп 2 (бой), хелп 3 (работа), хелп 4 (вещи), хелп 5 (прочее)");
        }
    }

    private static String dirName(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        String[] d = {"на север", "на северо-восток", "на восток", "на юго-восток", "на юг", "на юго-запад", "на запад", "на северо-запад"};
        int i = (int) Math.round(((ang + 360) % 360) / 45) % 8;
        return d[i];
    }

    private static int parseInt(String s, int def) { try { return Integer.parseInt(s); } catch (Exception e) { return def; } }

    private void say(String s) {
        if (answerInChat) chat(s);
        ui.log("[бот] " + s);
    }

    public void chat(String s) {
        if (rak != null && spawned) {
            synchronized (recentSaid) { recentSaid.add(s); if (recentSaid.size() > 10) recentSaid.poll(); }
            send(Proto.text(name, s));
        }
    }

    // ======================= инвентарь =======================
    private interface Pred { boolean ok(int[] it); }

    private int findSlot(Pred p) {
        for (int i = 0; i < 36; i++) if (inv[i][0] > 0 && p.ok(inv[i])) return i;
        return -1;
    }

    private int findByName(String q) {
        int id = Items.find(q);
        String ql = q.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 36; i++) if (inv[i][0] > 0 && (inv[i][0] == id || Items.name(inv[i][0]).toLowerCase(Locale.ROOT).contains(ql))) return i;
        if (gamemode == 1 && id > 0) { inv[35][0] = id; inv[35][1] = 1; inv[35][2] = 0; return 35; } // творческий: любой предмет
        return -1;
    }

    private int[] heldItem() { int s = hotbar[heldSlot]; return s >= 0 && s < 36 ? inv[s] : new int[3]; }
    private String heldName() { int[] h = heldItem(); return h[0] == 0 ? "пусто" : Items.name(h[0]); }

    /** Взять предмет из слота инвентаря в руку. */
    public synchronized void equip(int invSlot) {
        if (invSlot < 0 || invSlot >= 36) return;
        int hb = -1;
        for (int i = 0; i < 9; i++) if (hotbar[i] == invSlot) { hb = i; break; }
        if (hb < 0) { hb = heldSlot; hotbar[hb] = invSlot; }
        heldSlot = hb;
        int[] it = inv[invSlot];
        send(Proto.mobEquipment(eid, it[0], it[1], it[2], invSlot + 9, hb));
    }

    /** Выбросить стак из слота (из приложения). */
    public synchronized void drop(int invSlot) {
        if (invSlot < 0 || invSlot >= 36 || inv[invSlot][0] == 0) return;
        giveItem = invSlot; giveTo = null; giveTicks = 0; mode = Mode.GIVE; equip(invSlot);
    }

    private void equipBestWeapon() {
        int best = -1, bd = 1;
        for (int i = 0; i < 36; i++) { int d = Items.damage(inv[i][0]); if (d > bd) { bd = d; best = i; } }
        if (best >= 0 && hotbar[heldSlot] != best) equip(best);
    }

    private int countItems() { int n = 0; for (int[] s : inv) if (s[0] > 0) n++; return n; }

    private String invSummary() {
        Map<String, Integer> sum = new HashMap<>();
        for (int[] s : inv) if (s[0] > 0) sum.merge(Items.name(s[0]), s[1], Integer::sum);
        if (sum.isEmpty()) return gamemode == 1 ? "Я в творческом — могу взять любой предмет: возьми <что>" : "Инвентарь пуст.";
        StringBuilder sb = new StringBuilder("В руке: " + heldName() + ". Есть: ");
        int k = 0;
        for (Map.Entry<String, Integer> e : sum.entrySet()) { if (k++ > 0) sb.append(", "); sb.append(e.getKey()).append(" ×").append(e.getValue()); if (sb.length() > 200) { sb.append("…"); break; } }
        return sb.toString();
    }

    /** Снимок инвентаря для приложения: {слот, id, кол-во, мета, вХотбаре(0/1), вРуке(0/1)}. */
    public synchronized List<int[]> inventory() {
        List<int[]> r = new ArrayList<>();
        int held = hotbar[heldSlot];
        for (int i = 0; i < 36; i++) {
            if (inv[i][0] == 0) continue;
            boolean hb = false; for (int h : hotbar) if (h == i) hb = true;
            r.add(new int[]{i, inv[i][0], inv[i][1], inv[i][2], hb ? 1 : 0, i == held ? 1 : 0});
        }
        return r;
    }

    public int gameMode() { return gamemode; }

    // ======================= такт =======================
    private void tick() {
        RakClient r = rak;
        if (r == null || !r.isConnected() || !spawned) return;
        tickNo++;
        if (health <= 0 && deadSince > 0) {
            long dt = System.currentTimeMillis() - deadSince;
            if (dt > 1500 && dt < 1600) send(Proto.action(eid, Proto.ACTION_RESPAWN, 0, 0, 0, -1));
            if (dt > 6000) { health = 20; deadSince = 0; }
        }
        if (world.chunkCount() == 0 && tickNo % 100 == 0) send(Proto.requestChunkRadius(5));
        boolean known = world.loaded(bx(), bz());
        double mx = 0, mz = 0;
        boolean wantJump = jumpReq; jumpReq = false;
        double speed = 0.215;
        double[] direct = null;
        sprinting = false;

        switch (mode) {
            case FOLLOW: {
                World.Ent t = followTarget();
                if (t == null) { blocked = "не вижу, за кем идти"; break; }
                direct = followTick(t);
                if (direct != null) { speed = direct[3]; }
                break;
            }
            case GUARD: {
                World.Ent t = followTarget();
                if (t == null) { blocked = "не вижу, кого охранять"; break; }
                World.Ent threat = findThreat(t);
                if (threat != null) {
                    if (combat.foeEid != threat.eid) combat.start(threat.isPlayer() ? threat.name : mobName(threat.type), threat.eid, threat.isPlayer(), yaw);
                    double[] mv = fightTick(threat);
                    mx = mv[0]; mz = mv[1]; wantJump |= mv[2] > 0;
                    break;
                }
                // на мало здоровья держимся за спиной владельца
                direct = followTick(t);
                if (direct != null) speed = direct[3];
                break;
            }
            case GOTO:
                if (goal != null) {
                    double gd = Math.sqrt(Math.pow(goal[0] + 0.5 - x, 2) + Math.pow(goal[2] + 0.5 - z, 2));
                    if (gd < 1.3) { mode = Mode.IDLE; say("Пришёл."); goal = null; path.clear(); break; }
                    direct = new double[]{goal[0] + 0.5, goal[1], goal[2] + 0.5, 0.215};
                    if (path.isEmpty() || pathIdx >= path.size()) {
                        if (--repathIn < -6 && path.size() <= 1 && known) { mode = Mode.IDLE; say("Не могу пройти туда."); goal = null; break; }
                        newPath(goal[0], goal[1], goal[2], 0.9, 8000);
                    }
                }
                break;
            case ATTACK: case PVP: {
                World.Ent m = world.ents.get(attackTarget);
                if (m == null) {
                    if (mode == Mode.PVP) { combat.endFight(true); say("Соперник пропал. " + combat.fightScore()); }
                    else say("Готово!");
                    mode = Mode.IDLE; break;
                }
                double[] mv = fightTick(m);
                mx = mv[0]; mz = mv[1]; wantJump |= mv[2] > 0;
                break;
            }
            case DIG: digTick(); break;
            case TUNNEL: tunnelTick(); break;
            case BUILD: { double[] mv = buildTick(); if (mv != null) { mx = mv[0]; mz = mv[1]; wantJump |= mv[2] > 0; } break; }
            case GIVE: giveTick(); break;
            case EAT:
                if (eatTicks == 0) send(Proto.useItem(0, 0, 0, 255, 0, 0, 0, (float) x, (float) (y + 1.62), (float) z, heldItem()[0], heldItem()[1], heldItem()[2]));
                if (++eatTicks >= 34) { send(Proto.entityEvent(eid, 9)); mode = Mode.IDLE; say("Вкусно!"); }
                break;
            case WANDER:
                if (--wanderIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                    wanderIn = 100 + rnd.nextInt(100);
                    int tx = bx() + rnd.nextInt(17) - 8, tz = bz() + rnd.nextInt(17) - 8;
                    newPath(tx, by(), tz, 1.5, 1500);
                }
                speed = 0.12;
                break;
            case COLLECT: {
                World.Ent it = nearestOfType(64, 16);
                if (it == null) { if (tickNo % 100 == 0) { mode = Mode.IDLE; say("Всё собрал."); } break; }
                direct = new double[]{it.x, it.y, it.z, 0.215};
                if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                    newPath((int) Math.floor(it.x), (int) Math.floor(it.y), (int) Math.floor(it.z), 0.5, 2000); repathIn = 20;
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

        // ---- движение по пути ----
        boolean usePath = known && path.size() > 1 && pathIdx < path.size() && mx == 0 && mz == 0;
        edgeAllowed = usePath;
        boolean climbing = false;
        if (usePath) {
            int[] wp = path.get(pathIdx);
            int[] prev = path.get(Math.max(0, pathIdx - 1));
            double tx = wp[0] + 0.5, tz = wp[2] + 0.5;
            double dx = tx - x, dz = tz - z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            int how = wp.length > 3 ? wp[3] : PathFinder.WALK;
            double gapLen = Math.sqrt(Math.pow(wp[0] - prev[0], 2) + Math.pow(wp[2] - prev[2], 2));
            if ((hd < 0.3 && Math.abs(wp[1] - y) < (how == PathFinder.CLIMB ? 0.3 : 1.2))
                    || (how == PathFinder.GAP && onGround && hd < 1.0 && Math.abs(wp[1] - y) < 1.2)) {
                pathIdx++;
            } else {
                double sp = speed;
                if (how == PathFinder.GAP) { sp = gapLen > 2.5 ? 0.28 : 0.21; sprinting = gapLen > 2.5; }
                if (hd > 0.01) { mx = dx / hd * Math.min(sp, hd); mz = dz / hd * Math.min(sp, hd); }
                if (how == PathFinder.CLIMB) {
                    climbing = true;
                    if (wp[1] > y + 0.05) vy = 0.2; else if (wp[1] < y - 0.05) vy = -0.15; else vy = 0;
                } else if (how == PathFinder.GAP) {
                    // прыгаем у края текущего блока
                    double fromX = prev[0] + 0.5, fromZ = prev[2] + 0.5;
                    double gone = Math.sqrt((x - fromX) * (x - fromX) + (z - fromZ) * (z - fromZ));
                    if (onGround && gone > 0.25 && hd > 1.2) { wantJump = true; gapFlight = true; }
                } else if (wp[1] > Math.floor(y + 0.01) && onGround && hd < 1.6) wantJump = true;
                if (hd > 0.05) yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                pitch = 0;
            }
            if (tickNo % 20 == 0) {
                double prog = Math.abs(x - lastProgX) + Math.abs(z - lastProgZ);
                if (prog < 0.3) { stuckTicks++; wantJump = true; } else stuckTicks = 0;
                if (stuckTicks >= 2) { path.clear(); repathIn = 0; stuckTicks = 0; }
                lastProgX = x; lastProgZ = z;
            }
            blocked = "";
        } else if (direct != null && mx == 0 && mz == 0) {
            double dx = direct[0] - x, dz = direct[2] - z;
            double hd = Math.sqrt(dx * dx + dz * dz);
            if (hd > 0.2) {
                mx = dx / hd * Math.min(speed, hd); mz = dz / hd * Math.min(speed, hd);
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            if (!known) { y += Math.max(-0.4, Math.min(0.4, direct[1] - y)); blocked = "нет данных о блоках здесь — иду напрямую"; }
            else {
                blocked = "путь не найден — иду напрямую";
                double[] sm = safeMove(mx, mz); mx = sm[0]; mz = sm[1];
                if (tickNo % 10 == 0) wantJump = true;
            }
        }

        if (lookAt != null && lookTicks > 0 && mode != Mode.PVP && mode != Mode.ATTACK) {
            lookTicks--;
            double dx = lookAt.x - x, dy = lookAt.y - y, dz = lookAt.z - z;
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }

        if (!known) { x += mx; z += mz; onGround = true; }
        else physics(mx, mz, wantJump, climbing);

        // защита от «улёта»: странные координаты — откат к последней нормальной позиции
        if (!ok(x) || !ok(y) || !ok(z) || y < -64 || y > 400) { x = goodX; y = goodY; z = goodZ; vy = 0; kx = kz = 0; ui.log("Позиция сбилась — вернул на место"); }
        else if (onGround) { goodX = x; goodY = y; goodZ = z; }
        sendMove();
    }

    /** Следование за игроком. Возвращает {x,y,z,speed} для прямого хода или null. */
    private double[] followTick(World.Ent t) {
        double d = Math.sqrt(t.dist2(x, y, z));
        if (d > 48 && allowTp) { x = t.x + 1; y = t.y; z = t.z + 1; vy = 0; path.clear(); sinceSent = 99; return null; }
        if (d <= followDist + 0.4) { path.clear(); lookAt = t; lookTicks = 2; noProgress = 0; lastStuckSaid = false; return null; }
        // цель — чуть впереди по ходу игрока (он идёт — мы предугадываем)
        double px = t.x + t.curVx() * 6, pz = t.z + t.curVz() * 6;
        double spd = d > 6 ? 0.28 : 0.215;
        if (d > 6) sprinting = true;
        int[] g = feetAt(px, t.y, pz);
        boolean pathStale = path.isEmpty() || pathIdx >= path.size();
        if (!pathStale) {
            int[] end = path.get(path.size() - 1);
            double ed = Math.sqrt(Math.pow(end[0] - g[0], 2) + Math.pow(end[2] - g[2], 2));
            if (ed > 2.5) pathStale = true;
        }
        if (--repathIn <= 0 && pathStale) {
            newPath(g[0], g[1], g[2], Math.max(1.2, followDist - 0.5), 5000); repathIn = 8;
        }
        // застряли надолго — догоняем телепортом или просим помощи
        if (tickNo % 20 == 0) {
            double prog = Math.abs(x - lastProgX) + Math.abs(z - lastProgZ);
            if (prog < 0.4 && d > followDist + 2) noProgress++; else noProgress = 0;
            if (noProgress >= 4) {
                if (allowTp && d > 6) { x = t.x + 0.5; y = t.y; z = t.z + 0.5; vy = 0; path.clear(); sinceSent = 99; say("Догоняю!"); }
                else if (!lastStuckSaid) { say("Не могу пройти, помоги!"); lastStuckSaid = true; }
                noProgress = 0;
            }
        }
        return new double[]{t.x, t.y, t.z, spd};
    }

    private World.Ent followTarget() {
        World.Ent t = followEid >= 0 ? world.ents.get(followEid) : null;
        if (t == null && followName != null) { t = player(followName); if (t != null) followEid = t.eid; }
        return t;
    }

    /** Угроза для охраняемого: кто его бьёт, затем враждебные мобы рядом. */
    private World.Ent findThreat(World.Ent owner) {
        World.Ent cur = attackTarget >= 0 ? world.ents.get(attackTarget) : null;
        if (cur != null && Math.sqrt(cur.dist2(owner.x, owner.y, owner.z)) < 14) return cur;
        attackTarget = -1;
        boolean ownerHit = tickNo - ownerHurtTick < 30, selfHit = tickNo - selfHurtTick < 30;
        World.Ent best = null; double bd = 1e9;
        for (World.Ent e : world.ents.values()) {
            if (e.eid == eid || e.eid == owner.eid) continue;
            double dOwner = Math.sqrt(e.dist2(owner.x, owner.y, owner.z)), dSelf = Math.sqrt(e.dist2(x, y, z));
            double score = 1e9;
            if (hostile(e.type)) {
                if (dOwner < 10 || dSelf < 6) score = dOwner;
            } else if (e.isPlayer()) {
                Long sw = lastSwing.get(e.eid);
                boolean swung = sw != null && tickNo - sw < 20;
                if (swung && ((ownerHit && dOwner < 4.5) || (selfHit && dSelf < 4.5))) score = dOwner - 5; // напал — в приоритете
            }
            if (score < bd) { bd = score; best = e; }
        }
        if (best != null) {
            attackTarget = best.eid;
            if (best.isPlayer()) say(best.name + ", не трогай " + (owner.name.isEmpty() ? "хозяина" : owner.name) + "!");
        }
        return best;
    }

    /** Такт боя через боевой ИИ: {mx, mz, jump}. */
    private double[] fightTick(World.Ent foe) {
        if (combat.foeEid != foe.eid) combat.start(foe.isPlayer() ? foe.name : mobName(foe.type), foe.eid, foe.isPlayer() && mode == Mode.PVP, yaw);
        Combat.Act act = combat.tick(x, y, z, foe.x, foe.y, foe.z, health, onGround, foe.type == 33);
        yaw = act.yaw; pitch = act.pitch;
        double dist = Math.sqrt(foe.dist2(x, y, z));
        if ((dist > 4 || (dist > 2.9 && !clearLine(foe.x, foe.z))) && world.loaded(bx(), bz())) {
            // далеко — подходим по пути (обходя препятствия)
            if (--repathIn <= 0 || path.isEmpty() || pathIdx >= path.size()) {
                int[] g = feet(foe);
                newPath(g[0], g[1], g[2], 2.0, 2500); repathIn = 10;
            }
            if (path.size() > 1 && pathIdx < path.size()) return new double[]{0, 0, 0};
        }
        if (act.attack) {
            send(Proto.animate(eid, 1));
            send(Proto.interact(2, foe.eid));
        }
        if (act.sprint) sprinting = true;
        double[] safe = safeMove(act.mx, act.mz);
        if (safe[0] == 0 && safe[1] == 0 && (act.mx != 0 || act.mz != 0) && dist > 2.8 && !act.retreat) {
            // напрямую нельзя (яма) — обходим или перепрыгиваем по пути
            if (path.isEmpty() || pathIdx >= path.size()) {
                int[] g = feet(foe);
                newPath(g[0], g[1], g[2], 1.5, 2500);
            }
            return new double[]{0, 0, 0};
        }
        return new double[]{safe[0], safe[1], act.jump ? 1 : 0};
    }

    /** Можно ли пройти к точке по прямой без ям и стен. */
    private boolean clearLine(double tx, double tz) {
        double dx = tx - x, dz = tz - z, d = Math.sqrt(dx * dx + dz * dz);
        for (double t = 0.5; t < d - 0.4; t += 0.5) {
            double px = x + dx / d * t, pz = z + dz / d * t;
            int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
            if (unsafeAt(px, pz) || !world.passable(ix, by(), iz) || !world.passable(ix, by() + 1, iz)) return false;
        }
        return true;
    }

    /** Не даём шагнуть в яму глубже 3 блоков, в лаву или огонь (в бою и при прямом ходе). */
    private double[] safeMove(double mx, double mz) {
        if (!world.loaded(bx(), bz())) return new double[]{mx, mz};
        double ax = mx, az = mz;
        if (unsafeAt(x + mx * 3, z)) ax = 0;
        if (unsafeAt(x, z + mz * 3)) az = 0;
        if (ax != 0 && az != 0 && unsafeAt(x + ax * 3, z + az * 3)) { ax = 0; az = 0; }
        return new double[]{ax, az};
    }

    private boolean unsafeAt(double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz), iy = by();
        if (ix == bx() && iz == bz()) return false;
        for (int d = 0; d <= 3; d++) {
            int id = world.id(ix, iy - d, iz);
            if (id == 10 || id == 11 || id == 51) return true;
            if (d > 0 && world.solid(ix, iy - d, iz)) return false;
            if (d == 0 && !world.passable(ix, iy, iz)) return false; // стена — не яма
        }
        return true;
    }

    // ---------- стройка ----------
    private void place(int cx, int cy, int cz, int face, double fx, double fy, double fz) {
        int[] h = heldItem();
        send(Proto.animate(eid, 1));
        send(Proto.useItem(cx, cy, cz, face, (float) fx, (float) fy, (float) fz, (float) x, (float) (y + 1.62), (float) z, h[0], h[1], h[2]));
        int nx = cx, ny = cy, nz = cz;
        switch (face) { case 0: ny--; break; case 1: ny++; break; case 2: nz--; break; case 3: nz++; break; case 4: nx--; break; case 5: nx++; break; }
        if (Items.isBlock(h[0])) world.set(nx, ny, nz, h[0], h[2]);
        int s = hotbar[heldSlot];
        if (gamemode != 1 && s >= 0 && s < 36 && inv[s][1] > 0) { inv[s][1]--; if (inv[s][1] == 0) inv[s][0] = 0; }
    }

    private void placeFront() {
        int tx = bx() + tunnelDx, tz = bz() + tunnelDz, ty = by();
        if (!world.passable(tx, ty, tz)) { say("Там уже занято."); return; }
        if (world.solid(tx, ty - 1, tz)) place(tx, ty - 1, tz, 1, 0.5, 1, 0.5);
        else place(bx(), ty - 1, bz(), tunnelDx > 0 ? 5 : tunnelDx < 0 ? 4 : tunnelDz > 0 ? 3 : 2, 0.5, 0.5, 0.5);
        say("Поставил!");
    }

    private double[] buildTick() {
        if (heldItem()[0] == 0 && gamemode != 1) { mode = Mode.IDLE; say("Блоки кончились."); return null; }
        if (buildKind == 1) { // столб: прыжок + блок под ноги
            if (buildLeft <= 0) { mode = Mode.IDLE; say("Столб готов!"); return null; }
            pitch = 90;
            if (onGround && buildStep == 0) { buildBaseY = Math.floor(y + 0.01); buildStep = 1; return new double[]{0, 0, 1}; }
            if (buildStep == 1 && y >= buildBaseY + 1.05) {
                int bx = bx(), by = (int) buildBaseY, bz = bz();
                if (world.passable(bx, by, bz)) place(bx, by - 1, bz, 1, 0.5, 1, 0.5);
                buildStep = 2;
            }
            if (buildStep == 2 && onGround) { buildLeft--; buildStep = 0; }
            return new double[]{0, 0, 0};
        }
        // мост: присев, ставим блок перед собой внизу и шагаем
        if (buildLeft <= 0) {
            mode = Mode.IDLE;
            if (sneaking) { sneaking = false; send(Proto.action(eid, Proto.ACTION_STOP_SNEAK, 0, 0, 0, 0)); }
            say("Мост готов!"); return null;
        }
        if (!sneaking) { sneaking = true; send(Proto.action(eid, Proto.ACTION_START_SNEAK, 0, 0, 0, 0)); }
        yaw = (float) Math.toDegrees(Math.atan2(-buildDx, buildDz)); pitch = 75;
        int cx = bx(), cy = by() - 1, cz = bz();
        int nx = cx + buildDx, nz = cz + buildDz;
        if (world.passable(nx, cy, nz)) {
            if (buildStep++ % 4 == 0) place(cx, cy, cz, buildDx > 0 ? 5 : buildDx < 0 ? 4 : buildDz > 0 ? 3 : 2, 0.5, 0.5, 0.5);
            return new double[]{0, 0, 0};
        }
        // блок есть — шагаем на него
        double tx = nx + 0.5, tz = nz + 0.5;
        double dx = tx - x, dz = tz - z, d = Math.sqrt(dx * dx + dz * dz);
        if (d < 0.2) { buildLeft--; buildStep = 0; return new double[]{0, 0, 0}; }
        return new double[]{dx / d * Math.min(0.1, d), dz / d * Math.min(0.1, d), 0};
    }

    // ---------- передать / выбросить ----------
    private void giveTick() {
        giveTicks++;
        if (giveTo != null) {
            World.Ent t = player(giveTo);
            if (t == null) { giveTo = null; }
            else {
                double d = Math.sqrt(t.dist2(x, y, z));
                if (d > 2.5 && giveTicks < 400) {
                    if (path.isEmpty() || pathIdx >= path.size()) { int[] g = feet(t); newPath(g[0], g[1], g[2], 1.8, 3000); }
                    return;
                }
                path.clear();
                double dx = t.x - x, dz = t.z - z;
                yaw = (float) Math.toDegrees(Math.atan2(-dx, dz)); pitch = 10;
            }
        }
        if (giveTicks < 3) return;
        int[] it = inv[giveItem];
        if (it[0] != 0) {
            send(Proto.dropItem(it[0], it[1], it[2]));
            ui.log("Выбросил " + Items.name(it[0]) + " ×" + it[1]);
            it[0] = 0; it[1] = 0; it[2] = 0;
        }
        mode = Mode.IDLE;
        if (giveTo != null) say("Держи!");
    }

    // ---------- копание ----------
    private void startDig(int[] p) {
        digPos = p; digTicks = 0;
        int id = world.id(p[0], p[1], p[2]);
        digTotal = gamemode == 1 ? 2 : breakTicks(id);
        if (digTotal < 0) { say("Этот блок не сломать."); digPos = null; return; }
        mode = mode == Mode.TUNNEL ? Mode.TUNNEL : Mode.DIG;
        double dx = p[0] + 0.5 - x, dz = p[2] + 0.5 - z;
        if (dx * dx + dz * dz > 16) { newPath(p[0], p[1], p[2], 2.5, 3000); }
        if (mode == Mode.DIG && digQueue.isEmpty()) say("Копаю " + p[0] + " " + p[1] + " " + p[2]);
    }

    private boolean digStep() {
        if (digPos == null) return true;
        double dx = digPos[0] + 0.5 - x, dy = digPos[1] + 0.5 - (y + 1.62), dz = digPos[2] + 0.5 - z;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (d > 5) {
            if (path.isEmpty() || pathIdx >= path.size()) {
                newPath(digPos[0], digPos[1], digPos[2], 2.5, 3000);
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
        if (path.isEmpty() || pathIdx >= path.size()) {
            if (tunnelLeft-- <= 0) { mode = Mode.IDLE; say("Туннель готов!"); return; }
            path = new ArrayList<>();
            path.add(new int[]{bx(), by(), bz(), 0});
            path.add(new int[]{fx, fy, fz, 0});
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

    private void physics(double mx, double mz, boolean jump, boolean climbing) {
        if (collides(x, y, z)) {
            for (int i = 1; i <= 4; i++) if (!collides(x, y + i, z)) { y = Math.floor(y) + i; vy = 0; break; }
        }
        // толкание игроками и мобами
        for (World.Ent e : world.ents.values()) {
            if (!e.isPlayer() && !(e.type >= 10 && e.type <= 45)) continue;
            double dx = x - e.x, dz = z - e.z, d2 = dx * dx + dz * dz;
            if (d2 < 0.36 && d2 > 1e-6 && Math.abs(e.y - y) < 1.8) { double d = Math.sqrt(d2); kx += dx / d * 0.04; kz += dz / d * 0.04; }
        }
        mx += kx; mz += kz;
        double fr = onGround ? 0.55 : 0.91;
        kx *= fr; kz *= fr;
        if (Math.abs(kx) < 0.003) kx = 0;
        if (Math.abs(kz) < 0.003) kz = 0;
        // не больше 0.6 блока за такт, чтобы не «проскакивать» сквозь стены
        double hm = Math.sqrt(mx * mx + mz * mz);
        if (hm > 0.6) { mx *= 0.6 / hm; mz *= 0.6 / hm; }

        boolean inWater = world.liquid((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z));
        boolean onLadder = PathFinder.climbable(world.id((int) Math.floor(x), (int) Math.floor(y + 0.1), (int) Math.floor(z)));
        // инерция в прыжке: в воздухе почти нельзя менять направление (как у игрока)
        if (gapFlight && !onGround && !inWater && !climbing && !onLadder) {
            mx = airMx * 0.98 + (mx - kx) * 0.08 + kx; mz = airMz * 0.98 + (mz - kz) * 0.08 + kz;
        }
        airMx = mx; airMz = mz;
        if (inWater && !climbing) { vy = Math.min(vy + 0.06, 0.12); mx *= 0.5; mz *= 0.5; }
        else if (jump && onGround) { vy = 0.42; onGround = false; send(Proto.action(eid, Proto.ACTION_JUMP, 0, 0, 0, 0)); }
        // защита от края: без пути не шагаем туда, где под ногами пропасть глубже 3 блоков
        if (onGround && !edgeAllowed) {
            if (mx != 0 && deepDrop(x + mx, z)) mx = 0;
            if (mz != 0 && deepDrop(x, z + mz)) mz = 0;
        }
        if (mx != 0) {
            if (!collides(x + mx, y, z)) x += mx;
            else if (onGround && !collides(x + mx, y + 0.55, z) && stepUp(x + mx, z)) x += mx;
        }
        if (mz != 0) {
            if (!collides(x, y, z + mz)) z += mz;
            else if (onGround && !collides(x, y + 0.55, z + mz) && stepUp(x, z + mz)) z += mz;
        }
        // вертикаль — небольшими шагами, чтобы при быстром падении не пролететь сквозь пол
        double remaining = vy;
        int steps = (int) Math.ceil(Math.abs(remaining) / 0.5);
        boolean landed = false;
        for (int i = 0; i < Math.max(1, steps); i++) {
            double step = remaining / Math.max(1, steps - i);
            double ny = y + step;
            if (!collides(x, ny, z)) { y = ny; remaining -= step; }
            else {
                if (step < 0) {
                    double lo = ny, hi = y;
                    for (int k = 0; k < 12; k++) { double mid = (lo + hi) / 2; if (collides(x, mid, z)) lo = mid; else hi = mid; }
                    y = hi; landed = true;
                }
                vy = 0; remaining = 0;
                break;
            }
        }
        onGround = landed || (vy <= 0 && collides(x, y - 0.05, z));
        if (landed) gapFlight = false;
        if (climbing || onLadder) { if (!climbing) vy = Math.max(vy, -0.15); }
        else if (!inWater) { vy -= 0.08; vy *= 0.98; if (vy < -3.9) vy = -3.9; }
        if (onGround && vy < 0) vy = -0.08;
    }

    private boolean deepDrop(double px, double pz) {
        if (collides(px, y - 0.1, pz)) return false; // опора есть
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz), iy = (int) Math.floor(y + 0.01);
        for (int d = 1; d <= 3; d++) {
            int id = world.id(ix, iy - d, iz);
            if (id == 10 || id == 11) return true;
            if (world.solid(ix, iy - d, iz)) return false;
        }
        return true;
    }

    private boolean stepUp(double px, double pz) {
        double lo = y, hi = y + 0.55;
        if (collides(px, hi, pz)) return false;
        for (int i = 0; i < 10; i++) { double mid = (lo + hi) / 2; if (collides(px, mid, pz)) lo = mid; else hi = mid; }
        y = hi;
        return true;
    }

    private boolean wasSprinting;

    private void sendMove() {
        if (sprinting != wasSprinting) {
            send(Proto.action(eid, sprinting ? Proto.ACTION_START_SPRINT : Proto.ACTION_STOP_SPRINT, 0, 0, 0, 0));
            wasSprinting = sprinting;
        }
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

    private int[] feet(World.Ent e) { return feetAt(e.x, e.y, e.z); }

    private int[] feetAt(double ex, double ey, double ez) {
        int fx = (int) Math.floor(ex), fz = (int) Math.floor(ez);
        int fy = (int) Math.floor(ey + 0.2);
        for (int i = 0; i < 4; i++) {
            if (world.passable(fx, fy, fz) && world.solid(fx, fy - 1, fz)) break;
            if (!world.passable(fx, fy, fz)) fy++; else fy--;
        }
        return new int[]{fx, fy, fz};
    }

    /** Новый путь — только стоя на земле (в прыжке продолжаем старый). */
    private void newPath(int tx, int ty, int tz, double near, int max) {
        if (!onGround && !path.isEmpty() && !world.liquid(bx(), by(), bz())) return;
        path = pf.find(bx(), by(), bz(), tx, ty, tz, near, max);
        pathIdx = 1;
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
            case GUARD: return "охрана"; case ATTACK: return "атака"; case PVP: return "ПвП (" + combat.tacticName() + ")";
            case DIG: return "копаю"; case TUNNEL: return "туннель";
            case WANDER: return "гуляю"; case COLLECT: return "собираю"; case DANCE: return "танцую";
            case BUILD: return "строю"; case GIVE: return "отдаю предмет"; case EAT: return "ем";
        }
        return mode.name();
    }

    private static String fmt1(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private String fmtPos() { return String.format(Locale.ROOT, "%.1f %.1f %.1f", x, y, z); }

    public synchronized String info() {
        if (rak == null || !spawned) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("Режим: ").append(modeName()).append("  ♥").append(health).append("  в руке: ").append(heldName())
                .append("\nПозиция: ").append(fmtPos())
                .append("\nЧанков: ").append(world.chunkCount()).append(world.loaded(bx(), bz()) ? "" : " (под ботом нет данных)")
                .append(", игроков: ").append(countPlayers()).append(", сущностей: ").append(world.ents.size());
        if (mode == Mode.PVP) sb.append("\nБой: ").append(combat.fightScore());
        if (!blocked.isEmpty() && mode != Mode.IDLE) sb.append("\n⚠ ").append(blocked);
        if (debug) {
            sb.append("\nid ").append(eid).append(" · пакеты: ");
            synchronized (pktCount) { for (Map.Entry<Integer, Integer> e : pktCount.entrySet()) sb.append(Integer.toHexString(e.getKey())).append('×').append(e.getValue()).append(' '); }
        }
        return sb.toString();
    }

    private int countPlayers() { int n = 0; for (World.Ent e : world.ents.values()) if (e.isPlayer()) n++; return n; }

    public boolean isOnline() { return rak != null && spawned; }

    public String brainData() { return combat.save(); }
    public void loadBrain(String s) { combat.load(s); }
}
