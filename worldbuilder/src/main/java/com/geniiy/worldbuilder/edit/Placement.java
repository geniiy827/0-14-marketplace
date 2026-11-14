package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.pe.PeWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * «Умная ориентация» при установке блока (повторяет логику place() Genisys 0.14).
 * Грани: 0 низ, 1 верх, 2 север, 3 юг, 4 запад, 5 восток (как Direction.ordinal).
 */
public final class Placement {
    private Placement() {}

    /** Блок для установки: смещение относительно позиции установки и состояние. */
    public record Put(int dx, int dy, int dz, int state) {}

    /** Горизонтальное направление взгляда по yaw (0 — юг +Z, 90 — запад, 180 — север, 270 — восток): 2 N, 3 S, 4 W, 5 E. */
    public static int lookDir(double yaw) {
        double r = ((yaw % 360) + 360) % 360;
        if (r < 45 || r >= 315) return 3;
        if (r < 135) return 4;
        if (r < 225) return 2;
        return 5;
    }
    public static int opposite(int f) { return f ^ 1; }

    // направления в разных кодировках
    static int encA(int f) { return switch (f) { case 5 -> 0; case 4 -> 1; case 3 -> 2; default -> 3; }; } // 0E 1W 2S 3N
    static int encB(int f) { return switch (f) { case 3 -> 0; case 4 -> 1; case 2 -> 2; default -> 3; }; } // 0S 1W 2N 3E
    static int encDoor(int f) { return switch (f) { case 5 -> 0; case 3 -> 1; case 4 -> 2; default -> 3; }; } // 0E 1S 2W 3N
    static int encTorch(int f) { return switch (f) { case 5 -> 1; case 4 -> 2; case 3 -> 3; case 2 -> 4; default -> 5; }; }
    static int dx(int f) { return f == 5 ? 1 : f == 4 ? -1 : 0; }
    static int dz(int f) { return f == 3 ? 1 : f == 2 ? -1 : 0; }

    /**
     * @param state  выбранный блок (id:мета из палитры)
     * @param face   грань, по которой кликнули (−1 — «в воздухе»)
     * @param fy     высота точки клика внутри блока 0..1
     * @param yaw    поворот камеры, pitch — наклон (вниз положительный)
     * @param target состояние блока, по которому кликнули (для плит и какао)
     */
    public static List<Put> place(int state, int face, double fy, double yaw, double pitch, int target, PeWorld w, int x, int y, int z) {
        List<Put> out = new ArrayList<>();
        int id = state >> 4, meta = state & 15;
        PeBlocks.BlockInfo b = PeBlocks.get().block(id);
        int look = lookDir(yaw);
        if (face < 0) face = 1;
        if (b == null) { out.add(new Put(0, 0, 0, state)); return out; }
        int m = meta;
        switch (b.family) {
            case "stairs" -> {
                m = encA(look);
                if ((fy > 0.5 && face != 1) || face == 0) m |= 4;
            }
            case "slab" -> {
                m = meta & 7;
                if ((fy > 0.5 && face != 1) || face == 0) m |= 8;
            }
            case "log" -> m = (meta & 3) | (face <= 1 ? 0 : face <= 3 ? 8 : 4);
            case "axis" -> m = face <= 1 ? 0 : face <= 3 ? 8 : 4;
            case "torch" -> m = face == 0 ? 5 : face == 1 ? 5 : encTorch(face);
            case "facing4_face" -> {
                if (id == 65 || id == 68) m = face >= 2 ? face : opposite(look);
                else m = opposite(look);
            }
            case "chest" -> m = opposite(look);
            case "facing6" -> {
                if (pitch > 55) m = 1; else if (pitch < -55) m = 0; else m = opposite(look);
            }
            case "hopper" -> m = face <= 1 ? 0 : opposite(face);
            case "button" -> m = face;
            case "lever" -> {
                boolean zAxis = look == 2 || look == 3;
                if (face == 0) m = zAxis ? 7 : 0;
                else if (face == 1) m = zAxis ? 5 : 6;
                else m = encTorch(face);
            }
            case "item_frame" -> m = face >= 2 ? encA(face) : encA(opposite(look));
            case "trapdoor" -> {
                int f = face >= 2 ? face : opposite(look);
                m = encA(f);
                if ((fy > 0.5 && face >= 2) || face == 0) m |= 4;
            }
            case "fence_gate", "anvil" -> m = (meta & ~3) | encB(look);
            case "facing4_h" -> {
                if (id == 131) m = face >= 2 ? encB(face) : encB(opposite(look));
                else m = encB(opposite(look));
            }
            case "repeater" -> m = (meta & ~3) | encB(look);
            case "bed" -> {
                int f = encB(look);
                out.add(new Put(0, 0, 0, (id << 4) | f));
                out.add(new Put(dx(look), 0, dz(look), (id << 4) | f | 8));
                return out;
            }
            case "door" -> {
                int f = encDoor(look);
                // петли: если слева уже есть дверь — ставим петли справа
                int lf = switch (look) { case 2 -> 4; case 3 -> 5; case 4 -> 3; default -> 2; };
                int left = w.getState(x + dx(lf), y, z + dz(lf)) >> 4;
                int hinge = left == id ? 1 : 0;
                out.add(new Put(0, 0, 0, (id << 4) | f));
                out.add(new Put(0, 1, 0, (id << 4) | 8 | hinge));
                return out;
            }
            case "double_plant" -> {
                out.add(new Put(0, 0, 0, (id << 4) | (meta & 7)));
                out.add(new Put(0, 1, 0, (id << 4) | 8 | (meta & 7)));
                return out;
            }
            case "sign_post" -> {
                if (face >= 2) { out.add(new Put(0, 0, 0, (68 << 4) | face)); return out; }
                m = (int) Math.floor(((yaw + 180) * 16 / 360) + 0.5) & 15;
            }
            case "skull" -> m = face >= 2 ? face : 1;
            case "rail" -> {
                boolean ns = look == 2 || look == 3;
                m = (meta & 8) | (ns ? 0 : 1);
            }
            case "cocoa" -> {
                int f = face >= 2 ? opposite(face) : look;
                m = (meta & 0xC) | encB(f);
            }
            case "vine" -> m = switch (face) { case 2 -> 1; case 3 -> 4; case 4 -> 8; case 5 -> 2; default -> meta == 0 ? 1 : meta; };
            case "portal" -> m = (look == 2 || look == 3) ? 0 : 2;
            default -> {
                if (id == 155 && (meta == 2 || meta == 6 || meta == 10)) m = face <= 1 ? 2 : face <= 3 ? 10 : 6;
            }
        }
        out.add(new Put(0, 0, 0, (id << 4) | (m & 15)));
        return out;
    }

    /** Плита на такую же плиту → двойная плита. Возвращает состояние двойной плиты или −1. */
    public static int doubleSlab(int placing, int target, int face, double fy) {
        int id = placing >> 4, tid = target >> 4;
        int dbl = id == 44 ? 43 : id == 158 ? 157 : id == 182 ? 181 : -1;
        if (dbl < 0 || tid != id || (placing & 7) != (target & 7)) return -1;
        boolean top = (target & 8) != 0;
        if ((face == 1 && !top) || (face == 0 && top)) return (dbl << 4) | (target & 7);
        return -1;
    }

    /** Блоку нужна опора (факел, кнопка…)? */
    public static boolean needsSupport(int id) {
        PeBlocks.BlockInfo b = PeBlocks.get().block(id);
        if (b == null) return false;
        return switch (b.family) { case "torch", "button", "lever", "item_frame", "rail", "sapling", "crop", "vine", "cocoa", "double_plant", "door", "redstone_wire", "repeater" -> true;
            default -> "plants".equals(b.category); };
    }
}
