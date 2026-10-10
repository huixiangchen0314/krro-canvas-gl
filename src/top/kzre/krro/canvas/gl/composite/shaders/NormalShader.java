package top.kzre.krro.canvas.gl.composite.shaders;

import top.kzre.krro.canvas.gl.composite.AbstractShader;
import top.kzre.krro.canvas.gl.composite.TileBufferGroup;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.GLAtlas;
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
    private final GLBindable[] bindables;
    private final int tileSize;
    /**
     * @param program    已编译的 GLProgram
     * @param group      本 shader 使用的瓦片数据
     * @param viewWidth  视口宽（像素，已对齐到 tileSize 倍数）
     * @param viewHeight 视口高（像素，已对齐到 tileSize 倍数）
     * @param bindables     纹理布局，提供 tileSize / texLayerSize
     */
    public NormalShader(GLProgram program,
                        TileBufferGroup group,
                        int viewWidth, int viewHeight,
                        int tileSize,
                        GLBindable[] bindables) {
        super(program);
        if (group == null) {
            throw new IllegalArgumentException("group must not be null");
        }
        if (viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid viewport: " + viewWidth + "x" + viewHeight);
        }
        if (bindables == null) {
            throw new IllegalArgumentException("layout must not be null");
        }

        this.group      = group;
        this.viewWidth  = viewWidth;
        this.viewHeight = viewHeight;
        this.tileSize = tileSize;
        this.bindables = bindables;
    }

    // ═══════════════════════════════════════════════
    // Shader 接口
    // ═══════════════════════════════════════════════

    @Override
    public List<TileBufferGroup> tileGroups() {
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

        // ── 几何参数 ──
        p.setVec2IfPresent("uViewport", viewWidth, viewHeight);
        p.setFloatIfPresent("uTileSize", tileSize);


        // ── 本 group 的三张 buffer 表 ──
        p.setIntIfPresent("uTileTable",  group.getTileTableUnit());
        p.setIntIfPresent("uLayerTable", group.getLayerTableUnit());
        p.setIntIfPresent("uIndexTable", group.getIndexTableUnit());

        // ── 纹理采样器 ──
        for (int i = 0; i < TEXTURE_SLOTS; i++) {
            p.setIntIfPresent("uTexture" + i, i);
        }


        float[] scales = new float[TEXTURE_SLOTS * 2];   // 8 * 2

        for (int i = 0; i < bindables.length; i++) {
            GLBindable b = bindables[i];
            int edgeU, edgeV;
            if (b instanceof GLAtlas) {
                GLAtlas atlas = (GLAtlas) b;
                int e = atlas.getLayout().getTilesPerEdge();
                edgeU = edgeV = e;
            } else if (b instanceof GLTexture) {
                // FBO 颜色附件——纹理尺寸等于视口像素尺寸，网格按 tileSize 划分。
                // 非正方形视口下 edgeU ≠ edgeV，必须各算各的。
                edgeU = Math.max(1, (viewWidth  + tileSize - 1) / tileSize);
                edgeV = Math.max(1, (viewHeight + tileSize - 1) / tileSize);
            } else {
                throw new IllegalStateException(
                        "unsupported bindable at unit " + i + ": " + b);
            }
            scales[i * 2]     = 1.0f / edgeU;
            scales[i * 2 + 1] = 1.0f / edgeV;
        }

        p.setVec2Array("uTextureScales", scales);
    }
}