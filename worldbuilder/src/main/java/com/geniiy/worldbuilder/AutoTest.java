package com.geniiy.worldbuilder;

import com.geniiy.worldbuilder.edit.*;
import com.geniiy.worldbuilder.remote.WorldSource;
import com.geniiy.worldbuilder.session.SaveManager;
import com.geniiy.worldbuilder.ui.EditorLauncher;
import com.geniiy.worldbuilder.ui.EditorScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

import java.nio.file.*;

/** Самопроверка для разработки: -Dwb.autotest=путь_к_миру. Открывает мир, делает снимки, выполняет операции и выходит. */
public final class AutoTest {
    private AutoTest() {}
    static final String PATH = System.getProperty("wb.autotest");
    static boolean started;
    static long t0;
    static int stage;

    public static boolean enabled() { return PATH != null && !PATH.isEmpty(); }

    public static void onTitle() {
        if (!enabled() || started) return;
        started = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            try {
                WorldSource s = WorldSource.offline(Paths.get(PATH));
                EditorLauncher.open(mc.currentScreen, s, null);
                t0 = System.currentTimeMillis();
                WorldBuilderMod.LOG.info("[WorldBuilder][autotest] редактор открыт");
            } catch (Exception e) { WorldBuilderMod.LOG.error("[WorldBuilder][autotest] открыть не удалось", e); mc.scheduleStop(); }
        });
    }

    public static String pending;
    static void shot(String name) { pending = name; }
    /** Вызывается в конце кадра экрана (после интерфейса). */
    public static void endFrame(net.minecraft.client.gui.DrawContext ctx) {
        if (pending == null) return;
        String name = pending; pending = null;
        ctx.draw();
        MinecraftClient mc = MinecraftClient.getInstance();
        ScreenshotRecorder.saveScreenshot(mc.runDirectory, name + ".png", mc.getFramebuffer(), t -> WorldBuilderMod.LOG.info("[WorldBuilder][autotest] снимок {}: {}", name, t.getString()));
    }

    public static void tick(EditorScreen s) {
        if (!enabled()) return;
        long el = System.currentTimeMillis() - t0;
        try {
            switch (stage) {
                case 0 -> { if (el > 9000) {
                    if (PATH.contains("testworld")) { s.cam.x = 30; s.cam.y = 75; s.cam.z = -6; s.cam.yaw = 0; s.cam.pitch = 35; stage = 100; break; }
                    s.cam.x = 150; s.cam.y = 90; s.cam.z = 100; s.cam.yaw = 20; s.cam.pitch = 35; stage++; } }
                case 100 -> { if (el > 16000) { shot("tw_1"); stage++; } }
                case 101 -> { if (el > 17000) { s.cam.x = 30; s.cam.z = 150; s.cam.y = 75; stage++; } }
                case 102 -> { if (el > 24000) { shot("tw_2"); stage++; } }
                case 103 -> { if (el > 25000) { s.cam.x = 30; s.cam.z = 380; stage++; } }
                case 104 -> { if (el > 32000) { shot("tw_3"); stage++; } }
                case 105 -> { if (el > 33000) { s.cam.x = 30; s.cam.z = 640; stage++; } }
                case 106 -> { if (el > 40000) { shot("tw_4"); stage++; } }
                case 107 -> { if (el > 41000) { s.cam.x = 30; s.cam.z = 860; stage++; } }
                case 108 -> { if (el > 48000) { shot("tw_5"); stage++; } }
                case 109 -> { if (el > 49000) { MinecraftClient.getInstance().scheduleStop(); stage++; } }
                case 1 -> { if (el > 15000) { shot("wb_autotest_1_view"); stage++; } }
                case 2 -> { if (el > 16000) {
                    Shape sh = new Shape(Shape.Type.ELLIPSOID, 140, 70, 120, 156, 82, 136);
                    s.ed.selection.add(sh, Shape.Mode.NEW);
                    Selection sel = s.ed.selection.copy();
                    Pattern p = new Pattern(); p.kind = Pattern.Kind.RANDOM; p.entries.add(new int[]{1 << 4, 60}); p.entries.add(new int[]{4 << 4, 40});
                    s.ed.run("autotest fill", "fill", op -> { Ops.fill(s.world, sel, p, false, null, op.cs, op); return null; }, op -> WorldBuilderMod.LOG.info("[WorldBuilder][autotest] fill: {} блоков, ошибка={}", op.cs.changedCount(), op.error));
                    // лестницы и забор для проверки вычисляемых свойств
                    for (int i = 0; i < 6; i++) { s.world.set(130 + i, 75, 110, 53 << 4 | 3, null); s.world.set(130 + i, 75, 112, 85 << 4, null); s.world.set(130 + i, 75, 114, 102 << 4, null); }
                    s.world.set(129, 75, 110, 53 << 4 | 0, null);
                    s.world.set(128, 75, 116, 54 << 4 | 3, null); s.world.set(130, 75, 116, 26 << 4 | 0, null); s.world.set(130, 75, 117, 26 << 4 | 8, null);
                    s.world.set(132, 75, 116, 64 << 4 | 1, null); s.world.set(132, 76, 116, 64 << 4 | 8, null);
                    s.world.set(134, 75, 116, 50 << 4 | 5, null); s.world.set(136, 75, 116, 63 << 4 | 8, null);
                    var t = s.world.getTile(136, 75, 116); if (t != null) t.putString("Text1", "Привет 0.14");
                    stage++; } }
                case 3 -> { if (el > 22000 && !s.ed.busy()) { s.cam.x = 133; s.cam.y = 80; s.cam.z = 104; s.cam.yaw = 0; s.cam.pitch = 30; stage++; } }
                case 4 -> { if (el > 26000) { shot("wb_autotest_2_edit"); stage++; } }
                case 5 -> { if (el > 27000) {
                    Path zip = Paths.get(System.getProperty("java.io.tmpdir"), "wb_autotest.zip");
                    var task = new com.geniiy.worldbuilder.ui.Task("save");
                    SaveManager.saveZip(s.ed, zip, true, true, task);
                    WorldBuilderMod.LOG.info("[WorldBuilder][autotest] ZIP сохранён: {} байт", Files.size(zip));
                    s.ed.undo(m -> WorldBuilderMod.LOG.info("[WorldBuilder][autotest] {}", m));
                    s.lightMode = 1;
                    stage++; } }
                case 6 -> { if (el > 33000) { shot("wb_autotest_3_light"); stage++; } }
                case 7 -> { if (el > 35000) {
                    s.lightMode = 0;
                    s.setTool(EditorScreen.Tool.SELECT);
                    s.ed.selection.clear();
                    s.ed.selection.add(new Shape(Shape.Type.CUBOID, 128, 74, 108, 137, 78, 117), Shape.Mode.NEW);
                    s.copy();
                    s.startPaste(s.ed.clip);
                    s.hover = null;
                    stage++; } }
                case 8 -> { if (el > 41000) { shot("wb_autotest_4_select_paste"); stage++; } }
                case 9 -> { if (el > 42000) {
                    s.pasting = false;
                    Path d = Files.createTempDirectory("wbtw").resolve("t");
                    var tw = com.geniiy.worldbuilder.session.WorldTemplates.testWorld(d);
                    WorldBuilderMod.LOG.info("[WorldBuilder][autotest] тестовый мир: чанков {}", tw.dirtyChunks().size());
                    s.ui().openSave();
                    stage++; } }
                case 10 -> { if (el > 46000) { shot("wb_autotest_5_save_dialog"); stage++; } }
                case 11 -> { if (el > 47000) { WorldBuilderMod.LOG.info("[WorldBuilder][autotest] ГОТОВО"); MinecraftClient.getInstance().scheduleStop(); stage++; } }
                default -> {}
            }
        } catch (Throwable e) { WorldBuilderMod.LOG.error("[WorldBuilder][autotest] ошибка на этапе {}", stage, e); stage = 11; }
    }
}
