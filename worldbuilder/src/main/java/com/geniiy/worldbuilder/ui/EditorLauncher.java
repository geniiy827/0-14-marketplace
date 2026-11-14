package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.remote.WorldSource;
import com.geniiy.worldbuilder.session.Editor;
import com.geniiy.worldbuilder.session.SaveManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import java.nio.file.Path;

/** Открытие редактора: рабочая копия сеанса, предупреждение о работающем сервере. */
public final class EditorLauncher {
    private EditorLauncher() {}

    public static void open(Screen parent, WorldSource src, PeWorld preopened) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            JavaStates.init();
            String sid = Long.toString(System.currentTimeMillis(), 36);
            PeWorld w = preopened;
            if (w == null) {
                String p = PeWorld.detectProblem(src.cacheDir);
                if (p != null) throw new java.io.IOException(p);
                Path work = SaveManager.makeWorkCopy(src.cacheDir, sid);
                w = PeWorld.open(work);
            }
            Editor ed = new Editor(w, src, sid);
            SaveManager.writeSessionInfo(ed, true, null);
            SaveManager.cleanupClosed(sid);
            EditorScreen es = new EditorScreen(parent, ed);
            mc.setScreen(es);
            if (src.isRemote() && !WbConfig.get().hideServerWarning && !warned) {
                warned = true;
                es.dialogs.push(new WbScreen.ChoiceDialog("Сервер может перезаписать изменения",
                        "Пока сервер запущен и этот мир загружен, сервер может перезаписать твои изменения при выключении. Перед загрузкой правок выключи сервер в панели, а после — включи.",
                        new String[]{"Понятно", "Больше не показывать"}, i -> { if (i == 1) { WbConfig.get().hideServerWarning = true; WbConfig.get().save(); } }));
            }
            if (!w.warnings.isEmpty()) es.toast("Есть предупреждения при чтении мира — см. вкладку «Мир»", Gui.WARN, String.join("\n", w.warnings));
        } catch (Exception e) {
            WorldBuilderMod.LOG.error("[WorldBuilder] Не удалось открыть мир", e);
            if (parent instanceof WbScreen ws) ws.toastError("Не удалось открыть мир: " + e.getMessage(), e);
        }
    }

    static boolean warned;

    /** Открыть уже созданный мир (новый мир, восстановление). */
    public static void openWorld(Screen parent, WorldSource src, PeWorld w, String sessionId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            JavaStates.init();
            Editor ed = new Editor(w, src, sessionId);
            SaveManager.writeSessionInfo(ed, true, null);
            mc.setScreen(new EditorScreen(parent, ed));
        } catch (Exception e) {
            WorldBuilderMod.LOG.error("[WorldBuilder] Не удалось открыть мир", e);
            if (parent instanceof WbScreen ws) ws.toastError("Не удалось открыть мир: " + e.getMessage(), e);
        }
    }
}
