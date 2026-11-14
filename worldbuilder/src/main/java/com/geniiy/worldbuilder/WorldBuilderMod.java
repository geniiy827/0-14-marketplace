package com.geniiy.worldbuilder;

import com.geniiy.worldbuilder.blocks.ModBlocks;
import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.ui.ConnectScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Точка входа клиента: кнопка в главном меню, загрузка таблицы блоков, слои рендера своих блоков. */
public class WorldBuilderMod implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("WorldBuilder");
    public static final Identifier ICON = new Identifier("worldbuilder", "textures/gui/icon16.png");

    @Override
    public void onInitializeClient() {
        try {
            PeBlocks t = PeBlocks.get();
            LOG.info("[WorldBuilder] Таблица блоков MCPE 0.14.3 загружена: {} блоков, {} записей java_to_pe", t.all.size(), t.javaToPe.size());
        } catch (Exception e) {
            LOG.error("[WorldBuilder] Не удалось загрузить таблицу блоков", e);
        }
        if (ModBlocks.UNKNOWN == null) new ModBlocks().onInitialize();
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.INVISIBLE_BEDROCK, RenderLayer.getTranslucent());
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.ITEM_FRAME, RenderLayer.getCutout());

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) return;
            AutoTest.onTitle();
            List<ClickableWidget> buttons = Screens.getButtons(screen);
            ClickableWidget mp = null;
            for (ClickableWidget b : buttons) {
                if (b.getMessage().getContent() instanceof TranslatableTextContent tc && "menu.multiplayer".equals(tc.getKey())) { mp = b; break; }
            }
            int x, y;
            if (mp != null) {
                x = mp.getX(); y = mp.getY() + 24;
                for (ClickableWidget b : buttons) if (b.getY() >= y && b != mp) b.setY(b.getY() + 24);
            } else { x = w / 2 - 100; y = h / 4 + 48 + 48; }
            ButtonWidget btn = new IconButton(x, y, 200, 20, Text.literal("World Builder 0.14.3"), b -> MinecraftClient.getInstance().setScreen(new ConnectScreen(screen)));
            buttons.add(btn);
        });
    }

    /** Кнопка с маленькой иконкой слева. */
    static class IconButton extends ButtonWidget {
        IconButton(int x, int y, int w, int h, Text msg, PressAction a) { super(x, y, w, h, msg, a, DEFAULT_NARRATION_SUPPLIER); }
        @Override
        public void renderButton(DrawContext ctx, int mouseX, int mouseY, float delta) {
            super.renderButton(ctx, mouseX, mouseY, delta);
            ctx.drawTexture(ICON, getX() + 3, getY() + 2, 0, 0, 16, 16, 16, 16);
        }
    }
}
