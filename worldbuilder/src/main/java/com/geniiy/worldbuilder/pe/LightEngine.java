package com.geniiy.worldbuilder.pe;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.util.Pos;

import java.util.*;

/**
 * Расчёт света для изменённых чанков по правилам ТЗ (раздел 24): небесный свет сверху колонки 15
 * до первого блока с light_filter &gt; 1, затем BFS: уровень соседа = текущий − max(1, filter соседа).
 * Свет от блоков — источники с light_emission &gt; 0. Значения света соседних (не пересчитываемых) чанков
 * на границе служат дополнительными источниками.
 */
public final class LightEngine {
    private LightEngine() {}

    public static void relight(PeWorld w, Collection<PeChunk> chunks, PeWorld.Progress pr) {
        if (chunks.isEmpty()) return;
        int[] filter = PeBlocks.get().lightFilter;
        int[] emit = PeBlocks.get().lightEmission;
        Map<Long, PeChunk> set = new HashMap<>();
        for (PeChunk c : chunks) if (!c.corrupt) set.put(Pos.chunkKey(c.cx, c.cz), c);
        Map<Long, byte[]> sky = new HashMap<>(), blk = new HashMap<>();
        for (var e : set.entrySet()) { sky.put(e.getKey(), new byte[32768]); blk.put(e.getKey(), new byte[32768]); }

        IntQueue q = new IntQueue();
        // ---- небо ----
        int i = 0;
        for (PeChunk c : set.values()) {
            byte[] s = sky.get(Pos.chunkKey(c.cx, c.cz));
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int base = (x << 11) | (z << 7);
                for (int y = 127; y >= 0; y--) {
                    int f = filter[c.blocks[base | y] & 0xFF];
                    if (f > 1) break;
                    s[base | y] = 15;
                }
            }
            if (pr != null) pr.update("Свет: небо " + (++i) + "/" + set.size(), 0.1 * i / set.size());
        }
        propagate(set, sky, filter, q, true, w);
        // ---- блоки ----
        for (PeChunk c : set.values()) {
            byte[] b = blk.get(Pos.chunkKey(c.cx, c.cz));
            for (int idx = 0; idx < 32768; idx++) {
                int e = emit[c.blocks[idx] & 0xFF];
                if (e > 0) b[idx] = (byte) e;
            }
        }
        if (pr != null) pr.update("Свет: источники", 0.3);
        propagate(set, blk, filter, q, false, w);
        // ---- запись полубайтов ----
        for (PeChunk c : set.values()) {
            long k = Pos.chunkKey(c.cx, c.cz);
            byte[] s = sky.get(k), b = blk.get(k);
            Arrays.fill(c.skyLight, (byte) 0);
            Arrays.fill(c.blockLight, (byte) 0);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 128; y++) {
                int idx = (x << 11) | (z << 7) | y;
                PeChunk.setNibble(c.skyLight, x, y, z, s[idx]);
                PeChunk.setNibble(c.blockLight, x, y, z, b[idx]);
            }
        }
        if (pr != null) pr.update("Свет посчитан", 0.45);
    }

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private static void propagate(Map<Long, PeChunk> set, Map<Long, byte[]> light, int[] filter, IntQueue q, boolean skyMode, PeWorld w) {
        // Очередь хранит мировые координаты в трёх int подряд
        q.clear();
        for (PeChunk c : set.values()) {
            byte[] l = light.get(Pos.chunkKey(c.cx, c.cz));
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y < 128; y++) {
                int idx = (x << 11) | (z << 7) | y;
                if (l[idx] > 1) q.add3(c.cx * 16 + x, y, c.cz * 16 + z);
            }
            // источники от соседних чанков (не пересчитываемых)
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                long nk = Pos.chunkKey(c.cx + d[0], c.cz + d[1]);
                if (set.containsKey(nk)) continue;
                PeChunk n = w.chunkExists(c.cx + d[0], c.cz + d[1]) ? w.getChunk(c.cx + d[0], c.cz + d[1]) : null;
                if (n == null || n.corrupt) continue;
                for (int a = 0; a < 16; a++) for (int y = 0; y < 128; y++) {
                    int nx = d[0] == 1 ? 0 : d[0] == -1 ? 15 : a;
                    int nz = d[1] == 1 ? 0 : d[1] == -1 ? 15 : a;
                    int lx = d[0] == 1 ? 15 : d[0] == -1 ? 0 : a;
                    int lz = d[1] == 1 ? 15 : d[1] == -1 ? 0 : a;
                    int nl = skyMode ? n.getSky(nx, y, nz) : n.getBlockLight(nx, y, nz);
                    int idx = (lx << 11) | (lz << 7) | y;
                    int nv = nl - Math.max(1, filter[c.blocks[idx] & 0xFF]);
                    if (nv > l[idx]) { l[idx] = (byte) nv; q.add3(c.cx * 16 + lx, y, c.cz * 16 + lz); }
                }
            }
        }
        PeChunk cache = null; byte[] cacheL = null; long cacheK = Long.MIN_VALUE;
        while (!q.isEmpty()) {
            int x = q.poll(), y = q.poll(), z = q.poll();
            long k = Pos.chunkKey(x >> 4, z >> 4);
            if (k != cacheK) { cache = set.get(k); cacheL = light.get(k); cacheK = k; }
            int cur = cacheL[((x & 15) << 11) | ((z & 15) << 7) | y];
            if (cur <= 1) continue;
            for (int[] d : DIRS) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                if (ny < 0 || ny > 127) continue;
                long nk = Pos.chunkKey(nx >> 4, nz >> 4);
                PeChunk nc; byte[] nl;
                if (nk == cacheK) { nc = cache; nl = cacheL; }
                else { nc = set.get(nk); if (nc == null) continue; nl = light.get(nk); }
                int idx = ((nx & 15) << 11) | ((nz & 15) << 7) | ny;
                int v = cur - Math.max(1, filter[nc.blocks[idx] & 0xFF]);
                if (v > nl[idx]) { nl[idx] = (byte) v; q.add3(nx, ny, nz); }
            }
        }
    }

    static final class IntQueue {
        int[] a = new int[1 << 16]; int head, tail;
        void clear() { head = tail = 0; }
        boolean isEmpty() { return head == tail; }
        void add3(int x, int y, int z) {
            if (tail + 3 > a.length) {
                if (head > a.length / 2) { System.arraycopy(a, head, a, 0, tail - head); tail -= head; head = 0; }
                else a = Arrays.copyOf(a, a.length * 2);
            }
            a[tail++] = x; a[tail++] = y; a[tail++] = z;
        }
        int poll() { return a[head++]; }
    }

    /** Небо и блоки для режима «как в игре» на лету (без записи): используем данные чанка. */
}
