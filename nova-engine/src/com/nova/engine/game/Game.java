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
    public static final String VERSION = "NOVA Engine 0.0.1 beta 1";

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
    private final java.util.Set<Long> generating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private int width = 1, height = 1;
    private float density = 3;
    private long lastNs, tickAcc;
    private int radius = 6;
    private int slot;
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
            if (world == null) {
                world = new World(System.currentTimeMillis());
                player.x = 0.5; player.z = 0.5; player.y = world.gen.height(0, 0) + 2;
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
    private void input() {
        Controls c = controls;
        c.poll();
        float sens = 0.25f / density * 1.6f;
        player.yaw += c.takeLookX() * sens;
        player.pitch = Math.max(-90, Math.min(90, player.pitch + c.takeLookY() * sens));
        player.moveF = c.joyY; player.moveS = c.joyX;
        player.jump = c.jump; player.sneak = c.down;
        if (c.takeFly() > 0) { player.flying = !player.flying; player.my = 0; }
        int s = c.takeSlot();
        if (s >= 0) slot = s;
        player.look(look);
        double ex = player.ex(1), ey = player.ey(1), ez = player.ez(1);
        ray.cast(world, ex, ey, ez, look[0], look[1], look[2], 6);
        int br = c.takeBreaks(), pl = c.takePlaces();
        if (ray.hit && br > 0 && world.get(ray.x, ray.y, ray.z) != 7) world.set(ray.x, ray.y, ray.z, 0);
        if (ray.hit && pl > 0) {
            int nx = ray.nx(), ny = ray.ny(), nz = ray.nz();
            int id = hotbar[slot];
            int cur = world.get(nx, ny, nz);
            boolean intoPlayer = Blocks.solid[id] && nx >= Math.floor(player.x - Player.W / 2) && nx <= Math.floor(player.x + Player.W / 2)
                    && nz >= Math.floor(player.z - Player.W / 2) && nz <= Math.floor(player.z + Player.W / 2)
                    && ny >= Math.floor(player.y) && ny <= Math.floor(player.y + Player.H);
            if ((cur == 0 || Blocks.kind[cur] == Blocks.LIQUID) && !intoPlayer) world.set(nx, ny, nz, id);
        }
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
        // generate missing chunks, nearest first (a few per frame)
        int queued = 0;
        for (int r = 0; r <= radius + 1 && queued < 4; r++) {
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
                        world.gen.generate(c);
                        world.chunks.put(k, c);
                        generating.remove(k);
                    }
                });
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
                if (Math.abs(c.cx - pcx) > radius + 3 || Math.abs(c.cz - pcz) > radius + 3) it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ render
    private void render(float a) {
        boolean underwater = Blocks.kind[world.get((int) Math.floor(player.x), (int) Math.floor(player.ey(a)), (int) Math.floor(player.z))] == Blocks.LIQUID;
        float[] fog = underwater ? new float[]{0.1f, 0.2f, 0.55f} : SKY;
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        float far = underwater ? 18 : radius * 16;
        Matrix.perspectiveM(proj, 0, 70, (float) width / height, 0.05f, radius * 16 + 64);
        Matrix.setIdentityM(view, 0);
        Matrix.rotateM(view, 0, player.pitch, 1, 0, 0);
        Matrix.rotateM(view, 0, player.yaw, 0, 1, 0);
        float ex = (float) player.ex(a), ey = (float) player.ey(a), ez = (float) player.ez(a);
        Matrix.translateM(view, 0, -ex, -ey, -ez);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        wr.draw(mvp, ex, ey, ez, look, atlas.texture, fog, far);
        drawHud();
    }

    private void drawHud() {
        Controls c = controls;
        hud.begin(width, height);
        float d = density;
        // crosshair
        hud.rect(width / 2f - d * 8, height / 2f - d, d * 16, d * 2, 0xC0FFFFFF);
        hud.rect(width / 2f - d, height / 2f - d * 8, d * 2, d * 16, 0xC0FFFFFF);
        // hotbar
        for (int i = 0; i < 9; i++) {
            float x = c.barX + i * c.slot, y = c.barY;
            hud.rect(x, y, c.slot, c.slot, i == slot ? 0xE0FFFFFF : 0x90000000);
            hud.rect(x + d * 2, y + d * 2, c.slot - d * 4, c.slot - d * 4, 0xC0303038);
            int id = hotbar[i];
            int s = Blocks.tex[id][Blocks.kind[id] == Blocks.CROSS ? 2 : 1] * 4;
            float[] uv = atlas.slotUv;
            int tint = Blocks.tint[id][1];
            hud.image(atlas.texture, x + d * 6, y + d * 6, c.slot - d * 12, c.slot - d * 12, uv[s], uv[s + 1], uv[s + 2], uv[s + 3], 0xFF000000 | tint);
        }
        // buttons
        button(c.jumpX, c.jumpY, c.btn, "^", c.jump);
        button(c.downX, c.downY, c.btn, player.flying ? "v" : "~", c.down);
        button(c.flyX, c.flyY, c.btn, player.flying ? "FLY" : "fly", player.flying);
        if (c.joyActive) {
            hud.rect(c.joyCX - c.joyR, c.joyCY - c.joyR, c.joyR * 2, c.joyR * 2, 0x30FFFFFF);
            hud.rect(c.joyCX + c.joyX * c.joyR - d * 18, c.joyCY - c.joyY * c.joyR - d * 18, d * 36, d * 36, 0x80FFFFFF);
        }
        // info
        float ts = 8 * d;
        hud.text(VERSION, d * 6, d * 6, ts, 0xFF66F2E0);
        hud.text(fps + " fps  " + wr.drawnSections + " sec  " + world.chunks.size() + " ch", d * 6, d * 6 + ts * 1.3f, ts, 0xFFFFFFFF);
        hud.text(String.format(java.util.Locale.US, "%.1f %.1f %.1f", player.x, player.y, player.z), d * 6, d * 6 + ts * 2.6f, ts, 0xFFFFFFFF);
        hud.text("textures: " + atlas.source, d * 6, d * 6 + ts * 3.9f, ts, 0xFFB0B0B0);
        if (ray.hit) hud.text(Blocks.name[world.get(ray.x, ray.y, ray.z)] + "", width / 2f + d * 12, height / 2f + d * 6, ts, 0xFFFFFFFF);
        if (error != null) hud.text(error, d * 6, height / 2f, ts, 0xFFFF6060);
        hud.end();
    }

    private void button(float x, float y, float s, String label, boolean on) {
        hud.rect(x, y, s, s, on ? 0x9066F2E0 : 0x60000000);
        float ts = s * 0.32f;
        hud.text(label, x + (s - hud.textWidth(label, ts)) / 2, y + (s - ts) / 2, ts, 0xFFFFFFFF);
    }
}
