package com.nova.engine.net;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * RakNet client as the 0.14.3 RakPeer speaks it (libminecraftpe RakNet::RakPeer::RunUpdateCycle):
 * ID_OPEN_CONNECTION_REQUEST_1 = 0x05 + OFFLINE_MESSAGE_DATA_ID + RAKNET_PROTOCOL_VERSION 7, padded to the MTU - 28;
 * reply 1 (0x06), request 2 (0x07, server address, mtu, client guid), reply 2 (0x08); then datagrams 0x80..0x8f
 * with frames, ACK 0xc0 / NACK 0xa0, ID_CONNECTION_REQUEST 0x09 -> ID_CONNECTION_REQUEST_ACCEPTED 0x10 ->
 * ID_NEW_INCOMING_CONNECTION 0x13, connected ping 0x00 / pong 0x03, disconnect 0x15.
 * Game packets go RELIABLE_ORDERED on channel 0, split when larger than the MTU.
 * Unconnected ping 0x01 / pong 0x1c for the server list (MOTD "MCPE;name;protocol;version;online;max").
 */
public final class RakNet {
    static final byte[] MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};
    static final int PROTOCOL = 7;

    // ------------------------------------------------------------------ server list ping
    /** unconnected ping; returns the MOTD string or null (timeout ms) */
    public static String ping(String host, int port, int timeout) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(timeout);
            Buf b = new Buf();
            b.u8(0x01); b.i64(System.currentTimeMillis()); b.raw(MAGIC); b.i64(0);
            InetAddress a = InetAddress.getByName(host);
            s.send(new DatagramPacket(b.a, b.n, a, port));
            byte[] r = new byte[2048];
            DatagramPacket p = new DatagramPacket(r, r.length);
            long end = System.currentTimeMillis() + timeout;
            while (System.currentTimeMillis() < end) {
                s.receive(p);
                if (p.getLength() > 35 && (r[0] & 255) == 0x1c) {
                    Rd d = new Rd(r, 0, p.getLength());
                    d.u8(); d.i64(); d.i64(); d.skip(16);
                    return d.str();
                }
            }
        } catch (Exception ignored) {
        } finally { if (s != null) s.close(); }
        return null;
    }

    // ------------------------------------------------------------------ connection
    public interface Listener { void onPacket(byte[] data); void onDisconnect(String reason); }

    private final DatagramSocket sock;
    private final InetSocketAddress server;
    private final long guid = new java.util.Random().nextLong();
    private int mtu = 1400;
    private volatile boolean running = true, connected;
    private final Listener listener;
    private final ConcurrentLinkedQueue<byte[]> outQueue = new ConcurrentLinkedQueue<byte[]>();
    private final ConcurrentLinkedQueue<Object[]> outRaw = new ConcurrentLinkedQueue<Object[]>();

    // send state
    private int seq, msgIndex, orderIndex, splitId;
    private final Map<Integer, Object[]> unacked = new HashMap<Integer, Object[]>();   // seq -> {bytes, time}
    // receive state
    private final ArrayList<Integer> acks = new ArrayList<Integer>();
    private int expectOrder;
    private boolean orderInit;
    private final Map<Integer, byte[]> orderBuf = new HashMap<Integer, byte[]>();
    private final Map<Integer, byte[][]> splits = new HashMap<Integer, byte[][]>();
    private final java.util.HashSet<Integer> seenMsg = new java.util.HashSet<Integer>();

    public RakNet(String host, int port, Listener l) throws Exception {
        server = new InetSocketAddress(InetAddress.getByName(host), port);
        listener = l;
        sock = new DatagramSocket();
        sock.setSoTimeout(10);
        Thread t = new Thread(new Runnable() { public void run() { loop(); } }, "raknet");
        t.setDaemon(true);
        t.start();
    }

    public boolean connected() { return connected; }

    /** a game packet (id + body) */
    public void send(byte[] packet) { outQueue.add(packet); }

    public void close() {
        if (connected) sendFrame(new byte[]{0x15}, 3);
        running = false;
    }

    private void raw(byte[] b, int n) {
        try { sock.send(new DatagramPacket(b, n, server)); } catch (Exception ignored) { }
    }

    private void loop() {
        byte[] r = new byte[4096];
        DatagramPacket p = new DatagramPacket(r, r.length);
        try {
            // offline handshake: MTU 1492, 1200, 576 like RakPeer's mtuSizes
            int[] mtus = {1492, 1200, 576};
            int state = 0, tries = 0;
            long last = 0;
            while (running && !connected) {
                long now = System.currentTimeMillis();
                if (now - last > 500) {
                    last = now;
                    if (tries++ > 24) { listener.onDisconnect("disconnectionScreen.noReason"); running = false; return; }
                    Buf b = new Buf();
                    if (state == 0) {
                        int m = mtus[Math.min(tries / 4, 2)];
                        b.u8(0x05); b.raw(MAGIC); b.u8(PROTOCOL);
                        while (b.n < m - 28) b.u8(0);
                    } else if (state == 1) {
                        b.u8(0x07); b.raw(MAGIC); addr(b, server); b.i16(mtu); b.i64(guid);
                    } else {
                        // connection request inside a reliable frame
                        Buf c = new Buf(); c.u8(0x09); c.i64(guid); c.i64(now); c.u8(0);
                        sendFrame(c.copy(), 2);
                        flushAcks();
                        b = null;
                    }
                    if (b != null) raw(b.a, b.n);
                }
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                int id = r[0] & 255, n = p.getLength();
                if (id == 0x06 && state == 0) {
                    Rd d = new Rd(r, 0, n); d.u8(); d.skip(16); d.i64(); d.u8(); mtu = d.u16();
                    state = 1; last = 0;
                } else if (id == 0x08 && state == 1) {
                    state = 2; last = 0;
                } else if (id >= 0x80 && id <= 0x8f) {
                    datagram(r, n);
                }
            }
            long lastPing = 0;
            while (running) {
                long now = System.currentTimeMillis();
                byte[] g;
                while ((g = outQueue.poll()) != null) sendFrame(g, 3);
                if (now - lastPing > 2000) { lastPing = now; Buf b = new Buf(); b.u8(0x00); b.i64(now); sendFrame(b.copy(), 0); }
                resend(now);
                flushAcks();
                try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                int id = r[0] & 255;
                if (id >= 0x80 && id <= 0x8f) datagram(r, p.getLength());
            }
        } catch (Exception e) {
            listener.onDisconnect("disconnectionScreen.noReason");
        } finally { sock.close(); }
    }

    private static void addr(Buf b, InetSocketAddress a) {
        byte[] ip = a.getAddress().getAddress();
        b.u8(4);
        for (int i = 0; i < 4; i++) b.u8(~ip[i] & 255);
        b.i16(a.getPort());
    }

    // ------------------------------------------------------------------ frames out
    /** reliability: 0 unreliable, 2 reliable, 3 reliable ordered */
    private synchronized void sendFrame(byte[] body, int rel) {
        int max = mtu - 60;
        // reliable message indexes must have no holes (the server's reliable window): unreliable frames take none
        if (body.length <= max) { frameOut(body, 0, body.length, rel, -1, 0, 0, rel >= 2 ? msgIndex++ : 0, rel == 3 ? orderIndex : 0); }
        else {
            int count = (body.length + max - 1) / max, sid = splitId++ & 0xffff, oi = orderIndex;
            for (int i = 0; i < count; i++) {
                int off = i * max, len = Math.min(max, body.length - off);
                frameOut(body, off, len, rel, count, sid, i, rel >= 2 ? msgIndex++ : 0, oi);
            }
        }
        if (rel == 3) orderIndex++;
    }

    private void frameOut(byte[] body, int off, int len, int rel, int splitCount, int sid, int sidx, int mi, int oi) {
        Buf b = new Buf();
        b.u8(0x84); b.i24le(seq);
        b.u8(rel << 5 | (splitCount > 0 ? 0x10 : 0));
        b.i16(len * 8);
        if (rel >= 2) b.i24le(mi);
        if (rel == 3) { b.i24le(oi); b.u8(0); }
        if (splitCount > 0) { b.i32(splitCount); b.i16(sid); b.i32(sidx); }
        b.raw(body, off, len);
        byte[] dg = b.copy();
        raw(dg, dg.length);
        if (rel >= 2) unacked.put(seq, new Object[]{dg, System.currentTimeMillis()});
        seq++;
    }

    private synchronized void resend(long now) {
        for (Map.Entry<Integer, Object[]> e : unacked.entrySet()) {
            if (now - (Long) e.getValue()[1] > 800) {
                byte[] dg = (byte[]) e.getValue()[0];
                raw(dg, dg.length);
                e.getValue()[1] = now;
            }
        }
    }

    // ------------------------------------------------------------------ frames in
    private void datagram(byte[] r, int n) {
        int id = r[0] & 255;
        if (id == 0xc0 || id == 0xa0) { ackIn(r, n, id == 0xc0); return; }
        Rd d = new Rd(r, 0, n);
        d.u8();
        int s = d.i24le();
        synchronized (acks) { acks.add(s); }
        while (d.left() > 0) {
            int flags = d.u8(), bits = d.u16(), len = (bits + 7) >> 3;
            int rel = flags >> 5;
            boolean split = (flags & 0x10) != 0;
            int mi = -1, oi = 0;
            if (rel == 2 || rel == 3 || rel == 4 || rel == 6 || rel == 7) mi = d.i24le();
            if (rel == 1 || rel == 3 || rel == 4 || rel == 7) { oi = d.i24le(); d.u8(); }
            int sc = 0, sid = 0, sidx = 0;
            if (split) { sc = d.i32(); sid = d.u16(); sidx = d.i32(); }
            byte[] body = d.bytes(len);
            if (mi >= 0) { if (!seenMsg.add(mi)) continue; }
            if (split) {
                byte[][] parts = splits.get(sid);
                if (parts == null) { parts = new byte[sc][]; splits.put(sid, parts); }
                if (sidx < parts.length) parts[sidx] = body;
                boolean all = true; int total = 0;
                for (byte[] q : parts) { if (q == null) { all = false; break; } total += q.length; }
                if (!all) continue;
                splits.remove(sid);
                body = new byte[total];
                int o = 0;
                for (byte[] q : parts) { System.arraycopy(q, 0, body, o, q.length); o += q.length; }
            }
            if (rel == 3 || rel == 4 || rel == 7) {
                if (!orderInit) { orderInit = true; expectOrder = oi; }      // servers differ in the first index
                if (oi < expectOrder) continue;
                orderBuf.put(oi, body);
                byte[] q;
                while ((q = orderBuf.remove(expectOrder)) != null) { expectOrder++; deliver(q); }
            } else deliver(body);
        }
    }

    private void ackIn(byte[] r, int n, boolean ack) {
        Rd d = new Rd(r, 0, n);
        d.u8();
        int count = d.u16();
        for (int i = 0; i < count && d.left() > 0; i++) {
            boolean single = d.u8() != 0;
            int a = d.i24le(), b = single ? a : d.i24le();
            synchronized (this) {
                for (int s = a; s <= b; s++) {
                    if (ack) unacked.remove(s);
                    else { Object[] e = unacked.get(s); if (e != null) { byte[] dg = (byte[]) e[0]; raw(dg, dg.length); e[1] = System.currentTimeMillis(); } }
                }
            }
        }
    }

    private void flushAcks() {
        Integer[] list;
        synchronized (acks) {
            if (acks.isEmpty()) return;
            list = acks.toArray(new Integer[0]);
            acks.clear();
        }
        java.util.Arrays.sort(list);
        Buf b = new Buf();
        b.u8(0xc0);
        Buf recs = new Buf();
        int count = 0;
        for (int i = 0; i < list.length; ) {
            int a = list[i], e = a;
            while (i + 1 < list.length && list[i + 1] <= e + 1) { i++; e = Math.max(e, list[i]); }
            i++;
            if (a == e) { recs.u8(1); recs.i24le(a); } else { recs.u8(0); recs.i24le(a); recs.i24le(e); }
            count++;
        }
        b.i16(count); b.raw(recs.a, 0, recs.n);
        raw(b.a, b.n);
    }

    private void deliver(byte[] b) {
        if (b.length == 0) return;
        int id = b[0] & 255;
        if (id == 0x10) {                                    // ID_CONNECTION_REQUEST_ACCEPTED
            Buf c = new Buf();
            c.u8(0x13); addr(c, server);
            for (int i = 0; i < 10; i++) { c.u8(4); c.i32(0); c.i16(0); }
            c.i64(System.currentTimeMillis()); c.i64(System.currentTimeMillis());
            sendFrame(c.copy(), 3);
            connected = true;
        } else if (id == 0x00) {                             // connected ping -> pong
            Rd d = new Rd(b, 0, b.length); d.u8();
            Buf c = new Buf(); c.u8(0x03); c.i64(d.i64()); c.i64(System.currentTimeMillis());
            sendFrame(c.copy(), 0);
        } else if (id == 0x15) {
            running = false;
            listener.onDisconnect("disconnectionScreen.disconnected");
        } else if (id >= 0x8e) listener.onPacket(b);
    }

    // ------------------------------------------------------------------ byte helpers (network order)
    public static final class Buf {
        public byte[] a = new byte[256];
        public int n;

        void need(int k) { if (n + k > a.length) a = java.util.Arrays.copyOf(a, Math.max(a.length * 2, n + k)); }
        public Buf u8(int v) { need(1); a[n++] = (byte) v; return this; }
        public Buf i16(int v) { need(2); a[n++] = (byte) (v >> 8); a[n++] = (byte) v; return this; }
        public Buf i24le(int v) { need(3); a[n++] = (byte) v; a[n++] = (byte) (v >> 8); a[n++] = (byte) (v >> 16); return this; }
        public Buf i32(int v) { need(4); for (int s = 24; s >= 0; s -= 8) a[n++] = (byte) (v >> s); return this; }
        public Buf i64(long v) { need(8); for (int s = 56; s >= 0; s -= 8) a[n++] = (byte) (v >> s); return this; }
        public Buf f32(float v) { return i32(Float.floatToIntBits(v)); }
        public Buf raw(byte[] b) { return raw(b, 0, b.length); }
        public Buf raw(byte[] b, int o, int l) { need(l); System.arraycopy(b, o, a, n, l); n += l; return this; }
        /** PacketUtil::writeString: u16 length + bytes */
        public Buf str(String s) {
            byte[] b;
            try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = new byte[0]; }
            i16(b.length); return raw(b);
        }
        public byte[] copy() { return java.util.Arrays.copyOf(a, n); }
    }

    public static final class Rd {
        public final byte[] a; public int p; public final int end;
        public Rd(byte[] a, int p, int end) { this.a = a; this.p = p; this.end = end; }
        public int left() { return end - p; }
        public int u8() { return a[p++] & 255; }
        public int u16() { return (u8() << 8) | u8(); }
        public int i24le() { return u8() | u8() << 8 | u8() << 16; }
        public int i32() { return u8() << 24 | u8() << 16 | u8() << 8 | u8(); }
        public long i64() { return ((long) i32() << 32) | (i32() & 0xffffffffL); }
        public float f32() { return Float.intBitsToFloat(i32()); }
        public void skip(int k) { p += k; }
        public byte[] bytes(int k) { byte[] b = java.util.Arrays.copyOfRange(a, p, p + k); p += k; return b; }
        public String str() {
            int l = u16();
            String s;
            try { s = new String(a, p, Math.min(l, end - p), "UTF-8"); } catch (Exception e) { s = ""; }
            p += l; return s;
        }
    }
}
