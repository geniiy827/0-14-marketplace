package com.geniiy.bridge14.server;

import com.geniiy.bridge14.proxy.Session;
import com.geniiy.bridge14.proxy.Skins;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Настоящий скин игрока Bedrock: Geyser держит данные входа клиента (BedrockClientData) с сырым RGBA скина.
 * Всё через отражение — никакой зависимости от версии Geyser при сборке.
 */
final class GeyserSkins implements Session.SkinSource {
    private final ClassLoader loader;
    private final Consumer<String> log;
    private boolean warned;

    GeyserSkins(ClassLoader loader, Consumer<String> log) { this.loader = loader; this.log = log; }

    @Override
    public Session.Skin find(String name, UUID uuid, List<String[]> props) {
        try {
            Class<?> apiCls = Class.forName("org.geysermc.geyser.api.GeyserApi", true, loader);
            Object api = apiCls.getMethod("api").invoke(null);
            Object conn = null;
            if (uuid != null) conn = apiCls.getMethod("connectionByUuid", UUID.class).invoke(api, uuid);
            if (conn == null) {
                List<?> all = (List<?>) apiCls.getMethod("onlineConnections").invoke(api);
                for (Object c : all) {
                    for (String m : new String[]{"javaUsername", "bedrockUsername", "name"}) {
                        Object n = call(c, m);
                        if (n != null && n.toString().equalsIgnoreCase(name)) { conn = c; break; }
                    }
                    if (conn != null) break;
                }
            }
            if (conn == null) return null;                     // не Bedrock-игрок
            Object cd = call(conn, "getClientData");
            if (cd == null) return null;
            byte[] raw = bytes(call(cd, "getSkinData"));
            int w = num(call(cd, "getSkinImageWidth")), h = num(call(cd, "getSkinImageHeight"));
            boolean persona = Boolean.TRUE.equals(call(cd, "isPersonaSkin"));
            String geo = new String(bytesOrEmpty(call(cd, "getGeometryName")), StandardCharsets.UTF_8);
            if (geo.isEmpty()) geo = new String(bytesOrEmpty(call(cd, "getSkinResourcePatch")), StandardCharsets.UTF_8);
            Object arm = call(cd, "getArmSize");
            boolean slim = geo.contains("customSlim") || (arm != null && "slim".equalsIgnoreCase(arm.toString()));
            // скины-«персоны» и необычные модели раскладывают текстуру по-своему — на 0.14 они развалятся
            boolean classic = geo.isEmpty() || geo.contains("geometry.humanoid.custom\"") || geo.contains("geometry.humanoid.customSlim")
                    || geo.contains("geometry.humanoid\"");
            if (persona || !classic) {
                Session.Skin viaProps = Skins.fromProperties(props);
                if (viaProps != null) return new Session.Skin(viaProps.rgba, viaProps.slim, "Bedrock (персона, через Geyser-скин)");
                log.accept("Скин Bedrock игрока " + name + " — персона/своя модель, на 0.14 его не показать; будет обычный скин");
                return null;
            }
            byte[] rgba = Skins.fromRgba(raw, w, h);
            if (rgba == null) { log.accept("Скин Bedrock игрока " + name + ": неподходящий размер " + w + "×" + h); return null; }
            return new Session.Skin(rgba, slim, "Bedrock " + w + "×" + h);
        } catch (ClassNotFoundException e) {
            if (!warned) { warned = true; log.accept("Geyser не найден — Bedrock-скины не подключены"); }
            return null;
        } catch (Throwable e) {
            if (!warned) { warned = true; log.accept("Bedrock-скин через Geyser не получился: " + e); }
            return null;
        }
    }

    private static Object call(Object o, String m) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Method mm = c.getDeclaredMethod(m); mm.setAccessible(true); return mm.invoke(o); }
            catch (NoSuchMethodException ignored) {}
            catch (Exception e) { return null; }
        }
        try { return o.getClass().getMethod(m).invoke(o); } catch (Exception e) { return null; }
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    private static byte[] bytesOrEmpty(Object o) { byte[] b = bytes(o); return b == null ? new byte[0] : b; }

    /** В старых Geyser это строка base64, в новых — уже byte[]. */
    private static byte[] bytes(Object o) {
        if (o instanceof byte[]) return (byte[]) o;
        if (o instanceof String) { try { return Base64.getDecoder().decode(((String) o).trim()); } catch (Exception e) { return ((String) o).getBytes(StandardCharsets.UTF_8); } }
        return null;
    }
}
