package com.geniiy.bridge14.proxy;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

/** Скины для сервера 0.14: из свойства профиля textures (Java/прокси) и из сырого RGBA (Bedrock через Geyser). */
public final class Skins {
    private Skins() {}

    private static final Pattern PROP = Pattern.compile("\\{[^{}]*\"name\"\\s*:\\s*\"([^\"]*)\"[^{}]*\"value\"\\s*:\\s*\"([^\"]*)\"(?:[^{}]*\"signature\"\\s*:\\s*\"([^\"]*)\")?[^{}]*\\}");
    private static final Pattern SKIN_URL = Pattern.compile("\"SKIN\"\\s*:\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SLIM = Pattern.compile("\"model\"\\s*:\\s*\"slim\"");

    /** [{"name":..,"value":..,"signature":..}, …] → список [имя, значение, подпись]. */
    public static List<String[]> parsePropertiesJson(String json) {
        List<String[]> out = new ArrayList<>();
        if (json == null) return out;
        Matcher m = PROP.matcher(json);
        while (m.find()) out.add(new String[]{m.group(1), m.group(2), m.group(3)});
        return out;
    }

    /** Скин из свойства textures: качаем PNG по ссылке. null — нет свойства или не удалось. */
    public static Session.Skin fromProperties(List<String[]> props) throws Exception {
        if (props == null) return null;
        for (String[] p : props) {
            if (!"textures".equals(p[0]) || p[1] == null) continue;
            String json = new String(Base64.getDecoder().decode(p[1].trim()), StandardCharsets.UTF_8);
            Matcher m = SKIN_URL.matcher(json);
            if (!m.find()) return null;
            String url = m.group(1).replace("\\/", "/");
            boolean slim = SLIM.matcher(json).find();
            BufferedImage img = download(url);
            byte[] rgba = img == null ? null : fromImage(img);
            return rgba == null ? null : new Session.Skin(rgba, slim, "скин профиля");
        }
        return null;
    }

    private static BufferedImage download(String url) throws Exception {
        if (url.startsWith("http://textures.minecraft.net")) url = "https" + url.substring(4);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Bridge0_14");
        try (InputStream in = c.getInputStream()) { return ImageIO.read(in); }
    }

    /** PNG любого «скинового» размера → RGBA 64×64 (или 64×32). */
    public static byte[] fromImage(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        if (w < 64 || (h != w && h * 2 != w)) return null;
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        return fromArgb(px, w, h);
    }

    /** Сырой RGBA (как его шлёт клиент Bedrock) любого размера 64/128/256 → RGBA 64×64 / 64×32. */
    public static byte[] fromRgba(byte[] raw, int w, int h) {
        if (raw == null || w <= 0 || h <= 0 || raw.length < w * h * 4) return null;
        if (w < 64 || (h != w && h * 2 != w)) return null;
        int[] px = new int[w * h];
        for (int i = 0; i < w * h; i++)
            px[i] = ((raw[i * 4 + 3] & 0xff) << 24) | ((raw[i * 4] & 0xff) << 16) | ((raw[i * 4 + 1] & 0xff) << 8) | (raw[i * 4 + 2] & 0xff);
        return fromArgb(px, w, h);
    }

    private static byte[] fromArgb(int[] px, int w, int h) {
        int k = w / 64, oh = h / k;           // 128×128 → шаг 2, 256×256 → шаг 4
        byte[] out = new byte[64 * oh * 4];
        for (int y = 0; y < oh; y++) for (int x = 0; x < 64; x++) {
            int argb = px[(y * k) * w + x * k];
            int i = (y * 64 + x) * 4;
            out[i] = (byte) (argb >> 16); out[i + 1] = (byte) (argb >> 8); out[i + 2] = (byte) argb; out[i + 3] = (byte) (argb >>> 24);
        }
        return out;
    }
}
