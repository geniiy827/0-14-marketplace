package com.geniiy.worldbuilder.remote;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Локальная папка как «сервер» (для офлайн-режима и тестов). Пути — абсолютные пути файловой системы. */
public class LocalFs implements RemoteFs {
    private Path p(String s) { return Paths.get(s); }
    public List<Entry> list(String path) throws IOException {
        List<Entry> l = new ArrayList<>();
        try (Stream<Path> s = Files.list(p(path))) {
            for (Path f : s.toList()) {
                boolean d = Files.isDirectory(f);
                l.add(new Entry(f.getFileName().toString(), d, d ? 0 : Files.size(f), Files.getLastModifiedTime(f).toMillis()));
            }
        }
        return l;
    }
    public void download(String remote, Path local, Progress pr) throws IOException { Files.createDirectories(local.getParent()); Files.copy(p(remote), local, StandardCopyOption.REPLACE_EXISTING); }
    public void upload(Path local, String remote, Progress pr) throws IOException { Files.createDirectories(p(remote).getParent()); Files.copy(local, p(remote), StandardCopyOption.REPLACE_EXISTING); }
    public void rename(String from, String to) throws IOException { Files.move(p(from), p(to), StandardCopyOption.REPLACE_EXISTING); }
    public void delete(String path) throws IOException { Files.deleteIfExists(p(path)); }
    public void mkdirs(String path) throws IOException { Files.createDirectories(p(path)); }
    public long size(String path) throws IOException { Path f = p(path); return Files.exists(f) ? Files.size(f) : -1; }
    public boolean exists(String path) { return Files.exists(p(path)); }
    public void keepAlive() {}
    public String describe() { return "Локальная папка"; }
    public void close() {}
}
