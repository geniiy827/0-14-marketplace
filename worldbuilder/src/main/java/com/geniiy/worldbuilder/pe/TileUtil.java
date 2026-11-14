package com.geniiy.worldbuilder.pe;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.nbt.Tag.CompoundTag;
import com.geniiy.worldbuilder.nbt.Tag.ListTag;

/** Минимальные тайлы так, как их создаёт Genisys (раздел 3.5 ТЗ). */
public final class TileUtil {
    private TileUtil() {}

    public static CompoundTag create(String tileId, int x, int y, int z) {
        CompoundTag t = new CompoundTag();
        t.putString("id", tileId);
        t.putInt("x", x);
        t.putInt("y", y);
        t.putInt("z", z);
        switch (tileId) {
            case "Chest", "Furnace", "BrewingStand", "Dispenser", "Dropper" -> t.put("Items", new ListTag(Tag.COMPOUND));
            case "Sign" -> { t.putString("Text1", ""); t.putString("Text2", ""); t.putString("Text3", ""); t.putString("Text4", ""); }
            case "FlowerPot" -> { t.putShort("item", 0); t.putInt("data", 0); }
            case "Skull" -> { t.putByte("SkullType", 0); t.putByte("Rot", 0); }
            case "ItemFrame" -> { t.putByte("ItemRotation", 0); t.putFloat("ItemDropChance", 1.0f); }
            case "Cauldron" -> { t.putShort("PotionId", -1); t.putByte("SplashPotion", 0); t.put("Items", new ListTag(Tag.COMPOUND)); }
            case "MobSpawner" -> t.putInt("EntityId", 0);
            default -> {}
        }
        return t;
    }

    /** Нужный тайл для блока id или null. У воронки и компаратора в Genisys 0.14 тайла нет. */
    public static String tileFor(int id) {
        if (id == 154 || id == 149 || id == 150) return null;
        return PeBlocks.get().tileId(id);
    }

    /** Скопировать тайл в новую позицию (с исправлением пары двойного сундука на смещение dx/dz). */
    public static CompoundTag moved(CompoundTag t, int nx, int ny, int nz) {
        CompoundTag c = t.copy();
        int ox = t.getInt("x", nx), oz = t.getInt("z", nz);
        c.setNumberKeepType("x", nx, Tag.INT);
        c.setNumberKeepType("y", ny, Tag.INT);
        c.setNumberKeepType("z", nz, Tag.INT);
        if (c.has("pairx") && c.has("pairz")) {
            c.setNumberKeepType("pairx", c.getInt("pairx", 0) - ox + nx, Tag.INT);
            c.setNumberKeepType("pairz", c.getInt("pairz", 0) - oz + nz, Tag.INT);
        }
        return c;
    }
}
