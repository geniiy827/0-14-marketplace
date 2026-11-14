package com.geniiy.worldbuilder.config;

/** Профиль подключения к серверу. Пароль хранится отдельно в зашифрованном виде. */
public class ServerProfile {
    public String id = java.util.UUID.randomUUID().toString();
    public String name = "";
    public String protocol = "FTP"; // FTP | FTPS | SFTP
    public String host = "";
    public int port = 21;
    public String user = "";
    public String startDir = "/";
    public boolean passive = true;
    public String encoding = "UTF-8";
    public int timeoutSec = 15;
    public int parallel = 2;
    public boolean remember = true;
    public String sftpAuth = "password"; // password | key
    public String keyFile = "";
    /** Зашифрованный пароль (Base64 AES-GCM) или null. */
    public String encPassword;
    public long lastLogin;
    public String trustedCert;
    public String hostKey;

    public String title() { return name == null || name.isBlank() ? host : name; }
    public String url() { return protocol.toLowerCase() + "://" + host + ":" + port; }
    public String cacheKey() { return (host + "_" + port + "_" + user).replaceAll("[^A-Za-z0-9._-]", "_"); }

    public ServerProfile copy() {
        ServerProfile p = new ServerProfile();
        p.name = name; p.protocol = protocol; p.host = host; p.port = port; p.user = user; p.startDir = startDir;
        p.passive = passive; p.encoding = encoding; p.timeoutSec = timeoutSec; p.parallel = parallel; p.remember = remember;
        p.sftpAuth = sftpAuth; p.keyFile = keyFile; p.encPassword = encPassword; p.trustedCert = trustedCert; p.hostKey = hostKey;
        return p;
    }
}
