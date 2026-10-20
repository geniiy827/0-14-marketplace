package com.geniiy.bridge14;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Надписи сервера 0.14 на экране: popup (над хотбаром) и tip (выше).
 * Серверы выводят так монеты, ранг, онлайн и т.п. — в несколько строк, обновляя каждую секунду.
 */
public final class Hud {
    private static volatile String popup = "", tip = "";
    private static volatile long popupAt, tipAt;
    private static final long SHOW = 2600, FADE = 500;

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
        int bottom = h - 59 - (mc.player != null && !mc.player.isCreative() ? 14 : 0);
        String p = popup;
        if (!p.isEmpty() && now - popupAt < SHOW) {
            bottom = drawBlock(ctx, tr, p, w / 2, bottom, alpha(now - popupAt)) - 4;
        } else bottom -= 8;
        String t = tip;
        if (!t.isEmpty() && now - tipAt < SHOW) drawBlock(ctx, tr, t, w / 2, bottom, alpha(now - tipAt));
    }

    private static int alpha(long age) {
        long left = SHOW - age;
        return left >= FADE ? 255 : (int) Math.max(8, 255 * left / FADE);
    }

    /** Рисует строки снизу вверх так, чтобы последняя оказалась на bottom. Возвращает верх блока. */
    private static int drawBlock(DrawContext ctx, TextRenderer tr, String text, int cx, int bottom, int alpha) {
        String[] lines = text.split("\n");
        int y = bottom - lines.length * 10;
        int top = y;
        int color = (alpha << 24) | 0xFFFFFF;
        for (String line : lines) {
            int lw = tr.getWidth(line);
            ctx.fill(cx - lw / 2 - 2, y - 1, cx + lw / 2 + 2, y + 9, (Math.min(alpha, 0x60)) << 24);
            ctx.drawTextWithShadow(tr, line, cx - lw / 2, y, color);
            y += 10;
        }
        return top;
    }
}
