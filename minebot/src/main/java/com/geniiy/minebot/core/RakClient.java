package com.geniiy.minebot.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * Минимальный клиент RakNet (как в MCPE 0.14): рукопожатие, надёжная доставка,
 * порядок, разбиение больших пакетов, ACK/NACK, пинги.
 */
public final class RakClient {
    public interface Listener {
        void onConnected();
        void onPayload(byte[] data);       // полезная нагрузка (уже собрана и упорядочена)
        void onClosed(String reason);
        void log(String msg);
    }

    static final byte[] MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    public static final int UNRELIABLE = 0, RELIABLE = 2, RELIABLE_ORDERED = 3;

    private final Listener L;
    private InetSocketAddress server;
    private final String host;
    private final int port;
    private DatagramSocket sock;
    private final long guid = new Random().nextLong();
    private volatile boolean running;
    private Thread thread;

    private int protocol = 7;
    private boolean protocolForced;
    private String pongSeen;
    /** Попытки подключения: {версия RakNet, MTU}. */
    private static final int[][] ATTEMPTS = {
            {7, 1492}, {6, 1492}, {8, 1492}, {7, 1200}, {6, 1200}, {5, 1492}, {8, 1200}, {7, 576}, {6, 576}, {9, 1200}};
    private int mtu = 1492;
    private int state; // 0 OCR1, 1 OCR2, 2 connecting, 3 connected, 4 closed
    private long stateTime, lastRecv, lastPing, startTime;
    private int ocr1Tries;

    // отправка
    private int sendSeq, msgIndex, splitId;
    private final int[] orderIndex = new int[32];
    private final List<byte[]> outFrames = new ArrayList<>();      // кадры на ближайшую отправку
    private final Map<Integer, Sent> sentReliable = new HashMap<>();
    private static final class Sent { List<byte[]> frames; long time; Sent(List<byte[]> f, long t) { frames = f; time = t; } }

    // приём
    private final TreeSet<Integer> ackQueue = new TreeSet<>();
    private final TreeSet<Integer> nackQueue = new TreeSet<>();
    private int highestSeq = -1;
    private final Set<Integer> gotMsg = new HashSet<>();
    private int gotMsgLow;
    private final int[] expectOrder = new int[32];
    private final List<Map<Integer, byte[]>> orderWait = new ArrayList<>();
    private final int[] highestSequenced = new int[32];
    private final Map<Integer, byte[][]> splits = new HashMap<>();

    public RakClient(String host, int port, Listener l) throws IOException {
        this.L = l;
        this.host = host;
        this.port = port;
        for (int i = 0; i < 32; i++) { orderWait.add(new HashMap<>()); highestSequenced[i] = -1; }
    }

    public boolean isConnected() { return state == 3; }

    public void start() throws IOException {
        sock = new DatagramSocket();
        sock.setSoTimeout(10);
        running = true;
        startTime = now();
        state = 0; stateTime = 0;
        thread = new Thread(() -> {
            try {
                server = new InetSocketAddress(InetAddress.getByName(host), port);
                L.log("Адрес: " + server.getAddress().getHostAddress() + ":" + port);
            } catch (Exception e) {
                shutdown("Не найден адрес " + host);
                return;
            }
            loop();
        }, "raknet");
        thread.setDaemon(true);
        thread.start();
    }

    public void close(String reason) {
        if (state == 3) {
            synchronized (this) { queue(new byte[]{0x15}, RELIABLE_ORDERED, 0); flush(); }
        }
        shutdown(reason);
    }

    private void shutdown(String reason) {
        if (!running) return;
        running = false;
        state = 4;
        try { sock.close(); } catch (Exception ignored) {}
        L.onClosed(reason);
    }

    static long now() { return System.nanoTime() / 1_000_000L; }

    // ---------------- основной цикл ----------------
    private void loop() {
        byte[] rb = new byte[2048];
        DatagramPacket dp = new DatagramPacket(rb, rb.length);
        lastRecv = now();
        while (running) {
            try {
                try {
                    dp.setLength(rb.length);
                    sock.receive(dp);
                    if (dp.getPort() == server.getPort()) {
                        lastRecv = now();
                        handleDatagram(new Buf(rb, 0, dp.getLength()));
                    }
                } catch (SocketTimeoutException ignored) {
                }
                tick();
            } catch (Exception e) {
                if (running) L.log("RakNet: " + e);
            }
        }
    }

    private void rawSend(Buf b) {
        try { sock.send(new DatagramPacket(b.b, b.len, server)); } catch (IOException e) { L.log("send: " + e); }
    }

    private synchronized void tick() {
        long t = now();
        if (state == 0) {
            if (t - stateTime > 500) {
                stateTime = t;
                if (ocr1Tries % 2 == 0) {
                    // пинг без соединения: проверяем, что сервер вообще слышит нас
                    rawSend(new Buf().u8(0x01).i64(t - startTime).raw(MAGIC).i64(guid));
                }
                if (ocr1Tries >= ATTEMPTS.length * 2) {
                    shutdown(pongSeen == null
                            ? "Сервер не отвечает ни на что. Мир открыт? Правильный адрес/порт?"
                            : "Сервер «" + pongSeen + "» отвечает на пинг, но не пускает. Пришли журнал разработчику");
                    return;
                }
                int[] at = ATTEMPTS[ocr1Tries / 2];
                if (!protocolForced) protocol = at[0];
                mtu = at[1];
                ocr1Tries++;
                L.log("RakNet: запрос 1 (протокол " + protocol + ", MTU " + mtu + ")");
                Buf b = new Buf().u8(0x05).raw(MAGIC).u8(protocol);
                b.zeros(Math.max(0, mtu - 28 - b.len));
                rawSend(b);
            }
            return;
        }
        if (state == 1) {
            if (t - stateTime > 500) { stateTime = t; sendOcr2(); }
            if (t - startTime > 15000) shutdown("Не удалось подключиться");
            return;
        }
        if (t - lastRecv > 15000) { shutdown("Соединение потеряно (таймаут)"); return; }
        if (state == 3 && t - lastPing > 4000) {
            lastPing = t;
            queue(new Buf().u8(0x00).i64(t - startTime).bytes(), UNRELIABLE, 0);
        }
        // повтор неподтверждённых
        Iterator<Map.Entry<Integer, Sent>> it = sentReliable.entrySet().iterator();
        List<List<byte[]>> again = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<Integer, Sent> e = it.next();
            if (t - e.getValue().time > 1000) { again.add(e.getValue().frames); it.remove(); }
        }
        for (List<byte[]> fr : again) sendFrames(fr, true);
        flushAcks();
        flush();
    }

    private void sendOcr2() {
        byte[] ip = server.getAddress().getAddress();
        if (ip.length != 4) ip = new byte[]{127, 0, 0, 1};
        Buf b = new Buf().u8(0x07).raw(MAGIC).addr4(ip, server.getPort()).i16(mtu).i64(guid);
        rawSend(b);
    }

    // ---------------- приём ----------------
    private void handleDatagram(Buf b) {
        int id = b.b[b.pos] & 0xff;
        if (state < 2) { handleOffline(b, id); return; }
        if ((id & 0x80) == 0) return;
        if ((id & 0x40) != 0) { b.u8(); handleAck(b, false); return; }
        if ((id & 0x20) != 0) { b.u8(); handleAck(b, true); return; }
        b.u8();
        int seq = b.i24le();
        synchronized (this) {
            ackQueue.add(seq);
            if (seq > highestSeq) {
                for (int i = highestSeq + 1; i < seq; i++) nackQueue.add(i);
                highestSeq = seq;
            } else nackQueue.remove(seq);
        }
        while (b.left() > 0) {
            int flags = b.u8();
            int rel = (flags & 0xe0) >> 5;
            boolean split = (flags & 0x10) != 0;
            int bits = b.u16();
            int n = (bits + 7) / 8;
            int mi = -1, oi = 0, ch = 0, si = -1;
            if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) mi = b.i24le();
            if (rel == 1 || rel == 4) si = b.i24le();
            if (rel == 1 || rel == 3 || rel == 4 || rel == 7) { oi = b.i24le(); ch = b.u8() & 31; }
            int sc = 0, sid = 0, sx = 0;
            if (split) { sc = b.i32(); sid = b.u16(); sx = b.i32(); }
            byte[] body = b.take(n);
            if (mi >= 0) {
                synchronized (this) {
                    if (mi < gotMsgLow || !gotMsg.add(mi)) continue; // дубликат
                    if (gotMsg.size() > 4096) {
                        gotMsgLow = mi - 2048;
                        gotMsg.removeIf(x -> x < gotMsgLow);
                    }
                }
            }
            if (split) {
                if (sc <= 0 || sc > 4096 || sx < 0 || sx >= sc) continue;
                final int fsc = sc;
                byte[][] parts = splits.computeIfAbsent(sid, k -> new byte[fsc][]);
                if (parts.length != sc) continue;
                parts[sx] = body;
                boolean done = true; int total = 0;
                for (byte[] p : parts) { if (p == null) { done = false; break; } total += p.length; }
                if (!done) continue;
                splits.remove(sid);
                Buf all = new Buf(total);
                for (byte[] p : parts) all.raw(p);
                body = all.bytes();
            }
            deliver(rel, oi, ch, si, body);
        }
    }

    private void deliver(int rel, int oi, int ch, int si, byte[] body) {
        if (rel == 1 || rel == 4) {
            if (si <= highestSequenced[ch]) return;
            highestSequenced[ch] = si;
            handlePacket(body);
            return;
        }
        if (rel == 3 || rel == 7) {
            if (oi < expectOrder[ch]) return;
            if (oi > expectOrder[ch]) { orderWait.get(ch).put(oi, body); return; }
            handlePacket(body);
            expectOrder[ch]++;
            Map<Integer, byte[]> w = orderWait.get(ch);
            byte[] nx;
            while ((nx = w.remove(expectOrder[ch])) != null) { handlePacket(nx); expectOrder[ch]++; }
            return;
        }
        handlePacket(body);
    }

    private void handleAck(Buf b, boolean nack) {
        int count = b.u16();
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < count && b.left() > 0; i++) {
            boolean single = b.u8() != 0;
            int s = b.i24le();
            int e = single ? s : b.i24le();
            if (e - s > 2048) e = s + 2048;
            for (int x = s; x <= e; x++) ids.add(x);
        }
        synchronized (this) {
            for (int x : ids) {
                Sent st = sentReliable.remove(x);
                if (nack && st != null) sendFrames(st.frames, true);
            }
        }
    }

    private void handleOffline(Buf b, int id) {
        b.u8();
        if (id == 0x06 && state == 0) {
            b.skip(16); b.i64(); b.u8();
            int m = b.u16();
            if (m > 400 && m <= 1500) mtu = m;
            synchronized (this) { state = 1; stateTime = 0; }
            L.log("RakNet: ответ 1, MTU " + mtu);
        } else if (id == 0x19) { // несовместимая версия протокола
            int p = b.u8();
            L.log("RakNet: сервер просит протокол " + p);
            if (p != protocol) { protocol = p; protocolForced = true; synchronized (this) { stateTime = 0; } }
        } else if (id == 0x1c) {
            try {
                b.i64(); b.i64(); b.skip(16);
                String s = b.str();
                String[] f = s.split(";");
                String motd = f.length > 1 ? f[1] : s;
                if (pongSeen == null) {
                    L.log("Сервер найден: " + s);
                    if (f.length > 3 && !f[2].equals("45"))
                        L.log("Внимание: у сервера протокол " + f[2] + " (версия " + f[3] + "), а бот умеет только 45 (0.14.0/0.14.1)");
                }
                pongSeen = motd;
            } catch (Exception e) { if (pongSeen == null) { pongSeen = "?"; L.log("Сервер ответил на пинг"); } }
        } else if (id == 0x1a || id == 0x12 || id == 0x14 || id == 0x17) {
            L.log("RakNet: сервер отказал (код 0x" + Integer.toHexString(id) + ")");
        } else if (id == 0x08 && state == 1) {
            synchronized (this) {
                state = 2;
                L.log("RakNet: ответ 2, отправляю запрос соединения");
                queue(new Buf().u8(0x09).i64(guid).i64(now() - startTime).u8(0).bytes(), RELIABLE, 0);
                flush();
            }
        } else {
            L.log("RakNet: пакет 0x" + Integer.toHexString(id) + " (" + b.len + " байт)");
        }
    }

    private void handlePacket(byte[] d) {
        if (d.length == 0) return;
        int id = d[0] & 0xff;
        Buf b = new Buf(d);
        b.u8();
        switch (id) {
            case 0x00: { // пинг от сервера → понг
                long ping = b.i64();
                synchronized (this) { queue(new Buf().u8(0x03).i64(ping).i64(now() - startTime).bytes(), UNRELIABLE, 0); }
                break;
            }
            case 0x03: break; // понг
            case 0x10: { // ConnectionRequestAccepted
                if (state != 2) break;
                long reqTime = 0, accTime = 0;
                try {
                    b.addr(); b.u16();
                    // внутренние адреса: читаем, пока не останется 16 байт времени
                    while (b.left() > 16) b.addr();
                    reqTime = b.i64(); accTime = b.i64();
                } catch (Exception ignored) {}
                byte[] ip = server.getAddress().getAddress();
                if (ip.length != 4) ip = new byte[]{127, 0, 0, 1};
                Buf r = new Buf().u8(0x13).addr4(ip, server.getPort());
                for (int i = 0; i < 10; i++) r.addr4(new byte[]{0, 0, 0, 0}, 0);
                r.i64(accTime).i64(now() - startTime);
                synchronized (this) {
                    queue(r.bytes(), RELIABLE_ORDERED, 0);
                    state = 3;
                    flush();
                }
                L.log("RakNet: соединение установлено");
                L.onConnected();
                break;
            }
            case 0x15: shutdown("Сервер закрыл соединение"); break;
            default:
                if (state == 3) L.onPayload(d);
        }
    }

    // ---------------- отправка ----------------
    public synchronized void send(byte[] payload) {
        if (state != 3) return;
        queue(payload, RELIABLE_ORDERED, 0);
    }

    private void queue(byte[] payload, int rel, int ch) {
        int maxBody = mtu - 28 - 4 - 23 - 16;
        boolean reliable = rel == 2 || rel == 3;
        int oi = rel == 3 ? orderIndex[ch]++ : 0;
        if (payload.length <= maxBody) {
            outFrames.add(frame(payload, 0, payload.length, rel, oi, ch, false, 0, 0, 0));
            return;
        }
        int count = (payload.length + maxBody - 1) / maxBody;
        int sid = (splitId++) & 0xffff;
        for (int i = 0; i < count; i++) {
            int off = i * maxBody, n = Math.min(maxBody, payload.length - off);
            // большие пакеты всегда отправляем надёжно
            outFrames.add(frame(payload, off, n, reliable ? rel : RELIABLE, oi, ch, true, count, sid, i));
            flush();
        }
    }

    private byte[] frame(byte[] d, int off, int n, int rel, int oi, int ch, boolean split, int sc, int sid, int sx) {
        Buf f = new Buf(n + 24);
        f.u8((rel << 5) | (split ? 0x10 : 0));
        f.i16(n << 3);
        if (rel == 2 || rel == 3) f.i24le(msgIndex++);
        if (rel == 3) { f.i24le(oi); f.u8(ch); }
        if (split) { f.i32(sc); f.i16(sid); f.i32(sx); }
        f.raw(d, off, n);
        return f.bytes();
    }

    private void flush() {
        if (outFrames.isEmpty()) return;
        List<byte[]> cur = new ArrayList<>();
        int size = 4;
        for (byte[] fr : outFrames) {
            if (size + fr.length > mtu - 28 && !cur.isEmpty()) { sendFrames(cur, false); cur = new ArrayList<>(); size = 4; }
            cur.add(fr); size += fr.length;
        }
        if (!cur.isEmpty()) sendFrames(cur, false);
        outFrames.clear();
    }

    private void sendFrames(List<byte[]> frames, boolean resend) {
        int seq = sendSeq++ & 0xffffff;
        Buf b = new Buf(mtu).u8(0x84).i24le(seq);
        boolean anyReliable = false;
        for (byte[] f : frames) {
            b.raw(f);
            int rel = (f[0] & 0xe0) >> 5;
            if (rel == 2 || rel == 3) anyReliable = true;
        }
        rawSend(b);
        if (anyReliable) sentReliable.put(seq, new Sent(frames, now()));
    }

    private void flushAcks() {
        if (!ackQueue.isEmpty()) { rawSend(ackPacket(0xc0, ackQueue)); ackQueue.clear(); }
        if (!nackQueue.isEmpty()) { rawSend(ackPacket(0xa0, nackQueue)); nackQueue.clear(); }
    }

    private static Buf ackPacket(int id, TreeSet<Integer> set) {
        Buf rec = new Buf();
        int records = 0;
        Integer start = null, last = null;
        for (int x : set) {
            if (start == null) { start = last = x; continue; }
            if (x == last + 1) { last = x; continue; }
            records += writeRange(rec, start, last);
            start = last = x;
        }
        if (start != null) records += writeRange(rec, start, last);
        Buf b = new Buf().u8(id).i16(records);
        b.raw(rec.b, 0, rec.len);
        return b;
    }

    private static int writeRange(Buf r, int s, int e) {
        if (s == e) r.u8(1).i24le(s); else r.u8(0).i24le(s).i24le(e);
        return 1;
    }
}
