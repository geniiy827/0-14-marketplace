package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.edit.ChangeSet;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.pe.LevelDat;

/** Вкладка «Мир»: поля level.dat и статистика (раздел 25.6). */
class WorldTab {
    final EditorUi ui; final EditorScreen s; final Gui g;
    WorldTab(EditorUi ui) { this.ui = ui; this.s = ui.s; this.g = ui.g; }

    static boolean seedEdit, genEdit;

    void draw(int px, int py, int pw, int ph) {
        int x = px + 6, w = pw - 12;
        LevelDat l = s.world.level;
        g.scissor(px, py, pw, ph);
        int y = py - (int) ui.worldOff, start = y;
        int lab = 90;
        g.label("Имя (LevelName)", x, y + 4);
        String nn = g.field("lvname", x + lab, y, w - lab, 16, l.levelName(), null, false, false);
        if (!nn.equals(l.levelName())) { String old = l.levelName(); l.setLevelName(nn); s.world.levelDirty = true; }
        y += 22;
        g.text("Точка спавна", x, y, Gui.TEXT2); y += 10;
        int third = (w - 4) / 3;
        int sx = (int) g.number("spx", x, y, third, 16, "X", l.spawnX(), -3e7, 3e7, 1, true);
        int sy = (int) g.number("spy", x + third + 2, y, third, 16, "Y", l.spawnY(), 0, 255, 1, true);
        int sz = (int) g.number("spz", x + 2 * third + 4, y, third, 16, "Z", l.spawnZ(), -3e7, 3e7, 1, true);
        if (sx != l.spawnX() || sy != l.spawnY() || sz != l.spawnZ()) setSpawn(sx, sy, sz);
        y += 20;
        if (g.button("spawnpick", x, y, w, 16, "Указать в мире (кликни по блоку)", "Спавн — над блоком")) {
            s.toast("Кликни по блоку — спавн будет над ним");
            pickSpawn = true;
        }
        y += 22;
        g.label("Режим игры", x, y + 4);
        int gm = g.segmented("gm", x + lab, y, w - lab, 16, new String[]{"Выживание", "Творческий"}, Math.min(1, Math.max(0, l.gameType())), null);
        if (gm != l.gameType()) { l.setGameType(gm); s.world.levelDirty = true; }
        y += 22;
        g.label("Время", x, y + 4);
        long tm = (long) g.number("time", x + lab, y, w - lab, 16, "", l.time(), 0, 1e12, 100, true);
        if (tm != l.time()) { l.setTime(tm); s.world.levelDirty = true; }
        y += 22;
        boolean hc = g.check("hardcore", x, y, "Hardcore", l.hardcore(), null);
        if (hc != l.hardcore()) { l.setHardcore(hc); s.world.levelDirty = true; }
        y += 20;
        g.label("Сид", x, y + 4);
        if (!seedEdit) {
            g.text(String.valueOf(l.seed()), x + lab, y + 4, Gui.TEXT);
            if (g.button("seededit", x + w - 60, y, 60, 16, "изменить", "Сид влияет на генерацию новых чанков")) seedEdit = true;
        } else {
            long sd = (long) g.number("seed", x + lab, y, w - lab, 16, "", l.seed(), -9.2e18, 9.2e18, 1, true);
            if (sd != l.seed()) { l.setSeed(sd); s.world.levelDirty = true; }
        }
        y += 22;
        g.label("Генератор", x, y + 4);
        if (!genEdit) {
            g.text(g.ellipsize(l.generatorName() + (l.generatorOptions().isEmpty() ? "" : " (" + l.generatorOptions() + ")"), w - lab - 64), x + lab, y + 4, Gui.TEXT);
            if (g.button("genedit", x + w - 60, y, 60, 16, "изменить", null)) genEdit = true;
            y += 20;
        } else {
            String gn = g.field("genname", x + lab, y, w - lab, 16, l.generatorName(), "normal / flat / hell / void", false, false);
            if (!gn.equals(l.generatorName())) { l.setGeneratorName(gn); s.world.levelDirty = true; }
            y += 20;
            String go = g.field("genopt", x + lab, y, w - lab, 16, l.generatorOptions(), "опции (для flat: 2;7,2x3,2;1;)", false, false);
            if (!go.equals(l.generatorOptions())) { l.setGeneratorOptions(go); s.world.levelDirty = true; }
            y += 20;
            for (String line : WbScreen.wrap(g, "Внимание: генератор влияет на то, что сервер сгенерирует в новых чанках.", w)) { g.text(line, x, y, Gui.WARN); y += 10; }
        }
        y += 8;
        g.hsep(x, y, w); y += 6;
        g.text("Статистика", x, y, Gui.TEXT2); y += 12;
        g.text("Чанков в файлах: " + s.world.existingChunks().size(), x, y, Gui.TEXT); y += 11;
        g.text("Регионов: " + s.world.regionCount(), x, y, Gui.TEXT); y += 11;
        long size = 0;
        try { for (String f : s.world.allFiles()) { var p = s.world.dir.resolve(f); if (java.nio.file.Files.exists(p)) size += java.nio.file.Files.size(p); } } catch (Exception ignored) {}
        g.text("Размер: " + Task.bytes(size), x, y, Gui.TEXT); y += 11;
        g.text("Изменённых чанков: " + s.world.dirtyChunks().size(), x, y, Gui.TEXT); y += 11;
        g.text("Версия level.dat: " + l.version() + (l.version() == 19133 ? " (McRegion)" : ""), x, y, Gui.TEXT2); y += 11;
        if (!s.world.warnings.isEmpty()) {
            y += 4;
            g.text("Предупреждения (" + s.world.warnings.size() + ")", x, y, Gui.WARN); y += 11;
            for (int i = 0; i < Math.min(8, s.world.warnings.size()); i++) for (String line : WbScreen.wrap(g, s.world.warnings.get(i), w)) { g.text(line, x, y, Gui.TEXT2); y += 10; }
        }
        int end = y;
        g.noScissor();
        ui.worldOff = g.scrollArea("worldtab", px, py, pw, ph, end - start + 8, ui.worldOff);
    }

    static boolean pickSpawn;

    void setSpawn(int x, int y, int z) {
        LevelDat l = s.world.level;
        int ox = l.spawnX(), oy = l.spawnY(), oz = l.spawnZ();
        l.setSpawn(x, y, z);
        s.world.levelDirty = true;
        ChangeSet cs = new ChangeSet("Точка спавна", "spawn");
        cs.extra.add(new Runnable[]{() -> { l.setSpawn(ox, oy, oz); s.world.levelDirty = true; }, () -> { l.setSpawn(x, y, z); s.world.levelDirty = true; }});
        s.ed.history.push(cs);
    }

    static Tag noop() { return null; }
}
