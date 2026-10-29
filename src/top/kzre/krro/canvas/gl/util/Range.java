package top.kzre.krro.canvas.gl.util;

/**
 * 半开区间 {@code [start, start + count)}。
 *
 * <p><b>用途</b>：表示一个连续范围——槽位段、层段、索引段。
 * 比通用元组有更明确的语义，调用方一眼能看出 {@code start} 是起点、
 * {@code count} 是长度。
 *
 * <p><b>不可变</b>：两个字段 final，构造后不可变。可安全跨线程发布。
 *
 * <p><b>值语义</b>：{@link #equals} / {@link #hashCode} 基于两个
 * 分量——两个内容相同的实例相等。
 */
public final class Range implements Comparable<Range> {

    /**
     * 空区间。所有方法对其行为合法——{@code count == 0}。
     */
    public static final Range EMPTY = new Range(0, 0);

    private final int start;
    private final int count;

    /**
     * @param start 起点（含）
     * @param count 长度，必须 &gt;= 0
     * @throws IllegalArgumentException count &lt; 0
     */
    public Range(int start, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        this.start = start;
        this.count = count;
    }

    /**
     * 起点（含）。
     */
    public int getStart() {
        return start;
    }

    /**
     * 长度。
     */
    public int getCount() {
        return count;
    }

    /**
     * 终点（不含）——即 {@code start + count}。
     */
    public int getEnd() {
        return start + count;
    }

    /**
     * 区间是否为空——{@code count == 0}。
     */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * 本区间是否完全包含 {@code other}。
     */
    public boolean contains(Range other) {
        return this.start <= other.start
                && other.getEnd() <= this.getEnd();
    }

    /**
     * 本区间是否包含位置 {@code value}。
     */
    public boolean contains(int value) {
        return start <= value && value < getEnd();
    }

    // ═══════════════════════════════════════════════
    // 值语义
    // ═══════════════════════════════════════════════

    /**
     * 按 {@code (start, count)} 字典序比较。
     *
     * <p>与 {@link #equals} 一致——排序结果不违反 equals 契约。
     */
    @Override
    public int compareTo(Range other) {
        int c = Integer.compare(this.start, other.start);
        if (c != 0) return c;
        return Integer.compare(this.count, other.count);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Range)) return false;
        Range other = (Range) o;
        return start == other.start && count == other.count;
    }

    @Override
    public int hashCode() {
        return 31 * start + count;
    }

    @Override
    public String toString() {
        return "[" + start + ", " + getEnd() + ")";
    }
}