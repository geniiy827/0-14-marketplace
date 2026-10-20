package com.geniiy.bridge14;

import com.geniiy.bridge14.pe.LanScanner;
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
import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Главное меню режима 0.14: профиль, список серверов, локальные миры. */
public class BridgeScreen extends Screen {
    private final Screen parent;
    private final Config cfg;
    private TextFieldWidget nickField;
    private int scroll;
    private static volatile List<LanScanner.Found> lan = new ArrayList<>();
    private static volatile long lastScan;
    private static volatile boolean scanning;
    private String skinInfo = "";
    private String message = "";

    private static final class Row { String title, sub; Config.Server saved; String host; int port; }

    public BridgeScreen(Screen parent) {
        super(Text.literal("Minecraft PE 0.14"));
        this.parent = parent;
        this.cfg = Config.load();
        if (!Bridge14.status.isEmpty()) { message = Bridge14.status; Bridge14.status = ""; }
    }

    @Override
    protected void init() {
        int cx = width / 2;
        // ---- профиль ----
        nickField = new TextFieldWidget(textRenderer, cx - 100, 40, 200, 20, Text.literal("Ник"));
        nickField.setMaxLength(16);
        nickField.setPlaceholder(Text.literal("Ник (3–16 латинских букв и цифр)"));
        nickField.setText(cfg.nick == null || cfg.nick.isEmpty() ? client.getSession().getUsername() : cfg.nick);
        nickField.setTextPredicate(s -> s.matches("[A-Za-z0-9_]{0,16}"));
        nickField.setChangedListener(s -> { cfg.nick = s; cfg.save(); });
        addDrawableChild(nickField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Выбрать скин…"), b -> pickSkin()).dimensions(cx - 100, 64, 98, 20)
                .tooltip(Tooltip.of(Text.literal("PNG 64×64 или 64×32 — так тебя увидят игроки 0.14"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Папка"), b -> Util.getOperatingSystem().open(Config.dir().toFile())).dimensions(cx + 2, 64, 48, 20)
                .tooltip(Tooltip.of(Text.literal("Можно просто положить сюда skin.png"))).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Сброс"), b -> { cfg.skinPath = ""; cfg.save(); updateSkinInfo(); }).dimensions(cx + 52, 64, 48, 20).build());
        updateSkinInfo();

        // ---- список миров и серверов ----
        List<Row> rows = rows();
        int top = 108, rowH = 24;
        int visible = Math.max(1, (height - top - 60) / rowH);
        if (scroll > Math.max(0, rows.size() - visible)) scroll = Math.max(0, rows.size() - visible);
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            Row r = rows.get(scroll + i);
            int y = top + i * rowH;
            String label = "▶ " + r.title;
            ButtonWidget play = ButtonWidget.builder(Text.literal(label), b -> play(r)).dimensions(cx - 150, y, r.saved != null ? 252 : 300, 20)
                    .tooltip(Tooltip.of(Text.literal(r.sub))).build();
            addDrawableChild(play);
            if (r.saved != null) {
                final Config.Server s = r.saved;
                addDrawableChild(ButtonWidget.builder(Text.literal("✎"), b -> client.setScreen(new ServerEditScreen(this, cfg, s))).dimensions(cx + 104, y, 22, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> { cfg.servers.remove(s); cfg.save(); clearAndInit(); }).dimensions(cx + 128, y, 22, 20).build());
            }
        }
        if (scroll > 0) addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> { scroll--; clearAndInit(); }).dimensions(cx + 154, top, 20, 20).build());
        if (scroll + visible < rows.size()) addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> { scroll++; clearAndInit(); }).dimensions(cx + 154, top + (visible - 1) * rowH, 20, 20).build());

        // ---- нижние кнопки ----
        int by = height - 28;
        addDrawableChild(ButtonWidget.builder(Text.literal("Добавить сервер"), b -> client.setScreen(new ServerEditScreen(this, cfg, null))).dimensions(cx - 154, by, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Обновить"), b -> { lastScan = 0; scan(); }).dimensions(cx - 50, by, 100, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Назад"), b -> close()).dimensions(cx + 54, by, 100, 20).build());
        scan();
    }

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (LanScanner.Found f : lan) {
            Row r = new Row();
            String[] p = f.info.split(";");
            String world = p.length > 1 ? p[1] : "Мир";
            String ver = p.length > 3 ? p[3] : "?";
            String players = p.length > 5 ? p[4] + "/" + p[5] : "";
            r.title = "🖧 " + strip(world) + "  (локальная сеть, " + ver + (players.isEmpty() ? "" : ", " + players) + ")";
            r.sub = f.host + ":" + f.port;
            r.host = f.host; r.port = f.port;
            out.add(r);
        }
        for (Config.Server s : cfg.servers) {
            Row r = new Row();
            r.title = s.name + "  —  " + s.host + (s.port != 19132 ? ":" + s.port : "");
            r.sub = s.host + ":" + s.port;
            r.saved = s; r.host = s.host; r.port = s.port;
            out.add(r);
        }
        return out;
    }

    private static String strip(String s) { return s.replaceAll("§.", ""); }

    private void scan() {
        if (scanning || System.currentTimeMillis() - lastScan < 4000) return;
        scanning = true;
        Thread t = new Thread(() -> {
            List<LanScanner.Found> res = LanScanner.scan(null, 1500);
            // свой же прокси/дубликаты убираем
            lan = res;
            lastScan = System.currentTimeMillis();
            scanning = false;
            client.execute(() -> { if (client.currentScreen == this) clearAndInit(); });
        }, "bridge14-lan");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void tick() {
        super.tick();
        if (System.currentTimeMillis() - lastScan > 6000) scan();
    }

    private void updateSkinInfo() {
        File f = cfg.skinFile();
        if (f == null) { skinInfo = "Скин: стандартный (робот)"; return; }
        byte[] s = Proxy.loadSkin(f);
        skinInfo = s == null ? "§cСкин не подходит: нужен PNG 64×64 или 64×32" : "Скин: " + f.getName() + (s.length == 16384 ? " (64×64)" : " (64×32)");
    }

    private void pickSkin() {
        try (MemoryStack st = MemoryStack.stackPush()) {
            PointerBuffer filter = st.mallocPointer(1);
            filter.put(st.UTF8("*.png"));
            filter.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog("Скин для 0.14 (PNG 64×64)", null, filter, "PNG", false);
            if (path != null) { cfg.skinPath = path; cfg.save(); updateSkinInfo(); }
        } catch (Throwable e) {
            message = "Не открылось окно выбора файла — положи skin.png в папку (кнопка «Папка»)";
        }
    }

    private void play(Row r) {
        String nick = nickField.getText().trim();
        if (!nick.matches("[A-Za-z0-9_]{3,16}")) { message = "§cНик: 3–16 латинских букв, цифр или _"; return; }
        cfg.nick = nick; cfg.save();
        Session.Config sc = new Session.Config();
        sc.nick = nick; sc.host = r.host; sc.port = r.port; sc.chunkRadius = cfg.chunkRadius;
        sc.tags = TagsBuilder::build;
        File sf = cfg.skinFile();
        if (sf != null) sc.skin = Proxy.loadSkin(sf);
        try {
            int port = Bridge14.PROXY.start(sc, new Session.Listener() {
                public void status(String s) { Bridge14.LOG.info(s); }
                public void log(String s) { Bridge14.LOG.info(s); }
                public void ended(String reason) { Bridge14.sessionEnded(reason); }
            });
            Bridge14.active = true;
            ConnectScreen.connect(this, client, new ServerAddress("127.0.0.1", port), new ServerInfo("0.14: " + r.title, "127.0.0.1:" + port, false), false);
        } catch (Exception e) {
            Bridge14.active = false;
            message = "§cНе удалось запустить мост: " + e.getMessage();
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        int cx = width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, "§lMinecraft PE 0.14", cx, 10, 0xFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "§7сервера и локальные миры карманной версии", cx, 22, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Ник:", cx - 130, 46, 0xCCCCCC);
        ctx.drawCenteredTextWithShadow(textRenderer, skinInfo, cx, 88, 0xCCCCCC);
        ctx.drawTextWithShadow(textRenderer, "§eМиры и серверы", cx - 150, 98, 0xFFFF55);
        if (rows().isEmpty())
            ctx.drawCenteredTextWithShadow(textRenderer, scanning ? "Ищу миры в локальной сети…" : "Пусто. Открой мир на телефоне в той же Wi-Fi сети или добавь сервер.", cx, 120, 0x999999);
        if (!message.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, message, cx, height - 42, 0xFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() { cfg.save(); client.setScreen(parent); }
}
