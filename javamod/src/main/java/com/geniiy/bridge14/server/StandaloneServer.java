package com.geniiy.bridge14.server;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

/**
 * Отдельный сервер-мост: java -jar Bridge0_14-server.jar [адрес_сервера_0.14[:порт]] [--listen 0.0.0.0:25565] [--nick Имя]
 * Java-игроки 1.20.1 заходят на этот компьютер (порт 25565), а мост переводит их на сервер MCPE 0.14.
 * Тот же jar — плагин Velocity (положить в plugins/), см. VelocityPlugin.
 */
public final class StandaloneServer {

    public static void main(String[] args) throws Exception {
        File cfgFile = new File("bridge0_14-server.properties");
        Properties p = BridgeServer.loadConfig(cfgFile, BridgeServer.defaults(),
                "Bridge0_14 server: pe.* - server MCPE 0.14, listen.* - where Java 1.20.1 players connect, nick empty = Java nickname");

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) { usage(); return; }
            else if (a.equals("--listen") && i + 1 < args.length) { String[] hp = split(args[++i], 25565); p.setProperty("listen.host", hp[0]); p.setProperty("listen.port", hp[1]); }
            else if (a.equals("--nick") && i + 1 < args.length) p.setProperty("nick", args[++i]);
            else if (a.equals("--radius") && i + 1 < args.length) p.setProperty("chunk.radius", args[++i]);
            else if (a.equals("--skin") && i + 1 < args.length) p.setProperty("skin", args[++i]);
            else if (a.equals("--bedwars")) p.setProperty("bedwars.fix", "true");
            else if (a.equals("--debug")) p.setProperty("debug", "true");
            else if (!a.startsWith("--")) { String[] hp = split(a, 19132); p.setProperty("pe.host", hp[0]); p.setProperty("pe.port", hp[1]); }
        }

        BridgeServer s = new BridgeServer(p, StandaloneServer::log);
        File secret = new File("forwarding.secret");
        if (secret.isFile()) s.forwardingSecret = java.nio.file.Files.readAllBytes(secret.toPath());
        s.start(new File("."));
        log("Настройки: " + cfgFile.getAbsolutePath() + "   (остановить: Ctrl+C)");
        Runtime.getRuntime().addShutdownHook(new Thread(s::stop));
        while (true) Thread.sleep(60_000);
    }

    private static String[] split(String a, int defPort) {
        int c = a.lastIndexOf(':');
        if (c > 0 && a.indexOf(':') == c) return new String[]{a.substring(0, c), a.substring(c + 1)};
        return new String[]{a, String.valueOf(defPort)};
    }

    private static void usage() {
        System.out.println("java -jar Bridge0_14-server.jar [host[:port]] [--listen 0.0.0.0:25565] [--nick Name] [--radius 6] [--skin skin.png] [--bedwars] [--debug]");
        System.out.println("Без аргументов берёт настройки из bridge0_14-server.properties (создаётся при первом запуске).");
        System.out.println("Плагин Velocity: положите этот же jar в папку plugins прокси.");
    }

    static synchronized void log(String m) {
        System.out.println(new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + m);
    }
}
