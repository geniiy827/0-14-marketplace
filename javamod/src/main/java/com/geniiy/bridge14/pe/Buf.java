package com.geniiy.bridge14.pe;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Простой буфер чтения/записи. Big-endian по умолчанию, L* — little-endian. */
public final class Buf {
    public byte[] b;
    public int pos;
    public int len;

    public Buf() { this(64); }
    public Buf(int cap) { b = new byte[cap]; }
    public Buf(byte[] data) { this(data, 0, data.length); }
    public Buf(byte[] data, int off, int end) { b = data; pos = off; len = end; }

    // ---------- запись ----------
    private void need(int n) {
        if (len + n > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, len + n));
    }
    public Buf u8(int v) { need(1); b[len++] = (byte) v; return this; }
    public Buf bool(boolean v) { return u8(v ? 1 : 0); }
    public Buf i16(int v) { need(2); b[len++] = (byte) (v >> 8); b[len++] = (byte) v; return this; }
    public Buf li16(int v) { need(2); b[len++] = (byte) v; b[len++] = (byte) (v >> 8); return this; }
    public Buf i24le(int v) { need(3); b[len++] = (byte) v; b[len++] = (byte) (v >> 8); b[len++] = (byte) (v >> 16); return this; }
    public Buf i32(int v) { need(4); b[len++] = (byte) (v >> 24); b[len++] = (byte) (v >> 16); b[len++] = (byte) (v >> 8); b[len++] = (byte) v; return this; }
    public Buf li32(int v) { need(4); b[len++] = (byte) v; b[len++] = (byte) (v >> 8); b[len++] = (byte) (v >> 16); b[len++] = (byte) (v >> 24); return this; }
    public Buf i64(long v) { i32((int) (v >> 32)); return i32((int) v); }
    public Buf f32(float v) { return i32(Float.floatToIntBits(v)); }
    public Buf raw(byte[] d) { return raw(d, 0, d.length); }
    public Buf raw(byte[] d, int off, int n) { need(n); System.arraycopy(d, off, b, len, n); len += n; return this; }
    public Buf zeros(int n) { need(n); Arrays.fill(b, len, len + n, (byte) 0); len += n; return this; }
    public Buf str(String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); i16(d.length); return raw(d); }
    public Buf strBytes(byte[] d) { i16(d.length); return raw(d); }
    public Buf addr4(byte[] ip, int port) {
        u8(4);
        for (int i = 0; i < 4; i++) u8(~ip[i] & 0xff);
        return i16(port);
    }

    public byte[] bytes() { return Arrays.copyOf(b, len); }

    // ---------- чтение ----------
    public int left() { return len - pos; }
    private void has(int n) { if (pos + n > len) throw new IndexOutOfBoundsException("buffer underflow"); }
    public int u8() { has(1); return b[pos++] & 0xff; }
    public boolean bool() { return u8() != 0; }
    public int u16() { has(2); int v = ((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff); pos += 2; return v; }
    public int s16() { return (short) u16(); }
    public int lu16() { has(2); int v = (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8); pos += 2; return v; }
    public int i24le() { has(3); int v = (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8) | ((b[pos + 2] & 0xff) << 16); pos += 3; return v; }
    public int i32() { has(4); int v = ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff); pos += 4; return v; }
    public int li32() { has(4); int v = (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8) | ((b[pos + 2] & 0xff) << 16) | ((b[pos + 3] & 0xff) << 24); pos += 4; return v; }
    public long i64() { long hi = i32() & 0xffffffffL; long lo = i32() & 0xffffffffL; return (hi << 32) | lo; }
    public long li64() { long lo = li32() & 0xffffffffL; long hi = li32() & 0xffffffffL; return (hi << 32) | lo; }
    public float f32() { return Float.intBitsToFloat(i32()); }
    public float lf32() { return Float.intBitsToFloat(li32()); }
    public byte[] take(int n) { has(n); byte[] r = Arrays.copyOfRange(b, pos, pos + n); pos += n; return r; }
    public void skip(int n) { has(n); pos += n; }
    public String str() { int n = u16(); has(n); String s = new String(b, pos, n, StandardCharsets.UTF_8); pos += n; return s; }
    public byte[] rest() { return take(left()); }

    /** Адрес RakNet: IPv4 (инвертированные байты) или IPv6. Возвращает "ip:port". */
    public String addr() {
        int ver = u8();
        if (ver == 4) {
            int a = ~u8() & 0xff, c = ~u8() & 0xff, d = ~u8() & 0xff, e = ~u8() & 0xff;
            int port = u16();
            return a + "." + c + "." + d + "." + e + ":" + port;
        } else {
            // sockaddr_in6: family(2, LE) port(2) flowinfo(4) addr(16) scope(4)
            skip(2); int port = u16(); skip(4); skip(16); skip(4);
            return "[v6]:" + port;
        }
    }
}
