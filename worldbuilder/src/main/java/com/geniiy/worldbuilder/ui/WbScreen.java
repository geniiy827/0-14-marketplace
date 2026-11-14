package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.config.WbConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/** Базовый экран мода: собственный масштаб интерфейса, немедленный GUI, тосты и модальные диалоги. */
public abstract class WbScreen extends Screen {
    protected final Gui gui = new Gui();
    protected int W, H; // размеры в единицах интерфейса мода
    protected final Deque<Dialog> dialogs = new ArrayDeque<>();
    protected final List<Toast> toasts = new ArrayList<>();

    public record Toast(String text, int color, long time, String details) {}

    /** Модальный диалог (рисуется поверх, Enter — основное действие, Esc — отмена). */
    public abstract static class Dialog {
        public String title;
        public int w = 320, h = 160;
        public Dialog(String title) { this.title = title; }
        /** Нарисовать содержимое; вернуть true, чтобы закрыть. */
        public abstract boolean render(Gui g, int x, int y);
        public boolean onEnter() { return false; }
        public boolean onEsc() { return true; }
    }

    protected WbScreen(String title) { super(Text.literal(title)); }

    public float uiScale() {
        int pct = WbConfig.get().uiScalePercent;
        double phys;
        if (pct <= 0) {
            int fw = client.getWindow().getFramebufferWidth(), fh = client.getWindow().getFramebufferHeight();
            double k = Math.min(fw / 1050.0, fh / 600.0);
            phys = Math.max(1.0, Math.floor(k * 2) / 2);
        } else phys = 2.0 * pct / 100.0;
        return (float) (phys / client.getWindow().getScaleFactor());
    }

    /** Физических пикселей на единицу интерфейса. */
    public double physPerUnit() { return gui.scale * client.getWindow().getScaleFactor(); }

    @Override
    public final void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        gui.scale = uiScale();
        W = (int) (width / gui.scale);
        H = (int) (height / gui.scale);
        double mxs = client.mouse.getX() * client.getWindow().getScaledWidth() / (double) client.getWindow().getWidth();
        double mys = client.mouse.getY() * client.getWindow().getScaledHeight() / (double) client.getWindow().getHeight();
        renderBackground3D(ctx, delta);
        ctx.getMatrices().push();
        ctx.getMatrices().scale(gui.scale, gui.scale, 1);
        gui.beginFrame(ctx, (int) (mxs / gui.scale), (int) (mys / gui.scale));
        gui.mods = (hasControlDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_CONTROL : 0) | (hasShiftDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT : 0) | (hasAltDown() ? org.lwjgl.glfw.GLFW.GLFW_MOD_ALT : 0);
        gui.blocked = !dialogs.isEmpty();
        gui.overlayLayer = false;
        try {
            draw(delta);
            if (!dialogs.isEmpty()) {
                ctx.getMatrices().push();
                ctx.getMatrices().translate(0, 0, 300);
                gui.rect(0, 0, W, H, 0x90000000);
                gui.blocked = false;
                gui.overlayLayer = true;
                Dialog d = dialogs.peek();
                int dx = (W - d.w) / 2, dy = (H - d.h) / 2;
                gui.rect(dx - 1, dy - 1, d.w + 2, d.h + 2, 0xFF000000);
                gui.rect(dx, dy, d.w, d.h, 0xFF1E1F22);
                gui.rect(dx, dy, d.w, 18, 0xFF26272B);
                gui.text(d.title, dx + 8, dy + 5, Gui.TEXT);
                boolean close = d.render(gui, dx, dy + 18);
                if (gui.keyPressed(GLFW.GLFW_KEY_ENTER) && gui.focus == null) close |= d.onEnter();
                if (close) dialogs.remove(d);
                ctx.getMatrices().pop();
            }
            drawToasts();
            gui.drawTooltip(WbConfig.get().tooltips ? WbConfig.get().tooltipDelayMs : 999999);
        } finally {
            ctx.getMatrices().pop();
            gui.endFrame();
        }
        com.geniiy.worldbuilder.AutoTest.endFrame(ctx);
    }

    /** Отрисовка 3D до интерфейса (для редактора). */
    protected void renderBackground3D(DrawContext ctx, float delta) {
        ctx.fill(0, 0, width, height, 0xFF141517);
    }

    protected abstract void draw(float delta);

    public void toast(String s) { toast(s, Gui.TEXT, null); }
    public void toastError(String s, Throwable t) {
        String det = null;
        if (t != null) { java.io.StringWriter sw = new java.io.StringWriter(); t.printStackTrace(new java.io.PrintWriter(sw)); det = sw.toString(); }
        toast(s, Gui.ERR, det);
    }
    public void toast(String s, int color, String details) {
        synchronized (toasts) { toasts.add(new Toast(s, color, System.currentTimeMillis(), details)); }
    }

    private void drawToasts() {
        long now = System.currentTimeMillis();
        int y = H - 34;
        synchronized (toasts) {
            for (int i = toasts.size() - 1; i >= 0; i--) {
                Toast t = toasts.get(i);
                int w = Math.min(W / 2, gui.width(t.text()) + 16 + (t.details() != null ? 70 : 0));
                int x = W - w - 8;
                boolean hv = gui.mx >= x && gui.my >= y - 18 && gui.mx < x + w && gui.my < y;
                if (!hv && now - t.time() > 4000) { toasts.remove(i); continue; }
                gui.rect(x, y - 18, w, 18, 0xF0202226);
                gui.rect(x, y - 18, 2, 18, t.color());
                gui.text(gui.ellipsize(t.text(), w - 16 - (t.details() != null ? 70 : 0)), x + 8, y - 13, t.color() == Gui.ERR ? Gui.ERR : Gui.TEXT);
                if (t.details() != null) {
                    gui.overlayLayer = true; boolean b = gui.blocked; gui.blocked = false;
                    if (gui.button("toastd" + i, x + w - 68, y - 16, 64, 14, "Подробнее", null)) {
                        String det = t.details();
                        dialogs.push(new TextDialog("Подробности ошибки", det));
                    }
                    gui.blocked = b; gui.overlayLayer = false;
                }
                gui.registerOverlay(x, y - 18, w, 18);
                y -= 22;
                if (y < 40) break;
            }
        }
    }

    /** Диалог с текстом (подробности ошибки) и кнопкой «Скопировать». */
    public static class TextDialog extends Dialog {
        final String text; double off;
        public TextDialog(String title, String text) { super(title); this.text = text; w = 460; h = 260; }
        public boolean render(Gui g, int x, int y) {
            List<String> lines = new ArrayList<>();
            for (String l : text.split("\n")) {
                while (g.width(l) > w - 24 && l.length() > 10) { String part = g.tr.trimToWidth(l, w - 24); lines.add(part); l = l.substring(part.length()); }
                lines.add(l);
            }
            int ah = h - 18 - 34;
            off = g.scrollArea("txtdlg", x + 6, y + 6, w - 12, ah, lines.size() * 10, off);
            g.scissor(x + 6, y + 6, w - 12, ah);
            for (int i = 0; i < lines.size(); i++) g.text(lines.get(i), x + 10, y + 8 + i * 10 - (int) off, Gui.TEXT2);
            g.noScissor();
            if (g.button("txtcopy", x + w - 190, y + h - 18 - 24, 90, 18, "Скопировать", null)) g.mc.keyboard.setClipboard(text);
            return g.primary("txtok", x + w - 94, y + h - 18 - 24, 86, 18, "ОК", null, true);
        }
        public boolean onEnter() { return true; }
    }

    /** Вопрос с кнопками. callback получает индекс кнопки (−1 — отмена). */
    public static class ChoiceDialog extends Dialog {
        final String message; final String[] buttons; final java.util.function.IntConsumer cb;
        public ChoiceDialog(String title, String message, String[] buttons, java.util.function.IntConsumer cb) {
            super(title); this.message = message; this.buttons = buttons; this.cb = cb;
            w = 380;
        }
        public boolean render(Gui g, int x, int y) {
            List<String> lines = wrap(g, message, w - 20);
            h = 18 + 14 + lines.size() * 11 + 36;
            for (int i = 0; i < lines.size(); i++) g.text(lines.get(i), x + 10, y + 10 + i * 11, Gui.TEXT);
            int bx = x + w - 8;
            for (int i = buttons.length - 1; i >= 0; i--) {
                int bw = Math.max(70, g.width(buttons[i]) + 16);
                bx -= bw;
                boolean click = i == 0 ? g.primary("ch" + i, bx, y + h - 18 - 26, bw, 18, buttons[i], null, true)
                        : g.button("ch" + i, bx, y + h - 18 - 26, bw, 18, buttons[i], null);
                if (click) { cb.accept(i); return true; }
                bx -= 6;
            }
            return false;
        }
        public boolean onEnter() { cb.accept(0); return true; }
        public boolean onEsc() { cb.accept(-1); return true; }
    }

    public static List<String> wrap(Gui g, String text, int w) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (line.length() > 0 && g.width(line + " " + word) > w) { out.add(line.toString()); line.setLength(0); }
                if (line.length() > 0) line.append(' ');
                line.append(word);
            }
            out.add(line.toString());
        }
        return out;
    }

    public void message(String title, String text) {
        dialogs.push(new ChoiceDialog(title, text, new String[]{"ОК"}, i -> {}));
    }

    // ---------- ввод ----------
    @Override public boolean mouseClicked(double mx, double my, int button) {
        if (button < 3) { gui.down[button] = true; gui.pressed[button] = true; }
        onMouseDown(button);
        return true;
    }
    @Override public boolean mouseReleased(double mx, double my, int button) {
        if (button < 3) { gui.down[button] = false; gui.released[button] = true; }
        onMouseUp(button);
        return true;
    }
    @Override public boolean mouseScrolled(double mx, double my, double amount) { gui.scroll += amount; return true; }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        gui.mods = mods;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (gui.focus != null) { gui.focus = null; return true; }
            if (!dialogs.isEmpty()) { Dialog d = dialogs.peek(); if (d.onEsc()) dialogs.remove(d); return true; }
            onEscape();
            return true;
        }
        gui.keys.add(key);
        if (gui.focus == null && dialogs.isEmpty()) onKey(key, mods);
        return true;
    }
    @Override public boolean keyReleased(int key, int scan, int mods) { gui.mods = mods; return true; }
    @Override public boolean charTyped(char chr, int mods) { gui.chars.add(chr); return true; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    protected void onEscape() { close(); }
    protected void onKey(int key, int mods) {}
    protected void onMouseDown(int button) {}
    protected void onMouseUp(int button) {}

    /** Выполнить на главном потоке. */
    protected void runMain(Runnable r) { client.execute(r); }
}
