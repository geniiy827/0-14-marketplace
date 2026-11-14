package com.geniiy.worldbuilder.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.*;
import org.lwjgl.opengl.GL11;

/** Линии и полупрозрачные грани во вьюпорте (мировые координаты; модельная матрица уже содержит −камера). */
public final class Overlay {
    private Overlay() {}
    private static BufferBuilder lines, quads;

    public static void begin() {
        if (lines == null) { lines = new BufferBuilder(1 << 18); quads = new BufferBuilder(1 << 18); }
        lines.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
        quads.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
    }

    /** Нарисовать накопленное. depth — учитывать глубину (иначе поверх всего). */
    public static void end(boolean depth) {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1f, -1f);
        BufferBuilder.BuiltBuffer q = quads.endNullable();
        if (q != null) BufferRenderer.drawWithGlobalProgram(q);
        RenderSystem.lineWidth(2f);
        BufferBuilder.BuiltBuffer l = lines.endNullable();
        if (l != null) BufferRenderer.drawWithGlobalProgram(l);
        RenderSystem.lineWidth(1f);
        RenderSystem.disablePolygonOffset();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
    }

    public static void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        lines.vertex(x1, y1, z1).color(argb).next();
        lines.vertex(x2, y2, z2).color(argb).next();
    }

    public static void box(double x1, double y1, double z1, double x2, double y2, double z2, int c) {
        line(x1, y1, z1, x2, y1, z1, c); line(x1, y2, z1, x2, y2, z1, c); line(x1, y1, z2, x2, y1, z2, c); line(x1, y2, z2, x2, y2, z2, c);
        line(x1, y1, z1, x1, y2, z1, c); line(x2, y1, z1, x2, y2, z1, c); line(x1, y1, z2, x1, y2, z2, c); line(x2, y1, z2, x2, y2, z2, c);
        line(x1, y1, z1, x1, y1, z2, c); line(x2, y1, z1, x2, y1, z2, c); line(x1, y2, z1, x1, y2, z2, c); line(x2, y2, z1, x2, y2, z2, c);
    }

    public static void quad(double[] a, double[] b, double[] c, double[] d, int argb) {
        quads.vertex(a[0], a[1], a[2]).color(argb).next();
        quads.vertex(b[0], b[1], b[2]).color(argb).next();
        quads.vertex(c[0], c[1], c[2]).color(argb).next();
        quads.vertex(d[0], d[1], d[2]).color(argb).next();
    }

    /** Грань единичного куба (face — Direction.ordinal). */
    public static void face(int x, int y, int z, int face, int argb) { face(x, y, z, x + 1, y + 1, z + 1, face, argb); }

    public static void face(double x1, double y1, double z1, double x2, double y2, double z2, int face, int c) {
        switch (face) {
            case 0 -> quad(new double[]{x1, y1, z1}, new double[]{x2, y1, z1}, new double[]{x2, y1, z2}, new double[]{x1, y1, z2}, c);
            case 1 -> quad(new double[]{x1, y2, z1}, new double[]{x1, y2, z2}, new double[]{x2, y2, z2}, new double[]{x2, y2, z1}, c);
            case 2 -> quad(new double[]{x1, y1, z1}, new double[]{x1, y2, z1}, new double[]{x2, y2, z1}, new double[]{x2, y1, z1}, c);
            case 3 -> quad(new double[]{x1, y1, z2}, new double[]{x2, y1, z2}, new double[]{x2, y2, z2}, new double[]{x1, y2, z2}, c);
            case 4 -> quad(new double[]{x1, y1, z1}, new double[]{x1, y1, z2}, new double[]{x1, y2, z2}, new double[]{x1, y2, z1}, c);
            default -> quad(new double[]{x2, y1, z1}, new double[]{x2, y2, z1}, new double[]{x2, y2, z2}, new double[]{x2, y1, z2}, c);
        }
    }

    public static void cube(double x1, double y1, double z1, double x2, double y2, double z2, int c) {
        for (int f = 0; f < 6; f++) face(x1, y1, z1, x2, y2, z2, f, c);
    }
}
