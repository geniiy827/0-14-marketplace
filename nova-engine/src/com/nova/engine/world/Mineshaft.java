package com.nova.engine.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Mineshafts of MCPE 0.14.3 (MineshaftFeature / MineshaftStart / MineshaftRoom, Corridor, Crossing, Stairs).
 *  - isFeatureChunk 0x5e7a98: nextFloat < 0.004 and nextInt(80) < max(|cx|, |cz|), with the LargeFeature chunk random
 *    after StructureFeature::addFeature took one draw; the start uses that random on.
 *  - _isSurfaceMineshaft: biome type of (cx*16, cz*16) == 8 (river) -> MineshaftData(true): dark oak planks / fence
 *    (data 5), moved to the surface (y0 += 68 + h/2 - y1); otherwise oak and StructureStart::moveBelowSeaLevel(r, 10).
 *  - pieces and postProcess as the binary: the corridor cobweb at (0, 2, k) (not k - 1), _placeSupport with a beam and
 *    two 0.05 torches unless nextInt(4) == 0 (then two planks), rails 0.7 below sky light 9 else 0.9, floor planks
 *    where air and dark (StructurePiece::getBrightness reads two blocks above the local y).
 * Verified piece by piece and block by block against the original run in an emulator (tools/emu_mineshaft.py).
 */
final class Mineshaft {
    static final int S = 0, W = 1, N = 2, E = 3, NONE = -1;
    static final int ROOM = 0, CORRIDOR = 1, CROSSING = 2, STAIRS = 3;

    interface World {
        int get(int x, int y, int z);
        void set(int x, int y, int z, int id, int data);
        /** sky light 0..15 */
        int sky(int x, int y, int z);
    }

    static final class Box {
        int x0, y0, z0, x1, y1, z1;
        Box(int x0, int y0, int z0, int x1, int y1, int z1) { this.x0 = x0; this.y0 = y0; this.z0 = z0; this.x1 = x1; this.y1 = y1; this.z1 = z1; }
        boolean intersects(Box o) { return x1 >= o.x0 && x0 <= o.x1 && z1 >= o.z0 && z0 <= o.z1 && y1 >= o.y0 && y0 <= o.y1; }
        boolean inside(int x, int y, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1 && y >= y0 && y <= y1; }
        public String toString() { return "[" + x0 + "," + y0 + "," + z0 + "," + x1 + "," + y1 + "," + z1 + "]"; }
    }

    static final class Data {
        final int planks, planksData, fence, fenceData;
        final float factor;
        Data(boolean surface) {
            planks = 5; fence = 85;
            planksData = surface ? 5 : 0; fenceData = surface ? 5 : 0; factor = surface ? 0.5f : 1f;
        }
    }

    static final class Piece {
        final int type, depth;
        int dir;
        final Box bb;
        final Data data;
        boolean rails, spiders, spawner, twoFloors;
        int cdir;
        int sections;
        final List<Box> links = new ArrayList<Box>();
        Piece(int type, Data data, int depth, Box bb, int dir) { this.type = type; this.data = data; this.depth = depth; this.bb = bb; this.dir = dir; }

        int wx(int x, int z) { return dir == W ? bb.x1 - z : dir == E ? bb.x0 + z : dir == NONE ? x : bb.x0 + x; }
        int wy(int y) { return dir == NONE ? y : bb.y0 + y; }
        int wz(int x, int z) { return dir == N ? bb.z1 - z : dir == S ? bb.z0 + z : dir == NONE ? z : bb.z0 + x; }
        public String toString() { return type + " d" + dir + " g" + depth + " " + bb + (type == CORRIDOR ? " r" + rails + " s" + spiders + " n" + sections : type == CROSSING ? " two" + twoFloors : ""); }
    }

    static final class Start {
        final List<Piece> pieces = new ArrayList<Piece>();
        Box bb;
        final ArrayList<Long> done = new ArrayList<Long>();
    }

    // ------------------------------------------------------------------ feature
    final Generator gen;
    final int seed, a, b;
    private final HashMap<Long, Start> starts = new HashMap<Long, Start>();
    private final HashMap<Long, Boolean> checked = new HashMap<Long, Boolean>();

    Mineshaft(Generator gen, int seed) {
        this.gen = gen; this.seed = seed;
        McRandom r = new McRandom(seed);
        a = (int) ((r.nextU32() >>> 2) << 1) + 1;
        b = (int) ((r.nextU32() >>> 2) << 1) + 1;
    }

    /** MineshaftFeature::isFeatureChunk + createStructureStart, null when there is none */
    Start feature(int cx, int cz) {
        McRandom r = new McRandom((cx * a + cz * b) ^ seed);
        r.nextU32();                                              // StructureFeature::addFeature
        if (!isFeatureChunk(r, cx, cz)) return null;
        boolean surface = gen != null && Biomes.CLASS[gen.biomeAt(cx * 16, cz * 16)] == Biomes.C_RIVER;
        return start(r, cx, cz, surface);
    }

    static boolean isFeatureChunk(McRandom r, int cx, int cz) {
        float f = (float) (r.nextU32() * 2.3283064365386963E-10);
        if (!(f < 0.004f)) return false;
        return r.nextU32() % 80 < Math.max(Math.abs(cx), Math.abs(cz));
    }

    /** MineshaftStart::MineshaftStart */
    static Start start(McRandom r, int cx, int cz, boolean surface) {
        Start s = new Start();
        Data d = new Data(surface);
        Piece room = room(d, 0, r, cx * 16 + 2, cz * 16 + 2);
        s.pieces.add(room);
        addChildren(room, room, s.pieces, r);
        Box bb = new Box(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        for (Piece p : s.pieces) {
            bb.x0 = Math.min(bb.x0, p.bb.x0); bb.y0 = Math.min(bb.y0, p.bb.y0); bb.z0 = Math.min(bb.z0, p.bb.z0);
            bb.x1 = Math.max(bb.x1, p.bb.x1); bb.y1 = Math.max(bb.y1, p.bb.y1); bb.z1 = Math.max(bb.z1, p.bb.z1);
        }
        s.bb = bb;
        int off;
        if (surface) {
            off = 68 + (bb.y1 - bb.y0 + 1) / 2 - bb.y1;         // MineshaftStart::_moveToSurface
        } else {                                                 // StructureStart::moveBelowSeaLevel(r, 10)
            int i = 63 - 10, j = bb.y1 - bb.y0 + 2;
            if (i > j) j += (int) (r.nextU32() % (i - j));
            off = j - bb.y1;
        }
        bb.y0 += off; bb.y1 += off;
        for (Piece p : s.pieces) { p.bb.y0 += off; p.bb.y1 += off; }
        return s;
    }

    static int nextInt(McRandom r, int n) { return (int) (r.nextU32() % n); }

    static Piece room(Data d, int depth, McRandom r, int x, int z) {
        int a = nextInt(r, 6), b = nextInt(r, 6), c = nextInt(r, 6);
        return new Piece(ROOM, d, depth, new Box(x, 50, z, x + 7 + a, 54 + b, z + 7 + c), NONE);
    }

    static Piece collides(List<Piece> list, Box bb) {
        for (Piece p : list) if (p.bb.intersects(bb)) return p;
        return null;
    }

    /** MineshaftPiece::createRandomShaftPiece */
    static Piece randomPiece(Data d, List<Piece> list, McRandom r, int x, int y, int z, int dir, int depth) {
        int i = nextInt(r, 100);
        if (i >= 80) {
            Box bb = findCrossing(list, r, x, y, z, dir);
            if (bb != null) { Piece p = new Piece(CROSSING, d, depth, bb, NONE); p.twoFloors = bb.y1 - bb.y0 + 1 > 3; p.cdir = dir; return p; }
        } else if (i >= 70) {
            Box bb = findStairs(list, r, x, y, z, dir);
            if (bb != null) return new Piece(STAIRS, d, depth, bb, dir);
        } else {
            Box bb = findCorridor(list, r, x, y, z, dir);
            if (bb != null) {
                Piece p = new Piece(CORRIDOR, d, depth, bb, dir);
                p.rails = nextInt(r, 3) == 0;
                p.spiders = !p.rails && nextInt(r, 23) == 0 && bb.y1 <= 49;     // only low corridors (before moving)
                p.sections = (dir == N || dir == S ? bb.z1 - bb.z0 + 1 : bb.x1 - bb.x0 + 1) / 5;
                return p;
            }
        }
        return null;
    }

    /** MineshaftPiece::generateAndAddPiece */
    static Piece add(Piece start, List<Piece> list, McRandom r, int x, int y, int z, int dir, int depth) {
        if (depth > 8) return null;
        if (Math.abs(x - start.bb.x0) > 80 || Math.abs(z - start.bb.z0) > 80) return null;
        Piece p = randomPiece(start.data, list, r, x, y, z, dir, depth + 1);
        if (p != null) { list.add(p); addChildren(start, p, list, r); }
        return p;
    }

    static Box findCorridor(List<Piece> list, McRandom r, int x, int y, int z, int dir) {
        Box bb = new Box(x, y, z, x, y + 2, z);
        int i;
        for (i = nextInt(r, 3) + 2; i > 0; i--) {
            int j = i * 5;
            switch (dir) {
                case N: bb.x1 = x + 2; bb.z0 = z - (j - 1); break;
                case S: bb.x1 = x + 2; bb.z1 = z + (j - 1); break;
                case W: bb.x0 = x - (j - 1); bb.z1 = z + 2; break;
                default: bb.x1 = x + (j - 1); bb.z1 = z + 2; break;
            }
            if (collides(list, bb) == null) break;
        }
        return i > 0 ? bb : null;
    }

    static Box findCrossing(List<Piece> list, McRandom r, int x, int y, int z, int dir) {
        Box bb = new Box(x, y, z, x, y + 2, z);
        if (nextInt(r, 4) == 0) bb.y1 += 4;
        switch (dir) {
            case N: bb.x0 = x - 1; bb.x1 = x + 3; bb.z0 = z - 4; break;
            case S: bb.x0 = x - 1; bb.x1 = x + 3; bb.z1 = z + 4; break;
            case W: bb.x0 = x - 4; bb.z0 = z - 1; bb.z1 = z + 3; break;
            default: bb.x1 = x + 4; bb.z0 = z - 1; bb.z1 = z + 3; break;
        }
        return collides(list, bb) != null ? null : bb;
    }

    static Box findStairs(List<Piece> list, McRandom r, int x, int y, int z, int dir) {
        Box bb = new Box(x, y - 5, z, x, y + 2, z);
        switch (dir) {
            case N: bb.x1 = x + 2; bb.z0 = z - 8; break;
            case S: bb.x1 = x + 2; bb.z1 = z + 8; break;
            case W: bb.x0 = x - 8; bb.z1 = z + 2; break;
            default: bb.x1 = x + 8; bb.z1 = z + 2; break;
        }
        return collides(list, bb) != null ? null : bb;
    }

    static void addChildren(Piece start, Piece p, List<Piece> list, McRandom r) {
        Box bb = p.bb;
        int i = p.depth;
        switch (p.type) {
            case ROOM: {
                if (p.data.factor < nextFloat(r)) break;                 // MineshaftData factor gate (0.5 on the surface)
                int k = bb.y1 - bb.y0 + 1 - 3 - 1;
                if (k <= 0) k = 1;
                int xs = bb.x1 - bb.x0 + 1, zs = bb.z1 - bb.z0 + 1;
                for (int j = 0; j < xs; j += 4) {
                    j += nextInt(r, xs);
                    if (j + 3 > xs) break;
                    Piece c = add(start, list, r, bb.x0 + j, bb.y0 + nextInt(r, k) + 1, bb.z0 - 1, N, i);
                    if (c != null) p.links.add(new Box(c.bb.x0, c.bb.y0, bb.z0, c.bb.x1, c.bb.y1, bb.z0 + 1));
                }
                for (int j = 0; j < xs; j += 4) {
                    j += nextInt(r, xs);
                    if (j + 3 > xs) break;
                    Piece c = add(start, list, r, bb.x0 + j, bb.y0 + nextInt(r, k) + 1, bb.z1 + 1, S, i);
                    if (c != null) p.links.add(new Box(c.bb.x0, c.bb.y0, bb.z1 - 1, c.bb.x1, c.bb.y1, bb.z1));
                }
                for (int j = 0; j < zs; j += 4) {
                    j += nextInt(r, zs);
                    if (j + 3 > zs) break;
                    Piece c = add(start, list, r, bb.x0 - 1, bb.y0 + nextInt(r, k) + 1, bb.z0 + j, W, i);
                    if (c != null) p.links.add(new Box(bb.x0, c.bb.y0, c.bb.z0, bb.x0 + 1, c.bb.y1, c.bb.z1));
                }
                for (int j = 0; j < zs; j += 4) {
                    j += nextInt(r, zs);
                    if (j + 3 > zs) break;
                    Piece c = add(start, list, r, bb.x1 + 1, bb.y0 + nextInt(r, k) + 1, bb.z0 + j, E, i);
                    if (c != null) p.links.add(new Box(bb.x1 - 1, c.bb.y0, c.bb.z0, bb.x1, c.bb.y1, c.bb.z1));
                }
                break;
            }
            case CORRIDOR: {
                int j = nextInt(r, 4);
                switch (p.dir) {
                    case N:
                        if (j <= 1) add(start, list, r, bb.x0, bb.y0 - 1 + nextInt(r, 3), bb.z0 - 1, N, i);
                        else if (j == 2) add(start, list, r, bb.x0 - 1, bb.y0 - 1 + nextInt(r, 3), bb.z0, W, i);
                        else add(start, list, r, bb.x1 + 1, bb.y0 - 1 + nextInt(r, 3), bb.z0, E, i);
                        break;
                    case S:
                        if (j <= 1) add(start, list, r, bb.x0, bb.y0 - 1 + nextInt(r, 3), bb.z1 + 1, S, i);
                        else if (j == 2) add(start, list, r, bb.x0 - 1, bb.y0 - 1 + nextInt(r, 3), bb.z1 - 3, W, i);
                        else add(start, list, r, bb.x1 + 1, bb.y0 - 1 + nextInt(r, 3), bb.z1 - 3, E, i);
                        break;
                    case W:
                        if (j <= 1) add(start, list, r, bb.x0 - 1, bb.y0 - 1 + nextInt(r, 3), bb.z0, W, i);
                        else if (j == 2) add(start, list, r, bb.x0, bb.y0 - 1 + nextInt(r, 3), bb.z0 - 1, N, i);
                        else add(start, list, r, bb.x0, bb.y0 - 1 + nextInt(r, 3), bb.z1 + 1, S, i);
                        break;
                    default:
                        if (j <= 1) add(start, list, r, bb.x1 + 1, bb.y0 - 1 + nextInt(r, 3), bb.z0, E, i);
                        else if (j == 2) add(start, list, r, bb.x1 - 3, bb.y0 - 1 + nextInt(r, 3), bb.z0 - 1, N, i);
                        else add(start, list, r, bb.x1 - 3, bb.y0 - 1 + nextInt(r, 3), bb.z1 + 1, S, i);
                        break;
                }
                if (i < 8) {
                    if (p.dir != N && p.dir != S) {
                        for (int x = bb.x0 + 3; x + 3 <= bb.x1; x += 5) {
                            int k = nextInt(r, 5);
                            if (k == 0) add(start, list, r, x, bb.y0, bb.z0 - 1, N, i + 1);
                            else if (k == 1) add(start, list, r, x, bb.y0, bb.z1 + 1, S, i + 1);
                        }
                    } else {
                        for (int z = bb.z0 + 3; z + 3 <= bb.z1; z += 5) {
                            int k = nextInt(r, 5);
                            if (k == 0) add(start, list, r, bb.x0 - 1, bb.y0, z, W, i + 1);
                            else if (k == 1) add(start, list, r, bb.x1 + 1, bb.y0, z, E, i + 1);
                        }
                    }
                }
                break;
            }
            case CROSSING:
                switch (p.cdir) {
                    case N:
                        add(start, list, r, bb.x0 + 1, bb.y0, bb.z0 - 1, N, i);
                        add(start, list, r, bb.x0 - 1, bb.y0, bb.z0 + 1, W, i);
                        add(start, list, r, bb.x1 + 1, bb.y0, bb.z0 + 1, E, i);
                        break;
                    case S:
                        add(start, list, r, bb.x0 + 1, bb.y0, bb.z1 + 1, S, i);
                        add(start, list, r, bb.x0 - 1, bb.y0, bb.z0 + 1, W, i);
                        add(start, list, r, bb.x1 + 1, bb.y0, bb.z0 + 1, E, i);
                        break;
                    case W:
                        add(start, list, r, bb.x0 + 1, bb.y0, bb.z0 - 1, N, i);
                        add(start, list, r, bb.x0 + 1, bb.y0, bb.z1 + 1, S, i);
                        add(start, list, r, bb.x0 - 1, bb.y0, bb.z0 + 1, W, i);
                        break;
                    default:
                        add(start, list, r, bb.x0 + 1, bb.y0, bb.z0 - 1, N, i);
                        add(start, list, r, bb.x0 + 1, bb.y0, bb.z1 + 1, S, i);
                        add(start, list, r, bb.x1 + 1, bb.y0, bb.z0 + 1, E, i);
                        break;
                }
                if (p.twoFloors) {
                    if ((r.nextU32() >>> 27 & 1) != 0) add(start, list, r, bb.x0 + 1, bb.y0 + 3 + 1, bb.z0 - 1, N, i);
                    if ((r.nextU32() >>> 27 & 1) != 0) add(start, list, r, bb.x0 - 1, bb.y0 + 3 + 1, bb.z0 + 1, W, i);
                    if ((r.nextU32() >>> 27 & 1) != 0) add(start, list, r, bb.x1 + 1, bb.y0 + 3 + 1, bb.z0 + 1, E, i);
                    if ((r.nextU32() >>> 27 & 1) != 0) add(start, list, r, bb.x0 + 1, bb.y0 + 3 + 1, bb.z1 + 1, S, i);
                }
                break;
            default:                                                 // STAIRS
                switch (p.dir) {
                    case N: add(start, list, r, bb.x0, bb.y0, bb.z0 - 1, N, i); break;
                    case S: add(start, list, r, bb.x0, bb.y0, bb.z1 + 1, S, i); break;
                    case W: add(start, list, r, bb.x0 - 1, bb.y0, bb.z0, W, i); break;
                    default: add(start, list, r, bb.x1 + 1, bb.y0, bb.z0, E, i); break;
                }
                break;
        }
    }

    // ------------------------------------------------------------------ StructurePiece primitives
    static void place(World w, Piece p, Box chunk, int id, int data, int x, int y, int z) {
        int X = p.wx(x, z), Y = p.wy(y), Z = p.wz(x, z);
        if (chunk.inside(X, Y, Z)) w.set(X, Y, Z, id, data);
    }

    static int block(World w, Piece p, Box chunk, int x, int y, int z) {
        int X = p.wx(x, z), Y = p.wy(y), Z = p.wz(x, z);
        return chunk.inside(X, Y, Z) ? w.get(X, Y, Z) : 0;
    }

    /** StructurePiece::getBrightness: sky light two blocks above the local position */
    static int bright(World w, Piece p, int x, int y, int z) { return w.sky(p.wx(x, z), p.wy(y + 1) + 1, p.wz(x, z)); }

    static void box(World w, Piece p, Box chunk, int x0, int y0, int z0, int x1, int y1, int z1, int eid, int ed, int fid, int fd, boolean skipAir) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (skipAir && block(w, p, chunk, x, y, z) == 0) continue;
            boolean edge = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            place(w, p, chunk, edge ? eid : fid, edge ? ed : fd, x, y, z);
        }
    }

    static float nextFloat(McRandom r) { return (float) (r.nextU32() * 2.3283064365386963E-10); }

    static void maybeBox(World w, Piece p, Box chunk, McRandom r, float chance, int x0, int y0, int z0, int x1, int y1, int z1,
                         int eid, int fid, boolean skipAir, int maxBright) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            float f = nextFloat(r);
            if (f > chance) continue;
            if (skipAir && block(w, p, chunk, x, y, z) == 0) continue;
            if (maxBright != 0 && bright(w, p, x, y, z) >= maxBright) continue;
            boolean edge = y == y0 || y == y1 || x == x0 || x == x1 || z == z0 || z == z1;
            place(w, p, chunk, edge ? eid : fid, 0, x, y, z);
        }
    }

    static boolean DEBUG;

    static void maybeBlock(World w, Piece p, Box chunk, McRandom r, float chance, int x, int y, int z, int id, int data) {
        if (DEBUG) System.err.println("DBG mgb [" + Float.floatToIntBits(chance) + ", " + x + ", " + y + ", " + z + "] mti " + r.debugIndex());
        if (chance > nextFloat(r)) place(w, p, chunk, id, data, x, y, z);
    }

    static boolean liquid(int id) { return id == 8 || id == 9 || id == 10 || id == 11; }

    /** StructurePiece::edgesLiquid */
    static boolean edgesLiquid(World w, Piece p, Box c) {
        Box bb = p.bb;
        int i = Math.max(bb.x0 - 1, c.x0), j = Math.max(bb.y0 - 1, c.y0), k = Math.max(bb.z0 - 1, c.z0);
        int l = Math.min(bb.x1 + 1, c.x1), m = Math.min(bb.y1 + 1, c.y1), n = Math.min(bb.z1 + 1, c.z1);
        for (int x = i; x <= l; x++) for (int z = k; z <= n; z++) if (liquid(w.get(x, j, z)) || liquid(w.get(x, m, z))) return true;
        for (int x = i; x <= l; x++) for (int y = j; y <= m; y++) if (liquid(w.get(x, y, k)) || liquid(w.get(x, y, n))) return true;
        for (int z = k; z <= n; z++) for (int y = j; y <= m; y++) if (liquid(w.get(i, y, z)) || liquid(w.get(l, y, z))) return true;
        return false;
    }

    static final int[][] TORCH = {{3, 1, 4, 2}, {2, 3, 1, 4}, {4, 1, 3, 2}, {1, 3, 2, 4}};   // getTorchData [orientation][dir]

    static int torch(Piece p, int d) { return p.dir < 0 ? 5 : TORCH[p.dir][d]; }

    // ------------------------------------------------------------------ postProcess
    static void postProcess(World w, Piece p, Box chunk, McRandom r) {
        if (edgesLiquid(w, p, chunk)) return;
        Data d = p.data;
        Box bb = p.bb;
        switch (p.type) {
            case ROOM: {
                box(w, p, chunk, bb.x0, bb.y0, bb.z0, bb.x1, bb.y0, bb.z1, 3, 0, 0, 0, true);
                box(w, p, chunk, bb.x0, bb.y0 + 1, bb.z0, bb.x1, Math.min(bb.y0 + 3, bb.y1), bb.z1, 0, 0, 0, 0, false);
                for (Box l : p.links) box(w, p, chunk, l.x0, l.y1 - 2, l.z0, l.x1, l.y1, l.z1, 0, 0, 0, 0, false);
                halfSphere(w, p, chunk, bb.x0, bb.y0 + 4, bb.z0, bb.x1, bb.y1, bb.z1, 0, false);
                break;
            }
            case CORRIDOR: {
                int len = p.sections * 5 - 1;
                box(w, p, chunk, 0, 0, 0, 2, 1, len, 0, 0, 0, 0, false);
                maybeBox(w, p, chunk, r, 0.8f, 0, 2, 0, 2, 2, len, 0, 0, false, 0);
                if (p.spiders) maybeBox(w, p, chunk, r, 0.6f, 0, 0, 0, 2, 1, len, 30, 0, false, 8);
                for (int j = 0; j < p.sections; j++) {
                    int k = 2 + j * 5;
                    support(w, p, chunk, 0, 0, k, 2, 2, r);
                    web(w, p, chunk, r, 0.1f, 0, 2, k);
                    web(w, p, chunk, r, 0.1f, 2, 2, k - 1);
                    web(w, p, chunk, r, 0.1f, 0, 2, k + 1);
                    web(w, p, chunk, r, 0.1f, 2, 2, k + 1);
                    web(w, p, chunk, r, 0.05f, 0, 2, k - 2);
                    web(w, p, chunk, r, 0.05f, 2, 2, k - 2);
                    web(w, p, chunk, r, 0.05f, 0, 2, k + 2);
                    web(w, p, chunk, r, 0.05f, 2, 2, k + 2);
                    if (p.spiders && !p.spawner) {
                        int y = p.wy(0);
                        int lz = k - 1 + nextInt(r, 3);
                        int x = p.wx(1, lz), z = p.wz(1, lz);
                        if (chunk.inside(x, y, z) && bright(w, p, x, y, z) <= 7) {     // world coordinates handed to the local-space getBrightness (as the original)
                            p.spawner = true;
                            w.set(x, y, z, 52, 0);                    // cave spider spawner
                        }
                    }
                }
                for (int x = 0; x < 3; x++) for (int z = 0; z <= len; z++) {
                    if (block(w, p, chunk, x, -1, z) != 0) continue;
                    if (bright(w, p, x, -1, z) > 7) continue;
                    place(w, p, chunk, d.planks, d.planksData, x, -1, z);
                }
                if (p.rails) for (int z = 0; z <= len; z++) {
                    int id = block(w, p, chunk, 1, -1, z);
                    if (id == 0 || !Blocks.solid[id]) continue;
                    float c = bright(w, p, 1, -1, z) >= 9 ? 0.9f : 0.7f;
                    maybeBlock(w, p, chunk, r, c, 1, 0, z, 66, p.dir == W || p.dir == E ? 1 : 0);
                }
                break;
            }
            case CROSSING: {
                if (p.twoFloors) {
                    box(w, p, chunk, bb.x0 + 1, bb.y0, bb.z0, bb.x1 - 1, bb.y0 + 3 - 1, bb.z1, 0, 0, 0, 0, false);
                    box(w, p, chunk, bb.x0, bb.y0, bb.z0 + 1, bb.x1, bb.y0 + 3 - 1, bb.z1 - 1, 0, 0, 0, 0, false);
                    box(w, p, chunk, bb.x0 + 1, bb.y1 - 2, bb.z0, bb.x1 - 1, bb.y1, bb.z1, 0, 0, 0, 0, false);
                    box(w, p, chunk, bb.x0, bb.y1 - 2, bb.z0 + 1, bb.x1, bb.y1, bb.z1 - 1, 0, 0, 0, 0, false);
                    box(w, p, chunk, bb.x0 + 1, bb.y0 + 3, bb.z0 + 1, bb.x1 - 1, bb.y0 + 3, bb.z1 - 1, 0, 0, 0, 0, false);
                } else {
                    box(w, p, chunk, bb.x0 + 1, bb.y0, bb.z0, bb.x1 - 1, bb.y1, bb.z1, 0, 0, 0, 0, false);
                    box(w, p, chunk, bb.x0, bb.y0, bb.z0 + 1, bb.x1, bb.y1, bb.z1 - 1, 0, 0, 0, 0, false);
                }
                pillar(w, p, chunk, bb.x0 + 1, bb.y0, bb.z0 + 1, bb.y1);
                pillar(w, p, chunk, bb.x0 + 1, bb.y0, bb.z1 - 1, bb.y1);
                pillar(w, p, chunk, bb.x1 - 1, bb.y0, bb.z0 + 1, bb.y1);
                pillar(w, p, chunk, bb.x1 - 1, bb.y0, bb.z1 - 1, bb.y1);
                for (int x = bb.x0; x <= bb.x1; x++) for (int z = bb.z0; z <= bb.z1; z++) {
                    if (block(w, p, chunk, x, bb.y0 - 1, z) != 0) continue;
                    if (bright(w, p, x, bb.y0 - 1, z) > 7) continue;
                    place(w, p, chunk, d.planks, d.planksData, x, bb.y0 - 1, z);
                }
                break;
            }
            default: {                                                    // STAIRS
                box(w, p, chunk, 0, 5, 0, 2, 7, 1, 0, 0, 0, 0, false);
                box(w, p, chunk, 0, 0, 7, 2, 2, 8, 0, 0, 0, 0, false);
                for (int i = 0; i < 5; i++) box(w, p, chunk, 0, 5 - i - (i < 4 ? 1 : 0), 2 + i, 2, 7 - i, 2 + i, 0, 0, 0, 0, false);
                break;
            }
        }
    }

    /** MineshaftCorridor::_placeCobWeb: the light is checked at z, the web goes to z - 1 */
    static void web(World w, Piece p, Box chunk, McRandom r, float chance, int x, int y, int z) {
        if (bright(w, p, x, y, z) <= 7) maybeBlock(w, p, chunk, r, chance, x, y, z - 1, 30, 0);   // placed one block lower in z
    }

    /** MineshaftCorridor::_placeSupport(x0, y0, z, maxY, x1) */
    static void support(World w, Piece p, Box chunk, int x0, int y0, int z, int maxY, int x1, McRandom r) {
        if (DEBUG) System.err.println("DBG support " + x0 + " " + y0 + " " + z + " " + maxY + " " + x1);
        // MineshaftPiece::_isSupportingBox: every block of the row above the beam (world) exists
        int ax = p.wx(x0, z), az = p.wz(x0, z), bx = p.wx(x1, z), bz = p.wz(x1, z), y = p.wy(maxY + 1);
        for (int X = Math.min(ax, bx); X <= Math.max(ax, bx); X++) for (int Z = Math.min(az, bz); Z <= Math.max(az, bz); Z++)
            if (w.get(X, y, Z) == 0) return;
        Data d = p.data;
        box(w, p, chunk, x0, y0, z, x0, maxY - 1, z, d.fence, d.fenceData, 0, 0, false);
        box(w, p, chunk, x1, y0, z, x1, maxY - 1, z, d.fence, d.fenceData, 0, 0, false);
        if ((r.nextU32() & 3) != 0) {
            box(w, p, chunk, x0, maxY, z, x1, maxY, z, d.planks, d.planksData, 0, 0, false);
            maybeBlock(w, p, chunk, r, 0.05f, x0 + 1, maxY, z - 1, 50, torch(p, 2));
            maybeBlock(w, p, chunk, r, 0.05f, x0 + 1, maxY, z + 1, 50, torch(p, 0));
        } else {
            box(w, p, chunk, x0, maxY, z, x0, maxY, z, d.planks, d.planksData, 0, 0, false);
            box(w, p, chunk, x1, maxY, z, x1, maxY, z, d.planks, d.planksData, 0, 0, false);
        }
    }

    /** MineshaftCrossing::_placeSupportPillar */
    static void pillar(World w, Piece p, Box chunk, int x, int y, int z, int maxY) {
        if (block(w, p, chunk, x, maxY + 1, z) != 0)
            box(w, p, chunk, x, y, z, x, maxY, z, p.data.planks, p.data.planksData, 0, 0, false);
    }

    /** StructurePiece::generateUpperHalfSphere */
    static void halfSphere(World w, Piece p, Box chunk, int x0, int y0, int z0, int x1, int y1, int z1, int id, boolean skipAir) {
        float fx = x1 - x0 + 1, fy = y1 - y0 + 1, fz = z1 - z0 + 1, cx = x0 + fx / 2f, cz = z0 + fz / 2f;
        for (int y = y0; y <= y1; y++) {
            float dy = (float) (y - y0) / fy;
            for (int x = x0; x <= x1; x++) {
                float dx = ((float) x - cx) / (fx * 0.5f);
                for (int z = z0; z <= z1; z++) {
                    float dz = ((float) z - cz) / (fz * 0.5f);
                    if (skipAir && block(w, p, chunk, x, y, z) == 0) continue;
                    if (dx * dx + dy * dy + dz * dz <= 1.05f) place(w, p, chunk, id, 0, x, y, z);
                }
            }
        }
    }

    // ------------------------------------------------------------------ in the world
    List<Start> near(int cx, int cz) {
        List<Start> out = new ArrayList<Start>();
        for (int x = cx - 8; x <= cx + 8; x++) for (int z = cz - 8; z <= cz + 8; z++) {
            long k = com.nova.engine.world.World.key(x, z);
            Boolean c = checked.get(k);
            if (c == null) {
                Start s = feature(x, z);
                checked.put(k, s != null);
                if (s != null) starts.put(k, s);
                c = s != null;
            }
            if (c) out.add(starts.get(k));
        }
        return out;
    }

    /** StructureFeature::postProcess for this feature: every start once per chunk, its pieces that touch the chunk */
    void postProcess(World w, McRandom r, int cx, int cz) {
        Box chunk = new Box(cx << 4, 1, cz << 4, (cx << 4) + 15, 512, (cz << 4) + 15);
        for (Start s : near(cx, cz)) {
            if (!(s.bb.x1 >= chunk.x0 && s.bb.x0 <= chunk.x1 + 1 && s.bb.z1 >= chunk.z0 && s.bb.z0 <= chunk.z1 + 1)) continue;
            for (Piece p : s.pieces) if (p.bb.intersects(chunk)) postProcess(w, p, chunk, r);
        }
    }
}
