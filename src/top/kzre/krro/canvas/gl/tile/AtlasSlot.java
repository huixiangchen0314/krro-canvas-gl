package top.kzre.krro.canvas.gl.tile;

/**
 * atlas 上的一个槽位。不可变值对象。
 *
 * <p>规划阶段分配，执行阶段按此上传。描述"瓦片放在哪个 atlas
 * 的哪个位置"。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@code unit}  —— 纹理单元。多 atlas 场景下区分 atlas。</li>
 *   <li>{@code layer} —— texture array 的层索引。</li>
 *   <li>{@code row}   —— 层内行坐标。对应 atlas 内部 v 方向。</li>
 *   <li>{@code col}   —— 层内列坐标。对应 atlas 内部 u 方向。</li>
 * </ul>
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

    /** 层内行坐标（v 方向）。 */
    private final int row;

    /** 层内列坐标（u 方向）。 */
    private final int col;

    public AtlasSlot(int atlasIndex, int layer, int row, int col) {
        this.atlasIndex = atlasIndex;
        this.layer = layer;
        this.row   = row;
        this.col   = col;
    }

    public int getAtlasIndex()  { return atlasIndex; }
    public int getLayer() { return layer; }
    public int getRow()   { return row; }
    public int getCol()   { return col; }

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
                && row   == that.row
                && col   == that.col;
    }

    @Override
    public int hashCode() {
        int h = atlasIndex;
        h = 31 * h + layer;
        h = 31 * h + row;
        h = 31 * h + col;
        return h;
    }

    @Override
    public String toString() {
        return "AtlasSlot{unit=" + atlasIndex
                + ", layer=" + layer
                + ", row=" + row
                + ", col=" + col + "}";
    }
}