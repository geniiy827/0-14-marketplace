package com.geniiy.bridge14.pe;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ищет открытые миры MCPE в локальной сети (как список «Локальные игры» в самой игре). */
public final class LanScanner {
    public static final class Found {
        public final String host; public final int port; public final String info;
        Found(String h, int p, String i) { host = h; port = p; info = i; }
    }

    public static List<Found> scan(List<String> extraTargets, int millis) {
        Map<String, Found> res = new LinkedHashMap<>();
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(100);
            List<String> targets = new ArrayList<>();
            targets.add("127.0.0.1");
            targets.add("255.255.255.255");
            if (extraTargets != null) targets.addAll(extraTargets);
            long end = System.currentTimeMillis() + millis, nextSend = 0;
            byte[] rb = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSend) {
                    nextSend = System.currentTimeMillis() + 500;
                    Buf b = new Buf().u8(0x01).i64(System.currentTimeMillis()).raw(RakClient.MAGIC).i64(12345);
                    for (String t : targets) {
                        for (int port : new int[]{19132, 19133}) {
                            try { s.send(new DatagramPacket(b.b, b.len, InetAddress.getByName(t), port)); } catch (Exception ignored) {}
                        }
                    }
                }
                DatagramPacket dp = new DatagramPacket(rb, rb.length);
                try { s.receive(dp); } catch (SocketTimeoutException e) { continue; }
                if (dp.getLength() < 35 || (rb[0] & 0xff) != 0x1c) continue;
                String info;
                try {
                    Buf r = new Buf(rb, 1, dp.getLength());
                    r.i64(); r.i64(); r.skip(16);
                    info = r.str();
                } catch (Exception e) { info = "?"; }
                String h = dp.getAddress().getHostAddress();
                res.put(h + ":" + dp.getPort(), new Found(h, dp.getPort(), info));
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(res.values());
    }

    /** "MCPE;Имя мира;45;0.14.1;1;5" → "Имя мира (0.14.1, игроков 1/5)". */
    public static String pretty(String info) {
        String[] f = info.split(";");
        if (f.length >= 6) return f[1] + "  (версия " + f[3] + ", игроков " + f[4] + "/" + f[5] + ", протокол " + f[2] + ")";
        return info;
    }
}
