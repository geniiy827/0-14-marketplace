package com.nova.engine.world;

import static com.nova.engine.world.Biomes.*;

/**
 * Biome layer stack of MCPE 0.14.3 (Layer::getDefaultLayers): the same chain and 64-bit LCG as the layers of
 * the Java edition of that time (checked against libminecraftpe: Layer::init/initRandom/nextRandom, the chain,
 * BiomeInitLayer lists, VoronoiZoom in float). Infinite worlds: biome size 4, river size 4.
 * Layer objects keep per-call state (chunkSeed), so a stack is used by one thread at a time.
 */
public final class Layers {
    public final Layer biomes4;   // 1:4 (RiverMixer) - raw biomes for the terrain heights
    public final Layer biomes1;   // 1:1 (Voronoi) - the biome of each column

    public Layers(int seed) {
        Layer l = new Island(1);
        l = new Zoom(2000, l, true);
        l = new AddIsland(1, l);
        l = new Zoom(2001, l, false);
        l = new AddIsland(2, l);
        l = new AddIsland(50, l);
        l = new AddIsland(70, l);
        l = new RemoveTooMuchOcean(2, l);
        l = new AddSnow(2, l);
        l = new AddIsland(3, l);
        l = new Edge(2, l, 0);
        l = new Edge(2, l, 1);
        l = new Edge(3, l, 2);
        l = new Zoom(2002, l, false);
        l = new Zoom(2003, l, false);
        l = new AddIsland(4, l);
        l = new AddMushroom(5, l);
        Layer deep = new DeepOcean(4, l);
        Layer riverInit = new RiverInit(100, deep);
        Layer biome = new BiomeInit(200, deep);
        biome = magnify(1000, biome, 2);
        biome = new BiomeEdge(1000, biome);
        Layer hillsRiver = magnify(1000, riverInit, 2);
        Layer hills = new Hills(1000, biome, hillsRiver);
        Layer river = magnify(1000, riverInit, 2);
        river = magnify(1000, river, 4);
        river = new River(1, river);
        river = new Smooth(1000, river);
        hills = new RareBiome(1001, hills);
        for (int k = 0; k < 4; k++) {
            hills = new Zoom(1000 + k, hills, false);
            if (k == 0) hills = new AddIsland(3, hills);
            if (k == 1) hills = new Shore(1000, hills);
        }
        hills = new Smooth(1000, hills);
        RiverMix mix = new RiverMix(100, hills, river);
        Layer vor = new Voronoi(10, mix);
        long s = seed & 0xffffffffL;
        mix.init(s);
        vor.init(s);
        biomes4 = mix;
        biomes1 = vor;
    }

    static Layer magnify(long seed, Layer l, int n) {
        for (int i = 0; i < n; i++) l = new Zoom(seed + i, l, false);
        return l;
    }

    // ------------------------------------------------------------------ base
    static final long M = 6364136223846793005L, A = 1442695040888963407L;

    public abstract static class Layer {
        final long baseSeed;
        long worldSeed, chunkSeed;
        Layer parent;

        Layer(long base) {
            long b = base;
            b *= b * M + A; b += base;
            b *= b * M + A; b += base;
            b *= b * M + A; b += base;
            baseSeed = b;
        }

        void init(long seed) {
            worldSeed = seed;
            if (parent != null) parent.init(seed);
            worldSeed *= worldSeed * M + A; worldSeed += baseSeed;
            worldSeed *= worldSeed * M + A; worldSeed += baseSeed;
            worldSeed *= worldSeed * M + A; worldSeed += baseSeed;
        }

        void initRandom(long x, long z) {
            chunkSeed = worldSeed;
            chunkSeed *= chunkSeed * M + A; chunkSeed += x;
            chunkSeed *= chunkSeed * M + A; chunkSeed += z;
            chunkSeed *= chunkSeed * M + A; chunkSeed += x;
            chunkSeed *= chunkSeed * M + A; chunkSeed += z;
        }

        int nextInt(int n) {
            int i = (int) ((chunkSeed >> 24) % n);
            if (i < 0) i += n;
            chunkSeed *= chunkSeed * M + A;
            chunkSeed += worldSeed;
            return i;
        }

        int pick2(int a, int b) { return nextInt(2) == 0 ? a : b; }
        int pick4(int a, int b, int c, int d) {
            int i = nextInt(4);
            return i == 0 ? a : i == 1 ? b : i == 2 ? c : d;
        }

        int modeOrRandom(int a, int b, int c, int d) {
            if (b == c && c == d) return b;
            if (a == b && a == c) return a;
            if (a == b && a == d) return a;
            if (a == c && a == d) return a;
            if (a == b && c != d) return a;
            if (a == c && b != d) return a;
            if (a == d && b != c) return a;
            if (b == c && a != d) return b;
            if (b == d && a != c) return b;
            if (c == d && a != b) return c;
            return pick4(a, b, c, d);
        }

        public abstract int[] get(int x, int z, int w, int h);
    }

    static boolean equalOrMesa(int a, int b) {
        if (a == b) return true;
        if (!valid(a) || !valid(b)) return false;
        if (a != MESA_ROCK && a != MESA_CLEAR_ROCK) return CLASS[a] == CLASS[b];
        return b == MESA_ROCK || b == MESA_CLEAR_ROCK;
    }

    // ------------------------------------------------------------------ layers
    static final class Island extends Layer {
        Island(long s) { super(s); }
        public int[] get(int x, int z, int w, int h) {
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(x + j, z + i);
                o[j + i * w] = nextInt(10) == 0 ? 1 : 0;
            }
            if (x > -w && x <= 0 && z > -h && z <= 0) o[-x + -z * w] = 1;
            return o;
        }
    }

    static final class Zoom extends Layer {
        final boolean fuzzy;
        Zoom(long s, Layer p, boolean fuzzy) { super(s); parent = p; this.fuzzy = fuzzy; }
        public int[] get(int x, int z, int w, int h) {
            int px = x >> 1, pz = z >> 1, pw = (w >> 1) + 2, ph = (h >> 1) + 2;
            int[] in = parent.get(px, pz, pw, ph);
            int tw = (pw - 1) << 1, th = (ph - 1) << 1;
            int[] t = new int[tw * th];
            for (int k1 = 0; k1 < ph - 1; k1++) {
                int l1 = (k1 << 1) * tw;
                int i2 = 0;
                int a = in[i2 + k1 * pw];
                int b = in[i2 + (k1 + 1) * pw];
                for (; i2 < pw - 1; i2++) {
                    initRandom((long) (i2 + px) << 1, (long) (k1 + pz) << 1);
                    int c = in[i2 + 1 + k1 * pw];
                    int d = in[i2 + 1 + (k1 + 1) * pw];
                    t[l1] = a;
                    t[l1++ + tw] = pick2(a, b);
                    t[l1] = pick2(a, c);
                    t[l1++ + tw] = fuzzy ? pick4(a, c, b, d) : modeOrRandom(a, c, b, d);
                    a = c;
                    b = d;
                }
            }
            int[] o = new int[w * h];
            for (int j = 0; j < h; j++) System.arraycopy(t, (j + (z & 1)) * tw + (x & 1), o, j * w, w);
            return o;
        }
    }

    static final class AddIsland extends Layer {
        AddIsland(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int a = in[j + i * pw], b = in[j + 2 + i * pw], c = in[j + (i + 2) * pw], d = in[j + 2 + (i + 2) * pw];
                int k = in[j + 1 + (i + 1) * pw];
                initRandom(j + x, i + z);
                int r;
                if (k != 0 || (a == 0 && b == 0 && c == 0 && d == 0)) {
                    if (k > 0 && (a == 0 || b == 0 || c == 0 || d == 0)) {
                        if (nextInt(5) == 0) r = k == 4 ? 4 : 0; else r = k;
                    } else r = k;
                } else {
                    int n = 1, v = 1;
                    if (a != 0 && nextInt(n++) == 0) v = a;
                    if (b != 0 && nextInt(n++) == 0) v = b;
                    if (c != 0 && nextInt(n++) == 0) v = c;
                    if (d != 0 && nextInt(n++) == 0) v = d;
                    if (nextInt(3) == 0) r = v;
                    else r = v == 4 ? 4 : 0;
                }
                o[j + i * w] = r;
            }
            return o;
        }
    }

    static final class RemoveTooMuchOcean extends Layer {
        RemoveTooMuchOcean(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int n = in[j + 1 + i * pw], e = in[j + 2 + (i + 1) * pw], wv = in[j + (i + 1) * pw], s = in[j + 1 + (i + 2) * pw];
                int k = in[j + 1 + (i + 1) * pw];
                o[j + i * w] = k;
                initRandom(j + x, i + z);
                if (k == 0 && n == 0 && e == 0 && wv == 0 && s == 0 && nextInt(2) == 0) o[j + i * w] = 1;
            }
            return o;
        }
    }

    static final class AddSnow extends Layer {
        AddSnow(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int k = in[j + 1 + (i + 1) * pw];
                initRandom(j + x, i + z);
                if (k == 0) o[j + i * w] = 0;
                else {
                    int r = nextInt(6);
                    o[j + i * w] = r == 0 ? 4 : r <= 1 ? 3 : 1;
                }
            }
            return o;
        }
    }

    /** AddEdgeLayer: mode 0 cool/warm, 1 heat/ice, 2 introduce special */
    static final class Edge extends Layer {
        final int mode;
        Edge(long s, Layer p, int mode) { super(s); parent = p; this.mode = mode; }
        public int[] get(int x, int z, int w, int h) {
            int[] o = new int[w * h];
            if (mode == 2) {
                int[] in = parent.get(x, z, w, h);
                for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                    initRandom(j + x, i + z);
                    int k = in[j + i * w];
                    if (k != 0 && nextInt(13) == 0) k |= (1 + nextInt(15)) << 8 & 3840;
                    o[j + i * w] = k;
                }
                return o;
            }
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                int k = in[j + 1 + (i + 1) * pw];
                int n = in[j + 1 + i * pw], e = in[j + 2 + (i + 1) * pw], wv = in[j + (i + 1) * pw], s = in[j + 1 + (i + 2) * pw];
                if (mode == 0 && k == 1) {
                    boolean f = n == 3 || e == 3 || wv == 3 || s == 3;
                    boolean f1 = n == 4 || e == 4 || wv == 4 || s == 4;
                    if (f || f1) k = 2;
                } else if (mode == 1 && k == 4) {
                    boolean f = n == 2 || e == 2 || wv == 2 || s == 2;
                    boolean f1 = n == 1 || e == 1 || wv == 1 || s == 1;
                    if (f1 || f) k = 3;
                }
                o[j + i * w] = k;
            }
            return o;
        }
    }

    static final class AddMushroom extends Layer {
        AddMushroom(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int a = in[j + i * pw], b = in[j + 2 + i * pw], c = in[j + (i + 2) * pw], d = in[j + 2 + (i + 2) * pw];
                int k = in[j + 1 + (i + 1) * pw];
                initRandom(j + x, i + z);
                if (k == 0 && a == 0 && b == 0 && c == 0 && d == 0 && nextInt(100) == 0) o[j + i * w] = MUSHROOM_ISLAND;
                else o[j + i * w] = k;
            }
            return o;
        }
    }

    static final class DeepOcean extends Layer {
        DeepOcean(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int n = in[j + 1 + i * pw], e = in[j + 2 + (i + 1) * pw], wv = in[j + (i + 1) * pw], s = in[j + 1 + (i + 2) * pw];
                int k = in[j + 1 + (i + 1) * pw];
                int c = 0;
                if (n == 0) c++;
                if (e == 0) c++;
                if (wv == 0) c++;
                if (s == 0) c++;
                o[j + i * w] = k == 0 && c > 3 ? DEEP_OCEAN : k;
            }
            return o;
        }
    }

    static final class RiverInit extends Layer {
        RiverInit(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int[] in = parent.get(x, z, w, h);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                o[j + i * w] = in[j + i * w] > 0 ? nextInt(299999) + 2 : 0;
            }
            return o;
        }
    }

    static final int[] WARM = {DESERT, DESERT, DESERT, SAVANNA, SAVANNA, PLAINS};
    // MCPE 0.14: three plains in the medium list (Java has one)
    static final int[] MEDIUM = {FOREST, ROOFED_FOREST, EXTREME_HILLS, PLAINS, PLAINS, PLAINS, BIRCH_FOREST, SWAMPLAND};
    static final int[] COLD = {FOREST, EXTREME_HILLS, TAIGA, PLAINS};
    static final int[] ICE = {ICE_FLATS, ICE_FLATS, ICE_FLATS, TAIGA_COLD};

    static final class BiomeInit extends Layer {
        BiomeInit(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int[] in = parent.get(x, z, w, h);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                int k = in[j + i * w];
                int l = (k & 3840) >> 8;
                k &= ~3840;
                int r;
                if (isOceanic(k)) r = k;
                else if (k == MUSHROOM_ISLAND) r = k;
                else if (k == 1) r = l > 0 ? (nextInt(3) == 0 ? MESA_CLEAR_ROCK : MESA_ROCK) : WARM[nextInt(WARM.length)];
                else if (k == 2) r = l > 0 ? JUNGLE : MEDIUM[nextInt(MEDIUM.length)];
                else if (k == 3) r = l > 0 ? REDWOOD_TAIGA : COLD[nextInt(COLD.length)];
                else if (k == 4) r = ICE[nextInt(ICE.length)];
                else r = MUSHROOM_ISLAND;
                o[j + i * w] = r;
            }
            return o;
        }
    }

    static final class BiomeEdge extends Layer {
        BiomeEdge(long s, Layer p) { super(s); parent = p; }

        static boolean neighbors(int a, int b) {
            if (equalOrMesa(a, b)) return true;
            if (!valid(a) || !valid(b)) return false;
            int ta = tempCategory(a), tb = tempCategory(b);
            return ta == tb || ta == T_MEDIUM || tb == T_MEDIUM;
        }

        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                int k = in[j + 1 + (i + 1) * pw];
                int n = in[j + 1 + i * pw], e = in[j + 2 + (i + 1) * pw], wv = in[j + (i + 1) * pw], s = in[j + 1 + (i + 2) * pw];
                int r;
                if (equalOrMesa(k, EXTREME_HILLS)) {
                    r = neighbors(n, EXTREME_HILLS) && neighbors(e, EXTREME_HILLS) && neighbors(wv, EXTREME_HILLS) && neighbors(s, EXTREME_HILLS) ? k : SMALLER_EXTREME_HILLS;
                } else if (k == MESA_ROCK) {
                    r = equalOrMesa(n, k) && equalOrMesa(e, k) && equalOrMesa(wv, k) && equalOrMesa(s, k) ? k : MESA;
                } else if (k == MESA_CLEAR_ROCK) {
                    r = equalOrMesa(n, k) && equalOrMesa(e, k) && equalOrMesa(wv, k) && equalOrMesa(s, k) ? k : MESA;
                } else if (k == REDWOOD_TAIGA) {
                    r = equalOrMesa(n, k) && equalOrMesa(e, k) && equalOrMesa(wv, k) && equalOrMesa(s, k) ? k : TAIGA;
                } else if (k == DESERT) {
                    r = n != ICE_FLATS && e != ICE_FLATS && wv != ICE_FLATS && s != ICE_FLATS ? k : EXTREME_HILLS_WITH_TREES;
                } else if (k == SWAMPLAND) {
                    if (n != DESERT && e != DESERT && wv != DESERT && s != DESERT && n != TAIGA_COLD && e != TAIGA_COLD && wv != TAIGA_COLD
                            && s != TAIGA_COLD && n != ICE_FLATS && e != ICE_FLATS && wv != ICE_FLATS && s != ICE_FLATS) {
                        r = n != JUNGLE && e != JUNGLE && wv != JUNGLE && s != JUNGLE ? k : JUNGLE_EDGE;
                    } else r = PLAINS;
                } else r = k;
                o[j + i * w] = r;
            }
            return o;
        }
    }

    static final class Hills extends Layer {
        final Layer river;
        Hills(long s, Layer p, Layer river) { super(s); parent = p; this.river = river; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] rv = river.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                int k = in[j + 1 + (i + 1) * pw];
                int l = rv[j + 1 + (i + 1) * pw];
                boolean flag = (l - 2) % 29 == 0;
                boolean mut = valid(k) && MUTATED[k];
                if (k != 0 && l >= 2 && (l - 2) % 29 == 1 && !mut) {
                    int m = valid(k) ? MUTATION[k] : -1;
                    o[j + i * w] = m < 0 ? k : m;
                } else if (nextInt(3) != 0 && !flag) {
                    o[j + i * w] = k;
                } else {
                    int t = k;
                    if (k == DESERT) t = DESERT_HILLS;
                    else if (k == FOREST) t = FOREST_HILLS;
                    else if (k == BIRCH_FOREST) t = BIRCH_FOREST_HILLS;
                    else if (k == ROOFED_FOREST) t = PLAINS;
                    else if (k == TAIGA) t = TAIGA_HILLS;
                    else if (k == REDWOOD_TAIGA) t = REDWOOD_TAIGA_HILLS;
                    else if (k == TAIGA_COLD) t = TAIGA_COLD_HILLS;
                    else if (k == PLAINS) t = nextInt(3) == 0 ? FOREST_HILLS : FOREST;
                    else if (k == ICE_FLATS) t = ICE_MOUNTAINS;
                    else if (k == JUNGLE) t = JUNGLE_HILLS;
                    else if (k == OCEAN) t = DEEP_OCEAN;
                    else if (k == EXTREME_HILLS) t = EXTREME_HILLS_WITH_TREES;
                    else if (k == SAVANNA) t = SAVANNA_ROCK;
                    else if (equalOrMesa(k, MESA_ROCK)) t = MESA;
                    else if (k == DEEP_OCEAN && nextInt(3) == 0) t = nextInt(2) == 0 ? PLAINS : FOREST;
                    int j2 = t;
                    if (flag && j2 != k) {
                        int m = valid(t) ? MUTATION[t] : -1;
                        j2 = m < 0 ? k : m;
                    }
                    if (j2 == k) o[j + i * w] = k;
                    else {
                        int n = in[j + 1 + i * pw], e = in[j + 2 + (i + 1) * pw], wv = in[j + (i + 1) * pw], s = in[j + 1 + (i + 2) * pw];
                        int c = 0;
                        if (equalOrMesa(n, k)) c++;
                        if (equalOrMesa(e, k)) c++;
                        if (equalOrMesa(wv, k)) c++;
                        if (equalOrMesa(s, k)) c++;
                        o[j + i * w] = c >= 3 ? j2 : k;
                    }
                }
            }
            return o;
        }
    }

    static int riverFilter(int v) { return v >= 2 ? 2 + (v & 1) : v; }

    static final class River extends Layer {
        River(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int a = riverFilter(in[j + (i + 1) * pw]), b = riverFilter(in[j + 2 + (i + 1) * pw]);
                int c = riverFilter(in[j + 1 + i * pw]), d = riverFilter(in[j + 1 + (i + 2) * pw]);
                int k = riverFilter(in[j + 1 + (i + 1) * pw]);
                o[j + i * w] = k == a && k == c && k == b && k == d ? -1 : RIVER;
            }
            return o;
        }
    }

    static final class Smooth extends Layer {
        Smooth(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                int a = in[j + (i + 1) * pw], b = in[j + 2 + (i + 1) * pw], c = in[j + 1 + i * pw], d = in[j + 1 + (i + 2) * pw];
                int k = in[j + 1 + (i + 1) * pw];
                if (a == b && c == d) {
                    initRandom(j + x, i + z);
                    k = nextInt(2) == 0 ? a : c;
                } else {
                    if (a == b) k = a;
                    if (c == d) k = c;
                }
                o[j + i * w] = k;
            }
            return o;
        }
    }

    static final class RareBiome extends Layer {
        RareBiome(long s, Layer p) { super(s); parent = p; }
        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                int k = in[j + 1 + (i + 1) * pw];
                o[j + i * w] = nextInt(57) == 0 && k == PLAINS ? MUTATED_PLAINS : k;
            }
            return o;
        }
    }

    static final class Shore extends Layer {
        Shore(long s, Layer p) { super(s); parent = p; }

        static boolean jungleCompatible(int id) {
            if (valid(id) && CLASS[id] == C_JUNGLE) return true;
            return id == JUNGLE_EDGE || id == JUNGLE || id == JUNGLE_HILLS || id == FOREST || id == TAIGA || isOceanic(id);
        }
        static boolean mesa(int id) { return valid(id) && CLASS[id] == C_MESA; }

        public int[] get(int x, int z, int w, int h) {
            int pw = w + 2;
            int[] in = parent.get(x - 1, z - 1, w + 2, h + 2);
            int[] o = new int[w * h];
            for (int i = 0; i < h; i++) for (int j = 0; j < w; j++) {
                initRandom(j + x, i + z);
                int k = in[j + 1 + (i + 1) * pw];
                int n = in[j + 1 + i * pw], e = in[j + 2 + (i + 1) * pw], wv = in[j + (i + 1) * pw], s = in[j + 1 + (i + 2) * pw];
                int r;
                if (k == MUSHROOM_ISLAND) {
                    r = n != OCEAN && e != OCEAN && wv != OCEAN && s != OCEAN ? k : MUSHROOM_ISLAND_SHORE;
                } else if (valid(k) && CLASS[k] == C_JUNGLE) {
                    if (jungleCompatible(n) && jungleCompatible(e) && jungleCompatible(wv) && jungleCompatible(s)) {
                        r = !isOceanic(n) && !isOceanic(e) && !isOceanic(wv) && !isOceanic(s) ? k : BEACHES;
                    } else r = JUNGLE_EDGE;
                } else if (k != EXTREME_HILLS && k != EXTREME_HILLS_WITH_TREES && k != SMALLER_EXTREME_HILLS) {
                    if (valid(k) && SNOWY[k]) {
                        r = replaceIfOcean(k, n, e, wv, s, COLD_BEACH);
                    } else if (k != MESA && k != MESA_ROCK) {
                        if (k != OCEAN && k != DEEP_OCEAN && k != RIVER && k != SWAMPLAND) {
                            r = !isOceanic(n) && !isOceanic(e) && !isOceanic(wv) && !isOceanic(s) ? k : BEACHES;
                        } else r = k;
                    } else {
                        if (!isOceanic(n) && !isOceanic(e) && !isOceanic(wv) && !isOceanic(s)) {
                            r = mesa(n) && mesa(e) && mesa(wv) && mesa(s) ? k : DESERT;
                        } else r = k;
                    }
                } else {
                    r = replaceIfOcean(k, n, e, wv, s, STONE_BEACH);
                }
                o[j + i * w] = r;
            }
            return o;
        }

        static int replaceIfOcean(int k, int n, int e, int w, int s, int rep) {
            if (isOceanic(k)) return k;
            return !isOceanic(n) && !isOceanic(e) && !isOceanic(w) && !isOceanic(s) ? k : rep;
        }
    }

    static final class RiverMix extends Layer {
        final Layer biome, river;
        RiverMix(long s, Layer biome, Layer river) { super(s); this.biome = biome; this.river = river; }
        @Override void init(long seed) {
            biome.init(seed);
            river.init(seed);
            super.init(seed);
        }
        public int[] get(int x, int z, int w, int h) {
            int[] b = biome.get(x, z, w, h), r = river.get(x, z, w, h);
            int[] o = new int[w * h];
            for (int i = 0; i < w * h; i++) {
                int v = b[i];
                if (v != OCEAN && v != DEEP_OCEAN) {
                    if (r[i] == RIVER) {
                        if (v == ICE_FLATS) o[i] = FROZEN_RIVER;
                        else if (v != MUSHROOM_ISLAND && v != MUSHROOM_ISLAND_SHORE) o[i] = r[i] & 255;
                        else o[i] = MUSHROOM_ISLAND_SHORE;
                    } else o[i] = v;
                } else o[i] = v;
            }
            return o;
        }
    }

    static final class Voronoi extends Layer {
        Voronoi(long s, Layer p) { super(s); parent = p; }
        float off(float base) { return base + ((float) nextInt(1024) * (1f / 1024f) + -0.5f) * 3.6f; }
        float off0() { return ((float) nextInt(1024) * (1f / 1024f) + -0.5f) * 3.6f; }
        public int[] get(int x, int z, int w, int h) {
            x -= 2; z -= 2;
            int px = x >> 2, pz = z >> 2, pw = (w >> 2) + 2, ph = (h >> 2) + 2;
            int[] in = parent.get(px, pz, pw, ph);
            int tw = (pw - 1) << 2, th = (ph - 1) << 2;
            int[] t = new int[tw * th];
            for (int k1 = 0; k1 < ph - 1; k1++) {
                int l1 = 0;
                int i2 = in[l1 + k1 * pw];
                int j2 = in[l1 + (k1 + 1) * pw];
                for (; l1 < pw - 1; l1++) {
                    initRandom((long) (l1 + px) << 2, (long) (k1 + pz) << 2);
                    float d1 = off0(), d2 = off0();
                    initRandom((long) (l1 + px + 1) << 2, (long) (k1 + pz) << 2);
                    float d3 = off(4f), d4 = off0();
                    initRandom((long) (l1 + px) << 2, (long) (k1 + pz + 1) << 2);
                    float d5 = off0(), d6 = off(4f);
                    initRandom((long) (l1 + px + 1) << 2, (long) (k1 + pz + 1) << 2);
                    float d7 = off(4f), d8 = off(4f);
                    int k2 = in[l1 + 1 + k1 * pw] & 255;
                    int l2 = in[l1 + 1 + (k1 + 1) * pw] & 255;
                    for (int i3 = 0; i3 < 4; i3++) {
                        int j3 = ((k1 << 2) + i3) * tw + (l1 << 2);
                        float fi = (float) i3;
                        float a2 = (fi - d2) * (fi - d2), b2 = (fi - d4) * (fi - d4), c2 = (fi - d6) * (fi - d6), e2 = (fi - d8) * (fi - d8);
                        for (int k3 = 0; k3 < 4; k3++) {
                            float fk = (float) k3;
                            float d9 = a2 + (fk - d1) * (fk - d1);
                            float d10 = b2 + (fk - d3) * (fk - d3);
                            float d11 = c2 + (fk - d5) * (fk - d5);
                            float d12 = e2 + (fk - d7) * (fk - d7);
                            if (d9 < d10 && d9 < d11 && d9 < d12) t[j3++] = i2 & 255;
                            else if (d10 < d9 && d10 < d11 && d10 < d12) t[j3++] = k2;
                            else if (d11 < d9 && d11 < d10 && d11 < d12) t[j3++] = j2 & 255;
                            else t[j3++] = l2;
                        }
                    }
                    i2 = k2;
                    j2 = l2;
                }
            }
            int[] o = new int[w * h];
            for (int j = 0; j < h; j++) System.arraycopy(t, (j + (z & 3)) * tw + (x & 3), o, j * w, w);
            return o;
        }
    }
}
