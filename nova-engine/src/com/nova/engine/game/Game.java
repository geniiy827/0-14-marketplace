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
    public static final String VERSION = "NOVA Engine beta21e";

    private final Context ctx;
    public final Controls controls = new Controls();
    private final Atlas atlas = new Atlas();
    private final WorldRenderer wr = new WorldRenderer();
    private final Hud hud = new Hud();
    private volatile World world;
    /** the title screen is shown until Play */
    public volatile boolean inMenu = true;
    private final com.nova.engine.render.HandRenderer hand = new com.nova.engine.render.HandRenderer();
    private com.nova.engine.ui.StartMenu menu;
    private final java.util.ArrayList<float[]> menuEvents = new java.util.ArrayList<float[]>();

    /** UI thread: touches while the title screen is up */
    public void menuTouch(android.view.MotionEvent e) {
        int a = e.getActionMasked();
        int t = a == android.view.MotionEvent.ACTION_DOWN ? 0 : a == android.view.MotionEvent.ACTION_MOVE ? 1
                : a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL ? 2 : -1;
        if (t < 0) return;
        synchronized (menuEvents) { menuEvents.add(new float[]{t, e.getX(), e.getY()}); }
    }
    /** seed for the first world (set by the activity before the surface is created) */
    public volatile int startSeed = new java.util.Random().nextInt();
    private volatile boolean pendingWorld;
    private volatile int pendingSeed;

    /** any thread: replace the world by a new one with this seed (done on the GL thread) */
    public void newWorld(int seed) { pendingSeed = seed; pendingWorld = true; }
    /** structures within r chunks of the player: {type, x, z, distance} */
    public java.util.List<int[]> structures(int r) {
        World w = world;
        if (w == null) return new java.util.ArrayList<int[]>();
        return com.nova.engine.world.Finder.find(w, (int) Math.floor(player.x) >> 4, (int) Math.floor(player.z) >> 4, r);
    }
    /** any thread: move the player above x, z (lands on the top when the chunk is there) */
    public void teleport(int x, int z) { tpX = x; tpZ = z; tpPending = true; }
    private volatile boolean tpPending;
    private volatile int tpX, tpZ;
    public int seed() { World w = world; return w == null ? startSeed : (int) w.seed; }

    private void spawn() {
        int[] sp = world.gen.spawnPosition();                // BiomeSource::getSpawnPosition like the original
        int sx = sp[0], sz = sp[1];
        player.x = sx + 0.5; player.z = sz + 0.5; player.y = world.gen.height(sx, sz) + 2;
        player.px = player.x; player.py = player.y; player.pz = player.z;
        player.mx = player.my = player.mz = 0;
        settleSpawn = true;
    }
    private volatile boolean settleSpawn;

    private void replaceWorld() {
        pendingWorld = false;
        world = new World(pendingSeed);
        deco = new com.nova.engine.world.Decorator(world, world.seed);
        generating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        decorating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        done.clear();
        wr.clearAll();
        spawn();
    }
    private final Player player = new Player();
    private final Raycast ray = new Raycast();
    private ExecutorService workers;
    private final ConcurrentLinkedQueue<Mesher.Mesh> done = new ConcurrentLinkedQueue<Mesher.Mesh>();
    private final ThreadLocal<Mesher> meshers = new ThreadLocal<Mesher>();
    private volatile java.util.Set<Long> decorating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private final ExecutorService decoThread = Executors.newSingleThreadExecutor();
    /** light thread of its own: queued behind the decoration backlog it never got a turn */
    private final ExecutorService lightThread = Executors.newSingleThreadExecutor();
    private volatile com.nova.engine.world.Decorator deco;
    private volatile java.util.Set<Long> generating = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private int width = 1, height = 1;
    private float density = 3;
    private long lastNs, tickAcc;
    /** view radius in chunks (Options view distance 12 -> LocalPlayer::getViewBlockRadius 192) */
    private int radius = 12;
    private final java.util.concurrent.atomic.AtomicInteger lightBusy = new java.util.concurrent.atomic.AtomicInteger();
    private int slot, guiTex, iconsTex;
    private final int[] hotbar = CoreContent.HOTBAR.clone();
    private final float[] proj = new float[16], view = new float[16], mvp = new float[16], look = new float[3];
    private int fps, frames;
    private long fpsT;
    private String error;
    private volatile boolean ready;

    private long holdTarget = Long.MIN_VALUE;
    private final float xdpi;
    public Game(Context c) {
        ctx = c; density = c.getResources().getDisplayMetrics().density;
        float xd = c.getResources().getDisplayMetrics().xdpi;
        xdpi = xd > 50 ? xd : density * 160f;
    }

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
            wr.texW = atlas.width; wr.texH = atlas.height; wr.texLevels = atlas.mipLevels;
            sky.init(atlas.gameAssets);
            hand.init(atlas.gameAssets);
            hud.init(atlas.gameAssets);
            guiTex = hud.loadTex(atlas.gameAssets, "images/gui/gui.png");
            iconsTex = hud.loadTex(atlas.gameAssets, "images/gui/icons.png");
            if (inMenu && atlas.gameAssets != null) {
                android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                float inches = (float) Math.sqrt((double) dm.widthPixels * dm.widthPixels + (double) dm.heightPixels * dm.heightPixels) / xdpi;
                java.util.Locale lc = java.util.Locale.getDefault();
                menu = new com.nova.engine.ui.StartMenu(atlas.gameAssets, lc.getLanguage() + "_" + lc.getCountry(), inches);
            }
            if (world == null) {
                world = new World(startSeed);
                deco = new com.nova.engine.world.Decorator(world, world.seed);
                spawn();
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
        frameDt = dt / 1e9f;
        if (inMenu && menu != null) {
            String act = null;
            synchronized (menuEvents) {
                for (float[] e : menuEvents) { String r = menu.touch((int) e[0], e[1], e[2]); if (r != null) act = r; }
                menuEvents.clear();
            }
            if ("button.menu_play".equals(act)) inMenu = false;      // Play: straight into the world for now
            else {
                menu.render(hud, width, height, frameDt);
                stream();
                return;
            }
        }
        if (pendingWorld) replaceWorld();
        if (tpPending) {
            tpPending = false;
            player.x = player.px = tpX + 0.5; player.z = player.pz = tpZ + 0.5; player.y = player.py = 120;
            player.mx = player.my = player.mz = 0;
            player.flying = true;
            settleSpawn = true;
        }
        if (settleSpawn) {                                   // stand on top of what decoration/villages built
            int px = (int) Math.floor(player.x), pz = (int) Math.floor(player.z);
            Chunk sc = world.ready(px >> 4, pz >> 4);
            if (sc != null && sc.generated) {
                int y = Chunk.H - 1;
                while (y > 0 && world.get(px, y, pz) == 0) y--;
                player.y = player.py = y + 1;
                player.my = 0;
                settleSpawn = false;
            }
        }
        input();
        tickAcc += dt;
        while (tickAcc >= 50_000_000L) { player.tick(world); world.time++; levelTicks++; tickAcc -= 50_000_000L; }
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
        // MinecraftClient::tickBuildAction while destroy_or_interact is held: aimed at a block -> creative
        // GameMode::continueDestroyBlock (break, then again 250 ms later); aimed at nothing -> stopDestroyBlock (no-op
        // in creative) and the button stays held, so dragging over other blocks keeps breaking them
        if (c.destroying && c.interactId >= 0) {
            long now = System.currentTimeMillis();
            if (pickAt(c.interactX, c.interactY) && now >= nextDestroy && world.get(pick.x, pick.y, pick.z) != 7) {
                world.set(pick.x, pick.y, pick.z, 0);
                player.swing();
                nextDestroy = now + 250;
            }
        } else nextDestroy = 0;
        int pl = c.takePlaces(touchXY);
        for (int i = 0; i < pl && i < 16; i++) {
            if (!pickAt(touchXY[i * 2], touchXY[i * 2 + 1])) continue;
            // GameMode::useItemOn: not sneaking -> Block::use of the aimed block first; true = done (no placing)
            if (!player.sneak && useBlock(pick.x, pick.y, pick.z)) { player.swing(); continue; }
            int nx = pick.nx(), ny = pick.ny(), nz = pick.nz();
            int id = hotbar[slot];
            int cur = world.get(nx, ny, nz);
            boolean intoPlayer = Blocks.solid[id] && nx >= Math.floor(player.x - Player.W / 2) && nx <= Math.floor(player.x + Player.W / 2)
                    && nz >= Math.floor(player.z - Player.W / 2) && nz <= Math.floor(player.z + Player.W / 2)
                    && ny >= Math.floor(player.y) && ny <= Math.floor(player.y + Player.H);
            if ((cur == 0 || Blocks.kind[cur] == Blocks.LIQUID) && !intoPlayer) { world.set(nx, ny, nz, id, id == 86 ? pumpkinFacing() : 0); player.swing(); }
        }
        hl = c.interactId >= 0 && pickAt(c.interactX, c.interactY);
        if (hl) { hlX = pick.x; hlY = pick.y; hlZ = pick.z; }

        ray.hit = hl; ray.x = hlX; ray.y = hlY; ray.z = hlZ;
    }

    // ------------------------------------------------------------------ selection (LevelRenderer::renderHitSelect)
    private int[] hlVerts = new int[6 * 4 * 6 * 10];
    private static final int[][] CUBE = {
            {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1}, {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0},
            {1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0}, {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1},
            {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0}, {1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1}};

    private Mesher hlMesher;
    private final int[] hlOrigin = new int[3];

    /** LevelRenderer::renderHitSelect: the block's own geometry again with the selection material */
    private void drawHighlight() {
        if (world.get(hlX, hlY, hlZ) == 0) return;
        if (hlMesher == null) hlMesher = new Mesher(atlas.slotUv);
        int[] v = hlMesher.single(world, hlX, hlY, hlZ, hlOrigin);
        int col = 166 | 166 << 8 | 166 << 16 | 255 << 24;             // shader colour 0.65
        for (int i = 5; i < v.length; i += Mesher.STRIDE) v[i] = col;
        wr.drawOverlay(mvp, atlas.texture, v, v.length, hlOrigin[0], hlOrigin[1], hlOrigin[2]);
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
            if (m.world == world) wr.upload(m);
        }
        // the chunk scans below look up ~1000 columns several times (boxed keys): every 3rd frame is plenty
        if (frames % 3 != 0) return;
        int pcx = (int) Math.floor(player.x) >> 4, pcz = (int) Math.floor(player.z) >> 4;
        // generation like the original: terrain (parallel) -> decoration when the 3x3 around has terrain (one thread,
        // it writes into neighbours) -> visible when the 3x3 around is decorated
        int queued = 0;
        for (int r = 0; r <= radius + 3 && queued < 4; r++) {
            for (int dz = -r; dz <= r && queued < 4; dz++) for (int dx = -r; dx <= r && queued < 4; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                final int cx = pcx + dx, cz = pcz + dz;
                final long k = World.key(cx, cz);
                if (world.chunks.containsKey(k) || generating.contains(k)) continue;
                generating.add(k);
                queued++;
                final World wld = world;
                final java.util.Set<Long> gen = generating;
                workers.execute(new Runnable() {
                    public void run() {
                        try {
                            Chunk c = new Chunk(cx, cz);
                            wld.gen.terrain(c);
                            wld.addTerrain(c);
                        } catch (Throwable t) { android.util.Log.e("NOVA", "terrain", t); }
                        gen.remove(k);
                    }
                });
            }
        }
        int decoQueued = 0;
        for (int r = 0; r <= radius + 2 && decoQueued < 4; r++) {
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
                final com.nova.engine.world.Decorator dc = deco;
                final java.util.Set<Long> dset = decorating;
                decoThread.execute(new Runnable() {
                    public void run() {
                        try { dc.decorate(cx, cz); } catch (Throwable t) { android.util.Log.e("NOVA", "decorate", t); }
                        c.stage = 2;
                        dset.remove(k);
                    }
                });
            }
        }
        // light (LevelChunk::recalcHeightmap / updateLightsAndHeights): filled when the blocks are final (3x3
        // decorated), spread when the 3x3 is filled; visible when spread (light from chunks further out is at most
        // 15 blocks: a later spread only touches the border, the sections get rebuilt)
        if (lightBusy.get() == 0) {
            final java.util.ArrayList<Chunk> fill = new java.util.ArrayList<Chunk>(), spread = new java.util.ArrayList<Chunk>();
            for (int r = 0; r <= radius + 1 && fill.size() + spread.size() < 24; r++) {
                for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = pcx + dx, cz = pcz + dz;
                    Chunk c = world.chunk(cx, cz);
                    if (c == null || c.stage != 2 || c.lit == 2) continue;
                    boolean ok = true;
                    int need = c.lit == 0 ? 2 : 1;      // fill: 3x3 decorated; spread: 3x3 filled
                    for (int nz = -1; nz <= 1 && ok; nz++) for (int nx = -1; nx <= 1; nx++) {
                        Chunk n = world.chunk(cx + nx, cz + nz);
                        if (n == null || (need == 2 ? n.stage != 2 : n.lit < 1)) { ok = false; break; }
                    }
                    if (!ok) continue;
                    if (c.lit == 0) fill.add(c); else if (r <= radius) spread.add(c);
                }
            }
            final World wld = world;
            final boolean edits = !world.lightEdits.isEmpty();
            if (!fill.isEmpty() || !spread.isEmpty() || edits) {
                lightBusy.incrementAndGet();
                lightThread.execute(new Runnable() {
                    public void run() {
                        try {
                            for (Chunk c : fill) { com.nova.engine.world.Light.fill(c); c.lit = 1; }
                            for (Chunk c : spread) { com.nova.engine.world.Light.spread(wld, c); c.lit = 2; }
                            int[] e;
                            while ((e = wld.lightEdits.poll()) != null) com.nova.engine.world.Light.relight(wld, e[0], e[1], e[2]);
                        } catch (Throwable t) { android.util.Log.e("NOVA", "light", t); }
                        lightBusy.decrementAndGet();
                    }
                });
            }
        }
        for (int r = 0; r <= radius; r++) {
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int cx = pcx + dx, cz = pcz + dz;
                Chunk c = world.chunk(cx, cz);
                if (c == null || c.generated || c.lit != 2) continue;
                for (int q = 0; q < Chunk.SECTIONS; q++) c.version[q]++;
                c.generated = true;
            }
        }
        // (re)mesh sections whose version changed; neighbours must exist for faces and AO
        int meshQueued = 0;
        for (int r = 0; r <= radius && meshQueued < 12; r++) {
            for (int dz = -r; dz <= r && meshQueued < 12; dz++) for (int dx = -r; dx <= r && meshQueued < 12; dx++) {
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
                    final World wld = world;
                    workers.execute(new Runnable() {
                        public void run() {
                            Mesher m = meshers.get();
                            if (m == null) { m = new Mesher(atlas.slotUv); meshers.set(m); }
                            Mesher.Mesh mesh = null;
                            try { mesh = m.build(wld, fcx, sy, fcz, ver); } catch (Throwable t) { android.util.Log.e("NOVA", "mesh", t); }
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
    private final com.nova.engine.render.Sky sky = new com.nova.engine.render.Sky();
    private final float[] skyRot = new float[16], cloudProj = new float[16], fogDist = new float[4], sunCol = new float[4];
    private long levelTicks;
    private float sunGlare;

    private void render(float a) {
        boolean underwater = Blocks.kind[world.get((int) Math.floor(player.x), (int) Math.floor(player.ey(a)), (int) Math.floor(player.z))] == Blocks.LIQUID;
        float ex = (float) player.ex(a), ey = (float) player.ey(a), ez = (float) player.ez(a);
        long time = world.time;
        float tod = com.nova.engine.render.Sky.timeOfDay(time, a);
        // LevelRenderer::_setupFog: Dimension::getFogColor(fog brightness), blended toward the sunrise colour by
        // Dimension::getSunIntensity(view) * sunrise.a; under water the biome water colour * (0.35 + 0.65 b), 0..10 blocks
        float fb = com.nova.engine.render.Sky.dayBrightness(tod);
        float[] fog = new float[3];
        if (underwater) {
            // Biome::DEFAULT_UNDERWATER_COLOR (0.0196, 0.0196, 0.196) * (0.35 + 0.65 fog brightness)
            float k = 0.35f + fb * 0.65f;
            fog[0] = 0.019607844f * k; fog[1] = 0.019607844f * k; fog[2] = 0.19607845f * k;
        } else {
            com.nova.engine.render.Sky.fogColor(fb, fog);
            com.nova.engine.render.Sky.sunrise(tod, sunCol);
            float si = com.nova.engine.render.Sky.sunIntensity(tod, look, 0.35f) * sunCol[3];
            for (int i = 0; i < 3; i++) fog[i] = fog[i] * (1 - si) + sunCol[i] * si;
            // LevelRenderer::startFrame: +0x1c9c = getSunIntensity(a, camera, 0.9); _skyDarkeningFactor = it * 0.2 is
            // taken off the fog, sky and cloud colours (looking into the sun darkens the view)
            sunGlare = com.nova.engine.render.Sky.sunIntensity(tod, look, 0.9f);
            for (int i = 0; i < 3; i++) fog[i] = Math.max(0f, fog[i] - sunGlare * 0.2f);
        }
        if (underwater) sunGlare = 0;
        float amb = com.nova.engine.render.Sky.ambient(ey, world.lightPair((int) Math.floor(player.x), (int) Math.floor(ey), (int) Math.floor(player.z)) >> 8);
        com.nova.engine.render.Sky.fogDistances(radius * 16, amb, underwater, fogDist);
        sky.updateLight(tod);
        wr.lightTex = sky.lightTexture();
        wr.farChunks = fogDist[3];
        GLES30.glClearColor(fog[0], fog[1], fog[2], 1);                // LevelRenderer::setupClearColor = the fog colour
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_BLEND);
        Matrix.perspectiveM(proj, 0, 70, (float) width / height, 0.05f, Math.max(radius * 16 + 64, 600));   // far enough for the clouds
        Matrix.setIdentityM(view, 0);
        com.nova.engine.render.HandRenderer.bobView(view, player, a);
        Matrix.rotateM(view, 0, player.pitch, 1, 0, 0);
        Matrix.rotateM(view, 0, player.yaw, 0, 1, 0);
        System.arraycopy(view, 0, skyRot, 0, 16);
        Matrix.perspectiveM(cloudProj, 0, 70, (float) width / height, 0.5f, 5000f);
        if (!underwater) {
            int bx = (int) Math.floor(player.x), bz = (int) Math.floor(player.z);
            sky.renderSky(cloudProj, skyRot, time, a, com.nova.engine.world.Biomes.TEMP[world.biome(bx, bz)], amb, fog, sunGlare);
        }
        Matrix.translateM(view, 0, -ex, -ey, -ez);
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0);
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glDepthFunc(GLES30.GL_LEQUAL);
        GLES30.glEnable(GLES30.GL_CULL_FACE);
        float fogStart = underwater ? 0f : fogDist[1], fogEnd = underwater ? 10f : fogDist[2];
        wr.draw(mvp, ex, ey, ez, look, atlas.texture, fog, fogStart, fogEnd);
        if (!underwater) sky.renderClouds(proj, skyRot, ex, ey, ez, levelTicks, a, time, fogDist[0], sunGlare);
        if (hl) drawHighlight();
        sky.lightColor(world.lightPair((int) Math.floor(player.x), (int) Math.floor(ey), (int) Math.floor(player.z)), hand.tile);
        hand.render(player, a, width, height);                 // InGamePlayScreen::_renderItemInHand
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
        progressRing(c);
        // info
        float ts = 8 * d;
        button(controls.menuR[0], controls.menuR[1], controls.menuR[2] - controls.menuR[0], "| |", false);
        hud.text(VERSION + "   seed " + seed(), d * 6, d * 6, ts, 0xFF66F2E0);
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

    /**
     * HudProgressRenderer: _buildFeedbackCircle: R = min(4 mm in pixels, 40), 24 segments; outer ring R..0.95R,
     * inner disc 0.95R scaled by 0.5 + 0.5 * progress; drawn at the finger scaled by 3.5 (gui units -> pixels)
     * with ui_invert_overlay.
     */
    /** Block::use overrides of 0.14.3 for the blocks we have; returns whether the tap was consumed */
    private boolean useBlock(int x, int y, int z) {
        int id = world.get(x, y, z);
        switch (id) {
            case 64: {                                    // DoorBlock::use -> setOpen(!open): lower half data ^ 4
                int ly = (world.data(x, y, z) & 8) != 0 ? y - 1 : y;
                if (world.get(x, ly, z) == 64) world.set(x, ly, z, 64, world.data(x, ly, z) ^ 4);
                if (world.get(x, ly + 1, z) == 64) world.set(x, ly + 1, z, 64, world.data(x, ly + 1, z));   // remesh the upper half
                return true;
            }
            case 96:                                      // TrapDoorBlock::use -> setOpen: data ^ 8
                world.set(x, y, z, 96, world.data(x, y, z) ^ 8);
                return true;
            case 54: case 58: case 61:                    // chest / workbench / furnace open their screen (none yet)
                return true;
            default:
                return false;
        }
    }

    private void progressRing(Controls c) {
        // _renderProgressIndicator: an alpha follows button.turn_interact (GuiData::calcNewAlpha +0.02 / -0.04 a frame) and
        // the ring is drawn while it is above 0, at the pointer. The material is ui_invert_overlay = flat_white.fragment
        // (always vec4(1)) with OneMinusDestColor / OneMinusSrcColor blending, so the ShaderColor alpha (x0.8 / x0.4)
        // has no visible effect: the ring is always a full inversion. Destroy progress > 0 (survival only, creative
        // keeps 0) adds the disc scaled 0.5 + 0.5 * progress.
        if (c.interactId >= 0) { ringX = c.interactX; ringY = c.interactY; }
        float steps = Math.min(4f, frameDt * 60f);
        ringAlpha = c.showProgress ? Math.min(1f, ringAlpha + 0.02f * steps) : Math.max(0f, ringAlpha - 0.04f * steps);
        float p = 0f;                                                       // GameMode::getDestroyProgress (creative)
        float ppmDevice = xdpi / 25.4f;                                     // AppPlatform pixels per millimetre
        float R = Math.min(ppmDevice * 3.9996f, 40f) * 3.5f;                // mesh radius, drawn scaled 3.5 (screen px)
        if (p > 0f || ringAlpha > 0f) hud.invertCircle(ringX, ringY, R, R * 0.95f, 24);
        if (p > 0f) hud.invertCircle(ringX, ringY, R * 0.95f * (0.5f + 0.5f * p), 0, 24);
    }
    private float ringX, ringY, ringAlpha, frameDt;
    private long nextDestroy;

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
