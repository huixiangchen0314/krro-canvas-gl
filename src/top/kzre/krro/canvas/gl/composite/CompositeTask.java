package top.kzre.krro.canvas.gl.composite;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLQuad;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;
import top.kzre.krro.canvas.gl.tile.AtlasSlot;
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
 * <p><b>执行流程</b>：
 * <ol>
 *   <li>双 FBO 从 request 取——它们是 atlas 的视图，规划阶段
 *       已建立，任务只借用</li>
 *   <li>逐个 bundle 执行：换入 group → 绑定 bindables → 上传 buffer
 *       → 切目标 FBO → draw</li>
 *   <li>第 i 个 bundle 写 {@code pool[i % 2]}</li>
 *   <li>返回最终 FBO</li>
 * </ol>
 *
 * <p><b>每个 bundle = 一次 draw call</b>：bundle 持有 shader、bindables、
 * 换页接口三件套。不同 bundle 的资源、绑定方式、shader 可以完全不同。
 *
 * <p><b>输入的统一</b>：FBO 是 atlas 的视图，前一个 bundle 的输出
 * 就写在 atlas 上。下一个 bundle 采样 atlas 时自然读到，不需要额外
 * 的纹理绑定。backdrop 作为单独概念已消失。
 *
 * <p><b>换页</b>：每个 group 的瓦片在渲染前逐块检查。非 GPU 形态的
 * 瓦片尝试换入。槽位被占时，从已经渲染过的 bundle 中反向查找占用者，
 * 换出后重试。
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
        ViewportGrid viewport        = request.getViewportGrid();
        AtlasPoolPage atlasPage      = request.getPage();
        List<TileBufferBundle> bundles = request.getBundles();
        GLCompositeContext context = request.getContext();
        GLFramebuffer[] pool = { request.getFboA(), request.getFboB() };

        if (bundles.isEmpty()) {
            pool[0].bind();
            try {
                glClearColor(0f, 0f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
            } finally {
                GLFramebuffer.unbind();
            }
            return pool[0];
        }

        for (GLFramebuffer fbo : pool) {
            fbo.bind();
            try {
                glClearColor(0f, 0f, 0f, 0f);
                glClear(GL_COLOR_BUFFER_BIT);
            } finally {
                GLFramebuffer.unbind();
            }
        }

        GLQuad quad = context.getQuad();
        List<TileBufferBundle> executed = new ArrayList<>();

        // 本次调用生成的所有实例 VBO——纯局部状态，退出时统一释放。
        // 不写入任何外部对象，无跨调用副作用。
        List<Integer> transientVbos = new ArrayList<>();

        try {
            for (int i = 0; i < bundles.size(); i++) {
                TileBufferBundle bundle = bundles.get(i);
                GLFramebuffer target = pool[i % 2];
                Shader shader = bundle.getShader();

                quad.bind();
                try {
                    // 1. 准备本 bundle 的所有 group
                    List<TileBufferGroup> groups = shader.tiles();
                    for (int gi = 0; gi < groups.size(); gi++) {
                        prepareGroup(atlasPage,
                                groups.get(gi),
                                shader.instanceLocation(gi),
                                viewport,
                                executed,
                                transientVbos);
                    }

                    // 2. 绑定 bindables
                    GLBindable[] bindables = bundle.getBindables();
                    for (int u = 0; u < bindables.length; u++) {
                        GLBindable b = bindables[u];
                        if (b != null) {
                            b.bind(u);
                        }
                    }

                    // 3. 渲染
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
                } finally {
                    GLQuad.unbind();
                }

                executed.add(bundle);
            }
        } finally {
            // 统一释放本任务创建的临时 VBO——正常返回、异常、
            // 未完成绘制，任何路径下都清理干净。
            for (int vbo : transientVbos) {
                glDeleteBuffers(vbo);
            }
        }

        return pool[(bundles.size() - 1) % 2];
    }

    private void prepareGroup(AtlasPoolPage page,
                              TileBufferGroup group,
                              int instanceLocation,
                              ViewportGrid grid,
                              List<TileBufferBundle> executed,
                              List<Integer> transientVbos) {
        // 1. 换入瓦片
        for (Map.Entry<AtlasSlot, TileRef> e : group.getTiles().entrySet()) {
            pageIn(page, e.getKey(), e.getValue(), executed);
        }

        // 2. 上传 buffer
        //    瓦片表：每瓦片 2 个 vec4 = 8 个 float
        uploadFloatTable(group.getTileTableUnit(),
                group.getTileTable(),
                group.getTileEntryCount() * 8);

        //    图层表：每图层 2 个 vec4 = 8 个 float
        uploadFloatTable(group.getLayerTableUnit(),
                group.getLayerTable(),
                group.getLayerEntryCount() * 8);

        //    索引表：usamplerBuffer，扁平 uint
        uploadIndexTable(group.getIndexTableUnit(),
                group.getIndexList(),
                group.getIndexCount());

        //    逐实例 attribute —— VBO 句柄登记，由 call() 退出时统一释放
        transientVbos.add(uploadInstances(group, instanceLocation, grid));
    }

    /**
     * 换入单块瓦片。
     *
     * <p>流程：
     * <ol>
     *   <li>已经是 {@link GLTile} 形态 → 命中，跳过</li>
     *   <li>尝试 {@link AtlasPoolPage#allocateAt} 换入</li>
     *   <li>成功 → 完成</li>
     *   <li>失败（槽位被占）→ 从已渲染的 bundle 中反向查占用者
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
     * @param executed 已经渲染过的 bundle 列表
     */
    private void pageIn(AtlasPoolPage page,
                        AtlasSlot slot,
                        TileRef ref,
                        List<TileBufferBundle> executed) {
        // ── 1. 已是 GPU 形态：确保上传 ──
        if (ref.getTile().queryData(GLTile.class) != null) {
            uploadIfNeeded(ref);
            return;
        }

        // ── 2. 尝试换入 ──
        if (page.allocateAt(slot, ref)) {
            uploadIfNeeded(ref);
            return;
        }

        // ── 3. 槽位被占：从已渲染的 bundle 中反向查占用者 ──
        TileRef occupant = findOccupant(executed, slot);
        if (occupant == null) {
            throw new IllegalStateException(
                    "planning error: slot " + slot + " is occupied but no "
                            + "occupant found in executed bundles");
        }

        occupant.pageOut();

        // ── 4. 重试 ──
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
            System.out.println("upload tile");
            uploadable.ensureUploaded();
        }
    }

    /**
     * 从已渲染的 bundle 中反向查找占用指定槽位的瓦片引用。
     *
     * <p>从最近的 bundle 开始往前找——最近使用过的槽位最可能是
     * 当前冲突的来源。
     *
     * @return 占用者的 TileRef；未找到返回 null
     */
    private TileRef findOccupant(List<TileBufferBundle> executed, AtlasSlot slot) {
        for (int i = executed.size() - 1; i >= 0; i--) {
            TileBufferBundle bundle = executed.get(i);
            Shader shader = bundle.getShader();
            for (TileBufferGroup g : shader.tiles()) {
                TileRef ref = g.getTiles().get(slot);
                if (ref != null) {
                    return ref;
                }
            }
        }
        return null;
    }

    /**
     * 上传一张 float 类型的 buffer 纹理（用于 {@code samplerBuffer}）。
     *
     * <p>shader 侧每次 {@code texelFetch} 读取一个 {@code vec4}，
     * 因此内部格式必须是 {@code GL_RGBA32F}——一个纹素 4 个 float。
     *
     * @param unit  目标纹理单元
     * @param data  数据
     * @param count 要上传的 float 个数
     */
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

            glActiveTexture(GL_TEXTURE0 + unit);      // ← 先切 unit
            glBindTexture(GL_TEXTURE_BUFFER, tex);    // ← 再绑纹理
            glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buf);  // ← 最后附 buffer
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

    /**
     * 上传逐实例 attribute。
     *
     * <p>返回新建的 VBO 句柄，由调用方负责在绘制完成后删除——
     * 本方法不在内部登记状态，也不对 {@link TileBufferGroup} 写入。
     *
     * <p>异常时自我清理：半成品 VBO 不会泄漏给调用方。
     *
     * @return 新建的 VBO 句柄
     */
    private int uploadInstances(TileBufferGroup group,
                                int instanceLocation,
                                ViewportGrid grid) {
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