package com.nova.client;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.nova.client.mcmod.Json;

/**
 * NOVA accounts: talks to the NovaAccounts plugin on the game port of the NOVA server (UDP, see the plugin's
 * Crypto.php). Every request: fresh AES-128 + HMAC keys sealed with the server's RSA key that is built into the
 * client (assets/nova/account_pub.pem), so passwords and tokens are never readable on the way.
 * No Android code here: the desktop test uses it as is. Blocking: call from a worker thread.
 */
public final class AccountClient {
    public static final String HOST = "nova-host.mcbe.in";
    public static final int PORT = 12055;

    private final String host; private final int port;
    private final PublicKey key; private final byte[] keyFp;
    private final SecureRandom rnd = new SecureRandom();
    private long timeOffset;                 // server time - phone time (seconds)
    private boolean helloDone;
    /** send through another socket (the NOVA tunnel); null = a fresh socket per request */
    public interface Transport { byte[] exchange(byte[] packet, int tries, int timeoutMs) throws Exception; }
    private volatile Transport transport;
    public void setTransport(Transport t) { transport = t; }
    public volatile String token = "";

    public AccountClient(String host, int port, String publicPem) throws Exception {
        this.host = host; this.port = port;
        String b64 = publicPem.replaceAll("-----[^-]+-----", "").replaceAll("\\s+", "");
        byte[] der = decodeB64(b64);
        key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        keyFp = MessageDigest.getInstance("SHA-256").digest(der);
    }

    /** Result of a request: ok, the server's message and the fields. */
    public static final class Result {
        public final boolean ok; public final String err, msg; public final Map<String, Object> data;
        Result(boolean ok, String err, String msg, Map<String, Object> data) { this.ok = ok; this.err = err; this.msg = msg; this.data = data; }
        public String str(String k) { Object v = data == null ? null : data.get(k); return v == null ? "" : String.valueOf(v); }
    }

    public static final String NET = "net";       // err when the server did not answer

    // ------------------------------------------------------------------ requests
    public Result register(String tag, String pass, String pass2, String device) {
        Map<String, Object> r = req("register"); r.put("tag", tag); r.put("pass", pass); r.put("pass2", pass2); r.put("dev", device);
        return call(r);
    }
    public Result login(String tag, String pass, String device) {
        Map<String, Object> r = req("login"); r.put("tag", tag); r.put("pass", pass); r.put("dev", device);
        return call(r);
    }
    public Result resume(String token) { Map<String, Object> r = req("resume"); r.put("token", token); return call(r); }
    public Result logout(String token) { Map<String, Object> r = req("logout"); r.put("token", token); return call(r); }
    /** any op of the plugin (or of other plugins through it); token null = the client's own */
    public Result op(String op, String token, Map<String, Object> fields) {
        Map<String, Object> r = req(op);
        r.put("token", token != null ? token : this.token);
        if (fields != null) r.putAll(fields);
        return call(r);
    }

    private Map<String, Object> req(String op) {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("op", op);
        return r;
    }

    /** reachability + clock: true when the server answered the hello */
    public synchronized boolean hello() {
        try {
            byte[] nonce = new byte[8]; rnd.nextBytes(nonce);
            byte[] p = new byte[13];
            p[0] = (byte) 0xfe; p[1] = (byte) 0xfd; p[2] = 0x4e; p[3] = 1; p[4] = 0;
            System.arraycopy(nonce, 0, p, 5, 8);
            byte[] a = exchange(p, 2, 1500);
            if (a == null || a.length != 5 + 8 + 8 + 32 || (a[4] & 0xff) != 0x80) return false;
            for (int i = 0; i < 8; i++) if (a[5 + i] != nonce[i]) return false;
            long t = 0;
            for (int i = 0; i < 8; i++) t = (t << 8) | (a[13 + i] & 0xff);
            byte[] fp = new byte[32]; System.arraycopy(a, 21, fp, 0, 32);
            if (!MessageDigest.isEqual(fp, keyFp)) throw new SecurityException("server key changed");
            timeOffset = t - System.currentTimeMillis() / 1000;
            helloDone = true;
            return true;
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private synchronized Result call(Map<String, Object> r) {
        try {
            if (!helloDone && !hello()) return new Result(false, NET, null, null);
            for (int attempt = 0; attempt < 2; attempt++) {
                byte[] nb = new byte[16]; rnd.nextBytes(nb);
                r.put("n", hex(nb));
                r.put("ts", (double) (System.currentTimeMillis() / 1000 + timeOffset));
                byte[] enc = new byte[16], mac = new byte[16], iv = new byte[16];
                rnd.nextBytes(enc); rnd.nextBytes(mac); rnd.nextBytes(iv);
                byte[] keys = new byte[32];
                System.arraycopy(enc, 0, keys, 0, 16); System.arraycopy(mac, 0, keys, 16, 16);
                Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                rsa.init(Cipher.ENCRYPT_MODE, key);
                byte[] sealed = rsa.doFinal(keys);
                Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
                aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(enc, "AES"), new IvParameterSpec(iv));
                byte[] ct = aes.doFinal(Json.write(r).getBytes("UTF-8"));
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                o.write(new byte[]{(byte) 0xfe, (byte) 0xfd, 0x4e, 1, 1});
                o.write(sealed); o.write(iv); o.write(ct);
                Mac hm = Mac.getInstance("HmacSHA256");
                hm.init(new SecretKeySpec(mac, "HmacSHA256"));
                hm.update((byte) 1); hm.update(sealed); hm.update(iv); hm.update(ct);
                o.write(hm.doFinal());
                java.util.Arrays.fill(keys, (byte) 0);
                byte[] a = exchange(o.toByteArray(), 3, 3500);
                if (a == null) return new Result(false, NET, null, null);
                Map<String, Object> res = openAnswer(a, enc, mac);
                if (res == null || !hex(nb).equals(res.get("n"))) return new Result(false, NET, null, null);
                boolean ok = Boolean.TRUE.equals(res.get("ok"));
                String err = res.get("err") == null ? "" : String.valueOf(res.get("err"));
                if (!ok && err.equals("time") && res.get("time") instanceof Double && attempt == 0) {
                    timeOffset = (long) (double) (Double) res.get("time") - System.currentTimeMillis() / 1000;
                    continue;                                 // the phone clock is off: retry with the server's
                }
                return new Result(ok, err, res.get("msg") == null ? null : String.valueOf(res.get("msg")), res);
            }
        } catch (SecurityException e) {
            return new Result(false, "key", "Ключ сервера NOVA изменился. Обнови клиент", null);
        } catch (Exception e) {
            return new Result(false, NET, null, null);
        }
        return new Result(false, NET, null, null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> openAnswer(byte[] a, byte[] enc, byte[] mac) throws Exception {
        if (a.length < 5 + 16 + 16 + 32 || (a[4] & 0xff) != 0x81) return null;
        int ctLen = a.length - 5 - 16 - 32;
        Mac hm = Mac.getInstance("HmacSHA256");
        hm.init(new SecretKeySpec(mac, "HmacSHA256"));
        hm.update((byte) 0x81); hm.update(a, 5, 16 + ctLen);
        byte[] want = hm.doFinal(), got = new byte[32];
        System.arraycopy(a, a.length - 32, got, 0, 32);
        if (!MessageDigest.isEqual(want, got)) return null;
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(enc, "AES"), new IvParameterSpec(a, 5, 16));
        Object v = Json.read(new String(aes.doFinal(a, 21, ctLen), "UTF-8"));
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** send, wait for one answer starting with FE FD 4E 01 */
    private byte[] exchange(byte[] p, int tries, int timeoutMs) throws Exception {
        Transport tr = transport;
        if (tr != null) return tr.exchange(p, tries, timeoutMs);
        InetAddress addr = InetAddress.getByName(host);
        DatagramSocket s = new DatagramSocket();
        try {
            s.setSoTimeout(timeoutMs / tries + 200);
            byte[] buf = new byte[2048];
            for (int t = 0; t < tries; t++) {
                s.send(new DatagramPacket(p, p.length, addr, port));
                long end = System.currentTimeMillis() + timeoutMs / tries + 200;
                while (System.currentTimeMillis() < end) {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    try { s.receive(in); } catch (java.net.SocketTimeoutException e) { break; }
                    if (in.getLength() >= 5 && buf[0] == (byte) 0xfe && buf[1] == (byte) 0xfd && buf[2] == 0x4e && buf[3] == 1) {
                        byte[] r = new byte[in.getLength()];
                        System.arraycopy(buf, 0, r, 0, r.length);
                        return r;
                    }
                }
            }
            return null;
        } finally {
            s.close();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }

    private static byte[] decodeB64(String s) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (char c : s.toCharArray()) {
            int v = A.indexOf(c);
            if (v < 0) continue;
            buf = (buf << 6) | v; bits += 6;
            if (bits >= 8) { bits -= 8; o.write((buf >> bits) & 0xff); }
        }
        return o.toByteArray();
    }
}
