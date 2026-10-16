package top.kzre.krro.canvas.gl.composite.shaders;

import top.kzre.krro.canvas.gl.composite.*;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.GLAtlas;
import top.kzre.krro.canvas.gl.tile.GLTile;
import top.kzre.krro.canvas.gl.tile.GLTileDescriptor;
import top.kzre.krro.canvas.gl.util.Tuple4f;
import top.kzre.krro.util.tile.Tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * Normal 混合的合成 shader。单 group。
 *
 * <p><b>uniform 布局</b>：
 * <ul>
 *   <li>{@code uViewport} / {@code uTileSize} —— 几何参数（顶点着色器）</li>
 *   <li>{@code uTextureScales} —— 每个 bindable 的 (1/edgeU, 1/edgeV)</li>
 *   <li>{@code uTileTable} / {@code uLayerTable} / {@code uIndexTable}
 *       —— 本 group 的三张 buffer 表单元</li>
 *   <li>{@code uTexture0..N} —— 纹理采样器，指向 unit 0..N</li>
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

    private final ShaderLayerTileSet tileSet;
    private final ViewTileInfo viewTileInfo;
    private final GLBindable[] bindables;
    private final int tileSize;
    private final Tuple4f imageAABB;

    /**
     * @param program      已编译的 GLProgram
     * @param tileSet      本 shader 使用的瓦片数据
     * @param viewTileInfo 屏幕瓦片网格 + 视口尺寸
     * @param tileSize     瓦片边长——安全冗余，需与 viewTileInfo 一致
     * @param bindables    纹理布局——顺序即单元号
     */
    public NormalShader(Supplier<GLProgram> program,
                        ShaderLayerTileSet tileSet,
                        ViewTileInfo viewTileInfo,
                        int tileSize,
                        GLBindable[] bindables, Tuple4f imageAABB) {

        super(program, bindables, tileSet.getLayerTileRefs());
        this.imageAABB = imageAABB;

        if (bindables == null) {
            throw new IllegalArgumentException("bindables must not be null");
        }
        if (viewTileInfo == null) {
            throw new IllegalArgumentException("viewTileInfo must not be null");
        }
        if (bindables.length > ShaderConstants.MAX_LAYER_TEXTURE_UNIT_COUNT){
            throw new IllegalArgumentException("bindables must not exceed MAX_LAYER_TEXTURE_UNIT_COUNT");
        }
        if (imageAABB == null) {
            throw new IllegalArgumentException("imageAABB must not be null");
        }

        this.tileSet      = tileSet;
        this.viewTileInfo = viewTileInfo;
        this.tileSize     = tileSize;
        this.bindables    = bindables;

    }

    // ═══════════════════════════════════════════════
    // Shader 接口
    // ═══════════════════════════════════════════════

    @Override
    public List<TileBufferGroup> buildBufferGroups() {
        // 此时所有瓦片应当都已经上传到 GPU。
        List<ShaderLayerTileRef> layerTileRefs = tileSet.getLayerTileRefs();
        List<GLTileDescriptor> descriptors = new ArrayList<>(layerTileRefs.size());

        for (ShaderLayerTileRef layerTileRef : layerTileRefs) {
            Tile tile = layerTileRef.getTile();
            GLTile glTile = tile.queryData(GLTile.class);
            if (glTile == null) {
                throw new IllegalStateException(
                        "tile is not GPU-backed at buildBufferGroups time: " + tile);
            }
            descriptors.add(glTile.getDescriptor());
        }

        ShaderPackedBuffer packed =
                TileBufferUtils.packBuffers(tileSet, descriptors, viewTileInfo);

        return Collections.singletonList(
                new TileBufferGroup(
                        ShaderConstants.TEXTURE_UNIT_TILE_TABLE,
                        ShaderConstants.TEXTURE_UNIT_LAYER_TABLE,
                        ShaderConstants.TEXTURE_UNIT_INDEX_TABLE,
                        packed.getOffsets(),
                        packed.getCounts(),
                        packed.getLayerTable(),
                        packed.getLayerEntryCount(),
                        packed.getTileTable(),
                        packed.getTileEntryCount(),
                        packed.getIndexList(),
                        packed.getIndexCount())
        );
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

        int viewWidth  = viewTileInfo.getViewWidth();
        int viewHeight = viewTileInfo.getViewHeight();

        // ── 几何参数 ──
        p.setVec2IfPresent("uViewport", viewWidth, viewHeight);
        p.setFloatIfPresent("uTileSize", tileSize);

        // ── 图像范围 AABB（视口空间）──
        p.setVec2IfPresent("uImageMinPoint", imageAABB.x(), imageAABB.y());
        p.setVec2IfPresent("uImageMaxPoint", imageAABB.z(), imageAABB.w());

        // ── 本 group 的三张 buffer 表单元 ──
        p.setIntIfPresent("uTileTable",  ShaderConstants.TEXTURE_UNIT_TILE_TABLE);
        p.setIntIfPresent("uLayerTable", ShaderConstants.TEXTURE_UNIT_LAYER_TABLE);
        p.setIntIfPresent("uIndexTable", ShaderConstants.TEXTURE_UNIT_INDEX_TABLE);

        // ── 纹理采样器 ──
        for (int i = 0; i < ShaderConstants.MAX_LAYER_TEXTURE_UNIT_COUNT; i++) {
            p.setIntIfPresent("uTexture" + i, i);
        }

        // ── per-unit scale：每个 bindable 的 (1/edgeU, 1/edgeV) ──
        float[] scales = new float[ShaderConstants.MAX_LAYER_TEXTURE_UNIT_COUNT * 2];

        for (int i = 0; i < bindables.length; i++) {
            GLBindable b = bindables[i];
            int edgeU, edgeV;

            if (b instanceof GLAtlas) {
                GLAtlas atlas = (GLAtlas) b;
                int e = atlas.getLayout().getTilesPerEdge();
                edgeU = edgeV = e;
            } else if (b instanceof GLTexture) {
                // 从纹理自身尺寸推 edge——不依赖"纹理尺寸 == 视口"这一假设
                GLTexture tex = (GLTexture) b;
                edgeU = Math.max(1, (tex.getWidth()  + tileSize - 1) / tileSize);
                edgeV = Math.max(1, (tex.getHeight() + tileSize - 1) / tileSize);
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