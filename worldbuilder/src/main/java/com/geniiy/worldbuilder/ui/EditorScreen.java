package com.geniiy.worldbuilder.ui;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.blocks.PeBlocks;
import com.geniiy.worldbuilder.blocks.Rotation;
import com.geniiy.worldbuilder.camera.EditorCamera;
import com.geniiy.worldbuilder.config.WbConfig;
import com.geniiy.worldbuilder.edit.*;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.render.GhostMesh;
import com.geniiy.worldbuilder.render.Overlay;
import com.geniiy.worldbuilder.render.ViewportRenderer;
import com.geniiy.worldbuilder.session.Editor;
import com.geniiy.worldbuilder.session.SaveManager;
import com.geniiy.worldbuilder.util.LongIntMap;
import com.geniiy.worldbuilder.util.Pos;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.*;

/** Редактор мира: вьюпорт, камера, инструменты, панели (раздел 11–19 ТЗ). */
public class EditorScreen extends WbScreen {
    public enum Tool {
        INSPECT("Инспектор", "tool_inspect", "Клик по блоку — карточка с подробностями"),
        PENCIL("Карандаш", "tool_pencil", "Точечная установка блоков с умной ориентацией"),
        ERASER("Ластик", "tool_eraser", "Удаление блоков кликом или протяжкой"),
        BUCKET("Ведро", "tool_bucket", "Заливка связной области того же блока"),
        SELECT("Выделение", "tool_select", "Фигуры выделения, гизмо, булевы операции"),
        BRUSH("Кисть", "tool_brush", "Кисти: ставить, стирать, красить, деревья…"),
        LINE("Линия", "tool_line", "Отрезок, ломаная, сплайн, стена по линии"),
        MOVE("Перемещение", "tool_move", "Перетащить содержимое выделения"),
        TERRAIN("Рельеф", "tool_terrain", "Поднять, опустить, сгладить, выровнять"),
        BIOME("Биомы", "tool_biome", "Кисть биомов и цвета травы"),
        RULER("Линейка", "tool_ruler", "Расстояние, площадь, объём"),
        OBJECTS("Объекты", "tool_objects", "Деревья, валуны, арки, лестницы…");
        public final String ru, key, desc;
        Tool(String ru, String key, String desc) { this.ru = ru; this.key = key; this.desc = desc; }
    }

    final Screen parent;
    public final Editor ed;
    public final PeWorld world;
    public ViewportRenderer vr;
    public final EditorCamera cam = new EditorCamera();
    final EditorUi ui;
    public Tool tool = Tool.PENCIL;

    // раскладка (единицы интерфейса)
    int leftW = 200, rightW = 290;
    boolean leftCollapsed, rightCollapsed, hideUi;
    int vx, vy, vw, vh;
    static final int TOP = 18, STATUS = 14;

    // ввод
    boolean looking; double lookX, lookY; long rmbTime; double rmbX, rmbY; boolean rmbMoved;
    boolean mmb; long mmbTime; double mmbX, mmbY; boolean mmbMoved; double[] orbitPivot; boolean panMode;
    boolean lmb;
    long lastFrame = System.nanoTime();
    long lastWTap; boolean wWasDown;
    public Raycast.Hit hover;
    public boolean overViewport;
    double airDist = 8;
    String bigSpeedText; long bigSpeedUntil;

    // вид
    public boolean sliceOn, showBiomes, debug, onlySel;
    public int sliceY = 64;
    public int lightMode;

    // карандаш / ластик / протяжки
    ChangeSet stroke;
    int strokeMode; // 0 ставить, 1 удалять, 2 заменять
    int strokeAxis = -1, strokePlane;
    int[] strokeLast, strokeStart;
    int axisLock = -1;
    boolean manualOrient; int manualRot; boolean manualFlip;
    public boolean smartOrient = WbConfig.get().smartOrientation;
    public int eraserSize = 1; public boolean eraserSphere; public Mask eraserMask = Mask.any();

    // ведро
    public int bucketRadius = 64, bucketMax = 500_000; public boolean bucket26, bucketPlane, bucketMeta = true;

    // выделение
    public Shape.Type selType = Shape.Type.CUBOID;
    public Shape.Mode selMode = Shape.Mode.NEW;
    public boolean selFromCenter;
    int selPhase; int[] selAnchor; int selFace; Shape creating; double heightMouseY; int heightBase;
    int dragHandle = -1; double dragT0; Shape dragOrig; boolean dragSym;
    final GhostMesh selMesh = new GhostMesh();
    volatile LongIntMap selVoxels; volatile int selVoxelsVersion = -1; boolean selComputing; int selRequested = -2;
    List<int[]> polyPoints = new ArrayList<>();
    public int wandLimit = 200_000; public boolean wand26, wandMeta = true;
    List<double[]> lasso; public int lassoDepth = 1;
    public int brushSelRadius = 3;

    // кисть
    public final Brush brush = new Brush();
    public final Brush terrain = new Brush();
    public final Brush biomeBrush = new Brush();
    double[] lastDab; Random rnd = new Random();
    final GhostMesh brushMesh = new GhostMesh();

    // вставка
    public boolean pasting; public Clipboard pasteClip; public int pasteAnchor; public int pasteDy;
    public boolean pasteSkipAir = WbConfig.get().pasteSkipAir, pasteOnlyAir, pasteBiomes, pasteTiles = true; public Mask pasteMask = Mask.any();
    final GhostMesh pasteMesh = new GhostMesh(); int[] pasteOrigin; boolean pasteFixed;

    // перемещение
    public int[] moveOffset = {0, 0, 0}; public boolean moveSkipAir = true, moveCopy, moveSelection = true; public Clipboard moveClip; int moveRot; boolean moveMirror;
    final GhostMesh moveMesh = new GhostMesh();

    // линия
    public final List<double[]> linePts = new ArrayList<>();
    public int lineMode = 0; public int lineThick = 1; public boolean lineSquare, lineHollow, lineWall; public int wallHeight = 3; public double tension = 0.5;
    final GhostMesh lineMesh = new GhostMesh(); int lineVersion, lineBuilt = -1; int dragPoint = -1;

    // линейка
    public final List<int[]> rulerPts = new ArrayList<>();

    // объекты
    public final Generators.Params objParams = new Generators.Params();
    final GhostMesh objMesh = new GhostMesh(); long objKey;

    // инспектор
    public int[] inspect;

    // контекстное меню
    List<EditorUi.MenuItem> ctxMenu; int ctxX, ctxY;
    boolean tourShown;
    int bookmarkFlash;

    public EditorScreen(Screen parent, Editor ed) {
        super("Редактор — " + ed.world.level.levelName());
        this.parent = parent;
        this.ed = ed;
        this.world = ed.world;
        this.ui = new EditorUi(this);
        cam.x = world.level.spawnX() + 0.5; cam.z = world.level.spawnZ() - 20.5; cam.y = Math.min(110, world.level.spawnY() + 18);
        cam.yaw = 0; cam.pitch = 35;
        cam.speed = WbConfig.get().baseSpeed;
        terrain.mode = Brush.Mode.RAISE; terrain.size = 6;
        biomeBrush.mode = Brush.Mode.BIOME; biomeBrush.size = 8; biomeBrush.form = Brush.Form.CYLINDER;
        lightMode = WbConfig.get().lightMode;
    }

    @Override
    protected void init() {
        if (vr == null) {
            WbConfig c = WbConfig.get();
            vr = new ViewportRenderer(world, c.meshThreads);
            vr.renderDistance = c.renderDistance;
            vr.fog = c.fog;
            vr.skyTop = c.skyTop; vr.skyBottom = c.skyBottom;
            vr.opt.showInvisible = c.showInvisible;
        }
    }

    // ---------- раскладка ----------
    void layout() {
        int l = hideUi || leftCollapsed ? 0 : leftW, r = hideUi || rightCollapsed ? 0 : rightW;
        int top = hideUi ? 0 : TOP, bot = hideUi ? 0 : STATUS;
        vx = l; vy = top; vw = Math.max(50, W - l - r); vh = Math.max(50, H - top - bot);
    }

    @Override
    protected void renderBackground3D(DrawContext ctx, float delta) {
        gui.scale = uiScale();
        W = (int) (width / gui.scale);
        H = (int) (height / gui.scale);
        layout();
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        double mxu = client.mouse.getX() / physPerUnit(), myu = client.mouse.getY() / physPerUnit();
        overViewport = mxu >= vx && myu >= vy && mxu < vx + vw && myu < vy + vh && dialogs.isEmpty() && ctxMenu == null && !gui.inOverlay((int) mxu, (int) myu);
        updateCamera(dt, mxu, myu);
        double ppu = physPerUnit();
        int px = (int) Math.round(vx * ppu), py = (int) Math.round(vy * ppu), pw = (int) Math.round(vw * ppu), ph = (int) Math.round(vh * ppu);
        cam.fov = WbConfig.get().fov;
        vr.setCamera(cam.x, cam.y, cam.z, cam.yaw, cam.pitch, cam.fov, cam.ortho, cam.orthoScale, px, py, pw, ph);
        vr.opt.sliceY = sliceOn ? sliceY : 999;
        vr.opt.onlySelection = onlySel && !ed.selection.isEmpty();
        vr.opt.selection = ed.selection;
        // наведение
        if (overViewport && !looking) {
            double[] r = vr.ray((mxu - vx) / vw, (myu - vy) / vh);
            hover = Raycast.cast(world, r[0], r[1], r[2], r[3], r[4], r[5], Math.max(64, vr.renderDistance * 16), WbConfig.get().pickLiquids, vr.opt.showInvisible, sliceOn ? sliceY : 999, airDist);
            toolDrag(r, mxu, myu);
        } else if (!overViewport && !lmb) hover = null;
        try {
            vr.render(delta, lightMode, this::drawOverlays);
        } catch (Throwable t) {
            WorldBuilderMod.LOG.error("[WorldBuilder] Ошибка рендера", t);
        }
        autosaveTick();
        com.geniiy.worldbuilder.AutoTest.tick(this);
    }

    private void autosaveTick() {
        int min = WbConfig.get().autosaveMinutes;
        if (min <= 0 || System.currentTimeMillis() - ed.lastAutosave < min * 60_000L) return;
        ed.lastAutosave = System.currentTimeMillis();
        if (!world.hasUnsavedChanges()) return;
        ed.enqueue(() -> { try { SaveManager.autosave(ed); } catch (Exception e) { WorldBuilderMod.LOG.warn("[WorldBuilder] Автосохранение: {}", e.toString()); } });
    }

    // ---------- камера ----------
    private boolean key(String id) {
        int k = Keys.key(id);
        return k >= 0 && InputUtil.isKeyPressed(client.getWindow().getHandle(), k);
    }

    private void updateCamera(double dt, double mxu, double myu) {
        long win = client.getWindow().getHandle();
        WbConfig c = WbConfig.get();
        if (looking) {
            double[] x = new double[1], y = new double[1];
            GLFW.glfwGetCursorPos(win, x, y);
            double dx = x[0] - lookX, dy = y[0] - lookY;
            lookX = x[0]; lookY = y[0];
            if (Math.abs(dx) + Math.abs(dy) > 0) rmbMoved = true;
            double s = 0.15 * c.sensitivity;
            cam.rotate(dx * s, dy * s * (c.invertY ? -1 : 1));
        }
        if (mmb && orbitPivot != null) {
            double[] x = new double[1], y = new double[1];
            GLFW.glfwGetCursorPos(win, x, y);
            double dx = x[0] - mmbX, dy = y[0] - mmbY;
            if (Math.abs(dx) + Math.abs(dy) > 3) mmbMoved = true;
            if (mmbMoved) {
                mmbX = x[0]; mmbY = y[0];
                if (panMode) {
                    double dist = Math.max(4, Math.sqrt(Math.pow(cam.x - orbitPivot[0], 2) + Math.pow(cam.y - orbitPivot[1], 2) + Math.pow(cam.z - orbitPivot[2], 2)));
                    double k = cam.ortho ? cam.orthoScale * 2 / (vh * physPerUnit()) : dist * 0.0015;
                    cam.pan(dx * k, dy * k);
                } else cam.orbit(orbitPivot[0], orbitPivot[1], orbitPivot[2], dx * 0.3, dy * 0.3);
            }
        }
        boolean allow = (overViewport || looking) && gui.focus == null && dialogs.isEmpty() && !lmb && !mmb;
        boolean f = allow && key("forward"), b = allow && key("back"), l = allow && key("left"), r = allow && key("right"),
                u = allow && key("up"), d = allow && key("down");
        // двойное нажатие W — ускорение ×2
        if (f && !wWasDown) { long now = System.currentTimeMillis(); if (now - lastWTap < 300) cam.boost = true; lastWTap = now; }
        if (!f) cam.boost = false;
        wWasDown = f;
        cam.update(dt, f, b, l, r, u, d, c.smoothMove ? c.accelTime : 0);
    }

    void startLook() {
        long win = client.getWindow().getHandle();
        double[] x = new double[1], y = new double[1];
        GLFW.glfwGetCursorPos(win, x, y);
        lookX = rmbX = x[0]; lookY = rmbY = y[0];
        looking = true; rmbMoved = false; rmbTime = System.currentTimeMillis();
        GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        if (GLFW.glfwRawMouseMotionSupported()) GLFW.glfwSetInputMode(win, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE);
    }

    void stopLook() {
        if (!looking) return;
        long win = client.getWindow().getHandle();
        looking = false;
        GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        GLFW.glfwSetCursorPos(win, rmbX, rmbY);
        // короткий клик ПКМ без движения — контекстное меню
        if (!rmbMoved && System.currentTimeMillis() - rmbTime < 250) openContextMenu();
    }

    @Override protected void onMouseDown(int button) {
        if (!overViewport) return;
        if (button == 1) { startLook(); return; }
        if (button == 2) {
            long win = client.getWindow().getHandle();
            double[] x = new double[1], y = new double[1];
            GLFW.glfwGetCursorPos(win, x, y);
            mmb = true; mmbMoved = false; mmbTime = System.currentTimeMillis(); mmbX = x[0]; mmbY = y[0];
            panMode = gui.shift();
            if (hover != null && !hover.air) orbitPivot = new double[]{hover.hx, hover.hy, hover.hz};
            else { double[] lk = new double[3]; cam.lookDir(lk); orbitPivot = new double[]{cam.x + lk[0] * 16, cam.y + lk[1] * 16, cam.z + lk[2] * 16}; }
            return;
        }
        if (button == 0) { lmb = true; toolPress(); }
    }

    @Override protected void onMouseUp(int button) {
        if (button == 1) stopLook();
        if (button == 2 && mmb) {
            mmb = false;
            if (!mmbMoved && System.currentTimeMillis() - mmbTime < 300) eyedropper(gui.alt());
            orbitPivot = null;
        }
        if (button == 0 && lmb) { lmb = false; toolRelease(); }
    }

    @Override public boolean mouseScrolled(double mx, double my, double amount) {
        if (overViewport && dialogs.isEmpty()) {
            if (looking) { cam.changeSpeed(amount); bigSpeed(); return true; }
            if (gui.ctrl() && gui.alt()) { airDist = Math.max(2, Math.min(64, airDist + amount)); toast("Дистанция «в воздухе»: " + (int) airDist); return true; }
            if (gui.alt()) {
                if (tool == Tool.BRUSH || tool == Tool.TERRAIN) { Brush b = tool == Tool.BRUSH ? brush : terrain; b.strength = Math.max(1, Math.min(100, b.strength + (int) Math.signum(amount) * 5)); return true; }
                ed.slot = Math.floorMod(ed.slot - (int) Math.signum(amount), 9); return true;
            }
            if (gui.ctrl()) {
                if (pasting) { pasteDy += (int) Math.signum(amount); return true; }
                if (tool == Tool.BRUSH || tool == Tool.TERRAIN || tool == Tool.BIOME) { Brush b = tool == Tool.BRUSH ? brush : tool == Tool.TERRAIN ? terrain : biomeBrush; b.size = Math.max(0, Math.min(64, b.size + (int) Math.signum(amount))); return true; }
                if (tool == Tool.ERASER) { eraserSize = Math.max(1, Math.min(9, eraserSize + (int) Math.signum(amount))); return true; }
                if (tool == Tool.OBJECTS) { objParams.scale = Math.max(0.3, Math.min(4, objParams.scale + 0.1 * Math.signum(amount))); return true; }
                if (tool == Tool.SELECT && ed.selection.activeShape() != null && hover != null) { growSelectionByFace((int) Math.signum(amount)); return true; }
            }
            cam.dolly(amount * (cam.ortho ? 1 : Math.max(1, cam.speed / 6)));
            return true;
        }
        return super.mouseScrolled(mx, my, amount);
    }

    void bigSpeed() { bigSpeedText = "Скорость " + Math.round(cam.speed); bigSpeedUntil = System.currentTimeMillis() + 900; }

    // ---------- пипетка ----------
    void eyedropper(boolean noOrient) {
        if (hover == null || hover.air || hover.ungenerated) return;
        if (tool == Tool.BIOME && noOrient) {
            int v = world.getBiomeColor(hover.x, hover.z);
            biomeBrush.biomeId = (v >>> 24) & 0xFF; biomeBrush.biomeColor = v & 0xFFFFFF; biomeBrush.biomeAuto = false;
            toast("Биом: " + Biomes.name(biomeBrush.biomeId) + String.format(" #%06X", biomeBrush.biomeColor));
            return;
        }
        int v = world.getV(hover.x, hover.y, hover.z);
        if (v < 0) { toast("Это заглушка импорта: " + world.stubName(v)); return; }
        if (noOrient) {
            PeBlocks.BlockInfo b = PeBlocks.get().block(v >> 4);
            if (b != null && !PeBlocks.metaIsVariant(b)) v = v & ~15 | (b.metaList.isEmpty() ? 0 : b.metaList.get(0).meta);
            else if (b != null && "log".equals(b.family)) v = v & ~0xC;
            else if (b != null && "slab".equals(b.family)) v = v & ~8;
        }
        ed.hotbar[ed.slot] = Pattern.single(v);
        ed.saveHotbar();
        WbConfig.get().addRecent(v);
        manualOrient = false;
        toast("Взят: " + PeBlocks.get().displayName(v) + " (" + (v >> 4) + ":" + (v & 15) + ")");
    }

    // ---------- инструменты: нажатие ----------
    void toolPress() {
        if (pasting) { pasteClick(); return; }
        if (hover == null) return;
        if (WorldTab.pickSpawn && !hover.air && !hover.ungenerated) {
            WorldTab.pickSpawn = false;
            new WorldTab(ui).setSpawn(hover.x, hover.y + 1, hover.z);
            toast("Спавн: " + hover.x + ", " + (hover.y + 1) + ", " + hover.z);
            return;
        }
        switch (tool) {
            case INSPECT -> { if (!hover.air && !hover.ungenerated) inspect = new int[]{hover.x, hover.y, hover.z}; }
            case PENCIL -> {
                if (ed.busy()) { toast("Подожди окончания операции"); return; }
                stroke = new ChangeSet("Карандаш", "pencil");
                strokeMode = gui.ctrl() ? 1 : gui.alt() ? 2 : 0;
                axisLock = -1;
                int[] p = strokeMode == 0 ? placePos() : new int[]{hover.x, hover.y, hover.z};
                if (p == null) return;
                strokeStart = p.clone(); strokeLast = p.clone();
                strokeAxis = hover.face < 0 ? 1 : (hover.face >> 1); // ось нормали первой грани
                strokePlane = strokeAxis == 0 ? p[0] : strokeAxis == 1 ? p[1] : p[2];
                pencilAt(p, true);
            }
            case ERASER -> {
                if (ed.busy()) return;
                stroke = new ChangeSet("Ластик", "eraser");
                if (!hover.air) { strokeLast = new int[]{hover.x, hover.y, hover.z}; eraseAt(strokeLast); }
            }
            case BUCKET -> bucketFill();
            case SELECT -> selectPress();
            case BRUSH, TERRAIN, BIOME -> brushPress();
            case LINE -> linePress();
            case MOVE -> movePress();
            case RULER -> { int[] p = hover.air ? new int[]{hover.x, hover.y, hover.z} : new int[]{hover.x, hover.y, hover.z}; if (rulerPts.size() >= 2) rulerPts.clear(); rulerPts.add(p); }
            case OBJECTS -> placeObject();
        }
    }

    void toolRelease() {
        switch (tool) {
            case PENCIL, ERASER -> { if (stroke != null) { ed.history.push(stroke); stroke = null; } }
            case SELECT -> selectRelease();
            case BRUSH, TERRAIN, BIOME -> brushRelease();
            case LINE -> dragPoint = -1;
            case MOVE -> dragHandle = -1;
            default -> {}
        }
    }

    /** Движение мыши с зажатой ЛКМ. */
    void toolDrag(double[] ray, double mxu, double myu) {
        if (!lmb) return;
        switch (tool) {
            case PENCIL -> pencilDrag(ray);
            case ERASER -> { if (stroke != null && hover != null && !hover.air) { int[] p = {hover.x, hover.y, hover.z}; if (!Arrays.equals(p, strokeLast)) { strokeLast = p; eraseAt(p); } } }
            case SELECT -> selectDrag(ray, mxu, myu);
            case BRUSH, TERRAIN, BIOME -> brushDrag();
            case LINE -> lineDrag(ray);
            case MOVE -> moveDrag(ray);
            default -> {}
        }
    }

    // ---------- карандаш ----------
    int[] placePos() {
        if (hover == null) return null;
        if (hover.air) return new int[]{hover.x, hover.y, hover.z};
        if (hover.ungenerated) return new int[]{hover.x, 0, hover.z};
        return new int[]{hover.nx(), hover.ny(), hover.nz()};
    }

    /** Состояние для установки с учётом умной ориентации, ручного поворота и отражения. */
    List<Placement.Put> computePlacement(int[] p) {
        int st = ed.active().apply(p[0], p[1], p[2]);
        int target = hover != null && !hover.air ? world.getState(hover.x, hover.y, hover.z) : 0;
        List<Placement.Put> puts;
        if (smartOrient && !manualOrient) puts = Placement.place(st, hover == null ? -1 : hover.face, hover == null ? 0.5 : hover.fy(), cam.yaw, cam.pitch, target, world, p[0], p[1], p[2]);
        else {
            int s = Rotation.rotate(st, manualRot);
            if (manualFlip) s = Rotation.mirrorY(s);
            puts = List.of(new Placement.Put(0, 0, 0, s));
        }
        return puts;
    }

    void pencilAt(int[] p, boolean first) {
        if (!PeWorld.inHeight(p[1])) { toast("Высота мира PE 0.14 — 0…127"); return; }
        switch (strokeMode) {
            case 1 -> world.set(p[0], p[1], p[2], 0, stroke);
            case 2 -> { int st = ed.active().apply(p[0], p[1], p[2]); world.set(p[0], p[1], p[2], st, stroke); }
            default -> {
                // плита на такую же плиту → двойная
                if (first && hover != null && !hover.air && smartOrient && !manualOrient) {
                    int target = world.getState(hover.x, hover.y, hover.z);
                    int dbl = Placement.doubleSlab(ed.activeState(), target, hover.face, hover.fy());
                    if (dbl >= 0) { world.set(hover.x, hover.y, hover.z, dbl, stroke); return; }
                }
                for (Placement.Put put : computePlacement(p)) {
                    int y = p[1] + put.dy();
                    if (!PeWorld.inHeight(y)) continue;
                    world.set(p[0] + put.dx(), y, p[2] + put.dz(), put.state(), stroke);
                }
                WbConfig.get().addRecent(ed.activeState());
            }
        }
    }

    void pencilDrag(double[] ray) {
        if (stroke == null || strokeLast == null) return;
        int[] target;
        if (axisLock >= 0) {
            // линия вдоль оси от начала: ближайшая точка луча к оси
            double[] o = {strokeStart[0] + 0.5, strokeStart[1] + 0.5, strokeStart[2] + 0.5};
            double[] ax = {axisLock == 0 ? 1 : 0, axisLock == 1 ? 1 : 0, axisLock == 2 ? 1 : 0};
            double t = closestOnLine(o, ax, ray);
            target = strokeStart.clone();
            target[axisLock] = (int) Math.floor(o[axisLock] + t);
        } else {
            double[] n = {strokeAxis == 0 ? 1 : 0, strokeAxis == 1 ? 1 : 0, strokeAxis == 2 ? 1 : 0};
            double planeC = strokePlane + 0.5;
            double denom = n[0] * ray[3] + n[1] * ray[4] + n[2] * ray[5];
            if (Math.abs(denom) < 1e-6) return;
            double t = (planeC - (n[0] * ray[0] + n[1] * ray[1] + n[2] * ray[2])) / denom;
            if (t < 0) return;
            target = new int[]{(int) Math.floor(ray[0] + ray[3] * t), (int) Math.floor(ray[1] + ray[4] * t), (int) Math.floor(ray[2] + ray[5] * t)};
            target[strokeAxis] = strokePlane;
            if (strokeMode != 0 && hover != null && !hover.air) target = new int[]{hover.x, hover.y, hover.z};
        }
        if (Arrays.equals(target, strokeLast)) return;
        // интерполяция, чтобы не было пропусков
        int[] a = strokeLast;
        int steps = Math.max(Math.abs(target[0] - a[0]), Math.max(Math.abs(target[1] - a[1]), Math.abs(target[2] - a[2])));
        for (int i = 1; i <= steps; i++) {
            int[] q = {a[0] + Math.round((target[0] - a[0]) * i / (float) steps), a[1] + Math.round((target[1] - a[1]) * i / (float) steps), a[2] + Math.round((target[2] - a[2]) * i / (float) steps)};
            pencilAt(q, false);
        }
        strokeLast = target;
    }

    static double closestOnLine(double[] o, double[] dir, double[] ray) {
        // ближайшая точка на прямой (o + dir*t) к лучу (ray[0..2] + ray[3..5]*s)
        double[] w0 = {o[0] - ray[0], o[1] - ray[1], o[2] - ray[2]};
        double a = dot(dir, dir), b = dir[0] * ray[3] + dir[1] * ray[4] + dir[2] * ray[5], c = ray[3] * ray[3] + ray[4] * ray[4] + ray[5] * ray[5];
        double d = dot(dir, w0), e = ray[3] * w0[0] + ray[4] * w0[1] + ray[5] * w0[2];
        double den = a * c - b * b;
        if (Math.abs(den) < 1e-9) return 0;
        return (b * e - c * d) / den;
    }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

    void eraseAt(int[] p) {
        int r = eraserSize - 1;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            if (eraserSphere && dx * dx + dy * dy + dz * dz > (r + 0.5) * (r + 0.5)) continue;
            int x = p[0] + dx, y = p[1] + dy, z = p[2] + dz;
            if (!PeWorld.inHeight(y)) continue;
            int cur = world.getV(x, y, z);
            if (cur == 0) continue;
            if (!eraserMask.isAny() && !eraserMask.test(world, x, y, z, cur)) continue;
            world.set(x, y, z, 0, stroke);
        }
    }

    // ---------- ведро ----------
    void bucketFill() {
        if (hover == null || hover.air || hover.ungenerated) return;
        int sx = hover.x, sy = hover.y, sz = hover.z, face = hover.face;
        int src = world.getV(sx, sy, sz);
        Pattern pat = ed.active().copy();
        boolean n26 = bucket26, plane = bucketPlane, meta = bucketMeta; int radius = bucketRadius, max = bucketMax;
        ed.run("Заливка: " + pat.describe(), "bucket", op -> {
            ArrayDeque<long[]> q = new ArrayDeque<>();
            LongIntMap seen = new LongIntMap(1024);
            q.add(new long[]{Pos.pack(sx, sy, sz)});
            seen.put(Pos.pack(sx, sy, sz), 1);
            int count = 0;
            int ax = face < 0 ? -1 : face >> 1;
            while (!q.isEmpty() && count < max) {
                long p = q.poll()[0];
                int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
                world.set(x, y, z, pat.apply(x, y, z), op.cs);
                count++;
                if ((count & 4095) == 0) { op.check(); op.progress(count / (double) max); }
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                    int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (m == 0 || (!n26 && m > 1)) continue;
                    if (plane && ax >= 0 && ((ax == 0 && dx != 0) || (ax == 1 && dy != 0) || (ax == 2 && dz != 0))) continue;
                    int nx = x + dx, ny = y + dy, nz = z + dz;
                    if (ny < 0 || ny > 127) continue;
                    if (Math.abs(nx - sx) > radius || Math.abs(ny - sy) > radius || Math.abs(nz - sz) > radius) continue;
                    long k = Pos.pack(nx, ny, nz);
                    if (seen.get(k, 0) != 0) continue;
                    int v = world.getV(nx, ny, nz);
                    boolean same = meta ? v == src : (v >> 4) == (src >> 4) && (v < 0) == (src < 0);
                    if (!same) continue;
                    seen.put(k, 1);
                    q.add(new long[]{k});
                }
            }
            return String.format("Залито %,d блоков", count) + (count >= max ? " (достигнут предел)" : "");
        }, this::opDone);
    }

    public void opDone(Editor.Op op) {
        if (op.error != null) toastError("Ошибка: " + op.error, op.error);
        else if (op.cancelled) toast("Отменено: " + op.name);
        else toast(op.result != null ? op.result : op.name + ": " + String.format("%,d", op.cs.changedCount()) + " блоков");
    }

    // ---------- выделение ----------
    int[] hitOrAir() {
        if (hover == null) return null;
        if (hover.air || hover.ungenerated) return new int[]{hover.x, Math.max(0, hover.y), hover.z};
        return new int[]{hover.x, hover.y, hover.z};
    }

    void selectPress() {
        // ручки гизмо
        if (selPhase == 2) { selPhase = 0; finishCreate(); return; }
        int h = handleUnderMouse();
        if (h >= 0) { dragHandle = h; dragOrig = ed.selection.activeShape().copy(); dragSym = gui.alt(); return; }
        Shape.Mode mode = selMode;
        if (gui.ctrl() && gui.alt()) mode = Shape.Mode.INTERSECT; else if (gui.ctrl()) mode = Shape.Mode.ADD; else if (gui.alt()) mode = Shape.Mode.SUBTRACT;
        switch (selType) {
            case POLYGON -> {
                int[] p = hitOrAir(); if (p == null) return;
                if (polyPoints.isEmpty()) polyPoints.add(new int[]{p[0], p[1] + (hover.air ? 0 : 1), p[2]});
                else polyPoints.add(new int[]{p[0], polyPoints.get(0)[1], p[2]});
                return;
            }
            case VOXELS -> {
                // режимы «палочка/кисть/по типу/лассо» — подтип в selVoxMode
                switch (ui.selVoxMode) {
                    case 0 -> magicWand(mode);
                    case 1 -> { selBrushStroke = new LongIntMap(1024); selBrushMode = mode; brushSelectAt(); }
                    case 2 -> selectByType(mode);
                    default -> { lasso = new ArrayList<>(); }
                }
                return;
            }
            default -> {}
        }
        int[] p = hitOrAir();
        if (p == null) return;
        selAnchor = p;
        selFace = hover.air ? 1 : hover.face;
        creating = new Shape(selType, p[0], p[1], p[2], p[0], p[1], p[2]);
        creating.mode = mode;
        selPhase = 1;
    }

    Shape.Mode selBrushMode; LongIntMap selBrushStroke;

    void selectDrag(double[] ray, double mxu, double myu) {
        if (dragHandle >= 0) { dragHandleTo(ray); return; }
        if (lasso != null) { lasso.add(new double[]{(mxu - vx) / vw, (myu - vy) / vh}); return; }
        if (selBrushStroke != null) { brushSelectAt(); return; }
        if (selPhase != 1 || creating == null) return;
        int ax = selFace < 0 ? 1 : selFace >> 1;
        double[] n = {ax == 0 ? 1 : 0, ax == 1 ? 1 : 0, ax == 2 ? 1 : 0};
        double c = (ax == 0 ? selAnchor[0] : ax == 1 ? selAnchor[1] : selAnchor[2]) + 0.5;
        double den = n[0] * ray[3] + n[1] * ray[4] + n[2] * ray[5];
        if (Math.abs(den) < 1e-6) return;
        double t = (c - (n[0] * ray[0] + n[1] * ray[1] + n[2] * ray[2])) / den;
        if (t < 0) return;
        int[] q = {(int) Math.floor(ray[0] + ray[3] * t), (int) Math.floor(ray[1] + ray[4] * t), (int) Math.floor(ray[2] + ray[5] * t)};
        q[ax] = selAnchor[ax];
        if (selFromCenter && (selType == Shape.Type.ELLIPSOID || selType == Shape.Type.CYLINDER || selType == Shape.Type.TORUS || selType == Shape.Type.CONE)) {
            int[] a = selAnchor;
            int[] lo = new int[3], hi = new int[3];
            for (int i = 0; i < 3; i++) { int d = Math.abs(q[i] - a[i]); lo[i] = a[i] - d; hi[i] = a[i] + d; }
            if (ax == 1) { lo[1] = hi[1] = a[1]; }
            creating.setBox(lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        } else creating.setBox(selAnchor[0], selAnchor[1], selAnchor[2], q[0], q[1], q[2]);
    }

    void selectRelease() {
        if (dragHandle >= 0) { dragHandle = -1; ed.selection.changed(); return; }
        if (lasso != null) { finishLasso(); lasso = null; return; }
        if (selBrushStroke != null) { finishBrushSelect(); return; }
        if (selPhase == 1 && creating != null) {
            if (gui.ctrl()) {
                int ax = selFace < 0 ? 1 : selFace >> 1;
                int wdt = Math.max(creating.sx(), Math.max(creating.sy(), creating.sz()));
                extrude(ax, wdt);
                finishCreate();
                selPhase = 0;
                return;
            }
            selPhase = 2;
            heightMouseY = gui.my;
            int ax = selFace < 0 ? 1 : selFace >> 1;
            heightBase = ax == 0 ? creating.minX : ax == 1 ? creating.minY : creating.minZ;
        }
    }

    void extrude(int ax, int amount) {
        int sign = selFace >= 0 && (selFace & 1) == 0 ? -1 : 1; // нижняя/северная/западная грань — тянем в минус
        Shape s = creating;
        int a = amount - 1;
        switch (ax) {
            case 0 -> { if (sign > 0) s.setBox(s.minX, s.minY, s.minZ, s.minX + a, s.maxY, s.maxZ); else s.setBox(s.maxX - a, s.minY, s.minZ, s.maxX, s.maxY, s.maxZ); }
            case 1 -> { if (sign > 0) s.setBox(s.minX, s.minY, s.minZ, s.maxX, Math.min(127, s.minY + a), s.maxZ); else s.setBox(s.minX, Math.max(0, s.maxY - a), s.minZ, s.maxX, s.maxY, s.maxZ); }
            default -> { if (sign > 0) s.setBox(s.minX, s.minY, s.minZ, s.maxX, s.maxY, s.minZ + a); else s.setBox(s.minX, s.minY, s.maxZ - a, s.maxX, s.maxY, s.maxZ); }
        }
    }

    void updateHeightPhase() {
        if (selPhase != 2 || creating == null) return;
        int ax = selFace < 0 ? 1 : selFace >> 1;
        int amount = Math.max(1, 1 + (int) Math.round((heightMouseY - gui.my) / 6.0));
        extrude(ax, amount);
    }

    void finishCreate() {
        if (creating == null) return;
        ChangeSet cs = selectionChange("Выделение: " + creating.type.ru);
        ed.selection.add(creating, creating.mode == Shape.Mode.NEW || selMode == Shape.Mode.NEW && creating.mode == Shape.Mode.NEW ? Shape.Mode.NEW : creating.mode);
        if (creating.mode == Shape.Mode.NEW) {}
        creating = null;
        commitSelectionChange(cs);
    }

    /** Смена выделения — шаг отмены. */
    ChangeSet selectionChange(String name) {
        ChangeSet cs = new ChangeSet(name, "select");
        Selection before = ed.selection.copy();
        cs.selectionChanged = true;
        cs.extra.add(new Runnable[]{() -> ed.selection = before.copy(), null});
        return cs;
    }
    void commitSelectionChange(ChangeSet cs) {
        Selection after = ed.selection.copy();
        cs.extra.get(0)[1] = () -> ed.selection = after.copy();
        ed.history.push(cs);
        ed.selection.changed();
    }

    public void clearSelection() {
        if (ed.selection.isEmpty()) return;
        ChangeSet cs = selectionChange("Снять выделение");
        ed.selection.clear();
        commitSelectionChange(cs);
    }

    public void finishPolygon(int height) {
        if (polyPoints.size() < 3) { toast("Нужно хотя бы 3 точки"); return; }
        Shape s = new Shape();
        s.type = Shape.Type.POLYGON;
        for (int[] p : polyPoints) s.polygon.add(new int[]{p[0], p[2]});
        s.updatePolygonBounds();
        int y0 = polyPoints.get(0)[1];
        s.minY = Math.max(0, y0); s.maxY = Math.min(127, y0 + Math.max(1, height) - 1);
        creating = s; creating.mode = selMode;
        finishCreate();
        polyPoints.clear();
    }

    void magicWand(Shape.Mode mode) {
        if (hover == null || hover.air) return;
        int sx = hover.x, sy = hover.y, sz = hover.z;
        int src = world.getV(sx, sy, sz);
        LongIntMap set = new LongIntMap(1024);
        ArrayDeque<Long> q = new ArrayDeque<>();
        q.add(Pos.pack(sx, sy, sz)); set.put(Pos.pack(sx, sy, sz), 1);
        int[] b = {sx, sy, sz, sx, sy, sz};
        while (!q.isEmpty() && set.size() < wandLimit) {
            long p = q.poll();
            int x = Pos.x(p), y = Pos.y(p), z = Pos.z(p);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                if (m == 0 || (!wand26 && m > 1)) continue;
                int nx = x + dx, ny = y + dy, nz = z + dz;
                if (ny < 0 || ny > 127) continue;
                long k = Pos.pack(nx, ny, nz);
                if (set.get(k, 0) != 0) continue;
                int v = world.getV(nx, ny, nz);
                if (wandMeta ? v != src : (v >> 4) != (src >> 4)) continue;
                set.put(k, 1); q.add(k);
                b[0] = Math.min(b[0], nx); b[1] = Math.min(b[1], ny); b[2] = Math.min(b[2], nz); b[3] = Math.max(b[3], nx); b[4] = Math.max(b[4], ny); b[5] = Math.max(b[5], nz);
            }
        }
        Shape s = new Shape(Shape.Type.VOXELS, b[0], b[1], b[2], b[3], b[4], b[5]);
        s.voxels = set; s.name = "Волшебная палочка";
        ChangeSet cs = selectionChange("Волшебная палочка");
        ed.selection.add(s, mode);
        commitSelectionChange(cs);
        toast(String.format("Выделено %,d блоков", set.size()) + (set.size() >= wandLimit ? " (предел)" : ""));
    }

    void brushSelectAt() {
        if (hover == null || selBrushStroke == null) return;
        int r = brushSelRadius;
        int cx = hover.x, cy = hover.y, cz = hover.z;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++)
            if (dx * dx + dy * dy + dz * dz <= (r + 0.5) * (r + 0.5) && PeWorld.inHeight(cy + dy)) selBrushStroke.put(Pos.pack(cx + dx, cy + dy, cz + dz), 1);
    }
    void finishBrushSelect() {
        LongIntMap set = selBrushStroke; selBrushStroke = null;
        if (set == null || set.size() == 0) return;
        int[] b = bounds(set);
        Shape s = new Shape(Shape.Type.VOXELS, b[0], b[1], b[2], b[3], b[4], b[5]);
        s.voxels = set; s.name = "Кистевое выделение";
        ChangeSet cs = selectionChange("Кистевое выделение");
        ed.selection.add(s, gui.ctrl() ? Shape.Mode.SUBTRACT : selBrushMode);
        commitSelectionChange(cs);
    }
    static int[] bounds(LongIntMap set) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        set.forEach((k, v) -> { int x = Pos.x(k), y = Pos.y(k), z = Pos.z(k); b[0] = Math.min(b[0], x); b[1] = Math.min(b[1], y); b[2] = Math.min(b[2], z); b[3] = Math.max(b[3], x); b[4] = Math.max(b[4], y); b[5] = Math.max(b[5], z); });
        return b;
    }

    void selectByType(Shape.Mode mode) {
        if (hover == null || hover.air) return;
        int target = world.getV(hover.x, hover.y, hover.z);
        Selection area = ed.selection.isEmpty() ? null : ed.selection;
        if (area == null) { toast("Сначала выдели область, в которой искать"); return; }
        LongIntMap set = new LongIntMap(1024);
        area.forEach((x, y, z) -> { if (world.getV(x, y, z) == target) set.put(Pos.pack(x, y, z), 1); });
        if (set.size() == 0) return;
        int[] b = bounds(set);
        Shape s = new Shape(Shape.Type.VOXELS, b[0], b[1], b[2], b[3], b[4], b[5]);
        s.voxels = set; s.name = "По типу: " + PeBlocks.get().shortName(target);
        ChangeSet cs = selectionChange("Выделение по типу");
        ed.selection.clear();
        ed.selection.add(s, Shape.Mode.NEW);
        commitSelectionChange(cs);
        toast(String.format("Найдено %,d блоков", set.size()));
    }

    void finishLasso() {
        if (lasso == null || lasso.size() < 3) return;
        double minx = 1, miny = 1, maxx = 0, maxy = 0;
        for (double[] p : lasso) { minx = Math.min(minx, p[0]); miny = Math.min(miny, p[1]); maxx = Math.max(maxx, p[0]); maxy = Math.max(maxy, p[1]); }
        LongIntMap set = new LongIntMap(1024);
        double step = 1.5 / (vh * physPerUnit());
        for (double fy = miny; fy <= maxy; fy += step) for (double fx = minx; fx <= maxx; fx += step) {
            if (!inPoly(lasso, fx, fy)) continue;
            double[] r = vr.ray(fx, fy);
            Raycast.Hit h = Raycast.cast(world, r[0], r[1], r[2], r[3], r[4], r[5], 256, false, vr.opt.showInvisible, sliceOn ? sliceY : 999, 8);
            if (h.air || h.ungenerated) continue;
            for (int d = 0; d < lassoDepth; d++) {
                int x = h.x - (int) Math.signum(Math.round(r[3] * 3)) * 0, y = h.y, z = h.z;
                int ox = h.face == 5 ? -d : h.face == 4 ? d : 0, oy = h.face == 1 ? -d : h.face == 0 ? d : 0, oz = h.face == 3 ? -d : h.face == 2 ? d : 0;
                if (PeWorld.inHeight(y + oy)) set.put(Pos.pack(x + ox, y + oy, z + oz), 1);
            }
            if (set.size() > 2_000_000) break;
        }
        if (set.size() == 0) return;
        int[] b = bounds(set);
        Shape s = new Shape(Shape.Type.VOXELS, b[0], b[1], b[2], b[3], b[4], b[5]);
        s.voxels = set; s.name = "Лассо";
        ChangeSet cs = selectionChange("Лассо");
        ed.selection.add(s, selMode);
        commitSelectionChange(cs);
    }
    static boolean inPoly(List<double[]> p, double x, double y) {
        boolean in = false;
        for (int i = 0, j = p.size() - 1; i < p.size(); j = i++) {
            double xi = p.get(i)[0], yi = p.get(i)[1], xj = p.get(j)[0], yj = p.get(j)[1];
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) in = !in;
        }
        return in;
    }

    /** Ручки: 0–5 грани (−X,+X,−Y,+Y,−Z,+Z), 6–8 стрелки перемещения X/Y/Z. */
    double[][] handlePositions(Shape s) {
        double cx = (s.minX + s.maxX + 1) / 2.0, cy = (s.minY + s.maxY + 1) / 2.0, cz = (s.minZ + s.maxZ + 1) / 2.0;
        double len = Math.max(2, Math.min(8, Math.max(s.sx(), Math.max(s.sy(), s.sz())) * 0.4));
        return new double[][]{
                {s.minX, cy, cz}, {s.maxX + 1, cy, cz}, {cx, s.minY, cz}, {cx, s.maxY + 1, cz}, {cx, cy, s.minZ}, {cx, cy, s.maxZ + 1},
                {s.maxX + 1 + len, cy, cz}, {cx, s.maxY + 1 + len, cz}, {cx, cy, s.maxZ + 1 + len}
        };
    }

    int handleUnderMouse() {
        Shape s = ed.selection.activeShape();
        if (s == null || selPhase != 0 || s.type == Shape.Type.VOXELS) return -1;
        double[][] hp = handlePositions(s);
        int best = -1; double bd = 9;
        for (int i = 0; i < hp.length; i++) {
            double[] p = vr.project(hp[i][0], hp[i][1], hp[i][2]);
            if (p == null) continue;
            double sx = vx + p[0] * vw, sy = vy + p[1] * vh;
            double d = Math.hypot(sx - gui.mx, sy - gui.my);
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    void dragHandleTo(double[] ray) {
        Shape s = ed.selection.activeShape();
        if (s == null || dragOrig == null) return;
        double[][] hp = handlePositions(dragOrig);
        int h = dragHandle;
        int ax = h < 6 ? h / 2 : h - 6;
        double[] dir = {ax == 0 ? 1 : 0, ax == 1 ? 1 : 0, ax == 2 ? 1 : 0};
        double t = closestOnLine(hp[h], dir, ray);
        int d = (int) Math.round(t);
        Shape o = dragOrig;
        if (h >= 6) {
            Shape n = o.copy(); n.move(ax == 0 ? d : 0, ax == 1 ? d : 0, ax == 2 ? d : 0);
            if (n.minY < 0) n.move(0, -n.minY, 0);
            if (n.maxY > 127) n.move(0, 127 - n.maxY, 0);
            copyBox(n, s);
            if (s.type == Shape.Type.POLYGON || s.type == Shape.Type.VOXELS) { s.polygon.clear(); for (int[] p : n.polygon) s.polygon.add(p.clone()); s.voxels = n.voxels; }
        } else {
            int[] mn = {o.minX, o.minY, o.minZ}, mx = {o.maxX, o.maxY, o.maxZ};
            boolean neg = (h & 1) == 0;
            if (neg) { mn[ax] = Math.min(mx[ax], o.min(ax) + d); if (dragSym) mx[ax] = Math.max(mn[ax], o.max(ax) - d); }
            else { mx[ax] = Math.max(mn[ax], o.max(ax) + d); if (dragSym) mn[ax] = Math.min(mx[ax], o.min(ax) - d); }
            mn[1] = Math.max(0, mn[1]); mx[1] = Math.min(127, mx[1]);
            s.setBox(mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
        }
        s.version++;
        ed.selection.changed();
    }
    static void copyBox(Shape from, Shape to) { to.minX = from.minX; to.minY = from.minY; to.minZ = from.minZ; to.maxX = from.maxX; to.maxY = from.maxY; to.maxZ = from.maxZ; to.version++; }

    void growSelectionByFace(int d) {
        Shape s = ed.selection.activeShape();
        if (s == null || hover == null || hover.face < 0) return;
        int f = hover.face;
        int ax = f >> 1; boolean pos = (f & 1) == 1;
        int[] mn = {s.minX, s.minY, s.minZ}, mx = {s.maxX, s.maxY, s.maxZ};
        if (pos) mx[ax] = Math.max(mn[ax], mx[ax] + d); else mn[ax] = Math.min(mx[ax], mn[ax] - d);
        mn[1] = Math.max(0, mn[1]); mx[1] = Math.min(127, mx[1]);
        s.setBox(mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]);
        ed.selection.changed();
    }

    public void nudgeSelection(int dx, int dy, int dz) {
        if (ed.selection.isEmpty()) return;
        ChangeSet cs = selectionChange("Сдвиг выделения");
        ed.selection.move(dx, dy, dz);
        commitSelectionChange(cs);
    }

    // ---------- кисти ----------
    Brush curBrush() { return tool == Tool.TERRAIN ? terrain : tool == Tool.BIOME ? biomeBrush : brush; }

    void brushPress() {
        if (hover == null) return;
        Brush b = curBrush();
        if (b.mode == Brush.Mode.STAMP && ed.clip == null) { toast("Буфер пуст — сначала скопируй что-нибудь"); return; }
        stroke = new ChangeSet(tool == Tool.BIOME ? "Кисть биомов" : "Кисть: " + b.mode.ru, "brush");
        b.pattern = ed.active().copy();
        b.flattenY = hover.air ? hover.y : hover.y;
        lastDab = null;
        brushDrag();
    }

    int[] brushCenter() {
        if (hover == null) return null;
        Brush b = curBrush();
        if (b.placement == 1 || hover.air) return new int[]{hover.x, hover.y, hover.z};
        if (b.placement == 2) return new int[]{hover.x, b.planeY, hover.z};
        return new int[]{hover.x, hover.y, hover.z};
    }

    void brushDrag() {
        if (stroke == null) return;
        int[] c = brushCenter();
        if (c == null) return;
        Brush b = curBrush();
        double spacing = Math.max(1, b.size * 0.25);
        if (lastDab != null && Math.sqrt(Math.pow(c[0] - lastDab[0], 2) + Math.pow(c[1] - lastDab[1], 2) + Math.pow(c[2] - lastDab[2], 2)) < spacing) return;
        lastDab = new double[]{c[0], c[1], c[2]};
        int na = hover == null || hover.face < 0 ? 1 : hover.face >> 1;
        boolean inv = gui.ctrl();
        ChangeSet cs = stroke;
        Clipboard clip = ed.clip;
        long seed = rnd.nextLong();
        ed.enqueue(() -> b.dab(world, c[0], c[1], c[2], na, inv, clip, cs, new Random(seed)));
    }

    void brushRelease() {
        if (stroke == null) return;
        ChangeSet cs = stroke;
        stroke = null;
        ed.enqueue(() -> client.execute(() -> ed.history.push(cs)));
    }

    // ---------- линия ----------
    int pointUnderMouse() {
        for (int i = 0; i < linePts.size(); i++) {
            double[] p = linePts.get(i);
            double[] s = vr.project(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
            if (s == null) continue;
            if (Math.hypot(vx + s[0] * vw - gui.mx, vy + s[1] * vh - gui.my) < 7) return i;
        }
        return -1;
    }

    void linePress() {
        int pi = pointUnderMouse();
        if (pi >= 0 && gui.ctrl()) { linePts.remove(pi); lineVersion++; return; }
        if (pi >= 0) { dragPoint = pi; return; }
        int[] p = hover.air ? new int[]{hover.x, hover.y, hover.z} : placePos();
        if (p == null) return;
        if (lineMode == 0 && linePts.size() >= 2) linePts.clear();
        linePts.add(new double[]{p[0], p[1], p[2]});
        lineVersion++;
    }

    void lineDrag(double[] ray) {
        if (dragPoint < 0 || dragPoint >= linePts.size() || hover == null) return;
        int[] p = hover.air ? new int[]{hover.x, hover.y, hover.z} : placePos();
        if (p == null) return;
        double[] old = linePts.get(dragPoint);
        if (old[0] != p[0] || old[1] != p[1] || old[2] != p[2]) { linePts.set(dragPoint, new double[]{p[0], p[1], p[2]}); lineVersion++; }
    }

    /** Воксели линии/кривой с толщиной и сечением. */
    public LongIntMap lineVoxels() {
        LongIntMap set = new LongIntMap(1024);
        List<double[]> pts = new ArrayList<>();
        if (linePts.size() < 2) return set;
        if (lineMode == 2 && linePts.size() >= 3) {
            for (int i = 0; i < linePts.size() - 1; i++) {
                double[] p0 = linePts.get(Math.max(0, i - 1)), p1 = linePts.get(i), p2 = linePts.get(i + 1), p3 = linePts.get(Math.min(linePts.size() - 1, i + 2));
                double seg = Math.max(2, dist(p1, p2) * 2);
                for (int k = 0; k < seg; k++) pts.add(catmull(p0, p1, p2, p3, k / seg, tension));
            }
            pts.add(linePts.get(linePts.size() - 1));
        } else {
            for (int i = 0; i < linePts.size() - 1; i++) {
                double[] a = linePts.get(i), b = linePts.get(i + 1);
                double n = Math.max(1, dist(a, b) * 2);
                for (int k = 0; k < n; k++) pts.add(new double[]{a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n, a[2] + (b[2] - a[2]) * k / n});
            }
            pts.add(linePts.get(linePts.size() - 1));
        }
        double r = (lineThick - 1) / 2.0;
        int ri = (int) Math.ceil(r);
        for (double[] p : pts) {
            int cx = (int) Math.round(p[0]), cy = (int) Math.round(p[1]), cz = (int) Math.round(p[2]);
            if (lineWall) {
                for (int dy = 0; dy < wallHeight; dy++) for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++)
                    if (dx * dx + dz * dz <= (r + 0.5) * (r + 0.5) && PeWorld.inHeight(cy + dy)) set.put(Pos.pack(cx + dx, cy + dy, cz + dz), 1);
                continue;
            }
            for (int dx = -ri; dx <= ri; dx++) for (int dy = -ri; dy <= ri; dy++) for (int dz = -ri; dz <= ri; dz++) {
                double d2 = dx * dx + dy * dy + dz * dz;
                boolean in = lineSquare || d2 <= (r + 0.5) * (r + 0.5);
                if (!in) continue;
                if (lineHollow && ri > 0) { boolean inner = lineSquare ? Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) < ri : d2 < (r - 0.5) * (r - 0.5); if (inner) continue; }
                if (PeWorld.inHeight(cy + dy)) set.put(Pos.pack(cx + dx, cy + dy, cz + dz), 1);
            }
        }
        return set;
    }
    static double dist(double[] a, double[] b) { return Math.sqrt(Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2)); }
    static double[] catmull(double[] p0, double[] p1, double[] p2, double[] p3, double t, double tension) {
        double[] r = new double[3];
        double s = (1 - tension) * 2;
        for (int i = 0; i < 3; i++) {
            double m1 = (p2[i] - p0[i]) * s / 2, m2 = (p3[i] - p1[i]) * s / 2;
            double t2 = t * t, t3 = t2 * t;
            r[i] = (2 * t3 - 3 * t2 + 1) * p1[i] + (t3 - 2 * t2 + t) * m1 + (-2 * t3 + 3 * t2) * p2[i] + (t3 - t2) * m2;
        }
        return r;
    }

    public void applyLine() {
        LongIntMap set = lineVoxels();
        if (set.size() == 0) { toast("Поставь хотя бы две точки"); return; }
        Pattern pat = ed.active().copy();
        Mask m = brush.mask;
        ed.run("Линия: " + pat.describe(), "line", op -> {
            set.forEach((k, v) -> {
                int x = Pos.x(k), y = Pos.y(k), z = Pos.z(k);
                if (!m.isAny() && !m.test(world, x, y, z, world.getV(x, y, z))) return;
                world.set(x, y, z, pat.apply(x, y, z), op.cs);
            });
            return String.format("Линия: %,d блоков", set.size());
        }, this::opDone);
        linePts.clear(); lineVersion++;
    }

    // ---------- перемещение ----------
    void ensureMoveClip() {
        if (moveClip == null && !ed.selection.isEmpty()) { moveClip = Clipboard.copy(world, ed.selection, true); moveOffset = new int[]{0, 0, 0}; moveRot = 0; moveMirror = false; moveMesh.close(); }
    }

    void movePress() {
        ensureMoveClip();
        if (moveClip == null) { toast("Сначала выдели, что перемещать"); return; }
        int[] b = ed.selection.bounds();
        double[][] arrows = moveArrows(b);
        for (int i = 0; i < 3; i++) {
            double[] s = vr.project(arrows[i][0], arrows[i][1], arrows[i][2]);
            if (s != null && Math.hypot(vx + s[0] * vw - gui.mx, vy + s[1] * vh - gui.my) < 9) { dragHandle = 6 + i; dragT0 = moveOffset[i]; return; }
        }
    }
    double[][] moveArrows(int[] b) {
        double cx = (b[0] + b[3] + 1) / 2.0 + moveOffset[0], cy = (b[1] + b[4] + 1) / 2.0 + moveOffset[1], cz = (b[2] + b[5] + 1) / 2.0 + moveOffset[2];
        return new double[][]{{b[3] + 1 + moveOffset[0] + 3, cy, cz}, {cx, b[4] + 1 + moveOffset[1] + 3, cz}, {cx, cy, b[5] + 1 + moveOffset[2] + 3}};
    }
    void moveDrag(double[] ray) {
        if (dragHandle < 6 || moveClip == null) return;
        int ax = dragHandle - 6;
        int[] b = ed.selection.bounds();
        if (b == null) return;
        int[] saved = moveOffset.clone();
        moveOffset[ax] = (int) dragT0;
        double[][] arrows = moveArrows(b);
        moveOffset = saved;
        double[] dir = {ax == 0 ? 1 : 0, ax == 1 ? 1 : 0, ax == 2 ? 1 : 0};
        double t = closestOnLine(arrows[ax], dir, ray);
        moveOffset[ax] = (int) Math.round(dragT0 + t);
    }

    public void applyMove() {
        if (moveClip == null) return;
        Clipboard c = moveClip;
        for (int i = 0; i < moveRot; i++) c = c.rotateCW();
        if (moveMirror) c = c.mirror(Rotation.Op.MIRROR_X);
        Clipboard fc = c;
        int[] b = ed.selection.bounds();
        Selection src = ed.selection.copy();
        int ox = b[0] + moveOffset[0] - (fc.w - (b[3] - b[0] + 1)) / 2, oy = b[1] + moveOffset[1], oz = b[2] + moveOffset[2] - (fc.l - (b[5] - b[2] + 1)) / 2;
        boolean copy = moveCopy, skip = moveSkipAir, movSel = moveSelection;
        int[] off = moveOffset.clone();
        int rot = moveRot;
        ed.run(copy ? "Копирование выделения" : "Перемещение выделения", "move", op -> {
            if (!copy) Ops.clear(world, src, false, false, op.cs, null);
            fc.paste(world, ox, oy, oz, skip, false, null, true, true, op.cs, op);
            return (copy ? "Скопировано" : "Перемещено") + String.format(" %,d блоков", fc.blockCount());
        }, op -> {
            opDone(op);
            if (!op.cancelled && op.error == null && movSel) {
                ChangeSet cs = selectionChange("Сдвиг выделения");
                if (rot == 0 && !moveMirror) ed.selection.move(off[0], off[1], off[2]);
                else { ed.selection.clear(); ed.selection.add(new Shape(Shape.Type.CUBOID, ox, oy, oz, ox + fc.w - 1, oy + fc.h - 1, oz + fc.l - 1), Shape.Mode.NEW); }
                commitSelectionChange(cs);
            }
        });
        moveClip = null; moveMesh.close(); moveOffset = new int[]{0, 0, 0};
    }

    // ---------- вставка ----------
    public void startPaste(Clipboard c) {
        if (c == null) { toast("Буфер пуст"); return; }
        pasting = true; pasteClip = c; pasteDy = 0; pasteFixed = false; pasteMesh.close();
        toast("Вставка: ЛКМ — поставить, R — повернуть, Alt+R — отразить, Q — точка привязки, Enter — вставить и выйти, Esc — отмена");
    }

    int[] pasteOriginAtHover() {
        if (pasteFixed && pasteOrigin != null) return pasteOrigin;
        if (hover == null || pasteClip == null) return null;
        int[] p = hover.air ? new int[]{hover.x, hover.y, hover.z} : hover.ungenerated ? new int[]{hover.x, 0, hover.z} : new int[]{hover.nx(), hover.ny(), hover.nz()};
        int ox, oz;
        if (pasteAnchor == 0) { ox = p[0] - pasteClip.w / 2; oz = p[2] - pasteClip.l / 2; } else { ox = p[0]; oz = p[2]; }
        int oy = p[1] + pasteDy;
        if (!hover.air && hover.face == 0) oy = p[1] - pasteClip.h + 1 + pasteDy;
        return new int[]{ox, oy, oz};
    }

    void pasteClick() {
        int[] o = pasteOriginAtHover();
        if (o == null) return;
        doPaste(o);
    }

    void doPaste(int[] o) {
        Clipboard c = pasteClip;
        boolean skip = pasteSkipAir, onlyAir = pasteOnlyAir, bio = pasteBiomes, tiles = pasteTiles; Mask m = pasteMask;
        ed.run("Вставка: " + c.name, "paste", op -> { c.paste(world, o[0], o[1], o[2], skip, onlyAir, m, bio, tiles, op.cs, op); return String.format("Вставлено %,d блоков", c.blockCount()); }, this::opDone);
    }

    public void rotatePaste() { if (pasteClip != null) { pasteClip = pasteClip.rotateCW(); pasteMesh.close(); } }
    public void mirrorPaste(boolean vertical) {
        if (pasteClip == null) return;
        if (vertical) pasteClip = pasteClip.mirror(Rotation.Op.MIRROR_Y);
        else {
            int look = Placement.lookDir(cam.yaw);
            pasteClip = pasteClip.mirror(look == 2 || look == 3 ? Rotation.Op.MIRROR_X : Rotation.Op.MIRROR_Z);
        }
        pasteMesh.close();
    }

    // ---------- объекты ----------
    void placeObject() {
        int[] p = placePos();
        if (p == null) return;
        Generators.Params prm = objParams;
        long seed = prm.seed;
        Generators.Params copy = new Generators.Params();
        copy.kind = prm.kind; copy.height = prm.height; copy.crown = prm.crown; copy.randomness = prm.randomness; copy.seed = seed; copy.rotation = prm.rotation; copy.scale = prm.scale; copy.material = ed.activeState(); copy.material2 = prm.material2;
        ed.run("Объект: " + prm.kind.ru, "objects", op -> { Generators.place(world, p[0], p[1], p[2], copy, op.cs); return "Поставлен объект: " + copy.kind.ru; }, this::opDone);
        prm.seed = new Random().nextLong();
        objMesh.close();
    }

    // ---------- контекстное меню ----------
    void openContextMenu() {
        List<EditorUi.MenuItem> m = new ArrayList<>();
        if (pasting) {
            m.add(new EditorUi.MenuItem("Вставить здесь", "ЛКМ", this::pasteClick, true));
            m.add(new EditorUi.MenuItem("Повернуть", "R", this::rotatePaste, true));
            m.add(new EditorUi.MenuItem("Отменить вставку", "Esc", () -> { pasting = false; pasteMesh.close(); }, true));
        } else {
            boolean sel = !ed.selection.isEmpty();
            if (hover != null && !hover.air && !hover.ungenerated) {
                m.add(new EditorUi.MenuItem("Пипетка: " + PeBlocks.get().shortName(Math.max(0, world.getV(hover.x, hover.y, hover.z))), "СКМ", () -> eyedropper(false), true));
                m.add(new EditorUi.MenuItem("Инспектор блока", "V", () -> inspect = new int[]{hover.x, hover.y, hover.z}, true));
                m.add(new EditorUi.MenuItem("Выделить такие же (палочка)", null, () -> magicWand(Shape.Mode.NEW), true));
            }
            m.add(null);
            m.add(new EditorUi.MenuItem("Заполнить", "Ctrl+F", () -> ui.actionFill(), sel));
            m.add(new EditorUi.MenuItem("Заменить…", "Ctrl+H", () -> ui.openReplace(), sel));
            m.add(new EditorUi.MenuItem("Очистить", "Delete", () -> ui.actionClear(false), sel));
            m.add(new EditorUi.MenuItem("Копировать", "Ctrl+C", this::copy, sel));
            m.add(new EditorUi.MenuItem("Вырезать", "Ctrl+X", this::cut, sel));
            m.add(new EditorUi.MenuItem("Вставить", "Ctrl+V", () -> startPaste(ed.clip), ed.clip != null));
            m.add(new EditorUi.MenuItem("Статистика", null, () -> ui.openStats(), sel));
            m.add(new EditorUi.MenuItem("Снять выделение", "Ctrl+D", this::clearSelection, sel));
        }
        ctxMenu = m; ctxX = gui.mx; ctxY = gui.my;
    }

    // ---------- буфер ----------
    public void copy() {
        if (ed.selection.isEmpty()) { toast("Нечего копировать — выдели область"); return; }
        Clipboard c = Clipboard.copy(world, ed.selection, true);
        if (c == null) return;
        c.name = "Копия " + c.w + "×" + c.h + "×" + c.l;
        ed.addClipboard(c);
        toast(String.format("Скопировано %,d блоков", c.blockCount()));
    }
    public void cut() {
        if (ed.selection.isEmpty()) return;
        copy();
        Selection s = ed.selection.copy();
        ed.run("Вырезать", "cut", op -> { Ops.clear(world, s, false, false, op.cs, op); return "Вырезано"; }, this::opDone);
    }

    // ---------- клавиатура ----------
    @Override
    protected void onKey(int key, int mods) {
        boolean ctrl = (mods & GLFW.GLFW_MOD_CONTROL) != 0, alt = (mods & GLFW.GLFW_MOD_ALT) != 0, shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        if (ctxMenu != null) { ctxMenu = null; }
        // фиксация оси при протяжке
        if (lmb && (key == GLFW.GLFW_KEY_X || key == GLFW.GLFW_KEY_Y || key == GLFW.GLFW_KEY_Z)) {
            int a = key == GLFW.GLFW_KEY_X ? 0 : key == GLFW.GLFW_KEY_Y ? 1 : 2;
            axisLock = axisLock == a ? -1 : a;
            return;
        }
        if (ctrl) {
            switch (key) {
                case GLFW.GLFW_KEY_Z -> { if (shift) ed.redo(this::toast); else ed.undo(this::toast); return; }
                case GLFW.GLFW_KEY_Y -> { ed.redo(this::toast); return; }
                case GLFW.GLFW_KEY_C -> { copy(); return; }
                case GLFW.GLFW_KEY_X -> { cut(); return; }
                case GLFW.GLFW_KEY_V -> { startPaste(ed.clip); return; }
                case GLFW.GLFW_KEY_F -> { ui.actionFill(); return; }
                case GLFW.GLFW_KEY_H -> { ui.openReplace(); return; }
                case GLFW.GLFW_KEY_D -> { clearSelection(); return; }
                case GLFW.GLFW_KEY_S -> { if (shift) ui.openSave(); else ui.quickSave(); return; }
                case GLFW.GLFW_KEY_I -> { ui.importSchematic(null); return; }
                case GLFW.GLFW_KEY_G -> { ui.openGoto(); return; }
                default -> {}
            }
            if (alt && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                WbConfig.get().bookmarks.put(world.level.levelName() + "#" + (key - GLFW.GLFW_KEY_0), new double[]{cam.x, cam.y, cam.z, cam.yaw, cam.pitch});
                WbConfig.get().save();
                toast("Закладка камеры " + (key - GLFW.GLFW_KEY_0) + " сохранена");
                return;
            }
            if (alt && key == GLFW.GLFW_KEY_R) { if (pasting) mirrorPaste(true); else { manualFlip = !manualFlip; manualOrient = true; } return; }
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) { arrow(key, 10); return; }
            return;
        }
        if (alt) {
            if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
                double[] b = WbConfig.get().bookmarks.get(world.level.levelName() + "#" + (key - GLFW.GLFW_KEY_0));
                if (b != null) cam.flyTo(b[0], b[1], b[2], (float) b[3], (float) b[4], 0.4); else toast("Закладка " + (key - GLFW.GLFW_KEY_0) + " не задана (Ctrl+Alt+" + (key - GLFW.GLFW_KEY_0) + " — сохранить)");
                return;
            }
            if (key == GLFW.GLFW_KEY_R) { if (pasting) mirrorPaste(false); else if (tool == Tool.MOVE) { moveMirror = !moveMirror; moveMesh.close(); } else { manualFlip = !manualFlip; manualOrient = true; } return; }
            if (key == GLFW.GLFW_KEY_PAGE_UP) { sliceY = Math.min(127, sliceY + 1); if (sliceOn) vr.invalidateAll(); return; }
            if (key == GLFW.GLFW_KEY_PAGE_DOWN) { sliceY = Math.max(0, sliceY - 1); if (sliceOn) vr.invalidateAll(); return; }
            return;
        }
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) { ed.slot = key - GLFW.GLFW_KEY_1; manualOrient = false; return; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (pasting) { pasteClick(); pasting = false; pasteMesh.close(); return; }
            if (tool == Tool.MOVE && moveClip != null) { applyMove(); return; }
            if (tool == Tool.LINE && linePts.size() >= 2) { applyLine(); return; }
            if (tool == Tool.SELECT && selType == Shape.Type.POLYGON && polyPoints.size() >= 3) { finishPolygon(ui.polyHeight); return; }
            return;
        }
        if (Keys.is(key, "rotate")) {
            if (pasting) rotatePaste();
            else if (tool == Tool.MOVE) { moveRot = (moveRot + 1) % 4; moveMesh.close(); }
            else if (tool == Tool.OBJECTS) { objParams.rotation = (objParams.rotation + 1) % 4; objMesh.close(); }
            else { manualRot = (manualRot + 1) % 4; manualOrient = true; }
            return;
        }
        if (Keys.is(key, "anchor") && pasting) { pasteAnchor = 1 - pasteAnchor; toast(pasteAnchor == 0 ? "Привязка: низ-центр" : "Привязка: угол"); return; }
        if (Keys.is(key, "hide_ui")) { hideUi = !hideUi; return; }
        if (Keys.is(key, "slice")) { sliceOn = !sliceOn; vr.invalidateAll(); toast(sliceOn ? "Срез по высоте Y ≤ " + sliceY : "Срез выключен"); return; }
        if (Keys.is(key, "help")) { ui.openHelp(); return; }
        if (Keys.is(key, "debug")) { debug = !debug; return; }
        if (Keys.is(key, "frame")) { frame(); return; }
        if (Keys.is(key, "home")) { cam.flyTo(world.level.spawnX() + 0.5, world.level.spawnY() + 10, world.level.spawnZ() - 15, 0, 30, 0.4); return; }
        if (Keys.is(key, "delete")) { ui.actionClear(false); return; }
        if (Keys.is(key, "brush_smaller") || Keys.is(key, "brush_bigger")) {
            int d = Keys.is(key, "brush_bigger") ? 1 : -1;
            Brush b = curBrush(); b.size = Math.max(0, Math.min(64, b.size + d * (gui.ctrl() ? 5 : 1)));
            return;
        }
        if (key == GLFW.GLFW_KEY_KP_5) { cam.ortho = !cam.ortho; return; }
        if (key == GLFW.GLFW_KEY_KP_7) { cam.ortho = true; cam.pitch = 90; return; }
        if (key == GLFW.GLFW_KEY_KP_1) { cam.ortho = true; cam.pitch = 0; cam.yaw = 180; return; }
        if (key == GLFW.GLFW_KEY_KP_3) { cam.ortho = true; cam.pitch = 0; cam.yaw = 90; return; }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN || key == GLFW.GLFW_KEY_PAGE_UP || key == GLFW.GLFW_KEY_PAGE_DOWN) { arrow(key, 1); return; }
        for (Tool t : Tool.values()) if (Keys.is(key, t.key) && !lmb) { setTool(t); return; }
    }

    void arrow(int key, int step) {
        int look = Placement.lookDir(cam.yaw);
        int fx = look == 5 ? 1 : look == 4 ? -1 : 0, fz = look == 3 ? 1 : look == 2 ? -1 : 0;
        int dx = 0, dy = 0, dz = 0;
        switch (key) {
            case GLFW.GLFW_KEY_UP -> { dx = fx; dz = fz; }
            case GLFW.GLFW_KEY_DOWN -> { dx = -fx; dz = -fz; }
            case GLFW.GLFW_KEY_LEFT -> { dx = fz; dz = -fx; }
            case GLFW.GLFW_KEY_RIGHT -> { dx = -fz; dz = fx; }
            case GLFW.GLFW_KEY_PAGE_UP -> dy = 1;
            case GLFW.GLFW_KEY_PAGE_DOWN -> dy = -1;
        }
        dx *= step; dy *= step; dz *= step;
        if (pasting) { if (pasteOrigin == null) pasteOrigin = pasteOriginAtHover(); if (pasteOrigin != null) { pasteFixed = true; pasteOrigin[0] += dx; pasteOrigin[1] += dy; pasteOrigin[2] += dz; } return; }
        if (tool == Tool.MOVE && moveClip != null) { moveOffset[0] += dx; moveOffset[1] += dy; moveOffset[2] += dz; return; }
        nudgeSelection(dx, dy, dz);
    }

    public void setTool(Tool t) {
        if (tool == Tool.MOVE && t != Tool.MOVE) { moveClip = null; moveMesh.close(); }
        tool = t;
        selPhase = 0; creating = null; manualOrient = false;
        if (t == Tool.MOVE) ensureMoveClip();
    }

    void frame() {
        int[] b = ed.selection.bounds();
        if (b != null) cam.frame(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1);
        else if (hover != null && !hover.air) cam.frame(hover.x - 3, hover.y - 3, hover.z - 3, hover.x + 4, hover.y + 4, hover.z + 4);
    }

    @Override
    protected void onEscape() {
        if (ctxMenu != null) { ctxMenu = null; return; }
        if (lmb) return;
        if (pasting) { pasting = false; pasteMesh.close(); return; }
        if (selPhase != 0) { selPhase = 0; creating = null; return; }
        if (!polyPoints.isEmpty()) { polyPoints.clear(); return; }
        if (tool == Tool.LINE && !linePts.isEmpty()) { linePts.clear(); lineVersion++; return; }
        if (tool == Tool.MOVE && moveClip != null && (moveOffset[0] != 0 || moveOffset[1] != 0 || moveOffset[2] != 0 || moveRot != 0)) { moveOffset = new int[]{0, 0, 0}; moveRot = 0; moveMesh.close(); return; }
        if (!rulerPts.isEmpty()) { rulerPts.clear(); return; }
        if (inspect != null) { inspect = null; return; }
        if (ed.busy() && ed.running != null) { ed.running.cancelled = true; return; }
        if (!ed.selection.isEmpty()) { clearSelection(); return; }
        ui.askExit();
    }

    // ---------- отрисовка ----------
    @Override
    protected void draw(float delta) {
        updateHeightPhase();
        if (ed.selection.version != selRequested) requestSelectionMesh();
        ui.draw(delta);
        if (!tourShown && !WbConfig.get().tourDone) { tourShown = true; ui.startTour(); }
    }

    void requestSelectionMesh() {
        selRequested = ed.selection.version;
        if (ed.selection.isEmpty()) { selVoxels = null; selMesh.close(); return; }
        Selection s = ed.selection.copy();
        int ver = ed.selection.version;
        int[] b = s.bounds();
        if (b == null) return;
        long vol = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        if (vol > 8_000_000L) { selVoxels = null; selMesh.close(); return; }
        Task.IO.submit(() -> {
            LongIntMap set = new LongIntMap((int) Math.min(1 << 20, vol));
            s.forEach((x, y, z) -> set.put(Pos.pack(x, y, z), 1));
            client.execute(() -> { if (ver == ed.selection.version) { selVoxels = set; selVoxelsVersion = ver; selMesh.close(); } });
        });
    }

    /** Оверлеи во вьюпорте (мировые координаты). */
    void drawOverlays() {
        WbConfig c = WbConfig.get();
        int accent = 0xFF3DDC97;
        // выделение
        if (selVoxels != null && selVoxelsVersion == ed.selection.version && selMesh.builtKey != selVoxelsVersion) {
            int a = (int) (Math.max(0.05, Math.min(0.8, c.selectionAlpha)) * 255);
            int col = (a << 24) | (accent & 0xFFFFFF);
            selMesh.build(selVoxels, 0, 0, 0, (x, y, z) -> col, 3_000_000);
            selMesh.builtKey = selVoxelsVersion;
        }
        if (!ed.selection.isEmpty()) selMesh.draw(vr, 0, 0, 0, true);
        Overlay.begin();
        // границы мира
        if (c.worldBounds) {
            int ccx = (int) Math.floor(cam.x) >> 4, ccz = (int) Math.floor(cam.z) >> 4, r = vr.renderDistance;
            for (int i = -r; i <= r + 1; i++) {
                Overlay.line((ccx + i) * 16, 128, (ccz - r) * 16, (ccx + i) * 16, 128, (ccz + r + 1) * 16, 0x30FF8080);
                Overlay.line((ccx - r) * 16, 128, (ccz + i) * 16, (ccx + r + 1) * 16, 128, (ccz + i) * 16, 0x30FF8080);
            }
        }
        // сетка чанков и регионов
        if (c.chunkGrid) {
            int ccx = (int) Math.floor(cam.x) >> 4, ccz = (int) Math.floor(cam.z) >> 4, r = Math.min(vr.renderDistance, 16);
            double gy = Math.max(0, Math.min(127, (int) cam.y - 1));
            if (hover != null && !hover.air) gy = hover.y + 1;
            for (int i = -r; i <= r + 1; i++) {
                int xl = (ccx + i) * 16, zl = (ccz + i) * 16;
                boolean rx = ((ccx + i) & 31) == 0, rz = ((ccz + i) & 31) == 0;
                Overlay.line(xl, gy, (ccz - r) * 16, xl, gy, (ccz + r + 1) * 16, rx ? 0xC0FF5050 : 0x80FFFF60);
                Overlay.line((ccx - r) * 16, gy, zl, (ccx + r + 1) * 16, gy, zl, rz ? 0xC0FF5050 : 0x80FFFF60);
            }
        }
        // несгенерированные чанки
        {
            int ccx = (int) Math.floor(cam.x) >> 4, ccz = (int) Math.floor(cam.z) >> 4, r = Math.min(vr.renderDistance, 12);
            for (int cx = ccx - r; cx <= ccx + r; cx++) for (int cz = ccz - r; cz <= ccz + r; cz++) {
                if (world.chunkExists(cx, cz)) continue;
                Overlay.face(cx * 16, -0.02, cz * 16, cx * 16 + 16, 0, cz * 16 + 16, 1, 0x50101010);
                Overlay.box(cx * 16 + 0.1, 0, cz * 16 + 0.1, cx * 16 + 15.9, 0.01, cz * 16 + 15.9, 0x40FFFFFF);
            }
        }
        // повреждённые чанки
        for (var ch : world.loadedChunks()) if (ch.corrupt) Overlay.box(ch.cx * 16, 0, ch.cz * 16, ch.cx * 16 + 16, 128, ch.cz * 16 + 16, 0xFFF25F5C);
        // пол-сетка на высоте курсора
        if (c.floorGrid && hover != null) {
            int gy = hover.air ? hover.y : hover.ny();
            for (int i = -16; i <= 16; i++) {
                Overlay.line(hover.x - 16, gy, hover.z + i, hover.x + 17, gy, hover.z + i, 0x30FFFFFF);
                Overlay.line(hover.x + i, gy, hover.z - 16, hover.x + i, gy, hover.z + 17, 0x30FFFFFF);
            }
        }
        // рамки фигур выделения и ручки
        for (Shape s : ed.selection.shapes) {
            if (s.hidden) continue;
            int col = s == ed.selection.activeShape() ? 0xFF3DDC97 : s.mode == Shape.Mode.SUBTRACT ? 0xFFF25F5C : s.mode == Shape.Mode.INTERSECT ? 0xFFF2C14E : 0xA03DDC97;
            int[] b = s.bounds();
            Overlay.box(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, col);
        }
        if (selVoxels == null && !ed.selection.isEmpty()) {
            int[] b = ed.selection.bounds();
            if (b != null) Overlay.cube(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, 0x303DDC97);
        }
        Shape act = ed.selection.activeShape();
        if (tool == Tool.SELECT && act != null && selPhase == 0 && act.type != Shape.Type.VOXELS) {
            double[][] hp = handlePositions(act);
            int hov = dragHandle >= 0 ? dragHandle : handleUnderMouse();
            double hs = Math.max(0.25, Math.min(1.2, Math.sqrt(Math.pow(cam.x - hp[0][0], 2) + Math.pow(cam.y - hp[0][1], 2) + Math.pow(cam.z - hp[0][2], 2)) * 0.02));
            for (int i = 0; i < hp.length; i++) {
                int ax = i < 6 ? i / 2 : i - 6;
                int col = ax == 0 ? 0xFFE04848 : ax == 1 ? 0xFF48D048 : 0xFF4878E0;
                if (i == hov) col = 0xFFFFFFFF;
                double[] p = hp[i];
                if (i < 6) Overlay.cube(p[0] - hs / 2, p[1] - hs / 2, p[2] - hs / 2, p[0] + hs / 2, p[1] + hs / 2, p[2] + hs / 2, col);
                else {
                    double[] base = {i == 6 ? act.maxX + 1 : p[0], i == 7 ? act.maxY + 1 : p[1], i == 8 ? act.maxZ + 1 : p[2]};
                    Overlay.line(base[0], base[1], base[2], p[0], p[1], p[2], col);
                    Overlay.cube(p[0] - hs * 0.7, p[1] - hs * 0.7, p[2] - hs * 0.7, p[0] + hs * 0.7, p[1] + hs * 0.7, p[2] + hs * 0.7, col);
                }
            }
        }
        // создание фигуры
        if (creating != null) {
            Overlay.box(creating.minX, creating.minY, creating.minZ, creating.maxX + 1, creating.maxY + 1, creating.maxZ + 1, 0xFFFFFFFF);
            Overlay.cube(creating.minX, creating.minY, creating.minZ, creating.maxX + 1, creating.maxY + 1, creating.maxZ + 1, 0x303DDC97);
        }
        for (int i = 0; i < polyPoints.size(); i++) {
            int[] p = polyPoints.get(i);
            Overlay.box(p[0], p[1], p[2], p[0] + 1, p[1] + 1, p[2] + 1, 0xFFFFFFFF);
            int[] q = polyPoints.get((i + 1) % polyPoints.size());
            Overlay.line(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5, q[0] + 0.5, p[1] + 0.5, q[2] + 0.5, 0xFF3DDC97);
        }
        // наведение и призрак
        if (hover != null && overViewport && !looking && !pasting) drawHover();
        // кисть
        if ((tool == Tool.BRUSH || tool == Tool.TERRAIN || tool == Tool.BIOME) && hover != null && overViewport) {
            int[] cc = brushCenter();
            Brush b = curBrush();
            if (cc != null) {
                double r = b.size + 0.5;
                int segs = 48;
                for (int i = 0; i < segs; i++) {
                    double a1 = i * Math.PI * 2 / segs, a2 = (i + 1) * Math.PI * 2 / segs;
                    double yy = cc[1] + 1.02;
                    Overlay.line(cc[0] + 0.5 + Math.cos(a1) * r, yy, cc[2] + 0.5 + Math.sin(a1) * r, cc[0] + 0.5 + Math.cos(a2) * r, yy, cc[2] + 0.5 + Math.sin(a2) * r, 0xFFFFFFFF);
                }
                if (b.symX || b.symZ) Overlay.box(b.symCX, 0, b.symCZ, b.symCX + 1, 128, b.symCZ + 1, 0xA0FF60FF);
            }
        }
        // линия
        for (int i = 0; i < linePts.size(); i++) {
            double[] p = linePts.get(i);
            Overlay.cube(p[0] + 0.3, p[1] + 0.3, p[2] + 0.3, p[0] + 0.7, p[1] + 0.7, p[2] + 0.7, i == dragPoint ? 0xFFFFFFFF : 0xFFF2C14E);
        }
        // линейка
        if (!rulerPts.isEmpty()) {
            int[] a = rulerPts.get(0);
            int[] b = rulerPts.size() > 1 ? rulerPts.get(1) : (hover != null ? new int[]{hover.x, hover.y, hover.z} : a);
            Overlay.line(a[0] + 0.5, a[1] + 0.5, a[2] + 0.5, b[0] + 0.5, b[1] + 0.5, b[2] + 0.5, 0xFFF2C14E);
            Overlay.box(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]) + 1, Math.max(a[1], b[1]) + 1, Math.max(a[2], b[2]) + 1, 0x80F2C14E);
        }
        // спавн
        Overlay.line(world.level.spawnX() + 0.5, world.level.spawnY(), world.level.spawnZ() + 0.5, world.level.spawnX() + 0.5, world.level.spawnY() + 3, world.level.spawnZ() + 0.5, 0xFFFF4040);
        Overlay.face(world.level.spawnX() + 0.5, world.level.spawnY() + 2.2, world.level.spawnZ() + 0.5, world.level.spawnX() + 1.3, world.level.spawnY() + 3, world.level.spawnZ() + 0.5, 2, 0xFFFF4040);
        // биомы
        if (showBiomes) {
            int ccx = (int) Math.floor(cam.x), ccz = (int) Math.floor(cam.z);
            for (int x = ccx - 48; x < ccx + 48; x++) for (int z = ccz - 48; z < ccz + 48; z++) {
                if (!world.chunkExists(x >> 4, z >> 4)) continue;
                int top = Math.max(0, world.topY(x, z));
                int id = (world.getBiomeColor(x, z) >>> 24) & 0xFF;
                Overlay.face(x, top, z, x + 1, top + 1.05, z + 1, 1, (Biomes.debugColor(id) & 0xFFFFFF) | 0x70000000);
            }
        }
        Overlay.end(true);
        // предпросмотры (без глубины поверх)
        drawPreviews();
    }

    void drawHover() {
        int act = 0xFFFFFFFF;
        switch (tool) {
            case PENCIL -> {
                boolean del = gui.ctrl(), rep = gui.alt();
                if (del || rep) {
                    if (!hover.air) { Overlay.box(hover.x - 0.002, hover.y - 0.002, hover.z - 0.002, hover.x + 1.002, hover.y + 1.002, hover.z + 1.002, del ? 0xFFF25F5C : 0xFFF2C14E); Overlay.face(hover.x, hover.y, hover.z, hover.face, (del ? 0x50F25F5C : 0x50F2C14E)); }
                } else {
                    int[] p = placePos();
                    if (p != null && PeWorld.inHeight(p[1])) {
                        for (Placement.Put put : computePlacement(p)) {
                            int x = p[0] + put.dx(), y = p[1] + put.dy(), z = p[2] + put.dz();
                            Overlay.cube(x + 0.02, y + 0.02, z + 0.02, x + 0.98, y + 0.98, z + 0.98, 0x40FFFFFF);
                            Overlay.box(x, y, z, x + 1, y + 1, z + 1, act);
                        }
                        if (!hover.air && !hover.ungenerated) Overlay.face(hover.x, hover.y, hover.z, hover.face, 0x40FFFFFF);
                    }
                }
            }
            case ERASER -> { if (!hover.air) { int r = eraserSize - 1; Overlay.box(hover.x - r, hover.y - r, hover.z - r, hover.x + r + 1, hover.y + r + 1, hover.z + r + 1, 0xFFF25F5C); } }
            default -> {
                if (!hover.air && !hover.ungenerated) {
                    Overlay.box(hover.x - 0.002, hover.y - 0.002, hover.z - 0.002, hover.x + 1.002, hover.y + 1.002, hover.z + 1.002, 0xC0FFFFFF);
                    Overlay.face(hover.x, hover.y, hover.z, hover.face, 0x30FFFFFF);
                } else if (hover.air) Overlay.box(hover.x, hover.y, hover.z, hover.x + 1, hover.y + 1, hover.z + 1, 0x80FFFFFF);
            }
        }
    }

    void drawPreviews() {
        // вставка
        if (pasting && pasteClip != null) {
            if (pasteMesh.builtKey != System.identityHashCode(pasteClip)) {
                Clipboard c = pasteClip;
                pasteMesh.build(0, 0, 0, c.w - 1, c.h - 1, c.l - 1, (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < c.w && y < c.h && z < c.l && c.get(x, y, z) != Clipboard.NONE && c.get(x, y, z) != 0,
                        (x, y, z) -> c.get(x, y, z) < 0 ? 0x80FF00FF : 0x6060B0FF, 1_500_000);
                pasteMesh.builtKey = System.identityHashCode(pasteClip);
            }
            int[] o = pasteOriginAtHover();
            if (o != null) {
                pasteMesh.draw(vr, o[0], o[1], o[2], true);
                Overlay.begin();
                Overlay.box(o[0], o[1], o[2], o[0] + pasteClip.w, o[1] + pasteClip.h, o[2] + pasteClip.l, 0xFF60B0FF);
                Overlay.end(false);
            }
        }
        // перемещение
        if (tool == Tool.MOVE && moveClip != null && !ed.selection.isEmpty()) {
            Clipboard c = moveClip;
            for (int i = 0; i < moveRot; i++) c = c.rotateCW();
            if (moveMirror) c = c.mirror(Rotation.Op.MIRROR_X);
            Clipboard fc = c;
            long key = System.identityHashCode(moveClip) * 31L + moveRot * 2 + (moveMirror ? 1 : 0);
            if (moveMesh.builtKey != key) {
                moveMesh.build(0, 0, 0, fc.w - 1, fc.h - 1, fc.l - 1, (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < fc.w && y < fc.h && z < fc.l && fc.get(x, y, z) != Clipboard.NONE && fc.get(x, y, z) != 0, (x, y, z) -> 0x6060FFB0, 1_500_000);
                moveMesh.builtKey = key;
            }
            int[] b = ed.selection.bounds();
            int ox = b[0] + moveOffset[0] - (fc.w - (b[3] - b[0] + 1)) / 2, oy = b[1] + moveOffset[1], oz = b[2] + moveOffset[2] - (fc.l - (b[5] - b[2] + 1)) / 2;
            moveMesh.draw(vr, ox, oy, oz, true);
            Overlay.begin();
            Overlay.box(ox, oy, oz, ox + fc.w, oy + fc.h, oz + fc.l, 0xFF60FFB0);
            double[][] ar = moveArrows(b);
            int[] cols = {0xFFE04848, 0xFF48D048, 0xFF4878E0};
            double cx = (b[0] + b[3] + 1) / 2.0 + moveOffset[0], cy = (b[1] + b[4] + 1) / 2.0 + moveOffset[1], cz = (b[2] + b[5] + 1) / 2.0 + moveOffset[2];
            for (int i = 0; i < 3; i++) { Overlay.line(cx, cy, cz, ar[i][0], ar[i][1], ar[i][2], cols[i]); Overlay.cube(ar[i][0] - 0.4, ar[i][1] - 0.4, ar[i][2] - 0.4, ar[i][0] + 0.4, ar[i][1] + 0.4, ar[i][2] + 0.4, dragHandle == 6 + i ? 0xFFFFFFFF : cols[i]); }
            Overlay.end(false);
        }
        // линия
        if (tool == Tool.LINE && linePts.size() >= 2) {
            if (lineBuilt != lineVersion * 31 + lineThick * 7 + (lineHollow ? 1 : 0) + (lineSquare ? 2 : 0) + lineMode * 3 + (lineWall ? 5 : 0) + wallHeight * 11) {
                LongIntMap set = lineVoxels();
                lineMesh.build(set, 0, 0, 0, (x, y, z) -> 0x60F2C14E, 1_000_000);
                lineBuilt = lineVersion * 31 + lineThick * 7 + (lineHollow ? 1 : 0) + (lineSquare ? 2 : 0) + lineMode * 3 + (lineWall ? 5 : 0) + wallHeight * 11;
            }
            lineMesh.draw(vr, 0, 0, 0, true);
        }
        // объекты
        if (tool == Tool.OBJECTS && hover != null && overViewport) {
            long key = Objects.hash(objParams.kind, objParams.height, objParams.crown, objParams.randomness, objParams.seed, objParams.rotation, objParams.scale);
            if (objMesh.builtKey != key) {
                Map<Long, Integer> m = Generators.build(objParams, new Random(objParams.seed));
                LongIntMap set = new LongIntMap(1024);
                for (long k : m.keySet()) {
                    int dx = Generators.dxOf(k), dy = Generators.dyOf(k), dz = Generators.dzOf(k);
                    for (int i = 0; i < objParams.rotation; i++) { int t = dx; dx = -dz; dz = t; }
                    set.put(Pos.pack(dx, dy + 64, dz), 1);
                }
                objMesh.build(set, 0, 64, 0, (x, y, z) -> 0x5080FF80, 500_000);
                objMesh.builtKey = key;
            }
            int[] p = placePos();
            if (p != null) objMesh.draw(vr, p[0], p[1], p[2], true);
        }
        // кисть: точный предпросмотр до размера 12
        if ((tool == Tool.BRUSH || tool == Tool.TERRAIN) && hover != null && overViewport && curBrush().size <= 12) {
            int[] cc = brushCenter();
            if (cc != null) {
                Brush b = curBrush();
                long key = ((long) b.size << 8) | (b.form.ordinal() << 4) | (hover.face < 0 ? 1 : hover.face >> 1);
                if (brushMesh.builtKey != key) {
                    LongIntMap set = b.previewVoxels(0, 64, 0, hover.face < 0 ? 1 : hover.face >> 1);
                    brushMesh.build(set, 0, 64, 0, (x, y, z) -> 0x30FFFFFF, 200_000);
                    brushMesh.builtKey = key;
                }
                brushMesh.draw(vr, cc[0], cc[1], cc[2], true);
            }
        }
    }

    @Override
    public void filesDragged(List<Path> paths) {
        for (Path p : paths) {
            String n = p.getFileName().toString().toLowerCase();
            if (n.endsWith(".schem") || n.endsWith(".schematic")) { ui.importSchematic(p); return; }
        }
        toast("Перетащи файл .schem или .schematic");
    }

    @Override
    public void removed() {
        stopLook();
    }

    public void exitEditor() {
        stopLook();
        if (vr != null) vr.close();
        selMesh.close(); pasteMesh.close(); moveMesh.close(); lineMesh.close(); objMesh.close(); brushMesh.close();
        ed.shutdown();
        SaveManager.closeSession(ed);
        client.setScreen(parent);
    }

    @Override public boolean shouldPause() { return false; }
    public EditorUi ui() { return ui; }

    public static int stateOrAir(int v) { return Math.max(0, v); }
    static boolean jsReady() { return JavaStates.isReady(); }
}
