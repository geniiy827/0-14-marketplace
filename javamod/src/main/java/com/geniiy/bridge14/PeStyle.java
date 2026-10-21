package com.geniiy.bridge14;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.RotatingCubeMapRenderer;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Оформление в стиле Minecraft PE 0.14: серо-бежевые «объёмные» кнопки, полоса заголовка, панорама. */
public final class PeStyle {
    public static final int FILL = 0xFF8C837C, FILL_HOVER = 0xFFA39B93, FILL_OFF = 0xFF6B645E;
    public static final int LIGHT = 0xFFB5ADA6, SHADOW = 0xFF5A534D, BORDER = 0xFF1E1B19;
    public static final int BAR = 0xFF7F7671, BAR_DARK = 0xFF2B2725, ROW = 0xFF8F877F, ROW_HOVER = 0xFF9C948C, ROW_SEL = 0xFFA79F96;
    private static RotatingCubeMapRenderer panorama;

    private PeStyle() {}

    public static void background(DrawContext ctx, int w, int h, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) { ctx.fillGradient(0, 0, w, h, 0xA0101010, 0xC0101010); return; }
        try {
            if (panorama == null) panorama = new RotatingCubeMapRenderer(TitleScreen.PANORAMA_CUBE_MAP);
            panorama.render(delta, 1f);
        } catch (Throwable t) {
            ctx.fill(0, 0, w, h, 0xFF202020);
        }
        ctx.fill(0, 0, w, h, 0x60000000);
    }

    /** Кнопка 0.14: рамка, светлый верх, тёмный низ. */
    public static void button(DrawContext ctx, TextRenderer tr, int x, int y, int w, int h, Text text, boolean hover, boolean active, boolean pressed) {
        ctx.fill(x, y, x + w, y + h, BORDER);
        int fill = !active ? FILL_OFF : hover ? FILL_HOVER : FILL;
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        if (!pressed) {
            ctx.fill(x + 1, y + 1, x + w - 1, y + 2, active ? LIGHT : FILL_OFF);
            ctx.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, SHADOW);
        } else ctx.fill(x + 1, y + 1, x + w - 1, y + 3, SHADOW);
        int color = active ? 0xFFFFFF : 0xB0B0B0;
        int tw = tr.getWidth(text);
        int ty = y + (h - 8) / 2 - (pressed ? 0 : 1);
        if (tw > w - 6) { // не влезает — уменьшаем
            float sc = (w - 6) / (float) tw;
            ctx.getMatrices().push();
            ctx.getMatrices().translate(x + w / 2f, y + h / 2f, 0);
            ctx.getMatrices().scale(sc, sc, 1);
            ctx.drawTextWithShadow(tr, text, -tw / 2, -4, color);
            ctx.getMatrices().pop();
        } else ctx.drawTextWithShadow(tr, text, x + (w - tw) / 2, ty, color);
    }

    /** Полоса заголовка как в 0.14 (кнопка «Назад» рисуется отдельно). */
    public static int bar(DrawContext ctx, TextRenderer tr, int w, String title) {
        int h = 26;
        ctx.fill(0, 0, w, h, BAR);
        ctx.fill(0, 0, w, 1, 0xFF9C948D);
        ctx.fill(0, h - 2, w, h, BAR_DARK);
        ctx.drawCenteredTextWithShadow(tr, title, w / 2, (h - 9) / 2, 0xFFFFFF);
        return h;
    }

    /** Плашка (строка списка, панель). */
    public static void panel(DrawContext ctx, int x, int y, int w, int h, int fill) {
        ctx.fill(x, y, x + w, y + h, BORDER);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        ctx.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, SHADOW);
    }

    /** Круглый индикатор (онлайн/нет ответа). */
    public static void dot(DrawContext ctx, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.round(Math.sqrt(r * r - dy * dy + 0.5));
            ctx.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, 0xFF1E1B19);
        }
        for (int dy = -r + 1; dy <= r - 1; dy++) {
            int dx = (int) Math.round(Math.sqrt((r - 1) * (r - 1) - dy * dy + 0.5));
            ctx.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
        ctx.fill(cx - r / 2, cy - r / 2, cx, cy - r / 2 + 1, 0x60FFFFFF);
    }

    /** Кнопка с отрисовкой 0.14. */
    public static class Button extends ButtonWidget {
        private int textColor = -1;
        public Button(int x, int y, int w, int h, Text msg, PressAction onPress) { super(x, y, w, h, msg, onPress, DEFAULT_NARRATION_SUPPLIER); }
        public Button red() { textColor = 0xFF4A3A; return this; }
        @Override
        protected void renderButton(DrawContext ctx, int mouseX, int mouseY, float delta) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            boolean hover = isHovered() && active;
            Text t = textColor >= 0 ? getMessage().copy().styled(s -> s.withColor(textColor)) : getMessage();
            button(ctx, tr, getX(), getY(), getWidth(), getHeight(), t, hover, active, hover && MinecraftClient.getInstance().mouse.wasLeftButtonClicked());
        }
    }
}
