package com.geniiy.bridge14;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Добавление и изменение сервера 0.14. */
public class ServerEditScreen extends Screen {
    private final BridgeScreen parent;
    private final Config cfg;
    private final Config.Server editing;
    private TextFieldWidget name, address;
    private String error = "";

    public ServerEditScreen(BridgeScreen parent, Config cfg, Config.Server editing) {
        super(Text.literal(editing == null ? "Новый сервер 0.14" : "Изменить сервер"));
        this.parent = parent; this.cfg = cfg; this.editing = editing;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        name = new TextFieldWidget(textRenderer, cx - 100, 60, 200, 20, Text.literal("Название"));
        name.setMaxLength(40);
        name.setText(editing != null ? editing.name : "Сервер 0.14");
        addDrawableChild(name);
        address = new TextFieldWidget(textRenderer, cx - 100, 100, 200, 20, Text.literal("Адрес"));
        address.setMaxLength(128);
        address.setPlaceholder(Text.literal("play.example.ru:19132"));
        address.setText(editing != null ? editing.host + (editing.port != 19132 ? ":" + editing.port : "") : "");
        addDrawableChild(address);
        addDrawableChild(ButtonWidget.builder(Text.literal("Сохранить"), b -> save()).dimensions(cx - 100, 140, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Отмена"), b -> close()).dimensions(cx + 2, 140, 98, 20).build());
        setInitialFocus(editing == null ? address : name);
    }

    private void save() {
        String a = address.getText().trim();
        if (a.isEmpty()) { error = "Введи адрес сервера"; return; }
        String host = a; int port = 19132;
        int c = a.lastIndexOf(':');
        if (c > 0 && a.indexOf(':') == c) {
            host = a.substring(0, c);
            try { port = Integer.parseInt(a.substring(c + 1)); } catch (Exception e) { error = "Порт должен быть числом"; return; }
        }
        String n = name.getText().trim();
        if (n.isEmpty()) n = host;
        if (editing != null) { editing.name = n; editing.host = host; editing.port = port; }
        else cfg.servers.add(new Config.Server(n, host, port));
        cfg.save();
        close();
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        int cx = width / 2;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, 20, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Название", cx - 100, 48, 0xAAAAAA);
        ctx.drawTextWithShadow(textRenderer, "Адрес (порт по умолчанию 19132)", cx - 100, 88, 0xAAAAAA);
        if (!error.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, "§c" + error, cx, 170, 0xFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() { client.setScreen(parent); }
}
