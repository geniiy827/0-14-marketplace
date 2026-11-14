package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.edit.*;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.remote.RemoteFs;
import com.geniiy.worldbuilder.remote.WorldSource;
import com.geniiy.worldbuilder.schematic.SchematicIO;
import com.geniiy.worldbuilder.session.SaveManager;
import com.geniiy.worldbuilder.session.WorldTemplates;
import org.lwjgl.glfw.GLFW;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Диалоги редактора: сохранение, замена, маски, статистика, импорт, справка и др. */
class EditorDialogs {
    final EditorScreen s; final EditorUi ui;
    EditorDialogs(EditorScreen s, EditorUi ui) { this.s = s; this.ui = ui; }

    void push(WbScreen.Dialog d) { s.dialogs.push(d); }
    PeBlocks t() { return PeBlocks.get(); }

    // ---------- простые параметры ----------
    void intParam(String title, String label, int def, int min, int max, IntConsumer ok) {
        int[] v = {def};
        push(new WbScreen.Dialog(title) {
            { w = 300; h = 90; }
            public boolean render(Gui g, int x, int y) {
                v[0] = (int) g.number("ip", x + 10, y + 10, w - 20, 16, label, v[0], min, max, 1, true);
                if (g.button("ipc", x + w - 170, y + h - 18 - 24, 76, 18, "Отмена", null)) return true;
                if (g.primary("ipo", x + w - 88, y + h - 18 - 24, 78, 18, "Применить", null, true)) { ok.accept(v[0]); return true; }
                return false;
            }
            public boolean onEnter() { ok.accept(v[0]); return true; }
        });
    }

    void rename(Clipboard c) { textInput("Переименовать буфер", c.name, n -> c.name = n); }
    void renameShape(Shape sh) { textInput("Переименовать фигуру", sh.name, n -> sh.name = n); }
    void saveSelection() { textInput("Сохранить выделение как…", "Выделение " + (s.ed.savedSelections.size() + 1), n -> s.ed.savedSelections.put(n, s.ed.selection.copy())); }
    void savePattern(Pattern p) { textInput("Сохранить смесь в библиотеку", p.name.isEmpty() ? "Смесь " + (s.ed.patternLibrary.size() + 1) : p.name, n -> { Pattern c = p.copy(); c.name = n; s.ed.patternLibrary.put(n, c); }); }

    void textInput(String title, String def, Consumer<String> ok) {
        String[] v = {def};
        push(new WbScreen.Dialog(title) {
            { w = 320; h = 90; }
            public boolean render(Gui g, int x, int y) {
                v[0] = g.field("ti", x + 10, y + 10, w - 20, 16, v[0], null, false, false);
                if (g.focus == null) g.focus = "ti";
                if (g.keyPressed(GLFW.GLFW_KEY_ENTER)) { g.focus = null; ok.accept(v[0]); return true; }
                if (g.button("tic", x + w - 170, y + h - 18 - 24, 76, 18, "Отмена", null)) { g.focus = null; return true; }
                if (g.primary("tio", x + w - 88, y + h - 18 - 24, 78, 18, "ОК", null, true)) { g.focus = null; ok.accept(v[0]); return true; }
                return false;
            }
        });
    }

    void patternLibrary() {
        push(new WbScreen.Dialog("Библиотека смесей") {
            { w = 320; h = 220; }
            public boolean render(Gui g, int x, int y) {
                int yy = y + 8;
                if (s.ed.patternLibrary.isEmpty()) g.text("Пусто. Сохрани смесь кнопкой «В библиотеку».", x + 10, yy, Gui.TEXT2);
                for (var e : new ArrayList<>(s.ed.patternLibrary.entrySet())) {
                    if (g.button("pl" + e.getKey(), x + 10, yy, w - 44, 16, e.getKey() + " — " + e.getValue().describe(), "Положить в активную ячейку хотбара")) { s.ed.hotbar[s.ed.slot] = e.getValue().copy(); return true; }
                    if (g.button("pld" + e.getKey(), x + w - 30, yy, 20, 16, "×", null)) s.ed.patternLibrary.remove(e.getKey());
                    yy += 18;
                }
                return g.button("plc", x + w - 90, y + h - 18 - 24, 80, 18, "Закрыть", null);
            }
        });
    }

    // ---------- маска ----------
    void maskEditor(Mask initial, Consumer<Mask> ok) {
        Mask m = initial.copy();
        push(new WbScreen.Dialog("Маска — к каким блокам применять") {
            { w = 440; h = 300; }
            double off;
            public boolean render(Gui g, int x, int y) {
                int yy = y + 8;
                m.or = g.segmented("mor", x + 10, yy, 200, 14, new String[]{"Все условия (И)", "Любое (ИЛИ)"}, m.or ? 1 : 0, null) == 1;
                if (g.button("madd", x + w - 130, yy, 120, 14, "+ Условие", null)) { Mask.Cond c = new Mask.Cond(); c.kind = Mask.Kind.BLOCKS; m.conds.add(c); }
                yy += 20;
                int listH = h - 18 - 70;
                g.scissor(x, yy, w, listH);
                int cy = yy - (int) off;
                for (int i = 0; i < m.conds.size(); i++) {
                    Mask.Cond c = m.conds.get(i);
                    g.rect(x + 8, cy, w - 16, 2, Gui.SEP);
                    cy += 4;
                    c.not = g.check("mnot" + i, x + 10, cy + 1, "НЕ", c.not, null);
                    Mask.Kind[] ks = Mask.Kind.values();
                    if (g.button("mkind" + i, x + 50, cy, 140, 14, c.kind.ru + " ▾", "Клик — следующий тип")) c.kind = ks[(c.kind.ordinal() + 1) % ks.length];
                    if (g.button("mdel" + i, x + w - 30, cy, 20, 14, "×", null)) { m.conds.remove(i); break; }
                    cy += 18;
                    switch (c.kind) {
                        case BLOCKS -> {
                            for (int j = 0; j < c.blocks.size(); j++) {
                                int[] b = c.blocks.get(j);
                                g.text(g.ellipsize(t().displayName(b[0]) + " (" + (b[0] >> 4) + ":" + (b[0] & 15) + ")", w - 180), x + 20, cy + 3, Gui.TEXT);
                                b[1] = g.check("many" + i + "_" + j, x + w - 150, cy + 1, "любая мета", b[1] != 0, null) ? 1 : 0;
                                if (g.button("mbd" + i + "_" + j, x + w - 30, cy, 20, 14, "×", null)) { c.blocks.remove(j); break; }
                                cy += 16;
                            }
                            if (g.button("mfromhb" + i, x + 20, cy, 150, 14, "+ из активной ячейки", null)) c.blocks.add(new int[]{s.ed.activeState(), 0});
                            if (g.button("mfromhv" + i, x + 174, cy, 150, 14, "+ блок под курсором", "Наведи на блок во вьюпорте перед открытием") && s.hover != null && !s.hover.air) c.blocks.add(new int[]{Math.max(0, s.world.getV(s.hover.x, s.hover.y, s.hover.z)), 0});
                            cy += 18;
                        }
                        case YRANGE -> { c.yMin = (int) g.number("my1" + i, x + 20, cy, 120, 14, "от", c.yMin, 0, 127, 1, true); c.yMax = (int) g.number("my2" + i, x + 150, cy, 120, 14, "до", c.yMax, 0, 127, 1, true); cy += 18; }
                        case BIOME -> { c.biome = (int) g.number("mbi" + i, x + 20, cy, 160, 14, "ID биома", c.biome, 0, 255, 1, true); g.text(Biomes.name(c.biome), x + 190, cy + 3, Gui.TEXT2); cy += 18; }
                        case STUB -> {
                            List<String> pal = s.world.stubPalette;
                            String cur = c.stub == -1 ? "любая заглушка" : s.world.stubName(c.stub);
                            if (g.button("mstub" + i, x + 20, cy, w - 40, 14, cur + " ▾", null)) { int idx = c.stub == -1 ? 0 : (-c.stub - 1) + 1; c.stub = idx >= pal.size() ? -1 : -(idx + 1); }
                            cy += 18;
                        }
                        default -> {}
                    }
                }
                int contentH = cy + (int) off - yy;
                g.noScissor();
                off = g.scrollArea("mscroll", x, yy, w, listH, contentH, off);
                if (m.conds.isEmpty()) g.text("Нет условий — действие применяется ко всем блокам.", x + 12, yy + 4, Gui.TEXT2);
                int by = y + h - 18 - 26;
                if (g.button("mlib", x + 10, by, 110, 18, "Библиотека…", null)) maskLibrary(m);
                if (g.button("msave", x + 124, by, 110, 18, "Сохранить как…", null)) textInput("Имя маски", m.name.isEmpty() ? "Маска" : m.name, n -> { Mask c = m.copy(); c.name = n; s.ed.maskLibrary.put(n, c); });
                if (g.button("mcancel", x + w - 170, by, 76, 18, "Отмена", null)) return true;
                if (g.primary("mok", x + w - 88, by, 78, 18, "Готово", null, true)) { ok.accept(m); return true; }
                return false;
            }
        });
    }

    void maskLibrary(Mask target) {
        push(new WbScreen.Dialog("Библиотека масок") {
            { w = 320; h = 200; }
            public boolean render(Gui g, int x, int y) {
                int yy = y + 8;
                if (s.ed.maskLibrary.isEmpty()) g.text("Пусто.", x + 10, yy, Gui.TEXT2);
                for (var e : new ArrayList<>(s.ed.maskLibrary.entrySet())) {
                    if (g.button("ml" + e.getKey(), x + 10, yy, w - 20, 16, e.getKey() + " — " + e.getValue().describe(), null)) {
                        target.conds.clear(); for (Mask.Cond c : e.getValue().conds) target.conds.add(c.copy()); target.or = e.getValue().or; return true;
                    }
                    yy += 18;
                }
                return g.button("mlc", x + w - 90, y + h - 18 - 24, 80, 18, "Закрыть", null);
            }
        });
    }

    // ---------- замена ----------
    void replace(Mask preset) {
        Mask[] what = {preset != null ? preset : (s.hover != null && !s.hover.air ? Mask.blocks(Math.max(0, s.world.getV(s.hover.x, s.hover.y, s.hover.z)), false) : Mask.of(Mask.Kind.NOT_AIR))};
        Pattern[] to = {s.ed.active().copy()};
        boolean[] keep = {true};
        long[] count = {-1};
        int[] cver = {-1};
        Selection sel = s.ed.selection.copy();
        push(new WbScreen.Dialog("Заменить в выделении") {
            { w = 420; h = 200; }
            public boolean render(Gui g, int x, int y) {
                int yy = y + 10;
                g.text("Что заменить:", x + 10, yy + 4, Gui.TEXT2);
                if (g.button("rwhat", x + 100, yy, w - 170, 16, g.ellipsize(what[0].describe(), w - 180), "Маска: список блоков, «не воздух», заглушки…")) maskEditor(what[0], m -> { what[0] = m; cver[0] = -1; });
                if (g.button("rwhatpick", x + w - 66, yy, 56, 16, "Пипетка", "Взять блок под курсором") && s.hover != null && !s.hover.air) { what[0] = Mask.blocks(Math.max(0, s.world.getV(s.hover.x, s.hover.y, s.hover.z)), false); cver[0] = -1; }
                yy += 22;
                g.text("На что:", x + 10, yy + 4, Gui.TEXT2);
                g.text(g.ellipsize(to[0].describe(), w - 180), x + 100, yy + 4, Gui.TEXT);
                if (g.button("rtohb", x + w - 130, yy, 60, 16, "Хотбар", "Активная ячейка (блок или смесь)")) to[0] = s.ed.active().copy();
                if (g.button("rtopick", x + w - 66, yy, 56, 16, "Пипетка", null) && s.hover != null && !s.hover.air) to[0] = Pattern.single(Math.max(0, s.world.getV(s.hover.x, s.hover.y, s.hover.z)));
                yy += 22;
                keep[0] = g.check("rkeep", x + 10, yy, "Сохранять ориентацию (ступени → ступени, брёвна → брёвна…)", keep[0], "Переносятся биты направления и половины");
                yy += 20;
                int hash = what[0].describe().hashCode();
                if (cver[0] != hash) {
                    cver[0] = hash; count[0] = -1;
                    Mask mm = what[0].copy();
                    Task.IO.submit(() -> { long[] c = {0}; sel.forEach((xx, yy2, zz) -> { if (mm.test(s.world, xx, yy2, zz, s.world.getV(xx, yy2, zz))) c[0]++; }); count[0] = c[0]; });
                }
                g.text(count[0] < 0 ? "Считаю, сколько блоков будет заменено…" : String.format("Будет заменено: %,d блоков", count[0]), x + 10, yy, count[0] == 0 ? Gui.WARN : Gui.TEXT2);
                int by = y + h - 18 - 26;
                if (g.button("rcancel", x + w - 170, by, 76, 18, "Отмена", null)) return true;
                if (g.primary("rok", x + w - 88, by, 78, 18, "Заменить", null, true)) { go(); return true; }
                return false;
            }
            void go() {
                Mask m = what[0]; Pattern p = to[0]; boolean k = keep[0];
                s.ed.run("Замена: " + m.describe() + " → " + p.describe(), "replace", op -> { Ops.replace(s.world, sel, m, p, k, op.cs, op); return String.format("Заменено %,d блоков", op.cs.changedCount()); }, s::opDone);
            }
            public boolean onEnter() { go(); return true; }
        });
    }

    void clearDialog() {
        boolean[] ent = {false}, liq = {false};
        Selection sel = s.ed.selection.copy();
        push(new WbScreen.Dialog("Очистить выделение") {
            { w = 340; h = 120; }
            public boolean render(Gui g, int x, int y) {
                ent[0] = g.check("cent", x + 10, y + 10, "Также удалить сущности в выделении", ent[0], "Удаление сущностей не отменяется");
                liq[0] = g.check("cliq", x + 10, y + 28, "Оставить жидкости", liq[0], null);
                if (g.button("cc", x + w - 170, y + h - 18 - 26, 76, 18, "Отмена", null)) return true;
                if (g.primary("co", x + w - 88, y + h - 18 - 26, 78, 18, "Очистить", null, true)) { go(); return true; }
                return false;
            }
            void go() { boolean e = ent[0], l = liq[0]; s.ed.run("Очистка выделения", "clear", op -> { Ops.clear(s.world, sel, l, e, op.cs, op); return String.format("Очищено %,d блоков", op.cs.changedCount()); }, s::opDone); }
            public boolean onEnter() { go(); return true; }
        });
    }

    void decorate(Selection sel) {
        Pattern plants = new Pattern();
        plants.kind = Pattern.Kind.RANDOM;
        plants.entries.add(new int[]{31 << 4 | 1, 60}); plants.entries.add(new int[]{37 << 4, 10}); plants.entries.add(new int[]{38 << 4, 10});
        plants.entries.add(new int[]{31 << 4 | 2, 10}); plants.entries.add(new int[]{39 << 4, 3}); plants.entries.add(new int[]{32, 7});
        plants.entries.get(5)[0] = 32 << 4;
        double[] dens = {15};
        push(new WbScreen.Dialog("Декорировать поверхность") {
            { w = 380; h = 230; }
            public boolean render(Gui g, int x, int y) {
                int yy = y + 8;
                for (int i = 0; i < plants.entries.size(); i++) {
                    int[] e = plants.entries.get(i);
                    ui.drawIcon(e[0], x + 10, yy, 16);
                    g.text(g.ellipsize(t().displayName(e[0]), w - 150), x + 30, yy + 4, Gui.TEXT);
                    e[1] = (int) g.number("dw" + i, x + w - 110, yy, 70, 16, "", e[1], 0, 1000, 1, true);
                    if (g.button("dd" + i, x + w - 34, yy, 24, 16, "×", null) && plants.entries.size() > 1) { plants.entries.remove(i); break; }
                    yy += 18;
                }
                if (g.button("dadd", x + 10, yy, 180, 16, "+ блок из активной ячейки", null)) plants.entries.add(new int[]{s.ed.activeState(), 10});
                yy += 22;
                dens[0] = g.number("dden", x + 10, yy, 200, 16, "Плотность %", dens[0], 0.1, 100, 1, false);
                int by = y + h - 18 - 26;
                if (g.button("dc", x + w - 170, by, 76, 18, "Отмена", null)) return true;
                if (g.primary("do", x + w - 88, by, 78, 18, "Применить", null, true)) {
                    double d = dens[0];
                    s.ed.run("Декорирование", "decorate", op -> { Ops.decorate(s.world, sel, plants, d, op.cs, op); return String.format("Посажено %,d растений", op.cs.changedCount()); }, s::opDone);
                    return true;
                }
                return false;
            }
        });
    }

    void rotate(Selection sel) {
        s.dialogs.push(new WbScreen.ChoiceDialog("Повернуть содержимое", "Поворот вокруг вертикальной оси Y по центру выделения. Меты блоков пересчитываются (ступени, двери, факелы смотрят правильно).",
                new String[]{"90°", "180°", "270°", "Отмена"}, i -> {
            if (i < 0 || i > 2) return;
            int q = i + 1;
            s.ed.run("Поворот на " + q * 90 + "°", "rotate", op -> { Selection ns = Ops.transform(s.world, sel, q, null, op.cs, op); net.minecraft.client.MinecraftClient.getInstance().execute(() -> { s.ed.selection = ns; s.ed.selection.changed(); }); return "Повёрнуто на " + q * 90 + "°"; }, s::opDone);
        }));
    }

    void mirror(Selection sel) {
        s.dialogs.push(new WbScreen.ChoiceDialog("Отразить содержимое", "Отражение относительно центра выделения с пересчётом мет. У дверей меняются петли.",
                new String[]{"По X (восток↔запад)", "По Z (север↔юг)", "По Y (верх↔низ)", "Отмена"}, i -> {
            if (i < 0 || i > 2) return;
            Rotation.Op op0 = i == 0 ? Rotation.Op.MIRROR_X : i == 1 ? Rotation.Op.MIRROR_Z : Rotation.Op.MIRROR_Y;
            if (i == 2) s.toast("Внимание: факелы на полу и потолке при отражении по Y не меняются");
            s.ed.run("Отражение", "mirror", op -> { Ops.transform(s.world, sel, 0, op0, op.cs, op); return "Отражено"; }, s::opDone);
        }));
    }

    void stack(Selection sel) {
        int look = Placement.lookDir(s.cam.yaw);
        int[] dir = {s.cam.pitch > 50 ? 4 : s.cam.pitch < -50 ? 5 : look}; // 2N 3S 4W 5E, 4/5 перекрываем ниже
        int[] p = {3, 0};
        boolean[] air = {true};
        String[] names = {"Вниз", "Вверх", "Север", "Юг", "Запад", "Восток"};
        int[] d6 = {s.cam.pitch > 50 ? 0 : s.cam.pitch < -50 ? 1 : look};
        push(new WbScreen.Dialog("Размножить (Stack)") {
            { w = 380; h = 150; }
            public boolean render(Gui g, int x, int y) {
                p[0] = (int) g.number("stn", x + 10, y + 10, 170, 16, "Сколько раз", p[0], 1, 1000, 1, true);
                p[1] = (int) g.number("stg", x + 190, y + 10, 170, 16, "Зазор", p[1], 0, 1000, 1, true);
                d6[0] = g.segmented("std", x + 10, y + 32, w - 20, 16, names, d6[0], null);
                air[0] = g.check("sta", x + 10, y + 54, "Пропускать воздух", air[0], null);
                int by = y + h - 18 - 26;
                if (g.button("stc", x + w - 170, by, 76, 18, "Отмена", null)) return true;
                if (g.primary("sto", x + w - 88, by, 78, 18, "Применить", null, true)) {
                    int f = d6[0];
                    int dx = f == 5 ? 1 : f == 4 ? -1 : 0, dy = f == 1 ? 1 : f == 0 ? -1 : 0, dz = f == 3 ? 1 : f == 2 ? -1 : 0;
                    int n = p[0], gap = p[1]; boolean a = air[0];
                    s.ed.run("Размножение ×" + n, "stack", op -> { Ops.stack(s.world, sel, n, dx, dy, dz, gap, a, op.cs, op); return "Размножено ×" + n; }, s::opDone);
                    return true;
                }
                return false;
            }
        });
    }

    // ---------- статистика ----------
    void stats() {
        if (s.ed.selection.isEmpty()) return;
        Selection sel = s.ed.selection.copy();
        @SuppressWarnings("unchecked") LinkedHashMap<Integer, Long>[] res = new LinkedHashMap[1];
        Task.IO.submit(() -> res[0] = Ops.stats(s.world, sel));
        push(new WbScreen.Dialog("Статистика выделения") {
            { w = 460; h = 300; }
            double off;
            public boolean render(Gui g, int x, int y) {
                if (res[0] == null) { g.textC("Считаю…", x + w / 2, y + 40, Gui.TEXT2); return g.button("stc", x + w - 90, y + h - 18 - 24, 80, 18, "Закрыть", null); }
                long total = 0; for (long v : res[0].values()) total += v;
                int yy = y + 6;
                g.text("Блок", x + 34, yy, Gui.TEXT2); g.text("id:meta", x + w - 190, yy, Gui.TEXT2); g.text("Кол-во", x + w - 120, yy, Gui.TEXT2); g.text("%", x + w - 44, yy, Gui.TEXT2);
                yy += 12;
                int listH = h - 18 - 50;
                List<Map.Entry<Integer, Long>> rows = new ArrayList<>(res[0].entrySet());
                off = g.scrollArea("stl", x + 4, yy, w - 8, listH, rows.size() * 18, off);
                g.scissor(x + 4, yy, w - 8, listH);
                for (int i = 0; i < rows.size(); i++) {
                    int ry = yy + i * 18 - (int) off;
                    int st = rows.get(i).getKey(); long c = rows.get(i).getValue();
                    boolean hv = g.hover(x + 6, ry, w - 12, 18) && g.my >= yy && g.my < yy + listH;
                    if (hv) g.rect(x + 6, ry, w - 12, 18, Gui.BTN_H);
                    if (st >= 0) ui.drawIcon(st, x + 10, ry + 1, 16);
                    g.text(g.ellipsize(st < 0 ? "Заглушка: " + s.world.stubName(st) : t().displayName(st), w - 260), x + 34, ry + 5, Gui.TEXT);
                    g.text(st < 0 ? "—" : (st >> 4) + ":" + (st & 15), x + w - 190, ry + 5, Gui.TEXT2);
                    g.text(String.format("%,d", c), x + w - 120, ry + 5, Gui.TEXT);
                    g.text(String.format("%.1f", 100.0 * c / Math.max(1, total)), x + w - 44, ry + 5, Gui.TEXT2);
                    if (hv) g.tooltip("str" + i, "Клик — заменить такие блоки…", null);
                    if (hv && g.clicked(x + 6, ry, w - 12, 18, 0)) {
                        Mask m = new Mask(); Mask.Cond cnd = new Mask.Cond();
                        if (st < 0) { cnd.kind = Mask.Kind.STUB; cnd.stub = st; } else { cnd.kind = Mask.Kind.BLOCKS; cnd.blocks.add(new int[]{st, 0}); }
                        m.conds.add(cnd);
                        s.dialogs.remove(this);
                        replace(m);
                        g.noScissor();
                        return false;
                    }
                }
                g.noScissor();
                g.text(String.format("Всего: %,d", total), x + 10, y + h - 18 - 20, Gui.TEXT2);
                return g.button("stc", x + w - 90, y + h - 18 - 24, 80, 18, "Закрыть", null);
            }
        });
    }

    // ---------- переход ----------
    void gotoDialog() {
        int[] v = {(int) s.cam.x, -1, (int) s.cam.z};
        push(new WbScreen.Dialog("Перейти к координатам") {
            { w = 340; h = 100; }
            public boolean render(Gui g, int x, int y) {
                int third = (w - 28) / 3;
                v[0] = (int) g.number("gx", x + 10, y + 10, third, 16, "X", v[0], -3e7, 3e7, 1, true);
                v[1] = (int) g.number("gy", x + 14 + third, y + 10, third, 16, "Y", v[1], -1, 255, 1, true);
                v[2] = (int) g.number("gz", x + 18 + 2 * third, y + 10, third, 16, "Z", v[2], -3e7, 3e7, 1, true);
                g.text("Y = −1 — над поверхностью", x + 10, y + 32, Gui.TEXT2);
                if (g.button("gc", x + w - 170, y + h - 18 - 26, 76, 18, "Отмена", null)) return true;
                if (g.primary("go", x + w - 88, y + h - 18 - 26, 78, 18, "Перейти", null, true)) { go(); return true; }
                return false;
            }
            void go() {
                int yy = v[1];
                if (yy < 0) yy = Math.max(s.world.topY(v[0], v[2]), 60) + 12;
                s.cam.flyTo(v[0] + 0.5, yy, v[2] + 0.5, s.cam.yaw, 45, 0.4);
            }
            public boolean onEnter() { go(); return true; }
        });
    }

    // ---------- справка ----------
    void help() {
        String text = """
                КАМЕРА
                Зажать ПКМ над вьюпортом — обзор (курсор скрывается и вернётся в ту же точку)
                W / S — вперёд/назад по взгляду (с наклоном), A / D — влево/вправо
                Пробел / Левый Shift — строго вверх/вниз
                Колесо при зажатой ПКМ — скорость (1–100), двойное W — ускорение ×2
                Колесо — наезд камерой, СКМ (тянуть) — орбита, Shift+СКМ — панорама
                F — показать выделение/блок, Home — к спавну, Ctrl+G — к координатам
                Ctrl+Alt+1…9 — сохранить закладку, Alt+1…9 — перелететь
                NumPad5 — перспектива/орто, NumPad7/1/3 — сверху/спереди/сбоку

                ИНСТРУМЕНТЫ
                V Инспектор, P Карандаш, E Ластик, K Ведро, M Выделение, B Кисть
                L Линия, G Перемещение, T Рельеф, Y Биомы, U Линейка, O Объекты
                ЛКМ — основное, Ctrl+ЛКМ — обратное (удалить), Alt+ЛКМ — заменить
                Клик СКМ — пипетка (Alt — без ориентации)
                R — повернуть, Alt+R — отразить (Ctrl+Alt+R — верх/низ при вставке)

                ПРАВКА
                Ctrl+Z / Ctrl+Y (Ctrl+Shift+Z) — отменить / повторить
                Ctrl+C / Ctrl+X / Ctrl+V — копировать / вырезать / вставить
                Ctrl+F — заполнить, Ctrl+H — заменить, Delete — очистить
                Ctrl+D или Esc — снять выделение
                Стрелки, PgUp/PgDn — сдвиг выделения на 1 (с Ctrl — на 10)
                [ ] или Ctrl+колесо — размер кисти, Alt+колесо — сила / хотбар
                1–9 — ячейка хотбара, Q — точка привязки вставки
                X / Y / Z (пока зажата кнопка) — фиксация оси
                Enter — применить, Esc — отменить текущее

                ВИД И ФАЙЛ
                Tab — скрыть панели, N — срез по высоте (Alt+PgUp/PgDn — уровень)
                F1 — справка, F3 — отладка, Ctrl+S — сохранить, Ctrl+Shift+S — сохранить как…
                Ctrl+I — импорт схематика (или перетащи файл .schem/.schematic в окно)

                КАК ПОЛЬЗОВАТЬСЯ
                1. Выбери блок в палитре справа (он попадёт в активную ячейку хотбара).
                2. Карандашом ставь блоки, выделением — создавай фигуры и заполняй их.
                3. Сохрани: ZIP, новым миром на сервер или перезаписью (с резервной копией).
                4. Перед загрузкой на сервер выключи его в панели, после — включи.""";
        push(new WbScreen.TextDialog("Справка — горячие клавиши (клавиши переназначаются в настройках)", text) {{ w = 520; h = Math.min(s.H - 20, 420); }});
    }

    // ---------- выход ----------
    void exit() {
        if (!s.world.hasUnsavedChanges()) { s.exitEditor(); return; }
        s.dialogs.push(new WbScreen.ChoiceDialog("Выйти из редактора?", "Есть несохранённые изменения в мире «" + s.world.level.levelName() + "».",
                new String[]{"Сохранить…", "Не сохранять", "Отмена"}, i -> {
            if (i == 0) save();
            else if (i == 1) s.exitEditor();
        }));
    }

    // ---------- сохранение ----------
    void quickSave() {
        int mode = WbConfig.get().lastSaveMode;
        if (mode == 2 && s.ed.source.isRemote()) doSave(2, null, null, null);
        else save();
    }

    boolean checkBeforeSave(Runnable then) {
        if (!s.world.stubs.isEmpty()) { stubReplace(true, then); return false; }
        return true;
    }

    void save() {
        if (!checkBeforeSave(this::save)) return;
        WbConfig c = WbConfig.get();
        boolean remote = s.ed.source.isRemote();
        boolean[] opt = {c.backup, false, c.preventPopulation, c.computeLight};
        String[] newName = {s.world.dir.getFileName().toString() + "_2"};
        String[] parent = {remote ? RemoteFs.parent(s.ed.source.remotePath) : ""};
        push(new WbScreen.Dialog("Сохранение мира") {
            { w = 520; h = 300; }
            public boolean render(Gui g, int x, int y) {
                int cw = (w - 40) / 3, ch = 120, cy = y + 10;
                String[][] cards = {{"Скачать ZIP", "Архив с папкой мира. Можно залить через панель хостинга."},
                        {remote ? "Новый мир на сервер" : "Сохранить в папку", remote ? "Загрузить копию в новую папку на сервере." : "Записать мир целиком в папку на диске."},
                        {remote ? "Перезаписать на сервере" : (s.ed.source.offlinePath != null && Files.isDirectory(s.ed.source.offlinePath) ? "Перезаписать папку" : "—"), remote ? "Только изменённые регионы и level.dat." : "Записать изменения в исходную папку."}};
                for (int i = 0; i < 3; i++) {
                    int cx = x + 10 + i * (cw + 10);
                    boolean enabled = i != 2 || remote || (s.ed.source.offlinePath != null && Files.isDirectory(s.ed.source.offlinePath));
                    boolean hv = g.hover(cx, cy, cw, ch) && enabled;
                    g.rrect(cx, cy, cw, ch, hv ? 0xFF2C3A33 : 0xFF26272B);
                    g.frame(cx, cy, cw, ch, hv ? Gui.ACCENT : Gui.SEP);
                    g.text(cards[i][0], cx + 8, cy + 8, enabled ? Gui.ACCENT : 0xFF5F6268);
                    int ly = cy + 24;
                    for (String l : WbScreen.wrap(g, cards[i][1], cw - 16)) { g.text(l, cx + 8, ly, Gui.TEXT2); ly += 10; }
                    if (i == 1 && remote) {
                        g.text("Папка:", cx + 8, cy + ch - 46, Gui.TEXT2);
                        parent[0] = g.field("snp", cx + 8, cy + ch - 36, cw - 16, 14, parent[0], "/worlds", false, false);
                        newName[0] = g.field("snn", cx + 8, cy + ch - 20, cw - 16, 14, newName[0], "имя папки", false, false);
                    }
                    if (enabled && g.clicked(cx, cy, cw, i == 1 && remote ? ch - 50 : ch, 0)) { doSaveFromCard(i, opt, parent[0], newName[0]); return true; }
                }
                int oy = cy + ch + 12;
                if (remote) { opt[0] = g.check("sbk", x + 10, oy, "Сделать резервную копию перед перезаписью", opt[0], "Старые файлы скачиваются в .minecraft/worldbuilder/backups"); oy += 16;
                    if (opt[0]) { opt[1] = g.check("sbks", x + 26, oy, "…и скопировать на сервер в папку <мир>_backup_<дата>", opt[1], null); oy += 16; } }
                opt[2] = g.check("spop", x + 10, oy, "Запретить серверу досыпать руды и деревья в изменённые чанки", opt[2], "Ставит TerrainPopulated = 1"); oy += 16;
                opt[3] = g.check("slight", x + 10, oy, "Пересчитать свет", opt[3], "Выключи, только если на сервере включено chunk-ticking.light-updates: true"); oy += 16;
                return g.button("scancel", x + w - 90, y + h - 18 - 24, 80, 18, "Отмена", null);
            }
        });
    }

    void doSaveFromCard(int i, boolean[] opt, String parent, String name) {
        WbConfig c = WbConfig.get();
        c.backup = opt[0]; c.preventPopulation = opt[2]; c.computeLight = opt[3];
        c.lastSaveMode = i; c.save();
        doSave(i, opt, parent, name);
    }

    void doSave(int mode, boolean[] opt0, String parent, String name) {
        WbConfig c = WbConfig.get();
        boolean[] opt = opt0 != null ? opt0 : new boolean[]{c.backup, false, c.preventPopulation, c.computeLight};
        var rep = s.world.checkConsistency();
        String fixes = (rep.tilesRemoved + rep.tilesCreated) > 0 ? " Исправлено тайлов: удалено " + rep.tilesRemoved + ", создано " + rep.tilesCreated + "." : "";
        if (!rep.brokenDoubles.isEmpty() && opt0 != null) {
            StringBuilder sb = new StringBuilder("Двери или высокие растения без второй половины (" + rep.brokenDoubles.size() + "):\n");
            for (int k = 0; k < Math.min(8, rep.brokenDoubles.size()); k++) { int[] p = rep.brokenDoubles.get(k); sb.append(p[0]).append(", ").append(p[1]).append(", ").append(p[2]).append('\n'); }
            int[] first = rep.brokenDoubles.get(0);
            s.dialogs.push(new WbScreen.ChoiceDialog("Предупреждение", sb.toString(), new String[]{"Сохранить всё равно", "Показать", "Отмена"}, k -> {
                if (k == 0) doSaveNow(mode, opt, parent, name, fixes);
                else if (k == 1) s.cam.flyTo(first[0] + 0.5, first[1] + 4, first[2] - 6, 0, 30, 0.4);
            }));
            return;
        }
        doSaveNow(mode, opt, parent, name, fixes);
    }

    void doSaveNow(int mode, boolean[] opt, String parent, String name, String fixes) {
        var ed = s.ed;
        switch (mode) {
            case 0 -> {
                String def = s.world.level.levelName() + "_" + SaveManager.stamp() + ".zip";
                String dir = WbConfig.get().zipDir.isEmpty() ? System.getProperty("user.home") : WbConfig.get().zipDir;
                Path zip = FileDialogs.saveFile("Сохранить ZIP", Paths.get(dir, def).toString(), new String[]{"*.zip"}, "ZIP-архив");
                if (zip == null) return;
                if (!zip.toString().toLowerCase().endsWith(".zip")) zip = zip.resolveSibling(zip.getFileName() + ".zip");
                WbConfig.get().zipDir = zip.getParent().toString(); WbConfig.get().save();
                Path fz = zip;
                runSaveTask("Сохранение ZIP", t -> SaveManager.saveZip(ed, fz, opt[2], opt[3], t), "Сохранено в " + fz.getFileName() + "." + fixes);
            }
            case 1 -> {
                if (ed.source.isRemote()) {
                    if (name == null || !name.matches("[A-Za-z0-9_-]+")) { s.message("Имя папки", "Имя папки: латиница, цифры, «_» и «-», без пробелов."); return; }
                    runSaveTask("Загрузка нового мира", t -> SaveManager.uploadNew(ed, parent, name, opt[2], opt[3], t),
                            "Мир загружен в " + RemoteFs.join(parent, name) + ". Чтобы сервер загрузил новый мир, впиши его имя в server.properties → level-name или в pocketmine.yml → worlds, затем перезапусти сервер." + fixes);
                } else {
                    Path dir = FileDialogs.selectFolder("Папка, куда сохранить мир", System.getProperty("user.home"));
                    if (dir == null) return;
                    Path target = dir.resolve(s.world.dir.getFileName().toString());
                    runSaveTask("Сохранение в папку", t -> SaveManager.saveFolder(ed, target, opt[2], opt[3], t), "Сохранено в " + target + "." + fixes);
                }
            }
            case 2 -> {
                if (ed.source.isRemote()) {
                    Task check = Task.start("Проверка сервера", t -> {
                        t.set("Сравниваю файлы на сервере со снимком…", -1);
                        List<String> ch = SaveManager.conflicts(ed);
                        net.minecraft.client.MinecraftClient.getInstance().execute(() -> {
                            if (ch.isEmpty()) overwriteNow(opt, fixes);
                            else s.dialogs.push(new WbScreen.ChoiceDialog("Мир на сервере изменился",
                                    "Мир на сервере изменился после того, как ты его открыл (изменены файлы: " + String.join(", ", ch.subList(0, Math.min(6, ch.size()))) + (ch.size() > 6 ? "…" : "") + ").",
                                    new String[]{"Перезаписать всё равно", "Сохранить как новый мир", "Скачать ZIP", "Отмена"}, k -> {
                                if (k == 0) overwriteNow(opt, fixes);
                                else if (k == 1) save();
                                else if (k == 2) doSaveNow(0, opt, null, null, fixes);
                            }));
                        });
                    }, t -> { if (t.error != null) s.toastError("Не удалось проверить сервер: " + RemoteFs.humanError(t.error), t.error); }, Task.IO);
                    push(new Task.ProgressDialog(check));
                    if (!WbConfig.get().hideServerWarning) {}
                } else if (ed.source.offlinePath != null && Files.isDirectory(ed.source.offlinePath)) {
                    Path target = ed.source.offlinePath;
                    runSaveTask("Перезапись папки", t -> {
                        Path bak = SaveManager.backupsRoot().resolve("_local").resolve(target.getFileName().toString()).resolve(SaveManager.stamp());
                        Files.createDirectories(bak.resolve("region"));
                        for (String f : s.world.allFiles()) { Path src = target.resolve(f); if (Files.exists(src)) Files.copy(src, bak.resolve(f), StandardCopyOption.REPLACE_EXISTING); }
                        SaveManager.saveFolder(ed, target, opt[2], opt[3], t);
                    }, "Папка мира перезаписана (резервная копия — в worldbuilder/backups)." + fixes);
                }
            }
        }
    }

    void overwriteNow(boolean[] opt, String fixes) {
        runSaveTask("Сохранение на сервер", t -> SaveManager.overwrite(s.ed, opt[0], opt[1], opt[2], opt[3], t), "Сохранено на сервер. Если сервер был выключен — включи его." + fixes);
    }

    interface SaveBody { void run(Task t) throws Exception; }
    void runSaveTask(String title, SaveBody body, String okMsg) {
        if (s.ed.busy()) { s.toast("Подожди окончания операции"); return; }
        Task task = Task.start(title, body::run, t -> {
            if (t.error != null) s.toastError("Ошибка сохранения: " + RemoteFs.humanError(t.error), t.error);
            else if (t.cancelled) s.toast("Сохранение отменено");
            else { s.toast(okMsg.length() > 90 ? okMsg.substring(0, okMsg.indexOf('.') + 1) : okMsg); if (okMsg.length() > 90) s.message("Готово", okMsg); }
        }, Task.IO);
        push(new Task.ProgressDialog(task));
    }

    // ---------- заглушки ----------
    void stubReplace(boolean saving, Runnable then) {
        Map<String, Integer> repl = new LinkedHashMap<>();
        List<String> pal = new ArrayList<>(s.world.stubPalette);
        Map<Integer, Long> counts = new HashMap<>();
        for (int v : s.world.stubs.values()) counts.merge(v, 1L, Long::sum);
        push(new WbScreen.Dialog(saving ? "Сохранить мир с заглушками нельзя" : "Заменить заглушки") {
            { w = 520; h = 320; }
            double off;
            public boolean render(Gui g, int x, int y) {
                int yy = y + 6;
                for (String l : WbScreen.wrap(g, "Заглушки — блоки, которых нет в 0.14. Выбери для каждого блок PE (активная ячейка хотбара или пипетка), либо замени все сразу.", w - 20)) { g.text(l, x + 10, yy, Gui.TEXT2); yy += 10; }
                yy += 4;
                int listH = h - 18 - (yy - y) - 34;
                off = g.scrollArea("stubl", x + 4, yy, w - 8, listH, pal.size() * 20, off);
                g.scissor(x + 4, yy, w - 8, listH);
                for (int i = 0; i < pal.size(); i++) {
                    int ry = yy + i * 20 - (int) off;
                    long cnt = counts.getOrDefault(i, 0L);
                    if (cnt == 0 && !repl.containsKey(pal.get(i))) { continue; }
                    g.text(g.ellipsize(pal.get(i), w - 260) + "  ×" + cnt, x + 10, ry + 5, Gui.TEXT);
                    Integer to = repl.get(pal.get(i));
                    if (to != null) { ui.drawIcon(to, x + w - 220, ry + 1, 16); g.text(g.ellipsize(t().shortName(to), 90), x + w - 200, ry + 5, Gui.ACCENT); }
                    if (g.button("sth" + i, x + w - 104, ry + 1, 50, 16, "Хотбар", "Активная ячейка")) repl.put(pal.get(i), s.ed.activeState());
                    if (g.button("stp" + i, x + w - 52, ry + 1, 44, 16, "Воздух", null)) repl.put(pal.get(i), 0);
                }
                g.noScissor();
                int by = y + h - 18 - 26;
                if (g.button("stall0", x + 10, by, 120, 18, "Все → воздух", null)) { for (String p : pal) repl.put(p, 0); }
                if (g.button("stallhb", x + 134, by, 140, 18, "Все → активный блок", null)) { int st = s.ed.activeState(); for (String p : pal) repl.put(p, st); }
                if (g.button("stcancel", x + w - 190, by, 80, 18, "Отмена", null)) return true;
                if (g.primary("stok", x + w - 106, by, 96, 18, "Применить", null, !repl.isEmpty())) {
                    Map<Integer, Integer> byV = new HashMap<>();
                    for (var e : repl.entrySet()) byV.put(-(pal.indexOf(e.getKey()) + 1), e.getValue());
                    // в буфере тоже
                    for (Clipboard c : s.ed.clipboards) for (int k = 0; k < c.data.length; k++) {
                        int v = c.data[k];
                        if (v < 0 && v != Clipboard.NONE) { String nm = -v - 1 < c.stubPalette.size() ? c.stubPalette.get(-v - 1) : null; if (nm != null && repl.containsKey(nm)) c.data[k] = repl.get(nm); }
                    }
                    s.ed.run("Замена заглушек", "replace", op -> {
                        for (var e : new ArrayList<>(s.world.stubs.entrySet())) {
                            Integer to = byV.get(-(e.getValue() + 1));
                            if (to == null) continue;
                            long p = e.getKey();
                            s.world.set(com.geniiy.worldbuilder.util.Pos.x(p), com.geniiy.worldbuilder.util.Pos.y(p), com.geniiy.worldbuilder.util.Pos.z(p), to, op.cs);
                        }
                        return String.format("Заменено заглушек: %,d", op.cs.changedCount());
                    }, op -> { s.opDone(op); if (s.world.stubs.isEmpty() && then != null) then.run(); });
                    return true;
                }
                return false;
            }
        });
    }

    // ---------- схематики ----------
    void importSchematic(Path p) {
        if (p == null) {
            Path lib = WbConfig.gameDir().resolve("schematics");
            p = FileDialogs.openFile("Импорт схематика", lib.toString() + "/", new String[]{"*.schem", "*.schematic"}, "Схематики");
            if (p == null) return;
        }
        Path fp = p;
        boolean analogs = true;
        Task task = Task.start("Импорт " + fp.getFileName(), t -> {
            t.set("Читаю " + fp.getFileName() + "…", -1);
            SchematicIO.Report r = SchematicIO.read(fp, analogs);
            r.clip.name = fp.getFileName().toString();
            net.minecraft.client.MinecraftClient.getInstance().execute(() -> showImportReport(r));
        }, t -> { if (t.error != null) s.toastError("Не удалось импортировать: " + t.error.getMessage(), t.error); }, Task.IO);
        push(new Task.ProgressDialog(task));
    }

    void showImportReport(SchematicIO.Report r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Формат: ").append(r.format).append(". Размер: ").append(r.clip.w).append('×').append(r.clip.h).append('×').append(r.clip.l).append('\n');
        sb.append(String.format("Импортировано блоков: %,d\n", r.imported));
        if (!r.analogs.isEmpty()) { sb.append("\nЗаменено на аналоги:\n"); r.analogs.forEach((k, v) -> sb.append("  ").append(k).append("  ×").append(v).append('\n')); }
        if (!r.stubs.isEmpty()) { sb.append("\nСтали заглушками (замени их перед сохранением):\n"); r.stubs.forEach((k, v) -> sb.append("  ").append(k).append("  ×").append(v).append('\n')); }
        if (r.tilesDropped > 0) sb.append("\nОтброшено тайлов: ").append(r.tilesDropped).append('\n');
        for (String wn : r.warnings) sb.append("\n⚠ ").append(wn);
        s.ed.addClipboard(r.clip);
        push(new WbScreen.TextDialog("Отчёт импорта", sb.toString()) {
            { w = 520; h = 300; }
            @Override public boolean render(Gui g, int x, int y) {
                boolean close = super.render(g, x, y);
                if (!r.stubs.isEmpty() && g.button("stubrep", x + 8, y + h - 18 - 24, 150, 18, "Заменить заглушки…", null)) { s.dialogs.remove(this); s.startPaste(r.clip); stubReplaceClip(r.clip); return false; }
                if (close) s.startPaste(r.clip);
                return close;
            }
        });
    }

    /** Замена заглушек в буфере до вставки. */
    void stubReplaceClip(Clipboard c) {
        Map<String, Integer> repl = new LinkedHashMap<>();
        push(new WbScreen.Dialog("Заменить заглушки в буфере") {
            { w = 520; h = 320; }
            double off;
            public boolean render(Gui g, int x, int y) {
                int yy = y + 8;
                int listH = h - 18 - 50;
                off = g.scrollArea("scl", x + 4, yy, w - 8, listH, c.stubPalette.size() * 20, off);
                g.scissor(x + 4, yy, w - 8, listH);
                for (int i = 0; i < c.stubPalette.size(); i++) {
                    int ry = yy + i * 20 - (int) off;
                    String nm = c.stubPalette.get(i);
                    g.text(g.ellipsize(nm, w - 260), x + 10, ry + 5, Gui.TEXT);
                    Integer to = repl.get(nm);
                    if (to != null) { ui.drawIcon(to, x + w - 220, ry + 1, 16); g.text(g.ellipsize(t().shortName(to), 90), x + w - 200, ry + 5, Gui.ACCENT); }
                    if (g.button("sch" + i, x + w - 104, ry + 1, 50, 16, "Хотбар", null)) repl.put(nm, s.ed.activeState());
                    if (g.button("scp" + i, x + w - 52, ry + 1, 44, 16, "Воздух", null)) repl.put(nm, 0);
                }
                g.noScissor();
                int by = y + h - 18 - 26;
                if (g.button("sccancel", x + w - 190, by, 80, 18, "Отмена", null)) return true;
                if (g.primary("scok", x + w - 106, by, 96, 18, "Применить", null, true)) {
                    for (int k = 0; k < c.data.length; k++) {
                        int v = c.data[k];
                        if (v < 0 && v != Clipboard.NONE) { String nm = -v - 1 < c.stubPalette.size() ? c.stubPalette.get(-v - 1) : null; if (nm != null && repl.containsKey(nm)) c.data[k] = repl.get(nm); }
                    }
                    s.pasteMesh.close();
                    return true;
                }
                return false;
            }
        });
    }

    void exportSchematic() {
        if (s.ed.selection.isEmpty()) { s.toast("Сначала выдели область"); return; }
        Clipboard c = Clipboard.copy(s.world, s.ed.selection, false);
        saveToLibrary(c);
    }

    void saveToLibrary(Clipboard c) {
        Path lib = WbConfig.gameDir().resolve("schematics");
        try { Files.createDirectories(lib); } catch (Exception ignored) {}
        Path f = FileDialogs.saveFile("Сохранить схематик (.schematic Pocket)", lib.resolve((c.name == null ? "заготовка" : c.name.replaceAll("[^A-Za-zА-Яа-я0-9_ -]", "_")) + ".schematic").toString(), new String[]{"*.schematic"}, "Схематик MCEdit (Pocket)");
        if (f == null) return;
        if (!f.toString().endsWith(".schematic")) f = f.resolveSibling(f.getFileName() + ".schematic");
        try { SchematicIO.writePocket(c, f); s.toast("Сохранено: " + f.getFileName()); }
        catch (Exception e) { s.toastError("Не удалось сохранить схематик: " + e.getMessage(), e); }
    }

    // ---------- тестовый мир ----------
    void testWorld() {
        s.dialogs.push(new WbScreen.ChoiceDialog("Тестовый мир всех блоков",
                "Будет создан плоский мир, где на сетке стоят все id:meta из таблицы (на постаментах, с табличками). Залей его на сервер, зайди с 0.14.3 и сравни с видом в редакторе. Расхождения правятся только в JSON-таблице.\nСохранить мир как ZIP?",
                new String[]{"Создать и сохранить ZIP…", "Отмена"}, i -> {
            if (i != 0) return;
            Path zip = FileDialogs.saveFile("Сохранить тестовый мир", Paths.get(System.getProperty("user.home"), "wb_test_all_blocks.zip").toString(), new String[]{"*.zip"}, "ZIP");
            if (zip == null) return;
            Task task = Task.start("Тестовый мир", t -> {
                t.set("Расставляю блоки…", -1);
                Path dir = WbConfig.gameDir().resolve("cache").resolve("_test").resolve("wb_test_all_blocks");
                WorldSource.deleteTree(dir);
                PeWorld w = WorldTemplates.testWorld(dir);
                t.set("Записываю…", 0.6);
                PeWorld.SaveOptions o = new PeWorld.SaveOptions();
                o.computeLight = false;
                List<String> files = w.writeChanged(dir, o, null);
                WorldSource.writeZip(zip, "wb_test_all_blocks", dir, dir, files, w.allFiles());
            }, t -> { if (t.error != null) s.toastError("Ошибка: " + t.error, t.error); else s.toast("Тестовый мир сохранён: " + zip.getFileName()); }, Task.IO);
            push(new Task.ProgressDialog(task));
        }));
    }
}
