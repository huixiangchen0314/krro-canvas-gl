package top.kzre.krro.canvas.gl.composite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 瓦片缓冲区束。一次合成的完整资源清单。
 *
 * <p><b>两部分</b>：
 * <ul>
 *   <li>{@code page} —— atlas 资源与换页能力</li>
 *   <li>{@code shaders} —— 按顺序执行的 shader 序列</li>
 * </ul>
 *
 * <h2>atlas 通过 page 访问</h2>
 *
 * <p>atlas 数组由 {@link AtlasPoolPage#getAtlases()} 提供，下标即
 * 纹理单元。{@link TileBufferGroup} 的 tileEntries 里
 * {@code atlasIndex} 字段即该数组的下标，执行阶段
 * {@code atlases[atlasIndex].bind(atlasIndex)} 即可。
 *
 * <p>bundle 不重复持有 atlas 数组，避免和 page 的状态不一致。
 * 换页（{@link AtlasPoolPage#allocateAt}）和执行阶段绑定都用
 * 同一份数组。
 *
 * <h2>线程契约</h2>
 *
 * <p>纯数据，任意线程可构造和读取。绑定和执行动作由任务在 GL
 * 线程内完成。
 */
public final class TileBufferBundle {

    /**
     * atlas 资源与换页能力。atlas 数组下标即纹理单元。
     */
    private final AtlasPoolPage page;

    /**
     * 按顺序执行的 shader 列表。每个 shader 一次 draw call。
     */
    private final List<Shader> shaders;

    public TileBufferBundle(AtlasPoolPage page, List<Shader> shaders) {
        if (page == null || page.getAtlases().length == 0) {
            throw new IllegalArgumentException("atlases must not be empty");
        }
        if (shaders == null) {
            throw new IllegalArgumentException("shaders must not be null");
        }
        for (Shader s : shaders) {
            if (s == null) {
                throw new IllegalArgumentException("shaders must not contain null");
            }
        }
        this.page    = page;
        this.shaders = Collections.unmodifiableList(new ArrayList<>(shaders));
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** atlas 资源与换页接口。 */
    public AtlasPoolPage getPage() { return page; }

    /** 按顺序执行的 shader 列表。 */
    public List<Shader> getShaders() { return shaders; }
}