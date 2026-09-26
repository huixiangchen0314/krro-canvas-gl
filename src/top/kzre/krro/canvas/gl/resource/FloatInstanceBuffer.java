package top.kzre.krro.canvas.gl.resource;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

/**
 * {@link GLInstanceBuffer} 的 float 视图。
 *
 * <p><b>视图语义</b>：本类不拥有 GL 资源——只把字节级核心解释为
 * float 语义。核心的生命周期由外部管理（通常由池借出 / 归还）。
 * 因此本类<b>没有 close</b>——释放是核心的事。
 *
 * <p><b>与核心的关系</b>：
 * <ul>
 *   <li>stride：{@code strideFloats * Float.BYTES}</li>
 *   <li>attribute 拆分：每 4 float 一个 vec4，从 {@code baseLocation} 起</li>
 *   <li>upload：FloatBuffer 零拷贝 reinterpret 为 ByteBuffer 后交给核心</li>
 * </ul>
 *
 * <p><b>线程契约</b>：所有方法必须在 GL 线程调用。
 */
public final class FloatInstanceBuffer {

    private final GLInstanceBuffer inner;
    private final int strideFloats;

    private FloatInstanceBuffer(GLInstanceBuffer inner, int strideFloats) {
        this.inner = inner;
        this.strideFloats = strideFloats;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 创建 float 视图，内部自动建核心。
     *
     * @param strideFloats     每实例 float 数，至少 1
     * @param initialInstances 初始容量（实例数），至少 1
     */
    public static FloatInstanceBuffer create(int strideFloats, int initialInstances) {
        if (strideFloats < 1) {
            throw new IllegalArgumentException(
                    "strideFloats must be >= 1: " + strideFloats);
        }
        GLInstanceBuffer inner = GLInstanceBuffer.create(
                strideFloats * Float.BYTES, initialInstances);
        return new FloatInstanceBuffer(inner, strideFloats);
    }

    /**
     * 包装已有核心。要求字节 stride 是 4 的倍数。
     *
     * @throws IllegalArgumentException 字节 stride 不是 4 的倍数
     */
    public static FloatInstanceBuffer wrap(GLInstanceBuffer inner) {
        if (inner == null) {
            throw new IllegalArgumentException("inner must not be null");
        }
        if (inner.getStrideBytes() % Float.BYTES != 0) {
            throw new IllegalArgumentException(
                    "byte stride " + inner.getStrideBytes()
                            + " is not a multiple of 4");
        }
        return new FloatInstanceBuffer(inner, inner.getStrideBytes() / Float.BYTES);
    }

    // ═══════════════════════════════════════════════
    // 绑定
    // ═══════════════════════════════════════════════

    /**
     * 绑到 VAO——按 vec4 拆分 attribute。
     *
     * <p>{@code strideFloats = 4} → 1 个 location（vec4）；
     * {@code strideFloats = 8} → 2 个 location（vec4 + vec4）。
     * {@code strideFloats} 非 4 倍数时，最后一个 attribute 会读越界——
     * 调用方保证 buffer 尾部填充。
     *
     * @param vao          目标 VAO
     * @param baseLocation 起始 attribute location
     */
    public void bindTo(int vao, int baseLocation) {
        int attrCount = (strideFloats + 3) / 4;
        List<GLInstanceBuffer.Attribute> attrs = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) {
            attrs.add(new GLInstanceBuffer.Attribute(
                    baseLocation + i,
                    4,
                    GL_FLOAT,
                    i * 4 * Float.BYTES,
                    false,
                    1));
        }
        inner.bindTo(vao, attrs);
    }

    // ═══════════════════════════════════════════════
    // 上传
    // ═══════════════════════════════════════════════

    /**
     * 上传实例数据。零拷贝——FloatBuffer 的剩余部分直接视图化为
     * ByteBuffer 交给核心。
     *
     * @param data  position 到 limit 之间至少 {@code count * strideFloats} 个 float
     * @param count 实例数
     */
    public void upload(FloatBuffer data, int count) {
        int floats = count * strideFloats;
        if (data.remaining() < floats) {
            throw new IllegalArgumentException(
                    "data has " + data.remaining() + " floats but need " + floats);
        }
        if (count == 0) {
            inner.upload(ByteBuffer.allocate(0), 0);
            return;
        }

        FloatBuffer slice = data.slice();
        slice.limit(floats);
        ByteBuffer bytes = org.lwjgl.system.MemoryUtil.memByteBuffer(
                org.lwjgl.system.MemoryUtil.memAddress(slice),
                floats * Float.BYTES);
        inner.upload(bytes, count);
    }

    // ═══════════════════════════════════════════════
    // 视图访问器
    // ═══════════════════════════════════════════════

    /**
     * 底层核心。视图不拥有核心——调用方持有核心的所有权，通常
     * 由池管理（借出 / 归还）。
     */
    public GLInstanceBuffer buffer()  { return inner; }

    /** 每实例 float 数。 */
    public int getStrideFloats()      { return strideFloats; }

    /** 每实例字节数（透传核心）。 */
    public int getStrideBytes()       { return inner.getStrideBytes(); }

    /** 实例容量（透传核心）。 */
    public int getCapacityInstances() { return inner.getCapacityInstances(); }

    /** 核心的 VBO 句柄（透传，调试用）。 */
    public int getVbo()               { return inner.getVbo(); }

    /** 核心是否已释放。视图不释放核心，但可查询状态。 */
    public boolean isClosed()       { return inner.isClosed(); }
}