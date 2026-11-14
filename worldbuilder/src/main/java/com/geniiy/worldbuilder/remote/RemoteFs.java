package com.geniiy.worldbuilder.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Файловая система сервера (FTP, FTPS, SFTP) или локальная папка. Все методы блокирующие — вызывать из фонового потока. */
public interface RemoteFs extends AutoCloseable {
    record Entry(String name, boolean dir, long size, long mtime) {}

    interface Progress { void bytes(long done, long total); boolean cancelled(); }

    List<Entry> list(String path) throws IOException;
    void download(String remote, Path local, Progress p) throws IOException;
    void upload(Path local, String remote, Progress p) throws IOException;
    void rename(String from, String to) throws IOException;
    void delete(String path) throws IOException;
    void mkdirs(String path) throws IOException;
    /** Размер файла или −1, если файла нет. */
    long size(String path) throws IOException;
    boolean exists(String path) throws IOException;
    /** Проверить, что соединение живо (NOOP), переподключиться при необходимости. */
    void keepAlive();
    String describe();
    @Override void close();

    static String join(String dir, String name) {
        if (dir == null || dir.isEmpty()) return "/" + name;
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }
    static String parent(String path) {
        if (path == null || path.equals("/") || path.isEmpty()) return "/";
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }
    static String name(String path) {
        String p = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return p.substring(p.lastIndexOf('/') + 1);
    }

    /** Понятное сообщение об ошибке сети. */
    static String humanError(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = String.valueOf(t.getMessage());
        if (t instanceof java.net.UnknownHostException) return "Адрес не найден: " + m;
        if (t instanceof java.net.SocketTimeoutException) return "Сервер не отвечает (таймаут)";
        if (t instanceof java.net.ConnectException) return "Не удалось подключиться: " + m;
        if (m.contains("530") || m.toLowerCase().contains("auth fail") || m.toLowerCase().contains("login")) return "Неверный логин или пароль";
        if (m.contains("550") || m.toLowerCase().contains("permission")) return "Нет прав или файл не найден: " + m;
        if (t instanceof java.net.SocketException) return "Соединение сброшено: " + m;
        return e.getMessage() != null ? e.getMessage() : t.toString();
    }
}
