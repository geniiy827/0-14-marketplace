package com.nova.client;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Playing with friends over the internet as if on the same Wi-Fi.
 *
 * One UDP "tunnel" socket talks to the NovaAccounts plugin on the NOVA server (presence every few seconds, so the
 * server knows this socket's public address) and to the other phone of a game:
 *   FE FD 4E 01 02 | link (4) | game packet     data (straight to the peer, or through the server when direct fails)
 *   FE FD 4E 01 04 | link (4)                   punch (opens the NAT path), answered with 05 (pong)
 *   FE FD 4E 01 06 | link | file (1) | chunk (4)                  file request (mods of the host)
 *   FE FD 4E 01 07 | link | file (1) | chunk (4) | count (4) | data   file data
 *   FE FD 4E 01 03                              from the server: "ask now" (a join or an invite is waiting)
 * The direct path is used only while pongs come back: both directions work. A router that lets packets out but
 * not in (or the other way round) makes the link use the server, never lose packets.
 * Host side: every link gets a local socket that talks to the game's own server (127.0.0.1:game port).
 * Join side: a local socket on 127.0.0.1 the game connects to (MinecraftClient::joinMultiplayer).
 * RakNet asks for a 1492-byte MTU; with our 9 bytes on top that is fragmented on the internet, so the MTU the two
 * games agree on is clamped to 1400 in the RakNet handshake packets.
 * No Android code here (the desktop test runs it).
 */
public final class NovaNet {
    public interface Listener {
        void invites(List<Map<String, Object>> invites);      // from the presence answer
        void incomingRequests(int n);
        void log(String s);
    }
    /** host: the files friends may download (the mods of the open world); null bytes = no such file */
    public interface Files { byte[] file(int index); }
    public interface DownloadProgress { void progress(long done, long total); }

    static final byte[] MAGIC = {(byte) 0xfe, (byte) 0xfd, 0x4e, 1};
    static final int MTU = 1400, CHUNK = 1100;

    final AccountClient api;
    private final String host; private final int port;
    private volatile InetSocketAddress server;
    private DatagramSocket tun;
    private final ArrayBlockingQueue<byte[]> answers = new ArrayBlockingQueue<byte[]>(8);
    private final Object exchangeLock = new Object();
    private volatile boolean running;
    private volatile long nextPresence;
    final Map<Integer, Link> links = new ConcurrentHashMap<Integer, Link>();
    private final Listener listener;
    /** the world this phone hosts for friends, or null: name, mode ("friends" / "invite"), max, mods, msig */
    public volatile Map<String, Object> world;
    public volatile Files files;
    public volatile int gamePort = 19132;
    public volatile boolean allowDirect = true;     // tests: force the relay

    public NovaNet(AccountClient api, String host, int port, Listener l) {
        this.api = api; this.host = host; this.port = port; this.listener = l;
    }

    // ------------------------------------------------------------------ lifecycle
    public synchronized void start() throws Exception {
        if (running) return;
        tun = new DatagramSocket();
        running = true;
        api.setTransport(new AccountClient.Transport() {
            public byte[] exchange(byte[] p, int tries, int timeoutMs) throws Exception { return viaTunnel(p, tries, timeoutMs); }
        });
        Thread rx = new Thread(new Runnable() { public void run() { receiveLoop(); } }, "nova-tun-rx");
        rx.setDaemon(true); rx.start();
        Thread tk = new Thread(new Runnable() { public void run() { tickLoop(); } }, "nova-tun-tick");
        tk.setDaemon(true); tk.start();
        nextPresence = 0;
    }

    public synchronized void stop() {
        running = false;
        for (Link l : links.values()) l.close();
        links.clear();
        if (tun != null) tun.close();
    }

    public boolean isRunning() { return running; }

    private InetSocketAddress server() throws Exception {
        InetSocketAddress s = server;
        if (s == null || s.isUnresolved()) { s = new InetSocketAddress(InetAddress.getByName(host), port); server = s; }
        return s;
    }

    /** AccountClient requests through the tunnel socket (the server learns this socket's address) */
    private byte[] viaTunnel(byte[] p, int tries, int timeoutMs) throws Exception {
        synchronized (exchangeLock) {
            answers.clear();
            InetSocketAddress s = server();
            for (int t = 0; t < tries; t++) {
                tun.send(new DatagramPacket(p, p.length, s));
                byte[] a = answers.poll(timeoutMs / tries + 200, TimeUnit.MILLISECONDS);
                if (a != null) return a;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ packets
    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            try { tun.receive(in); } catch (Exception e) { if (!running) return; continue; }
            int n = in.getLength();
            if (n < 5 || buf[0] != MAGIC[0] || buf[1] != MAGIC[1] || buf[2] != MAGIC[2] || buf[3] != MAGIC[3]) continue;
            int type = buf[4] & 0xff;
            InetSocketAddress from = (InetSocketAddress) in.getSocketAddress();
            boolean fromServer = from.equals(server);
            try {
                if (type == 0x80 || type == 0x81) {
                    if (fromServer) { byte[] a = new byte[n]; System.arraycopy(buf, 0, a, 0, n); answers.offer(a); }
                } else if (type == 0x03) {
                    if (fromServer) nextPresence = 0;
                } else if (type >= 0x02 && type <= 0x07 && n >= 9) {
                    int id = int32(buf, 5);
                    Link l = links.get(id);
                    if (l == null) continue;
                    long now = System.currentTimeMillis();
                    if (!fromServer) {
                        if (l.peer == null || !from.getAddress().equals(l.peer.getAddress())) continue;
                        if (!allowDirect) continue;
                        if (type == 0x04) { l.peer = from; send(pkt(0x05, id, null, 0, 0), from); continue; }
                        if (type == 0x05) { l.peer = from; l.lastPong = now; continue; }
                    }
                    l.lastIn = now;
                    if (type == 0x02) l.toGame(buf, 9, n - 9);
                    else if (type == 0x06 && l.host && n >= 14) serveChunk(l, buf[9] & 0xff, int32(buf, 10));
                    else if (type == 0x07 && !l.host && n >= 18) l.chunk(buf[9] & 0xff, int32(buf, 10), int32(buf, 14), buf, 18, n - 18);
                }
            } catch (Exception e) { log("rx: " + e); }
        }
    }

    static int int32(byte[] b, int o) {
        return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
    }
    static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24); b[o + 1] = (byte) (v >>> 16); b[o + 2] = (byte) (v >>> 8); b[o + 3] = (byte) v;
    }

    static byte[] pkt(int type, int link, byte[] data, int off, int len) {
        byte[] p = new byte[9 + len];
        System.arraycopy(MAGIC, 0, p, 0, 4);
        p[4] = (byte) type;
        put32(p, 5, link);
        if (len > 0) System.arraycopy(data, off, p, 9, len);
        return p;
    }

    private void send(byte[] p, InetSocketAddress to) {
        try { tun.send(new DatagramPacket(p, p.length, to)); } catch (Exception ignored) { }
    }

    /** a packet of a link -> the peer directly while that path answers, else through the server */
    private void toPeerPacket(Link l, byte[] p) {
        try { send(p, l.direct() ? l.peer : server()); } catch (Exception ignored) { }
    }

    /** game packet of a link -> the peer */
    void toPeer(Link l, byte[] data, int off, int len) {
        clampMtu(data, off, len);
        if (len > MTU - 28 && data[off] == 0x05 && offline(data, off, len)) len = MTU - 28;   // OpenConnectionRequest1: its length is the MTU
        toPeerPacket(l, pkt(0x02, l.id, data, off, len));
        l.lastOut = System.currentTimeMillis();
    }

    private static final byte[] RAKNET_MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    static boolean offline(byte[] b, int off, int len) {
        if (len < 17) return false;
        for (int i = 0; i < 16; i++) if (b[off + 1 + i] != RAKNET_MAGIC[i]) return false;
        return true;
    }

    /** RakNet offline handshake: the MTU fields of OpenConnectionReply1 / Request2 / Reply2 at most MTU */
    static void clampMtu(byte[] b, int off, int len) {
        if (len < 17) return;
        int id = b[off] & 0xff;
        if (id < 0x05 || id > 0x08 || !offline(b, off, len)) return;
        int at = id == 0x06 && len == 28 ? 26 : id == 0x07 && len == 34 ? 24 : id == 0x08 && len == 35 ? 32 : -1;
        if (at < 0) return;
        int mtu = ((b[off + at] & 0xff) << 8) | (b[off + at + 1] & 0xff);
        if (mtu > MTU) { b[off + at] = (byte) (MTU >> 8); b[off + at + 1] = (byte) MTU; }
    }

    // ------------------------------------------------------------------ periodic work
    private void tickLoop() {
        long lastPunch = 0;
        while (running) {
            long now = System.currentTimeMillis();
            try {
                if (now >= nextPresence && api.token != null && api.token.length() > 0) {
                    nextPresence = now + (world != null || !links.isEmpty() ? 3000 : 15000);
                    presence();
                }
                if (now - lastPunch >= 250) {
                    lastPunch = now;
                    for (Link l : links.values()) {
                        boolean young = now - l.created < 6000;
                        if (l.peer != null && allowDirect && (young || now - l.lastPunch >= 1000)) {
                            l.lastPunch = now;
                            send(pkt(0x04, l.id, null, 0, 0), l.peer);
                        }
                        if (l.gone(now)) drop(l);
                        else if (l.dl != null) l.downloadTick(now);
                    }
                }
                Thread.sleep(50);
            } catch (Exception e) {
                log("tick: " + e);
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void drop(Link l) {
        if (links.remove(l.id) == null) return;
        l.close();
        log("link " + l.id + " closed");
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("link", (double) l.id);
        try { api.op("leave", null, f); } catch (Exception ignored) { }
        nextPresence = 0;
    }

    /** players in the world this phone hosts: itself + links whose game still talks */
    public int players() {
        int n = 1;
        long now = System.currentTimeMillis();
        for (Link l : links.values()) if (l.host && !l.files && now - l.lastIn < 8000) n++;
        return n;
    }

    /** tell the server we are here (and what we host); take invites and new players of our world */
    @SuppressWarnings("unchecked")
    public void presence() {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        Map<String, Object> w = world;
        if (w != null) {
            Map<String, Object> ww = new LinkedHashMap<String, Object>(w);
            ww.put("players", (double) players());
            f.put("world", ww);
        }
        List<Object> keep = new ArrayList<Object>();
        for (Link l : links.values()) keep.add((double) l.id);
        f.put("keep", keep);
        AccountClient.Result r = api.op("presence", null, f);
        if (!r.ok) { if (!AccountClient.NET.equals(r.err)) log("presence: " + r.err + " " + r.msg); return; }
        Object inv = r.data.get("invites");
        if (inv instanceof List && listener != null) listener.invites((List<Map<String, Object>>) inv);
        Object inc = r.data.get("incoming");
        if (inc instanceof Double && listener != null) listener.incomingRequests((int) (double) (Double) inc);
        Object ls = r.data.get("links");
        if (ls instanceof List && world != null) {
            for (Object o : (List<Object>) ls) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                int id = (int) (double) (Double) m.get("id");
                if (links.containsKey(id)) continue;
                try {
                    boolean fl = Boolean.TRUE.equals(m.get("files"));
                    Link l = new Link(id, true, fl, addr(String.valueOf(m.get("peer"))));
                    if (!fl) l.openHost(gamePort);
                    links.put(id, l);
                    log((fl ? "mods for " : "player ") + m.get("tag") + " (link " + id + ")");
                } catch (Exception e) { log("host link: " + e); }
            }
        }
    }

    static InetSocketAddress addr(String s) throws Exception {
        int c = s.lastIndexOf(':');
        return new InetSocketAddress(InetAddress.getByName(s.substring(0, c)), Integer.parseInt(s.substring(c + 1)));
    }

    /** join a friend's world: portOut[0] = the local port the game must connect to (127.0.0.1) */
    public AccountClient.Result join(String tag, int[] portOut) {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("tag", tag);
        AccountClient.Result r = api.op("join", null, f);
        if (!r.ok) return r;
        try {
            int id = (int) (double) (Double) r.data.get("link");
            Link l = new Link(id, false, false, addr(r.str("peer")));
            l.openJoin();
            links.put(id, l);
            portOut[0] = l.local.getLocalPort();
        } catch (Exception e) {
            return new AccountClient.Result(false, "net", "Не удалось подключиться", null);
        }
        return r;
    }

    /**
     * Download files of a friend's open world (blocking, a worker thread). Returns the files in the order asked,
     * or throws with a text for the player.
     */
    public byte[][] download(String tag, int[] fileIndexes, DownloadProgress progress) throws Exception {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("tag", tag);
        f.put("files", Boolean.TRUE);
        AccountClient.Result r = api.op("join", null, f);
        if (!r.ok) throw new Exception(r.msg != null ? r.msg : "Нет связи с сервером NOVA");
        int id = (int) (double) (Double) r.data.get("link");
        Link l = new Link(id, false, true, addr(r.str("peer")));
        links.put(id, l);
        try {
            byte[][] out = new byte[fileIndexes.length][];
            for (int i = 0; i < fileIndexes.length; i++) out[i] = l.fetch(fileIndexes[i], progress);
            return out;
        } finally {
            drop(l);
        }
    }

    /** host: one chunk of a file for a friend */
    private void serveChunk(Link l, int index, int chunk) {
        Files fs = files;
        byte[] data = fs == null ? null : fs.file(index);
        int count = data == null ? 0 : (data.length + CHUNK - 1) / CHUNK;
        if (data != null && count == 0) count = 1;
        int off = chunk * CHUNK, len = data == null || chunk < 0 || chunk >= count ? 0 : Math.min(CHUNK, data.length - off);
        byte[] p = new byte[9 + 1 + 4 + 4 + len];
        byte[] head = pkt(0x07, l.id, null, 0, 0);
        System.arraycopy(head, 0, p, 0, 9);
        p[9] = (byte) index;
        put32(p, 10, chunk);
        put32(p, 14, data == null ? -1 : count);
        if (len > 0) System.arraycopy(data, off, p, 18, len);
        toPeerPacket(l, p);
        l.lastOut = System.currentTimeMillis();
    }

    public String linkInfo() {
        StringBuilder b = new StringBuilder();
        for (Link l : links.values()) b.append(l.id).append(l.host ? " host " : " join ").append(l.direct() ? "direct" : "relay").append('\n');
        return b.toString();
    }

    public int linkCount() { return links.size(); }

    public void stopHosting() {
        world = null;
        files = null;
        for (Link l : links.values()) if (l.host) drop(l);
        nextPresence = 0;
    }

    void log(String s) { if (listener != null) listener.log(s); }

    // ------------------------------------------------------------------ one connection
    final class Link {
        final int id; final boolean host, files; volatile InetSocketAddress peer;
        volatile long lastPong, lastPunch, lastIn, lastOut;
        final long created = System.currentTimeMillis();
        DatagramSocket local;
        volatile InetSocketAddress game;       // join: where the game sends from; host: the game's server

        Link(int id, boolean host, boolean files, InetSocketAddress peer) {
            this.id = id; this.host = host; this.files = files; this.peer = peer;
            lastIn = lastOut = created;
        }

        boolean direct() { return allowDirect && peer != null && System.currentTimeMillis() - lastPong < 4000; }

        /** the game left (join side: it stopped sending; host side: the friend's game stopped) */
        boolean gone(long now) {
            if (now - created < 20000) return false;
            if (files) return dl == null && now - lastIn > 30000;
            return host ? now - lastIn > 20000 : now - lastOut > 12000;
        }

        void openHost(int gamePort) throws Exception {
            local = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            game = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), gamePort);
            pump();
        }

        void openJoin() throws Exception {
            local = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            pump();
        }

        /** game -> peer */
        private void pump() {
            Thread t = new Thread(new Runnable() {
                public void run() {
                    byte[] buf = new byte[2048];
                    while (running && !local.isClosed()) {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        try { local.receive(in); } catch (Exception e) { return; }
                        if (!host) game = (InetSocketAddress) in.getSocketAddress();
                        if (in.getLength() > 0) toPeer(Link.this, buf, 0, in.getLength());
                    }
                }
            }, "nova-link-" + id);
            t.setDaemon(true);
            t.start();
        }

        /** peer -> game */
        void toGame(byte[] b, int off, int len) {
            InetSocketAddress g = game;
            if (g == null || local == null || len <= 0) return;
            clampMtu(b, off, len);
            try { local.send(new DatagramPacket(b, off, len, g)); } catch (Exception ignored) { }
        }

        void close() { if (local != null) local.close(); }

        // ---- downloading one file (join side of a files link)
        volatile Dl dl;

        final class Dl {
            final int index; int count = -2; byte[][] parts; int got, next;
            final Map<Integer, Long> asked = new ConcurrentHashMap<Integer, Long>();
            long lastProgress = System.currentTimeMillis();
            Dl(int index) { this.index = index; }
        }

        byte[] fetch(int index, DownloadProgress progress) throws Exception {
            Dl d = new Dl(index);
            dl = d;
            ask(d, 0);
            long reported = 0;
            synchronized (d) {
                while (d.count == -2 || d.got < d.count) {
                    if (d.count == -1) { dl = null; throw new Exception("Хост не отдал файл"); }
                    if (System.currentTimeMillis() - d.lastProgress > 20000) { dl = null; throw new Exception("Хост не отвечает"); }
                    if (!running) throw new Exception("Нет связи");
                    d.wait(200);
                    if (progress != null && d.count > 0 && d.got != reported) {
                        reported = d.got;
                        progress.progress((long) d.got * CHUNK, (long) d.count * CHUNK);
                    }
                }
            }
            dl = null;
            if (d.count < 0 || d.parts == null) throw new Exception("Хост не отдал файл");
            int size = 0;
            for (byte[] p : d.parts) size += p.length;
            byte[] out = new byte[size];
            int o = 0;
            for (byte[] p : d.parts) { System.arraycopy(p, 0, out, o, p.length); o += p.length; }
            return out;
        }

        private void ask(Dl d, int chunk) {
            byte[] p = new byte[14];
            byte[] head = pkt(0x06, id, null, 0, 0);
            System.arraycopy(head, 0, p, 0, 9);
            p[9] = (byte) d.index;
            put32(p, 10, chunk);
            d.asked.put(chunk, System.currentTimeMillis());
            toPeerPacket(this, p);
            lastOut = System.currentTimeMillis();
        }

        void chunk(int index, int chunk, int count, byte[] b, int off, int len) {
            Dl d = dl;
            if (d == null || index != d.index) return;
            synchronized (d) {
                if (count == -1) { d.count = -1; d.notifyAll(); return; }
                if (count <= 0 || count > 200000) return;
                if (d.parts == null) { d.count = count; d.parts = new byte[count][]; }
                if (chunk < 0 || chunk >= d.count || d.parts[chunk] != null) return;
                byte[] part = new byte[len];
                System.arraycopy(b, off, part, 0, len);
                d.parts[chunk] = part;
                d.got++;
                d.asked.remove(chunk);
                d.lastProgress = System.currentTimeMillis();
                d.notifyAll();
            }
            downloadTick(System.currentTimeMillis());
        }

        /** keep 48 chunks asked for; ask again what did not come in 1 s */
        synchronized void downloadTick(long now) {
            Dl d = dl;
            if (d == null) return;
            if (d.parts == null) {
                Long t = d.asked.get(0);
                if (t == null || now - t > 1000) ask(d, 0);
                return;
            }
            for (Map.Entry<Integer, Long> e : d.asked.entrySet())
                if (now - e.getValue() > 1000 && d.parts[e.getKey()] == null) ask(d, e.getKey());
            while (d.asked.size() < 48 && d.next < d.count) {
                int c = d.next++;
                if (d.parts[c] == null && !d.asked.containsKey(c)) ask(d, c);
            }
        }
    }
}
