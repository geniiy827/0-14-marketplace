package com.nova.engine.game;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import com.nova.engine.mod.CoreContent;
import com.nova.engine.mod.NovaMod;
import com.nova.engine.mod.Registry;
import com.nova.engine.render.Atlas;
import com.nova.engine.render.Hud;
import com.nova.engine.render.Mesher;
import com.nova.engine.render.WorldRenderer;
import com.nova.engine.world.Blocks;
import com.nova.engine.world.Chunk;
import com.nova.engine.world.Generator;
import com.nova.engine.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** The game loop (GL thread): ticks at 20 TPS, renders every frame, feeds the worker threads. */
public final class Game implements GLSurfaceView.Renderer {
    static final String TAG = "NOVA.game";
    public static final String VERSION = "NOVA Engine beta6";

    private final Context ctx;
    public final Controls controls = new Controls();
    private final Atlas atlas = new Atlas();
    private final WorldRenderer wr = new WorldRenderer();
    private final Hud hud = new Hud();
    private World world;
    private final Player player = new Player();
    private final Raycast ray = new Raycast();
    private ExecutorService workers;
    private final ConcurrentLinkedQueue<Mesher.Mesh> done = new ConcurrentLinkedQueue<Mesher.Mesh>();
    private final ThreadLocal<Mesher> meshers = new ThreadLocal<Mesher>();
    private final java.util.Set<Long> decorating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final ExecutorService decoThread = Executors.newSingleThreadExecutor();
    private com.nova.engine.world.Decorator deco;
    private final java.util.Set<Long> generating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private int width = 1, height = 1;
    private float density = 3;
    private long lastNs, tickAcc;
    private int radius = 6;
    private int slot, guiTex, iconsTex;
    private final int[] hotbar = CoreContent.HOTBAR.clone();
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16], look = new float[3];
    private static final float[] SKY = {0.53f, 0.71f, 0.98f};
    private int fps, frames;
    private long fpsT;
    private String error;
    private volatile boolean ready;

    public Game(Context c) { ctx = c; density = c.getResources().getDisplayMetrics().density; }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            atlas.load(ctx);
            Registry reg = new Registry(atlas);
            List<NovaMod> mods = new ArrayList<NovaMod>();
            mods.add(new CoreContent());                       // the game itself is the first mod
            for (NovaMod m : mods) { reg.modId = m.id(); m.register(reg); }
            atlas.upload();
            wr.init();
            hud.init(atlas.gameAssets);
            guiTex = hud.loadTex(atlas.gameAssets, "images/gui/gui.png");
            iconsTex = hud.loadTex(atlas.gameAssets, "images/gui/icons.png");
            if (world == null) {
                world = new World(System.currentTimeMillis());
                deco = new com.nova.engine.world.Decorator(world, world.seed);
                // spawn: first dry land along a spiral from 0,0
                int sx = 0, sz = 0;
                for (int i = 0; i < 400; i++) {
                    double a = i * 0.5;
                    int tx = (int) (Math.cos(a) * i * 8), tz = (int) (Math.sin(a) * i * 8);
                    int b = world.gen.biomeAt(tx, tz);
                    if (b != com.nova.engine.world.Biomes.OCEAN && b != com.nova.engine.world.Biomes.DEEP_OCEAN && world.gen.height(tx, tz) > Generator.SEA) { sx = tx; sz = tz; break; }
                }
                player.x = sx + 0.5; player.z = sz + 0.5; player.y = world.gen.height(sx, sz) + 2;
                player.px = player.x; player.py = player.y; player.pz = player.z;
            } else {
                wr.sections.clear();                            // new GL context: everything re-uploads
                for (Chunk c : world.chunks.values()) for (int s = 0; s < Chunk.SECTIONS; s++) c.version[s]++;
            }
            if (workers == null) {
                int n = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() - 1));
                workers = Executors.newFixedThreadPool(n, new ThreadFactory() {
                    public Thread newThread(Runnable r) { Thread t = new Thread(r, "nova-worker"); t.setPriority(Thread.MIN_PRIORITY + 1); return t; }
                });
            }
            GLES30.glEnable(GLES30.GL_DEPTH_TEST);
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);
            GLES30.glFrontFace(GLES30.GL_CCW);
            lastNs = System.nanoTime();
            ready = true;
        } catch (Throwable t) {
            Log.e(TAG, "init", t);
            error = String.valueOf(t);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w; height = h;
        GLES30.glViewport(0, 0, w, h);
        controls.layout(w, h, density);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (!ready) {
            GLES30.glClearColor(0.1f, 0.1f, 0.15f, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        long now = System.nanoTime();
        long dt = Math.min(250_000_000L, now - lastNs);
        lastNs = now;
        input();
        tickAcc += dt;
        while (tickAcc >= 50_000_000L) { player.tick(world); tickAcc -= 50_000_000L; }
        float a = tickAcc / 50_000_000f;
        stream();
        render(a);
        frames++;
        if (now - fpsT > 1_000_000_000L) { fps = frames; frames = 0; fpsT = now; }
    }

    // ------------------------------------------------------------------ input
    private final float[] touchXY = new float[32], pickDir = new float[3];
    private final Raycast pick = new Raycast();
    /** block under the interacting finger (highlight), valid when hl */
    private boolean hl;
    private int hlX, hlY, hlZ;

    /** direction through a screen point (MCPE touch picks under the finger: useFreeformPickDirection) */
    private void screenDir(float sx, float sy, float[] out) {
        float tanY = (float) Math.tan(Math.toRadians(70 / 2.0)), tanX = tanY * width / (float) height;
        float nx = (sx / width * 2 - 1) * tanX, ny = (1 - sy / height * 2) * tanY;
        double ry = Math.toRadians(player.yaw), rp = Math.toRadians(player.pitch);
        float fx = (float) (Math.sin(ry) * Math.cos(rp)), fy = (float) -Math.sin(rp), fz = (float) (-Math.cos(ry) * Math.cos(rp));
        float rx = (float) Math.cos(ry), rz = (float) Math.sin(ry);                       // right
        float ux = (float) (Math.sin(ry) * Math.sin(rp)), uy = (float) Math.cos(rp), uz = (float) (-Math.cos(ry) * Math.sin(rp));   // up
        float dx = fx + rx * nx + ux * ny, dy = fy + uy * ny, dz = fz + rz * nx + uz * ny;
        float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        out[0] = dx / l; out[1] = dy / l; out[2] = dz / l;
    }

    private boolean pickAt(float sx, float sy) {
        screenDir(sx, sy, pickDir);
        pick.cast(world, player.ex(1), player.ey(1), player.ez(1), pickDir[0], pickDir[1], pickDir[2], 6);
        return pick.hit;
    }

    private void input() {
        Controls c = controls;
        c.flying = player.flying;
        c.poll();
        float sens = 0.25f / density * 1.6f;
        player.yaw += c.takeLookX() * sens;
        player.pitch = Math.max(-90, Math.min(90, player.pitch + c.takeLookY() * sens));
        player.moveF = c.moveF; player.moveS = c.moveS;
        player.jump = player.flying ? c.ascend : c.jump;
        player.sneak = player.flying ? c.descend : c.sneak;
        if (c.takeFly() > 0) { player.flying = !player.flying; player.my = 0; }
        int s = c.takeSlot();
        if (s >= 0) slot = s;
        player.look(look);
        int br = c.takeBreaks(touchXY);
        for (int i = 0; i < br && i < 16; i++) {
            if (pickAt(touchXY[i * 2], touchXY[i * 2 + 1]) && world.get(pick.x, pick.y, pick.z) != 7) world.set(pick.x, pick.y, pick.z, 0);
        }
        int pl = c.takePlaces(touchXY);
        for (int i = 0; i < pl && i < 16; i++) {
            if (!pickAt(touchXY[i * 2], touchXY[i * 2 + 1])) continue;
            int nx = pick.nx(), ny = pick.ny(), nz = pick.nz();
            int id = hotbar[slot];
            int cur = world.get(nx, ny, nz);
            boolean intoPlayer = Blocks.solid[id] && nx >= Math.floor(player.x - Player.W / 2) && nx <= Math.floor(player.x + Player.W / 2)
                    && nz >= Math.floor(player.z - Player.W / 2) && nz <= Math.floor(player.z + Player.W / 2)
                    && ny >= Math.floor(player.y) && ny <= Math.floor(player.y + Player.H);
            if ((cur == 0 || Blocks.kind[cur] == Blocks.LIQUID) && !intoPlayer) world.set(nx, ny, nz, id, id == 86 ? pumpkinFacing() : 0);
        }
        hl = c.interactId >= 0 && pickAt(c.interactX, c.interactY);
        if (hl) { hlX = pick.x; hlY = pick.y; hlZ = pick.z; }
        ray.hit = hl; ray.x = hlX; ray.y = hlY; ray.z = hlZ;
    }

    // ------------------------------------------------------------------ selection (LevelRenderer::renderHitSelect)
    private final int[] hlVerts = new int[6 * 4 * 6];
    private static final int[][] CUBE = {
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0}, {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0}, {1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1}};

    private void drawHighlight() {
        int id = world.get(hlX, hlY, hlZ);
        if (id == 0) return;
        int data = world.data(hlX, hlY, hlZ), n = 0;
        int col = 166 | 166 << 8 | 166 << 16 | 255 << 24;             // shader colour 0.65
        boolean cross = Blocks.kind[id] == Blocks.CROSS;
        float hgt = Blocks.height[id];
        for (int f = 0; f < 6; f++) {
            int sl = Blocks.texFor(id, data, f) * 4;
            float[] uv = atlas.slotUv;
            float u0 = uv[sl], v0 = uv[sl + 1], u1 = uv[sl + 2], v1 = uv[sl + 3];
            float[] tu = f >= 2 ? new float[]{u0, u1, u1, u0} : new float[]{u0, u0, u1, u1};
            float[] tv = f >= 2 ? new float[]{v1, v1, v0, v0} : new float[]{v0, v1, v1, v0};
            for (int v = 0; v < 4; v++) {
                float px = CUBE[f][v * 3], py = CUBE[f][v * 3 + 1] * hgt, pz = CUBE[f][v * 3 + 2];
                if (cross) { px = 0.15f + px * 0.7f; pz = 0.15f + pz * 0.7f; }
                hlVerts[n++] = Float.floatToRawIntBits(px); hlVerts[n++] = Float.floatToRawIntBits(py); hlVerts[n++] = Float.floatToRawIntBits(pz);
                hlVerts[n++] = Float.floatToRawIntBits(tu[v]); hlVerts[n++] = Float.floatToRawIntBits(tv[v]);
                hlVerts[n++] = col;
            }
        }
        wr.drawOverlay(mvp, atlas.texture, hlVerts, n, hlX, hlY, hlZ);
    }

    /** pumpkin faces the player (data 0 south .. 3 east) */
    private int pumpkinFacing() {
        int d = (int) Math.floor((player.yaw * 4f / 360f) + 0.5f) & 3;
        return (d + 2) & 3;
    }

    // ------------------------------------------------------------------ chunk streaming
    private void stream() {
        // finished meshes -> GPU (bounded per frame)
        for (int i = 0; i < 8; i++) {
            Mesher.Mesh m = done.poll();
            if (m == null) break;
            wr.upload(m);
        }
        int pcx = (int) Math.floor(player.x) >> 4, pcz = (int) Math.floor(player.z) >> 4;
        // generation like the original: terrain (parallel) -> decoration when the 3x3 around has terrain (one thread,
        // it writes into neighbours) -> visible when the 3x3 around is decorated
        int queued = 0;
        for (int r = 0; r <= radius + 2 && queued < 4; r++) {
            for (int dz = -r; dz <= r && queued < 4; dz++) for (int dx = -r; dx <= r && queued < 4; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                final int cx = pcx + dx, cz = pcz + dz;
                final long k = World.key(cx, cz);
                if (world.chunks.containsKey(k) || generating.contains(k)) continue;
                generating.add(k);
                queued++;
                workers.execute(new Runnable() {
                    public void run() {
                        Chunk c = new Chunk(cx, cz);
                        world.gen.terrain(c);
                        world.addTerrain(c);
                        generating.remove(k);
                    }
                });
            }
        }
        int decoQueued = 0;
        for (int r = 0; r <= radius + 1 && decoQueued < 4; r++) {
            for (int dz = -r; dz <= r && decoQueued < 4; dz++) for (int dx = -r; dx <= r && decoQueued < 4; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                final int cx = pcx + dx, cz = pcz + dz;
                final Chunk c = world.chunk(cx, cz);
                if (c == null || c.stage != 1 || decorating.contains(World.key(cx, cz))) continue;
                boolean ok = true;
                for (int nz = -1; nz <= 1 && ok; nz++) for (int nx = -1; nx <= 1; nx++) if (world.chunk(cx + nx, cz + nz) == null) { ok = false; break; }
                if (!ok) continue;
                final long k = World.key(cx, cz);
                decorating.add(k);
                decoQueued++;
                decoThread.execute(new Runnable() {
                    public void run() {
                        try { deco.decorate(cx, cz); } catch (Throwable t) { android.util.Log.e("NOVA", "decorate", t); }
                        c.stage = 2;
                        decorating.remove(k);
                    }
                });
            }
        }
        for (int r = 0; r <= radius; r++) {
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int cx = pcx + dx, cz = pcz + dz;
                Chunk c = world.chunk(cx, cz);
                if (c == null || c.generated || c.stage != 2) continue;
                boolean ok = true;
                for (int nz = -1; nz <= 1 && ok; nz++) for (int nx = -1; nx <= 1; nx++) {
                    Chunk n = world.chunk(cx + nx, cz + nz);
                    if (n == null || n.stage != 2) { ok = false; break; }
                }
                if (ok) { for (int q = 0; q < Chunk.SECTIONS; q++) c.version[q]++; c.generated = true; }
            }
        }
        // (re)mesh sections whose version changed; neighbours must exist for faces and AO
        int meshQueued = 0;
        for (int r = 0; r <= radius && meshQueued < 6; r++) {
            for (int dz = -r; dz <= r && meshQueued < 6; dz++) for (int dx = -r; dx <= r && meshQueued < 6; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int cx = pcx + dx, cz = pcz + dz;
                final Chunk c = world.ready(cx, cz);
                if (c == null) continue;
                boolean ok = true;
                for (int nz = -1; nz <= 1 && ok; nz++) for (int nx = -1; nx <= 1; nx++) if (world.ready(cx + nx, cz + nz) == null) { ok = false; break; }
                if (!ok) continue;
                for (int s = 0; s < Chunk.SECTIONS; s++) {
                    final WorldRenderer.Section sec = wr.section(cx, s, cz);
                    final int ver = c.version[s];
                    if (sec.version == ver || sec.building) continue;
                    sec.building = true;
                    meshQueued++;
                    final int sy = s, fcx = cx, fcz = cz;
                    workers.execute(new Runnable() {
                        public void run() {
                            Mesher m = meshers.get();
                            if (m == null) { m = new Mesher(atlas.slotUv); meshers.set(m); }
                            Mesher.Mesh mesh = m.build(world, fcx, sy, fcz, ver);
                            if (mesh != null) done.add(mesh);
                            else sec.building = false;
                        }
                    });
                }
            }
        }
        // forget far chunks
        if ((frames & 63) == 0) {
            wr.unloadFar(pcx, pcz, radius);
            for (java.util.Iterator<Chunk> it = world.chunks.values().iterator(); it.hasNext(); ) {
                Chunk c = it.next();
                if (Math.abs(c.cx - pcx) > radius + 4 || Math.abs(c.cz - pcz) > radius + 4) it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ render
    private void render(float a) {
        boolean underwater = Blocks.kind[world.get((int) Math.floor(player.x), (int) Math.floor(player.ey(a)), (int) Math.floor(player.z))] == Blocks.LIQUID;
        // LevelRenderer::_setupFog: under water the fog goes from 0 to 10 blocks, colour = underwater colour * (0.35 + 0.65 * brightness)
        float[] fog = underwater ? new float[]{0.02f, 0.02f, 0.2f} : SKY;
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        float far = underwater ? 10 : radius * 16;
        Matrix.perspectiveM(proj, 0, 70, (float) width / height, 0.05f, radius * 16 + 64);
        Matrix.setIdentityM(view, 0);
        Matrix.rotateM(view, 0, player.pitch, 1, 0, 0);
        Matrix.rotateM(view, 0, player.yaw, 0, 1, 0);
        float ex = (float) player.ex(a), ey = (float) player.ey(a), ez = (float) player.ez(a);
        Matrix.translateM(view, 0, -ex, -ey, -ez);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        if (underwater) wr.draw(mvp, ex, ey, ez, look, atlas.texture, fog, 0f, 10f);
        else wr.draw(mvp, ex, ey, ez, look, atlas.texture, fog, far);
        if (hl) drawHighlight();
        drawHud();
    }

    private void drawHud() {
        Controls c = controls;
        hud.begin(width, height);
        float d = density;
        // crosshair
        // no crosshair in touch mode (0.14 picks under the finger)
        // hotbar: gui.png of 0.14 (bar 182x22 at 0,0; selection 24x24 at 0,22; a slot is 20 px)
        float u = c.slot / 20f;
        if (guiTex != 0) {
            float g = 1f / 256f;
            hud.image(guiTex, c.barX - u, c.barY - u, 182 * u, 22 * u, 0, 0, 182 * g, 22 * g, 0xFFFFFFFF);
        }
        for (int i = 0; i < 9; i++) {
            float x = c.barX + i * c.slot, y = c.barY;
            if (guiTex == 0) {
                hud.rect(x, y, c.slot, c.slot, i == slot ? 0xE0FFFFFF : 0x90000000);
                hud.rect(x + d * 2, y + d * 2, c.slot - d * 4, c.slot - d * 4, 0xC0303038);
            }
            int id = hotbar[i];
            int s = Blocks.tex[id][Blocks.kind[id] == Blocks.CROSS ? 2 : 1] * 4;
            float[] uv = atlas.slotUv;
            int tint = Blocks.tint[id][1];
            if (tint < 0) tint = tint == Blocks.TINT_FOLIAGE ? 0x59AE30 : tint == Blocks.TINT_WATER ? 0x3F76E4 : 0x79C05A;
            hud.image(atlas.texture, x + 2 * u, y + 2 * u, 16 * u, 16 * u, uv[s], uv[s + 1], uv[s + 2], uv[s + 3], 0xFF000000 | tint);
        }
        if (guiTex != 0) {
            float g = 1f / 256f;
            hud.image(guiTex, c.barX + slot * c.slot - 2 * u, c.barY - 2 * u, 24 * u, 24 * u, 0, 22 * g, 24 * g, 46 * g, 0xFFFFFFFF);
        }
        // touch controls of 0.14 (Controls): glyphs of gui.png, colours of ButtonColors x BUTTONS_TRANSPARENCY
        if (guiTex != 0) {
            glyph(c.up, 0, 107, c.pUp);
            glyph(c.left, 26, 107, c.pLeft);
            glyph(c.right, 78, 107, c.pRight);
            glyph(c.down, 52, 107, c.pDown);
            if (c.movingForward) { glyph(c.upLeft, 0, 133, c.pUpLeft); glyph(c.upRight, 26, 133, c.pUpRight); }
            glyph(c.sneakR, 104, 133, c.sneak);
            if (player.flying) { glyph(c.ascendR, 52, 133, c.ascend); glyph(c.descendR, 78, 133, c.descend); }
            else glyph(c.jumpR, 104, 107, c.jump);
        }
        // hold progress at the finger (HudProgressRenderer: ring + growing disc, ui_invert_overlay)
        if (c.interactId >= 0 && c.holdProgress > 0) progressRing(c.interactX, c.interactY, c.holdProgress);
        // info
        float ts = 8 * d;
        hud.text(VERSION, d * 6, d * 6, ts, 0xFF66F2E0);
        hud.text(fps + " fps  " + wr.drawnSections + " sec  " + world.chunks.size() + " ch", d * 6, d * 6 + ts * 1.3f, ts, 0xFFFFFFFF);
        hud.text(String.format(java.util.Locale.US, "%.1f %.1f %.1f", player.x, player.y, player.z), d * 6, d * 6 + ts * 2.6f, ts, 0xFFFFFFFF);
        hud.text("textures: " + atlas.source + "   biome: " + String.valueOf(com.nova.engine.world.Biomes.NAME[world.biome((int) Math.floor(player.x), (int) Math.floor(player.z))]), d * 6, d * 6 + ts * 3.9f, ts, 0xFFB0B0B0);
        if (ray.hit) hud.text(Blocks.name[world.get(ray.x, ray.y, ray.z)] + "", width / 2f + d * 12, height / 2f + d * 6, ts, 0xFFFFFFFF);
        if (error != null) hud.text(error, d * 6, height / 2f, ts, 0xFFFF6060);
        hud.end();
    }

    private void glyph(float[] r, int u, int v, boolean pressed) {
        float cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;
        float hw = (r[2] - r[0]) * Controls.GLYPH_SCALE / 2, hh = (r[3] - r[1]) * Controls.GLYPH_SCALE / 2;
        float g = 1f / 256f;
        int a = Math.round(Controls.ALPHA * 255), cc = pressed ? Math.round(Controls.PRESSED * 255) : 255;
        hud.image(guiTex, cx - hw, cy - hh, hw * 2, hh * 2, u * g, v * g, (u + 26) * g, (v + 26) * g, a << 24 | cc << 16 | cc << 8 | cc);
    }

    private void progressRing(float x, float y, float p) {
        float rOut = 7f * density * 3.5f / 2f, rIn = rOut * 0.6f * (0.5f + 0.5f * p);
        hud.invertCircle(x, y, rOut, rOut * 0.8f);
        hud.invertCircle(x, y, rIn, 0);
    }

    /** a gui.png icon (u,v,w,h in pixels) scaled into the button square; darker while pressed / active */
    private void guiButton(float x, float y, float s, int u, int v, int w, int h, boolean on) {
        float g = 1f / 256f, pad = s * 0.08f;
        hud.image(guiTex, x + pad, y + pad, s - 2 * pad, s - 2 * pad, u * g, v * g, (u + w) * g, (v + h) * g, on ? 0xFFB0B0B0 : 0xE0FFFFFF);
    }

    private void button(float x, float y, float s, String label, boolean on) {
        hud.rect(x, y, s, s, on ? 0x9066F2E0 : 0x60000000);
        float ts = s * 0.32f;
        hud.text(label, x + (s - hud.textWidth(label, ts)) / 2, y + (s - ts) / 2, ts, 0xFFFFFFFF);
    }
}
