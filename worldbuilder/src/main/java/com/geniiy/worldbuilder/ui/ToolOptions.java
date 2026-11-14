package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.edit.*;

/** Параметры активного инструмента (левая панель). */
class ToolOptions {
    final EditorUi ui; final EditorScreen s; final Gui g;
    int x, y, w;

    ToolOptions(EditorUi ui, int x, int y, int w) { this.ui = ui; this.s = ui.s; this.g = ui.g; this.x = x; this.y = y; this.w = w; }

    void line(String txt) { for (String l : WbScreen.wrap(g, txt, w)) { g.text(l, x, y, Gui.TEXT2); y += 10; } y += 2; }
    boolean check(String id, String label, boolean v, String tip) { boolean r = g.check(id, x, y, label, v, tip); y += 16; return r; }
    double num(String id, String label, double v, double min, double max, double step, boolean integer) { double r = g.number(id, x, y, w, 16, label, v, min, max, step, integer); y += 20; return r; }
    int seg(String id, String[] labels, int sel) { int r = g.segmented(id, x, y, w, 15, labels, sel, null); y += 19; return r; }
    boolean btn(String id, String label, String tip) { boolean r = g.button(id, x, y, w, 16, label, tip); y += 20; return r; }

    int draw() {
        switch (s.tool) {
            case INSPECT -> line("Кликни по блоку — справа появится карточка: координаты, имя, id:meta (с битами), Java-состояние, свет, биом и тайл.");
            case PENCIL -> {
                s.smartOrient = check("smart", "Умная ориентация", s.smartOrient, "Ориентация как в Genisys: ступени по взгляду, брёвна по грани, факелы по опоре…");
                if (s.manualOrient) { line("Ручная ориентация: поворот " + (s.manualRot * 90) + "°" + (s.manualFlip ? ", отражено" : "")); if (btn("resetorient", "Сбросить ручную ориентацию", null)) s.manualOrient = false; }
                line("R — повернуть призрак на 90°, Alt+R — отразить. Протяжка ЛКМ рисует в плоскости первого блока; X/Y/Z — фиксация оси.");
                line("Опора не нужна: факел или кнопку можно поставить куда угодно.");
            }
            case ERASER -> {
                s.eraserSize = (int) num("ersize", "Размер", s.eraserSize, 1, 9, 1, true);
                s.eraserSphere = seg("erform", new String[]{"Куб", "Шар"}, s.eraserSphere ? 1 : 0) == 1;
                if (btn("ermask", "Маска: " + s.eraserMask.describe(), "Удалять только определённые блоки")) ui.dlg.maskEditor(s.eraserMask, m -> s.eraserMask = m);
            }
            case BUCKET -> {
                s.bucketRadius = (int) num("bkr", "Радиус", s.bucketRadius, 1, 512, 1, true);
                s.bucketMax = (int) num("bkm", "Максимум", s.bucketMax, 1, 10_000_000, 1000, true);
                s.bucket26 = seg("bkn", new String[]{"6 соседей", "26 соседей"}, s.bucket26 ? 1 : 0) == 1;
                s.bucketPlane = check("bkp", "Только в плоскости грани", s.bucketPlane, "Для полов и стен");
                s.bucketMeta = check("bkmeta", "Учитывать мету", s.bucketMeta, "Выкл — заливать любой вариант того же id");
                line("Заливка — активным блоком или смесью ячейки хотбара.");
            }
            case SELECT -> drawSelect();
            case BRUSH -> drawBrush(s.brush, Brush.Mode.values(), "br");
            case TERRAIN -> drawBrush(s.terrain, new Brush.Mode[]{Brush.Mode.RAISE, Brush.Mode.SMOOTH, Brush.Mode.FLATTEN, Brush.Mode.NOISE, Brush.Mode.ERODE, Brush.Mode.TOPSOIL, Brush.Mode.GRAVITY}, "tr");
            case BIOME -> drawBiome();
            case LINE -> drawLine();
            case MOVE -> drawMove();
            case RULER -> line("Кликни две точки. Показываются расстояние по прямой, размеры по осям, площадь и объём рамки. Esc — сбросить.");
            case OBJECTS -> drawObjects();
        }
        return y;
    }

    void drawSelect() {
        Shape.Type[] types = {Shape.Type.CUBOID, Shape.Type.ELLIPSOID, Shape.Type.CYLINDER, Shape.Type.CONE, Shape.Type.PYRAMID, Shape.Type.TORUS, Shape.Type.HALF_SPHERE, Shape.Type.HALF_CYLINDER, Shape.Type.POLYGON, Shape.Type.VOXELS};
        String[] names = {"Куб", "Сфера", "Цилиндр", "Конус", "Пирамида", "Тор", "Полусфера", "Полуцилиндр", "Контур", "Особое"};
        int cols = 2, bw = (w - 2) / cols;
        for (int i = 0; i < types.length; i++) {
            int bx = x + (i % cols) * (bw + 2), by = y + (i / cols) * 18;
            if (g.button("st" + i, bx, by, bw, 16, names[i], types[i].ru, s.selType == types[i], true)) { s.selType = types[i]; s.polyPoints.clear(); }
        }
        y += ((types.length + 1) / 2) * 18 + 4;
        String[] modes = {"Новое", "Добавить", "Вычесть", "Пересечь"};
        s.selMode = Shape.Mode.values()[seg("smode", modes, s.selMode.ordinal())];
        line("Временно: Ctrl — добавить, Alt — вычесть, Ctrl+Alt — пересечь.");
        if (s.selType == Shape.Type.ELLIPSOID || s.selType == Shape.Type.CYLINDER || s.selType == Shape.Type.CONE || s.selType == Shape.Type.TORUS)
            s.selFromCenter = check("fromc", "От центра", s.selFromCenter, "Первая точка — центр фигуры");
        if (s.selType == Shape.Type.POLYGON) {
            line("Кликами поставь точки контура на плоскости, затем Enter.");
            ui.polyHeight = (int) num("polyh", "Высота", ui.polyHeight, 1, 128, 1, true);
            if (btn("polydone", "Завершить контур (" + s.polyPoints.size() + " точек)", "Enter")) s.finishPolygon(ui.polyHeight);
        }
        if (s.selType == Shape.Type.VOXELS) {
            ui.selVoxMode = seg("vmode", new String[]{"Палочка", "Кисть", "По типу", "Лассо"}, ui.selVoxMode);
            switch (ui.selVoxMode) {
                case 0 -> { s.wandMeta = check("wmeta", "Учитывать мету", s.wandMeta, null); s.wand26 = check("w26", "26 соседей", s.wand26, null); s.wandLimit = (int) num("wlim", "Предел", s.wandLimit, 100, 5_000_000, 1000, true); line("Клик — выделить связную область одинаковых блоков."); }
                case 1 -> { s.brushSelRadius = (int) num("bsr", "Радиус", s.brushSelRadius, 0, 32, 1, true); line("Рисуй выделение шаром. Ctrl — стирать."); }
                case 2 -> line("Клик по блоку — выделить все такие блоки внутри текущего выделения.");
                default -> { s.lassoDepth = (int) num("ldep", "Глубина", s.lassoDepth, 1, 32, 1, true); line("Обведи область на экране — выделятся видимые поверхностные блоки."); }
            }
        }
        y += 4;
        line("Ручки на гранях — размер (Alt — симметрично), стрелки — перемещение. Числа и действия — во вкладке «Выделение».");
    }

    void drawBrush(Brush b, Brush.Mode[] modes, String id) {
        int cols = 2, bw = (w - 2) / cols;
        for (int i = 0; i < modes.length; i++) {
            int bx = x + (i % cols) * (bw + 2), by = y + (i / cols) * 18;
            if (g.button(id + "m" + i, bx, by, bw, 16, modes[i].ru, null, b.mode == modes[i], true)) b.mode = modes[i];
        }
        y += ((modes.length + 1) / 2) * 18 + 4;
        Brush.Form[] forms = Brush.Form.values();
        String[] fn = new String[forms.length];
        for (int i = 0; i < forms.length; i++) fn[i] = forms[i].ru;
        b.form = forms[seg(id + "form", fn, b.form.ordinal())];
        b.size = (int) num(id + "size", "Размер", b.size, 0, 64, 1, true);
        if (b.mode == Brush.Mode.RAISE || b.mode == Brush.Mode.FLATTEN || b.mode == Brush.Mode.NOISE) {
            b.strength = (int) num(id + "str", "Сила %", b.strength, 1, 100, 1, true);
            Brush.Falloff[] fo = Brush.Falloff.values();
            b.falloff = fo[seg(id + "fall", new String[]{fo[0].ru, fo[1].ru, fo[2].ru}, b.falloff.ordinal())];
        }
        if (b.mode == Brush.Mode.PAINT) b.depth = (int) num(id + "dep", "Глубина", b.depth, 1, 16, 1, true);
        if (b.mode == Brush.Mode.VEGETATION || b.mode == Brush.Mode.TREES) b.density = num(id + "den", "Плотность %", b.density, 0.5, 100, 0.5, false);
        if (b.mode == Brush.Mode.TREES) {
            Generators.Kind[] kinds = {Generators.Kind.OAK, Generators.Kind.SPRUCE, Generators.Kind.BIRCH, Generators.Kind.JUNGLE, Generators.Kind.ACACIA, Generators.Kind.DARK_OAK};
            for (int i = 0; i < kinds.length; i++) { int bx = x + (i % 3) * ((w - 4) / 3 + 2), by = y + (i / 3) * 18; if (g.button(id + "tk" + i, bx, by, (w - 4) / 3, 16, kinds[i].ru, null, b.tree.kind == kinds[i], true)) b.tree.kind = kinds[i]; }
            y += 40;
            b.tree.height = (int) num(id + "th", "Высота", b.tree.height, 3, 30, 1, true);
            b.tree.randomness = (int) num(id + "tr", "Разброс %", b.tree.randomness, 0, 100, 5, true);
        }
        if (b.mode == Brush.Mode.TOPSOIL) {
            line("Верхний слой: трава + 3 земли (по умолчанию). Смесь ячейки хотбара задаёт верхний блок.");
            int top = s.ed.activeState();
            b.topsoil.entries.get(0)[0] = top;
            b.topsoil.entries.get(1)[1] = (int) num(id + "tsd", "Слоёв земли", b.topsoil.entries.get(1)[1], 0, 10, 1, true);
        }
        if (b.mode == Brush.Mode.STAMP) line("Штамп: содержимое буфера с случайным поворотом и отражением.");
        String[] pl = {"Поверхность", "В воздухе", "Плоскость Y"};
        b.placement = seg(id + "pl", pl, b.placement);
        if (b.placement == 2) b.planeY = (int) num(id + "py", "Y", b.planeY, 0, 127, 1, true);
        b.symX = check(id + "sx", "Симметрия по X", b.symX, null);
        b.symZ = check(id + "sz", "Симметрия по Z", b.symZ, null);
        if ((b.symX || b.symZ) && btn(id + "sc", "Центр симметрии — блок под курсором", "Или задай числами ниже") && s.hover != null) { b.symCX = s.hover.x; b.symCZ = s.hover.z; }
        if (b.symX || b.symZ) { b.symCX = (int) num(id + "scx", "X", b.symCX, -30000000, 30000000, 1, true); b.symCZ = (int) num(id + "scz", "Z", b.symCZ, -30000000, 30000000, 1, true); }
        if (btn(id + "mask", "Маска: " + g.ellipsize(b.mask.describe(), w - 60), null)) ui.dlg.maskEditor(b.mask, m -> b.mask = m);
        line("Блок/смесь — из активной ячейки хотбара. [ ] — размер, Ctrl+ЛКМ — обратное действие.");
    }

    void drawBiome() {
        Brush b = s.biomeBrush;
        b.size = (int) num("bsize", "Размер кисти", b.size, 0, 64, 1, true);
        b.form = seg("bform", new String[]{"Круг", "Квадрат"}, b.form == Brush.Form.CUBE ? 1 : 0) == 1 ? Brush.Form.CUBE : Brush.Form.CYLINDER;
        g.text("Биом: " + b.biomeId + " — " + Biomes.name(b.biomeId), x, y, Gui.TEXT); y += 12;
        int lh = 120;
        int n = Biomes.ALL.size();
        ui.biomeOff = g.scrollArea("biol", x, y, w, lh, n * 13, ui.biomeOff);
        g.scissor(x, y, w, lh);
        int i = 0;
        for (Biomes.B bb : Biomes.ALL.values()) {
            int ry = y + i * 13 - (int) ui.biomeOff;
            boolean sel = bb.id() == b.biomeId;
            boolean hv = g.hover(x, ry, w - 6, 13) && g.my >= y && g.my < y + lh;
            if (sel || hv) g.rect(x, ry, w - 6, 13, sel ? Gui.BTN_SEL : Gui.BTN_H);
            g.rect(x + 2, ry + 2, 9, 9, 0xFF000000 | Biomes.grassColor(bb.id()));
            g.text(bb.id() + " " + bb.ru() + (bb.genisys() ? "" : "  ·"), x + 14, ry + 3, bb.genisys() ? Gui.TEXT : Gui.TEXT2);
            if (hv && g.clicked(x, ry, w - 6, 13, 0)) b.biomeId = bb.id();
            i++;
        }
        g.noScissor();
        y += lh + 4;
        line("· — биом, которого нет в Genisys 0.14 (клиент его знает).");
        b.biomeId = (int) num("bid", "Произвольный ID", b.biomeId, 0, 255, 1, true);
        b.biomeAuto = seg("bauto", new String[]{"Цвет: авто", "Свой цвет"}, b.biomeAuto ? 0 : 1) == 0;
        if (!b.biomeAuto) {
            String hex = g.field("bhex", x, y, w - 24, 16, String.format("%06X", b.biomeColor & 0xFFFFFF), "RRGGBB", false, false);
            try { b.biomeColor = Integer.parseInt(hex.replace("#", "").trim(), 16) & 0xFFFFFF; } catch (Exception ignored) {}
            g.rect(x + w - 20, y, 20, 16, 0xFF000000 | b.biomeColor);
            y += 20;
            line("Alt+СКМ по блоку — пипетка биома и цвета.");
        } else { g.rect(x, y, w, 6, 0xFF000000 | Biomes.grassColor(b.biomeId)); y += 10; }
        s.showBiomes = check("showbio", "Показать биомы", s.showBiomes, null);
        if (btn("biofill", "Залить выделение этим биомом", null)) {
            if (s.ed.selection.isEmpty()) s.toast("Сначала выдели область");
            else {
                Selection sel = s.ed.selection.copy();
                int id = b.biomeId, col = b.biomeAuto ? Biomes.grassColor(id) : b.biomeColor;
                s.ed.run("Биом: " + Biomes.name(id), "biome", op -> { Ops.setBiome(s.world, sel, id, col, op.cs); return "Биом задан: " + Biomes.name(id); }, s::opDone);
            }
        }
    }

    void drawLine() {
        s.lineMode = seg("lmode", new String[]{"Отрезок", "Ломаная", "Сплайн"}, s.lineMode);
        s.lineWall = check("lwall", "Стена по линии", s.lineWall, "Для заборов и стен по контуру");
        if (s.lineWall) s.wallHeight = (int) num("lwh", "Высота стены", s.wallHeight, 1, 128, 1, true);
        s.lineThick = (int) num("lth", "Толщина", s.lineThick, 1, 32, 1, true);
        if (!s.lineWall) {
            s.lineSquare = seg("lsec", new String[]{"Круг", "Квадрат"}, s.lineSquare ? 1 : 0) == 1;
            s.lineHollow = check("lhol", "Трубка (полая)", s.lineHollow, null);
        }
        if (s.lineMode == 2) s.tension = num("lten", "Натяжение", s.tension, 0, 1, 0.05, false);
        s.lineVersion += 0;
        line("Точек: " + s.linePts.size() + ". Блок — из хотбара.");
        if (btn("lapply", "Применить (Enter)", null)) s.applyLine();
        if (btn("lclear", "Очистить точки (Esc)", null)) { s.linePts.clear(); s.lineVersion++; }
    }

    void drawMove() {
        if (s.moveClip == null) { line("Сначала выдели область — её содержимое можно будет перетащить."); if (btn("mvtake", "Взять выделение", null)) s.ensureMoveClip(); return; }
        String[] ax = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) s.moveOffset[i] = (int) num("mvo" + i, "Сдвиг " + ax[i], s.moveOffset[i], -100000, 100000, 1, true);
        s.moveSkipAir = check("mvair", "Не переносить воздух", s.moveSkipAir, null);
        s.moveCopy = check("mvcopy", "Оставлять на старом месте (копия)", s.moveCopy, null);
        s.moveSelection = check("mvsel", "Перенести и выделение", s.moveSelection, null);
        if (btn("mvrot", "Повернуть на 90° (R) — сейчас " + s.moveRot * 90 + "°", null)) { s.moveRot = (s.moveRot + 1) % 4; s.moveMesh.close(); }
        if (btn("mvmir", "Отразить (Alt+R)" + (s.moveMirror ? " ✓" : ""), null)) { s.moveMirror = !s.moveMirror; s.moveMesh.close(); }
        if (g.primary("mvapply", x, y, w, 18, "Применить (Enter)", null, true)) s.applyMove();
        y += 22;
        if (btn("mvcancel", "Сбросить (Esc)", null)) { s.moveOffset = new int[]{0, 0, 0}; s.moveRot = 0; s.moveMirror = false; s.moveMesh.close(); }
    }

    void drawObjects() {
        Generators.Kind[] kinds = Generators.Kind.values();
        int bw = (w - 4) / 3;
        for (int i = 0; i < kinds.length; i++) {
            int bx = x + (i % 3) * (bw + 2), by = y + (i / 3) * 18;
            if (g.button("ok" + i, bx, by, bw, 16, kinds[i].ru, null, s.objParams.kind == kinds[i], true)) s.objParams.kind = kinds[i];
        }
        y += ((kinds.length + 2) / 3) * 18 + 4;
        Generators.Params p = s.objParams;
        p.height = (int) num("oh", "Высота", p.height, 1, 64, 1, true);
        p.crown = (int) num("oc", "Ширина / радиус", p.crown, 1, 32, 1, true);
        p.randomness = (int) num("or", "Случайность %", p.randomness, 0, 100, 5, true);
        p.scale = num("osc", "Масштаб", p.scale, 0.3, 4, 0.1, false);
        p.seed = (long) num("osd", "Сид", p.seed, -1e9, 1e9, 1, true);
        if (btn("odice", "Случайный сид", null)) p.seed = new java.util.Random().nextInt(1_000_000);
        line("Поворот: " + p.rotation * 90 + "° (R). Материал валунов, сфер, арок, колонн — блок из хотбара.");
    }
}
