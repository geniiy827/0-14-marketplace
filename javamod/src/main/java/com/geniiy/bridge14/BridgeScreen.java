package com.geniiy.bridge14;

import com.geniiy.bridge14.pe.LanScanner;
import com.geniiy.bridge14.proxy.DefaultSkin;
import com.geniiy.bridge14.proxy.Proxy;
import com.geniiy.bridge14.proxy.Session;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
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

/** Главное меню Bridge0_14 в стиле Minecraft PE 0.14: профиль с 3D-куклой, серверы и миры по Wi-Fi. */
public class BridgeScreen extends Screen {
    private final Screen parent;
    private final Config cfg;
    private TextFieldWidget nickField;
    private PeStyle.Button modelBtn;
    private static volatile List<LanScanner.Found> lan = new ArrayList<>();
    private static volatile long lastScan;
    private static volatile boolean scanning;
    private static final Map<String, LanScanner.Found> PINGS = new ConcurrentHashMap<>();
    private static final Map<String, Long> PINGED_AT = new ConcurrentHashMap<>();
    private String skinInfo = "";
    private String message = "";
    private int scroll;
    private float dollYaw = 20, dollSpin = 0.4f; private boolean dragging; private double dragX;
    private int ticks;

    private int lx, lw, rx, rw, top, listTop, listBottom, dollTop, dollH;
    private static final int ROW_H = 36, SIDE = 26;

    private static final class Row { String title, motd, ver, players; long ping = -2; boolean lan; Config.Server saved; String host; int port; }

    public BridgeScreen(Screen parent) {
        super(Text.literal("Bridge0_14"));
        this.parent = parent;
        this.cfg = Config.load();
        if (!Bridge14.status.isEmpty()) { message = Bridge14.status; Bridge14.status = ""; }
    }

    @Override
    protected void init() {
        top = 32;
        lx = 8; lw = Math.max(124, Math.min(170, width * 3 / 10));
        rx = lx + lw + 8; rw = width - rx - 8;
        listTop = top; listBottom = height - 18;
        addDrawableChild(new PeStyle.Button(4, 3, 56, 20, Text.literal("Назад"), b -> close()));
        addDrawableChild(new PeStyle.Button(width - 4 - 96, 3, 96, 20, Text.literal("Новый сервер"), b -> client.setScreen(new ServerEditScreen(this, cfg, null))));

        int controls = 20 + 4 + 20 + 4 + 20 + 14;
        dollTop = top + 14;
        dollH = Math.max(40, Math.min(150, height - 8 - controls - dollTop - 8));
        int y = dollTop + dollH + 6;
        int fx = lx + 6, fw = lw - 12;
        nickField = new TextFieldWidget(textRenderer, fx + 1, y + 1, fw - 2, 18, Text.literal("Имя"));
        nickField.setMaxLength(16);
        nickField.setPlaceholder(Text.literal("Имя в 0.14"));
        nickField.setText(cfg.nick == null || cfg.nick.isEmpty() ? client.getSession().getUsername() : cfg.nick);
        nickField.setTextPredicate(s -> s.matches("[A-Za-z0-9_]{0,16}"));
        nickField.setChangedListener(s -> { cfg.nick = s; cfg.save(); });
        addDrawableChild(nickField);
        y += 24;
        int half = (fw - 4) / 2;
        PeStyle.Button skin = new PeStyle.Button(fx, y, half, 20, Text.literal("Скин…"), b -> pickSkin());
        skin.setTooltip(Tooltip.of(Text.literal("PNG 64×64 или 64×32 — так тебя увидят игроки 0.14")));
        addDrawableChild(skin);
        PeStyle.Button folder = new PeStyle.Button(fx + half + 4, y, fw - half - 4, 20, Text.literal("Папка"), b -> Util.getOperatingSystem().open(Config.dir().toFile()));
        folder.setTooltip(Tooltip.of(Text.literal("Можно просто положить сюда skin.png")));
        addDrawableChild(folder);
        y += 24;
        modelBtn = new PeStyle.Button(fx, y, half, 20, modelText(), b -> { cfg.slim = !cfg.slim; cfg.save(); updateSkin(); });
        modelBtn.setTooltip(Tooltip.of(Text.literal("Стив — обычные руки, Алекс — тонкие")));
        addDrawableChild(modelBtn);
        addDrawableChild(new PeStyle.Button(fx + half + 4, y, fw - half - 4, 20, Text.literal("Сброс"), b -> { cfg.skinPath = ""; cfg.save(); updateSkin(); }));
        updateSkin();
        rebuildRowButtons();
        scan();
    }

    private Text modelText() { return Text.literal(cfg.slim ? "Алекс" : "Стив"); }

    private final List<PeStyle.Button> rowButtons = new ArrayList<>();

    /** Кнопки ✎ и ✕ у каждой видимой строки. */
    private void rebuildRowButtons() {
        for (PeStyle.Button b : rowButtons) remove(b);
        rowButtons.clear();
        List<Row> rs = rows();
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        for (int i = 0; i < visible && scroll + i < rs.size(); i++) {
            Row r = rs.get(scroll + i);
            if (r.saved == null) continue;
            int y = listTop + i * ROW_H;
            final Config.Server s = r.saved;
            PeStyle.Button del = new PeStyle.Button(rx + rw - SIDE, y + 2, SIDE, ROW_H - 4, Text.literal("✖"), b -> { cfg.servers.remove(s); cfg.save(); rebuildRowButtons(); });
            del.red();
            PeStyle.Button edit = new PeStyle.Button(rx + rw - SIDE * 2 - 2, y + 2, SIDE, ROW_H - 4, Text.literal("✎"), b -> client.setScreen(new ServerEditScreen(this, cfg, s)));
            rowButtons.add(addDrawableChild(del));
            rowButtons.add(addDrawableChild(edit));
        }
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (LanScanner.Found f : lan) {
            Row r = new Row();
            fill(r, f.info);
            r.title = r.motd == null || r.motd.isEmpty() ? "Мир" : r.motd;
            r.motd = "Локальная сеть: " + f.host + (f.port != 19132 ? ":" + f.port : "");
            r.ping = f.ping; r.lan = true;
            r.host = f.host; r.port = f.port;
            out.add(r);
        }
        for (Config.Server s : cfg.servers) {
            Row r = new Row();
            r.title = s.name; r.saved = s; r.host = s.host; r.port = s.port;
            String addr = s.name + ": " + s.host + (s.port != 19132 ? ":" + s.port : "");
            LanScanner.Found f = PINGS.get(s.host + ":" + s.port);
            if (f != null) { fill(r, f.info); r.ping = f.ping; if (r.motd != null && !r.motd.isEmpty()) r.title = r.motd; }
            else r.ping = PINGED_AT.containsKey(s.host + ":" + s.port) ? -1 : -2;
            r.motd = addr;
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

    private int rowCount = -1;

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
        int rc = rows().size();
        if (rc != rowCount) { rowCount = rc; rebuildRowButtons(); }
    }

    // ---------------- скин ----------------
    private void updateSkin() {
        File f = cfg.skinFile();
        byte[] rgba = null;
        if (f == null) skinInfo = "§7Скин: стандартный";
        else {
            rgba = Proxy.loadSkin(f);
            skinInfo = rgba == null ? "§cНужен PNG 64×64 или 64×32" : "§7" + f.getName();
        }
        SkinStore.put(SkinStore.LOCAL, rgba != null ? rgba : DefaultSkin.make(), cfg.slim);
        if (modelBtn != null) modelBtn.setMessage(modelText());
    }

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
    private void play(Row r) {
        String nick = nickField.getText().trim();
        if (!nick.matches("[A-Za-z0-9_]{3,16}")) { message = "§cИмя: 3–16 латинских букв, цифр или _"; return; }
        cfg.nick = nick; cfg.save();
        Session.Config sc = new Session.Config();
        sc.nick = nick; sc.host = r.host; sc.port = r.port; sc.chunkRadius = cfg.chunkRadius; sc.slim = cfg.slim;
        sc.tags = TagsBuilder::build;
        sc.skin32 = r.lan;
        File sf = cfg.skinFile();
        if (sf != null) sc.skin = Proxy.loadSkin(sf);
        try {
            int port = Bridge14.PROXY.start(sc, Bridge14.listener());
            Bridge14.active = true;
            ConnectScreen.connect(this, client, new ServerAddress("127.0.0.1", port), new ServerInfo("0.14: " + strip(r.title), "127.0.0.1:" + port, false), false);
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
                client.getSoundManager().play(net.minecraft.client.sound.PositionedSoundInstance.master(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1f));
                play(rs.get(i));
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
        int ns = Math.max(0, Math.min(max, scroll - (int) Math.signum(amount)));
        if (ns != scroll) { scroll = ns; rebuildRowButtons(); }
        return true;
    }

    // ---------------- отрисовка ----------------
    @Override
    public void renderBackground(DrawContext ctx) { PeStyle.background(ctx, width, height, 0); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        PeStyle.background(ctx, width, height, delta);
        PeStyle.bar(ctx, textRenderer, width, "Bridge0_14");

        // профиль
        ctx.fill(lx, top, lx + lw, height - 8, 0x50000000);
        ctx.drawBorder(lx, top, lw, height - 8 - top, 0xFF1E1B19);
        ctx.drawTextWithShadow(textRenderer, "Профиль", lx + 6, top + 4, 0xFFFFFF);
        Identifier tex = SkinStore.texture(SkinStore.LOCAL);
        int cx = lx + lw / 2;
        float lookX = (float) Math.max(-1, Math.min(1, (mouseX - cx) / 120.0));
        float lookY = (float) Math.max(-1, Math.min(1, (mouseY - (dollTop + dollH / 4)) / 120.0));
        PlayerDoll.draw(ctx, cx, dollTop + dollH - 4, Math.max(16, (int) (dollH / 2.25f)), dollYaw, lookX, lookY, tex, cfg.slim, ticks + delta);
        ctx.fill(nickField.getX() - 1, nickField.getY() - 1, nickField.getX() + nickField.getWidth() + 1, nickField.getY() + nickField.getHeight() + 1, 0xFF1E1B19);
        ctx.drawCenteredTextWithShadow(textRenderer, fit(skinInfo, lw - 8), cx, height - 8 - 11, 0xFFFFFF);

        // список
        List<Row> rs = rows();
        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        if (scroll > Math.max(0, rs.size() - visible)) scroll = Math.max(0, rs.size() - visible);
        if (rs.isEmpty()) {
            PeStyle.panel(ctx, rx, listTop, rw, ROW_H, PeStyle.ROW);
            ctx.drawCenteredTextWithShadow(textRenderer, scanning ? "Ищу миры в локальной сети…" : "Пусто: открой мир на телефоне в той же Wi-Fi", rx + rw / 2, listTop + 8, 0xFFFFFF);
            ctx.drawCenteredTextWithShadow(textRenderer, "§7или нажми «Новый сервер»", rx + rw / 2, listTop + 20, 0xFFFFFF);
        }
        for (int i = 0; i < visible && scroll + i < rs.size(); i++) {
            Row r = rs.get(scroll + i);
            int y = listTop + i * ROW_H;
            int w = r.saved != null ? rw - SIDE * 2 - 4 : rw;
            boolean hover = mouseX >= rx && mouseX < rx + w && mouseY >= y && mouseY < y + ROW_H - 2;
            drawRow(ctx, r, rx, y, w, hover);
        }
        if (rs.size() > visible) {
            int barH = Math.max(10, (listBottom - listTop) * visible / rs.size());
            int barY = listTop + (listBottom - listTop - barH) * scroll / Math.max(1, rs.size() - visible);
            ctx.fill(width - 6, barY, width - 4, barY + barH, 0xC0FFFFFF);
        }
        String hint = !message.isEmpty() ? message : scanning ? "§7Поиск миров в локальной сети…" : "§7Нажми на сервер или мир, чтобы играть";
        ctx.drawCenteredTextWithShadow(textRenderer, hint, rx + rw / 2, height - 13, 0xFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    private void drawRow(DrawContext ctx, Row r, int x, int y, int w, boolean hover) {
        PeStyle.panel(ctx, x, y, w, ROW_H - 2, hover ? PeStyle.ROW_HOVER : PeStyle.ROW);
        int right = x + w - 6;
        int dotColor = r.ping >= 0 ? 0xFF55E040 : r.ping == -1 ? 0xFF6E6E6E : 0xFFE0C040;
        PeStyle.dot(ctx, right - 6, y + ROW_H / 2 - 1, 6, dotColor);
        int infoRight = right - 16;
        String players = r.players != null && !r.players.isEmpty() ? r.players : (r.ping == -1 ? "нет ответа" : "");
        int pw = textRenderer.getWidth(players);
        if (!players.isEmpty()) ctx.drawTextWithShadow(textRenderer, players, infoRight - pw, y + 6, 0xFFFFFF);
        String ver = r.ver == null ? "" : r.ver + (r.ping >= 0 ? "  " + r.ping + "мс" : "");
        int vw = textRenderer.getWidth(ver);
        if (!ver.isEmpty()) ctx.drawTextWithShadow(textRenderer, ver, infoRight - vw, y + 19, r.ver.startsWith("0.14") ? 0xE8E8E8 : 0xFF5050);
        int tx = x + 8;
        int maxT = infoRight - Math.max(pw, vw) - 8 - tx;
        String title = (r.lan ? "§a" : "§f") + r.title;
        ctx.drawTextWithShadow(textRenderer, fit(title, maxT), tx, y + 6, 0xFFFFFF);
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
