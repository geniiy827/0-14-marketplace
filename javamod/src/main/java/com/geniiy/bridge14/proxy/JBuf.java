package com.geniiy.bridge14.proxy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Буфер протокола Java Edition (varint, строки, позиции). */
public final class JBuf {
    public byte[] b;
    public int pos, len;

    public JBuf() { b = new byte[256]; }
    public JBuf(byte[] d, int off, int end) { b = d; pos = off; len = end; }

    // ---------- запись ----------
    private void need(int n) { if (len + n > b.length) b = Arrays.copyOf(b, Math.max(b.length * 2, len + n)); }
    public JBuf u8(int v) { need(1); b[len++] = (byte) v; return this; }
    public JBuf bool(boolean v) { return u8(v ? 1 : 0); }
    public JBuf i16(int v) { need(2); b[len++] = (byte) (v >> 8); b[len++] = (byte) v; return this; }
    public JBuf i32(int v) { need(4); b[len++] = (byte) (v >> 24); b[len++] = (byte) (v >> 16); b[len++] = (byte) (v >> 8); b[len++] = (byte) v; return this; }
    public JBuf i64(long v) { i32((int) (v >> 32)); return i32((int) v); }
    public JBuf f32(float v) { return i32(Float.floatToIntBits(v)); }
    public JBuf f64(double v) { return i64(Double.doubleToLongBits(v)); }
    public JBuf varint(int v) {
        while ((v & ~0x7F) != 0) { u8((v & 0x7F) | 0x80); v >>>= 7; }
        return u8(v);
    }
    public JBuf raw(byte[] d) { return raw(d, 0, d.length); }
    public JBuf raw(byte[] d, int off, int n) { need(n); System.arraycopy(d, off, b, len, n); len += n; return this; }
    public JBuf str(String s) { byte[] d = s.getBytes(StandardCharsets.UTF_8); varint(d.length); return raw(d); }
    public JBuf uuid(UUID u) { i64(u.getMostSignificantBits()); return i64(u.getLeastSignificantBits()); }
    public JBuf position(int x, int y, int z) { return i64(((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF)); }
    public JBuf angle(float deg) { return u8((int) (deg * 256f / 360f) & 0xff); }
    public byte[] bytes() { return Arrays.copyOf(b, len); }

    public static int varintSize(int v) { int n = 1; while ((v & ~0x7F) != 0) { n++; v >>>= 7; } return n; }

    // ---------- чтение ----------
    public int left() { return len - pos; }
    private void has(int n) { if (pos + n > len) throw new IndexOutOfBoundsException("underflow"); }
    public int u8() { has(1); return b[pos++] & 0xff; }
    public boolean bool() { return u8() != 0; }
    public int i16() { has(2); int v = (short) (((b[pos] & 0xff) << 8) | (b[pos + 1] & 0xff)); pos += 2; return v; }
    public int u16() { return i16() & 0xffff; }
    public int i32() { has(4); int v = ((b[pos] & 0xff) << 24) | ((b[pos + 1] & 0xff) << 16) | ((b[pos + 2] & 0xff) << 8) | (b[pos + 3] & 0xff); pos += 4; return v; }
    public long i64() { long hi = i32() & 0xffffffffL, lo = i32() & 0xffffffffL; return (hi << 32) | lo; }
    public float f32() { return Float.intBitsToFloat(i32()); }
    public double f64() { return Double.longBitsToDouble(i64()); }
    public int varint() {
        int v = 0, s = 0;
        while (true) {
            int k = u8();
            v |= (k & 0x7F) << s;
            if ((k & 0x80) == 0) return v;
            s += 7;
            if (s > 35) throw new RuntimeException("varint too big");
        }
    }
    public String str() { int n = varint(); has(n); String s = new String(b, pos, n, StandardCharsets.UTF_8); pos += n; return s; }
    public UUID uuid() { return new UUID(i64(), i64()); }
    public int[] position() {
        long v = i64();
        int x = (int) (v >> 38), z = (int) (v << 26 >> 38), y = (int) (v << 52 >> 52);
        return new int[]{x, y, z};
    }
    public void skip(int n) { has(n); pos += n; }

    /** Читает предмет (slot): {itemId, count} или null. NBT пропускаем. */
    public int[] slot() {
        if (!bool()) return null;
        int id = varint(), cnt = (byte) u8();
        skipNbt();
        return new int[]{id, cnt};
    }

    /** Пропустить сетевой NBT (с корневым тегом). */
    public void skipNbt() {
        int t = u8();
        if (t == 0) return;
        int nl = u16(); skip(nl);
        skipPayload(t);
    }

    private void skipPayload(int t) {
        switch (t) {
            case 1: skip(1); break;
            case 2: skip(2); break;
            case 3: case 5: skip(4); break;
            case 4: case 6: skip(8); break;
            case 7: skip(i32()); break;
            case 8: skip(u16()); break;
            case 9: { int et = u8(); int n = i32(); for (int i = 0; i < n; i++) skipPayload(et); break; }
            case 10: { while (true) { int ct = u8(); if (ct == 0) break; skip(u16()); skipPayload(ct); } break; }
            case 11: skip(i32() * 4); break;
            case 12: skip(i32() * 8); break;
            default: throw new RuntimeException("bad nbt " + t);
        }
    }
}
