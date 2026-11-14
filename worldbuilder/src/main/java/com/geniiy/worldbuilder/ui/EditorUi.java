package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.edit.*;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.session.Editor;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.*;

/** Панели редактора: меню, инструменты и их параметры, вкладки, строка состояния, хотбар. */
public class EditorUi {
    final EditorScreen s;
    final Gui g;
    public record MenuItem(String label, String key, Runnable action, boolean enabled) {}
    int openMenu = -1; int menuX;
    int tab = 0;
    String search = ""; int category = -1; boolean allMetas, allIds; double palOff; int favTab; // 0 все, 1 избранное, 2 недавние
    double selOff, histOff, clipOff, worldOff, optOff, biomeOff;
    Integer dragState; Pattern dragPattern;
    boolean mixOpen;
    public int selVoxMode; public int polyHeight = 5;
    int tourStep = -1;
    final EditorDialogs dlg;

    EditorUi(EditorScreen s) { this.s = s; this.g = s.gui; this.dlg = new EditorDialogs(s, this); }

    PeBlocks t() { return PeBlocks.get(); }
    Editor ed() { return s.ed; }

    void draw(float delta) {
        int W = s.W, H = s.H;
        if (!s.hideUi) {
            drawTopBar(W);
            if (!s.leftCollapsed) drawLeft(0, EditorScreen.TOP, s.leftW, H - EditorScreen.TOP - EditorScreen.STATUS);
            if (!s.rightCollapsed) drawRight(W - s.rightW, EditorScreen.TOP, s.rightW, H - EditorScreen.TOP - EditorScreen.STATUS);
            drawCollapseButtons(W, H);
            drawStatus(W, H);
        }
        drawViewportHud();
        drawHotbar();
        drawDrag();
        if (openMenu >= 0) drawMenu();
        if (s.ctxMenu != null) drawContext();
        if (tourStep >= 0) drawTour();
    }

    // ================== верхняя панель ==================
    static final String[] MENUS = {"≡ Файл", "Правка", "Вид", "Инструменты", "Справка"};

    void drawTopBar(int W) {
        g.rect(0, 0, W, EditorScreen.TOP, 0xFF1A1B1E);
        g.hsep(0, EditorScreen.TOP - 1, W);
        int x = 4;
        for (int i = 0; i < MENUS.length; i++) {
            int w = g.width(MENUS[i]) + 14;
            boolean hv = g.hover(x, 1, w, 16);
            if (openMenu == i || hv) g.rect(x, 1, w, 16, openMenu == i ? Gui.BTN_SEL : Gui.BTN_H);
            g.text(MENUS[i], x + 7, 5, Gui.TEXT);
            if (g.clicked(x, 1, w, 16, 0)) { openMenu = openMenu == i ? -1 : i; menuX = x; }
            else if (openMenu >= 0 && hv && openMenu != i) { openMenu = i; menuX = x; }
            x += w + 2;
        }
        String title = s.world.level.levelName() + "  •  " + (s.ed.source.isRemote() ? s.ed.source.profile.title() + ":" + s.ed.source.remotePath : "диск");
        boolean dirty = s.world.hasUnsavedChanges();
        g.text(g.ellipsize(title, W - x - 260), x + 16, 5, Gui.TEXT2);
        if (dirty) g.text("● не сохранено", x + 16 + Math.min(g.width(title), W - x - 260) + 10, 5, Gui.WARN);
        if (g.primary("save", W - 128, 1, 90, 16, "Сохранить", "Ctrl+S — последний вариант, Ctrl+Shift+S — диалог", true)) openSave();
        if (g.button("savearrow", W - 36, 1, 32, 16, "▾", "Варианты сохранения")) openSave();
    }

    List<MenuItem> menuItems(int i) {
        List<MenuItem> m = new ArrayList<>();
        boolean sel = !ed().selection.isEmpty();
        switch (i) {
            case 0 -> {
                m.add(new MenuItem("Сохранить…", "Ctrl+Shift+S", this::openSave, true));
                m.add(new MenuItem("Быстрое сохранение", "Ctrl+S", this::quickSave, true));
                m.add(null);
                m.add(new MenuItem("Импорт схематика…", "Ctrl+I", () -> importSchematic(null), true));
                m.add(new MenuItem("Сохранить выделение как схематик…", null, this::exportSchematic, sel));
                m.add(null);
                m.add(new MenuItem("Создать тестовый мир всех блоков", null, () -> dlg.testWorld(), true));
                m.add(new MenuItem("Настройки…", null, () -> net.minecraft.client.MinecraftClient.getInstance().setScreen(new SettingsScreen(s)), true));
                m.add(null);
                m.add(new MenuItem("Выйти из редактора", "Esc", this::askExit, true));
            }
            case 1 -> {
                m.add(new MenuItem("Отменить", "Ctrl+Z", () -> ed().undo(s::toast), ed().history.canUndo()));
                m.add(new MenuItem("Повторить", "Ctrl+Y", () -> ed().redo(s::toast), ed().history.canRedo()));
                m.add(null);
                m.add(new MenuItem("Копировать", "Ctrl+C", s::copy, sel));
                m.add(new MenuItem("Вырезать", "Ctrl+X", s::cut, sel));
                m.add(new MenuItem("Вставить", "Ctrl+V", () -> s.startPaste(ed().clip), ed().clip != null));
                m.add(null);
                m.add(new MenuItem("Заполнить", "Ctrl+F", this::actionFill, sel));
                m.add(new MenuItem("Заменить…", "Ctrl+H", this::openReplace, sel));
                m.add(new MenuItem("Очистить", "Delete", () -> actionClear(false), sel));
                m.add(new MenuItem("Снять выделение", "Ctrl+D", s::clearSelection, sel));
                m.add(new MenuItem("Перейти к координатам…", "Ctrl+G", this::openGoto, true));
            }
            case 2 -> {
                String[] lm = {"Полная яркость", "Как в игре (день)", "Как в игре (ночь)"};
                for (int k = 0; k < 3; k++) { int kk = k; m.add(new MenuItem((s.lightMode == k ? "● " : "   ") + "Освещение: " + lm[k], null, () -> { s.lightMode = kk; WbConfig.get().lightMode = kk; WbConfig.get().save(); }, true)); }
                m.add(null);
                m.add(new MenuItem((s.cam.ortho ? "● " : "   ") + "Ортогональный вид", "NumPad5", () -> s.cam.ortho = !s.cam.ortho, true));
                m.add(new MenuItem("   Вид сверху", "NumPad7", () -> { s.cam.ortho = true; s.cam.pitch = 90; }, true));
                m.add(new MenuItem("   Вид спереди", "NumPad1", () -> { s.cam.ortho = true; s.cam.pitch = 0; s.cam.yaw = 180; }, true));
                m.add(new MenuItem("   Вид сбоку", "NumPad3", () -> { s.cam.ortho = true; s.cam.pitch = 0; s.cam.yaw = 90; }, true));
                m.add(null);
                WbConfig c = WbConfig.get();
                m.add(new MenuItem((s.sliceOn ? "● " : "   ") + "Срез по высоте (Y ≤ " + s.sliceY + ")", "N", () -> { s.sliceOn = !s.sliceOn; s.vr.invalidateAll(); }, true));
                m.add(new MenuItem("   Срез выше на 1", "Alt+PgUp", () -> { s.sliceY = Math.min(127, s.sliceY + 1); if (s.sliceOn) s.vr.invalidateAll(); }, true));
                m.add(new MenuItem("   Срез ниже на 1", "Alt+PgDn", () -> { s.sliceY = Math.max(0, s.sliceY - 1); if (s.sliceOn) s.vr.invalidateAll(); }, true));
                m.add(new MenuItem((s.onlySel ? "● " : "   ") + "Показать только выделение", null, () -> { s.onlySel = !s.onlySel; s.vr.invalidateAll(); }, true));
                m.add(new MenuItem((c.showInvisible ? "● " : "   ") + "Показывать невидимые блоки", null, () -> { c.showInvisible = !c.showInvisible; s.vr.opt.showInvisible = c.showInvisible; s.vr.invalidateAll(); c.save(); }, true));
                m.add(new MenuItem((c.chunkGrid ? "● " : "   ") + "Сетка чанков и регионов", null, () -> { c.chunkGrid = !c.chunkGrid; c.save(); }, true));
                m.add(new MenuItem((c.worldBounds ? "● " : "   ") + "Границы мира", null, () -> { c.worldBounds = !c.worldBounds; c.save(); }, true));
                m.add(new MenuItem((c.floorGrid ? "● " : "   ") + "Сетка-пол на высоте курсора", null, () -> { c.floorGrid = !c.floorGrid; c.save(); }, true));
                m.add(new MenuItem((s.showBiomes ? "● " : "   ") + "Показать биомы", null, () -> s.showBiomes = !s.showBiomes, true));
                m.add(new MenuItem((c.fog ? "● " : "   ") + "Туман", null, () -> { c.fog = !c.fog; s.vr.fog = c.fog; c.save(); }, true));
                m.add(new MenuItem("   Скрыть панели", "Tab", () -> s.hideUi = !s.hideUi, true));
                m.add(null);
                m.add(new MenuItem("   К точке спавна", "Home", () -> s.cam.flyTo(s.world.level.spawnX() + 0.5, s.world.level.spawnY() + 10, s.world.level.spawnZ() - 15, 0, 30, 0.4), true));
                for (int k = 1; k <= 9; k++) {
                    double[] b = WbConfig.get().bookmarks.get(s.world.level.levelName() + "#" + k);
                    if (b != null) m.add(new MenuItem("   Закладка " + k + String.format(" (%d, %d, %d)", (int) b[0], (int) b[1], (int) b[2]), "Alt+" + k, () -> s.cam.flyTo(b[0], b[1], b[2], (float) b[3], (float) b[4], 0.4), true));
                }
            }
            case 3 -> {
                for (EditorScreen.Tool tl : EditorScreen.Tool.values()) m.add(new MenuItem((s.tool == tl ? "● " : "   ") + tl.ru, Keys.hint(tl.key), () -> s.setTool(tl), true));
            }
            case 4 -> {
                m.add(new MenuItem("Горячие клавиши и справка", "F1", this::openHelp, true));
                m.add(new MenuItem("Показать тур для новичка", null, this::startTour, true));
                m.add(new MenuItem("Отладочный оверлей", "F3", () -> s.debug = !s.debug, true));
            }
        }
        return m;
    }

    void drawMenu() {
        List<MenuItem> items = menuItems(openMenu);
        drawPopup(items, menuX, EditorScreen.TOP, () -> openMenu = -1);
        if (g.pressed[0] && !g.clickConsumed && !g.hover(0, 0, s.W, EditorScreen.TOP)) openMenu = -1;
    }

    void drawContext() {
        drawPopup(s.ctxMenu, s.ctxX, s.ctxY, () -> s.ctxMenu = null);
        if ((g.pressed[0] || g.pressed[1]) && !g.clickConsumed) s.ctxMenu = null;
    }

    void drawPopup(List<MenuItem> items, int x, int y, Runnable close) {
        int w = 160;
        for (MenuItem m : items) if (m != null) w = Math.max(w, g.width(m.label()) + (m.key() != null ? g.width(m.key()) + 24 : 0) + 20);
        int h = 4;
        for (MenuItem m : items) h += m == null ? 5 : 15;
        if (x + w > s.W) x = s.W - w - 2;
        if (y + h > s.H) y = Math.max(0, s.H - h - 2);
        g.ctx.getMatrices().push();
        g.ctx.getMatrices().translate(0, 0, 250);
        boolean ov = g.overlayLayer;
        g.overlayLayer = true;
        g.rect(x, y, w, h, 0xF8202226);
        g.frame(x, y, w, h, Gui.SEP);
        int yy = y + 2;
        for (MenuItem m : items) {
            if (m == null) { g.hsep(x + 4, yy + 2, w - 8); yy += 5; continue; }
            boolean hv = g.hover(x + 1, yy, w - 2, 15);
            if (hv && m.enabled()) g.rect(x + 1, yy, w - 2, 15, Gui.BTN_SEL);
            g.text(m.label(), x + 8, yy + 4, m.enabled() ? Gui.TEXT : 0xFF5F6268);
            if (m.key() != null) g.textR(m.key(), x + w - 8, yy + 4, Gui.TEXT2);
            if (m.enabled() && g.clicked(x + 1, yy, w - 2, 15, 0)) { close.run(); m.action().run(); }
            yy += 15;
        }
        g.overlayLayer = ov;
        g.registerOverlay(x, y, w, h);
        g.ctx.getMatrices().pop();
    }

    void drawCollapseButtons(int W, int H) {
        int y = EditorScreen.TOP + 4;
        int lx = s.leftCollapsed ? 2 : s.leftW + 2;
        if (g.button("colL", lx, y, 14, 14, s.leftCollapsed ? "»" : "«", s.leftCollapsed ? "Показать левую панель" : "Свернуть левую панель")) s.leftCollapsed = !s.leftCollapsed;
        int rx = s.rightCollapsed ? W - 16 : W - s.rightW - 16;
        if (g.button("colR", rx, y, 14, 14, s.rightCollapsed ? "«" : "»", s.rightCollapsed ? "Показать правую панель" : "Свернуть правую панель")) s.rightCollapsed = !s.rightCollapsed;
        // перетаскивание разделителей
        if (!s.leftCollapsed) {
            boolean hv = g.hover(s.leftW - 2, EditorScreen.TOP + 20, 4, H - 60);
            if (hv && g.pressed[0] && !g.clickConsumed) { g.active = "splitL"; g.clickConsumed = true; }
            if ("splitL".equals(g.active) && g.down[0]) s.leftW = Math.max(150, Math.min(400, g.mx));
            if (hv || "splitL".equals(g.active)) g.rect(s.leftW - 1, EditorScreen.TOP, 2, H - EditorScreen.TOP - EditorScreen.STATUS, Gui.ACCENT);
        }
        if (!s.rightCollapsed) {
            int sx = W - s.rightW;
            boolean hv = g.hover(sx - 2, EditorScreen.TOP + 20, 4, H - 60);
            if (hv && g.pressed[0] && !g.clickConsumed) { g.active = "splitR"; g.clickConsumed = true; }
            if ("splitR".equals(g.active) && g.down[0]) s.rightW = Math.max(220, Math.min(520, W - g.mx));
            if (hv || "splitR".equals(g.active)) g.rect(sx - 1, EditorScreen.TOP, 2, H - EditorScreen.TOP - EditorScreen.STATUS, Gui.ACCENT);
        }
    }

    // ================== левая панель ==================
    void drawLeft(int x, int y, int w, int h) {
        g.panel(x, y, w, h);
        EditorScreen.Tool[] tools = EditorScreen.Tool.values();
        boolean narrow = w < 180;
        int cols = narrow ? 3 : 2;
        int bw = (w - 8 - (cols - 1) * 2) / cols, bh = 18;
        for (int i = 0; i < tools.length; i++) {
            EditorScreen.Tool tl = tools[i];
            int bx = x + 4 + (i % cols) * (bw + 2), by = y + 22 + (i / cols) * (bh + 2);
            String lbl = narrow ? tl.ru.substring(0, Math.min(4, tl.ru.length())) : tl.ru;
            if (g.button("tool" + i, bx, by, bw, bh, lbl, null, s.tool == tl, true)) s.setTool(tl);
            if (g.hover(bx, by, bw, bh)) g.tooltip("tooltip" + i, tl.ru + "\n" + tl.desc, Keys.hint(tl.key));
        }
        int oy = y + 22 + ((tools.length + cols - 1) / cols) * (bh + 2) + 6;
        g.hsep(x + 4, oy - 3, w - 8);
        g.text("Параметры: " + s.tool.ru, x + 6, oy + 1, Gui.ACCENT);
        oy += 14;
        int area = y + h - oy - 2;
        g.scissor(x, oy, w, area);
        int start = oy - (int) optOff;
        int end = new ToolOptions(this, x + 6, start, w - 14).draw();
        g.noScissor();
        optOff = g.scrollArea("opts", x, oy, w, area, end - start + 8, optOff);
    }

    // ================== правая панель ==================
    static final String[] TABS = {"Блоки", "Выделение", "История", "Буфер", "Мир"};

    void drawRight(int x, int y, int w, int h) {
        g.panel(x, y, w, h);
        tab = g.segmented("tabs", x + 2, y + 22, w - 4, 16, TABS, tab, null);
        int cy = y + 42, ch = h - 44;
        switch (tab) {
            case 0 -> drawPalette(x, cy, w, ch);
            case 1 -> new SelectionTab(this).draw(x, cy, w, ch);
            case 2 -> drawHistory(x, cy, w, ch);
            case 3 -> drawClipboard(x, cy, w, ch);
            default -> new WorldTab(this).draw(x, cy, w, ch);
        }
    }

    // ---------- палитра ----------
    static final String[] CAT_RU = {"Все", "Строит.", "Дерево", "Природа", "Растения", "Цветные", "Декор", "Редстоун", "Жидкости", "Особые PE"};

    ItemStack iconFor(int state) {
        if (state < 0) return ItemStack.EMPTY;
        int id = state >> 4;
        if (id == 8 || id == 9) return new ItemStack(Items.WATER_BUCKET);
        if (id == 10 || id == 11) return new ItemStack(Items.LAVA_BUCKET);
        if (id == 51) return new ItemStack(Items.FLINT_AND_STEEL);
        if (id == 55) return new ItemStack(Items.REDSTONE);
        if (id == 59) return new ItemStack(Items.WHEAT);
        if (id == 63 || id == 68) return new ItemStack(Items.OAK_SIGN);
        if (id == 83) return new ItemStack(Items.SUGAR_CANE);
        if (id == 92) return new ItemStack(Items.CAKE);
        if (id == 26) return new ItemStack(Items.RED_BED);
        if (id == 199) return new ItemStack(Items.ITEM_FRAME);
        if (id == 132) return new ItemStack(Items.STRING);
        if (id == 140) return new ItemStack(Items.FLOWER_POT);
        if (id == 144) return new ItemStack(Items.SKELETON_SKULL);
        if (id == 104) return new ItemStack(Items.PUMPKIN_SEEDS);
        if (id == 105) return new ItemStack(Items.MELON_SEEDS);
        if (id == 141) return new ItemStack(Items.CARROT);
        if (id == 142) return new ItemStack(Items.POTATO);
        if (id == 244) return new ItemStack(Items.BEETROOT);
        if (id == 115) return new ItemStack(Items.NETHER_WART);
        if (id == 127) return new ItemStack(Items.COCOA_BEANS);
        if (id == 118) return new ItemStack(Items.CAULDRON);
        if (id == 117) return new ItemStack(Items.BREWING_STAND);
        if (id == 93 || id == 94) return new ItemStack(Items.REPEATER);
        if (id == 149 || id == 150) return new ItemStack(Items.COMPARATOR);
        if (id == 90) return new ItemStack(Items.OBSIDIAN);
        if (!JavaStates.isReady()) return ItemStack.EMPTY;
        BlockState bs = JavaStates.BASE[state & 4095];
        var item = bs.getBlock().asItem();
        if (item == Items.AIR) return ItemStack.EMPTY;
        return new ItemStack(item);
    }

    void drawIcon(int state, int x, int y, int size) {
        ItemStack st = iconFor(state);
        if (st.isEmpty()) {
            int id = state >> 4;
            int c = state < 0 || t().block(id) == null ? 0xFFB000B0 : (0xFF000000 | (id * 0x9E3779B1 >>> 8));
            g.rect(x + 1, y + 1, size - 2, size - 2, c);
            g.frame(x + 1, y + 1, size - 2, size - 2, 0xFF000000);
            String lbl = state < 0 ? "?" : String.valueOf(id);
            g.ctx.getMatrices().push();
            g.ctx.getMatrices().translate(0, 0, 200);
            g.textC(lbl, x + size / 2, y + size / 2 - 4, 0xFFFFFFFF);
            g.ctx.getMatrices().pop();
            return;
        }
        g.ctx.getMatrices().push();
        g.ctx.getMatrices().translate(x, y, 0);
        float sc = size / 16f;
        g.ctx.getMatrices().scale(sc, sc, 1);
        g.ctx.drawItem(st, 0, 0);
        g.ctx.getMatrices().pop();
    }

    void drawPalette(int x, int y, int w, int h) {
        WbConfig cfg = WbConfig.get();
        search = g.field("palsearch", x + 4, y, w - 8, 16, search, "Поиск: имя, id, id:meta, oak_log…", false, false);
        int yy = y + 20;
        // категории
        int bx = x + 4;
        for (int i = -1; i < PeBlocks.CATEGORIES.length; i++) {
            String lbl = CAT_RU[i + 1];
            int bw = g.width(lbl) + 8;
            if (bx + bw > x + w - 4) { bx = x + 4; yy += 15; }
            if (g.button("cat" + i, bx, yy, bw, 13, lbl, null, category == i && favTab == 0, true)) { category = i; favTab = 0; palOff = 0; }
            bx += bw + 2;
        }
        yy += 16;
        if (g.button("favs", x + 4, yy, 70, 13, "★ Избранное", "ПКМ по блоку — добавить/убрать", favTab == 1, true)) { favTab = favTab == 1 ? 0 : 1; palOff = 0; }
        if (g.button("recent", x + 76, yy, 70, 13, "Недавние", null, favTab == 2, true)) { favTab = favTab == 2 ? 0 : 2; palOff = 0; }
        allMetas = g.check("allmetas", x + 152, yy + 1, "Все меты", allMetas, "Каждая пара id:meta отдельной иконкой");
        allIds = g.check("allids", x + 218, yy + 1, "Все id", allIds, "Показать и неизвестные id (для продвинутых)");
        yy += 17;
        List<Integer> states = new ArrayList<>();
        if (favTab == 1) states.addAll(cfg.favorites);
        else if (favTab == 2) states.addAll(cfg.recents);
        else {
            for (int st : t().paletteStates(allMetas)) {
                PeBlocks.BlockInfo b = t().block(st >> 4);
                if (category >= 0 && !PeBlocks.CATEGORIES[category].equals(b.category)) continue;
                states.add(st);
            }
            if (allIds) for (int id = 1; id < 256; id++) if (t().block(id) == null) states.add(id << 4);
        }
        if (!search.isBlank()) { String q = search; states.removeIf(st -> !t().matches(st, q)); }
        int mixH = mixOpen ? 150 : 18;
        int size = cfg.paletteIcon, cell = size + 4;
        int cols = Math.max(1, (w - 12) / cell);
        int gridH = h - (yy - y) - mixH - 4;
        int rows = (states.size() + cols - 1) / cols;
        palOff = g.scrollArea("pal", x + 2, yy, w - 4, gridH, rows * cell, palOff);
        g.scissor(x + 2, yy, w - 4, gridH);
        int hoverState = Integer.MIN_VALUE;
        for (int i = 0; i < states.size(); i++) {
            int cx = x + 4 + (i % cols) * cell, cy = yy + (i / cols) * cell - (int) palOff;
            if (cy + cell < yy || cy > yy + gridH) continue;
            int st = states.get(i);
            boolean hv = g.hover(cx, cy, cell, cell) && g.my >= yy && g.my < yy + gridH;
            boolean cur = ed().activeState() == st;
            if (hv || cur) g.rect(cx, cy, cell, cell, cur ? Gui.BTN_SEL : Gui.BTN_H);
            drawIcon(st, cx + 2, cy + 2, size);
            if (cfg.favorites.contains(st)) g.text("★", cx + cell - 7, cy, Gui.WARN);
            PeBlocks.BlockInfo b = t().block(st >> 4);
            if (b != null && "verify".equals(b.status)) g.rect(cx + 1, cy + cell - 3, 3, 2, Gui.WARN);
            if (b != null && "pe_only".equals(b.status)) g.rect(cx + 1, cy + cell - 3, 3, 2, 0xFF60A0FF);
            if (hv) {
                hoverState = st;
                if (g.pressed[0] && !g.clickConsumed) {
                    g.clickConsumed = true;
                    if (mixOpen) { Pattern p = ed().active(); if (p.kind == Pattern.Kind.SINGLE) p.kind = Pattern.Kind.RANDOM; p.entries.add(new int[]{st, 50}); }
                    else { ed().hotbar[ed().slot] = Pattern.single(st); ed().saveHotbar(); cfg.addRecent(st); s.manualOrient = false; dragState = st; }
                }
                if (g.pressed[1] && !g.clickConsumed) { g.clickConsumed = true; if (cfg.favorites.contains(st)) cfg.favorites.remove((Integer) st); else cfg.favorites.add(st); cfg.save(); }
            }
        }
        g.noScissor();
        if (states.isEmpty()) g.textC("Ничего не найдено", x + w / 2, yy + 20, Gui.TEXT2);
        if (hoverState != Integer.MIN_VALUE) g.tooltip("palcard" + hoverState, blockCard(hoverState), null);
        drawMixEditor(x, y + h - mixH, w, mixH);
    }

    String blockCard(int st) {
        int id = st >> 4, meta = st & 15;
        PeBlocks.Resolved r = t().resolve(id, meta);
        if (r == null) return "Неизвестный блок #" + id + "\nНет в таблице 0.14 — показывается заглушкой";
        StringBuilder sb = new StringBuilder();
        sb.append(r.meta() != null && r.meta().nameRu != null ? r.meta().nameRu : r.block().nameRu).append('\n');
        sb.append(r.block().nameEn).append("  •  ").append(id).append(':').append(meta).append('\n');
        if (r.meta() != null) sb.append(r.meta().java).append('\n');
        sb.append("Свет: ").append(r.block().lightEmission).append(", фильтр: ").append(r.block().lightFilter).append(r.block().transparent ? ", прозрачный" : "").append('\n');
        if ("verify".equals(r.block().status) || (r.meta() != null && "verify".equals(r.meta().status))) sb.append("⚠ Проверить в игре 0.14.3 (статус verify)\n");
        if ("pe_only".equals(r.block().status)) sb.append("Блок только PE (свой блок мода)\n");
        if (r.block().tileEntity != null) sb.append("Тайл: ").append(r.block().tileEntity).append('\n');
        sb.append("Клик — в хотбар, ПКМ — в избранное");
        return sb.toString();
    }

    void drawMixEditor(int x, int y, int w, int h) {
        g.hsep(x + 2, y, w - 4);
        Pattern p = ed().active();
        if (g.button("mixtoggle", x + 4, y + 2, w - 8, 14, (mixOpen ? "▾ " : "▸ ") + "Смесь для ячейки " + (ed().slot + 1) + ": " + g.ellipsize(p.describe(), w - 140), "Случайная смесь, градиент, шум, шахматка, плитка буфера")) mixOpen = !mixOpen;
        if (!mixOpen) return;
        int yy = y + 20;
        Pattern.Kind[] kinds = Pattern.Kind.values();
        String[] kn = new String[kinds.length];
        for (int i = 0; i < kinds.length; i++) kn[i] = kinds[i].ru;
        int k = g.segmented("mixkind", x + 4, yy, w - 8, 14, kn, p.kind.ordinal(), null);
        p.kind = kinds[k];
        if (p.kind == Pattern.Kind.CLIPBOARD) p.clipboard = ed().clip;
        yy += 18;
        g.text("Клик по блоку в палитре — добавить", x + 6, yy, Gui.TEXT2);
        yy += 11;
        int listH = h - (yy - y) - 22;
        g.scissor(x, yy, w, listH);
        for (int i = 0; i < p.entries.size(); i++) {
            int[] e = p.entries.get(i);
            int ey = yy + i * 18;
            drawIcon(e[0], x + 6, ey, 16);
            g.text(g.ellipsize(t().displayName(e[0]), w - 150), x + 26, ey + 4, Gui.TEXT);
            if (p.kind == Pattern.Kind.RANDOM) e[1] = (int) g.number("mixw" + i, x + w - 98, ey, 50, 16, "", e[1], 0, 1000, 1, true);
            if (g.button("mixdel" + i, x + w - 40, ey, 30, 16, "×", "Убрать") && p.entries.size() > 1) { p.entries.remove(i); break; }
        }
        g.noScissor();
        int by = y + h - 18;
        if (g.button("mixeq", x + 4, by, 64, 14, "Поровну", null)) { for (int[] e : p.entries) e[1] = 100 / Math.max(1, p.entries.size()); }
        if (g.button("mixnorm", x + 70, by, 64, 14, "Нормализ.", "Привести веса к 100%")) p.normalize();
        if (g.button("mixsave", x + 136, by, 70, 14, "В библиотеку", null)) dlg.savePattern(p);
        if (g.button("mixlib", x + 208, by, w - 212, 14, "Библиотека…", null)) dlg.patternLibrary();
        if (p.kind == Pattern.Kind.GRADIENT || p.kind == Pattern.Kind.NOISE || p.kind == Pattern.Kind.CHECKER) {
            // параметры поверх списка внизу
        }
    }

    // ---------- история ----------
    void drawHistory(int x, int y, int w, int h) {
        var hist = ed().history;
        int rowH = 16;
        int listH = h - 26;
        histOff = g.scrollArea("hist", x + 2, y, w - 4, listH, (hist.steps.size() + 1) * rowH, histOff);
        g.scissor(x + 2, y, w - 4, listH);
        SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss");
        for (int i = -1; i < hist.steps.size(); i++) {
            int ry = y + (i + 1) * rowH - (int) histOff;
            boolean applied = i < hist.pos;
            boolean hv = g.hover(x + 4, ry, w - 8, rowH) && g.my >= y && g.my < y + listH;
            boolean cur = i == hist.pos - 1;
            if (hv || cur) g.rect(x + 4, ry, w - 8, rowH, cur ? Gui.BTN_SEL : Gui.BTN_H);
            if (i < 0) g.text("Открытие мира", x + 8, ry + 4, applied ? Gui.TEXT : 0xFF5F6268);
            else {
                ChangeSet cs = hist.steps.get(i);
                int col = applied ? Gui.TEXT : 0xFF5F6268;
                g.text(g.ellipsize(cs.name, w - 120), x + 8, ry + 4, col);
                String info = (cs.size() > 0 ? String.format("%,d", cs.changedCount()) + " • " : "") + df.format(new Date(cs.time));
                g.textR(info, x + w - 10, ry + 4, applied ? Gui.TEXT2 : 0xFF4F5258);
            }
            if (hv && g.clicked(x + 4, ry, w - 8, rowH, 0)) ed().jumpTo(i + 1);
        }
        g.noScissor();
        g.text("Память: " + com.geniiy.worldbuilder.ui.Task.bytes(hist.memory()), x + 6, y + h - 18, Gui.TEXT2);
        if (g.button("histclear", x + w - 110, y + h - 22, 104, 16, "Очистить историю", null))
            s.dialogs.push(new WbScreen.ChoiceDialog("Очистить историю?", "Отменить сделанные изменения будет нельзя (сами изменения останутся).", new String[]{"Очистить", "Отмена"}, i -> { if (i == 0) hist.clear(); }));
    }

    // ---------- буфер ----------
    void drawClipboard(int x, int y, int w, int h) {
        int yy = y;
        g.text("Последние копии", x + 6, yy + 2, Gui.TEXT2);
        yy += 14;
        SimpleDateFormat df = new SimpleDateFormat("HH:mm");
        List<Clipboard> list = ed().clipboards;
        for (int i = 0; i < list.size(); i++) {
            Clipboard c = list.get(i);
            boolean act = c == ed().clip;
            boolean hv = g.hover(x + 4, yy, w - 8, 26);
            if (hv || act) g.rect(x + 4, yy, w - 8, 26, act ? Gui.BTN_SEL : Gui.BTN_H);
            g.text((c.pinned ? "📌 " : "") + g.ellipsize(c.name, w - 110), x + 8, yy + 3, Gui.TEXT);
            g.text(c.w + "×" + c.h + "×" + c.l + "  •  " + String.format("%,d", c.blockCount()) + " бл.  •  " + df.format(new Date(c.time)), x + 8, yy + 14, Gui.TEXT2);
            if (g.button("clpin" + i, x + w - 76, yy + 5, 20, 16, c.pinned ? "●" : "○", "Закрепить")) c.pinned = !c.pinned;
            if (g.button("clren" + i, x + w - 54, yy + 5, 20, 16, "✎", "Переименовать")) dlg.rename(c);
            if (g.button("clpaste" + i, x + w - 32, yy + 5, 24, 16, "⇩", "Вставить")) { ed().clip = c; s.startPaste(c); }
            else if (hv && g.clicked(x + 4, yy, w - 80, 26, 0)) ed().clip = c;
            yy += 28;
        }
        if (list.isEmpty()) { g.text("Пусто. Выдели область и нажми Ctrl+C.", x + 8, yy, 0xFF6A6D72); yy += 14; }
        yy += 6;
        g.hsep(x + 4, yy, w - 8);
        yy += 4;
        g.text("Библиотека заготовок (worldbuilder/schematics)", x + 6, yy, Gui.TEXT2);
        yy += 12;
        if (g.button("libsave", x + 4, yy, (w - 12) / 2, 16, "Сохранить буфер…", "Сохранить активный буфер в библиотеку (.schematic Pocket)") && ed().clip != null) dlg.saveToLibrary(ed().clip);
        if (g.button("libimport", x + 8 + (w - 12) / 2, yy, (w - 12) / 2, 16, "Импорт файла…", "Ctrl+I, или перетащи файл в окно")) importSchematic(null);
        yy += 20;
        java.nio.file.Path lib = WbConfig.gameDir().resolve("schematics");
        List<java.nio.file.Path> files = new ArrayList<>();
        try { java.nio.file.Files.createDirectories(lib); try (var st = java.nio.file.Files.list(lib)) { st.filter(p -> p.toString().endsWith(".schematic") || p.toString().endsWith(".schem")).sorted().forEach(files::add); } } catch (Exception ignored) {}
        int listH = y + h - yy - 2;
        clipOff = g.scrollArea("lib", x + 2, yy, w - 4, listH, files.size() * 15, clipOff);
        g.scissor(x + 2, yy, w - 4, listH);
        for (int i = 0; i < files.size(); i++) {
            int ry = yy + i * 15 - (int) clipOff;
            boolean hv = g.hover(x + 4, ry, w - 8, 15) && g.my >= yy && g.my < yy + listH;
            if (hv) g.rect(x + 4, ry, w - 8, 15, Gui.BTN_H);
            g.text(g.ellipsize(files.get(i).getFileName().toString(), w - 20), x + 8, ry + 4, Gui.TEXT);
            if (hv) g.tooltip("lib" + i, "Клик — в буфер и вставка", null);
            if (hv && g.clicked(x + 4, ry, w - 8, 15, 0)) importSchematic(files.get(i));
        }
        g.noScissor();
    }

    // ================== строка состояния ==================
    void drawStatus(int W, int H) {
        int y = H - EditorScreen.STATUS;
        g.rect(0, y, W, EditorScreen.STATUS, 0xFF1A1B1E);
        g.hsep(0, y, W);
        StringBuilder sb = new StringBuilder();
        Raycast.Hit h = s.hover;
        if (h != null && !h.air && !h.ungenerated) {
            String[] faces = {"низ", "верх", "север", "юг", "запад", "восток"};
            int v = s.world.getV(h.x, h.y, h.z);
            sb.append(h.x).append(' ').append(h.y).append(' ').append(h.z).append(" (").append(h.face >= 0 ? faces[h.face] : "").append(")  |  ");
            if (v < 0) sb.append("Заглушка: ").append(s.world.stubName(v));
            else sb.append(t().displayName(v)).append(" ").append(v >> 4).append(':').append(v & 15);
            if (v > 0 && JavaStates.NON_STANDARD[v & 4095]) sb.append(" ⚠");
        } else if (h != null && h.ungenerated) sb.append(h.x).append(' ').append(0).append(' ').append(h.z).append("  |  Чанк не создан: сервер сгенерирует его сам (генератор: ").append(s.world.level.generatorName()).append(")");
        else if (h != null) sb.append("в воздухе ").append(h.x).append(' ').append(h.y).append(' ').append(h.z);
        int[] b = ed().selection.bounds();
        if (b != null) sb.append("  |  выделение ").append(b[3] - b[0] + 1).append('×').append(b[4] - b[1] + 1).append('×').append(b[5] - b[2] + 1).append(" = ").append(selCountText());
        g.text(g.ellipsize(sb.toString(), W / 2 + 120), 6, y + 3, Gui.TEXT);
        Runtime rt = Runtime.getRuntime();
        String right = String.format("камера %d %d %d  |  скорость %d  |  %d FPS  |  чанков %d  |  %s / %s",
                (int) Math.floor(s.cam.x), (int) Math.floor(s.cam.y), (int) Math.floor(s.cam.z), Math.round(s.cam.speed), net.minecraft.client.MinecraftClient.getInstance().getCurrentFps(),
                s.world.loadedCount(), Task.bytes(rt.totalMemory() - rt.freeMemory()), Task.bytes(rt.maxMemory()));
        g.textR(right, W - 6, y + 3, Gui.TEXT2);
    }

    long selCountCache = -1; int selCountVer = -1;
    String selCountText() {
        if (selCountVer != ed().selection.version) {
            selCountVer = ed().selection.version;
            int[] b = ed().selection.bounds();
            long vol = b == null ? 0 : (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
            if (vol <= 2_000_000) selCountCache = ed().selection.count(); else selCountCache = -vol;
        }
        return selCountCache >= 0 ? String.format("%,d", selCountCache) : "≤ " + String.format("%,d", -selCountCache);
    }

    String toolHint() {
        return switch (s.tool) {
            case INSPECT -> "ЛКМ — показать карточку блока • СКМ — пипетка";
            case PENCIL -> "ЛКМ — поставить • Ctrl+ЛКМ — удалить • Alt+ЛКМ — заменить • R — повернуть • Alt+R — отразить • X/Y/Z — фиксация оси";
            case ERASER -> "ЛКМ — удалить (протяжка) • Ctrl+колесо — размер";
            case BUCKET -> "ЛКМ — залить связную область активным блоком/смесью";
            case SELECT -> s.selPhase == 2 ? "Двигай мышь вверх/вниз — высота • клик — зафиксировать" : "ЛКМ и тяни — новая фигура • Ctrl — добавить • Alt — вычесть • ручки — размер • стрелки — сдвиг";
            case BRUSH -> "ЛКМ — мазок • Ctrl+ЛКМ — обратное • Ctrl+колесо — размер • Alt+колесо — сила";
            case LINE -> "Клик — точка • тяни точку — переместить • Ctrl+клик — удалить • Enter — применить";
            case MOVE -> "Тяни стрелки • R — повернуть • Alt+R — отразить • стрелки/PgUp/PgDn — сдвиг • Enter — применить";
            case TERRAIN -> "ЛКМ — поднять/сгладить… • Ctrl+ЛКМ — опустить";
            case BIOME -> "ЛКМ — красить биом • Alt+СКМ — пипетка биома";
            case RULER -> "Клик-клик — измерение • Esc — сбросить";
            case OBJECTS -> "ЛКМ — поставить • R — повернуть • Ctrl+колесо — масштаб";
        };
    }

    // ================== HUD вьюпорта ==================
    void drawViewportHud() {
        int vx = s.vx, vy = s.vy, vw = s.vw, vh = s.vh;
        // подсказка инструмента
        if (!s.hideUi) {
            String hint = s.pasting ? "Вставка: ЛКМ — поставить (штамп) • R — повернуть • Alt+R — отразить • Ctrl+Alt+R — верх/низ • Q — привязка • Ctrl+колесо — высота • Enter — вставить и выйти • Esc — отмена" : toolHint();
            g.rect(vx, vy, vw, 12, 0x60000000);
            g.text(g.ellipsize(hint, vw - 10), vx + 5, vy + 2, Gui.TEXT2);
        }
        // мини-гизмо осей
        drawAxisGizmo(vx + vw - 34, vy + 34);
        // прогресс операции
        Editor.Op op = ed().running;
        if (op != null) {
            int w = Math.min(360, vw - 40), x = vx + (vw - w) / 2, y = vy + 18;
            g.rect(x, y, w, 30, 0xE0202226);
            g.text(g.ellipsize(op.name, w - 90), x + 6, y + 4, Gui.TEXT);
            g.textR(Math.round(op.progress * 100) + "%  " + op.eta(), x + w - 70, y + 4, Gui.TEXT2);
            g.rect(x + 6, y + 18, w - 82, 6, Gui.FIELD);
            g.rect(x + 6, y + 18, (int) ((w - 82) * Math.min(1, op.progress)), 6, Gui.ACCENT);
            boolean ov = g.overlayLayer; g.overlayLayer = true;
            if (g.button("opcancel", x + w - 70, y + 12, 64, 14, "Отмена", "Отменить и откатить сделанное")) op.cancelled = true;
            g.overlayLayer = ov;
            g.registerOverlay(x, y, w, 30);
        }
        // крупная скорость
        if (s.bigSpeedText != null && System.currentTimeMillis() < s.bigSpeedUntil) {
            g.ctx.getMatrices().push();
            g.ctx.getMatrices().translate(vx + vw / 2f, vy + vh / 2f - 10, 0);
            g.ctx.getMatrices().scale(2, 2, 1);
            g.textC(s.bigSpeedText, 0, 0, 0xFFFFFFFF);
            g.ctx.getMatrices().pop();
        }
        // размеры при создании выделения
        if (s.creating != null) {
            String txt = s.creating.sizeText();
            g.rect(g.mx + 14, g.my + 10, g.width(txt) + 8, 12, 0xC0000000);
            g.text(txt, g.mx + 18, g.my + 12, 0xFFFFFFFF);
        }
        // линейка
        if (!s.rulerPts.isEmpty()) {
            int[] a = s.rulerPts.get(0);
            int[] b = s.rulerPts.size() > 1 ? s.rulerPts.get(1) : (s.hover != null ? new int[]{s.hover.x, s.hover.y, s.hover.z} : a);
            int dx = Math.abs(b[0] - a[0]) + 1, dy = Math.abs(b[1] - a[1]) + 1, dz = Math.abs(b[2] - a[2]) + 1;
            double d = Math.sqrt(Math.pow(b[0] - a[0], 2) + Math.pow(b[1] - a[1], 2) + Math.pow(b[2] - a[2], 2));
            String txt = String.format("Расстояние %.2f  •  по осям %d × %d × %d  •  площадь XZ %d  •  объём %,d", d, dx, dy, dz, dx * dz, (long) dx * dy * dz);
            int w = g.width(txt) + 10;
            g.rect(vx + (vw - w) / 2, vy + vh - 52, w, 14, 0xD0000000);
            g.text(txt, vx + (vw - w) / 2 + 5, vy + vh - 49, Gui.WARN);
        }
        // предупреждение об опоре
        if (s.tool == EditorScreen.Tool.PENCIL && s.overViewport && s.hover != null && !g.ctrl() && !g.alt()) {
            int[] p = s.placePos();
            if (p != null && Placement.needsSupport(ed().activeState() >> 4) && p[1] > 0 && s.world.getState(p[0], p[1] - 1, p[2]) == 0 && (s.hover.face == 1 || s.hover.air)) {
                String w = "В игре этот блок может отвалиться при обновлении соседей";
                g.rect(vx + (vw - g.width(w)) / 2 - 4, vy + vh - 36, g.width(w) + 8, 12, 0xC0302000);
                g.textC(w, vx + vw / 2, vy + vh - 34, Gui.WARN);
            }
        }
        // инспектор
        if (s.inspect != null) drawInspector(vx + 8, vy + 18);
        // отладка
        if (s.debug) {
            String[] lines = {
                    "Меши: " + s.vr.meshCount() + "  в очереди: " + s.vr.queued + "  вызовов отрисовки: " + s.vr.lastDrawCalls,
                    "Чанков в памяти: " + s.world.loadedCount() + "  регионов: " + s.world.regionCount(),
                    "FPS: " + net.minecraft.client.MinecraftClient.getInstance().getCurrentFps() + "  камера yaw " + Math.round(s.cam.yaw) + " pitch " + Math.round(s.cam.pitch),
                    "Предупреждения мира: " + s.world.warnings.size()
            };
            for (int i = 0; i < lines.length; i++) { g.rect(vx + 6, vy + vh - 64 + i * 11, g.width(lines[i]) + 6, 11, 0xA0000000); g.text(lines[i], vx + 9, vy + vh - 62 + i * 11, 0xFFFFFF80); }
        }
        // срез
        if (s.sliceOn) { String st = "Срез Y ≤ " + s.sliceY; g.rect(vx + 6, vy + 16, g.width(st) + 8, 12, 0xA0000000); g.text(st, vx + 10, vy + 18, Gui.WARN); }
    }

    void drawAxisGizmo(int cx, int cy) {
        double[][] axes = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        int[] cols = {0xFFE04848, 0xFF48D048, 0xFF4878E0};
        String[] names = {"X", "Y", "Z"};
        g.rect(cx - 26, cy - 26, 52, 52, 0x40000000);
        double ry = Math.toRadians(s.cam.yaw + 180), rp = Math.toRadians(s.cam.pitch);
        for (int i = 0; i < 3; i++) {
            double x = axes[i][0], y = axes[i][1], z = axes[i][2];
            double x1 = x * Math.cos(ry) - z * Math.sin(ry), z1 = x * Math.sin(ry) + z * Math.cos(ry);
            double y2 = y * Math.cos(rp) - z1 * Math.sin(rp);
            int ex = cx + (int) (x1 * 20), ey = cy - (int) (y2 * 20);
            int steps = 20;
            for (int k = 0; k <= steps; k++) g.rect(cx + (ex - cx) * k / steps, cy + (ey - cy) * k / steps, 2, 2, cols[i]);
            boolean hv = g.hover(ex - 6, ey - 6, 12, 12);
            g.rect(ex - 4, ey - 4, 9, 9, hv ? 0xFFFFFFFF : cols[i]);
            g.text(names[i], ex - 2, ey - 3, 0xFF000000);
            if (g.clicked(ex - 6, ey - 6, 12, 12, 0)) {
                s.cam.ortho = true;
                if (i == 1) { s.cam.pitch = 90; } else if (i == 0) { s.cam.pitch = 0; s.cam.yaw = 90; } else { s.cam.pitch = 0; s.cam.yaw = 180; }
            }
        }
        if (g.clicked(cx - 4, cy - 4, 8, 8, 0)) s.cam.ortho = false;
        g.registerOverlay(cx - 26, cy - 26, 52, 52);
        if (g.hover(cx - 26, cy - 26, 52, 52)) g.tooltip("axisg", "Клик по оси — вид вдоль неё (орто)\nКлик в центр — перспектива", "NumPad5");
    }

    void drawInspector(int x, int y) {
        int[] p = s.inspect;
        int v = s.world.getV(p[0], p[1], p[2]);
        List<String> lines = new ArrayList<>();
        lines.add("Блок " + p[0] + ", " + p[1] + ", " + p[2]);
        if (v < 0) lines.add("Заглушка импорта: " + s.world.stubName(v));
        else {
            int id = v >> 4, meta = v & 15;
            PeBlocks.Resolved r = t().resolve(id, meta);
            lines.add(r == null ? "Неизвестный id " + id : t().displayName(v));
            if (r != null) lines.add(r.block().nameEn);
            lines.add("id:meta " + id + ":" + meta + "   мета (двоичн.) " + String.format("%4s", Integer.toBinaryString(meta)).replace(' ', '0') + (r != null && r.nonStandard() ? "   ⚠ нестандартная мета" : ""));
            lines.add("Java: " + (JavaStates.isReady() ? net.minecraft.command.argument.BlockArgumentParser.stringifyBlockState(JavaStates.BASE[v & 4095]) : "?"));
        }
        var c = s.world.getChunk(p[0] >> 4, p[2] >> 4);
        if (c != null) {
            lines.add("Свет: небо " + c.getSky(p[0] & 15, p[1], p[2] & 15) + ", блоки " + c.getBlockLight(p[0] & 15, p[1], p[2] & 15));
            int bc = c.getBiomeColor(p[0] & 15, p[2] & 15);
            lines.add("Биом " + ((bc >>> 24) & 0xFF) + " (" + Biomes.name((bc >>> 24) & 0xFF) + "), трава " + String.format("#%06X", bc & 0xFFFFFF));
            lines.add("Высота колонки: " + c.heightMap[(p[2] & 15) << 4 | (p[0] & 15)]);
        }
        Tag.CompoundTag tile = s.world.getTile(p[0], p[1], p[2]);
        String nbt = tile == null ? null : Tag.pretty(tile, 0);
        if (nbt != null) { lines.add("Тайл:"); for (String l : nbt.split("\n")) if (lines.size() < 30) lines.add("  " + l); }
        int w = 0;
        for (String l : lines) w = Math.max(w, g.width(l));
        w = Math.min(w + 12, s.vw - 20);
        int h = lines.size() * 10 + 26;
        g.rect(x, y, w, h, 0xE81E1F22);
        g.frame(x, y, w, h, Gui.SEP);
        for (int i = 0; i < lines.size(); i++) g.text(g.ellipsize(lines.get(i), w - 10), x + 6, y + 5 + i * 10, i == 0 ? Gui.ACCENT : Gui.TEXT);
        boolean ov = g.overlayLayer; g.overlayLayer = true;
        if (nbt != null && g.button("inspcopy", x + 6, y + h - 18, 120, 14, "Скопировать как текст", null)) g.mc.keyboard.setClipboard(nbt);
        if (g.button("inspclose", x + w - 50, y + h - 18, 44, 14, "Закрыть", null)) s.inspect = null;
        g.overlayLayer = ov;
        g.registerOverlay(x, y, w, h);
    }

    // ================== хотбар ==================
    void drawHotbar() {
        int cell = 22;
        int w = cell * 9 + 4;
        int x = s.vx + (s.vw - w) / 2, y = s.vy + s.vh - cell - 6;
        g.rect(x, y, w, cell + 4, 0xC0101113);
        for (int i = 0; i < 9; i++) {
            int cx = x + 2 + i * cell;
            boolean act = i == ed().slot;
            boolean hv = g.hover(cx, y + 2, cell, cell);
            if (act) g.frame(cx, y + 2, cell, cell, Gui.ACCENT);
            else if (hv) g.frame(cx, y + 2, cell, cell, 0xFF606368);
            Pattern p = ed().hotbar[i];
            drawIcon(p.first(), cx + 3, y + 5, 16);
            if (p.kind != Pattern.Kind.SINGLE && p.entries.size() > 1) g.text("✦", cx + cell - 7, y + 3, Gui.WARN);
            g.text(String.valueOf(i + 1), cx + 2, y + 3, 0xA0FFFFFF);
            if (hv) {
                g.tooltip("hb" + i, p.describe(), String.valueOf(i + 1));
                if (g.clicked(cx, y + 2, cell, cell, 0)) { ed().slot = i; dragPattern = p; s.manualOrient = false; }
                if (g.clicked(cx, y + 2, cell, cell, 1)) { ed().hotbar[i] = Pattern.single(0); ed().saveHotbar(); }
                if (g.released[0] && dragState != null) { ed().hotbar[i] = Pattern.single(dragState); ed().saveHotbar(); dragState = null; }
                if (g.released[0] && dragPattern != null && dragPattern != p) { Pattern tmp = ed().hotbar[i]; int from = Arrays.asList(ed().hotbar).indexOf(dragPattern); ed().hotbar[i] = dragPattern; if (from >= 0) ed().hotbar[from] = tmp; ed().slot = i; ed().saveHotbar(); }
            }
        }
        g.registerOverlay(x, y, w, cell + 4);
        if (g.released[0]) { dragState = null; dragPattern = null; }
    }

    void drawDrag() {
        if (dragState != null && g.down[0]) drawIcon(dragState, g.mx - 8, g.my - 8, 16);
    }

    // ================== тур ==================
    static final String[][] TOUR = {
            {"Это вьюпорт — здесь мир", "Зажми ПКМ и двигай мышь, чтобы осмотреться. WASD — полёт по взгляду, Пробел/Shift — вверх/вниз. Колесо с зажатой ПКМ — скорость."},
            {"Слева — инструменты", "Карандаш (P) ставит блоки, Ctrl+ЛКМ удаляет. Под кнопками — параметры активного инструмента."},
            {"Справа — палитра блоков", "Только блоки MCPE 0.14.3. Поиск по имени, id или id:meta. Клик — в активную ячейку хотбара (1–9)."},
            {"Выделение (M)", "Тяни ЛКМ — основание, затем веди мышь вверх — высота, клик — готово. Ручки на гранях меняют размер, Ctrl+F — заполнить, Ctrl+H — заменить."},
            {"Сохранение", "Ctrl+S — сохранить: ZIP, новый мир на сервер или перезапись (с резервной копией). Справка — F1."}
    };
    public void startTour() { tourStep = 0; }
    void drawTour() {
        String[] st = TOUR[tourStep];
        int w = 300;
        List<String> lines = WbScreen.wrap(g, st[1], w - 16);
        int h = 30 + lines.size() * 10 + 22;
        int x = tourStep == 1 ? s.leftW + 20 : tourStep == 2 ? s.W - s.rightW - w - 20 : s.vx + (s.vw - w) / 2;
        int y = tourStep == 0 ? s.vy + s.vh / 2 - h / 2 : s.vy + 40;
        g.ctx.getMatrices().push(); g.ctx.getMatrices().translate(0, 0, 260);
        boolean ov = g.overlayLayer; g.overlayLayer = true;
        g.rect(x, y, w, h, 0xF0203028); g.frame(x, y, w, h, Gui.ACCENT);
        g.text((tourStep + 1) + "/" + TOUR.length + "  " + st[0], x + 8, y + 8, Gui.ACCENT);
        for (int i = 0; i < lines.size(); i++) g.text(lines.get(i), x + 8, y + 24 + i * 10, Gui.TEXT);
        if (g.button("tourskip", x + 8, y + h - 20, 80, 14, "Пропустить", null)) { tourStep = -1; WbConfig.get().tourDone = true; WbConfig.get().save(); }
        else if (g.primary("tournext", x + w - 88, y + h - 20, 80, 14, tourStep == TOUR.length - 1 ? "Готово" : "Далее", null, true)) {
            tourStep++;
            if (tourStep >= TOUR.length) { tourStep = -1; WbConfig.get().tourDone = true; WbConfig.get().save(); }
        }
        g.overlayLayer = ov;
        g.registerOverlay(x, y, w, h);
        g.ctx.getMatrices().pop();
    }

    // ================== действия ==================
    public void actionFill() {
        if (ed().selection.isEmpty()) { s.toast("Сначала выдели область"); return; }
        Pattern p = ed().active().copy();
        Selection sel = ed().selection.copy();
        long n = sel.count();
        if (n > WbConfig.get().maxOpBlocks) { s.toast("Слишком много блоков: " + n + " (предел в настройках)"); return; }
        Runnable go = () -> ed().run("Заполнение: " + String.format("%,d", n) + " блоков → " + p.describe(), "fill", op -> { Ops.fill(s.world, sel, p, false, null, op.cs, op); return null; }, s::opDone);
        if (n > 5_000_000) s.dialogs.push(new WbScreen.ChoiceDialog("Большая операция", String.format("Будет изменено до %,d блоков. Продолжить?", n), new String[]{"Продолжить", "Отмена"}, i -> { if (i == 0) go.run(); }));
        else go.run();
    }

    public void actionClear(boolean ask) {
        if (ed().selection.isEmpty()) return;
        dlg.clearDialog();
    }

    public void openReplace() { if (ed().selection.isEmpty()) { s.toast("Сначала выдели область"); return; } dlg.replace(null); }
    public void openStats() { dlg.stats(); }
    public void openSave() { dlg.save(); }
    public void quickSave() { dlg.quickSave(); }
    public void openGoto() { dlg.gotoDialog(); }
    public void openHelp() { dlg.help(); }
    public void askExit() { dlg.exit(); }
    public void importSchematic(java.nio.file.Path p) { dlg.importSchematic(p); }
    public void exportSchematic() { dlg.exportSchematic(); }

    static String fmtNum(long v) { return String.format("%,d", v); }
    static int stateOr(int v) { return Math.max(0, v); }
    static boolean isKey(int k, int code) { return k == code; }
    static int glfwEnter() { return GLFW.GLFW_KEY_ENTER; }
    static Rotation.Op mx() { return Rotation.Op.MIRROR_X; }
    static boolean inH(int y) { return PeWorld.inHeight(y); }
}
