package com.geniiy.bridge14;

import com.geniiy.bridge14.proxy.Proxy;
import com.geniiy.bridge14.proxy.Tables;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import com.geniiy.bridge14.proxy.Session;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Bridge14 implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("bridge14");
    public static final Proxy PROXY = new Proxy();
    /** true, пока идёт игра через мост (в творческом видны только предметы 0.14). */
    public static volatile boolean active;
    public static volatile String status = "";

    @Override
    public void onInitializeClient() {
        try { Tables.load(); } catch (Exception e) { LOG.error("Таблицы 0.14 не загрузились", e); }

        // Кнопка в главном меню
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) return;
            int bx = w / 2 + 104;
            // справа от «Одиночной игры», а если занято — справа от следующей кнопки
            int y = h / 4 + 48;
            for (int row = 0; row < 3; row++) {
                int ty = h / 4 + 48 + row * 24;
                boolean busy = false;
                for (ClickableWidget b : Screens.getButtons(screen)) if (b.getX() >= bx - 2 && b.getX() < bx + 64 && b.getY() == ty) busy = true;
                if (!busy) { y = ty; break; }
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Bridge0_14"), b -> client.setScreen(new BridgeScreen(screen)))
                    .dimensions(bx, y, 64, 20).tooltip(Tooltip.of(Text.literal("Серверы и локальные миры Minecraft PE 0.14"))).build());
        });

        // надписи сервера (монеты, ранг…)
        HudRenderCallback.EVENT.register(Hud::render);

        // клавиша: строительство как на телефоне
        KeyBinding reach = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.bridge14.reach", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.bridge14"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (reach.wasPressed()) if (active) ReachAround.toggle();
            ReachAround.tick(client);
            // меню паузы в стиле 0.14
            if (active && client.currentScreen instanceof GameMenuScreen && client.world != null) client.setScreen(new PePauseScreen());
        });

        // обводка клетки, куда встанет блок в мобильном режиме строительства
        WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
            net.minecraft.util.math.BlockPos p = ReachAround.preview;
            if (p == null || ctx.consumers() == null) return;
            net.minecraft.util.math.Vec3d cam = ctx.camera().getPos();
            net.minecraft.client.util.math.MatrixStack ms = ctx.matrixStack();
            ms.push();
            ms.translate(-cam.x, -cam.y, -cam.z);
            net.minecraft.client.render.WorldRenderer.drawBox(ms, ctx.consumers().getBuffer(net.minecraft.client.render.RenderLayer.getLines()),
                    new net.minecraft.util.math.Box(p).expand(0.002), 0f, 0f, 0f, 0.4f);
            ms.pop();
        });

        // В режиме 0.14 в творческом инвентаре — только то, что есть в 0.14
        ItemGroupEvents.MODIFY_ENTRIES_ALL.register((group, entries) -> {
            if (!active) return;
            entries.getDisplayStacks().removeIf(Bridge14::notInPe);
            entries.getSearchTabStacks().removeIf(Bridge14::notInPe);
        });
    }

    private static boolean notInPe(ItemStack s) {
        if (s.isEmpty()) return true;
        if (s.hasNbt() && !s.getNbt().contains("Damage")) return true; // зачарованные книги, зелья с эффектами и т.п.
        return Tables.itemBack(Registries.ITEM.getRawId(s.getItem())) == null;
    }

    /** Слушатель сессии моста (лог, надписи на экране, скины). */
    public static Session.Listener listener() {
        return new Session.Listener() {
            public void status(String s) { LOG.info(s); }
            public void log(String s) { LOG.info(s); }
            public void ended(String reason) { sessionEnded(reason); }
            public void popup(String text, boolean tip) { Hud.set(text, tip); }
            public void skin(java.util.UUID u, byte[] rgba, boolean slim) { SkinStore.put(u, rgba, slim); }
        };
    }

    public static void sessionEnded(String reason) {
        active = false;
        status = reason;
        Hud.clear();
        SkinStore.clearRemote();
        MinecraftClient.getInstance().execute(PROXY::stop);
    }
}
