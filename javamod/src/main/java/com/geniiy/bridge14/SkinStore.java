package com.geniiy.bridge14;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Скины игроков 0.14 (свои и чужие): RGBA → текстура игры. Подменяются через PlayerListEntryMixin. */
public final class SkinStore {
    private static final class Entry {
        final byte[] rgba; final boolean slim; volatile Identifier id; volatile boolean failed;
        Entry(byte[] r, boolean s) { rgba = r; slim = s; }
    }

    private static final Map<UUID, Entry> SKINS = new ConcurrentHashMap<>();
    private static final Map<UUID, Identifier> OLD = new ConcurrentHashMap<>();
    /** Ключ для «своего» скина в меню. */
    public static final UUID LOCAL = new UUID(0x0B14L, 0x5C1AL);

    private SkinStore() {}

    public static void put(UUID u, byte[] rgba, boolean slim) {
        if (u == null || rgba == null || (rgba.length != 8192 && rgba.length != 16384)) return;
        Entry old = SKINS.put(u, new Entry(rgba, slim));
        if (old != null && old.id != null) OLD.put(u, old.id);
    }

    /** Убрать чужие скины (после выхода с сервера). */
    public static void clearRemote() {
        for (UUID u : SKINS.keySet()) if (!LOCAL.equals(u)) { Entry e = SKINS.remove(u); if (e != null && e.id != null) OLD.put(u, e.id); }
    }

    public static boolean has(UUID u) { return u != null && SKINS.containsKey(u); }

    public static Boolean slim(UUID u) { Entry e = u == null ? null : SKINS.get(u); return e == null ? null : e.slim; }

    /** Текстура (создаётся при первом обращении; вызывать только в потоке отрисовки). */
    public static Identifier texture(UUID u) {
        if (u == null) return null;
        Entry e = SKINS.get(u);
        if (e == null || e.failed) return null;
        if (e.id != null) return e.id;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!mc.isOnThread()) return null;
        try {
            Identifier prev = OLD.remove(u);
            if (prev != null) mc.getTextureManager().destroyTexture(prev);
            NativeImage img = toImage(e.rgba);
            Identifier id = new Identifier("bridge14", "skins/" + u.toString().replace("-", "") + "_" + Integer.toHexString(System.identityHashCode(e)));
            mc.getTextureManager().registerTexture(id, new NativeImageBackedTexture(img));
            e.id = id;
            return id;
        } catch (Throwable t) {
            e.failed = true;
            Bridge14.LOG.warn("Скин не загрузился: " + t);
            return null;
        }
    }

    /** RGBA 64×64 или 64×32 → картинка 64×64 (старый формат достраивается, как это делает сама игра). */
    static NativeImage toImage(byte[] rgba) {
        int h = rgba.length / (64 * 4);
        NativeImage img = new NativeImage(64, 64, true);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) img.setColor(x, y, 0);
        for (int y = 0; y < h; y++) for (int x = 0; x < 64; x++) {
            int i = (y * 64 + x) * 4;
            int r = rgba[i] & 0xff, g = rgba[i + 1] & 0xff, b = rgba[i + 2] & 0xff, a = rgba[i + 3] & 0xff;
            img.setColor(x, y, (a << 24) | (b << 16) | (g << 8) | r);
        }
        if (h == 32) {
            img.copyRect(4, 16, 16, 32, 4, 4, true, false);
            img.copyRect(8, 16, 16, 32, 4, 4, true, false);
            img.copyRect(0, 20, 24, 32, 4, 12, true, false);
            img.copyRect(4, 20, 16, 32, 4, 12, true, false);
            img.copyRect(8, 20, 8, 32, 4, 12, true, false);
            img.copyRect(12, 20, 16, 32, 4, 12, true, false);
            img.copyRect(44, 16, -8, 32, 4, 4, true, false);
            img.copyRect(48, 16, -8, 32, 4, 4, true, false);
            img.copyRect(40, 20, 0, 32, 4, 12, true, false);
            img.copyRect(44, 20, -8, 32, 4, 12, true, false);
            img.copyRect(48, 20, -16, 32, 4, 12, true, false);
            img.copyRect(52, 20, -8, 32, 4, 12, true, false);
        }
        // основной слой непрозрачный (как в игре), иначе «дырявые» скины
        opaque(img, 0, 0, 32, 16);
        opaque(img, 0, 16, 64, 32);
        opaque(img, 16, 48, 48, 64);
        return img;
    }

    private static void opaque(NativeImage img, int x1, int y1, int x2, int y2) {
        for (int x = x1; x < x2; x++) for (int y = y1; y < y2; y++) img.setColor(x, y, img.getColor(x, y) | 0xFF000000);
    }
}
