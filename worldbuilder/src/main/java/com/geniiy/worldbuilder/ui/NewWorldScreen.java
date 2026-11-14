package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.config.ServerProfile;
import com.geniiy.worldbuilder.edit.Biomes;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.remote.RemoteFs;
import com.geniiy.worldbuilder.remote.WorldSource;
import com.geniiy.worldbuilder.session.SaveManager;
import com.geniiy.worldbuilder.session.WorldTemplates;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Path;
import java.util.*;

/** Экран «Новый мир» (раздел 10 ТЗ). */
public class NewWorldScreen extends WbScreen {
    final Screen parent;
    final ServerProfile prof;
    final RemoteFs fs;
    final String remoteParent;
    final WorldTemplates.Params p = new WorldTemplates.Params();
    Set<String> taken = new HashSet<>();
    double biomeOff, layerOff;
    int pickLayer = -1; boolean pickPlatform;
    String blockSearch = "";

    public NewWorldScreen(Screen parent, ServerProfile prof, RemoteFs fs, String remoteParent) {
        super("Новый мир");
        this.parent = parent; this.prof = prof; this.fs = fs; this.remoteParent = remoteParent;
        if (fs != null) Task.IO.submit(() -> { try { for (RemoteFs.Entry e : fs.list(remoteParent)) taken.add(e.name()); } catch (Exception ignored) {} });
    }

    static final String[][] PRESETS = {
            {"Классический", "7,2x3,2"}, {"Песочница", "7,60x1,3x3,2"}, {"Пустыня", "7,3x1,52x24,8x12"}, {"Снежный", "7,59x1,3x3,2,78"}, {"Водный мир", "7,5x1,5x3,5x12,52x9"}
    };

    void applyPreset(String s) {
        p.layers.clear();
        for (String part : s.split(",")) {
            int n = 1; String b = part;
            if (part.contains("x")) { n = Integer.parseInt(part.substring(0, part.indexOf('x'))); b = part.substring(part.indexOf('x') + 1); }
            String[] im = b.split(":");
            p.layers.add(new int[]{(Integer.parseInt(im[0]) << 4) | (im.length > 1 ? Integer.parseInt(im[1]) : 0), n});
        }
    }

    @Override
    protected void draw(float delta) {
        gui.rect(0, 0, W, H, 0xFF17181B);
        gui.text("Новый мир" + (prof != null ? " — " + prof.title() + ":" + remoteParent : ""), 12, 10, Gui.ACCENT);
        int x = 12, y = 28, colW = Math.min(360, (W - 36) / 2);
        gui.panel(x, y, colW, H - y - 44);
        int ix = x + 8, iw = colW - 16, yy = y + 8, lab = 110;
        gui.label("Название папки", ix, yy + 5);
        String nf = gui.field("folder", ix + lab, yy, iw - lab, 18, p.folder, null, false, !validFolder());
        if (!nf.equals(p.folder)) { if (p.levelName.equals(p.folder)) p.levelName = nf; p.folder = nf; }
        yy += 20;
        if (!p.folder.matches("[A-Za-z0-9_-]+")) { gui.text("Только латиница, цифры, «_» и «-», без пробелов", ix + lab, yy, Gui.ERR); yy += 10; }
        else if (taken.contains(p.folder)) { gui.text("Имя занято на сервере — например, " + p.folder + "_2", ix + lab, yy, Gui.ERR); yy += 10; }
        yy += 4;
        gui.label("LevelName", ix, yy + 5);
        p.levelName = gui.field("lname", ix + lab, yy, iw - lab, 18, p.levelName, null, false, false);
        yy += 26;
        gui.text("Шаблон", ix, yy, Gui.TEXT2); yy += 12;
        String[][] cards = {{"Пустой", "Ничего нет. generator = flat «2;0;1;» — мир действительно пустой."}, {"Пустой с платформой", "Платформа N×N из блока на высоте Y. Спавн — над ней."}, {"Плоский", "Слои снизу вверх. Сервер продолжит местность так же."}};
        int cw = (iw - 8) / 3;
        for (int i = 0; i < 3; i++) {
            int cx = ix + i * (cw + 4);
            boolean sel = p.kind.ordinal() == i;
            boolean hv = gui.hover(cx, yy, cw, 62);
            gui.rrect(cx, yy, cw, 62, sel ? Gui.BTN_SEL : hv ? Gui.BTN_H : Gui.BTN);
            if (sel) gui.frame(cx, yy, cw, 62, Gui.ACCENT);
            gui.text(cards[i][0], cx + 5, yy + 5, sel ? Gui.ACCENT : Gui.TEXT);
            int ly = yy + 17;
            for (String l : wrap(gui, cards[i][1], cw - 10)) { gui.text(l, cx + 5, ly, Gui.TEXT2); ly += 9; }
            if (gui.clicked(cx, yy, cw, 62, 0)) { p.kind = WorldTemplates.Kind.values()[i]; if (i == 0 && p.radius == 8) p.radius = 0; else if (i != 0 && p.radius == 0) p.radius = 8; }
        }
        yy += 70;
        switch (p.kind) {
            case EMPTY -> gui.text("Подсказка: генератор void в Genisys ставит траву в (8,64,8) каждого чанка — поэтому используется flat.", ix, yy, Gui.TEXT2);
            case PLATFORM -> {
                p.platformSize = (int) gui.number("psize", ix, yy, iw / 2 - 4, 16, "Размер", p.platformSize, 1, 256, 1, true);
                p.platformY = (int) gui.number("py", ix + iw / 2, yy, iw / 2, 16, "Высота Y", p.platformY, 0, 127, 1, true);
                yy += 20;
                if (gui.button("pblock", ix, yy, iw, 16, "Блок: " + PeBlocks.get().displayName(p.platformBlock) + " (выбрать)", null)) { pickPlatform = true; pickLayer = -1; }
                yy += 20;
            }
            case FLAT -> {
                gui.text("Пресеты:", ix, yy + 3, Gui.TEXT2);
                int bx = ix + 50;
                for (String[] pr : PRESETS) { int bw = gui.width(pr[0]) + 10; if (gui.button("pre" + pr[0], bx, yy, bw, 14, pr[0], null)) applyPreset(pr[1]); bx += bw + 2; }
                yy += 18;
                gui.text("Слои (снизу вверх):", ix, yy, Gui.TEXT2);
                if (gui.button("addl", ix + iw - 60, yy - 2, 60, 14, "+ слой", null)) p.layers.add(new int[]{1 << 4, 1});
                yy += 14;
                int lh = Math.max(60, H - 44 - yy - 70);
                layerOff = gui.scrollArea("layers", ix, yy, iw, lh, p.layers.size() * 18, layerOff);
                gui.scissor(ix, yy, iw, lh);
                for (int i = p.layers.size() - 1, row = 0; i >= 0; i--, row++) {
                    int[] l = p.layers.get(i);
                    int ry = yy + row * 18 - (int) layerOff;
                    gui.text("#" + (i + 1), ix + 2, ry + 4, Gui.TEXT2);
                    if (gui.button("lb" + i, ix + 24, ry, iw - 150, 16, PeBlocks.get().displayName(l[0]) + " (" + (l[0] >> 4) + ":" + (l[0] & 15) + ")", "Выбрать блок", pickLayer == i, true)) { pickLayer = i; pickPlatform = false; }
                    l[1] = (int) gui.number("lc" + i, ix + iw - 122, ry, 50, 16, "", l[1], 1, 128, 1, true);
                    if (gui.button("lu" + i, ix + iw - 68, ry, 20, 16, "▲", "Выше") && i < p.layers.size() - 1) { Collections.swap(p.layers, i, i + 1); }
                    if (gui.button("ld" + i, ix + iw - 46, ry, 20, 16, "▼", "Ниже") && i > 0) { Collections.swap(p.layers, i, i - 1); }
                    if (gui.button("lx" + i, ix + iw - 24, ry, 20, 16, "−", "Удалить слой") && p.layers.size() > 1) { p.layers.remove(i); break; }
                }
                gui.noScissor();
                yy += lh + 4;
                int h = p.height();
                gui.text("Итоговая высота: " + h + (h > 128 ? " — больше 128!" : ""), ix, yy, h > 128 ? Gui.ERR : Gui.TEXT);
                yy += 11;
                gui.text("Пресет Genisys: " + WorldTemplates.preset(p), ix, yy, Gui.TEXT2);
            }
        }
        // правая колонка
        int rx = x + colW + 12, rw = W - rx - 12;
        gui.panel(rx, y, rw, H - y - 44);
        int rix = rx + 8, riw = rw - 16, ry = y + 8;
        if (pickLayer >= 0 || pickPlatform) {
            gui.text("Выбери блок:", rix, ry, Gui.ACCENT);
            blockSearch = gui.field("bsearch", rix + 80, ry - 3, riw - 80, 16, blockSearch, "поиск…", false, false);
            ry += 18;
            List<Integer> states = new ArrayList<>(PeBlocks.get().paletteStates(false));
            if (!blockSearch.isBlank()) states.removeIf(st -> !PeBlocks.get().matches(st, blockSearch));
            int cols = Math.max(1, riw / 120);
            for (int i = 0; i < Math.min(states.size(), cols * 20); i++) {
                int st = states.get(i);
                int bx = rix + (i % cols) * 120, by = ry + (i / cols) * 16;
                if (gui.button("bp" + i, bx, by, 118, 14, PeBlocks.get().shortName(st), null)) {
                    if (pickPlatform) p.platformBlock = st; else if (pickLayer < p.layers.size()) p.layers.get(pickLayer)[0] = st;
                    pickLayer = -1; pickPlatform = false;
                }
            }
        } else {
            gui.text("Биом", rix, ry, Gui.TEXT2); ry += 12;
            int lh = 130;
            biomeOff = gui.scrollArea("nbio", rix, ry, riw, lh, Biomes.ALL.size() * 13, biomeOff);
            gui.scissor(rix, ry, riw, lh);
            int i = 0;
            for (Biomes.B b : Biomes.ALL.values()) {
                int by = ry + i * 13 - (int) biomeOff;
                boolean sel = b.id() == p.biome;
                if (sel || gui.hover(rix, by, riw - 6, 13)) gui.rect(rix, by, riw - 6, 13, sel ? Gui.BTN_SEL : Gui.BTN_H);
                gui.rect(rix + 2, by + 2, 9, 9, 0xFF000000 | Biomes.grassColor(b.id()));
                gui.text(b.id() + " " + b.ru(), rix + 14, by + 3, Gui.TEXT);
                if (gui.hover(rix, by, riw - 6, 13) && gui.my >= ry && gui.my < ry + lh && gui.clicked(rix, by, riw - 6, 13, 0)) p.biome = b.id();
                i++;
            }
            gui.noScissor();
            ry += lh + 8;
            p.radius = (int) gui.number("rad", rix, ry, riw, 16, "Заранее созданная область (радиус в чанках)", p.radius, 0, 32, 1, true);
            ry += 20;
            gui.text("= " + (2 * p.radius + 1) + "×" + (2 * p.radius + 1) + " чанков вокруг спавна", rix, ry, Gui.TEXT2); ry += 14;
            p.autoSpawn = gui.check("aspawn", rix, ry, "Спавн автоматически", p.autoSpawn, null); ry += 16;
            if (!p.autoSpawn) {
                int t3 = (riw - 8) / 3;
                p.spawnX = (int) gui.number("sx", rix, ry, t3, 16, "X", p.spawnX, -3e7, 3e7, 1, true);
                p.spawnY = (int) gui.number("sy", rix + t3 + 4, ry, t3, 16, "Y", p.spawnY, 0, 127, 1, true);
                p.spawnZ = (int) gui.number("sz", rix + 2 * t3 + 8, ry, t3, 16, "Z", p.spawnZ, -3e7, 3e7, 1, true);
                ry += 20;
            }
            gui.label("Режим игры", rix, ry + 4);
            p.gameType = gui.segmented("gtype", rix + 80, ry, riw - 80, 16, new String[]{"Выживание (0)", "Творческий (1)"}, p.gameType, null); ry += 20;
            p.time = (long) gui.number("ntime", rix, ry, riw, 16, "Время суток", p.time, 0, 24000, 100, true); ry += 20;
            p.seed = (long) gui.number("nseed", rix, ry, riw, 16, "Сид", p.seed, -9.2e18, 9.2e18, 1, true); ry += 20;
        }
        int by = H - 34;
        if (gui.button("nback", 12, by, 100, 22, "Назад", null)) close();
        if (gui.primary("ncreate", W - 172, by, 160, 22, "Создать", "Мир создаётся локально и открывается в редакторе. На сервер попадёт при сохранении.", validFolder() && p.height() <= 128)) create();
    }

    boolean validFolder() { return p.folder.matches("[A-Za-z0-9_-]+") && !taken.contains(p.folder); }

    void create() {
        Task task = Task.start("Создание мира", t -> {
            JavaStates.init();
            t.set("Создаю мир…", -1);
            String sid = Long.toString(System.currentTimeMillis(), 36);
            Path work = SaveManager.workDir(sid).getParent().resolve(p.folder);
            WorldSource.deleteTree(work);
            PeWorld w = WorldTemplates.create(work, p);
            WorldSource src = new WorldSource();
            src.cacheDir = work;
            if (prof != null) { src.profile = prof; src.fs = fs; src.remotePath = RemoteFs.join(remoteParent, p.folder); }
            else src.offlinePath = null;
            client.execute(() -> EditorLauncher.openWorld(parent, src, w, sid));
        }, t -> { if (t.error != null) toastError("Не удалось создать мир: " + t.error.getMessage(), t.error); }, Task.IO);
        dialogs.push(new Task.ProgressDialog(task));
    }

    @Override public void close() { client.setScreen(parent); }
}
