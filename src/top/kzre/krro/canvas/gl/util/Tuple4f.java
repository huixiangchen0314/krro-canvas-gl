package top.kzre.krro.canvas.gl.util;

/**
 * 四元浮点组。纯值类型——四个 {@code float} 字段的不可变容器。
 *
 * <p><b>不可变</b>——构造后字段不再变化，可安全跨线程共享。
 *
 * <p><b>无语义</b>——本类不假定字段的含义。调用方决定
 * {@code (x, y, z, w)} 代表什么。需要 AABB 语义时，由使用方
 * 自行解释或另建类型。
 */
public final class Tuple4f {

    private final float x;
    private final float y;
    private final float z;
    private final float w;

    public Tuple4f(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public float x() { return x; }
    public float y() { return y; }
    public float z() { return z; }
    public float w() { return w; }

    // ── 值语义 ────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Tuple4f)) return false;
        Tuple4f t = (Tuple4f) o;
        return Float.compare(t.x, x) == 0
                && Float.compare(t.y, y) == 0
                && Float.compare(t.z, z) == 0
                && Float.compare(t.w, w) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.hashCode(x);
        h = 31 * h + Float.hashCode(y);
        h = 31 * h + Float.hashCode(z);
        h = 31 * h + Float.hashCode(w);
        return h;
    }

    @Override
    public String toString() {
        return "Tuple4f{"
                + "x=" + x
                + ", y=" + y
                + ", z=" + z
                + ", w=" + w
                + '}';
    }
}