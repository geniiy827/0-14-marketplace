package com.geniiy.bridge14;

import com.geniiy.bridge14.proxy.JBuf;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Собирает пакет тегов из ванильных данных, которые уже лежат в самой игре.
 * Без тегов Java-клиент не знает, что вода — это вода (плавание), лестница — лазается, чем что копается.
 */
public final class TagsBuilder {
    private static byte[] cached;

    public static synchronized byte[] build() {
        if (cached != null) return cached;
        JBuf out = new JBuf();
        List<byte[]> regs = new ArrayList<>();
        try {
            regs.add(registry("minecraft:block", "blocks", Registries.BLOCK));
            regs.add(registry("minecraft:item", "items", Registries.ITEM));
            regs.add(registry("minecraft:fluid", "fluids", Registries.FLUID));
            regs.add(registry("minecraft:entity_type", "entity_types", Registries.ENTITY_TYPE));
            regs.add(registry("minecraft:game_event", "game_events", Registries.GAME_EVENT));
        } catch (Exception e) {
            Bridge14.LOG.warn("Теги не собраны: " + e);
        }
        regs.removeIf(r -> r == null);
        out.varint(regs.size());
        for (byte[] r : regs) out.raw(r);
        cached = out.bytes();
        return cached;
    }

    private static <T> byte[] registry(String regId, String folder, Registry<T> reg) throws Exception {
        Optional<ModContainer> mc = FabricLoader.getInstance().getModContainer("minecraft");
        if (mc.isEmpty()) return null;
        Path root = null;
        for (Path p : mc.get().getRootPaths()) {
            Path t = p.resolve("data/minecraft/tags/" + folder);
            if (Files.isDirectory(t)) { root = t; break; }
        }
        if (root == null) return null;
        Map<String, JsonObject> raw = new HashMap<>();
        final Path base = root;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String fn = base.relativize(f).toString().replace('\\', '/');
                if (!fn.endsWith(".json")) continue;
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    raw.put("minecraft:" + fn.substring(0, fn.length() - 5), JsonParser.parseReader(r).getAsJsonObject());
                }
            }
        }
        Map<String, Set<Integer>> resolved = new HashMap<>();
        for (String tag : raw.keySet()) resolve(tag, raw, reg, resolved, new HashSet<>());
        JBuf b = new JBuf();
        b.str(regId).varint(resolved.size());
        for (Map.Entry<String, Set<Integer>> e : resolved.entrySet()) {
            b.str(e.getKey()).varint(e.getValue().size());
            for (int id : e.getValue()) b.varint(id);
        }
        return b.bytes();
    }

    private static <T> Set<Integer> resolve(String tag, Map<String, JsonObject> raw, Registry<T> reg, Map<String, Set<Integer>> done, Set<String> stack) {
        Set<Integer> have = done.get(tag);
        if (have != null) return have;
        Set<Integer> out = new LinkedHashSet<>();
        JsonObject o = raw.get(tag);
        if (o == null || !stack.add(tag)) return out;
        if (o.has("values")) {
            for (JsonElement v : o.getAsJsonArray("values")) {
                String id = v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString();
                if (id.startsWith("#")) out.addAll(resolve(id.substring(1), raw, reg, done, stack));
                else {
                    Identifier ident = Identifier.tryParse(id);
                    if (ident != null && reg.containsId(ident)) out.add(reg.getRawId(reg.get(ident)));
                }
            }
        }
        stack.remove(tag);
        done.put(tag, out);
        return out;
    }
}
