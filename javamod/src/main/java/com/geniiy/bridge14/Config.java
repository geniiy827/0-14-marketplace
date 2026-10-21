package com.geniiy.bridge14;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Настройки режима 0.14: профиль и список серверов. */
public final class Config {
    public static final class Server {
        public String name = "Сервер";
        public String host = "127.0.0.1";
        public int port = 19132;
        public Server() {}
        public Server(String n, String h, int p) { name = n; host = h; port = p; }
    }

    public String nick = "";
    public String skinPath = "";
    /** Тонкие руки (модель «Алекс»). */
    public boolean slim;
    /** Экспериментально: интерфейс сундуков/инвентаря в цветах 0.14. */
    public boolean classicGui;
    /** Bedwars fix: тап по предмету в сундуке сразу кладёт его в инвентарь. */
    public boolean bedwarsFix;
    /** Мобильный режим строительства включён. */
    public boolean mobileBuild;
    public int chunkRadius = 6;
    public List<Server> servers = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path dir() {
        Path d = FabricLoader.getInstance().getConfigDir().resolve("bridge14");
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        return d;
    }

    private static Path file() { return dir().resolve("config.json"); }

    public static Config load() {
        try (Reader r = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
            Config c = GSON.fromJson(r, Config.class);
            if (c != null) { if (c.servers == null) c.servers = new ArrayList<>(); return c; }
        } catch (Exception ignored) {}
        return new Config();
    }

    public void save() {
        try (Writer w = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) { GSON.toJson(this, w); }
        catch (Exception ignored) {}
    }

    /** Файл скина: выбранный, иначе skin.png в папке мода. */
    public File skinFile() {
        if (skinPath != null && !skinPath.isEmpty()) {
            File f = new File(skinPath);
            if (f.isFile()) return f;
        }
        File def = dir().resolve("skin.png").toFile();
        return def.isFile() ? def : null;
    }
}
