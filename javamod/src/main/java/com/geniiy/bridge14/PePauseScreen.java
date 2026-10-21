package com.geniiy.bridge14;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Меню паузы как в 0.14: кнопки слева, список игроков справа. */
public class PePauseScreen extends Screen {
    private int scroll;

    public PePauseScreen() { super(Text.literal("Меню игры")); }

    @Override
    protected void init() {
        int lw = Math.min(200, width / 2 - 20);
        int x = width / 4 - lw / 2;
        if (x < 10) x = 10;
        int y = height / 2 - 52;
        addDrawableChild(new PeStyle.Button(x, y, lw, 24, Text.literal("Вернуться к игре"), b -> client.setScreen(null)));
        addDrawableChild(new PeStyle.Button(x, y + 30, lw, 24, Text.literal("Настройки"), b -> client.setScreen(new OptionsScreen(this, client.options))));
        addDrawableChild(new PeStyle.Button(x, y + 60, lw, 24, Text.literal("Выйти в главное меню"), b -> quit()));
        addDrawableChild(new PeStyle.Button(x, y + 90, lw, 24, Text.literal("Настройки мода"), b -> client.setScreen(new ModSettingsScreen(this))));
    }

    private void quit() {
        if (client.world != null) client.world.disconnect();
        client.disconnect();
        client.setScreen(new BridgeScreen(new TitleScreen()));
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) { scroll = Math.max(0, scroll - (int) Math.signum(amount)); return true; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, width, height, 0x80101010, 0xA0101010);
        int lw = Math.min(200, width / 2 - 20);
        int x = Math.max(10, width / 4 - lw / 2);
        ctx.drawCenteredTextWithShadow(textRenderer, "Меню игры", x + lw / 2, height / 2 - 72, 0xFFFFFF);

        // игроки
        List<PlayerListEntry> list = new ArrayList<>();
        if (client.getNetworkHandler() != null) list.addAll(client.getNetworkHandler().getListedPlayerListEntries());
        list.sort(Comparator.comparing(e -> e.getProfile().getName().toLowerCase()));
        int px = width / 2 + 10, pw = width - px - 12, rowH = 22;
        int py = 12, maxRows = Math.max(1, (height - 24) / rowH);
        if (scroll > Math.max(0, list.size() - maxRows)) scroll = Math.max(0, list.size() - maxRows);
        ctx.fill(px, py, px + pw, py + maxRows * rowH, 0x60000000);
        for (int i = 0; i < maxRows && scroll + i < list.size(); i++) {
            PlayerListEntry e = list.get(scroll + i);
            int ry = py + i * rowH;
            ctx.fill(px, ry, px + pw, ry + rowH - 2, 0x50000000);
            icon(ctx, px + 6, ry + 4);
            Text name = e.getDisplayName() != null ? e.getDisplayName() : Text.literal(e.getProfile().getName());
            ctx.drawTextWithShadow(textRenderer, name, px + 26, ry + 7, 0xFFFFFF);
        }
        super.render(ctx, mouseX, mouseY, delta);
    }

    /** Значок «сеть» как у 0.14. */
    private static void icon(DrawContext ctx, int x, int y) {
        int c = 0xFFE0E0E0;
        ctx.fill(x + 5, y, x + 10, y + 4, c);
        ctx.fill(x + 7, y + 4, x + 8, y + 7, c);
        ctx.fill(x + 1, y + 7, x + 14, y + 8, c);
        ctx.fill(x + 1, y + 8, x + 2, y + 10, c); ctx.fill(x + 13, y + 8, x + 14, y + 10, c);
        ctx.fill(x, y + 10, x + 4, y + 14, c); ctx.fill(x + 11, y + 10, x + 15, y + 14, c);
    }
}
