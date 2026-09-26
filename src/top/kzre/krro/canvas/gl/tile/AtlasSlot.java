package top.kzre.krro.canvas.gl.tile;

/**
 * atlas 上的一个槽位。不可变值对象。
 *
 * <p>规划阶段分配，执行阶段按此上传。描述"瓦片放在哪个 atlas
 * 的哪个位置"。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@code atlasIndex} —— atlas 在池中的下标，多 atlas 场景下区分。</li>
 *   <li>{@code layer}      —— texture array 的层索引。</li>
 *   <li>{@code dataRow}    —— 层内行。atlas 槽位网格的第几行——
 *       <b>无方向语义</b>，不表示 v 方向，也不表示 canvas 行。</li>
 *   <li>{@code dataColumn} —— 层内列。同上，无方向语义。</li>
 * </ul>
 *
 * <p><b>为什么无方向</b>：槽位坐标只是"数据放在 atlas 第几格"。
 * 上传时被当 GL 纹理坐标（{@code y = dataRow * tileSize}），转
 * {@link GLTileDescriptor} 时被当 uv 起点（{@code v0 = dataRow / edge}）——
 * 两种用法都不需要翻转。方向由<b>使用方</b>决定，不是槽位本身的属性。
 *
 * <p>对比 canvas 网格坐标（{@code canvasRow}）——那套 y 向下，跨到
 * GL 坐标时要翻。两套命名不同，一眼区分。
 *
 * <p><b>与 atlas 的对应</b>：{@code (layer, row, col)} 唯一确定 atlas
 * 内部的一个槽位。atlas 的 {@code tilesPerEdge} 决定行列上界。
 * 瓦片在纹理上的 uv 起点由规划器计算：
 * <pre>
 *   u0 = col / tilesPerEdge
 *   v0 = row / tilesPerEdge
 * </pre>
 *
 * <p><b>作为 Map 键</b>：实现 {@code equals} / {@code hashCode}，
 * 用于 {@code Map<AtlasSlot, TileRef>} 的精确匹配。
 *
 * <p><b>线程契约</b>：纯数据，无 GL 副作用，任意线程可构造和读取。
 */
public final class AtlasSlot {
    /** atlas 在 AtlasPoolPage 中的下标。不是 GL 纹理单元。 */
    private final int atlasIndex;
    /** texture array 层索引。 */
    private final int layer;

    /** 层内行——atlas 槽位网格的第几行。无方向语义。 */
    private final int dataRow;

    /** 层内列——atlas 槽位网格的第几列。无方向语义。 */
    private final int dataColumn;

    public AtlasSlot(int atlasIndex, int layer, int dataRow, int dataColumn) {
        this.atlasIndex = atlasIndex;
        this.layer = layer;
        this.dataRow = dataRow;
        this.dataColumn = dataColumn;
    }

    public int getAtlasIndex()  { return atlasIndex; }
    public int getLayer() { return layer; }
    public int getDataRow()   { return dataRow; }
    public int getDataColumn()   { return dataColumn; }

    // ═══════════════════════════════════════════════
    // Object
    // ═══════════════════════════════════════════════

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AtlasSlot)) return false;
        AtlasSlot that = (AtlasSlot) o;
        return atlasIndex == that.atlasIndex
                && layer == that.layer
                && dataRow == that.dataRow
                && dataColumn == that.dataColumn;
    }

    @Override
    public int hashCode() {
        int h = atlasIndex;
        h = 31 * h + layer;
        h = 31 * h + dataRow;
        h = 31 * h + dataColumn;
        return h;
    }

    @Override
    public String toString() {
        return "AtlasSlot{unit=" + atlasIndex
                + ", layer=" + layer
                + ", row=" + dataRow
                + ", col=" + dataColumn + "}";
    }
}