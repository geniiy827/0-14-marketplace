package com.geniiy.bridge14;

import com.geniiy.bridge14.pe.LanScanner;
import com.geniiy.bridge14.proxy.DefaultSkin;
import com.geniiy.bridge14.proxy.Proxy;
import com.geniiy.bridge14.proxy.Session;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Главное меню Bridge0_14: профиль с 3D-куклой, серверы и локальные миры 0.14. */
public class BridgeScreen extends Screen {
    private final Screen parent;
    private final Config cfg;
    private TextFieldWidget nickField;
    private ButtonWidget playBtn, editBtn, delBtn, modelBtn;
    private static volatile List<LanScanner.Found> lan = new ArrayList<>();
    private static volatile long lastScan;
    private static volatile boolean scanning;
    private static final Map<String, LanScanner.Found> PINGS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PINGED_AT = new ConcurrentHashMap<>();
    private String skinInfo = "";
    private String message = "";
    private int selected = -1, scroll;
    private long lastClick; private int lastClickRow = -1;
    private float dollYaw = 20, dollSpin = 0.4f; private boolean dragging; private double dragX;
    private int ticks;

    // разметка
    private int lx, lw, rx, rw, top, listTop, listBottom, dollTop, dollH;
    private static final int ROW_H = 36;

    private static final class Row { String title, motd, addr, ver, players; long ping = -2; boolean lan; Config.Server saved; String host; int port; }

    public BridgeScreen(Screen parent) {
        super(Text.literal("Bridge0_14"));
        this.parent = parent;
        this.cfg = Config.load();
        if (!Bridge14.status.isEmpty()) { message = Bridge14.status; Bridge14.status = ""; }
    }

    @Override
    protected void init() {
        lx = 8; lw = Math.max(128, Math.min(180, width * 3 / 10));
        rx = lx + lw + 8; rw = width - rx - 8;
        top = 30;
        listTop = top + 16; listBottom = height - 8 - 48;
        int panelBottom = height - 8;
        int controls = 20 + 4 + 20 + 4 + 20 + 4 + 20 + 12;
        dollTop = top + 14;
        dollH = Math.max(40, Math.min(150, panelBottom - controls - dollTop - 8));
        int y = dollTop + dollH + 8;
        int fx = lx + 6, fw = lw - 12;

        nickField = new TextFieldWidget(textRenderer, fx, y, fw, 20, Text.literal("Ник"));
        nickField.setMaxLength(16);
        nickField.setPlaceholder(Text.literal("Ник 0.14"));
        nickField.setText(cfg.nick == null || cfg.nick.isEmpty() ? client.getSession().getUsername() : cfg.nick);
        nickField.setTextPredicate(s -> s.matches("[A-Za-z0-9_]{0,16}"));
        nickField.setChangedListener(s -> { cfg.nick = s; cfg.save(); });
        addDrawableChild(nickField);
        y += 24;
        int half = (fw - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Скин…"), b -> pickSkin()).dimensions(fx, y, half, 20)
                .tooltip(Tooltip.of(Text.literal("PNG 64×64 или 64×32 — так тебя увидят игроки 0.14"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Папка"), b -> Util.getOperatingSystem().open(Config.dir().toFile())).dimensions(fx + half + 4, y, fw - half - 4, 20)
                .tooltip(Tooltip.of(Text.literal("Можно просто положить сюда skin.png"))).build());
        y += 24;
        modelBtn = addDrawableChild(ButtonWidget.builder(modelText(), b -> { cfg.slim = !cfg.slim; cfg.save(); b.setMessage(modelText()); updateSkin(); })
                .dimensions(fx, y, half, 20).tooltip(Tooltip.of(Text.literal("Стив — обычные руки, Алекс — тонкие"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Сброс"), b -> { cfg.skinPath = ""; cfg.save(); updateSkin(); })
                .dimensions(fx + half + 4, y, fw - half - 4, 20).build());

        // ---- кнопки списка ----
        int by = height - 8 - 44;
        int bw = (rw - 12) / 4;
        playBtn = addDrawableChild(ButtonWidget.builder(Text.literal("§lИграть"), b -> playSelected()).dimensions(rx, by, bw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Добавить"), b -> client.setScreen(new ServerEditScreen(this, cfg, null))).dimensions(rx + (bw + 4), by, bw, 20).build());
        editBtn = addDrawableChild(ButtonWidget.builder(Text.literal("Изменить"), b -> { Row r = sel(); if (r != null && r.saved != null) client.setScreen(new ServerEditScreen(this, cfg, r.saved)); })
                .dimensions(rx + (bw + 4) * 2, by, bw, 20).build());
        delBtn = addDrawableChild(ButtonWidget.builder(Text.literal("Удалить"), b -> { Row r = sel(); if (r != null && r.saved != null) { cfg.servers.remove(r.saved); cfg.save(); selected = -1; } })
                .dimensions(rx + (bw + 4) * 3, by, rw - (bw + 4) * 3, 20).build());
        int hw = (rw - 4) / 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("Обновить"), b -> { lastScan = 0; PINGED_AT.clear(); scan(); }).dimensions(rx, by + 24, hw, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close()).dimensions(rx + hw + 4, by + 24, rw - hw - 4, 20).build());
        updateSkin();
        scan();
        updateButtons();
    }

    private Text modelText() { return Text.literal(cfg.slim ? "Алекс" : "Стив"); }

    private void updateButtons() {
        Row r = sel();
        if (playBtn != null) playBtn.active = r != null;
        if (editBtn != null) editBtn.active = r != null && r.saved != null;
        if (delBtn != null) delBtn.active = r != null && r.saved != null;
    }

    private Row sel() { List<Row> rs = rows(); return selected >= 0 && selected < rs.size() ? rs.get(selected) : null; }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (LanScanner.Found f : lan) {
            Row r = new Row();
            fill(r, f.info);
            r.title = r.motd == null || r.motd.isEmpty() ? "Мир" : r.motd;
            r.motd = "Локальная сеть • " + f.host + (f.port != 19132 ? ":" + f.port : "");
            r.ping = f.ping; r.lan = true;
            r.host = f.host; r.port = f.port; r.addr = f.host + ":" + f.port;
            out.add(r);
        }
        for (Config.Server s : cfg.servers) {
            Row r = new Row();
            r.title = s.name; r.saved = s; r.host = s.host; r.port = s.port;
            r.addr = s.host + (s.port != 19132 ? ":" + s.port : "");
            LanScanner.Found f = PINGS.get(s.host + ":" + s.port);
            if (f != null) { fill(r, f.info); r.ping = f.ping; }
            else r.ping = PINGED_AT.containsKey(s.host + ":" + s.port) ? -1 : -2;
            if (r.motd == null || r.motd.isEmpty()) r.motd = r.addr;
            out.add(r);
        }
        return out;
    }

    private static void fill(Row r, String info) {
        String[] p = info.split(";");
        r.motd = p.length > 1 ? p[1] : "";
        r.ver = p.length > 3 ? p[3] : "";
        r.players = p.length > 5 ? p[4] + "/" + p[5] : "";
    }

    private void scan() {
        if (!scanning && System.currentTimeMillis() - lastScan > 5000) {
            scanning = true;
            Thread t = new Thread(() -> {
                try { lan = LanScanner.scan(null, 2500); }
                finally { lastScan = System.currentTimeMillis(); scanning = false; }
            }, "bridge14-lan");
            t.setDaemon(true);
            t.start();
        }
        for (Config.Server s : cfg.servers) {
            String k = s.host + ":" + s.port;
            Long at = PINGED_AT.get(k);
            if (at != null && System.currentTimeMillis() - at < 15000) continue;
            PINGED_AT.put(k, System.currentTimeMillis());
            Thread t = new Thread(() -> {
                LanScanner.Found f = LanScanner.ping(s.host, s.port, 2500);
                if (f != null) PINGS.put(k, f); else PINGS.remove(k);
            }, "bridge14-ping");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        nickField.tick();
        if (ticks % 20 == 0) scan();
        if (!dragging) dollYaw += dollSpin;
        updateButtons();
    }

    // ---------------- скин ----------------
    private void updateSkin() {
        File f = cfg.skinFile();
        byte[] rgba = null;
        if (f == null) skinInfo = "§7Скин: стандартный";
        else {
            rgba = Proxy.loadSkin(f);
            skinInfo = rgba == null ? "§cНужен PNG 64×64 или 64×32" : "§7" + shorten(f.getName(), 22);
        }
        SkinStore.put(SkinStore.LOCAL, rgba != null ? rgba : DefaultSkin.make(), cfg.slim);
        if (modelBtn != null) modelBtn.setMessage(modelText());
    }

    private static String shorten(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "…" : s; }

    private void pickSkin() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer filter = st.mallocPointer(1);
            filter.put(st.UTF8("*.png"));
            filter.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Скин для 0.14 (PNG 64×64)", null, filter, "PNG", false);
            if (path != null) { cfg.skinPath = path; cfg.save(); updateSkin(); }
        } catch (Throwable e) {
            message = "Не открылось окно выбора файла — положи skin.png в папку (кнопка «Папка»)";
        }
    }

    // ---------------- игра ----------------
    private void playSelected() { Row r = sel(); if (r != null) play(r); }

    private void play(Row r) {
        String nick = nickField.getText().trim();
        if (!nick.matches("[A-Za-z0-9_]{3,16}")) { message = "§cНик: 3–16 латинских букв, цифр или _"; return; }
        cfg.nick = nick; cfg.save();
        Session.Config sc = new Session.Config();
        sc.nick = nick; sc.host = r.host; sc.port = r.port; sc.chunkRadius = cfg.chunkRadius; sc.slim = cfg.slim;
        sc.tags = TagsBuilder::build;
        File sf = cfg.skinFile();
        if (sf != null) sc.skin = Proxy.loadSkin(sf);
        try {
            int port = Bridge14.PROXY.start(sc, new Session.Listener() {
                public void status(String s) { Bridge14.LOG.info(s); }
                public void log(String s) { Bridge14.LOG.info(s); }
                public void ended(String reason) { Bridge14.sessionEnded(reason); }
                public void popup(String text, boolean tip) { Hud.set(text, tip); }
                public void skin(java.util.UUID u, byte[] rgba, boolean slim) { SkinStore.put(u, rgba, slim); }
            });
            Bridge14.active = true;
            ConnectScreen.connect(this, client, new ServerAddress("127.0.0.1", port), new ServerInfo("0.14: " + r.title, "127.0.0.1:" + port, false), false);
        } catch (Exception e) {
            Bridge14.active = false;
            message = "§cНе удалось запустить мост: " + e.getMessage();
        }
    }

    // ---------------- мышь ----------------
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;
        if (mx >= lx && mx < lx + lw && my >= dollTop && my < dollTop + dollH) { dragging = true; dragX = mx; return true; }
        if (mx >= rx && mx < rx + rw && my >= listTop && my < listBottom) {
            int i = scroll + (int) ((my - listTop) / ROW_H);
            List<Row> rs = rows();
            if (i >= 0 && i < rs.size()) {
                long now = System.currentTimeMillis();
                if (i == lastClickRow && now - lastClick < 300) { selected = i; play(rs.get(i)); return true; }
                selected = i; lastClick = now; lastClickRow = i;
                updateButtons();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) { dollYaw += (float) (mx - dragX) * 2f; dragX = mx; return true; }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) { dragging = false; dollSpin = 0; }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        int max = Math.max(0, rows().size() - visible);
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == 257 || key == 335) && !nickField.isFocused() && sel() != null) { playSelected(); return true; } // Enter
        return super.keyPressed(key, scan, mods);
    }

    // ---------------- отрисовка ----------------
    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        ctx.fillGradient(0, 0, width, height, 0x90101820, 0xC0080810);
        // заголовок
        ctx.getMatrices().push();
        ctx.getMatrices().translate(lx, 7, 0);
        ctx.getMatrices().scale(2f, 2f, 1f);
        ctx.drawTextWithShadow(textRenderer, "§bBridge§f0_14", 0, 0, 0xFFFFFF);
        ctx.getMatrices().pop();
        ctx.drawTextWithShadow(textRenderer, "§7клиент для Minecraft PE 0.14", lx + textRenderer.getWidth("Bridge0_14") * 2 + 10, 14, 0xAAAAAA);

        // левая панель: профиль
        panel(ctx, lx, top, lw, height - 8 - top);
        Identifier tex = SkinStore.texture(SkinStore.LOCAL);
        int cx = lx + lw / 2;
        float lookX = (float) Math.max(-1, Math.min(1, (mouseX - cx) / 120.0));
        float lookY = (float) Math.max(-1, Math.min(1, (mouseY - (dollTop + dollH / 4)) / 120.0));
        ctx.fill(lx + 10, dollTop + dollH - 3, lx + lw - 10, dollTop + dollH - 1, 0x40FFFFFF);
        PlayerDoll.draw(ctx, cx, dollTop + dollH - 4, Math.max(16, (int) (dollH / 2.25f)), dollYaw, lookX, lookY, tex, cfg.slim, ticks + delta);
        ctx.drawTextWithShadow(textRenderer, "§eПрофиль", lx + 6, top + 4, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, fit(skinInfo, lw - 8), cx, height - 8 - 11, 0xFFFFFF);

        // правая панель: список
        panel(ctx, rx, top, rw, listBottom - top + 4);
        ctx.drawTextWithShadow(textRenderer, "§eСерверы и миры", rx + 6, top + 4, 0xFFFFFF);
        if (scanning) ctx.drawTextWithShadow(textRenderer, "§7поиск в сети…", rx + rw - 6 - textRenderer.getWidth("поиск в сети…"), top + 4, 0xFFFFFF);
        List<Row> rs = rows();
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        if (scroll > Math.max(0, rs.size() - visible)) scroll = Math.max(0, rs.size() - visible);
        if (rs.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "§7Пусто. Открой мир на телефоне в той же Wi-Fi сети", rx + rw / 2, listTop + 20, 0xFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, "§7или нажми «Добавить», чтобы добавить сервер.", rx + rw / 2, listTop + 32, 0xFFFFFF);
        }
        ctx.enableScissor(rx, listTop, rx + rw, listBottom);
        for (int i = 0; i < visible + 1 && scroll + i < rs.size(); i++) {
            int idx = scroll + i;
            drawRow(ctx, rs.get(idx), rx + 3, listTop + i * ROW_H, rw - 6, idx == selected,
                    mouseX >= rx && mouseX < rx + rw && mouseY >= listTop + i * ROW_H && mouseY < listTop + (i + 1) * ROW_H && mouseY < listBottom);
        }
        ctx.disableScissor();
        if (rs.size() > visible) {
            int barH = Math.max(10, (listBottom - listTop) * visible / rs.size());
            int barY = listTop + (listBottom - listTop - barH) * scroll / Math.max(1, rs.size() - visible);
            ctx.fill(rx + rw - 3, barY, rx + rw - 1, barY + barH, 0x80FFFFFF);
        }
        if (!message.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, message, rx + rw / 2, listBottom - 10, 0xFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private static void panel(DrawContext ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x + w, y + h, 0x70000000);
        ctx.drawBorder(x, y, w, h, 0x30FFFFFF);
    }

    private void drawRow(DrawContext ctx, Row r, int x, int y, int w, boolean sel, boolean hover) {
        if (sel) { ctx.fill(x, y + 1, x + w, y + ROW_H - 1, 0x5055AAFF); ctx.drawBorder(x, y + 1, w, ROW_H - 2, 0xA0AACCFF); }
        else if (hover) ctx.fill(x, y + 1, x + w, y + ROW_H - 1, 0x25FFFFFF);
        // значок
        int ic = r.lan ? 0xFF2E9E4F : 0xFF3A6FD8;
        ctx.fill(x + 4, y + 5, x + 30, y + 31, ic);
        ctx.fill(x + 5, y + 6, x + 29, y + 30, ic + 0x00101010);
        ctx.drawCenteredTextWithShadow(textRenderer, r.lan ? "LAN" : "PE", x + 17, y + 14, 0xFFFFFF);
        int tx = x + 36;
        int right = x + w - 6;
        // пинг и игроки
        String pingS; int pc;
        if (r.ping >= 0) { pingS = r.ping + " мс"; pc = r.ping < 120 ? 0x55FF55 : r.ping < 300 ? 0xFFFF55 : 0xFF5555; }
        else if (r.ping == -1) { pingS = "нет ответа"; pc = 0xFF5555; }
        else { pingS = "…"; pc = 0xAAAAAA; }
        int pw = textRenderer.getWidth(pingS);
        ctx.drawTextWithShadow(textRenderer, pingS, right - pw, y + 6, pc);
        String info = (r.players != null && !r.players.isEmpty() ? "§f" + r.players + " " : "") + (r.ver != null && !r.ver.isEmpty() ? "§7" + r.ver : "");
        int iw = textRenderer.getWidth(info);
        if (!info.isEmpty()) ctx.drawTextWithShadow(textRenderer, info, right - iw, y + 19, 0xFFFFFF);
        int maxT = right - Math.max(pw, iw) - 8 - tx;
        ctx.drawTextWithShadow(textRenderer, fit("§f§l" + strip(r.title), maxT), tx, y + 6, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, fit("§7" + r.motd, maxT), tx, y + 19, 0xFFFFFF);
    }

    private String fit(String s, int w) {
        if (w <= 10) return "";
        if (textRenderer.getWidth(s) <= w) return s;
        while (s.length() > 3 && textRenderer.getWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String strip(String s) { return s.replaceAll("§.", ""); }

    @Override
    public void close() { cfg.save(); client.setScreen(parent); }
}
