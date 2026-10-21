package com.geniiy.bridge14;

import com.geniiy.bridge14.proxy.Session;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** «Настройки мода»: все переключатели в одном месте и клавиши для них (в стиле 0.14). */
public class ModSettingsScreen extends Screen {
    private final Screen parent;
    private KeyBinding waiting;

    public ModSettingsScreen(Screen parent) { super(Text.literal("Настройки мода")); this.parent = parent; }

    private static String onOff(boolean b) { return b ? "ВКЛ" : "ВЫКЛ"; }

    static String buildLabel() { return "Мобильный режим строительства: " + onOff(ReachAround.enabled); }
    static String classicLabel() { return "Classic GUI (эксперимент): " + onOff(Bridge14.classicGui); }
    static String bedwarsLabel() { return "Bedwars fix: " + onOff(Session.bedwarsFix); }

    private String keyLabel(String name, KeyBinding kb) {
        String k = kb == waiting ? "> ? <" : kb.isUnbound() ? "нет" : kb.getBoundKeyLocalizedText().getString();
        return name + ": " + k;
    }

    @Override
    protected void init() {
        clearChildren();
        addDrawableChild(new PeStyle.Button(4, 3, 50, 20, Text.literal("Назад"), b -> close()));
        int bw = Math.min(150, (width - 30) / 2);
        int lx = width / 2 - bw - 5, rx = width / 2 + 5;
        int y = 40;
        addDrawableChild(new PeStyle.Button(lx, y, bw, 20, Text.literal(buildLabel()), b -> { ReachAround.toggle(); Bridge14.saveToggles(); init(); }));
        addDrawableChild(new PeStyle.Button(rx, y, bw, 20, Text.literal(keyLabel("Клавиша", Bridge14.keyBuild)), b -> listen(Bridge14.keyBuild)));
        y += 26;
        addDrawableChild(new PeStyle.Button(lx, y, bw, 20, Text.literal(classicLabel()), b -> { Bridge14.toggleClassic(false); init(); }));
        addDrawableChild(new PeStyle.Button(rx, y, bw, 20, Text.literal(keyLabel("Клавиша", Bridge14.keyClassic)), b -> listen(Bridge14.keyClassic)));
        y += 26;
        addDrawableChild(new PeStyle.Button(lx, y, bw, 20, Text.literal(bedwarsLabel()), b -> { Bridge14.toggleBedwars(false); init(); }));
        addDrawableChild(new PeStyle.Button(rx, y, bw, 20, Text.literal(keyLabel("Клавиша", Bridge14.keyBedwars)), b -> listen(Bridge14.keyBedwars)));
    }

    private void listen(KeyBinding kb) { waiting = kb; init(); }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (waiting != null) {
            InputUtil.Key key = keyCode == GLFW.GLFW_KEY_ESCAPE ? InputUtil.UNKNOWN_KEY : InputUtil.fromKeyCode(keyCode, scanCode);
            client.options.setKeyCode(waiting, key);
            KeyBinding.updateKeysByCode();
            waiting = null;
            init();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() { client.setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        PeStyle.background(ctx, width, height, delta);
        PeStyle.bar(ctx, textRenderer, width, "Настройки мода");
        int y = 40 + 26 * 3 + 6;
        String[] help = {
                "Мобильный режим: ставить блок в воздух рядом с гранью, как на телефоне.",
                "Bedwars fix: нажатие на предмет в сундуке сразу кладёт его в инвентарь",
                "(так работают магазины BedWars и других мини-игр).",
                waiting != null ? "Нажми клавишу. Esc — убрать клавишу." : ""};
        for (String s : help) { ctx.drawCenteredTextWithShadow(textRenderer, s, width / 2, y, 0xD0D0D0); y += 11; }
        super.render(ctx, mouseX, mouseY, delta);
    }
}
