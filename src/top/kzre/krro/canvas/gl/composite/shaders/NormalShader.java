package top.kzre.krro.canvas.gl.composite.shaders;

import top.kzre.krro.canvas.gl.composite.AbstractShader;
import top.kzre.krro.canvas.gl.composite.TileBufferGroup;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayout;

import java.util.Collections;
import java.util.List;

/**
 * Normal 混合的合成 shader。单 group。
 *
 * <p><b>uniform 布局</b>：
 * <ul>
 *   <li>{@code uViewport} / {@code uTileSize} —— 几何参数（顶点着色器）</li>
 *   <li>{@code uTileScale} —— {@code tileSize / layerSize}（片段着色器）</li>
 *   <li>{@code uTileTable} / {@code uIndexTable} —— 本 group 的 buffer 单元</li>
 *   <li>{@code uAtlas0..3} —— atlas 采样器，指向 unit 0..3</li>
 * </ul>
 *
 * <h2>atlas 单元约定</h2>
 *
 * <p>{@code bindables[i]} 绑到单元 {@code i}，{@code tileEntries} 的
 * {@code atlasIndex} 字段即该下标。shader 里 {@code uAtlas0} 声明
 * 指向单元 0，{@code uAtlas1} 指向单元 1，以此类推。规划阶段保证
 * atlas 数量不超过 shader 声明的采样器个数。
 */
public final class NormalShader extends AbstractShader {

    /** 单 group 的实例 attribute location。 */
    private static final int INSTANCE_LOCATION = 1;

    /** shader 源码里声明的 atlas 采样器数量。 */
    public static final int ATLAS_SLOTS = 4;

    private final TileBufferGroup    group;
    private final int                viewWidth;
    private final int                viewHeight;
    private final GLTiledTextureLayout layout;

    /**
     * @param program    已编译的 GLProgram
     * @param group      本 shader 使用的瓦片数据
     * @param viewWidth  视口宽（像素，已对齐到 tileSize 倍数）
     * @param viewHeight 视口高（像素，已对齐到 tileSize 倍数）
     * @param layout     atlas 布局，提供 tileSize / layerSize
     */
    public NormalShader(GLProgram program,
                        TileBufferGroup group,
                        int viewWidth, int viewHeight,
                        GLTiledTextureLayout layout) {
        super(program);
        if (group == null) {
            throw new IllegalArgumentException("group must not be null");
        }
        if (viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid viewport: " + viewWidth + "x" + viewHeight);
        }
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }

        this.group      = group;
        this.viewWidth  = viewWidth;
        this.viewHeight = viewHeight;
        this.layout     = layout;
    }

    // ═══════════════════════════════════════════════
    // Shader 接口
    // ═══════════════════════════════════════════════

    @Override
    public List<TileBufferGroup> tiles() {
        return Collections.singletonList(group);
    }

    @Override
    public int instanceLocation(int groupIndex) {
        if (groupIndex != 0) {
            throw new IndexOutOfBoundsException("groupIndex: " + groupIndex);
        }
        return INSTANCE_LOCATION;
    }

    @Override
    protected void configure() {
        GLProgram p = getProgram();

        int tileSize  = layout.getTileSize();
        int layerSize = layout.getLayerSize();
        float tileScale = (float) tileSize / layerSize;

        // ── 几何参数 ──
        p.setVec2("uViewport", viewWidth, viewHeight);
        p.setFloat("uTileSize", tileSize);
        p.setFloat("uTileScale", tileScale);

        // ── 本 group 的 buffer 单元 ──
        p.setInt("uTileTable",  group.getTileTableUnit());
        p.setInt("uIndexTable", group.getIndexTableUnit());

        // ── atlas 采样器 —— 固定指向 unit 0..3 ──
        p.setInt("uAtlas0", 0);
        p.setInt("uAtlas1", 1);
        p.setInt("uAtlas2", 2);
        p.setInt("uAtlas3", 3);
    }
}