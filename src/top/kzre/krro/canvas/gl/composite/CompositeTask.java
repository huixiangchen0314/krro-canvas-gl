package top.kzre.krro.canvas.gl.composite;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLQuad;
import top.kzre.krro.canvas.gl.tile.GLAtlas;
import top.kzre.krro.canvas.gl.tile.GLTile;

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
 * <p><b>执行流程</b>：
 * <ol>
 *   <li>双 FBO 从 request 取——它们是 atlas 的视图，规划阶段
 *       已建立，任务只借用</li>
 *   <li>逐个 shader 执行：换入 group → 上传 buffer → 切目标 FBO → draw</li>
 *   <li>第 i 个 shader 写 {@code pool[i % 2]}</li>
 *   <li>返回最终 FBO</li>
 * </ol>
 *
 * <p><b>输入的统一</b>：FBO 是 atlas 的视图，前一个 shader 的输出
 * 就写在 atlas 上。下一个 shader 采样 atlas 时自然读到，不需要
 * 额外的纹理绑定。backdrop 作为单独概念已消失——它要么是 atlas
 * 上的一块瓦片（通过 group 渲染），要么由上游 shader 的输出提供。
 *
 * <p><b>换页</b>：每个 group 的瓦片在渲染前逐块检查。非 GPU 形态的
 * 瓦片尝试换入。槽位被占时，从已经渲染过的 shader 中反向查找占用者，
 * 换出后重试。
 *
 * <p><b>线程契约</b>：{@code call} 必须在 GL 线程上执行。
 */
public final class CompositeTask implements Callable<GLFramebuffer> {

    private final CompositeRequest request;
    private final GLQuad           quad;

    public CompositeTask(CompositeRequest request, GLQuad quad) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        if (quad == null)    throw new IllegalArgumentException("quad must not be null");
        this.request = request;
        this.quad    = quad;
    }

    @Override
    public GLFramebuffer call() throws Exception {
        ViewportGrid     grid   = request.getViewportGrid();
        TileBufferBundle bundle = request.getBundle();

        GLFramebuffer[] pool = { request.getFboA(), request.getFboB() };

        List<Shader> shaders = bundle.getShaders();

        // shader 为空：清空 fboA 作为结果
        if (shaders.isEmpty()) {
            pool[0].bind();
            try {
                glClearColor(0f, 0f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
            } finally {
                GLFramebuffer.unbind();
            }
            return pool[0];
        }

        List<Shader> executed = new ArrayList<>();

        for (int i = 0; i < shaders.size(); i++) {
            Shader        shader = shaders.get(i);
            GLFramebuffer target = pool[i % 2];

            quad.bind();
            try {
                // 1. 准备本 shader 的所有 group
                List<TileBufferGroup> groups = shader.tiles();
                for (int gi = 0; gi < groups.size(); gi++) {
                    prepareGroup(groups.get(gi),
                            shader.instanceLocation(gi),
                            grid, executed);
                }

                // 2. 渲染到 target——前一个 shader 的输出已在 atlas 上，
                //    shader 采样 atlas 时自然读到，无需额外绑定
                target.bind();
                try {
                    glClearColor(0f, 0f, 0f, 0f);
                    glClear(GL_COLOR_BUFFER_BIT);

                    shader.bind();
                    try {
                        quad.drawInstanced(grid.getScreenTileCount());
                    } finally {
                        shader.unbind();
                    }
                } finally {
                    GLFramebuffer.unbind();
                }
            } finally {
                GLQuad.unbind();
            }

            executed.add(shader);
        }

        // 结果在最后一次写入的 FBO
        return pool[(shaders.size() - 1) % 2];
    }

    // ═══════════════════════════════════════════════
    // 内部步骤
    // ═══════════════════════════════════════════════

    /**
     * 准备一个 {@link TileBufferGroup}：换入瓦片、绑定 atlas、
     * 上传 buffer。
     *
     * <p><b>前提</b>：调用时 {@link GLQuad} 的 VAO 已绑定。实例
     * attribute 会挂到这个 VAO 上。
     *
     * @param executed 已经渲染过的 shader 列表——换页时反向查占用者
     */
    private void prepareGroup(TileBufferGroup group,
                              int instanceLocation,
                              ViewportGrid grid,
                              List<Shader> executed) {
        AtlasPoolPage page = request.getBundle().getPage();

        // 1. 换入瓦片
        for (Map.Entry<AtlasSlot, TileRef> e : group.getTiles().entrySet()) {
            pageIn(page, e.getKey(), e.getValue(), executed);
        }

        // 2. 绑定 atlas
        GLAtlas[] atlases = page.getAtlases();
        for (int i = 0; i < atlases.length; i++) {
            GLAtlas atlas = atlases[i];
            if (atlas != null) {
                atlas.bind(i);
            }
        }

        // 3. 上传 buffer
        uploadTileTable(group.getTileTableUnit(),
                group.getTileEntries(),
                group.getTileEntryCount() * 12);
        uploadIndexTable(group.getIndexTableUnit(),
                group.getIndexList(),
                group.getIndexCount());
        uploadInstances(group, instanceLocation, grid);
    }

    /**
     * 换入单块瓦片。
     *
     * <p>流程：
     * <ol>
     *   <li>已经是 {@link GLTile} 形态 → 命中，跳过</li>
     *   <li>尝试 {@link AtlasPoolPage#allocateAt} 换入</li>
     *   <li>成功 → 完成</li>
     *   <li>失败（槽位被占）→ 从已渲染的 shader 中反向查占用者
     *     <ul>
     *       <li>查到 → 换出，重试 allocateAt</li>
     *       <li>重试仍失败 → 规划错误，抛异常</li>
     *       <li>未查到 → 规划错误，抛异常</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @param page     换页接口
     * @param slot     目标槽位
     * @param ref      瓦片引用
     * @param executed 已经渲染过的 shader 列表
     */
    private void pageIn(AtlasPoolPage page,
                        AtlasSlot slot,
                        TileRef ref,
                        List<Shader> executed) {
        // ── 1. 已是 GPU 形态：跳过 ──
        if (ref.getTile().queryData(GLTile.class) != null) {
            return;
        }

        // ── 2. 尝试换入 ──
        if (page.allocateAt(slot, ref)) {
            return;
        }

        // ── 3. 槽位被占：从已渲染的 shader 中反向查占用者 ──
        TileRef occupant = findOccupant(executed, slot);
        if (occupant == null) {
            throw new IllegalStateException(
                    "planning error: slot " + slot + " is occupied but no "
                            + "occupant found in executed shaders");
        }

        occupant.pageOut();

        // ── 4. 重试 ──
        if (!page.allocateAt(slot, ref)) {
            throw new IllegalStateException(
                    "planning error: slot " + slot + " still occupied after "
                            + "evicting " + occupant);
        }
    }

    /**
     * 从已渲染的 shader 中反向查找占用指定槽位的瓦片引用。
     *
     * <p>从最近的 shader 开始往前找——最近使用过的槽位最可能是
     * 当前冲突的来源。
     *
     * @return 占用者的 TileRef；未找到返回 null
     */
    private TileRef findOccupant(List<Shader> executed, AtlasSlot slot) {
        for (int i = executed.size() - 1; i >= 0; i--) {
            Shader s = executed.get(i);
            for (TileBufferGroup g : s.tiles()) {
                TileRef ref = g.getTiles().get(slot);
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════
    // buffer 上传
    // ═══════════════════════════════════════════════

    private void uploadTileTable(int unit, float[] data, int count) {
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

            glBindTexture(GL_TEXTURE_BUFFER, tex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32F, buf);

            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_BUFFER, tex);
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

            glBindTexture(GL_TEXTURE_BUFFER, tex);
            glTexBuffer(GL_TEXTURE_BUFFER, GL_R32UI, buf);

            glActiveTexture(GL_TEXTURE0 + unit);
            glBindTexture(GL_TEXTURE_BUFFER, tex);
        } catch (Throwable t) {
            glDeleteBuffers(buf);
            glDeleteTextures(tex);
            throw t;
        }
    }

    private void uploadInstances(TileBufferGroup group,
                                 int instanceLocation,
                                 ViewportGrid grid) {
        int n = grid.getScreenTileCount();
        int[] tileXY  = grid.getTileXY();
        int[] offsets = group.getOffsets();
        int[] counts  = group.getCounts();

        FloatBuffer fb = MemoryUtil.memAllocFloat(n * 4);
        try {
            for (int i = 0; i < n; i++) {
                fb.put(tileXY[i * 2]);
                fb.put(tileXY[i * 2 + 1]);
                fb.put(offsets[i]);
                fb.put(counts[i]);
            }
            fb.flip();

            int vbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, fb, GL_DYNAMIC_DRAW);

            glEnableVertexAttribArray(instanceLocation);
            glVertexAttribPointer(instanceLocation, 4, GL_FLOAT, false,
                    4 * Float.BYTES, 0L);
            glVertexAttribDivisor(instanceLocation, 1);

            glBindBuffer(GL_ARRAY_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(fb);
        }
    }
}