package com.nova.engine.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Villages of MCPE 0.14.3 (VillageFeature / VillageStart / StartPiece / StraightRoad / VillagePiece / LightPost).
 * Layout logic ported from the binary (isFeatureChunk 0x5e6cb0: chunk coordinate seed, spacing 40, offset u%28;
 * VillageStart 0x63c69c; piece weights; Math::nextInt; nextBoolean = bit 27). The buildings are replayed from the
 * programs recorded by running each piece's postProcess of the original in an emulator (VillageData,
 * tools/emu_pieces.py). Directions: 0 south, 1 west, 2 north, 3 east.
 */
final class Village {
    static final int S = 0, W = 1, N = 2, E = 3;
    static final int T_LIGHT = 9, T_ROAD = 10, T_WELL = 11;
    static final String[] NAME = {"SimpleHouse", "SmallTemple", "BookHouse", "SmallHut", "PigHouse", "DoubleFarmland", "Farmland",
            "Smithy", "TwoRoomHouse", "LightPost", "StraightRoad", "Well"};
    static final int[][] SIZE = {{5, 6, 5}, {5, 12, 9}, {9, 9, 6}, {4, 6, 5}, {9, 7, 11}, {13, 4, 9}, {7, 4, 9}, {10, 6, 7}, {9, 7, 12},
            {3, 4, 2}};
    static final int[] WEIGHT = {4, 20, 20, 3, 15, 3, 3, 15, 8};
    static final int[] VALID_BIOMES = {1, 2, 35};          // plains, desert, savanna

    // ------------------------------------------------------------------ boxes
    static final class Box {
        int x0, y0, z0, x1, y1, z1;
        Box(int x0, int y0, int z0, int x1, int y1, int z1) { this.x0 = x0; this.y0 = y0; this.z0 = z0; this.x1 = x1; this.y1 = y1; this.z1 = z1; }
        boolean intersects(Box o) { return x1 >= o.x0 && x0 <= o.x1 && z1 >= o.z0 && z0 <= o.z1 && y1 >= o.y0 && y0 <= o.y1; }
        boolean inside(int x, int y, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1 && y >= y0 && y <= y1; }
    }

    /** BoundingBox::orientBox (getComponentToAddBoundingBox) */
    static Box orient(int x, int y, int z, int ox, int oy, int oz, int sx, int sy, int sz, int dir) {
        switch (dir) {
            case N: return new Box(x + ox, y + oy, z - sz + 1 + oz, x + sx - 1 + ox, y + sy - 1 + oy, z + oz);
            case S: return new Box(x + ox, y + oy, z + oz, x + sx - 1 + ox, y + sy - 1 + oy, z + sz - 1 + oz);
            case W: return new Box(x - sz + 1 + oz, y + oy, z + ox, x + oz, y + sy - 1 + oy, z + sx - 1 + ox);
            default: return new Box(x + oz, y + oy, z + ox, x + sz - 1 + oz, y + sy - 1 + oy, z + sx - 1 + ox);
        }
    }

    // ------------------------------------------------------------------ pieces
    static final class Piece {
        final int type, dir, depth;
        final Box bb;
        int avg = -1;
        int var1, var2;                 // SimpleHouse roof / SmallHut tall, table
        int[] crops;
        Piece(int type, Box bb, int dir, int depth) { this.type = type; this.bb = bb; this.dir = dir; this.depth = depth; }
        int length() { return Math.max(bb.x1 - bb.x0 + 1, bb.z1 - bb.z0 + 1); }
        int worldX(int x, int z) { return dir == W ? bb.x1 - z : dir == E ? bb.x0 + z : bb.x0 + x; }
        int worldZ(int x, int z) { return dir == N ? bb.z1 - z : dir == S ? bb.z0 + z : bb.z0 + x; }
    }

    static final class Weight {
        final int type, weight, limit;
        int spawned;
        Weight(int type, int weight, int limit) { this.type = type; this.weight = weight; this.limit = limit; }
        boolean canSpawn() { return limit == 0 || spawned < limit; }
    }

    static final class Start {
        final List<Piece> pieces = new ArrayList<Piece>();
        final List<Piece> roads = new ArrayList<Piece>(), houses = new ArrayList<Piece>();
        final List<Weight> weights = new ArrayList<Weight>();
        Weight last;
        Piece well;
        boolean desert, valid;
        Box bounds;
        int size;
    }

    final Generator gen;
    final int seed;
    private final HashMap<Long, Start> starts = new HashMap<Long, Start>();
    private final HashMap<Long, Boolean> checked = new HashMap<Long, Boolean>();

    Village(Generator gen, int seed) { this.gen = gen; this.seed = seed; }

    // ------------------------------------------------------------------ VillageFeature::isFeatureChunk
    /** the random after the feature check (createStructureStart continues with it), or null */
    McRandom featureRandom(int cx, int cz) {
        int ax = cx < 0 ? cx - 39 : cx, az = cz < 0 ? cz - 39 : cz;
        McRandom r = new McRandom(ax * 0x9939F508 + az * 0xF1565BD5 + seed + 10387312);
        long u1 = r.nextU32(), u2 = r.nextU32();
        if ((int) (u1 % 28) + (ax / 40) * 40 != cx || (int) (u2 % 28) + (az / 40) * 40 != cz) return null;
        if (!containsOnly(cx * 16 + 8, cz * 16 + 8, 0)) return null;
        return r;
    }

    /** BiomeSource::containsOnly on the 1:4 layer */
    boolean containsOnly(int x, int z, int r) {
        int x0 = (x - r) >> 2, z0 = (z - r) >> 2, x1 = (x + r) >> 2, z1 = (z + r) >> 2;
        int w = x1 - x0 + 1, h = z1 - z0 + 1;
        int[] a = gen.biomes4(x0, z0, w, h);
        for (int i = 0; i < w * h; i++) {
            boolean ok = false;
            for (int b : VALID_BIOMES) if (a[i] == b) { ok = true; break; }
            if (!ok) return false;
        }
        return true;
    }

    /** the starts whose box may reach chunk cx, cz (LargeFeature range 8) */
    List<Start> startsNear(int cx, int cz) {
        List<Start> out = new ArrayList<Start>();
        for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 8; z <= cz + 8; z++) {
            long k = World.key(x, z);
            Boolean c = checked.get(k);
            if (c == null) {
                McRandom r = featureRandom(x, z);
                if (r != null) { Start s = create(r, x, z); starts.put(k, s); }
                checked.put(k, r != null);
                c = r != null;
            }
            if (c) { Start s = starts.get(k); if (s != null && s.valid) out.add(s); }
        }
        return out;
    }

    // ------------------------------------------------------------------ VillageStart
    static int nextInt(McRandom r, int a, int b) { return a >= b ? a : a + (int) (r.nextU32() % (b - a + 1)); }

    Start create(McRandom r, int cx, int cz) {
        Start s = new Start();
        int size = 0;
        s.size = size;
        int[][] lim = {{2 + size, 4 + size * 2}, {size, 1 + size}, {size, 2 + size}, {2 + size, 5 + size * 3}, {size, 2 + size},
                {1 + size, 4 + size}, {2 + size, 4 + size * 2}, {0, 1 + size}, {size, 3 + size * 2}};
        for (int t = 0; t < 9; t++) s.weights.add(new Weight(t, WEIGHT[t], nextInt(r, lim[t][0], lim[t][1])));
        for (int i = s.weights.size() - 1; i >= 0; i--) if (s.weights.get(i).limit == 0) s.weights.remove(i);
        int x = (cx << 4) + 2, z = (cz << 4) + 2;
        int wdir = (int) (r.nextU32() % 4);
        Piece well = new Piece(T_WELL, new Box(x, 64, z, x + 5, 78, z + 5), wdir, 0);
        int b = gen.biomeAt(x, z);
        s.desert = b == 2 || b == 17;
        s.well = well;
        s.pieces.add(well);
        Box wb = well.bb;
        road(s, r, wb.x0 - 1, wb.y1 - 4, wb.z0 + 1, W, 0);
        road(s, r, wb.x1 + 1, wb.y1 - 4, wb.z0 + 1, E, 0);
        road(s, r, wb.x0 + 1, wb.y1 - 4, wb.z0 - 1, N, 0);
        road(s, r, wb.x0 + 1, wb.y1 - 4, wb.z1 + 1, S, 0);
        while (!s.roads.isEmpty() || !s.houses.isEmpty()) {
            if (s.roads.isEmpty()) {
                int i = (int) (r.nextU32() % s.houses.size());
                s.houses.remove(i);                                   // houses add no children
            } else {
                int i = (int) (r.nextU32() % s.roads.size());
                roadChildren(s, r, s.roads.remove(i));
            }
        }
        int n = 0;
        Box bounds = null;
        for (Piece p : s.pieces) {
            if (p.type != T_ROAD) n++;
            if (bounds == null) bounds = new Box(p.bb.x0, p.bb.y0, p.bb.z0, p.bb.x1, p.bb.y1, p.bb.z1);
            else {
                bounds.x0 = Math.min(bounds.x0, p.bb.x0); bounds.y0 = Math.min(bounds.y0, p.bb.y0); bounds.z0 = Math.min(bounds.z0, p.bb.z0);
                bounds.x1 = Math.max(bounds.x1, p.bb.x1); bounds.y1 = Math.max(bounds.y1, p.bb.y1); bounds.z1 = Math.max(bounds.z1, p.bb.z1);
            }
        }
        s.bounds = bounds;
        s.valid = n > 2;
        return s;
    }

    static boolean collides(Start s, Box b) {
        for (Piece p : s.pieces) if (p.bb.intersects(b)) return true;
        return false;
    }

    /** VillagePiece::generateAndAddRoadPiece */
    void road(Start s, McRandom r, int x, int y, int z, int dir, int depth) {
        if (depth > 3 + s.size) return;
        if (Math.abs(x - s.well.bb.x0) > 112 || Math.abs(z - s.well.bb.z0) > 112) return;
        Box b = null;
        for (int i = 7 * nextInt(r, 3, 5); i >= 7; i -= 7) {
            Box t = orient(x, y, z, 0, 0, 0, 3, 3, i, dir);
            if (!collides(s, t)) { b = t; break; }
        }
        if (b == null || b.y0 <= 10) return;
        Piece p = new Piece(T_ROAD, b, dir, depth);
        s.pieces.add(p); s.roads.add(p);                       // 0.14 has no biome check here (Java has)
    }

    /** StraightRoad::addChildren */
    void roadChildren(Start s, McRandom r, Piece road) {
        boolean any = false;
        Box b = road.bb;
        int len = road.length();
        for (int i = (int) (r.nextU32() % 5); i < len - 8; i += 2 + (int) (r.nextU32() % 5)) {
            Piece c = road.dir == N || road.dir == S ? house(s, r, b.x0 - 1, b.y0, b.z0 + i, W, road.depth)
                    : house(s, r, b.x0 + i, b.y0, b.z0 - 1, N, road.depth);
            if (c != null) { i += c.length(); any = true; }
        }
        for (int j = (int) (r.nextU32() % 5); j < len - 8; j += 2 + (int) (r.nextU32() % 5)) {
            Piece c = road.dir == N || road.dir == S ? house(s, r, b.x1 + 1, b.y0, b.z0 + j, E, road.depth)
                    : house(s, r, b.x0 + j, b.y0, b.z1 + 1, S, road.depth);
            if (c != null) { j += c.length(); any = true; }
        }
        if (any && r.nextU32() % 3 > 0) {
            switch (road.dir) {
                case N: road(s, r, b.x0 - 1, b.y0, b.z0, W, road.depth); break;
                case S: road(s, r, b.x0 - 1, b.y0, b.z1 - 2, W, road.depth); break;
                case W: road(s, r, b.x0, b.y0, b.z0 - 1, N, road.depth); break;
                default: road(s, r, b.x1 - 2, b.y0, b.z0 - 1, N, road.depth); break;
            }
        }
        if (any && r.nextU32() % 3 > 0) {
            switch (road.dir) {
                case N: road(s, r, b.x1 + 1, b.y0, b.z0, E, road.depth); break;
                case S: road(s, r, b.x1 + 1, b.y0, b.z1 - 2, E, road.depth); break;
                case W: road(s, r, b.x0, b.y0, b.z1 + 1, S, road.depth); break;
                default: road(s, r, b.x1 - 2, b.y0, b.z1 + 1, S, road.depth); break;
            }
        }
    }

    /** VillagePiece::generateAndAddPiece */
    Piece house(Start s, McRandom r, int x, int y, int z, int dir, int depth) {
        if (depth > 50) return null;
        if (Math.abs(x - s.well.bb.x0) > 112 || Math.abs(z - s.well.bb.z0) > 112) return null;
        Piece c = component(s, r, x, y, z, dir, depth + 1);
        if (c == null) return null;
        s.pieces.add(c); s.houses.add(c);
        return c;
    }

    /** VillagePiece::generatePieceFromSmallDoor */
    Piece component(Start s, McRandom r, int x, int y, int z, int dir, int depth) {
        boolean more = false;
        int total = 0;
        for (Weight w : s.weights) { if (w.limit > 0 && w.spawned < w.limit) more = true; total += w.weight; }
        if (!more || total <= 0) return null;
        for (int tries = 0; tries < 5; tries++) {
            int k = (int) (r.nextU32() % total);
            for (Weight w : s.weights) {
                k -= w.weight;
                if (k < 0) {
                    if (!w.canSpawn() || (w == s.last && s.weights.size() > 1)) break;
                    Piece p = create(s, r, w.type, x, y, z, dir, depth);
                    if (p != null) {
                        w.spawned++;
                        s.last = w;
                        if (!w.canSpawn()) s.weights.remove(w);
                        return p;
                    }
                }
            }
        }
        Box b = orient(x, y, z, 0, 0, 0, 3, 4, 2, dir);
        if (collides(s, b)) return null;
        return new Piece(T_LIGHT, b, dir, depth);
    }

    Piece create(Start s, McRandom r, int type, int x, int y, int z, int dir, int depth) {
        int[] sz = SIZE[type];
        Box b = orient(x, y, z, 0, 0, 0, sz[0], sz[1], sz[2], dir);
        if (b.y0 <= 10 || collides(s, b)) return null;
        Piece p = new Piece(type, b, dir, depth);
        if (type == 0) p.var1 = (int) ((r.nextU32() >>> 27) & 1);
        else if (type == 3) { p.var1 = (int) ((r.nextU32() >>> 27) & 1); p.var2 = (int) (r.nextU32() % 3); }
        else if (type == 5 || type == 6) {
            p.crops = new int[type == 5 ? 4 : 2];
            for (int i = 0; i < p.crops.length; i++) p.crops[i] = selectCrop(r, s);
        }
        return p;
    }

    /** Farmland::selectCrops: 2 of 3 wheat, else a crop fixed per village by the start position */
    static int selectCrop(McRandom r, Start s) {
        if (r.nextU32() % 3 != 1) return 59;
        Box b = s.well.bb;
        int lx = b.x0 + (b.x1 - b.x0 + 1) / 2, ly = b.y0 + (b.y1 - b.y0 + 1) / 2, lz = b.z0 + (b.z1 - b.z0 + 1) / 2;
        long h = ((long) (lx * 0x88f9fa + ly * 0xef88b + lz) & 0xffffffffL) >>> 3;
        int m = (int) (h % 3);
        return m == 0 ? 142 : m == 1 ? 244 : 141;
    }

    // ------------------------------------------------------------------ postProcess
    interface Blocks4 {
        int get(int x, int y, int z);
        int data(int x, int y, int z);
        void set(int x, int y, int z, int id, int data);
        int topSolid(int x, int z, boolean liquids);
    }

    static final HashMap<String, int[][]> PROGRAMS = new HashMap<String, int[][]>();
    static {
        for (String line : VillageData.T) {
            String[] f = line.split("\\|", -1);
            String key = f[0] + "|" + f[1] + "|" + f[2];
            String[] ops = f[3].isEmpty() ? new String[0] : f[3].split(";");
            int[][] prog = new int[ops.length][];
            for (int i = 0; i < ops.length; i++) {
                String o = ops[i];
                String[] a = o.length() > 1 ? o.substring(1).split(",") : new String[0];
                int[] v = new int[a.length + 1];
                v[0] = o.charAt(0);
                for (int j = 0; j < a.length; j++) v[j + 1] = Integer.parseInt(a[j]);
                prog[i] = v;
            }
            PROGRAMS.put(key, prog);
        }
    }

    /** StructureFeature::postProcess for one chunk: every piece of every start that touches it */
    void postProcess(Blocks4 w, McRandom r, int cx, int cz) {
        Box chunk = new Box(cx << 4, 1, cz << 4, (cx << 4) + 15, 512, (cz << 4) + 15);
        for (Start s : startsNear(cx, cz)) {
            if (!(s.bounds.x0 <= chunk.x1 + 1 && s.bounds.x1 >= chunk.x0 && s.bounds.z0 <= chunk.z1 + 1 && s.bounds.z1 >= chunk.z0)) continue;
            for (Piece p : s.pieces) {
                if (!p.bb.intersects(chunk)) continue;
                if (p.type == T_ROAD) roadPlace(w, s, p, chunk);
                else piecePlace(w, r, s, p, chunk);
            }
        }
    }

    static int[] biomeBlock(Start s, int id, int data) {
        if (s == null || !s.desert) return new int[]{id, data};
        if (id == 17 || id == 162 || id == 4 || id == 198) return new int[]{24, 0};
        if (id == 5) return new int[]{24, 2};
        if (id == 53 || id == 67) return new int[]{128, data};
        return new int[]{id, data};
    }

    /** StraightRoad::postProcess: grass path on the top solid block, planks over liquid */
    void roadPlace(Blocks4 w, Start s, Piece p, Box chunk) {
        int[] path = biomeBlock(s, 198, 0), planks = biomeBlock(s, 5, 0);
        for (int x = p.bb.x0; x <= p.bb.x1; x++) for (int z = p.bb.z0; z <= p.bb.z1; z++) {
            if (!(x >= chunk.x0 && x <= chunk.x1 && z >= chunk.z0 && z <= chunk.z1)) continue;
            int y = w.topSolid(x, z, true) - 1;
            int id = w.get(x, y, z);
            boolean liquid = id == 8 || id == 9 || id == 10 || id == 11;
            int[] b = liquid ? planks : path;
            w.set(x, y, z, b[0], b[1]);
        }
    }

    /** VillagePiece::getAverageGroundHeight */
    static int averageGround(Blocks4 w, Piece p, Box chunk) {
        int sum = 0, n = 0;
        for (int z = p.bb.z0; z <= p.bb.z1; z++) for (int x = p.bb.x0; x <= p.bb.x1; x++) {
            if (!(x >= chunk.x0 && x <= chunk.x1 && z >= chunk.z0 && z <= chunk.z1)) continue;
            sum += Math.max(w.topSolid(x, z, false), 64);
            n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    void piecePlace(Blocks4 w, McRandom r, Start s, Piece p, Box chunk) {
        if (p.avg < 0) {
            p.avg = averageGround(w, p, chunk);
            if (p.avg < 0) return;
            int off = p.type == T_WELL ? p.avg - p.bb.y1 + 3 : p.avg - p.bb.y1 + SIZE[p.type][1] - 1;
            p.bb.y0 += off; p.bb.y1 += off;
        }
        String key;
        if (p.type == T_WELL) key = "Well|0|";
        else if (p.type == 0) key = NAME[0] + "|" + p.dir + "|" + p.var1;
        else if (p.type == 3) key = NAME[3] + "|" + p.dir + "|" + p.var1 + "." + p.var2;
        else key = NAME[p.type] + "|" + p.dir + "|";
        int[][] prog = PROGRAMS.get(key);
        if (prog == null) return;
        replay(w, r, s, p, chunk, prog);
    }

    /** runs a recorded piece program (local coordinates of the piece) clipped to the chunk; s == null: no biome blocks */
    static void replay(Blocks4 w, McRandom r, Start s, Piece p, Box chunk, int[][] prog) {
        long lastU = 0;
        int gx = Integer.MIN_VALUE, gy = 0, gz = 0, gCount = 0;
        for (int[] o : prog) {
            switch (o[0]) {
                case 'R': lastU = r.nextU32(); gCount = 0; break;
                case 'G':
                    if (gCount == 0) { gx = o[1]; gy = o[2]; gz = o[3]; }
                    gCount++;
                    break;
                case 'P': {
                    boolean ok = true;
                    if (gCount >= 2 && gx == o[1] && gy == o[2] && gz == o[3]) {
                        ok = w.get(p.worldX(o[1], o[3]), p.bb.y0 + o[2], p.worldZ(o[1], o[3])) == 0
                                && w.get(p.worldX(o[1], o[3]), p.bb.y0 + o[2] - 1, p.worldZ(o[1], o[3])) != 0;
                    }
                    gCount = 0;
                    if (ok) place(w, s, p, chunk, o[1], o[2], o[3], o[4], o[5]);
                    break;
                }
                case 'K': {
                    int crop = p.crops != null && o[4] < p.crops.length ? p.crops[o[4]] : 59;
                    place(w, s, p, chunk, o[1], o[2], o[3], crop, 2 + (int) (lastU % 6));
                    gCount = 0;
                    break;
                }
                case 'B': box(w, s, p, chunk, o); gCount = 0; break;
                case 'D': {
                    int x = p.worldX(o[1], o[3]), y = p.bb.y0 + o[2], z = p.worldZ(o[1], o[3]);
                    if (chunk.inside(x, y, z)) {
                        int[] b = biomeBlock(s, o[4], o[5]);
                        while (y > 1) {
                            int id = w.get(x, y, z);
                            if (!(id == 0 || id == 8 || id == 9 || id == 10 || id == 11)) break;
                            w.set(x, y, z, b[0], b[1]);
                            y--;
                        }
                    }
                    gCount = 0;
                    break;
                }
                case 'U': {
                    int x = p.worldX(o[1], o[3]), y = p.bb.y0 + o[2], z = p.worldZ(o[1], o[3]);
                    if (chunk.inside(x, y, z)) while (y < Chunk.H && w.get(x, y, z) != 0) { w.set(x, y, z, 0, 0); y++; }
                    gCount = 0;
                    break;
                }
                case 'O': {
                    int x = p.worldX(o[1], o[3]), y = p.bb.y0 + o[2], z = p.worldZ(o[1], o[3]);
                    if (chunk.inside(x, y, z)) { w.set(x, y, z, 64, doorData(p.dir, o[4]) & 3); w.set(x, y + 1, z, 64, 8); }
                    gCount = 0;
                    break;
                }
                case 'C': place(w, s, p, chunk, o[1], o[2], o[3], 54, 2); gCount = 0; break;
                case 'Q': place(w, s, p, chunk, o[1], o[2], o[3], 118, 0); gCount = 0; break;
                default: break;
            }
        }
    }

    static boolean stairs(int id) {
        return id == 53 || id == 67 || id == 108 || id == 109 || id == 114 || id == 128 || id == 134 || id == 135 || id == 136
                || id == 156 || id == 163 || id == 164 || id == 180;
    }

    /** StructurePiece::getOrientationData for stairs (shape 10): 0 east 1 west 2 south 3 north, by piece direction */
    static int stairsData(int dir, int d) {
        int k = d & 3, r = k;
        if (dir == S) { if (k == 2) r = 3; else if (k == 3) r = 2; }
        else if (dir == W) r = k == 0 ? 2 : k == 1 ? 3 : k == 2 ? 0 : 1;
        else if (dir == E) r = k == 0 ? 2 : k == 1 ? 3 : k == 2 ? 1 : 0;
        return (d & ~3) | r;
    }

    /** StructurePiece::getOrientationData for doors */
    static int doorData(int dir, int d) {
        if (dir == S) return d == 0 ? 2 : d == 2 ? 0 : d;
        if (dir == W) return (d + 1) & 3;
        if (dir == E) return (d + 3) & 3;
        return d;
    }

    static void place(Blocks4 w, Start s, Piece p, Box chunk, int lx, int ly, int lz, int id, int data) {
        int x = p.worldX(lx, lz), y = p.bb.y0 + ly, z = p.worldZ(lx, lz);
        if (!chunk.inside(x, y, z)) return;
        if (stairs(id)) data = stairsData(p.dir, data);
        int[] b = biomeBlock(s, id, data);
        w.set(x, y, z, b[0], b[1]);
    }

    /** VillagePiece::generateBox: edge blocks on the box surface, fill inside; existingOnly keeps air */
    static void box(Blocks4 w, Start s, Piece p, Box chunk, int[] o) {
        int x0 = o[1], y0 = o[2], z0 = o[3], x1 = o[4], y1 = o[5], z1 = o[6];
        boolean existing = o[11] != 0;
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            int wx = p.worldX(x, z), wy = p.bb.y0 + y, wz = p.worldZ(x, z);
            if (existing && w.get(wx, wy, wz) == 0) continue;
            boolean edge = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            place(w, s, p, chunk, x, y, z, edge ? o[7] : o[9], edge ? o[8] : o[10]);
        }
    }
}
