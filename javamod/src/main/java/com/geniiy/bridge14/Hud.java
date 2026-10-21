package com.geniiy.bridge14;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Надписи сервера 0.14 на экране (popup и tip) — так серверы выводят монеты, онлайн, «нельзя ломать здесь» и т.п.
 * В 0.14 текст начинается немного выше середины низа экрана и идёт вниз; каждая строка по центру,
 * а «боковые меню» сервер делает пробелами в начале строк — поэтому пробелы сохраняем как есть.
 */
public final class Hud {
    private static volatile String popup = "", tip = "";
    private static volatile long popupAt, tipAt;
    private static final long SHOW = 2200, FADE = 400;

    private Hud() {}

    public static void set(String text, boolean isTip) {
        if (isTip) { tip = text; tipAt = System.currentTimeMillis(); }
        else { popup = text; popupAt = System.currentTimeMillis(); }
    }

    public static void clear() { popup = ""; tip = ""; }

    public static void render(DrawContext ctx, float delta) {
        if (!Bridge14.active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        long now = System.currentTimeMillis();
        String p = popup, t = tip;
        boolean showP = !p.isEmpty() && now - popupAt < SHOW, showT = !t.isEmpty() && now - tipAt < SHOW;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 200);
        int topP = h;
        if (showP) { topP = top(h, p.split("\n", -1).length); drawBlock(ctx, tr, p, w / 2, topP, alpha(now - popupAt)); }
        if (showT) {
            int lines = t.split("\n", -1).length;
            int topT = top(h, lines);
            if (showP && topT + lines * 10 > topP) topT = topP - lines * 10 - 2;
            drawBlock(ctx, tr, t, w / 2, topT, alpha(now - tipAt));
        }
        ctx.getMatrices().pop();
    }

    /** Короткий текст — чуть выше хотбара (как в 0.14); длинный («меню» сервера) — целиком над сердечками, не заходя на хотбар. */
    private static int top(int h, int lines) { return Math.min(h - 72, h - 52 - lines * 10); }

    private static int alpha(long age) {
        long left = SHOW - age;
        return left >= FADE ? 255 : (int) Math.max(8, 255 * left / FADE);
    }

    private static void drawBlock(DrawContext ctx, TextRenderer tr, String text, int cx, int top, int alpha) {
        int color = (alpha << 24) | 0xFFFFFF;
        int y = top;
        for (String line : text.split("\n", -1)) {
            int lw = tr.getWidth(line);
            ctx.drawTextWithShadow(tr, line, cx - lw / 2, y, color);
            y += 10;
        }
    }
}
