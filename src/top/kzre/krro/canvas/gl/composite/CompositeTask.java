package top.kzre.krro.canvas.gl.composite;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLQuad;
import top.kzre.krro.canvas.gl.tile.*;
import top.kzre.krro.util.tile.Tile;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.Callable;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.GL_TEXTURE_BUFFER;
import static org.lwjgl.opengl.GL31.glTexBuffer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * 合成任务。在 GL 线程上执行一次 {@link CompositeRequest}，返回目标 FBO。
 *
 * <p><b>线程契约</b>：{@code call} 必须在 GL 线程上执行。
 */
public final class CompositeTask implements Callable<GLFramebuffer> {

    private final CompositeRequest request;

    public CompositeTask(CompositeRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        this.request = request;
    }

    @Override
    public GLFramebuffer call() throws Exception {
        long tCall = System.nanoTime();

        ViewTileInfo viewport = request.getViewportGrid();
        AtlasPoolPage atlasPage = request.getPage();
        List<ShaderDraw> draws = request.getDraws();
        GLCompositeContext context = request.getContext();
        GLFramebuffer[] fboPair = {request.getFboA(), request.getFboB()};

        if (draws.isEmpty()) {
            fboPair[0].bind();
            try {
                glClearColor(0f, 0f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
            } finally {
                GLFramebuffer.unbind();
            }
            return fboPair[0];
        }

        // ── 清屏 ──
        long tClearStart = System.nanoTime();
        for (GLFramebuffer fbo : fboPair) {
            fbo.bind();
            try {
                glClearColor(0f, 0f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
            } finally {
                GLFramebuffer.unbind();
            }
        }
        long tClearEnd = System.nanoTime();

        GLQuad quad = context.getQuad();
        List<Integer> transientVbos = new ArrayList<>();

        long tDrawsStart = System.nanoTime();

        try {
            for (int i = 0; i < draws.size(); i++) {
                ShaderDraw draw = draws.get(i);
                GLFramebuffer target = fboPair[i % 2];


                long tPageInNs = 0;
                long tTableNs = 0;
                long tInstNs = 0;
                long tDrawNs = 0;

                quad.bind();
                try {

                    Shader shader = draw.getShader();



                    List<ShaderLayerTileRef> tiles = shader.getTiles();
                    // 换入瓦片。内部 GL 调用（glActiveTexture / glBindTexture /
                    // glBindFramebuffer）会改变纹理绑定状态，因此必须在 bindables
                    // 绑定之前执行——否则后续的 glBindTexture 会覆盖 bindables 的绑定。
                    long p0 = System.nanoTime();
                    pageIn(atlasPage, tiles);
                    long p1 = System.nanoTime();
                    tPageInNs += p1 - p0;

                    // 绑定 bindables——顺序上有两条约束：
                    //   1. 必须在 pageIn 之后：pageIn 内部会改纹理状态，提前绑会被覆盖
                    //   2. 必须在 buildBufferGroups 之前：GLTexture.getUnit() 只有 bind
                    //      之后才有效，getDescriptor 依赖它生成 tileTable 的 unit 字段
                    GLBindable[] bindables = shader.getBindables();
                    for (int u = 0; u < bindables.length; u++) {
                        GLBindable b = bindables[u];
                        if (b != null) {
                            b.bind(u);
                            System.out.println("bind: " + b);
                        }
                    }

                    List<TileBufferGroup> groups = shader.buildBufferGroups();

                    for (int gi = 0; gi < groups.size(); gi++) {
                        TileBufferGroup group = groups.get(gi);
                        int instLoc = shader.instanceLocation(gi);

                        // 3. 上传三张 buffer table
                        uploadFloatTable(group.getTileTableUnit(),
                                group.getTileTable(),
                                group.getTileEntryCount() * 8);
                        uploadFloatTable(group.getLayerTableUnit(),
                                group.getLayerTable(),
                                group.getLayerEntryCount() * 8);
                        uploadIndexTable(group.getIndexTableUnit(),
                                group.getIndexList(),
                                group.getIndexCount());
                        long p2 = System.nanoTime();
                        tTableNs += p2 - p1;

                        // 4. 上传 instances VBO
                        transientVbos.add(uploadInstances(group, instLoc, viewport));
                        long p3 = System.nanoTime();
                        tInstNs += p3 - p2;
                    }

                    // 5. 绘制
                    long d0 = System.nanoTime();
                    target.bind();
                    try {
                        shader.bind();
                        try {
                            quad.drawInstanced(viewport.getViewTileCount());
                        } finally {
                            shader.unbind();
                        }
                    } finally {
                        GLFramebuffer.unbind();
                    }
                    long d1 = System.nanoTime();
                    tDrawNs += d1 - d0;
                } catch (Exception e) {
                    System.out.println("composite failed. draw index: " + i);
                    throw e;
                } finally {
                    GLQuad.unbind();
                }

                System.out.println("[draw " + i + "]"
                        + " pageIn=" + ms(tPageInNs) + "ms"
                        + " table=" + ms(tTableNs) + "ms"
                        + " inst=" + ms(tInstNs) + "ms"
                        + " glDraw=" + ms(tDrawNs) + "ms"
                        + " | total=" + ms(tPageInNs + tTableNs + tInstNs + tDrawNs) + "ms");

            }
        } finally {
            for (int vbo : transientVbos) {
                glDeleteBuffers(vbo);
            }
        }

        long tEnd = System.nanoTime();
        System.out.println("[composite]"
                + " clear=" + ms(tClearEnd - tClearStart) + "ms"
                + " draws=" + ms(tEnd - tDrawsStart) + "ms"
                + " | total=" + ms(tEnd - tCall) + "ms");

        return fboPair[(draws.size() - 1) % 2];
    }

    private static String ms(long ns) {
        return String.format("%.2f", ns / 1_000_000.0);
    }

    // ═══════════════════════════════════════════════
    // 换页
    // ═══════════════════════════════════════════════


    /**
     * 确保传入的所有瓦片引用都已换入 GPU。批量路径优先——能整层
     * 分配的走连续分配 + 整层上传；剩余的零头走逐个分配。
     *
     * <p><b>流程</b>：
     * <ol>
     *   <li>收集待换入瓦片 + 已在 GPU 的瓦片</li>
     *   <li>{@code ensureSlotsKeep} 腾出足够空闲槽位</li>
     *   <li>循环调用 {@code allocateContinuously}——每次处理一个 atlas，
     *       直到没有可分配的整层</li>
     *   <li>对每个产出 range 调 {@code atlas.uploadLayers}——批量上传</li>
     *   <li>剩余零头走 {@code allocate} 逐个分配——后续 {@code uploadTile}
     *       逐个上传</li>
     * </ol>
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。
     */
    private void pageIn(AtlasPoolPage page,
                        List<ShaderLayerTileRef> groups) {
        // ── 1. 收集（按 Tile 去重）──
        Set<Tile> toPageInTiles = new LinkedHashSet<>();
        List<TileRef> toPageIn = new ArrayList<>();
        Set<Tile> alreadyOnGpu = new HashSet<>();

        for (ShaderLayerTileRef layerTileRef : groups) {
            TileRef ref = new TileRef(layerTileRef.getCanvas(), layerTileRef.getTile());
            Tile tile = ref.getTile();
            if (tile.queryData(GLTile.class) != null) {
                alreadyOnGpu.add(tile);
            } else if (toPageInTiles.add(tile)) {
                toPageIn.add(ref);
            }
        }

        if (toPageIn.isEmpty() && alreadyOnGpu.isEmpty()) {
            return;
        }

        // ── 2. 腾出足够空闲槽位 ──
        int ensured = page.ensureSlotsKeep(toPageIn.size(), alreadyOnGpu);
        if (ensured < toPageIn.size()) {
            throw new IllegalStateException(
                    "cannot free enough slots: need=" + toPageIn.size()
                            + ", available=" + ensured);
        }
//        System.out.println(
//                "already on gpu size: " + alreadyOnGpu.size()
//                        + ",  ensured: " + ensured);
        // ── 3. 批量分配 + 批量上传 ──
        List<TileRef> remaining = toPageIn;
        List<TileRef> rest = new ArrayList<>();

        while (!remaining.isEmpty()) {
            rest.clear();
            AtlasLayerRange range = page.allocateContinuously(remaining, rest);

            // atlas 碎片化，无法连续分配了
            if (range == null) {
                break;
            }

            // 批量上传整层段——调用方负责 pack + glTexSubImage3D
            range.getAtlas().uploadLayers(range.getStart(), range.getCount());

            remaining = new ArrayList<>(rest);
        }

        // ── 4. 剩余零头逐个分配 + 上传 ──
        for (TileRef ref : remaining) {
            if (!page.allocate(ref)) {
                throw new IllegalStateException(
                        "allocate failed for tile: " + ref.getTile());
            }
            uploadTile(ref.getTile());
        }

        // ── 5. 已在 GPU 的瓦片——确保数据新鲜 ──
        for (Tile tile : alreadyOnGpu) {
            uploadTile(tile);
        }
    }

    /**
     * 确保瓦片的 GPU 数据是新鲜的。
     *
     * <p><b>两类 GPU 瓦片</b>：
     * <ul>
     *   <li>{@link top.kzre.krro.canvas.gl.tile.GLAtlas.GLTileData}——atlas 中的瓦片，
     *       实现 {@link UploadableTile}，脏时重传</li>
     *   <li>外部纹理瓦片（如 FBO 附着）——已是 GPU 数据，
     *       无 CPU peer，无需上传</li>
     * </ul>
     *
     * <p><b>非 GPU 瓦片报错</b>：执行阶段不应出现 CPU 形态的瓦片——
     * 规划阶段必须已完成换入。
     *
     * @param tile 待检查的瓦片，非 null
     * @throws IllegalStateException 瓦片不是 GPU 形态（规划错误）
     */
    private void uploadTile(Tile tile) {
        GLTile glTile = tile.queryData(GLTile.class);
        if (glTile == null) {
            throw new IllegalStateException(
                    "expected GPU tile at execution time, got: "
                            + tile.getClass().getName()
                            + " (tile=" + tile + ")");
        }
        if (glTile instanceof UploadableTile) {
            ((UploadableTile) glTile).ensureUploaded();
        }
    }

    // ═══════════════════════════════════════════════
    // buffer table 上传
    // ═══════════════════════════════════════════════

    private void uploadFloatTable(int unit, float[] data, int count) {
        int buf = glGenBuffers();
        int tex = glGenTextures();
        try {
            FloatBuffer fb = MemoryUtil.memAllocFloat(count);
            try {
                fb.put(data, 0, count);
                fb.flip();
                glBindBuffer(GL_TEXTURE_BUFFER, buf);
                glBufferData(GL_TEXTURE_BUFFER, fb, GL_DYNAMIC_DRAW);
            } finally {
                MemoryUtil.memFree(fb);
            }

            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_BUFFER, tex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buf);
        } catch (Throwable t) {
            glDeleteBuffers(buf);
            glDeleteTextures(tex);
            throw t;
        }
    }

    private void uploadIndexTable(int unit, int[] data, int count) {
        int buf = glGenBuffers();
        int tex = glGenTextures();
        try {
            IntBuffer ib = MemoryUtil.memAllocInt(count);
            try {
                ib.put(data, 0, count);
                ib.flip();
                glBindBuffer(GL_TEXTURE_BUFFER, buf);
                glBufferData(GL_TEXTURE_BUFFER, ib, GL_DYNAMIC_DRAW);
            } finally {
                MemoryUtil.memFree(ib);
            }

            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_BUFFER, tex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, buf);
        } catch (Throwable t) {
            glDeleteBuffers(buf);
            glDeleteTextures(tex);
            throw t;
        }
    }

    private int uploadInstances(TileBufferGroup group,
                                int instanceLocation,
                                ViewTileInfo grid) {
        int n = grid.getViewTileCount();
        int[] tileXY  = grid.getTileXY();
        int[] offsets = group.getOffsets();
        int[] counts  = group.getCounts();

        FloatBuffer fb = MemoryUtil.memAllocFloat(n * 4);
        int vbo = 0;
        try {
            for (int i = 0; i < n; i++) {
                fb.put(tileXY[i * 2]);
                fb.put(tileXY[i * 2 + 1]);
                fb.put(offsets[i]);
                fb.put(counts[i]);
            }
            fb.flip();

            vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);

            glEnableVertexAttribArray(instanceLocation);
            glVertexAttribPointer(instanceLocation, 4, GL_FLOAT, false,
                    4 * Float.BYTES, 0L);
            glVertexAttribDivisor(instanceLocation, 1);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } catch (Throwable t) {
            if (vbo != 0) glDeleteBuffers(vbo);
            throw t;
        } finally {
            MemoryUtil.memFree(fb);
        }
        return vbo;
    }
}