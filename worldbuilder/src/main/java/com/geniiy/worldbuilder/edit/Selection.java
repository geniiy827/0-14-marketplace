package com.geniiy.worldbuilder.edit;

import com.geniiy.worldbuilder.util.LongIntMap;
import com.geniiy.worldbuilder.util.Pos;

import java.util.ArrayList;
import java.util.List;

/** Составное выделение: список фигур с булевыми операциями (CSG), вокселизуется лениво. */
public class Selection {
    public final List<Shape> shapes = new ArrayList<>();
    public int active = -1;
    public int version;

    public boolean isEmpty() { return shapes.isEmpty(); }
    public Shape activeShape() { return active >= 0 && active < shapes.size() ? shapes.get(active) : null; }

    public void clear() { shapes.clear(); active = -1; version++; }

    public void add(Shape s, Shape.Mode mode) {
        if (mode == Shape.Mode.NEW) { shapes.clear(); s.mode = Shape.Mode.ADD; }
        else s.mode = mode;
        if (shapes.isEmpty()) s.mode = Shape.Mode.ADD;
        if (s.name == null || s.name.isEmpty()) s.name = s.type.ru + " " + (shapes.size() + 1);
        shapes.add(s);
        active = shapes.size() - 1;
        version++;
    }

    public void changed() { version++; }

    public boolean contains(int x, int y, int z) {
        boolean in = false;
        for (Shape s : shapes) {
            if (s.hidden) continue;
            switch (s.mode) {
                case NEW, ADD -> { if (!in && s.contains(x, y, z)) in = true; }
                case SUBTRACT -> { if (in && s.contains(x, y, z)) in = false; }
                case INTERSECT -> { if (in && !s.contains(x, y, z)) in = false; }
            }
        }
        return in;
    }

    /** Габариты {minX, minY, minZ, maxX, maxY, maxZ} (по фигурам «добавить»), null — пусто. */
    public int[] bounds() {
        int[] r = null;
        for (Shape s : shapes) {
            if (s.hidden || s.mode == Shape.Mode.SUBTRACT || s.mode == Shape.Mode.INTERSECT) continue;
            int[] b = s.bounds();
            if (r == null) r = b.clone();
            else { r[0] = Math.min(r[0], b[0]); r[1] = Math.min(r[1], b[1]); r[2] = Math.min(r[2], b[2]); r[3] = Math.max(r[3], b[3]); r[4] = Math.max(r[4], b[4]); r[5] = Math.max(r[5], b[5]); }
        }
        if (r != null) { r[1] = Math.max(0, r[1]); r[4] = Math.min(127, r[4]); }
        return r;
    }

    public interface VoxelVisitor { void visit(int x, int y, int z); }

    /** Обойти все блоки выделения (по колонкам, y быстрее всего). */
    public void forEach(VoxelVisitor v) {
        int[] b = bounds();
        if (b == null) return;
        boolean simple = shapes.size() == 1 && shapes.get(0).type == Shape.Type.CUBOID && !shapes.get(0).hollow && !shapes.get(0).wireframe;
        for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) for (int y = b[1]; y <= b[4]; y++)
            if (simple || contains(x, y, z)) v.visit(x, y, z);
    }

    /** Точный объём (число вокселей). */
    public long count() {
        long[] c = {0};
        forEach((x, y, z) -> c[0]++);
        return c[0];
    }

    /** Превратить в произвольную воксельную маску. */
    public void bake() {
        LongIntMap m = new LongIntMap(1024);
        forEach((x, y, z) -> m.put(Pos.pack(x, y, z), 1));
        int[] b = bounds();
        shapes.clear();
        if (b == null) { active = -1; version++; return; }
        Shape s = new Shape(Shape.Type.VOXELS, b[0], b[1], b[2], b[3], b[4], b[5]);
        s.voxels = m;
        s.name = "Запечённое выделение";
        shapes.add(s);
        active = 0;
        version++;
    }

    public Selection copy() {
        Selection s = new Selection();
        for (Shape sh : shapes) s.shapes.add(sh.copy());
        s.active = active;
        s.version = version + 1;
        return s;
    }

    public void move(int dx, int dy, int dz) { for (Shape s : shapes) s.move(dx, dy, dz); version++; }
}
