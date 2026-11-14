package com.geniiy.worldbuilder.blocks;

/**
 * Поворот и отражение блоков PE перекодировкой меты (раздел 21 ТЗ).
 * Поворот — на 90° по часовой при взгляде сверху (север → восток → юг → запад).
 */
public final class Rotation {
    private Rotation() {}

    // кодировки направлений (индексы: значение меты → новое значение)
    // A: 0 E, 1 W, 2 S, 3 N (ступени, люки, рамка)
    private static final int[] A_CW = {2, 3, 1, 0};
    private static final int[] A_MX = {1, 0, 2, 3};
    private static final int[] A_MZ = {0, 1, 3, 2};
    // B: 0 S, 1 W, 2 N, 3 E (калитки, кровати, тыквы, повторители, какао, наковальня)
    private static final int[] B_CW = {1, 2, 3, 0};
    private static final int[] B_MX = {0, 3, 2, 1};
    private static final int[] B_MZ = {2, 1, 0, 3};
    // C: 2 N, 3 S, 4 W, 5 E (печи, сундуки, раздатчики, кнопки…), 0/1 вертикаль
    private static final int[] C_CW = {0, 1, 5, 4, 2, 3, 6, 7};
    private static final int[] C_MX = {0, 1, 2, 3, 5, 4, 6, 7};
    private static final int[] C_MZ = {0, 1, 3, 2, 4, 5, 6, 7};
    // D: 1 E, 2 W, 3 S, 4 N (факелы), 0/5 пол
    private static final int[] D_CW = {0, 3, 4, 2, 1, 5, 6, 7};
    private static final int[] D_MX = {0, 2, 1, 3, 4, 5, 6, 7};
    private static final int[] D_MZ = {0, 1, 2, 4, 3, 5, 6, 7};
    // рычаг: 1–4 как факел; 5↔6 (пол Z/X); 0↔7 (потолок X/Z)
    private static final int[] L_CW = {7, 3, 4, 2, 1, 6, 5, 0};
    // двери (низ): 0 E, 1 S, 2 W, 3 N
    private static final int[] DOOR_CW = {1, 2, 3, 0};
    private static final int[] DOOR_MX = {2, 1, 0, 3};
    private static final int[] DOOR_MZ = {0, 3, 2, 1};
    // рельсы 0–9
    private static final int[] RAIL_CW = {1, 0, 5, 4, 2, 3, 7, 8, 9, 6};
    private static final int[] RAIL_MX = {0, 1, 3, 2, 4, 5, 7, 6, 9, 8};
    private static final int[] RAIL_MZ = {0, 1, 2, 3, 5, 4, 9, 8, 7, 6};
    // грибные блоки
    private static final int[] MUSH_CW = {0, 3, 6, 9, 2, 5, 8, 1, 4, 7, 10, 11, 12, 13, 14, 15};
    private static final int[] MUSH_MX = {0, 3, 2, 1, 6, 5, 4, 9, 8, 7, 10, 11, 12, 13, 14, 15};
    private static final int[] MUSH_MZ = {0, 7, 8, 9, 4, 5, 6, 1, 2, 3, 10, 11, 12, 13, 14, 15};

    public enum Op { CW, MIRROR_X, MIRROR_Z, MIRROR_Y }

    public static int rotateCW(int state) { return apply(state, Op.CW); }
    public static int rotate(int state, int quarterTurnsCW) {
        int q = ((quarterTurnsCW % 4) + 4) % 4;
        for (int i = 0; i < q; i++) state = apply(state, Op.CW);
        return state;
    }
    public static int mirrorX(int state) { return apply(state, Op.MIRROR_X); }
    public static int mirrorZ(int state) { return apply(state, Op.MIRROR_Z); }
    public static int mirrorY(int state) { return apply(state, Op.MIRROR_Y); }

    public static int apply(int state, Op op) {
        int id = state >> 4, m = state & 15;
        PeBlocks.BlockInfo b = PeBlocks.get().block(id);
        if (b == null) return state;
        int nm = applyMeta(b.family, id, m, op);
        return (id << 4) | (nm & 15);
    }

    static int low2(int m, int[] t) { return (m & ~3) | t[m & 3]; }
    static int low3(int m, int[] t) { return (m & ~7) | t[m & 7]; }

    public static int applyMeta(String family, int id, int m, Op op) {
        if (op == Op.MIRROR_Y) {
            return switch (family) {
                case "stairs" -> m ^ 4;
                case "slab" -> m ^ 8;
                case "trapdoor" -> m ^ 4;
                default -> m;
            };
        }
        switch (family) {
            case "stairs":
            case "trapdoor":
            case "item_frame":
                return low2(m, op == Op.CW ? A_CW : op == Op.MIRROR_X ? A_MX : A_MZ);
            case "fence_gate":
            case "bed":
            case "facing4_h":
            case "repeater":
            case "cocoa":
            case "anvil":
                return low2(m, op == Op.CW ? B_CW : op == Op.MIRROR_X ? B_MX : B_MZ);
            case "facing4_face":
            case "chest":
            case "facing6":
            case "hopper":
            case "button":
            case "skull":
                return low3(m, op == Op.CW ? C_CW : op == Op.MIRROR_X ? C_MX : C_MZ);
            case "torch":
                return low3(m, op == Op.CW ? D_CW : op == Op.MIRROR_X ? D_MX : D_MZ);
            case "lever":
                return low3(m, op == Op.CW ? L_CW : op == Op.MIRROR_X ? D_MX : D_MZ);
            case "door":
                if ((m & 8) != 0) return op == Op.CW ? m : m ^ 1; // верх: при отражении меняются петли
                return low2(m, op == Op.CW ? DOOR_CW : op == Op.MIRROR_X ? DOOR_MX : DOOR_MZ);
            case "sign_post":
                return switch (op) {
                    case CW -> (m + 4) & 15;
                    case MIRROR_X -> (16 - m) & 15;
                    default -> (8 - m) & 15;
                };
            case "log":
                if (op != Op.CW) return m;
                { int ax = (m >> 2) & 3; if (ax == 1) ax = 2; else if (ax == 2) ax = 1; return (m & 3) | (ax << 2); }
            case "axis":
                if (op != Op.CW) return m;
                if ((m & 0xC) == 4) return (m & 3) | 8;
                if ((m & 0xC) == 8) return (m & 3) | 4;
                return m;
            case "rail": {
                if (id == 66) { if (m > 9) return m; return (op == Op.CW ? RAIL_CW : op == Op.MIRROR_X ? RAIL_MX : RAIL_MZ)[m]; }
                int s = m & 7;
                if (s > 5) return m;
                return (m & 8) | (op == Op.CW ? RAIL_CW : op == Op.MIRROR_X ? RAIL_MX : RAIL_MZ)[s];
            }
            case "vine": {
                boolean s = (m & 1) != 0, w = (m & 2) != 0, n = (m & 4) != 0, e = (m & 8) != 0;
                boolean ns, nw, nn, ne;
                switch (op) {
                    case CW -> { nw = s; nn = w; ne = n; ns = e; }
                    case MIRROR_X -> { ns = s; nn = n; nw = e; ne = w; }
                    default -> { nw = w; ne = e; ns = n; nn = s; }
                }
                return (ns ? 1 : 0) | (nw ? 2 : 0) | (nn ? 4 : 0) | (ne ? 8 : 0);
            }
            case "mushroom_block":
                return (op == Op.CW ? MUSH_CW : op == Op.MIRROR_X ? MUSH_MX : MUSH_MZ)[m];
            case "portal":
                if (op != Op.CW) return m;
                return m == 0 ? 2 : m == 2 ? 0 : m;
            default:
                if (id == 155 && op == Op.CW) return m == 6 ? 10 : m == 10 ? 6 : m;
                return m;
        }
    }

    /** Поворот Rot головы на полу (тайл Skull). */
    public static int rotateSkullRot(int rot, Op op) {
        return switch (op) {
            case CW -> (rot + 4) & 15;
            case MIRROR_X -> (16 - rot) & 15;
            case MIRROR_Z -> (8 - rot) & 15;
            default -> rot;
        };
    }
}
