package com.geniiy.bridge14;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Добавление и изменение сервера 0.14 (оформление 0.14). */
public class ServerEditScreen extends Screen {
    private final Screen parent;
    private final Config cfg;
    private final Config.Server editing;
    private TextFieldWidget name, address;
    private String error = "";

    public ServerEditScreen(Screen parent, Config cfg, Config.Server editing) {
        super(Text.literal(editing == null ? "Новый сервер" : "Изменить сервер"));
        this.parent = parent; this.cfg = cfg; this.editing = editing;
    }

    @Override
    protected void init() {
        int cx = width / 2, fw = Math.min(260, width - 40);
        addDrawableChild(new PeStyle.Button(4, 3, 56, 20, Text.literal("Назад"), b -> close()));
        name = new TextFieldWidget(textRenderer, cx - fw / 2 + 1, 56, fw - 2, 18, Text.literal("Название"));
        name.setMaxLength(40);
        name.setText(editing != null ? editing.name : "Сервер 0.14");
        addDrawableChild(name);
        address = new TextFieldWidget(textRenderer, cx - fw / 2 + 1, 98, fw - 2, 18, Text.literal("Адрес"));
        address.setMaxLength(128);
        address.setPlaceholder(Text.literal("play.example.ru:19132"));
        address.setText(editing != null ? editing.host + (editing.port != 19132 ? ":" + editing.port : "") : "");
        addDrawableChild(address);
        addDrawableChild(new PeStyle.Button(cx - fw / 2, 132, fw, 24, Text.literal("Сохранить"), b -> save()));
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
    public void renderBackground(DrawContext ctx) { PeStyle.background(ctx, width, height, 0); }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        PeStyle.background(ctx, width, height, delta);
        PeStyle.bar(ctx, textRenderer, width, title.getString());
        int cx = width / 2, fw = Math.min(260, width - 40);
        ctx.drawTextWithShadow(textRenderer, "Название", cx - fw / 2, 44, 0xFFFFFF);
        ctx.drawTextWithShadow(textRenderer, "Адрес (порт по умолчанию 19132)", cx - fw / 2, 86, 0xFFFFFF);
        ctx.fill(name.getX() - 1, name.getY() - 1, name.getX() + name.getWidth() + 1, name.getY() + name.getHeight() + 1, PeStyle.BORDER);
        ctx.fill(address.getX() - 1, address.getY() - 1, address.getX() + address.getWidth() + 1, address.getY() + address.getHeight() + 1, PeStyle.BORDER);
        if (!error.isEmpty()) ctx.drawCenteredTextWithShadow(textRenderer, "§c" + error, cx, 166, 0xFFFFFF);
        super.render(ctx, mouseX, mouseY, delta);
    }

    @Override
    public void close() { client.setScreen(parent); }
}
