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
 *   <li>{@code uTileScale} —— {@code tileSize / texLayerSize}（片段着色器）</li>
 *   <li>{@code uTileTable} / {@code uLayerTable} / {@code uIndexTable}
 *       —— 本 group 的三张 buffer 表单元</li>
 *   <li>{@code uTexture0..3} —— 纹理采样器，指向 unit 0..3</li>
 * </ul>
 *
 * <h2>纹理单元约定</h2>
 *
 * <p>{@code bindables[i]} 绑到单元 {@code i}，{@code tileTable} 的
 * {@code unit} 字段即该下标。shader 里 {@code uTexture0} 声明指向
 * 单元 0，{@code uTexture1} 指向单元 1，以此类推。规划阶段保证
 * 纹理数量不超过 shader 声明的采样器个数。
 *
 * <p>shader 只认识纹理——不关心纹理背后是 atlas 还是独立纹理。
 * atlas 是 CPU 侧的资源组织方式，不在 shader 的语义里。
 */
public final class NormalShader extends AbstractShader {

    /** 单 group 的实例 attribute location。 */
    private static final int INSTANCE_LOCATION = 1;

    /** shader 源码里声明的纹理采样器数量。 */
    public static final int TEXTURE_SLOTS = 4;

    private final TileBufferGroup     group;
    private final int                 viewWidth;
    private final int                 viewHeight;
    private final GLTiledTextureLayout layout;

    /**
     * @param program    已编译的 GLProgram
     * @param group      本 shader 使用的瓦片数据
     * @param viewWidth  视口宽（像素，已对齐到 tileSize 倍数）
     * @param viewHeight 视口高（像素，已对齐到 tileSize 倍数）
     * @param layout     纹理布局，提供 tileSize / texLayerSize
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

        int tileSize = layout.getTileSize();
        float tileScale = (float) 1 / layout.getTilesPerEdge();

        // ── 几何参数 ──
        p.setVec2("uViewport", viewWidth, viewHeight);
        p.setFloat("uTileSize", tileSize);
        p.setFloat("uTileScale", tileScale);

        // ── 本 group 的三张 buffer 表 ──
        p.setInt("uTileTable",  group.getTileTableUnit());
        p.setInt("uLayerTable", group.getLayerTableUnit());
        p.setInt("uIndexTable", group.getIndexTableUnit());

        // ── 纹理采样器 —— 固定指向 unit 0..3 ──
        p.setInt("uTexture0", 0);
        p.setInt("uTexture1", 1);
        p.setInt("uTexture2", 2);
        p.setInt("uTexture3", 3);

        System.out.println("[NormalShader.configure]"
                + " program=" + System.identityHashCode(p)

                + " tileScale=" + tileScale
                + " viewport=" + viewWidth + "x" + viewHeight
                + " group={"
                + " tileTableUnit=" + group.getTileTableUnit()
                + " layerTableUnit=" + group.getLayerTableUnit()
                + " indexTableUnit=" + group.getIndexTableUnit()
                + " tileEntryCount=" + group.getTileEntryCount()
                + " layerEntryCount=" + group.getLayerEntryCount()
                + " indexCount=" + group.getIndexCount()
                + " }");
    }
}