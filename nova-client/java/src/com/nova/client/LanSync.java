package com.nova.client;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resource pack sync over LAN (beta).
 *
 * Host: while a local world is open (it answers a RakNet ping on 127.0.0.1:19132)
 *       and packs are enabled, broadcast an announce on UDP 19144 every 2 s and serve
 *       the .mcres files over TCP 19145.
 * Guest: listen on UDP 19144; if a host on the LAN uses packs we don't have (or have
 *        disabled), offer to download/enable them, then restart the game so they load.
 *
 * Wire format
 *   announce (UDP, UTF-8 JSON):
 *     {"nova":1,"motd":"...","port":19145,
 *      "packs":[{"uuid":..,"name":..,"version":[1,0,0],"size":123,"sha1":".."}]}
 *   download (TCP): client sends  "NOVA GET <uuid>\n"
 *                   server answers "OK <size> <sha1>\n" + raw .mcres bytes,  or "ERR <reason>\n"
 */
public final class LanSync {
    static final int ANNOUNCE_PORT = 19144;
    static final int FILE_PORT = 19145;
    static final int GAME_PORT = 19132;
    static final String TAG = "NOVA-LAN";
    private static final byte[] MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfe, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    /** Enabled packs at game start (what is actually loaded right now). */
    static volatile List<String> startupOrder;

    private final Activity act;
    private volatile boolean running;
    private ServerSocket server;
    private DatagramSocket listenSock;
    private WifiManager.MulticastLock mlock;
    private final Set<String> declined = Collections.synchronizedSet(new HashSet<String>());
    private volatile boolean dialogOpen;

    public LanSync(Activity a) {
        act = a;
        startupOrder = PackManager.enabledOrder(a);
    }

    public void start() {
        running = true;
        try {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("nova-packs");
                mlock.setReferenceCounted(false);
                mlock.acquire();
            }
        } catch (Throwable t) {
            Log.w(TAG, "multicast lock unavailable", t);
        }
        thread("nova-host", new Runnable() { public void run() { hostLoop(); } });
        thread("nova-listen", new Runnable() { public void run() { listenLoop(); } });
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        try { if (listenSock != null) listenSock.close(); } catch (Exception ignored) { }
        try { if (mlock != null) mlock.release(); } catch (Exception ignored) { }
    }

    private static void thread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }

    // ================================================================== host side

    private List<PackManager.Pack> sharedPacks() {
        List<PackManager.Pack> out = new ArrayList<PackManager.Pack>();
        List<String> loaded = startupOrder;  // only what this game actually runs with
        for (PackManager.Pack p : PackManager.list(act))
            if (p.enabled && loaded != null && loaded.contains(p.uuid) && p.archive().exists()) out.add(p);
        return out;
    }

    private void hostLoop() {
        while (running) {
            try {
                Thread.sleep(2000);
                List<PackManager.Pack> packs = sharedPacks();
                if (packs.isEmpty()) continue;
                String motd = pingLocalWorld();
                if (motd == null) continue;  // no world is hosted right now
                ensureFileServer();
                JSONObject a = new JSONObject();
                a.put("nova", 1);
                a.put("motd", motd);
                a.put("port", FILE_PORT);
                JSONArray arr = new JSONArray();
                for (PackManager.Pack p : packs) {
                    JSONObject o = new JSONObject();
                    o.put("uuid", p.uuid);
                    o.put("name", p.name);
                    o.put("version", new JSONArray().put(p.version[0]).put(p.version[1]).put(p.version[2]));
                    o.put("size", p.size);
                    o.put("sha1", p.sha1);
                    arr.put(o);
                }
                a.put("packs", arr);
                broadcast(a.toString().getBytes("UTF-8"));
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, "host loop", t);
            }
        }
    }

    /** RakNet unconnected ping to our own game; returns the world name or null. */
    static String pingLocalWorld() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setSoTimeout(400);
            ByteBuffer b = ByteBuffer.allocate(33);
            b.put((byte) 0x01).putLong(System.currentTimeMillis()).put(MAGIC).putLong(0x4E4F5641L);
            byte[] pkt = b.array();
            s.send(new DatagramPacket(pkt, pkt.length, InetAddress.getByName("127.0.0.1"), GAME_PORT));
            byte[] rb = new byte[1500];
            DatagramPacket dp = new DatagramPacket(rb, rb.length);
            s.receive(dp);
            if (dp.getLength() < 35 || (rb[0] & 0xff) != 0x1c) return null;
            ByteBuffer r = ByteBuffer.wrap(rb, 0, dp.getLength());
            r.position(1 + 8 + 8 + 16);
            int n = r.getShort() & 0xffff;
            String info = new String(rb, r.position(), Math.min(n, r.remaining()), "UTF-8");
            String[] parts = info.split(";");
            return parts.length > 1 ? parts[1] : info;
        } catch (Exception e) {
            return null;
        } finally {
            if (s != null) s.close();
        }
    }

    private synchronized void ensureFileServer() {
        if (server != null && !server.isClosed()) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(FILE_PORT));
            final ServerSocket ss = server;
            thread("nova-files", new Runnable() {
                public void run() {
                    while (running && !ss.isClosed()) {
                        try {
                            final Socket c = ss.accept();
                            thread("nova-file-client", new Runnable() { public void run() { serve(c); } });
                        } catch (Exception e) {
                            if (!running) return;
                        }
                    }
                }
            });
            Log.i(TAG, "pack file server on " + FILE_PORT);
        } catch (Exception e) {
            Log.w(TAG, "file server failed", e);
            server = null;
        }
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(10000);
            InputStream in = c.getInputStream();
            OutputStream out = new BufferedOutputStream(c.getOutputStream(), 64 * 1024);
            String line = readLine(in);
            PackManager.Pack found = null;
            if (line != null && line.startsWith("NOVA GET ")) {
                String uuid = line.substring(9).trim();
                for (PackManager.Pack p : sharedPacks()) if (p.uuid.equals(uuid)) found = p;
            }
            if (found == null) {
                out.write("ERR not shared\n".getBytes("UTF-8"));
            } else {
                File f = found.archive();
                out.write(("OK " + f.length() + " " + found.sha1 + "\n").getBytes("UTF-8"));
                InputStream fi = new FileInputStream(f);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = fi.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    fi.close();
                }
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "serve", e);
        } finally {
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    private static void broadcast(byte[] data) {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            Set<InetAddress> targets = new HashSet<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                for (InterfaceAddress ia : ni.getInterfaceAddresses())
                    if (ia.getBroadcast() != null) targets.add(ia.getBroadcast());
            }
            for (InetAddress t : targets) {
                try { s.send(new DatagramPacket(data, data.length, t, ANNOUNCE_PORT)); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            Log.w(TAG, "broadcast", e);
        } finally {
            if (s != null) s.close();
        }
    }

    static Set<String> selfAddresses() {
        Set<String> out = new HashSet<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address) out.add(a.getHostAddress());
        } catch (Exception ignored) { }
        return out;
    }

    // ================================================================== guest side

    private void listenLoop() {
        while (running) {
            try {
                listenSock = new DatagramSocket(null);
                listenSock.setReuseAddress(true);
                listenSock.setBroadcast(true);
                listenSock.bind(new InetSocketAddress(ANNOUNCE_PORT));
                byte[] buf = new byte[8192];
                while (running) {
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    listenSock.receive(dp);
                    String from = dp.getAddress().getHostAddress();
                    if (selfAddresses().contains(from)) continue;
                    try {
                        onAnnounce(from, new JSONObject(new String(buf, 0, dp.getLength(), "UTF-8")));
                    } catch (Exception e) {
                        Log.w(TAG, "bad announce from " + from, e);
                    }
                }
            } catch (Exception e) {
                if (!running) return;
                Log.w(TAG, "listen", e);
                try { Thread.sleep(3000); } catch (InterruptedException ie) { return; }
            } finally {
                if (listenSock != null) listenSock.close();
            }
        }
    }

    /** A pack the host uses that we must download or switch on. */
    static final class Need {
        String uuid, name, sha1;
        long size;
        boolean download;
    }

    private void onAnnounce(final String host, JSONObject a) throws Exception {
        if (a.optInt("nova") != 1 || dialogOpen) return;
        final String motd = a.optString("motd", "Мир");
        final int port = a.optInt("port", FILE_PORT);
        JSONArray arr = a.getJSONArray("packs");
        final List<Need> needs = new ArrayList<Need>();
        final List<String> hostOrder = new ArrayList<String>();
        StringBuilder key = new StringBuilder(host);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String uuid = o.getString("uuid");
            hostOrder.add(uuid);
            key.append('|').append(uuid).append('@').append(o.optString("sha1"));
            PackManager.Pack have = PackManager.find(act, uuid);
            boolean sameFile = have != null && have.sha1.equalsIgnoreCase(o.optString("sha1"));
            boolean loaded = startupOrder != null && startupOrder.contains(uuid);
            if (sameFile && loaded) continue;  // already playing with it
            Need n = new Need();
            n.uuid = uuid;
            n.name = o.optString("name", uuid);
            n.sha1 = o.optString("sha1");
            n.size = o.optLong("size");
            n.download = !sameFile;
            needs.add(n);
        }
        if (needs.isEmpty() || declined.contains(key.toString())) return;
        final String k = key.toString();
        dialogOpen = true;
        act.runOnUiThread(new Runnable() {
            public void run() { prompt(host, port, motd, needs, hostOrder, k); }
        });
    }

    private void prompt(final String host, final int port, String motd, final List<Need> needs,
                        final List<String> hostOrder, final String key) {
        final NovaUi.Card card = new NovaUi.Card(act, "Набор ресурсов сервера");
        long total = 0;
        StringBuilder sb = new StringBuilder();
        for (Need n : needs) {
            sb.append("- ").append(n.name).append(n.download ? "  (" + NovaUi.size(n.size) + ")" : "  (уже есть, включить)").append('\n');
            if (n.download) total += n.size;
        }
        int s = McKit.scale(act);
        card.addText("Мир «" + motd + "» (" + host + ") играет с наборами ресурсов:", McKit.WHITE);
        McKit.Text list = card.addText(sb.toString().trim(), McKit.ACCENT);
        list.setPadding(4 * s, 3 * s, 0, 3 * s);
        card.addText((total > 0 ? "Загрузить " + NovaUi.size(total) + "? " : "Включить? ")
                + "После этого игра перезапустится, затем зайдите в этот мир.", McKit.GRAY);
        card.dialog.setCancelable(false);
        card.addButton("Не сейчас", McKit.Button.NORMAL, new View.OnClickListener() {
            public void onClick(View v) {
                declined.add(key);
                card.dialog.dismiss();
                dialogOpen = false;
            }
        });
        card.addButton(total > 0 ? "Загрузить" : "Включить", McKit.Button.PRIMARY, new View.OnClickListener() {
            public void onClick(View v) {
                card.dialog.dismiss();
                download(host, port, needs, hostOrder);
            }
        });
        card.dialog.show();
    }

    private void download(final String host, final int port, final List<Need> needs, final List<String> hostOrder) {
        final NovaUi.Card card = new NovaUi.Card(act, "Загрузка набора ресурсов");
        final McKit.Text status = card.addText("Подключаемся к " + host + "...", McKit.GRAY);
        final NovaUi.Progress pb = card.addProgress();
        card.dialog.setCancelable(false);
        card.dialog.show();
        thread("nova-download", new Runnable() {
            public void run() {
                String err = null;
                try {
                    long total = 0;
                    for (Need n : needs) if (n.download) total += Math.max(1, n.size);
                    long doneBefore = 0;
                    for (final Need n : needs) {
                        if (!n.download) continue;
                        ui(status, "Загружаем «" + n.name + "»...");
                        final long base = doneBefore, all = Math.max(1, total);
                        fetch(host, port, n, new PackManager.Progress() {
                            public void onBytes(final long got) {
                                act.runOnUiThread(new Runnable() {
                                    public void run() { pb.setProgress((int) (1000 * (base + got) / all)); }
                                });
                            }
                        });
                        doneBefore += Math.max(1, n.size);
                    }
                    // enable in the host's priority order (first = highest)
                    for (int i = hostOrder.size() - 1; i >= 0; i--) {
                        if (PackManager.find(act, hostOrder.get(i)) != null)
                            PackManager.setEnabled(act, hostOrder.get(i), true);
                    }
                } catch (PackManager.PackException e) {
                    err = e.getMessage();
                } catch (Exception e) {
                    err = "Не удалось загрузить: " + e.getMessage();
                }
                final String ferr = err;
                act.runOnUiThread(new Runnable() {
                    public void run() {
                        if (ferr != null) {
                            card.title.setText("Ошибка загрузки");
                            card.title.setColor(McKit.RED);
                            status.setText(ferr);
                            card.body.removeView(pb);
                            card.addButton("Закрыть", McKit.Button.NORMAL, new View.OnClickListener() {
                                public void onClick(View v) { card.dialog.dismiss(); dialogOpen = false; }
                            });
                            return;
                        }
                        card.title.setText("Готово!");
                        pb.setProgress(1000);
                        status.setText("Наборы установлены. Перезапускаем игру, затем зайдите в мир.");
                        status.setColor(McKit.WHITE);
                        status.postDelayed(new Runnable() {
                            public void run() { NovaActivity.restartGame(act); }
                        }, 1600);
                    }
                });
            }
        });
    }

    private void ui(final McKit.Text t, final String s) {
        act.runOnUiThread(new Runnable() { public void run() { t.setText(s); } });
    }

    private void fetch(String host, int port, Need n, PackManager.Progress progress) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            s.setSoTimeout(15000);
            s.getOutputStream().write(("NOVA GET " + n.uuid + "\n").getBytes("UTF-8"));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            String head = readLine(in);
            if (head == null || !head.startsWith("OK ")) throw new java.io.IOException("хост отказал: " + head);
            String[] p = head.split(" ");
            long size = Long.parseLong(p[1]);
            String sha1 = p.length > 2 ? p[2] : n.sha1;
            InputStream limited = new LimitedStream(new DataInputStream(in), size);
            PackManager.importStream(act, limited, "lan", host, sha1, false, progress);
        } finally {
            s.close();
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) >= 0 && ch != '\n') {
            sb.append((char) ch);
            if (sb.length() > 512) return null;
        }
        return ch < 0 && sb.length() == 0 ? null : sb.toString();
    }

    /** Reads at most n bytes from the socket stream. */
    static final class LimitedStream extends InputStream {
        private final InputStream in;
        private long left;
        LimitedStream(InputStream in, long n) { this.in = in; left = n; }
        public int read() throws java.io.IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        public void close() { }
    }
}
