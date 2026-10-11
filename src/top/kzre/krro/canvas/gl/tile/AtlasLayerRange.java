package top.kzre.krro.canvas.gl.tile;

import java.util.Objects;

/**
 * 在单个 atlas 内的一段连续层区间 {@code [start, start + count)}。
 *
 * <p><b>用途</b>：描述"某个 atlas 上从 startLayer 开始的 count 个连续
 * 层"。常用于批量上传——拿到本对象后可直接调用
 * {@link GLAtlas#uploadLayers(int, int) atlas.uploadLayers(start, count)}
 * 一次覆盖整段。
 *
 * <p><b>不可变</b>：字段 final，构造后不可变。可安全跨线程发布。
 *
 * <p><b>值语义</b>：{@link #equals} / {@link #hashCode} 基于
 * {@code (atlas 身份, start, count)}——同一 atlas 实例、相同起点
 * 和长度即相等。
 */
public final class AtlasLayerRange {

    private final GLAtlas atlas;
    private final int start;
    private final int count;

    /**
     * @param atlas 所属 atlas，非 null
     * @param start 起始层索引（含），{@code >= 0}
     * @param count 连续层数，{@code >= 0}
     * @throws NullPointerException     atlas 为 null
     * @throws IllegalArgumentException start &lt; 0 或 count &lt; 0
     */
    public AtlasLayerRange(GLAtlas atlas, int start, int count) {
        this.atlas = Objects.requireNonNull(atlas, "atlas");
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0: " + start);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        this.start = start;
        this.count = count;
    }

    /** 所属 atlas。 */
    public GLAtlas getAtlas() { return atlas; }

    /** 起始层索引（含）。 */
    public int getStart() { return start; }

    /** 连续层数。 */
    public int getCount() { return count; }

    /** 终点层索引（不含）——即 {@code start + count}。 */
    public int getEnd() { return start + count; }

    /** 区间是否为空——{@code count == 0}。 */
    public boolean isEmpty() { return count == 0; }

    // ═══════════════════════════════════════════════
    // 值语义
    // ═══════════════════════════════════════════════

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AtlasLayerRange)) return false;
        AtlasLayerRange other = (AtlasLayerRange) o;
        return this.atlas == other.atlas
                && this.start == other.start
                && this.count == other.count;
    }

    @Override
    public int hashCode() {
        int h = System.identityHashCode(atlas);
        h = 31 * h + start;
        h = 31 * h + count;
        return h;
    }

    @Override
    public String toString() {
        return "[" + start + ", " + getEnd() + ")@"
                + System.identityHashCode(atlas);
    }
}