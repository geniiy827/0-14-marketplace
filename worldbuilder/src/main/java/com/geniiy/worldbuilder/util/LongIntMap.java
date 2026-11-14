package com.geniiy.worldbuilder.util;

import java.util.Arrays;

/** Простая хэш-таблица long → int с открытой адресацией (без упаковки в объекты). */
public final class LongIntMap {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] keys;
    private int[] vals;
    private int size, mask;

    public LongIntMap() { this(64); }
    public LongIntMap(int cap) {
        int c = Integer.highestOneBit(Math.max(16, cap) * 2 - 1);
        keys = new long[c]; vals = new int[c]; mask = c - 1;
        Arrays.fill(keys, EMPTY);
    }
    private static int hash(long k) { k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33; return (int) k; }

    public int get(long k, int def) {
        int i = hash(k) & mask;
        while (true) {
            long kk = keys[i];
            if (kk == EMPTY) return def;
            if (kk == k) return vals[i];
            i = (i + 1) & mask;
        }
    }
    public boolean containsKey(long k) { return get(k, Integer.MIN_VALUE) != Integer.MIN_VALUE || getSlow(k); }
    private boolean getSlow(long k) {
        int i = hash(k) & mask;
        while (true) { long kk = keys[i]; if (kk == EMPTY) return false; if (kk == k) return true; i = (i + 1) & mask; }
    }
    public void put(long k, int v) {
        if (size * 2 >= keys.length) grow();
        int i = hash(k) & mask;
        while (true) {
            long kk = keys[i];
            if (kk == EMPTY) { keys[i] = k; vals[i] = v; size++; return; }
            if (kk == k) { vals[i] = v; return; }
            i = (i + 1) & mask;
        }
    }
    private void grow() {
        long[] ok = keys; int[] ov = vals;
        keys = new long[ok.length * 2]; vals = new int[ok.length * 2]; mask = keys.length - 1; size = 0;
        Arrays.fill(keys, EMPTY);
        for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) put(ok[i], ov[i]);
    }
    public int size() { return size; }
    public void clear() { Arrays.fill(keys, EMPTY); size = 0; }
    public interface Visitor { void visit(long k, int v); }
    public void forEach(Visitor v) { for (int i = 0; i < keys.length; i++) if (keys[i] != EMPTY) v.visit(keys[i], vals[i]); }
    public long memoryBytes() { return keys.length * 12L; }
}
