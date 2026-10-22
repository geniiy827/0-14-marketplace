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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Сам мост как сервер Java 1.20.1: принимает Java-клиентов (или прокси Velocity) и для каждого
 * открывает своё подключение к серверу MCPE 0.14. Используется и отдельным jar, и плагином Velocity.
 */
public final class BridgeServer {
    private final Properties p;
    private final Consumer<String> log;
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private volatile ServerSocket ss;
    private volatile boolean running;
    public Session.SkinSource skins;
    public byte[] forwardingSecret;
    public boolean plugin;

    public BridgeServer(Properties p, Consumer<String> log) { this.p = p; this.log = log; }

    /** Настройки по умолчанию + чтение/создание файла. */
    public static Properties loadConfig(File f, Properties defaults, String comment) throws Exception {
        Properties p = new Properties();
        p.putAll(defaults);
        if (f.isFile()) {
            try (FileReader r = new FileReader(f, StandardCharsets.UTF_8)) { p.load(r); }
            // дописать новые ключи, которых не было в старом файле
            Properties onDisk = new Properties();
            try (FileReader r = new FileReader(f, StandardCharsets.UTF_8)) { onDisk.load(r); }
            boolean missing = false;
            for (String k : defaults.stringPropertyNames()) if (!onDisk.containsKey(k)) missing = true;
            if (missing) try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8)) { p.store(w, comment); }
        } else {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8)) { p.store(w, comment); }
        }
        return p;
    }

    public static Properties defaults() {
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
        p.setProperty("bedrock.skins", "true");
        p.setProperty("debug", "false");
        return p;
    }

    public int port() { ServerSocket s = ss; return s == null ? -1 : s.getLocalPort(); }

    public int online() { return sessions.size(); }

    public void start(File baseDir) throws Exception {
        Tables.load();
        Session.bedwarsFix = bool("bedwars.fix");
        if (Session.bedwarsFix) log.accept("Bedwars fix включён: тап по предмету в сундуке сразу кладёт его в инвентарь");
        final byte[] tags = readRes("/bridge14/tags.bin");
        byte[] skin = null;
        File sf = new File(p.getProperty("skin", "skin.png"));
        if (!sf.isAbsolute() && baseDir != null) sf = new File(baseDir, sf.getPath());
        if (sf.isFile()) { skin = Proxy.loadSkin(sf); if (skin == null) log.accept("Скин " + sf + " не подходит (нужен PNG 64×64 или 64×32)"); }
        final byte[] skinF = skin;

        String peHost = p.getProperty("pe.host").trim();
        int pePort = Integer.parseInt(p.getProperty("pe.port").trim());
        String lh = p.getProperty("listen.host").trim();
        int lp = Integer.parseInt(p.getProperty("listen.port").trim());
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(InetAddress.getByName(lh), lp), 32);
        ss = s;
        running = true;
        log.accept("Bridge0_14: Java 1.20.1 -> " + lh + ":" + lp + "  =>  MCPE 0.14 " + peHost + ":" + pePort);
        Thread t = new Thread(() -> {
            while (running) {
                Socket c;
                try { c = s.accept(); } catch (Exception e) { if (running) log.accept("accept: " + e); return; }
                Session.Config cfg = new Session.Config();
                cfg.host = peHost; cfg.port = pePort;
                cfg.nick = p.getProperty("nick", "").trim();
                cfg.chunkRadius = Integer.parseInt(p.getProperty("chunk.radius", "6").trim());
                cfg.skin = skinF;
                cfg.slim = bool("skin.slim");
                cfg.motd = p.getProperty("motd", "Сервер 0.14");
                cfg.tags = () -> tags;
                cfg.useClientUuid = true;
                cfg.askProxyInfo = true;
                cfg.forwardingSecret = forwardingSecret;
                cfg.skins = bool("bedrock.skins") ? skins : null;
                cfg.debug = bool("debug");
                final String who = c.getRemoteSocketAddress().toString();
                final Session[] me = new Session[1];
                me[0] = new Session(c, cfg, new Session.Listener() {
                    public void status(String m) { log.accept("[" + who + "] " + m); }
                    public void log(String m) { log.accept("[" + who + "] " + m); }
                    public void ended(String reason) { sessions.remove(me[0]); log.accept("[" + who + "] отключён: " + reason + " (игроков: " + sessions.size() + ")"); }
                });
                sessions.add(me[0]);
                me[0].start();
            }
        }, "bridge14-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { ServerSocket s = ss; if (s != null) s.close(); } catch (Exception ignored) {}
        for (Session s : sessions) s.close("Сервер выключается");
        sessions.clear();
    }

    private boolean bool(String k) { return Boolean.parseBoolean(p.getProperty(k, "false").trim()); }

    static byte[] readRes(String n) throws Exception {
        try (InputStream in = BridgeServer.class.getResourceAsStream(n)) {
            if (in == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int k;
            while ((k = in.read(b)) > 0) o.write(b, 0, k);
            return o.toByteArray();
        }
    }
}
