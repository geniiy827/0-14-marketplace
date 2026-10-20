package com.geniiy.bridge14.proxy;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import javax.imageio.ImageIO;

/** Локальный «сервер» для Java-клиента, который переводит игру на сервер 0.14. */
public final class Proxy {
    private ServerSocket server;
    private volatile Session session;
    private volatile boolean running;

    /** Запустить на случайном свободном порту localhost. Возвращает порт. */
    public int start(Session.Config cfg, Session.Listener listener) throws Exception {
        Tables.load();
        stop();
        server = new ServerSocket(0, 2, InetAddress.getLoopbackAddress());
        running = true;
        final ServerSocket ss = server;
        Thread t = new Thread(() -> {
            while (running) {
                try {
                    Socket s = ss.accept();
                    Session old = session;
                    if (old != null) old.close("Новое подключение");
                    session = new Session(s, cfg, listener);
                    session.start();
                } catch (Exception e) {
                    if (running) listener.log("Прокси: " + e);
                    return;
                }
            }
        }, "bridge14-accept");
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    public void stop() {
        running = false;
        Session s = session; session = null;
        if (s != null) s.close("Выход");
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    /** Загрузить скин PNG 64×64 или 64×32 в RGBA (для 0.14). null — если не удалось. */
    public static byte[] loadSkin(File f) {
        try {
            BufferedImage img = ImageIO.read(f);
            if (img == null) return null;
            int w = img.getWidth(), h = img.getHeight();
            if (w != 64 || (h != 64 && h != 32)) return null;
            byte[] out = new byte[w * h * 4];
            for (int yy = 0; yy < h; yy++) for (int xx = 0; xx < w; xx++) {
                int argb = img.getRGB(xx, yy);
                int i = (yy * w + xx) * 4;
                out[i] = (byte) (argb >> 16); out[i + 1] = (byte) (argb >> 8); out[i + 2] = (byte) argb; out[i + 3] = (byte) (argb >>> 24);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
