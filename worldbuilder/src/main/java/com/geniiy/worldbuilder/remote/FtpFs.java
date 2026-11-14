package com.geniiy.worldbuilder.remote;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.geniiy.worldbuilder.config.ServerProfile;
import org.apache.commons.net.ftp.*;

import java.io.*;
import java.nio.file.*;
import java.security.cert.X509Certificate;
import java.util.*;
import javax.net.ssl.*;

/**
 * FTP и FTPS (явный TLS, PROT P). Всегда двоичный режим (TYPE I), пассивный режим по умолчанию,
 * MLSD с откатом на LIST, автопереподключение (3 попытки).
 */
public class FtpFs implements RemoteFs {
    public interface CertTrust { boolean trust(String fingerprintSha256, String subject); }

    private final ServerProfile prof;
    private final String password;
    private final CertTrust certTrust;
    private FTPClient ftp;
    private boolean mlsdSupported = true;
    private long lastActivity;

    public FtpFs(ServerProfile prof, String password, CertTrust trust) { this.prof = prof; this.password = password; this.certTrust = trust; }

    public synchronized void connect() throws IOException {
        if (ftp != null && ftp.isConnected()) return;
        FTPClient c;
        if ("FTPS".equals(prof.protocol)) {
            FTPSClient s = new FTPSClient("TLS", false);
            s.setTrustManager(new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] a, String b) {}
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws java.security.cert.CertificateException {
                    try {
                        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        f.init((java.security.KeyStore) null);
                        ((X509TrustManager) f.getTrustManagers()[0]).checkServerTrusted(chain, auth);
                    } catch (Exception e) {
                        String fp = fingerprint(chain[0]);
                        if (prof.trustedCert != null && prof.trustedCert.equals(fp)) return;
                        if (certTrust != null && certTrust.trust(fp, chain[0].getSubjectX500Principal().getName())) { prof.trustedCert = fp; return; }
                        throw new java.security.cert.CertificateException("Сертификат сервера не принят");
                    }
                }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            });
            c = s;
        } else c = new FTPClient();
        int to = Math.max(3, prof.timeoutSec) * 1000;
        c.setConnectTimeout(to);
        c.setDefaultTimeout(to);
        c.setDataTimeout(java.time.Duration.ofMillis(to * 2L));
        c.setControlEncoding(prof.encoding == null ? "UTF-8" : prof.encoding);
        c.setAutodetectUTF8(true);
        c.connect(prof.host, prof.port);
        if (!FTPReply.isPositiveCompletion(c.getReplyCode())) { c.disconnect(); throw new IOException("Сервер отклонил подключение: " + c.getReplyString()); }
        if (c instanceof FTPSClient s) { s.execPBSZ(0); s.execPROT("P"); }
        if (!c.login(prof.user, password == null ? "" : password)) {
            String r = c.getReplyString();
            c.disconnect();
            throw new IOException("530 Неверный логин или пароль (" + (r == null ? "" : r.trim()) + ")");
        }
        try { c.sendCommand("OPTS UTF8 ON"); } catch (IOException ignored) {}
        if (!c.setFileType(FTP.BINARY_FILE_TYPE)) throw new IOException("Сервер не включил двоичный режим (TYPE I)");
        if (prof.passive) c.enterLocalPassiveMode(); else c.enterLocalActiveMode();
        c.setBufferSize(1 << 16);
        ftp = c;
        lastActivity = System.currentTimeMillis();
    }

    static String fingerprint(X509Certificate c) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) { if (sb.length() > 0) sb.append(':'); sb.append(String.format("%02X", b)); }
            return sb.toString();
        } catch (Exception e) { return "?"; }
    }

    private interface Op<T> { T run(FTPClient c) throws IOException; }

    private synchronized <T> T retry(Op<T> op) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                connect();
                T r = op.run(ftp);
                lastActivity = System.currentTimeMillis();
                return r;
            } catch (IOException e) {
                last = e;
                if (e.getMessage() != null && (e.getMessage().startsWith("530") || e.getMessage().contains("Отменено"))) throw e;
                WorldBuilderMod.LOG.warn("[WorldBuilder] FTP: ошибка, повтор {}/3: {}", attempt + 1, e.toString());
                disconnectQuiet();
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) { throw new IOException("Отменено"); }
            }
        }
        throw last;
    }

    private void disconnectQuiet() {
        try { if (ftp != null) ftp.disconnect(); } catch (IOException ignored) {}
        ftp = null;
    }

    public List<Entry> list(String path) throws IOException {
        return retry(c -> {
            FTPFile[] files = null;
            if (mlsdSupported) {
                try {
                    files = c.mlistDir(path);
                    if (!FTPReply.isPositiveCompletion(c.getReplyCode()) && (files == null || files.length == 0)) { mlsdSupported = false; files = null; }
                } catch (IOException e) { mlsdSupported = false; files = null; }
            }
            if (files == null) files = c.listFiles(path);
            List<Entry> l = new ArrayList<>();
            for (FTPFile f : files) {
                if (f == null) continue;
                String n = f.getName();
                if (n == null || n.equals(".") || n.equals("..")) continue;
                int slash = n.lastIndexOf('/');
                if (slash >= 0) n = n.substring(slash + 1);
                long mt = f.getTimestamp() == null ? 0 : f.getTimestamp().getTimeInMillis();
                l.add(new Entry(n, f.isDirectory(), f.getSize(), mt));
            }
            return l;
        });
    }

    public void download(String remote, Path local, Progress p) throws IOException {
        Files.createDirectories(local.getParent());
        Path tmp = local.resolveSibling(local.getFileName() + ".part");
        long total = size(remote);
        retry(c -> {
            c.setFileType(FTP.BINARY_FILE_TYPE);
            try (InputStream in = c.retrieveFileStream(remote)) {
                if (in == null) throw new IOException("Не удалось скачать " + remote + ": " + c.getReplyString());
                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                    copy(in, out, total, p);
                }
            }
            if (!c.completePendingCommand()) throw new IOException("Передача не завершена: " + c.getReplyString());
            return null;
        });
        if (total >= 0 && Files.size(tmp) != total) throw new IOException("Размер скачанного файла не совпал: " + remote);
        Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
    }

    public void upload(Path local, String remote, Progress p) throws IOException {
        long total = Files.size(local);
        retry(c -> {
            c.setFileType(FTP.BINARY_FILE_TYPE);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(local));
                 OutputStream out = c.storeFileStream(remote)) {
                if (out == null) throw new IOException("Нет прав на запись: " + remote + " (" + c.getReplyString() + ")");
                copy(in, out, total, p);
            }
            if (!c.completePendingCommand()) throw new IOException("Загрузка не завершена: " + c.getReplyString());
            return null;
        });
        long rs = size(remote);
        if (rs >= 0 && rs != total) throw new IOException("Размер на сервере не совпал после загрузки: " + remote);
    }

    private static void copy(InputStream in, OutputStream out, long total, Progress p) throws IOException {
        byte[] buf = new byte[1 << 16];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null) { p.bytes(done, total); if (p.cancelled()) throw new IOException("Отменено"); }
        }
    }

    public void rename(String from, String to) throws IOException {
        retry(c -> { if (!c.rename(from, to)) throw new IOException("Не удалось переименовать " + from + ": " + c.getReplyString()); return null; });
    }
    public void delete(String path) throws IOException {
        retry(c -> { if (!c.deleteFile(path) && size(path) >= 0) throw new IOException("Не удалось удалить " + path + ": " + c.getReplyString()); return null; });
    }
    public void mkdirs(String path) throws IOException {
        retry(c -> {
            StringBuilder cur = new StringBuilder();
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                cur.append('/').append(part);
                c.makeDirectory(cur.toString());
            }
            return null;
        });
    }
    public long size(String path) throws IOException {
        return retry(c -> {
            String r = null;
            try { r = c.getSize(path); } catch (IOException ignored) {}
            if (r != null) try { return Long.parseLong(r.trim()); } catch (NumberFormatException ignored) {}
            FTPFile[] f = c.listFiles(path);
            if (f != null && f.length == 1 && f[0] != null && f[0].isFile()) return f[0].getSize();
            return -1L;
        });
    }
    public boolean exists(String path) throws IOException {
        String parent = RemoteFs.parent(path), name = RemoteFs.name(path);
        for (Entry e : list(parent)) if (e.name().equals(name)) return true;
        return false;
    }
    public synchronized void keepAlive() {
        if (ftp == null || System.currentTimeMillis() - lastActivity < 30000) return;
        try { ftp.sendNoOp(); lastActivity = System.currentTimeMillis(); } catch (IOException e) { disconnectQuiet(); }
    }
    public String describe() { return prof.protocol.toLowerCase(Locale.ROOT) + "://" + prof.host + ":" + prof.port; }
    public synchronized void close() {
        try { if (ftp != null && ftp.isConnected()) { ftp.logout(); ftp.disconnect(); } } catch (IOException ignored) {}
        ftp = null;
    }
}
