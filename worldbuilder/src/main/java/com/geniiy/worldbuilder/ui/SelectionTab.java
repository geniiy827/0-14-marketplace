package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.edit.*;
import com.geniiy.worldbuilder.session.Editor;

/** Вкладка «Выделение»: список фигур, точные параметры, действия (раздел 17–18). */
class SelectionTab {
    final EditorUi ui; final EditorScreen s; final Gui g;
    int x, y, w;
    SelectionTab(EditorUi ui) { this.ui = ui; this.s = ui.s; this.g = ui.g; }

    double num(String id, int xx, int ww, String label, double v, double min, double max, boolean integer) { return g.number(id, xx, y, ww, 16, label, v, min, max, 1, integer); }

    void draw(int px, int py, int pw, int ph) {
        x = px + 6; w = pw - 12;
        int area = ph;
        g.scissor(px, py, pw, area);
        y = py - (int) ui.selOff;
        int start = y;
        Selection sel = s.ed.selection;
        g.text("Фигуры выделения", x, y + 2, Gui.TEXT2); y += 14;
        if (sel.isEmpty()) { g.text("Нет выделения. Инструмент «Выделение» (M).", x, y, 0xFF6A6D72); y += 14; }
        for (int i = 0; i < sel.shapes.size(); i++) {
            Shape sh = sel.shapes.get(i);
            boolean act = i == sel.active;
            boolean hv = g.hover(x, y, w, 16);
            if (act || hv) g.rect(x, y, w, 16, act ? Gui.BTN_SEL : Gui.BTN_H);
            String mode = switch (sh.mode) { case SUBTRACT -> "−"; case INTERSECT -> "∩"; default -> "+"; };
            g.text(mode + " " + g.ellipsize(sh.name, w - 90), x + 4, y + 4, sh.hidden ? 0xFF5F6268 : Gui.TEXT);
            if (g.button("shh" + i, x + w - 60, y, 18, 16, sh.hidden ? "○" : "◉", sh.hidden ? "Показать" : "Скрыть")) { sh.hidden = !sh.hidden; sel.changed(); }
            else if (g.button("shr" + i, x + w - 40, y, 18, 16, "✎", "Переименовать")) ui.dlg.renameShape(sh);
            else if (g.button("shd" + i, x + w - 20, y, 18, 16, "×", "Удалить фигуру")) { ChangeSet cs = s.selectionChange("Удалить фигуру"); sel.shapes.remove(i); sel.active = Math.min(sel.active, sel.shapes.size() - 1); s.commitSelectionChange(cs); break; }
            else if (hv && g.clicked(x, y, w - 62, 16, 0)) sel.active = i;
            y += 18;
        }
        Shape a = sel.activeShape();
        if (a != null) {
            y += 4;
            g.hsep(x, y, w); y += 4;
            g.text("Параметры: " + a.type.ru, x, y, Gui.ACCENT); y += 12;
            Shape.Mode[] modes = Shape.Mode.values();
            if (sel.active > 0) { int m = g.segmented("shmode", x, y, w, 14, new String[]{"Новое", "Добавить", "Вычесть", "Пересечь"}, a.mode.ordinal(), null); if (m != a.mode.ordinal()) { a.mode = modes[m == 0 ? 1 : m]; sel.changed(); } y += 18; }
            if (a.type != Shape.Type.VOXELS) {
                int third = (w - 4) / 3;
                g.text("Позиция (мин. угол)", x, y, Gui.TEXT2); y += 10;
                int nx = (int) num("px", x, third, "X", a.minX, -3e7, 3e7, true), ny = (int) num("py", x + third + 2, third, "Y", a.minY, 0, 127, true), nz = (int) num("pz", x + 2 * third + 4, third, "Z", a.minZ, -3e7, 3e7, true);
                y += 20;
                if (nx != a.minX || ny != a.minY || nz != a.minZ) { a.move(nx - a.minX, ny - a.minY, nz - a.minZ); sel.changed(); }
                g.text("Размер (Ш × В × Д)", x, y, Gui.TEXT2); y += 10;
                int sx = (int) num("sx", x, third, "Ш", a.sx(), 1, 100000, true), sy = (int) num("sy", x + third + 2, third, "В", a.sy(), 1, 128, true), sz = (int) num("sz", x + 2 * third + 4, third, "Д", a.sz(), 1, 100000, true);
                y += 20;
                if (sx != a.sx() || sy != a.sy() || sz != a.sz()) { a.setBox(a.minX, a.minY, a.minZ, a.minX + sx - 1, Math.min(127, a.minY + sy - 1), a.minZ + sz - 1); sel.changed(); }
                if (a.type == Shape.Type.ELLIPSOID || a.type == Shape.Type.CYLINDER || a.type == Shape.Type.TORUS) {
                    g.text(String.format("Радиусы: %.1f × %.1f × %.1f", a.sx() / 2.0, a.sy() / 2.0, a.sz() / 2.0), x, y, Gui.TEXT2); y += 12;
                }
                if (a.type == Shape.Type.CYLINDER || a.type == Shape.Type.CONE || a.type == Shape.Type.PYRAMID || a.type == Shape.Type.TORUS || a.type == Shape.Type.HALF_SPHERE || a.type == Shape.Type.HALF_CYLINDER) {
                    int ax = g.segmented("shax", x, y, w, 14, new String[]{"Ось X", "Ось Y", "Ось Z"}, a.axis, null);
                    if (ax != a.axis) { a.axis = ax; a.version++; sel.changed(); }
                    y += 18;
                }
                if (a.type == Shape.Type.CONE || a.type == Shape.Type.PYRAMID) { boolean v = g.check("apex", x, y, "Вершина вверх", a.apexUp, null); if (v != a.apexUp) { a.apexUp = v; a.version++; sel.changed(); } y += 16; }
                if (a.type == Shape.Type.PYRAMID) { boolean v = g.check("stepped", x, y, "Ступенчатая", a.stepped, null); if (v != a.stepped) { a.stepped = v; a.version++; sel.changed(); } y += 16; }
                if (a.type == Shape.Type.TORUS) { double v = g.number("minor", x, y, w, 16, "Толщина трубы", a.minorRatio, 0.05, 0.95, 0.05, false); if (v != a.minorRatio) { a.minorRatio = v; a.version++; sel.changed(); } y += 20; }
                if (a.type == Shape.Type.HALF_SPHERE || a.type == Shape.Type.HALF_CYLINDER) { boolean v = g.check("half", x, y, "Положительная половина", a.halfPositive, "Какую половину оставить"); if (v != a.halfPositive) { a.halfPositive = v; a.version++; sel.changed(); } y += 16; }
                if (a.type != Shape.Type.CUBOID && a.type != Shape.Type.POLYGON) {
                    g.text("Поворот формы (°)", x, y, Gui.TEXT2); y += 10;
                    double rx = num("rx", x, third, "X", a.rotX, -360, 360, false), ry = num("ry", x + third + 2, third, "Y", a.rotY, -360, 360, false), rz = num("rz", x + 2 * third + 4, third, "Z", a.rotZ, -360, 360, false);
                    y += 20;
                    if (rx != a.rotX || ry != a.rotY || rz != a.rotZ) { a.rotX = rx; a.rotY = ry; a.rotZ = rz; a.version++; sel.changed(); }
                }
                boolean hol = g.check("hollow", x, y, "Полая", a.hollow, null);
                if (hol != a.hollow) { a.hollow = hol; a.version++; sel.changed(); }
                if (a.hollow) { int t = (int) g.number("thick", x + 70, y - 2, w - 70, 16, "стенка", a.thickness, 1, 32, 1, true); if (t != a.thickness) { a.thickness = t; a.version++; sel.changed(); } }
                y += 18;
                if (a.type == Shape.Type.CUBOID) { boolean wf = g.check("wire", x, y, "Только каркас (рёбра)", a.wireframe, null); if (wf != a.wireframe) { a.wireframe = wf; a.version++; sel.changed(); } y += 16; }
            } else { g.text(String.format("Произвольная маска: %,d вокселей", a.voxels == null ? 0 : a.voxels.size()), x, y, Gui.TEXT2); y += 12; }
            int hw = (w - 2) / 2;
            if (g.button("bake", x, y, hw, 16, "Запечь", "Превратить в воксельную маску")) { ChangeSet cs = s.selectionChange("Запечь выделение"); sel.bake(); s.commitSelectionChange(cs); }
            if (g.button("savesel", x + hw + 2, y, hw, 16, "Сохранить как…", null)) ui.dlg.saveSelection();
            y += 20;
        }
        if (!s.ed.savedSelections.isEmpty()) {
            g.text("Сохранённые выделения", x, y, Gui.TEXT2); y += 12;
            for (var e : new java.util.ArrayList<>(s.ed.savedSelections.entrySet())) {
                if (g.button("ss" + e.getKey(), x, y, w - 22, 14, e.getKey(), "Восстановить")) { ChangeSet cs = s.selectionChange("Восстановить выделение"); s.ed.selection = e.getValue().copy(); s.commitSelectionChange(cs); }
                if (g.button("ssd" + e.getKey(), x + w - 20, y, 20, 14, "×", null)) s.ed.savedSelections.remove(e.getKey());
                y += 16;
            }
        }
        // ---------- действия ----------
        y += 6;
        g.hsep(x, y, w); y += 4;
        g.text("Действия над выделением", x, y, Gui.ACCENT); y += 12;
        boolean has = !sel.isEmpty();
        String[][] acts = {
                {"fill", "Заполнить", "Ctrl+F — активным блоком/смесью"}, {"replace", "Заменить…", "Ctrl+H — что на что, с сохранением ориентации"},
                {"clear", "Очистить", "Delete"}, {"walls", "Стены", "Боковые грани (толщина N)"},
                {"floor", "Пол", "Нижний слой"}, {"ceiling", "Потолок", "Верхний слой"},
                {"shell", "Оболочка", "Внутри — воздух"}, {"edges", "Каркас", "Только рёбра"},
                {"outline", "Обводка", "Слой вокруг снаружи"}, {"overlay", "Накрыть сверху", "Снег, трава… N слоёв"},
                {"naturalize", "Озеленить", "Трава, 3 земли, камень"}, {"decorate", "Декорировать", "Растения по поверхности"},
                {"smooth", "Сгладить рельеф", "По карте высот"}, {"water", "Залить водой…", "Ниже уровня Y"},
                {"drain", "Осушить", "Убрать воду и лаву"}, {"fixliq", "Исправить жидкости", "Всё — источники"},
                {"floating", "Убрать парящие", "Группы меньше N"}, {"dilate", "Нарастить", "Добавить слой у твёрдых блоков"},
                {"erode", "Срезать", "Убрать слой"}, {"rotate", "Повернуть…", "90/180/270° вокруг Y"},
                {"mirror", "Отразить…", "По X / Y / Z"}, {"stack", "Размножить…", "Повторить N раз"},
                {"move", "Переместить (G)", "Инструмент «Перемещение»"}, {"copy", "Копировать", "Ctrl+C"},
                {"cut", "Вырезать", "Ctrl+X"}, {"stats", "Статистика", "Таблица блоков"},
                {"biome", "Биом…", "Биом и цвет травы колонок"}, {"schem", "Сохранить схематик…", ".schematic Pocket"},
                {"light", "Пересчитать свет", "Для проверки (свет считается и при сохранении)"},
        };
        int hw = (w - 2) / 2;
        for (int i = 0; i < acts.length; i++) {
            int bx = x + (i % 2) * (hw + 2), by = y + (i / 2) * 18;
            if (g.button("act" + acts[i][0], bx, by, hw, 16, acts[i][1], acts[i][2], false, has)) run(acts[i][0]);
        }
        y += ((acts.length + 1) / 2) * 18 + 6;
        int end = y;
        g.noScissor();
        ui.selOff = g.scrollArea("seltab", px, py, pw, area, end - start + 6, ui.selOff);
    }

    void run(String id) {
        EditorDialogs d = ui.dlg;
        Selection sel = s.ed.selection.copy();
        Pattern pat = s.ed.active().copy();
        switch (id) {
            case "fill" -> ui.actionFill();
            case "replace" -> ui.openReplace();
            case "clear" -> ui.actionClear(false);
            case "walls" -> d.intParam("Стены", "Толщина", 1, 1, 32, n -> op("Стены → " + pat.describe(), "walls", op -> Ops.walls(s.world, sel, n, pat, op.cs, op)));
            case "floor" -> d.intParam("Пол", "Толщина", 1, 1, 64, n -> op("Пол → " + pat.describe(), "floor", op -> Ops.floorCeiling(s.world, sel, n, false, pat, op.cs, op)));
            case "ceiling" -> d.intParam("Потолок", "Толщина", 1, 1, 64, n -> op("Потолок → " + pat.describe(), "ceiling", op -> Ops.floorCeiling(s.world, sel, n, true, pat, op.cs, op)));
            case "shell" -> d.intParam("Оболочка", "Толщина стенки", 1, 1, 32, n -> op("Оболочка", "shell", op -> Ops.shell(s.world, sel, n, null, op.cs, op)));
            case "edges" -> op("Каркас → " + pat.describe(), "edges", op -> Ops.edges(s.world, sel, pat, op.cs, op));
            case "outline" -> op("Обводка → " + pat.describe(), "outline", op -> Ops.outline(s.world, sel, pat, op.cs, op));
            case "overlay" -> d.intParam("Накрыть сверху (" + pat.describe() + ")", "Слоёв", 1, 1, 16, n -> op("Накрыть сверху → " + pat.describe(), "overlay", op -> Ops.overlay(s.world, sel, pat, n, op.cs, op)));
            case "naturalize" -> op("Озеленение", "naturalize", op -> Ops.naturalize(s.world, sel, op.cs, op));
            case "decorate" -> d.decorate(sel);
            case "smooth" -> d.intParam("Сгладить рельеф", "Сила (1–10)", 2, 1, 10, n -> op("Сглаживание рельефа", "smooth", op -> Ops.smoothHeight(s.world, sel, n, 2, op.cs, op)));
            case "water" -> d.intParam("Залить водой ниже уровня", "Уровень Y", sel.bounds() == null ? 62 : sel.bounds()[4], 0, 127, n -> op("Залить водой до Y=" + n, "water", op -> Ops.waterBelow(s.world, sel, n, false, op.cs, op)));
            case "drain" -> op("Осушить", "drain", op -> Ops.drain(s.world, sel, op.cs, op));
            case "fixliq" -> op("Исправить жидкости", "fixliq", op -> Ops.fixLiquids(s.world, sel, op.cs, op));
            case "floating" -> d.intParam("Убрать парящие блоки", "Группы меньше", 50, 1, 100000, n -> op("Убрать парящие (< " + n + ")", "floating", op -> Ops.removeFloating(s.world, sel, n, op.cs, op)));
            case "dilate" -> op("Нарастить массу", "dilate", op -> Ops.dilateErode(s.world, sel, true, 3, op.cs, op));
            case "erode" -> op("Срезать массу", "erode", op -> Ops.dilateErode(s.world, sel, false, 3, op.cs, op));
            case "rotate" -> d.rotate(sel);
            case "mirror" -> d.mirror(sel);
            case "stack" -> d.stack(sel);
            case "move" -> s.setTool(EditorScreen.Tool.MOVE);
            case "copy" -> s.copy();
            case "cut" -> s.cut();
            case "stats" -> d.stats();
            case "biome" -> { s.setTool(EditorScreen.Tool.BIOME); s.toast("Выбери биом слева и нажми «Залить выделение этим биомом»"); }
            case "schem" -> ui.exportSchematic();
            case "light" -> op("Пересчёт света", "light", op -> {
                java.util.Set<com.geniiy.worldbuilder.pe.PeChunk> ch = new java.util.HashSet<>();
                int[] b = sel.bounds();
                for (int cx = b[0] >> 4; cx <= b[3] >> 4; cx++) for (int cz = b[2] >> 4; cz <= b[5] >> 4; cz++) { var c = s.world.getChunk(cx, cz); if (c != null && !c.corrupt) { ch.add(c); c.markDirty(); } }
                com.geniiy.worldbuilder.pe.LightEngine.relight(s.world, ch, null);
            });
            default -> {}
        }
    }

    interface OpBody { void run(Editor.Op op) throws Exception; }
    void op(String name, String icon, OpBody b) { s.ed.run(name, icon, op -> { b.run(op); return null; }, s::opDone); }

    static Rotation.Op mx() { return Rotation.Op.MIRROR_X; }
}
