package top.kzre.krro.canvas.gl.composite;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一组瓦片缓冲区。规划阶段的产出，执行阶段的输入。
 *
 * <p><b>纯数据</b>：只描述本组的 buffer 布局——瓦片表、索引表、
 * 屏幕瓦片映射、换出映射。不含 atlas 资源、不含 atlas 的 unit 分配
 * （那是 {@link TileBufferBundle} 的职责）。
 *
 * <p>一次 draw call 所需的瓦片相关 buffer 都在这里打包好，执行
 * 阶段只上传、绑定、draw。
 *
 * <p><b>GL 版本要求</b>：OpenGL 3.3 core。
 * 瓦片表和索引表通过 texture buffer（{@code GL_TEXTURE_BUFFER}，
 * 3.1+）上传，shader 用 {@code samplerBuffer} / {@code usamplerBuffer}
 * + {@code texelFetch} 读取。不使用 SSBO（4.3+）。
 *
 * <h2>渲染模型</h2>
 *
 * <p>一次 draw call 完成整个组：
 * <ul>
 *   <li>每个实例 = 一个屏幕瓦片，画一个四边形</li>
 *   <li>顶点着色器从实例数据算出四边形的屏幕位置</li>
 *   <li>片段着色器遍历该屏幕瓦片覆盖的图层瓦片，逐个采样、
 *       按预乘 source-over 混合</li>
 * </ul>
 *
 * <h2>数据布局</h2>
 *
 * <p><b>tileEntries</b>——瓦片表，每条目 12 float，构成 3 个 vec4：
 * <pre>
 *   vec4 e0 = [a, b, c, d]                    // 图层正向变换，本地 → 视口
 *   vec4 e1 = [tx, ty, u0, v0]                // 平移 + atlas uv 起点
 *   vec4 e2 = [atlasIndex, layer, alpha, pad] // bundle 内 atlas 索引、层、透明度
 * </pre>
 * 条目数 = {@code tileEntries.length / 12}。shader 中每个条目读 3 次
 * {@code texelFetch}。{@code atlasIndex} 是 {@link TileBufferBundle}
 * 中 atlas 数组的下标。
 *
 * <p><b>indexList</b>——索引表，扁平 int 数组，按实例顺序拼接。
 * 每个屏幕瓦片的索引区间由 {@code offsets} / {@code counts} 定位，
 * 索引值指向 tileEntries 中的条目下标。
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

    /** 瓦片表绑定的纹理单元。 */
    private final int tileTableUnit;

    /** 索引表绑定的纹理单元。 */
    private final int indexTableUnit;

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

    /**
     * 瓦片表数据。每条目 12 float，构成 3 个 vec4。
     * 条目数 = {@code tileEntries.length / 12}。
     */
    private final float[] tileEntries;

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

    /**
     * 槽位到画布位置的映射。换出用——从 atlas 槽位反查 canvas 上
     * 的具体瓦片，驱动 {@link TileRef#pageOut()}。
     *
     * <p>构造时做防御性拷贝 + 不可变包装。
     */
    private final Map<AtlasSlot, TileRef> tiles;

    public TileBufferGroup(int tileTableUnit,
                           int indexTableUnit,
                           int[] offsets,
                           int[] counts,
                           float[] tileEntries, int tileEntryCount,
                           int[] indexList, int indexCount,
                           Map<AtlasSlot, TileRef> tiles) {
        this.tileTableUnit  = tileTableUnit;
        this.indexTableUnit = indexTableUnit;
        this.offsets        = offsets;
        this.counts         = counts;
        this.tileEntries    = tileEntries;
        this.tileEntryCount = tileEntryCount;
        this.indexList      = indexList;
        this.indexCount     = indexCount;
        this.tiles          = Collections.unmodifiableMap(new LinkedHashMap<>(tiles));
    }

    public int     getTileTableUnit()         { return tileTableUnit; }
    public int     getIndexTableUnit()        { return indexTableUnit; }
    public int[]   getOffsets()               { return offsets; }
    public int[]   getCounts()                { return counts; }
    public float[] getTileEntries()           { return tileEntries; }
    public int     getTileEntryCount()        { return tileEntryCount; }
    public int[]   getIndexList()             { return indexList; }
    public int     getIndexCount()            { return indexCount; }
    public Map<AtlasSlot, TileRef> getTiles() { return tiles; }
}