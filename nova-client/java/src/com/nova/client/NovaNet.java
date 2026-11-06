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
 *   FE FD 4E 01 04 | link (4)                   punch (opens the NAT path), answered with 05
 *   FE FD 4E 01 03                              from the server: "ask now" (a join or an invite is waiting)
 * Host side: every link gets a local socket that talks to the game's own server (127.0.0.1:game port).
 * Join side: a local socket on 127.0.0.1 the game connects to (MinecraftClient::joinMultiplayer, or a LAN entry).
 * The game itself never knows: it sees a LAN game. No Android code here (the desktop test runs it).
 */
public final class NovaNet {
    public interface Listener {
        void invites(List<Map<String, Object>> invites);      // from the presence answer
        void incomingRequests(int n);
        void log(String s);
    }

    static final byte[] MAGIC = {(byte) 0xfe, (byte) 0xfd, 0x4e, 1};

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
    /** the world this phone hosts for friends, or null: name, mode ("friends" / "invite"), max */
    public volatile Map<String, Object> world;
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
                } else if ((type == 0x02 || type == 0x04 || type == 0x05) && n >= 9) {
                    int id = ((buf[5] & 0xff) << 24) | ((buf[6] & 0xff) << 16) | ((buf[7] & 0xff) << 8) | (buf[8] & 0xff);
                    Link l = links.get(id);
                    if (l == null) continue;
                    if (!fromServer) {
                        if (l.peer == null || !from.getAddress().equals(l.peer.getAddress())) continue;
                        if (!allowDirect) continue;
                        l.peer = from;                                 // the NAT may have picked another port
                        l.lastPeer = System.currentTimeMillis();
                        if (type == 0x04) send(pkt(0x05, id, null, 0, 0), from);
                    }
                    l.lastUse = System.currentTimeMillis();
                    if (type == 0x02) l.toGame(buf, 9, n - 9);
                }
            } catch (Exception e) { log("rx: " + e); }
        }
    }

    static byte[] pkt(int type, int link, byte[] data, int off, int len) {
        byte[] p = new byte[9 + len];
        System.arraycopy(MAGIC, 0, p, 0, 4);
        p[4] = (byte) type;
        p[5] = (byte) (link >>> 24); p[6] = (byte) (link >>> 16); p[7] = (byte) (link >>> 8); p[8] = (byte) link;
        if (len > 0) System.arraycopy(data, off, p, 9, len);
        return p;
    }

    private void send(byte[] p, InetSocketAddress to) {
        try { tun.send(new DatagramPacket(p, p.length, to)); } catch (Exception ignored) { }
    }

    /** game packet of a link -> the peer directly when that path works, else through the server */
    void toPeer(Link l, byte[] data, int off, int len) {
        byte[] p = pkt(0x02, l.id, data, off, len);
        boolean direct = allowDirect && l.peer != null && System.currentTimeMillis() - l.lastPeer < 6000;
        try { send(p, direct ? l.peer : server()); } catch (Exception ignored) { }
        l.lastUse = System.currentTimeMillis();
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
                        boolean young = now - l.created < 10000;
                        boolean lost = now - l.lastPeer > 3000;
                        if (l.peer != null && allowDirect && lost && (young || now - l.lastPunch > 2000)) {
                            l.lastPunch = now;
                            send(pkt(0x04, l.id, null, 0, 0), l.peer);
                        }
                        if (now - l.lastUse > 60000) {                 // nobody plays any more
                            links.remove(l.id);
                            l.close();
                            final int id = l.id;
                            Map<String, Object> f = new LinkedHashMap<String, Object>();
                            f.put("link", (double) id);
                            try { api.op("leave", null, f); } catch (Exception ignored) { }
                        }
                    }
                }
                Thread.sleep(50);
            } catch (Exception e) {
                log("tick: " + e);
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
            }
        }
    }

    /** tell the server we are here (and what we host); take invites and new players of our world */
    @SuppressWarnings("unchecked")
    public void presence() {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        Map<String, Object> w = world;
        if (w != null) {
            Map<String, Object> ww = new LinkedHashMap<String, Object>(w);
            int players = 1;
            for (Link l : links.values()) if (l.host && System.currentTimeMillis() - l.lastUse < 15000) players++;
            ww.put("players", (double) players);
            f.put("world", ww);
        }
        List<Object> keep = new ArrayList<Object>();
        for (Link l : links.values()) keep.add((double) l.id);
        f.put("keep", keep);
        AccountClient.Result r = api.op("presence", null, f);
        if (!r.ok) return;
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
                    Link l = new Link(id, true, addr(String.valueOf(m.get("peer"))));
                    links.put(id, l);
                    l.openHost(gamePort);
                    log("player " + m.get("tag") + " joins (link " + id + ")");
                } catch (Exception e) { log("host link: " + e); }
            }
        }
    }

    static InetSocketAddress addr(String s) throws Exception {
        int c = s.lastIndexOf(':');
        return new InetSocketAddress(InetAddress.getByName(s.substring(0, c)), Integer.parseInt(s.substring(c + 1)));
    }

    /** join a friend's world: returns the local port the game must connect to (127.0.0.1), or a negative error */
    public AccountClient.Result join(String tag, int[] portOut) {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("tag", tag);
        AccountClient.Result r = api.op("join", null, f);
        if (!r.ok) return r;
        try {
            int id = (int) (double) (Double) r.data.get("link");
            Link l = new Link(id, false, addr(r.str("peer")));
            l.openJoin();
            links.put(id, l);
            portOut[0] = l.local.getLocalPort();
        } catch (Exception e) {
            return new AccountClient.Result(false, "net", "Не удалось подключиться", null);
        }
        return r;
    }

    /** the host closed its world: drop the players' links */
    /** "link direct/relay" lines for the debug screen and tests */
    public String linkInfo() {
        StringBuilder b = new StringBuilder();
        for (Link l : links.values()) b.append(l.id).append(l.host ? " host " : " join ").append(l.direct() ? "direct" : "relay").append('\n');
        return b.toString();
    }

    public int linkCount() { return links.size(); }

    public void stopHosting() {
        world = null;
        for (Link l : links.values()) if (l.host) { links.remove(l.id); l.close(); }
        nextPresence = 0;
    }

    void log(String s) { if (listener != null) listener.log(s); }

    // ------------------------------------------------------------------ one game connection
    final class Link {
        final int id; final boolean host; volatile InetSocketAddress peer;
        volatile long lastPeer, lastPunch, lastUse = System.currentTimeMillis();
        final long created = System.currentTimeMillis();
        DatagramSocket local;
        volatile InetSocketAddress game;       // join: where the game sends from; host: the game's server

        Link(int id, boolean host, InetSocketAddress peer) { this.id = id; this.host = host; this.peer = peer; }

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
                        toPeer(Link.this, buf, 0, in.getLength());
                    }
                }
            }, "nova-link-" + id);
            t.setDaemon(true);
            t.start();
        }

        /** peer -> game */
        void toGame(byte[] b, int off, int len) {
            InetSocketAddress g = game;
            if (g == null || local == null) return;
            try { local.send(new DatagramPacket(b, off, len, g)); } catch (Exception ignored) { }
        }

        void close() { if (local != null) local.close(); }

        public boolean direct() { return System.currentTimeMillis() - lastPeer < 6000; }
    }
}
