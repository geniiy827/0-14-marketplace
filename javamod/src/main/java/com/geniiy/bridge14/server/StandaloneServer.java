package com.geniiy.bridge14.server;

import com.geniiy.bridge14.proxy.Proxy;
import com.geniiy.bridge14.proxy.Session;
import com.geniiy.bridge14.proxy.Tables;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Отдельный сервер-мост: java -jar Bridge0_14-server.jar [адрес_сервера_0.14[:порт]] [--listen 0.0.0.0:25565] [--nick Имя]
 * Java-игроки 1.20.1 заходят на этот компьютер (порт 25565), а мост переводит их на сервер MCPE 0.14.
 * Каждый Java-игрок получает своё подключение к серверу 0.14 под своим ником (если --nick не задан).
 */
public final class StandaloneServer {
    private static final List<Session> SESSIONS = new CopyOnWriteArrayList<>();

    public static void main(String[] args) throws Exception {
        File cfgFile = new File("bridge0_14-server.properties");
        Properties p = new Properties();
        p.setProperty("pe.host", "127.0.0.1");
        p.setProperty("pe.port", "19132");
        p.setProperty("listen.host", "0.0.0.0");
        p.setProperty("listen.port", "25565");
        p.setProperty("chunk.radius", "6");
        p.setProperty("nick", "");
        p.setProperty("skin", "skin.png");
        p.setProperty("skin.slim", "false");
        p.setProperty("motd", "Сервер 0.14 через Bridge0_14");
        p.setProperty("bedwars.fix", "false");
        if (cfgFile.isFile()) try (FileReader r = new FileReader(cfgFile, java.nio.charset.StandardCharsets.UTF_8)) { p.load(r); }
        else try (FileWriter w = new FileWriter(cfgFile, java.nio.charset.StandardCharsets.UTF_8)) {
            p.store(w, "Bridge0_14 server: pe.* - server MCPE 0.14, listen.* - where Java 1.20.1 players connect, nick empty = Java nickname");
        }

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) { usage(); return; }
            else if (a.equals("--listen") && i + 1 < args.length) { String[] hp = split(args[++i], 25565); p.setProperty("listen.host", hp[0]); p.setProperty("listen.port", hp[1]); }
            else if (a.equals("--nick") && i + 1 < args.length) p.setProperty("nick", args[++i]);
            else if (a.equals("--radius") && i + 1 < args.length) p.setProperty("chunk.radius", args[++i]);
            else if (a.equals("--skin") && i + 1 < args.length) p.setProperty("skin", args[++i]);
            else if (a.equals("--bedwars")) p.setProperty("bedwars.fix", "true");
            else if (!a.startsWith("--")) { String[] hp = split(a, 19132); p.setProperty("pe.host", hp[0]); p.setProperty("pe.port", hp[1]); }
        }

        Tables.load();
        Session.bedwarsFix = Boolean.parseBoolean(p.getProperty("bedwars.fix", "false").trim());
        if (Session.bedwarsFix) log("Bedwars fix включён: тап по предмету в сундуке сразу кладёт его в инвентарь");
        byte[] tags = readRes("/bridge14/tags.bin");
        byte[] skin = null;
        File sf = new File(p.getProperty("skin", "skin.png"));
        if (sf.isFile()) { skin = Proxy.loadSkin(sf); if (skin == null) log("Скин " + sf + " не подходит (нужен PNG 64×64 или 64×32)"); }

        String peHost = p.getProperty("pe.host");
        int pePort = Integer.parseInt(p.getProperty("pe.port").trim());
        String lh = p.getProperty("listen.host");
        int lp = Integer.parseInt(p.getProperty("listen.port").trim());
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getByName(lh), lp), 16);
        log("Bridge0_14: Java 1.20.1 -> " + lh + ":" + lp + "  =>  MCPE 0.14 " + peHost + ":" + pePort);
        log("Настройки: " + cfgFile.getAbsolutePath() + "   (остановить: Ctrl+C)");

        final byte[] skinF = skin;
        while (true) {
            Socket s = ss.accept();
            Session.Config c = new Session.Config();
            c.host = peHost; c.port = pePort;
            c.nick = p.getProperty("nick", "").trim();
            c.chunkRadius = Integer.parseInt(p.getProperty("chunk.radius", "6").trim());
            c.skin = skinF;
            c.slim = Boolean.parseBoolean(p.getProperty("skin.slim", "false").trim());
            c.motd = p.getProperty("motd", "Сервер 0.14");
            c.tags = () -> tags;
            final String who = s.getRemoteSocketAddress().toString();
            final Session[] me = new Session[1];
            me[0] = new Session(s, c, new Session.Listener() {
                public void status(String m) { StandaloneServer.log("[" + who + "] " + m); }
                public void log(String m) { StandaloneServer.log("[" + who + "] " + m); }
                public void ended(String reason) { SESSIONS.remove(me[0]); StandaloneServer.log("[" + who + "] отключён: " + reason + " (игроков: " + SESSIONS.size() + ")"); }
            });
            SESSIONS.add(me[0]);
            me[0].start();
        }
    }

    private static String[] split(String a, int defPort) {
        int c = a.lastIndexOf(':');
        if (c > 0 && a.indexOf(':') == c) return new String[]{a.substring(0, c), a.substring(c + 1)};
        return new String[]{a, String.valueOf(defPort)};
    }

    private static void usage() {
        System.out.println("java -jar Bridge0_14-server.jar [host[:port]] [--listen 0.0.0.0:25565] [--nick Name] [--radius 6] [--skin skin.png] [--bedwars]");
        System.out.println("Без аргументов берёт настройки из bridge0_14-server.properties (создаётся при первом запуске).");
    }

    private static byte[] readRes(String n) throws Exception {
        try (InputStream in = StandaloneServer.class.getResourceAsStream(n)) {
            if (in == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int k;
            while ((k = in.read(b)) > 0) o.write(b, 0, k);
            return o.toByteArray();
        }
    }

    static synchronized void log(String m) {
        System.out.println(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + m);
    }
}
