package com.geniiy.worldbuilder.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.*;

/**
 * Немедленный (immediate-mode) интерфейс в тёмной теме поверх DrawContext.
 * Ввод накапливается между кадрами и обрабатывается при отрисовке виджетов.
 */
public final class Gui {
    // ---- тема (раздел 6 ТЗ) ----
    public static final int BG = 0xF51E1F22, SEP = 0xFF2B2D31, FIELD = 0xFF141517, TEXT = 0xFFE6E6E6, TEXT2 = 0xFF9DA0A6,
            ACCENT = 0xFF3DDC97, WARN = 0xFFF2C14E, ERR = 0xFFF25F5C, BTN = 0xFF2B2D31, BTN_H = 0xFF383A40, BTN_P = 0xFF232428,
            BTN_SEL = 0xFF2F4A3E;

    public final MinecraftClient mc = MinecraftClient.getInstance();
    public final TextRenderer tr = mc.textRenderer;
    public DrawContext ctx;
    public float scale = 1f; // доп. масштаб поверх GUI Scale
    public int mx, my;
    public final boolean[] down = new boolean[3];
    public final boolean[] pressed = new boolean[3];
    public final boolean[] released = new boolean[3];
    public double scroll;
    public boolean scrollUsed;
    public final List<Integer> keys = new ArrayList<>();
    public final List<Character> chars = new ArrayList<>();
    public int mods;
    public String focus;          // id поля ввода в фокусе
    public String active;         // id виджета, который тянут мышью
    public boolean blocked;       // модальное окно поверх: нижний слой не интерактивен
    public final List<int[]> overlayRects = new ArrayList<>();
    private final List<int[]> nextOverlayRects = new ArrayList<>();
    public boolean overlayLayer;
    private String tipText, tipKey; private long tipSince; private String tipId;
    private String hoverId;
    public boolean clickConsumed;

    // ---- поле ввода ----
    private final Map<String, int[]> cursors = new HashMap<>();
    private int dragStartX; private double dragStartVal;

    public void beginFrame(DrawContext ctx, int mx, int my) {
        this.ctx = ctx; this.mx = mx; this.my = my;
        overlayRects.clear(); overlayRects.addAll(nextOverlayRects); nextOverlayRects.clear();
        tipText = null; hoverId = null; clickConsumed = false;
    }

    public void endFrame() {
        Arrays.fill(pressed, false);
        Arrays.fill(released, false);
        scroll = 0; scrollUsed = false;
        keys.clear(); chars.clear();
        if (!down[0] && !down[2]) active = null;
    }

    public void registerOverlay(int x, int y, int w, int h) { nextOverlayRects.add(new int[]{x, y, w, h}); }

    public boolean inOverlay(int x, int y) {
        for (int[] r : overlayRects) if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) return true;
        return false;
    }

    /** Мышь над прямоугольником и этот слой интерактивен. */
    public boolean hover(int x, int y, int w, int h) {
        if (blocked && !overlayLayer) return false;
        if (!overlayLayer && inOverlay(mx, my)) return false;
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }
    public boolean clicked(int x, int y, int w, int h, int btn) {
        if (clickConsumed) return false;
        boolean c = pressed[btn] && hover(x, y, w, h);
        if (c) clickConsumed = true;
        return c;
    }
    public boolean ctrl() { return (mods & GLFW.GLFW_MOD_CONTROL) != 0; }
    public boolean alt() { return (mods & GLFW.GLFW_MOD_ALT) != 0; }
    public boolean shift() { return (mods & GLFW.GLFW_MOD_SHIFT) != 0; }

    // ---- рисование ----
    public void rect(int x, int y, int w, int h, int c) { ctx.fill(x, y, x + w, y + h, c); }
    public void frame(int x, int y, int w, int h, int c) {
        ctx.fill(x, y, x + w, y + 1, c); ctx.fill(x, y + h - 1, x + w, y + h, c);
        ctx.fill(x, y, x + 1, y + h, c); ctx.fill(x + w - 1, y, x + w, y + h, c);
    }
    /** Скруглённый прямоугольник радиуса 2–3 px (срезанные углы). */
    public void rrect(int x, int y, int w, int h, int c) {
        ctx.fill(x + 1, y, x + w - 1, y + h, c);
        ctx.fill(x, y + 1, x + 1, y + h - 1, c);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }
    public void panel(int x, int y, int w, int h) { rect(x, y, w, h, BG); }
    public void text(String s, int x, int y, int c) { ctx.drawText(tr, s, x, y, c, false); }
    public void textShadow(String s, int x, int y, int c) { ctx.drawText(tr, s, x, y, c, true); }
    public void textC(String s, int cx, int y, int c) { ctx.drawText(tr, s, cx - tr.getWidth(s) / 2, y, c, false); }
    public void textR(String s, int rx, int y, int c) { ctx.drawText(tr, s, rx - tr.getWidth(s), y, c, false); }
    public int width(String s) { return tr.getWidth(s); }
    public String ellipsize(String s, int w) {
        if (tr.getWidth(s) <= w) return s;
        return tr.trimToWidth(s, Math.max(0, w - tr.getWidth("…"))) + "…";
    }
    public void hsep(int x, int y, int w) { rect(x, y, w, 1, SEP); }

    public void scissor(int x, int y, int w, int h) {
        ctx.enableScissor((int) Math.floor(x * scale), (int) Math.floor(y * scale), (int) Math.ceil((x + w) * scale), (int) Math.ceil((y + h) * scale));
    }
    public void noScissor() { ctx.disableScissor(); }

    // ---- подсказки ----
    public void tooltip(String id, String text, String key) {
        if (text == null) return;
        hoverId = id;
        if (!id.equals(tipId)) { tipId = id; tipSince = System.currentTimeMillis(); }
        tipText = text; tipKey = key;
    }
    public void drawTooltip(int delay) {
        if (hoverId == null) tipId = null;
        if (tipText == null || System.currentTimeMillis() - tipSince < delay) return;
        String[] lines = tipText.split("\n");
        int w = 0;
        for (String l : lines) w = Math.max(w, width(l));
        if (tipKey != null) w = Math.max(w, width(lines[0]) + 12 + width(tipKey));
        int h = lines.length * 11 + 6;
        int x = mx + 10, y = my + 12;
        int sw = (int) (mc.getWindow().getScaledWidth() / scale), sh = (int) (mc.getWindow().getScaledHeight() / scale);
        if (x + w + 8 > sw) x = sw - w - 8;
        if (y + h > sh) y = my - h - 4;
        ctx.getMatrices().push();
        ctx.getMatrices().translate(0, 0, 400);
        rect(x, y, w + 8, h, 0xF0101113);
        frame(x, y, w + 8, h, SEP);
        for (int i = 0; i < lines.length; i++) text(lines[i], x + 4, y + 4 + i * 11, i == 0 ? TEXT : TEXT2);
        if (tipKey != null) textR(tipKey, x + w + 4, y + 4, TEXT2);
        ctx.getMatrices().pop();
    }

    // ---- виджеты ----
    public boolean button(String id, int x, int y, int w, int h, String label, String tip) { return button(id, x, y, w, h, label, tip, false, true); }

    public boolean button(String id, int x, int y, int w, int h, String label, String tip, boolean selected, boolean enabled) {
        boolean hv = hover(x, y, w, h);
        int c = !enabled ? 0xFF25262A : selected ? BTN_SEL : hv ? (down[0] ? BTN_P : BTN_H) : BTN;
        rrect(x, y, w, h, c);
        if (selected) rect(x, y + 1, 2, h - 2, ACCENT);
        int tc = enabled ? TEXT : 0xFF6A6D72;
        textC(ellipsize(label, w - 6), x + w / 2, y + (h - 8) / 2, tc);
        if (hv && tip != null) tooltip(id, tip, null);
        return enabled && clicked(x, y, w, h, 0);
    }

    public boolean primary(String id, int x, int y, int w, int h, String label, String tip, boolean enabled) {
        boolean hv = hover(x, y, w, h);
        int c = !enabled ? 0xFF2A3A33 : hv ? (down[0] ? 0xFF2FB57B : 0xFF55E6A7) : ACCENT;
        rrect(x, y, w, h, c);
        textC(ellipsize(label, w - 6), x + w / 2, y + (h - 8) / 2, enabled ? 0xFF0E1A14 : 0xFF6A7D74);
        if (hv && tip != null) tooltip(id, tip, null);
        return enabled && clicked(x, y, w, h, 0);
    }

    public boolean check(String id, int x, int y, String label, boolean value, String tip) {
        int w = 12 + 4 + width(label);
        boolean hv = hover(x, y, w, 12);
        rrect(x, y, 12, 12, FIELD);
        frame(x, y, 12, 12, hv ? ACCENT : SEP);
        if (value) rect(x + 3, y + 3, 6, 6, ACCENT);
        text(label, x + 16, y + 2, TEXT);
        if (hv && tip != null) tooltip(id, tip, null);
        if (clicked(x, y, w, 12, 0)) return !value;
        return value;
    }

    public int segmented(String id, int x, int y, int w, int h, String[] labels, int sel, String[] tips) {
        int n = labels.length;
        int res = sel;
        for (int i = 0; i < n; i++) {
            int bx = x + w * i / n, bw = x + w * (i + 1) / n - bx;
            boolean hv = hover(bx, y, bw, h);
            rect(bx, y, bw - (i < n - 1 ? 1 : 0), h, i == sel ? BTN_SEL : hv ? BTN_H : BTN);
            if (i == sel) rect(bx, y + h - 2, bw - 1, 2, ACCENT);
            textC(ellipsize(labels[i], bw - 4), bx + bw / 2, y + (h - 8) / 2, i == sel ? TEXT : TEXT2);
            if (hv && tips != null && tips[i] != null) tooltip(id + i, tips[i], null);
            if (clicked(bx, y, bw, h, 0)) res = i;
        }
        return res;
    }

    public void label(String s, int x, int y) { text(s, x, y, TEXT2); }

    /** Однострочное поле ввода. */
    public String field(String id, int x, int y, int w, int h, String value, String placeholder, boolean password, boolean error) {
        boolean hv = hover(x, y, w, h);
        boolean focused = id.equals(focus);
        if (clicked(x, y, w, h, 0)) { focus = id; focused = true; cursors.put(id, new int[]{value.length(), 0}); }
        else if (pressed[0] && focused && !hv && !clickConsumed) { focus = null; focused = false; }
        rrect(x, y, w, h, FIELD);
        frame(x, y, w, h, error ? ERR : focused ? ACCENT : hv ? 0xFF3A3C42 : SEP);
        int[] cur = cursors.computeIfAbsent(id, k -> new int[]{value.length(), 0});
        if (cur[0] > value.length()) cur[0] = value.length();
        String v = value;
        if (focused) {
            for (char ch : chars) {
                if (ch < 32) continue;
                v = v.substring(0, cur[0]) + ch + v.substring(cur[0]);
                cur[0]++;
            }
            for (int k : keys) {
                boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0;
                switch (k) {
                    case GLFW.GLFW_KEY_BACKSPACE -> { if (cur[0] > 0) { v = v.substring(0, cur[0] - 1) + v.substring(cur[0]); cur[0]--; } }
                    case GLFW.GLFW_KEY_DELETE -> { if (cur[0] < v.length()) v = v.substring(0, cur[0]) + v.substring(cur[0] + 1); }
                    case GLFW.GLFW_KEY_LEFT -> cur[0] = Math.max(0, cur[0] - 1);
                    case GLFW.GLFW_KEY_RIGHT -> cur[0] = Math.min(v.length(), cur[0] + 1);
                    case GLFW.GLFW_KEY_HOME -> cur[0] = 0;
                    case GLFW.GLFW_KEY_END -> cur[0] = v.length();
                    case GLFW.GLFW_KEY_V -> { if (ctrl) { String cl = mc.keyboard.getClipboard(); if (cl != null) { cl = cl.replace("\n", "").replace("\r", ""); v = v.substring(0, cur[0]) + cl + v.substring(cur[0]); cur[0] += cl.length(); } } }
                    case GLFW.GLFW_KEY_C -> { if (ctrl && !password) mc.keyboard.setClipboard(v); }
                    case GLFW.GLFW_KEY_A -> { if (ctrl) { /* выделить всё = курсор в конец, Ctrl+Backspace очистит */ cur[0] = v.length(); cur[1] = 1; } }
                    case GLFW.GLFW_KEY_X -> { if (ctrl) { if (!password) mc.keyboard.setClipboard(v); v = ""; cur[0] = 0; } }
                    default -> {}
                }
                if (k == GLFW.GLFW_KEY_BACKSPACE && cur[1] == 1) { v = ""; cur[0] = 0; }
                if (k != GLFW.GLFW_KEY_A) cur[1] = 0;
            }
            if (!chars.isEmpty()) cur[1] = 0;
        }
        String shown = password ? "•".repeat(v.length()) : v;
        scissor(x + 2, y, w - 4, h);
        int tx = x + 4, ty = y + (h - 8) / 2;
        int cursorX = tx + width(shown.substring(0, Math.min(cur[0], shown.length())));
        int shift = Math.max(0, cursorX - (x + w - 6));
        if (shown.isEmpty() && !focused && placeholder != null) text(placeholder, tx, ty, 0xFF6A6D72);
        else {
            if (cur[1] == 1 && focused) rect(tx - shift, ty - 1, width(shown), 10, 0xFF2F4A3E);
            text(shown, tx - shift, ty, TEXT);
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) rect(cursorX - shift, ty - 1, 1, 10, ACCENT);
        noScissor();
        return v;
    }

    /** Числовое поле: колесо ±1 (Ctrl ±10), перетаскивание по подписи, выражения «64+16». */
    public double number(String id, int x, int y, int w, int h, String label, double value, double min, double max, double step, boolean integer) {
        int lw = label == null || label.isEmpty() ? 0 : width(label) + 6;
        if (lw > 0) {
            boolean lh = hover(x, y, lw, h);
            text(label, x, y + (h - 8) / 2, lh || id.equals(active) ? ACCENT : TEXT2);
            if (lh) tooltip(id + "#l", "Тяни влево/вправо, чтобы менять значение", null);
            if (lh && pressed[0] && !clickConsumed) { active = id; dragStartX = mx; dragStartVal = value; clickConsumed = true; }
            if (id.equals(active) && down[0]) {
                double nv = dragStartVal + Math.round((mx - dragStartX) / 3.0) * step * (ctrl() ? 10 : 1);
                value = clamp(nv, min, max);
            }
        }
        int fx = x + lw, fw = w - lw;
        String key = id + "#txt";
        String shown = integer ? Long.toString(Math.round(value)) : trimNum(value);
        if (!key.equals(focus)) textCache.put(key, shown);
        boolean hv = hover(fx, y, fw, h);
        if (hv && scroll != 0 && !scrollUsed && !key.equals(focus)) {
            value = clamp(value + Math.signum(scroll) * step * (ctrl() ? 10 : 1), min, max);
            scrollUsed = true;
            textCache.put(key, integer ? Long.toString(Math.round(value)) : trimNum(value));
        }
        String before = textCache.getOrDefault(key, shown);
        boolean wasFocused = key.equals(focus);
        String nv = field(key, fx, y, fw, h, before, null, false, false);
        textCache.put(key, nv);
        boolean enter = wasFocused && keys.contains(GLFW.GLFW_KEY_ENTER);
        if ((wasFocused && !key.equals(focus)) || enter) {
            Double ev = Expr.eval(nv);
            if (ev != null) value = clamp(integer ? Math.round(ev) : ev, min, max);
            if (enter) focus = null;
        }
        return value;
    }
    private final Map<String, String> textCache = new HashMap<>();
    private static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    public static String trimNum(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Ползунок. */
    public double slider(String id, int x, int y, int w, int h, double value, double min, double max, String fmt) {
        boolean hv = hover(x, y, w, h);
        if (hv && pressed[0] && !clickConsumed) { active = id; clickConsumed = true; }
        if (id.equals(active) && down[0]) value = clamp(min + (max - min) * (mx - x) / (double) w, min, max);
        if (hv && scroll != 0 && !scrollUsed) { value = clamp(value + Math.signum(scroll) * (max - min) / 50, min, max); scrollUsed = true; }
        rrect(x, y + h / 2 - 2, w, 4, FIELD);
        int kx = x + (int) ((value - min) / (max - min) * (w - 6));
        rect(x, y + h / 2 - 2, kx - x + 3, 4, 0xFF2F8F67);
        rrect(kx, y, 6, h, hv || id.equals(active) ? 0xFF55E6A7 : ACCENT);
        if (fmt != null) text(String.format(Locale.ROOT, fmt, value), x + w + 4, y + (h - 8) / 2, TEXT2);
        return value;
    }

    /** Простейший вертикальный список с прокруткой. Возвращает новое смещение. */
    public double scrollArea(String id, int x, int y, int w, int h, int contentH, double off) {
        double maxOff = Math.max(0, contentH - h);
        if (hover(x, y, w, h) && scroll != 0 && !scrollUsed) { off -= scroll * 24; scrollUsed = true; }
        off = clamp(off, 0, maxOff);
        if (maxOff > 0) {
            int bh = Math.max(16, h * h / contentH);
            int by = y + (int) ((h - bh) * (off / maxOff));
            rect(x + w - 3, y, 3, h, 0x40000000);
            boolean hv = hover(x + w - 6, y, 6, h);
            rect(x + w - 3, by, 3, bh, hv || (id + "#bar").equals(active) ? ACCENT : 0xFF4A4D55);
            if (hv && pressed[0] && !clickConsumed) { active = id + "#bar"; clickConsumed = true; }
            if ((id + "#bar").equals(active) && down[0]) off = clamp((my - y - bh / 2.0) / (h - bh) * maxOff, 0, maxOff);
        }
        return off;
    }

    public boolean keyPressed(int key) { return keys.contains(key); }
    public boolean anyFieldFocused() { return focus != null; }

    /** Простой вычислитель арифметических выражений для числовых полей. */
    public static final class Expr {
        private final String s; private int p;
        private Expr(String s) { this.s = s.replace(" ", "").replace(',', '.'); }
        public static Double eval(String s) {
            try { Expr e = new Expr(s); double v = e.sum(); return e.p == e.s.length() ? v : null; } catch (Exception ex) { return null; }
        }
        private double sum() { double v = prod(); while (p < s.length()) { char c = s.charAt(p); if (c == '+') { p++; v += prod(); } else if (c == '-') { p++; v -= prod(); } else break; } return v; }
        private double prod() { double v = unary(); while (p < s.length()) { char c = s.charAt(p); if (c == '*') { p++; v *= unary(); } else if (c == '/') { p++; v /= unary(); } else break; } return v; }
        private double unary() { if (p < s.length() && s.charAt(p) == '-') { p++; return -unary(); } return atom(); }
        private double atom() {
            if (s.charAt(p) == '(') { p++; double v = sum(); p++; return v; }
            int st = p; while (p < s.length() && (Character.isDigit(s.charAt(p)) || s.charAt(p) == '.')) p++;
            return Double.parseDouble(s.substring(st, p));
        }
    }

    public static Text t(String s) { return Text.literal(s); }
}
