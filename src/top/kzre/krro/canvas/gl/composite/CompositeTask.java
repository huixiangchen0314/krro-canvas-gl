package top.kzre.krro.canvas.gl.composite;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLQuad;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;
import top.kzre.krro.canvas.gl.tile.TextureTileSlot;
import top.kzre.krro.canvas.gl.tile.GLTile;
import top.kzre.krro.canvas.gl.tile.TileRef;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        List<ShaderDraw> executed = new ArrayList<>();
        List<Integer> transientVbos = new ArrayList<>();

        long tDrawsStart = System.nanoTime();

        try {
            for (int i = 0; i < draws.size(); i++) {
                ShaderDraw draw = draws.get(i);
                GLFramebuffer target = fboPair[i % 2];
                Shader shader = draw.getShader();

                long tPageInNs = 0;
                long tTableNs  = 0;
                long tInstNs   = 0;
                long tDrawNs   = 0;

                quad.bind();
                try {
                    List<TileBufferGroup> groups = shader.tileGroups();
                    for (int gi = 0; gi < groups.size(); gi++) {
                        TileBufferGroup group = groups.get(gi);
                        int instLoc = shader.instanceLocation(gi);

                        // 1. 换入瓦片
                        long p0 = System.nanoTime();
                        for (Map.Entry<TextureTileSlot, TileRef> e
                                : group.getSlotTileRefMap().entrySet()) {
                            pageIn(atlasPage, e.getKey(), e.getValue(), executed);
                        }
                        long p1 = System.nanoTime();
                        tPageInNs += p1 - p0;

                        // 2. 上传三张 buffer table
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

                        // 3. 上传 instances VBO
                        transientVbos.add(uploadInstances(group, instLoc, viewport));
                        long p3 = System.nanoTime();
                        tInstNs += p3 - p2;
                    }

                    // 4. 绑定 bindables
                    GLBindable[] bindables = draw.getBindables();
                    for (int u = 0; u < bindables.length; u++) {
                        GLBindable b = bindables[u];
                        if (b != null) {
                            b.bind(u);
                        }
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

                executed.add(draw);
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

    private void pageIn(AtlasPoolPage page,
                        TextureTileSlot slot,
                        TileRef ref,
                        List<ShaderDraw> executed) {

        if (ref.getTile().queryData(GLTile.class) != null) {
            uploadIfNeeded(ref);
            return;
        }

        if (page.allocateAt(slot, ref)) {
            uploadIfNeeded(ref);
            return;
        }

        TileRef occupant = findOccupant(executed, slot);
        if (occupant == null) {
            throw new IllegalStateException(
                    "planning error: slot " + slot + " is occupied but no "
                            + "occupant found in executed bundles");
        }
        if (ref.equals(occupant)) {
            return;
        }

        occupant.pageOut();

        if (!page.allocateAt(slot, ref)) {
            throw new IllegalStateException(
                    "planning error: slot " + slot + " still occupied after "
                            + "evicting " + occupant);
        }
        uploadIfNeeded(ref);
    }

    private void uploadIfNeeded(TileRef ref) {
        UploadableTile uploadable = ref.getTile().queryData(UploadableTile.class);
        if (uploadable != null) {
            uploadable.ensureUploaded();
        }
    }

    private TileRef findOccupant(List<ShaderDraw> executed, TextureTileSlot slot) {
        for (int i = executed.size() - 1; i >= 0; i--) {
            ShaderDraw draw = executed.get(i);
            Shader shader = draw.getShader();
            for (TileBufferGroup group : shader.tileGroups()) {
                TileRef ref = group.getSlotTileRefMap().get(slot);
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
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