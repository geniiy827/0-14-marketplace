package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.config.WbConfig;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/** Переназначаемые клавиши редактора (раздел 26). Сочетания с Ctrl фиксированы. */
public final class Keys {
    private Keys() {}
    public record K(String id, String ru, int def) {}
    public static final Map<String, K> ALL = new LinkedHashMap<>();
    static void k(String id, String ru, int def) { ALL.put(id, new K(id, ru, def)); }
    static {
        k("forward", "Вперёд по взгляду", GLFW.GLFW_KEY_W);
        k("back", "Назад", GLFW.GLFW_KEY_S);
        k("left", "Влево", GLFW.GLFW_KEY_A);
        k("right", "Вправо", GLFW.GLFW_KEY_D);
        k("up", "Вверх (по Y)", GLFW.GLFW_KEY_SPACE);
        k("down", "Вниз (по Y)", GLFW.GLFW_KEY_LEFT_SHIFT);
        k("frame", "Показать выделение/блок", GLFW.GLFW_KEY_F);
        k("home", "К точке спавна", GLFW.GLFW_KEY_HOME);
        k("tool_inspect", "Инспектор", GLFW.GLFW_KEY_V);
        k("tool_pencil", "Карандаш", GLFW.GLFW_KEY_P);
        k("tool_eraser", "Ластик", GLFW.GLFW_KEY_E);
        k("tool_bucket", "Ведро", GLFW.GLFW_KEY_K);
        k("tool_select", "Выделение", GLFW.GLFW_KEY_M);
        k("tool_brush", "Кисть", GLFW.GLFW_KEY_B);
        k("tool_line", "Линия", GLFW.GLFW_KEY_L);
        k("tool_move", "Перемещение", GLFW.GLFW_KEY_G);
        k("tool_terrain", "Рельеф", GLFW.GLFW_KEY_T);
        k("tool_biome", "Биомы", GLFW.GLFW_KEY_Y);
        k("tool_ruler", "Линейка", GLFW.GLFW_KEY_U);
        k("tool_objects", "Объекты", GLFW.GLFW_KEY_O);
        k("rotate", "Повернуть (R)", GLFW.GLFW_KEY_R);
        k("anchor", "Точка привязки вставки", GLFW.GLFW_KEY_Q);
        k("hide_ui", "Скрыть панели", GLFW.GLFW_KEY_TAB);
        k("slice", "Срез по высоте", GLFW.GLFW_KEY_N);
        k("help", "Справка", GLFW.GLFW_KEY_F1);
        k("debug", "Отладка", GLFW.GLFW_KEY_F3);
        k("delete", "Очистить выделение", GLFW.GLFW_KEY_DELETE);
        k("brush_smaller", "Кисть меньше", GLFW.GLFW_KEY_LEFT_BRACKET);
        k("brush_bigger", "Кисть больше", GLFW.GLFW_KEY_RIGHT_BRACKET);
    }

    public static int key(String id) {
        Integer v = WbConfig.get().keys.get(id);
        if (v != null) return v;
        K k = ALL.get(id);
        return k == null ? -1 : k.def;
    }

    public static boolean is(int pressed, String id) { return pressed == key(id); }

    public static String name(int key) {
        if (key < 0) return "—";
        String n = InputUtil.fromKeyCode(key, 0).getLocalizedText().getString();
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> "Пробел";
            case GLFW.GLFW_KEY_LEFT_SHIFT -> "Левый Shift";
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_HOME -> "Home";
            default -> n;
        };
    }

    public static String hint(String id) { return name(key(id)); }
}
