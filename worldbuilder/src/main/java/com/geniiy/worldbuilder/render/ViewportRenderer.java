package com.geniiy.worldbuilder.render;

import com.geniiy.worldbuilder.WorldBuilderMod;
import com.geniiy.worldbuilder.blocks.JavaStates;
import com.geniiy.worldbuilder.nbt.Tag;
import com.geniiy.worldbuilder.pe.PeWorld;
import com.geniiy.worldbuilder.util.Pos;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.block.*;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.fluid.FluidState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.random.Random;
import org.joml.*;
import org.lwjgl.opengl.GL11;

import java.lang.Math;
import java.util.*;
import java.util.concurrent.*;

/**
 * Рендер мира PE во вьюпорте: меши секций 16³ строятся в фоне BlockRenderManager'ом игры в BufferBuilder
 * по слоям и загружаются в VertexBuffer (подход Litematica). Интегрированный сервер не используется.
 */
public final class ViewportRenderer {
    static final RenderLayer[] LAYERS = {RenderLayer.getSolid(), RenderLayer.getCutoutMipped(), RenderLayer.getCutout(), RenderLayer.getTranslucent()};

    final MinecraftClient mc = MinecraftClient.getInstance();
    public final PeWorld world;
    public final SectionView.Options opt = new SectionView.Options();
    private final ConcurrentHashMap<Long, Integer> versions = new ConcurrentHashMap<>();
    private final HashMap<Long, Mesh> meshes = new HashMap<>();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();
    private final ExecutorService pool;
    private final BlockingQueue<BufferSet> buffers = new LinkedBlockingQueue<>();
    private final int threads;
    private int inFlight;
    private NativeImageBackedTexture lightmap;
    private int lightMode = -1;
    public int renderDistance = 12;
    public boolean fog = true;
    public int skyTop = 0xFF6FA8DC, skyBottom = 0xFFBFD9F2;
    public int lastDrawCalls, queued;
    private final HashMap<Long, BlockEntity> beCache = new HashMap<>();
    private int globalVersion;

    // матрицы кадра
    public Matrix4f proj = new Matrix4f(), view = new Matrix4f();
    public double camX, camY, camZ;
    public int vpX, vpY, vpW, vpH; // физические пиксели (y — сверху)

    static final class Mesh {
        int builtVersion = -1, requested = -1;
        final VertexBuffer[] vb = new VertexBuffer[4];
        List<BeInfo> bes = List.of();
        boolean building;
        boolean hasAny;
    }
    record BeInfo(BlockPos pos, BlockState state) {}
    static final class BufferSet { final BufferBuilder[] b = new BufferBuilder[4]; BufferSet() { for (int i = 0; i < 4; i++) b[i] = new BufferBuilder(i == 0 ? 262144 : 65536); } }
    record Result(long key, int version, BufferSet set, BufferBuilder.BuiltBuffer[] built, List<BeInfo> bes) {}

    public ViewportRenderer(PeWorld world, int threadsCfg) {
        this.world = world;
        int n = threadsCfg > 0 ? threadsCfg : Math.max(1, java.lang.Runtime.getRuntime().availableProcessors() - 2);
        threads = Math.min(8, n);
        pool = Executors.newFixedThreadPool(threads, r -> { Thread t = new Thread(r, "WorldBuilder-Mesher"); t.setDaemon(true); t.setPriority(Thread.NORM_PRIORITY - 1); return t; });
        for (int i = 0; i < threads * 2; i++) buffers.add(new BufferSet());
        world.listener = (cx, sy, cz) -> versions.merge(key(cx, sy, cz), 1, Integer::sum);
    }

    public static long key(int cx, int sy, int cz) { return (Pos.chunkKey(cx, cz) << 3) ^ sy; }
    private static long key2(int cx, int sy, int cz) { return ((long) (cx & 0x1FFFFFF) << 28) | ((long) (cz & 0x1FFFFFF) << 3) | sy; }

    /** Пометить все секции для перестройки (смена режима света, невидимых блоков, среза). */
    public void invalidateAll() { globalVersion++; }

    public void close() {
        pool.shutdownNow();
        for (Mesh m : meshes.values()) for (VertexBuffer v : m.vb) if (v != null) v.close();
        meshes.clear();
        Result r;
        while ((r = results.poll()) != null) release(r);
        if (lightmap != null) lightmap.close();
        world.listener = null;
    }

    private int ver(int cx, int sy, int cz) { return versions.getOrDefault(key(cx, sy, cz), 0) + globalVersion * 1000003; }

    // ---------- лайтмап ----------
    private void updateLightmap(int mode) {
        if (lightmap == null) { lightmap = new NativeImageBackedTexture(16, 16, false); }
        if (mode == lightMode) return;
        lightMode = mode;
        NativeImage img = lightmap.getImage();
        for (int s = 0; s < 16; s++) for (int b = 0; b < 16; b++) {
            float r, g, bl;
            if (mode == 0) { r = g = bl = 1f; }
            else {
                float skyF = mode == 1 ? 1f : 0.12f;
                float sk = brightness(s) * skyF, bk = brightness(b);
                float br = Math.max(sk, bk);
                r = Math.min(1f, sk * (mode == 2 ? 0.75f : 1f) + bk * 1.0f);
                g = Math.min(1f, sk * (mode == 2 ? 0.8f : 1f) + bk * 0.85f);
                bl = Math.min(1f, sk + bk * 0.7f);
                float amb = 0.06f;
                r = Math.max(r, amb); g = Math.max(g, amb); bl = Math.max(bl, amb * 1.2f);
                if (br < 0) r = 0;
            }
            int c = 0xFF000000 | ((int) (bl * 255) << 16) | ((int) (g * 255) << 8) | (int) (r * 255); // ABGR
            img.setColor(b, s, c);
        }
        lightmap.upload();
        lightmap.setFilter(true, false);
    }
    private static float brightness(int level) { float f = level / 15f; return f / (4f - 3f * f); }

    // ---------- кадр ----------
    public void setCamera(double x, double y, double z, float yaw, float pitch, double fov, boolean ortho, double orthoScale, int vx, int vy, int vw, int vh) {
        camX = x; camY = y; camZ = z;
        vpX = vx; vpY = vy; vpW = Math.max(1, vw); vpH = Math.max(1, vh);
        float aspect = vpW / (float) vpH;
        float far = Math.max(256f, renderDistance * 16f * 1.8f);
        if (ortho) {
            float hh = (float) orthoScale, hw = hh * aspect;
            proj = new Matrix4f().ortho(-hw, hw, -hh, hh, -4000f, 4000f);
        } else proj = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05f, far);
        view = new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw + 180f));
    }

    /** Луч из камеры через точку вьюпорта (в долях 0..1 от левого верхнего угла). */
    public double[] ray(double fx, double fy) {
        float nx = (float) (fx * 2 - 1), ny = (float) (1 - fy * 2);
        Matrix4f inv = new Matrix4f(proj).mul(view).invert();
        Vector4f a = inv.transform(new Vector4f(nx, ny, -1, 1)); a.div(a.w);
        Vector4f b = inv.transform(new Vector4f(nx, ny, 1, 1)); b.div(b.w);
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[]{camX + a.x, camY + a.y, camZ + a.z, dx / len, dy / len, dz / len};
    }

    /** Проекция мировой точки в доли вьюпорта (null — за камерой). */
    public double[] project(double x, double y, double z) {
        Vector4f v = new Matrix4f(proj).mul(view).transform(new Vector4f((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1));
        if (v.w <= 0.0001f) return null;
        return new double[]{(v.x / v.w + 1) / 2, (1 - v.y / v.w) / 2, v.z / v.w};
    }

    public void render(float tickDelta, int lightModeCfg, Runnable overlays) {
        int fbH = mc.getWindow().getFramebufferHeight(), fbW = mc.getWindow().getFramebufferWidth();
        int glY = fbH - vpY - vpH;
        Matrix4f oldProj = RenderSystem.getProjectionMatrix();
        VertexSorter oldSort = RenderSystem.getVertexSorting();
        float fs = RenderSystem.getShaderFogStart(), fe = RenderSystem.getShaderFogEnd();
        float[] fc = RenderSystem.getShaderFogColor().clone();
        MatrixStack mvs = RenderSystem.getModelViewStack();
        mvs.push();
        mvs.loadIdentity();
        RenderSystem.applyModelViewMatrix();
        try {
            RenderSystem.enableScissor(vpX, glY, vpW, vpH);
            RenderSystem.viewport(vpX, glY, vpW, vpH);
            RenderSystem.clearColor(((skyBottom >> 16) & 255) / 255f, ((skyBottom >> 8) & 255) / 255f, (skyBottom & 255) / 255f, 1f);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
            drawSky();
            RenderSystem.setProjectionMatrix(proj, VertexSorter.BY_DISTANCE);
            boolean full = lightModeCfg == 0;
            if (opt.fullBright != full) { opt.fullBright = full; invalidateAll(); }
            updateLightmap(lightModeCfg);
            float dist = renderDistance * 16f;
            int sb = skyBottom;
            if (fog) { RenderSystem.setShaderFogStart(dist * 0.75f); RenderSystem.setShaderFogEnd(dist); RenderSystem.setShaderFogColor(((sb >> 16) & 255) / 255f, ((sb >> 8) & 255) / 255f, (sb & 255) / 255f); }
            else { RenderSystem.setShaderFogStart(Float.MAX_VALUE); RenderSystem.setShaderFogEnd(Float.MAX_VALUE); }
            RenderSystem.setShaderFogShape(FogShape.CYLINDER);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            processResults();
            scheduleAndDraw();
            drawBlockEntities(tickDelta);
            // оверлеи — в мировых координатах относительно камеры
            mvs.push();
            mvs.multiplyPositionMatrix(view);
            mvs.translate(-camX, -camY, -camZ);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE); RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            try { overlays.run(); } catch (Throwable t) { WorldBuilderMod.LOG.error("[WorldBuilder] Ошибка рисования оверлея", t); }
            mvs.pop();
            RenderSystem.applyModelViewMatrix();
        } finally {
            mvs.pop();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProj, oldSort);
            RenderSystem.setShaderFogStart(fs); RenderSystem.setShaderFogEnd(fe); RenderSystem.setShaderFogColor(fc[0], fc[1], fc[2], fc[3]);
            RenderSystem.disableScissor();
            RenderSystem.viewport(0, 0, fbW, fbH);
            RenderSystem.enableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
        }
    }

    private void drawSky() {
        RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorter.BY_Z);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder b = Tessellator.getInstance().getBuffer();
        b.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        // градиент зависит от наклона камеры (горизонт)
        b.vertex(-1, -1, 0).color(skyBottom).next();
        b.vertex(1, -1, 0).color(skyBottom).next();
        b.vertex(1, 1, 0).color(skyTop).next();
        b.vertex(-1, 1, 0).color(skyTop).next();
        BufferRenderer.drawWithGlobalProgram(b.end());
        RenderSystem.enableDepthTest();
    }

    private void processResults() {
        long t0 = System.nanoTime();
        Result r;
        while ((r = results.poll()) != null) {
            inFlight--;
            Mesh m = meshes.get(r.key);
            if (m == null) { release(r); continue; }
            m.building = false;
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                if (r.built[i] == null) { if (m.vb[i] != null) { m.vb[i].close(); m.vb[i] = null; } continue; }
                if (m.vb[i] == null) m.vb[i] = new VertexBuffer(VertexBuffer.Usage.STATIC);
                m.vb[i].bind();
                m.vb[i].upload(r.built[i]);
                any = true;
            }
            VertexBuffer.unbind();
            m.hasAny = any;
            m.builtVersion = r.version;
            m.bes = r.bes;
            buffers.add(r.set);
            if (System.nanoTime() - t0 > 6_000_000) break;
        }
    }

    private void release(Result r) {
        for (BufferBuilder.BuiltBuffer b : r.built) if (b != null) b.release();
        buffers.add(r.set);
    }

    private void scheduleAndDraw() {
        int ccx = (int) Math.floor(camX) >> 4, ccz = (int) Math.floor(camZ) >> 4;
        int rd = renderDistance;
        FrustumIntersection fr = new FrustumIntersection(new Matrix4f(proj).mul(view));
        record Vis(long key, int cx, int sy, int cz, double d) {}
        List<Vis> vis = new ArrayList<>();
        List<Vis> need = new ArrayList<>();
        Set<Long> keep = new HashSet<>();
        for (int cx = ccx - rd; cx <= ccx + rd; cx++) for (int cz = ccz - rd; cz <= ccz + rd; cz++) {
            int dxc = cx - ccx, dzc = cz - ccz;
            if (dxc * dxc + dzc * dzc > (rd + 1) * (rd + 1)) continue;
            if (!world.chunkExists(cx, cz)) continue;
            for (int sy = 0; sy < 8; sy++) {
                long k = key(cx, sy, cz);
                keep.add(k);
                float minx = (float) (cx * 16 - camX), miny = (float) (sy * 16 - camY), minz = (float) (cz * 16 - camZ);
                boolean inFr = fr.testAab(minx, miny, minz, minx + 16, miny + 16, minz + 16);
                double ddx = cx * 16 + 8 - camX, ddy = sy * 16 + 8 - camY, ddz = cz * 16 + 8 - camZ;
                double d = ddx * ddx + ddy * ddy + ddz * ddz;
                Mesh m = meshes.computeIfAbsent(k, kk -> new Mesh());
                int v = ver(cx, sy, cz);
                if (m.builtVersion != v && !m.building) need.add(new Vis(k, cx, sy, cz, inFr ? d : d * 4 + 1e6));
                if (inFr && m.hasAny) vis.add(new Vis(k, cx, sy, cz, d));
            }
        }
        // выгрузка далёких мешей
        if (meshes.size() > keep.size() + 256) {
            for (Iterator<Map.Entry<Long, Mesh>> it = meshes.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (!keep.contains(e.getKey()) && !e.getValue().building) { for (VertexBuffer v : e.getValue().vb) if (v != null) v.close(); it.remove(); }
            }
            world.evictFar(ccx, ccz, rd + 4, (int) Math.max(2000, (long) com.geniiy.worldbuilder.config.WbConfig.get().cacheBudgetMb * 1024 / 120));
        }
        need.sort(Comparator.comparingDouble(Vis::d));
        queued = need.size();
        int cap = threads * 3;
        for (Vis n : need) {
            if (inFlight >= cap) break;
            BufferSet set = buffers.poll();
            if (set == null) break;
            Mesh m = meshes.get(n.key);
            m.building = true;
            int v = ver(n.cx, n.sy, n.cz);
            inFlight++;
            float relX = (float) (camX - n.cx * 16), relY = (float) (camY - n.sy * 16), relZ = (float) (camZ - n.cz * 16);
            pool.submit(() -> build(n.key, n.cx, n.sy, n.cz, v, set, relX, relY, relZ));
        }
        // отрисовка
        vis.sort(Comparator.comparingDouble(Vis::d));
        var atlas = mc.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
        int calls = 0;
        for (int li = 0; li < 4; li++) {
            boolean translucent = li == 3;
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.enableCull();
            if (translucent) { RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); } else RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            RenderSystem.setShader(li == 0 ? GameRenderer::getRenderTypeSolidProgram : li == 1 ? GameRenderer::getRenderTypeCutoutMippedProgram : li == 2 ? GameRenderer::getRenderTypeCutoutProgram : GameRenderer::getRenderTypeTranslucentProgram);
            atlas.setFilter(false, li != 2);
            RenderSystem.setShaderTexture(0, SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
            RenderSystem.setShaderTexture(2, lightmap.getGlId());
            ShaderProgram sh = RenderSystem.getShader();
            if (sh == null) continue;
            if (sh.chunkOffset != null) sh.chunkOffset.set(0f, 0f, 0f);
            List<Vis> order = vis;
            if (translucent) { order = new ArrayList<>(vis); Collections.reverse(order); }
            for (Vis s : order) {
                Mesh m = meshes.get(s.key);
                if (m == null || m.vb[li] == null) continue;
                Matrix4f mv = new Matrix4f(view).translate((float) (s.cx * 16 - camX), (float) (s.sy * 16 - camY), (float) (s.cz * 16 - camZ));
                m.vb[li].bind();
                m.vb[li].draw(mv, proj, sh);
                calls++;
            }
            VertexBuffer.unbind();
        }
        RenderSystem.disableBlend();
        RenderSystem.disableCull();
        lastDrawCalls = calls;
    }

    private void build(long key, int cx, int sy, int cz, int version, BufferSet set, float relX, float relY, float relZ) {
        BufferBuilder.BuiltBuffer[] built = new BufferBuilder.BuiltBuffer[4];
        List<BeInfo> bes = new ArrayList<>();
        try {
            SectionView v = SectionView.capture(world, cx, sy, cz, opt);
            if (!v.empty) {
                BlockRenderManager brm = mc.getBlockRenderManager();
                for (BufferBuilder b : set.b) b.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL);
                MatrixStack ms = new MatrixStack();
                Random rnd = Random.create();
                BlockPos.Mutable pos = new BlockPos.Mutable();
                for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    BlockState st = v.local(x, y, z);
                    if (st.isAir()) continue;
                    pos.set(v.ox + x, v.oy + y, v.oz + z);
                    try {
                        FluidState fs = st.getFluidState();
                        if (!fs.isEmpty()) {
                            RenderLayer fl = RenderLayers.getFluidLayer(fs);
                            brm.renderFluid(pos, v, set.b[layerIndex(fl)], st, fs);
                        }
                        if (st.getRenderType() == BlockRenderType.MODEL) {
                            RenderLayer bl = RenderLayers.getBlockLayer(st);
                            ms.push();
                            ms.translate(x, y, z);
                            brm.renderBlock(st, pos, v, ms, set.b[layerIndex(bl)], true, rnd);
                            ms.pop();
                        } else if (st.getRenderType() == BlockRenderType.ENTITYBLOCK_ANIMATED || st.getBlock() instanceof BlockEntityProvider) {
                            if (st.getBlock() instanceof ChestBlock || st.getBlock() instanceof BedBlock || st.getBlock() instanceof AbstractSignBlock || st.getBlock() instanceof AbstractSkullBlock)
                                bes.add(new BeInfo(pos.toImmutable(), st));
                        }
                    } catch (Throwable t) { /* один сломанный блок не должен ломать секцию */ }
                }
                for (int i = 0; i < 4; i++) {
                    if (i == 3) set.b[i].setSorter(VertexSorter.byDistance(relX, relY, relZ));
                    built[i] = set.b[i].endNullable();
                }
            }
        } catch (Throwable t) {
            WorldBuilderMod.LOG.error("[WorldBuilder] Ошибка построения меша секции {} {} {}", cx, sy, cz, t);
            for (int i = 0; i < 4; i++) { if (built[i] == null && set.b[i].isBuilding()) { try { BufferBuilder.BuiltBuffer bb = set.b[i].endNullable(); if (bb != null) bb.release(); } catch (Throwable ignored) {} } }
        }
        results.add(new Result(key, version, set, built, bes));
    }

    static int layerIndex(RenderLayer l) {
        if (l == RenderLayer.getSolid()) return 0;
        if (l == RenderLayer.getCutoutMipped()) return 1;
        if (l == RenderLayer.getCutout()) return 2;
        return 3;
    }

    // ---------- блоки с BlockEntityRenderer ----------
    private void drawBlockEntities(float tickDelta) {
        VertexConsumerProvider.Immediate imm = mc.getBufferBuilders().getEntityVertexConsumers();
        MatrixStack ms = new MatrixStack();
        ms.multiplyPositionMatrix(view);
        int max = 0;
        double lim = Math.min(96, renderDistance * 16.0);
        for (var e : meshes.entrySet()) {
            Mesh m = e.getValue();
            for (BeInfo bi : m.bes) {
                BlockPos p = bi.pos;
                double dx = p.getX() + 0.5 - camX, dy = p.getY() + 0.5 - camY, dz = p.getZ() + 0.5 - camZ;
                if (dx * dx + dy * dy + dz * dz > lim * lim) continue;
                if (++max > 2000) break;
                try { renderBe(bi, ms, imm, tickDelta); } catch (Throwable t) { /* пропустить */ }
            }
        }
        imm.draw();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void renderBe(BeInfo bi, MatrixStack ms, VertexConsumerProvider.Immediate imm, float td) {
        BlockState st = bi.state;
        BlockPos p = bi.pos;
        long k = Pos.pack(p.getX(), p.getY(), p.getZ());
        BlockEntity be = beCache.get(k);
        if (be == null || be.getCachedState() != st) {
            be = ((BlockEntityProvider) st.getBlock()).createBlockEntity(p, st);
            if (be == null) return;
            if (be instanceof SignBlockEntity sb) {
                Tag.CompoundTag t = world.getTile(p.getX(), p.getY(), p.getZ());
                if (t != null) {
                    NbtCompound n = new NbtCompound();
                    NbtCompound front = new NbtCompound();
                    NbtList msgs = new NbtList();
                    for (int i = 1; i <= 4; i++) msgs.add(NbtString.of(Text.Serializer.toJson(Text.literal(t.getString("Text" + i, "")))));
                    front.put("messages", msgs);
                    n.put("front_text", front);
                    sb.readNbt(n);
                }
            }
            beCache.put(k, be);
            if (beCache.size() > 8000) beCache.clear();
        }
        BlockEntityRenderer r = mc.getBlockEntityRenderDispatcher().get(be);
        if (r == null) return;
        ms.push();
        ms.translate(p.getX() - camX, p.getY() - camY, p.getZ() - camZ);
        float rot = 0;
        if (st.getBlock() instanceof ChestBlock && st.contains(ChestBlock.FACING)) rot = st.get(ChestBlock.FACING).asRotation();
        if (st.getBlock() instanceof BedBlock) {
            if (st.get(BedBlock.PART) != net.minecraft.block.enums.BedPart.HEAD) { ms.pop(); return; }
            rot = st.get(BedBlock.FACING).asRotation();
        }
        if (rot != 0) { ms.translate(0.5, 0.5, 0.5); ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-rot)); ms.translate(-0.5, -0.5, -0.5); }
        int light = opt.fullBright ? LightmapTextureManager.MAX_LIGHT_COORDINATE : LightmapTextureManager.pack(lightAt(p, false), lightAt(p, true));
        r.render(be, td, ms, imm, light, OverlayTexture.DEFAULT_UV);
        ms.pop();
    }

    private int lightAt(BlockPos p, boolean sky) {
        var c = world.getChunk(p.getX() >> 4, p.getZ() >> 4);
        if (c == null || p.getY() < 0 || p.getY() > 127) return sky ? 15 : 0;
        return sky ? c.getSky(p.getX() & 15, p.getY(), p.getZ() & 15) : c.getBlockLight(p.getX() & 15, p.getY(), p.getZ() & 15);
    }

    public int meshCount() { return meshes.size(); }
}
