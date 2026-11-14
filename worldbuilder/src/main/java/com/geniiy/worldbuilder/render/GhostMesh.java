package com.geniiy.worldbuilder.render;

import com.geniiy.worldbuilder.util.LongIntMap;
import com.geniiy.worldbuilder.util.Pos;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import org.joml.Matrix4f;

/**
 * Предпросмотр набора вокселей: внешние грани (между «внутри» и «снаружи») одним VertexBuffer.
 * Координаты относительны точке (bx, by, bz), при рисовании сдвигаются матрицей.
 */
public final class GhostMesh {
    private VertexBuffer vb;
    private static BufferBuilder buf;
    public int faces;
    public long builtKey = Long.MIN_VALUE;

    public interface Inside { boolean in(int x, int y, int z); }
    public interface Color { int color(int x, int y, int z); }

    /** Построить по функции принадлежности в рамке [x1..x2]. Возвращает false, если граней слишком много. */
    public boolean build(int x1, int y1, int z1, int x2, int y2, int z2, Inside in, Color col, int maxFaces) {
        if (buf == null) buf = new BufferBuilder(1 << 20);
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int n = 0;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) for (int y = y1; y <= y2; y++) {
            if (!in.in(x, y, z)) continue;
            int c = col.color(x, y, z);
            float fx = x - x1, fy = y - y1, fz = z - z1;
            if (!in.in(x, y - 1, z)) { q(fx, fy, fz, 0, c); n++; }
            if (!in.in(x, y + 1, z)) { q(fx, fy, fz, 1, c); n++; }
            if (!in.in(x, y, z - 1)) { q(fx, fy, fz, 2, c); n++; }
            if (!in.in(x, y, z + 1)) { q(fx, fy, fz, 3, c); n++; }
            if (!in.in(x - 1, y, z)) { q(fx, fy, fz, 4, c); n++; }
            if (!in.in(x + 1, y, z)) { q(fx, fy, fz, 5, c); n++; }
            if (n > maxFaces) { BufferBuilder.BuiltBuffer b = buf.endNullable(); if (b != null) b.release(); faces = 0; close(); return false; }
        }
        faces = n;
        upload();
        return true;
    }

    /** Построить по набору вокселей (ключи Pos.pack), относительно (bx,by,bz). */
    public boolean build(LongIntMap set, int bx, int by, int bz, Color col, int maxFaces) {
        if (buf == null) buf = new BufferBuilder(1 << 20);
        buf.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        int[] n = {0};
        boolean[] over = {false};
        set.forEach((k, v) -> {
            if (over[0]) return;
            int x = Pos.x(k), y = Pos.y(k), z = Pos.z(k);
            int c = col.color(x, y, z);
            float fx = x - bx, fy = y - by, fz = z - bz;
            if (set.get(Pos.pack(x, y - 1, z), 0) == 0 || y == 0) { q(fx, fy, fz, 0, c); n[0]++; }
            if (set.get(Pos.pack(x, y + 1, z), 0) == 0) { q(fx, fy, fz, 1, c); n[0]++; }
            if (set.get(Pos.pack(x, y, z - 1), 0) == 0) { q(fx, fy, fz, 2, c); n[0]++; }
            if (set.get(Pos.pack(x, y, z + 1), 0) == 0) { q(fx, fy, fz, 3, c); n[0]++; }
            if (set.get(Pos.pack(x - 1, y, z), 0) == 0) { q(fx, fy, fz, 4, c); n[0]++; }
            if (set.get(Pos.pack(x + 1, y, z), 0) == 0) { q(fx, fy, fz, 5, c); n[0]++; }
            if (n[0] > maxFaces) over[0] = true;
        });
        if (over[0]) { BufferBuilder.BuiltBuffer b = buf.endNullable(); if (b != null) b.release(); faces = 0; close(); return false; }
        faces = n[0];
        upload();
        return true;
    }

    private void upload() {
        BufferBuilder.BuiltBuffer b = buf.endNullable();
        if (b == null) { close(); return; }
        if (vb == null) vb = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        vb.bind();
        vb.upload(b);
        VertexBuffer.unbind();
    }

    private static void q(float x, float y, float z, int f, int c) {
        float e = 0.002f;
        float x1 = x - e, y1 = y - e, z1 = z - e, x2 = x + 1 + e, y2 = y + 1 + e, z2 = z + 1 + e;
        switch (f) {
            case 0 -> { v(x1, y1, z1, c); v(x2, y1, z1, c); v(x2, y1, z2, c); v(x1, y1, z2, c); }
            case 1 -> { v(x1, y2, z1, c); v(x1, y2, z2, c); v(x2, y2, z2, c); v(x2, y2, z1, c); }
            case 2 -> { v(x1, y1, z1, c); v(x1, y2, z1, c); v(x2, y2, z1, c); v(x2, y1, z1, c); }
            case 3 -> { v(x1, y1, z2, c); v(x2, y1, z2, c); v(x2, y2, z2, c); v(x1, y2, z2, c); }
            case 4 -> { v(x1, y1, z1, c); v(x1, y1, z2, c); v(x1, y2, z2, c); v(x1, y2, z1, c); }
            default -> { v(x2, y1, z1, c); v(x2, y2, z1, c); v(x2, y2, z2, c); v(x2, y1, z2, c); }
        }
    }
    private static void v(float x, float y, float z, int c) { buf.vertex(x, y, z).color(c).next(); }

    /** Нарисовать со смещением (мировые координаты угла). r — рендерер (для матриц). */
    public void draw(ViewportRenderer r, double wx, double wy, double wz, boolean depth) {
        if (vb == null || faces == 0) return;
        Matrix4f mv = new Matrix4f(r.view).translate((float) (wx - r.camX), (float) (wy - r.camY), (float) (wz - r.camZ));
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        vb.bind();
        vb.draw(mv, r.proj, GameRenderer.getPositionColorProgram());
        VertexBuffer.unbind();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    public void close() { if (vb != null) { vb.close(); vb = null; } faces = 0; builtKey = Long.MIN_VALUE; }
}
