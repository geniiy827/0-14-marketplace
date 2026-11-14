package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.remote.WorldSource;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Files;

/** Настройки мода (раздел 27), включая переназначение клавиш. */
public class SettingsScreen extends WbScreen {
    final Screen parent;
    int section;
    double off;
    String waitingKey;
    String keySearch = "";
    long cacheSize = -1;

    static final String[] SECTIONS = {"Интерфейс", "Камера", "Рендер", "Редактирование", "Подключения", "Сохранение", "Клавиши", "Память"};

    public SettingsScreen(Screen parent) {
        super("Настройки World Builder");
        this.parent = parent;
        Task.IO.submit(() -> { try (var w = Files.walk(WorldSource.cacheRoot())) { cacheSize = w.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum(); } catch (Exception e) { cacheSize = 0; } });
    }

    @Override
    protected void draw(float delta) {
        WbConfig c = WbConfig.get();
        gui.rect(0, 0, W, H, 0xFF17181B);
        gui.text("Настройки World Builder 0.14.3", 12, 10, Gui.ACCENT);
        int lx = 12, ly = 28;
        for (int i = 0; i < SECTIONS.length; i++) if (gui.button("sec" + i, lx, ly + i * 22, 140, 20, SECTIONS[i], null, section == i, true)) { section = i; off = 0; }
        int x = 164, w = Math.min(520, W - x - 12), y0 = 28, h = H - y0 - 44;
        gui.panel(x, y0, w, h);
        gui.scissor(x, y0, w, h);
        int ix = x + 10, iw = w - 20;
        int y = y0 + 10 - (int) off, start = y;
        switch (section) {
            case 0 -> {
                c.uiScalePercent = (int) gui.number("uiscale", ix, y, iw, 16, "Масштаб интерфейса, % (0 = авто)", c.uiScalePercent, 0, 200, 5, true); y += 22;
                gui.text("Не зависит от GUI Scale игры. Допустимо 75–200 %, 0 — подобрать под окно.", ix, y, Gui.TEXT2); y += 14;
                int sel = gui.segmented("icon3", ix, y, iw, 16, new String[]{"Иконки 16 px", "24 px", "32 px"}, c.paletteIcon <= 16 ? 0 : c.paletteIcon <= 24 ? 1 : 2, null);
                c.paletteIcon = sel == 0 ? 16 : sel == 1 ? 24 : 32; y += 22;
                c.selectionAlpha = (float) gui.slider("salpha", ix + 150, y, iw - 200, 12, c.selectionAlpha, 0.05, 0.8, "%.2f"); gui.text("Прозрачность выделения", ix, y + 2, Gui.TEXT2); y += 20;
                c.tooltips = gui.check("tips", ix, y, "Показывать подсказки", c.tooltips, null); y += 18;
                c.tooltipDelayMs = (int) gui.number("tipd", ix, y, iw, 16, "Задержка подсказок, мс", c.tooltipDelayMs, 0, 3000, 50, true); y += 22;
                if (gui.button("tour", ix, y, 200, 18, "Сбросить тур для новичка", null)) { c.tourDone = false; toast("Тур покажется при следующем открытии редактора"); }
                y += 24;
            }
            case 1 -> {
                c.fov = (int) gui.number("fov", ix, y, iw, 16, "Поле зрения (FOV)", c.fov, 30, 110, 1, true); y += 22;
                c.sensitivity = (float) gui.number("sens", ix, y, iw, 16, "Чувствительность", c.sensitivity, 0.1, 5, 0.1, false); y += 22;
                c.invertY = gui.check("inv", ix, y, "Инверсия Y", c.invertY, null); y += 18;
                c.smoothMove = gui.check("smooth", ix, y, "Плавный разгон и торможение", c.smoothMove, null); y += 18;
                c.accelTime = (float) gui.number("accel", ix, y, iw, 16, "Время разгона, с", c.accelTime, 0.01, 2, 0.05, false); y += 22;
                c.baseSpeed = (float) gui.number("bspeed", ix, y, iw, 16, "Базовая скорость", c.baseSpeed, 1, 100, 1, false); y += 22;
            }
            case 2 -> {
                c.renderDistance = (int) gui.number("rd", ix, y, iw, 16, "Дальность прорисовки (чанки)", c.renderDistance, 4, 32, 1, true); y += 22;
                c.lightMode = gui.segmented("lm", ix, y, iw, 16, new String[]{"Полная яркость", "Как в игре (день)", "Ночь"}, c.lightMode, null); y += 22;
                c.fog = gui.check("fog", ix, y, "Туман у края дальности", c.fog, null); y += 18;
                c.chunkGrid = gui.check("cg", ix, y, "Сетка чанков", c.chunkGrid, null); y += 18;
                c.worldBounds = gui.check("wb", ix, y, "Границы мира (y=128)", c.worldBounds, null); y += 18;
                c.showInvisible = gui.check("si", ix, y, "Показывать невидимые блоки", c.showInvisible, null); y += 18;
                c.meshThreads = (int) gui.number("mt", ix, y, iw, 16, "Потоков мешинга (0 = авто)", c.meshThreads, 0, 16, 1, true); y += 22;
                gui.text("Небо: верх / низ (HEX)", ix, y, Gui.TEXT2); y += 12;
                String a = gui.field("skyt", ix, y, 90, 16, String.format("%06X", c.skyTop & 0xFFFFFF), null, false, false);
                String b = gui.field("skyb", ix + 96, y, 90, 16, String.format("%06X", c.skyBottom & 0xFFFFFF), null, false, false);
                try { c.skyTop = 0xFF000000 | Integer.parseInt(a, 16); c.skyBottom = 0xFF000000 | Integer.parseInt(b, 16); } catch (Exception ignored) {}
                y += 22;
                gui.text("Изменения дальности и потоков применятся при следующем открытии редактора.", ix, y, Gui.TEXT2); y += 14;
            }
            case 3 -> {
                c.smartOrientation = gui.check("so", ix, y, "Умная ориентация по умолчанию", c.smartOrientation, null); y += 18;
                c.pasteSkipAir = gui.check("psa", ix, y, "Пропускать воздух при вставке", c.pasteSkipAir, null); y += 18;
                c.pickLiquids = gui.check("pl", ix, y, "Выбирать жидкости курсором", c.pickLiquids, null); y += 18;
                c.maxOpBlocks = (long) gui.number("mob", ix, y, iw, 16, "Максимум блоков в операции", c.maxOpBlocks, 1000, 500_000_000, 100000, true); y += 22;
                c.undoSteps = (int) gui.number("us", ix, y, iw, 16, "Шагов отмены", c.undoSteps, 10, 10000, 10, true); y += 22;
                c.undoMemoryMb = (int) gui.number("um", ix, y, iw, 16, "Память истории, МБ", c.undoMemoryMb, 32, 8192, 32, true); y += 22;
                c.autosaveMinutes = (int) gui.number("as", ix, y, iw, 16, "Автосохранение сессии, мин (0 — выкл)", c.autosaveMinutes, 0, 120, 1, true); y += 22;
            }
            case 4 -> {
                c.autofill = gui.check("af", ix, y, "Автоматически подставлять сохранённые данные", c.autofill, null); y += 18;
                c.autoConnect = gui.check("ac", ix, y, "Сразу подключаться к последнему профилю", c.autoConnect, "Если пароль сохранён"); y += 18;
                if (gui.button("forget", ix, y, 260, 18, "Забыть все сохранённые пароли", null))
                    dialogs.push(new ChoiceDialog("Забыть пароли?", "Все сохранённые пароли будут удалены из хранилища.", new String[]{"Забыть", "Отмена"}, i -> { if (i == 0) { c.forgetAllPasswords(); toast("Пароли удалены"); } }));
                y += 24;
                for (String l : wrap(gui, "Пароли хранятся в config/worldbuilder/profiles в зашифрованном виде (AES-GCM, ключ в config/worldbuilder/.key). Любой, у кого есть доступ к этому компьютеру, сможет их получить.", iw)) { gui.text(l, ix, y, Gui.TEXT2); y += 10; }
            }
            case 5 -> {
                c.backup = gui.check("bk", ix, y, "Резервная копия перед перезаписью", c.backup, null); y += 18;
                c.keepBackups = (int) gui.number("kb", ix, y, iw, 16, "Хранить последних копий", c.keepBackups, 1, 100, 1, true); y += 22;
                c.preventPopulation = gui.check("pp", ix, y, "Запрещать серверу досыпать руды и деревья", c.preventPopulation, null); y += 18;
                c.computeLight = gui.check("cl", ix, y, "Пересчитывать свет", c.computeLight, null); y += 18;
                gui.text("Папка для ZIP по умолчанию:", ix, y, Gui.TEXT2); y += 12;
                c.zipDir = gui.field("zipdir", ix, y, iw - 80, 16, c.zipDir, System.getProperty("user.home"), false, false);
                if (gui.button("zipb", ix + iw - 76, y, 76, 16, "Выбрать…", null)) { var p = FileDialogs.selectFolder("Папка для ZIP", c.zipDir.isEmpty() ? System.getProperty("user.home") : c.zipDir); if (p != null) c.zipDir = p.toString(); }
                y += 22;
            }
            case 6 -> {
                keySearch = gui.field("ksearch", ix, y, iw, 16, keySearch, "поиск действия…", false, false); y += 22;
                for (Keys.K k : Keys.ALL.values()) {
                    if (!keySearch.isBlank() && !k.ru().toLowerCase().contains(keySearch.toLowerCase())) continue;
                    int cur = Keys.key(k.id());
                    boolean conflict = false;
                    for (Keys.K o : Keys.ALL.values()) if (o != k && Keys.key(o.id()) == cur) conflict = true;
                    gui.text(k.ru(), ix, y + 4, conflict ? Gui.WARN : Gui.TEXT);
                    String lbl = k.id().equals(waitingKey) ? "нажми клавишу…" : Keys.name(cur);
                    if (gui.button("kb" + k.id(), ix + iw - 200, y, 120, 16, lbl, conflict ? "Конфликт с другим действием" : null, k.id().equals(waitingKey), true)) waitingKey = k.id();
                    if (gui.button("kd" + k.id(), ix + iw - 76, y, 76, 16, "По умолч.", null)) { c.keys.remove(k.id()); }
                    y += 18;
                }
                y += 6;
                if (gui.button("kall", ix, y, 200, 18, "Все — по умолчанию", null)) c.keys.clear();
                y += 24;
                gui.text("Сочетания с Ctrl (Ctrl+Z, Ctrl+C…) фиксированы.", ix, y, Gui.TEXT2); y += 14;
            }
            default -> {
                c.cacheBudgetMb = (int) gui.number("cb", ix, y, iw, 16, "Бюджет кэша чанков, МБ", c.cacheBudgetMb, 128, 16384, 64, true); y += 22;
                gui.text("Папка кэша: " + WorldSource.cacheRoot(), ix, y, Gui.TEXT2); y += 14;
                if (gui.button("clearcache", ix, y, 260, 18, "Очистить кэш (" + (cacheSize < 0 ? "…" : Task.bytes(cacheSize)) + ")", "Скачанные копии миров. Несохранённая работа не пострадает."))
                    dialogs.push(new ChoiceDialog("Очистить кэш?", "Будут удалены скачанные копии миров. Следующее открытие мира скачает его заново.", new String[]{"Очистить", "Отмена"}, i -> {
                        if (i == 0) Task.IO.submit(() -> { try { WorldSource.deleteTree(WorldSource.cacheRoot()); cacheSize = 0; } catch (Exception e) { toastError("Не удалось очистить кэш", e); } });
                    }));
                y += 24;
            }
        }
        int end = y;
        gui.noScissor();
        off = gui.scrollArea("setscroll", x, y0, w, h, end - start + 20, off);
        if (gui.primary("sdone", W - 132, H - 34, 120, 22, "Готово", null, true)) close();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (waitingKey != null) {
            if (key != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) WbConfig.get().keys.put(waitingKey, key);
            waitingKey = null;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override public void close() { WbConfig.get().save(); client.setScreen(parent); }
}
