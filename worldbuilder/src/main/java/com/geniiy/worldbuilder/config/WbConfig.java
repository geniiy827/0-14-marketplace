package com.geniiy.worldbuilder.config;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

/** Настройки мода, профили серверов (пароли — AES-GCM со случайным ключом в .key). */
public class WbConfig {
    // ---- интерфейс ----
    public int uiScalePercent = 0; // 0 = авто
    public int paletteIcon = 24;
    public float selectionAlpha = 0.25f;
    public boolean tooltips = true;
    public int tooltipDelayMs = 400;
    public boolean tourDone = false;
    // ---- камера ----
    public int fov = 70;
    public float sensitivity = 1.0f;
    public boolean invertY = false;
    public float accelTime = 0.15f;
    public boolean smoothMove = true;
    public float baseSpeed = 12f;
    // ---- рендер ----
    public int renderDistance = 12;
    public int lightMode = 0; // 0 полная яркость, 1 день, 2 ночь
    public boolean fog = true;
    public boolean chunkGrid = false;
    public boolean worldBounds = true;
    public boolean showInvisible = false;
    public boolean floorGrid = false;
    public int meshThreads = 0; // 0 = авто
    public int skyTop = 0xFF6FA8DC, skyBottom = 0xFFBFD9F2;
    // ---- редактирование ----
    public boolean smartOrientation = true;
    public boolean pasteSkipAir = true;
    public long maxOpBlocks = 50_000_000L;
    public int undoSteps = 200;
    public int undoMemoryMb = 512;
    public int autosaveMinutes = 5;
    public boolean pickLiquids = false;
    // ---- подключения ----
    public boolean autofill = true;
    public boolean autoConnect = false;
    public String lastProfile;
    public boolean hideServerWarning = false;
    // ---- сохранение ----
    public boolean backup = true;
    public int keepBackups = 10;
    public boolean preventPopulation = true;
    public boolean computeLight = true;
    public int lastSaveMode = 0;
    public String zipDir = "";
    // ---- память ----
    public int cacheBudgetMb = 1024;
    // ---- данные ----
    public List<ServerProfile> profiles = new ArrayList<>();
    public int[] hotbar = {1 << 4, 4 << 4, 5 << 4, 17 << 4, 20 << 4, 45 << 4, 98 << 4, 2 << 4, 3 << 4};
    public List<Integer> favorites = new ArrayList<>();
    public List<Integer> recents = new ArrayList<>();
    public Map<String, Integer> keys = new LinkedHashMap<>();
    public Map<String, double[]> bookmarks = new LinkedHashMap<>();

    private static WbConfig inst;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("worldbuilder"); }
    public static Path gameDir() { return FabricLoader.getInstance().getGameDir().resolve("worldbuilder"); }

    public static WbConfig get() {
        if (inst == null) {
            Path f = dir().resolve("config.json");
            try {
                if (Files.exists(f)) inst = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), WbConfig.class);
            } catch (Exception e) { WorldBuilderMod.LOG.warn("[WorldBuilder] Не удалось прочитать настройки: {}", e.toString()); }
            if (inst == null) inst = new WbConfig();
            if (inst.profiles == null) inst.profiles = new ArrayList<>();
            if (inst.hotbar == null || inst.hotbar.length != 9) inst.hotbar = new WbConfig().hotbar;
            if (inst.favorites == null) inst.favorites = new ArrayList<>();
            if (inst.recents == null) inst.recents = new ArrayList<>();
            if (inst.keys == null) inst.keys = new LinkedHashMap<>();
            if (inst.bookmarks == null) inst.bookmarks = new LinkedHashMap<>();
        }
        return inst;
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            Path f = dir().resolve("config.json");
            Path tmp = dir().resolve("config.json.tmp");
            Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { WorldBuilderMod.LOG.warn("[WorldBuilder] Не удалось сохранить настройки: {}", e.toString()); }
    }

    public ServerProfile profile(String id) {
        for (ServerProfile p : profiles) if (p.id.equals(id)) return p;
        return null;
    }

    // ---------- шифрование паролей ----------
    private static byte[] key() throws IOException {
        Path k = dir().resolve(".key");
        if (Files.exists(k)) {
            byte[] b = Files.readAllBytes(k);
            if (b.length == 32) return b;
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        Files.createDirectories(dir());
        Files.write(k, b);
        return b;
    }

    public static String encrypt(String plain) {
        if (plain == null) return null;
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[12 + ct.length];
            System.arraycopy(iv, 0, out, 0, 12);
            System.arraycopy(ct, 0, out, 12, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) { WorldBuilderMod.LOG.warn("[WorldBuilder] Шифрование: {}", e.toString()); return null; }
    }

    public static String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) return null;
        try {
            byte[] in = Base64.getDecoder().decode(enc);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, in, 0, 12));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { return null; }
    }

    public void forgetAllPasswords() { for (ServerProfile p : profiles) p.encPassword = null; save(); }

    public void addRecent(int state) {
        recents.remove((Integer) state);
        recents.add(0, state);
        while (recents.size() > 20) recents.remove(recents.size() - 1);
    }
}
