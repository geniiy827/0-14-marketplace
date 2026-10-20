package com.geniiy.bridge14.pe;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ищет открытые миры MCPE в локальной сети.
 * Широковещательный запрос Windows часто отправляет не в тот адаптер (VirtualBox, Hyper-V, VPN),
 * а брандмауэр режет ответы на него. Поэтому дополнительно опрашиваем каждый адрес своей подсети напрямую.
 */
public final class LanScanner {
    public static final class Found {
        public final String host; public final int port; public final String info; public final long ping;
        Found(String h, int p, String i, long ms) { host = h; port = p; info = i; ping = ms; }
    }

    private static final int[] PORTS = {19132, 19133};

    private static byte[] pingPacket(int id) {
        return new Buf().u8(id).i64(System.currentTimeMillis()).raw(RakClient.MAGIC).i64(0x0B14B14L).bytes();
    }

    /** Свои IPv4-адреса и широковещательные адреса подсетей. */
    private static void localNets(Set<InetAddress> self, List<InetAddress> bcast, List<InetAddress> sweep) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception e) { continue; }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    self.add(a);
                    if (ia.getBroadcast() != null) bcast.add(ia.getBroadcast());
                    byte[] ip = a.getAddress();
                    int first = ip[0] & 0xff, second = ip[1] & 0xff;
                    boolean priv = first == 10 || first == 192 && second == 168 || first == 172 && second >= 16 && second <= 31;
                    if (!priv) continue;
                    // /24 вокруг своего адреса (обычная домашняя сеть / раздача с телефона)
                    for (int h = 1; h < 255; h++) {
                        if (h == (ip[3] & 0xff)) continue;
                        try { sweep.add(InetAddress.getByAddress(new byte[]{ip[0], ip[1], ip[2], (byte) h})); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static List<Found> scan(List<String> extraTargets, int millis) {
        Map<String, Found> res = new LinkedHashMap<>();
        Set<InetAddress> self = new LinkedHashSet<>();
        List<InetAddress> bcast = new ArrayList<>(), sweep = new ArrayList<>();
        localNets(self, bcast, sweep);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(50);
            List<InetAddress> fast = new ArrayList<>();
            try { fast.add(InetAddress.getByName("127.0.0.1")); fast.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
            fast.addAll(bcast);
            if (extraTargets != null) for (String t : extraTargets) try { fast.add(InetAddress.getByName(t)); } catch (Exception ignored) {}
            long start = System.currentTimeMillis(), end = start + millis, nextFast = 0;
            int sweepPos = 0, rounds = 0;
            byte[] rb = new byte[2048];
            Map<String, Long> sentAt = new java.util.HashMap<>();
            while (System.currentTimeMillis() < end) {
                long now = System.currentTimeMillis();
                if (now >= nextFast && rounds < 3) {
                    nextFast = now + 500; rounds++;
                    for (InetAddress t : fast) for (int port : PORTS) send(s, t, port, sentAt);
                }
                // прямой опрос подсети порциями, чтобы не завалить сеть
                for (int i = 0; i < 64 && sweepPos < sweep.size(); i++, sweepPos++)
                    for (int port : PORTS) send(s, sweep.get(sweepPos), port, sentAt);
                DatagramPacket dp = new DatagramPacket(rb, rb.length);
                try { s.receive(dp); } catch (SocketTimeoutException e) { continue; }
                Found f = parse(rb, dp.getLength(), dp.getAddress(), dp.getPort(), sentAt);
                if (f == null) continue;
                String h = f.host;
                // свой же компьютер под разными адресами — один раз
                if (self.contains(dp.getAddress()) || dp.getAddress().isLoopbackAddress()) h = "127.0.0.1";
                String key = h + ":" + f.port;
                if (!res.containsKey(key)) res.put(key, new Found(h, f.port, f.info, f.ping));
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(res.values());
    }

    private static void send(DatagramSocket s, InetAddress a, int port, Map<String, Long> sentAt) {
        try {
            byte[] p1 = pingPacket(0x01), p2 = pingPacket(0x02);
            s.send(new DatagramPacket(p1, p1.length, new InetSocketAddress(a, port)));
            s.send(new DatagramPacket(p2, p2.length, new InetSocketAddress(a, port)));
            sentAt.putIfAbsent(a.getHostAddress() + ":" + port, System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    private static Found parse(byte[] rb, int len, InetAddress from, int port, Map<String, Long> sentAt) {
        if (len < 35 || (rb[0] & 0xff) != 0x1c) return null;
        String info;
        try {
            Buf r = new Buf(rb, 1, len);
            r.i64(); r.i64(); r.skip(16);
            info = r.str();
        } catch (Exception e) { info = "?"; }
        Long t0 = sentAt == null ? null : sentAt.get(from.getHostAddress() + ":" + port);
        return new Found(from.getHostAddress(), port, info, t0 == null ? -1 : System.currentTimeMillis() - t0);
    }

    /** Опрос одного сервера: строка «MCPE;motd;протокол;версия;онлайн;макс» и пинг, или null. */
    public static Found ping(String host, int port, int millis) {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(Math.max(50, millis / 3));
            InetAddress a = InetAddress.getByName(host);
            long end = System.currentTimeMillis() + millis;
            byte[] rb = new byte[2048];
            while (System.currentTimeMillis() < end) {
                long t0 = System.currentTimeMillis();
                byte[] p = pingPacket(0x01);
                s.send(new DatagramPacket(p, p.length, new InetSocketAddress(a, port)));
                DatagramPacket dp = new DatagramPacket(rb, rb.length);
                try { s.receive(dp); } catch (SocketTimeoutException e) { continue; }
                Found f = parse(rb, dp.getLength(), dp.getAddress(), dp.getPort(), null);
                if (f != null) return new Found(host, port, f.info, System.currentTimeMillis() - t0);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** "MCPE;Имя мира;45;0.14.1;1;5" → "Имя мира (0.14.1, игроков 1/5)". */
    public static String pretty(String info) {
        String[] f = info.split(";");
        if (f.length >= 6) return f[1] + "  (версия " + f[3] + ", игроков " + f[4] + "/" + f[5] + ", протокол " + f[2] + ")";
        return info;
    }
}
