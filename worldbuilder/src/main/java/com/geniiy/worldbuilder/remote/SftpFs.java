package com.geniiy.worldbuilder.remote;

import com.geniiy.worldbuilder.config.ServerProfile;
import com.jcraft.jsch.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** SFTP (JSch). Ключ хоста проверяется по принципу TOFU: при первом подключении — вопрос с отпечатком SHA256. */
public class SftpFs implements RemoteFs {
    public interface HostKeyTrust { boolean trust(String fingerprint, boolean changed); }

    private final ServerProfile prof;
    private final String password;
    private final HostKeyTrust hostTrust;
    private Session session;
    private ChannelSftp sftp;
    private long lastActivity;

    public SftpFs(ServerProfile prof, String password, HostKeyTrust trust) { this.prof = prof; this.password = password; this.hostTrust = trust; }

    public synchronized void connect() throws IOException {
        if (sftp != null && sftp.isConnected() && session.isConnected()) return;
        try {
            JSch jsch = new JSch();
            if ("key".equals(prof.sftpAuth) && prof.keyFile != null && !prof.keyFile.isEmpty()) {
                if (password != null && !password.isEmpty()) jsch.addIdentity(prof.keyFile, password);
                else jsch.addIdentity(prof.keyFile);
            }
            Session s = jsch.getSession(prof.user, prof.host, prof.port);
            if (!"key".equals(prof.sftpAuth)) s.setPassword(password == null ? "" : password);
            s.setConfig("StrictHostKeyChecking", "no");
            s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
            s.setTimeout(Math.max(3, prof.timeoutSec) * 1000);
            s.connect(Math.max(3, prof.timeoutSec) * 1000);
            HostKey hk = s.getHostKey();
            String fp = hk.getFingerPrint(jsch);
            try {
                byte[] raw = Base64.getDecoder().decode(hk.getKey());
                byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
                fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d);
            } catch (Exception ignored) {}
            if (prof.hostKey == null || !prof.hostKey.equals(fp)) {
                boolean changed = prof.hostKey != null;
                if (hostTrust == null || !hostTrust.trust(fp, changed)) { s.disconnect(); throw new IOException("Ключ сервера не принят"); }
                prof.hostKey = fp;
            }
            Channel ch = s.openChannel("sftp");
            ch.connect(Math.max(3, prof.timeoutSec) * 1000);
            session = s;
            sftp = (ChannelSftp) ch;
            lastActivity = System.currentTimeMillis();
        } catch (JSchException e) {
            String m = String.valueOf(e.getMessage());
            if (m.toLowerCase().contains("auth")) throw new IOException("530 Неверный логин или пароль", e);
            throw new IOException(m, e);
        }
    }

    private interface Op<T> { T run(ChannelSftp c) throws Exception; }

    private synchronized <T> T retry(Op<T> op) throws IOException {
        IOException last = null;
        for (int i = 0; i < 3; i++) {
            try {
                connect();
                T r = op.run(sftp);
                lastActivity = System.currentTimeMillis();
                return r;
            } catch (SftpException e) {
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) throw new FileNotFoundException(e.getMessage());
                if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) throw new IOException("Нет прав: " + e.getMessage());
                last = new IOException(e.getMessage(), e);
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).startsWith("530") || String.valueOf(e.getMessage()).contains("не принят") || String.valueOf(e.getMessage()).contains("Отменено")) throw e;
                last = e;
            } catch (Exception e) { last = new IOException(e.toString(), e); }
            close();
            try { Thread.sleep(1000L * (i + 1)); } catch (InterruptedException ie) { throw new IOException("Отменено"); }
        }
        throw last;
    }

    public List<Entry> list(String path) throws IOException {
        return retry(c -> {
            List<Entry> l = new ArrayList<>();
            for (Object o : c.ls(path)) {
                ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
                String n = e.getFilename();
                if (n.equals(".") || n.equals("..")) continue;
                SftpATTRS a = e.getAttrs();
                l.add(new Entry(n, a.isDir(), a.getSize(), a.getMTime() * 1000L));
            }
            return l;
        });
    }

    private SftpProgressMonitor mon(Progress p, long total) {
        return new SftpProgressMonitor() {
            long done;
            public void init(int op, String src, String dest, long max) {}
            public boolean count(long n) { done += n; if (p != null) { p.bytes(done, total); return !p.cancelled(); } return true; }
            public void end() {}
        };
    }

    public void download(String remote, Path local, Progress p) throws IOException {
        Files.createDirectories(local.getParent());
        Path tmp = local.resolveSibling(local.getFileName() + ".part");
        long total = size(remote);
        retry(c -> { try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) { c.get(remote, out, mon(p, total)); } return null; });
        if (p != null && p.cancelled()) throw new IOException("Отменено");
        if (total >= 0 && Files.size(tmp) != total) throw new IOException("Размер скачанного файла не совпал: " + remote);
        Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
    }

    public void upload(Path local, String remote, Progress p) throws IOException {
        long total = Files.size(local);
        retry(c -> { try (InputStream in = new BufferedInputStream(Files.newInputStream(local))) { c.put(in, remote, mon(p, total), ChannelSftp.OVERWRITE); } return null; });
        if (p != null && p.cancelled()) throw new IOException("Отменено");
        if (size(remote) != total) throw new IOException("Размер на сервере не совпал после загрузки: " + remote);
    }

    public void rename(String from, String to) throws IOException {
        retry(c -> { try { c.rm(to); } catch (SftpException ignored) {} c.rename(from, to); return null; });
    }
    public void delete(String path) throws IOException { retry(c -> { c.rm(path); return null; }); }
    public void mkdirs(String path) throws IOException {
        retry(c -> {
            StringBuilder cur = new StringBuilder();
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                cur.append('/').append(part);
                try { c.stat(cur.toString()); } catch (SftpException e) { c.mkdir(cur.toString()); }
            }
            return null;
        });
    }
    public long size(String path) throws IOException {
        try { return retry(c -> c.stat(path).getSize()); } catch (FileNotFoundException e) { return -1; }
    }
    public boolean exists(String path) throws IOException { return size(path) >= 0 || isDir(path); }
    private boolean isDir(String path) { try { return retry(c -> c.stat(path).isDir()); } catch (IOException e) { return false; } }
    public synchronized void keepAlive() {
        if (session == null || System.currentTimeMillis() - lastActivity < 30000) return;
        try { session.sendKeepAliveMsg(); lastActivity = System.currentTimeMillis(); } catch (Exception e) { close(); }
    }
    public String describe() { return "sftp://" + prof.host + ":" + prof.port; }
    public synchronized void close() {
        try { if (sftp != null) sftp.disconnect(); } catch (Exception ignored) {}
        try { if (session != null) session.disconnect(); } catch (Exception ignored) {}
        sftp = null; session = null;
    }
}
