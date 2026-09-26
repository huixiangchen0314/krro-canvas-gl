package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.tile.AtlasSlot;
import top.kzre.krro.canvas.gl.tile.TileRef;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一组瓦片缓冲区。规划阶段的产出，执行阶段的输入。
 *
 * <p><b>纯数据</b>：只描述本组的 buffer 布局——瓦片表、图层表、
 * 索引表、屏幕瓦片映射、换出映射。不含纹理资源、不含纹理单元
 * 分配（那是 {@link TileBufferBundle} 的职责）。
 *
 * <p>一次 draw call 所需的瓦片相关 buffer 都在这里打包好，执行
 * 阶段只上传、绑定、draw。
 *
 * <p><b>GL 版本要求</b>：OpenGL 3.3 core。
 * 所有表通过 texture buffer（{@code GL_TEXTURE_BUFFER}，3.1+）
 * 上传，shader 用 {@code samplerBuffer} / {@code usamplerBuffer}
 * + {@code texelFetch} 读取。
 *
 * <h2>为什么要分图层表和瓦片表</h2>
 *
 * <p>同一图层的所有瓦片共享逆变换和 alpha。若把这些字段嵌入每条
 * 瓦片条目，N 块瓦片就重复存 N 遍。拆出图层表后，变换和 alpha
 * 每图层只存一份，瓦片条目只保留一个 {@code layerEntry} 指向它。
 * 变换字段从 O(N) 降到 O(L)——L 为图层数，远小于瓦片数。
 *
 * <p>同时，瓦片表存的是<b>逆变换</b>，shader 直接乘加得到局部坐标——
 * 不再在片段阶段求逆矩阵。
 *
 * <h2>数据布局</h2>
 *
 * <p><b>layerTable</b>——图层表，每图层 2 个 vec4 = 8 float：
 * <pre>
 *   vec4 e0 = [invA, invB, invC, invD]     // 逆变换线性部分，视口 → 图层全局
 *   vec4 e1 = [invTx, invTy, alpha, pad]   // 逆变换平移 + 图层 alpha
 * </pre>
 * 条目数 = {@code layerTable.length / 8}。
 *
 * <p><b>tileTable</b>——瓦片表，每瓦片 2 个 vec4 = 8 float：
 * <pre>
 *   vec4 e0 = [u0, v0, unit, texLayer]
 *      u0, v0    — 瓦片在纹理层内的 uv 起点（左下角）
 *      unit      — GL 纹理单元，也是 bindables 数组下标
 *      texLayer  — texture array 的层索引
 *
 *   vec4 e1 = [tileX, tileY, layerEntry, pad]
 *      tileX, tileY — 瓦片在图层内的网格坐标
 *      layerEntry   — 该瓦片所属图层在 layerTable 中的条目下标
 *      pad          — 对齐填充
 * </pre>
 * 条目数 = {@code tileTable.length / 8}。
 *
 * <p><b>indexList</b>——索引表，扁平 int 数组，按屏幕瓦片顺序拼接。
 * 每个屏幕瓦片的索引区间由 {@code offsets} / {@code counts} 定位，
 * 索引值指向 tileTable 中的条目下标。
 *
 * <h2>几何参数</h2>
 *
 * <p>视口尺寸、瓦片边长属于顶点着色器的 uniform，消费者只有顶点
 * 着色器，不属于瓦片概念，因此不在本类中。由 shader 实例持有或
 * 执行阶段传入。
 *
 * <h2>线程契约</h2>
 *
 * <p>纯数据，任意线程可构造和读取。上传和绑定动作由执行器在 GL
 * 线程内完成。
 */
public final class TileBufferGroup {

    // ═══════════════════════════════════════════════
    // 纹理单元
    // ═══════════════════════════════════════════════

    /** 瓦片表绑定的纹理单元。 */
    private final int tileTableUnit;

    /** 图层表绑定的纹理单元。 */
    private final int layerTableUnit;

    /** 索引表绑定的纹理单元。 */
    private final int indexTableUnit;

    // ═══════════════════════════════════════════════
    // 屏幕瓦片映射
    // ═══════════════════════════════════════════════

    /**
     * 每个屏幕瓦片在本组 indexList 中的起始下标。
     * 长度 = 屏幕瓦片总数。顺序与 {@link ViewportGrid} 的 tileXY 一致。
     */
    private final int[] offsets;

    /**
     * 每个屏幕瓦片在本组覆盖的图层瓦片数。
     * 长度 = 屏幕瓦片总数。{@code count == 0} 表示该位置本组无内容。
     */
    private final int[] counts;

    // ═══════════════════════════════════════════════
    // Buffer 数据
    // ═══════════════════════════════════════════════

    /**
     * 图层表数据。每图层 8 float，构成 2 个 vec4。
     * 条目数 = {@code layerTable.length / 8}。
     */
    private final float[] layerTable;

    /** 图层表条目数。 */
    private final int layerEntryCount;

    /**
     * 瓦片表数据。每瓦片 8 float，构成 2 个 vec4。
     * 条目数 = {@code tileTable.length / 8}。
     */
    private final float[] tileTable;

    /** 瓦片表条目数。 */
    private final int tileEntryCount;

    /**
     * 索引表数据。扁平 int 数组，按实例顺序拼接。
     * 每个屏幕瓦片的索引区间由该实例的 {@code offset} 和 {@code count}
     * 定位。
     */
    private final int[] indexList;

    /** 索引总数。 */
    private final int indexCount;

    // ═══════════════════════════════════════════════
    // 换出映射
    // ═══════════════════════════════════════════════

    /**
     * 槽位到画布位置的映射。换出用——从 atlas 槽位反查 canvas 上
     * 的具体瓦片，驱动 {@link TileRef#pageOut()}。
     *
     * <p>构造时做防御性拷贝 + 不可变包装。
     */
    private final Map<AtlasSlot, TileRef> tiles;

    public TileBufferGroup(int tileTableUnit,
                           int layerTableUnit,
                           int indexTableUnit,
                           int[] offsets,
                           int[] counts,
                           float[] layerTable, int layerEntryCount,
                           float[] tileTable, int tileEntryCount,
                           int[] indexList, int indexCount,
                           Map<AtlasSlot, TileRef> tiles) {
        this.tileTableUnit   = tileTableUnit;
        this.layerTableUnit  = layerTableUnit;
        this.indexTableUnit  = indexTableUnit;
        this.offsets         = offsets;
        this.counts          = counts;
        this.layerTable      = layerTable;
        this.layerEntryCount = layerEntryCount;
        this.tileTable       = tileTable;
        this.tileEntryCount  = tileEntryCount;
        this.indexList       = indexList;
        this.indexCount      = indexCount;
        this.tiles           = Collections.unmodifiableMap(new LinkedHashMap<>(tiles));
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public int   getTileTableUnit()           { return tileTableUnit; }
    public int   getLayerTableUnit()          { return layerTableUnit; }
    public int   getIndexTableUnit()          { return indexTableUnit; }

    public int[] getOffsets()                 { return offsets; }
    public int[] getCounts()                  { return counts; }

    public float[] getLayerTable()            { return layerTable; }
    public int     getLayerEntryCount()       { return layerEntryCount; }

    public float[] getTileTable()             { return tileTable; }
    public int     getTileEntryCount()        { return tileEntryCount; }

    public int[]   getIndexList()             { return indexList; }
    public int     getIndexCount()            { return indexCount; }

    public Map<AtlasSlot, TileRef> getTiles() { return tiles; }
}