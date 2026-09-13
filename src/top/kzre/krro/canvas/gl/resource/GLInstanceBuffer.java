package top.kzre.krro.canvas.gl.resource;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * 实例数据缓冲：为实例化渲染提供 per-instance attribute。
 *
 * <p>用途：给每个 tile（实例）一组 descriptor，由顶点着色器按实例读取。
 * 数据布局为 AoS——每个实例的字段连续存放：
 * <pre>
 *   [f0 f1 f2 f3 f4][f0 f1 f2 f3 f4][...]
 *    ←── 实例 0 ──→   ←── 实例 1 ──→
 * </pre>
 * 每个实例占 {@code stride} 个 float。
 *
 * <h2>Attribute 拆分</h2>
 * 内部按每 4 个 float 拆成一个 {@code vec4} attribute。例如
 * {@code stride = 8} 拆成两个 attribute：
 * <pre>
 *   location = baseLocation      → vec4（实例字段 0-3）
 *   location = baseLocation + 1  → vec4（实例字段 4-7）
 * </pre>
 * 如果 {@code stride} 不是 4 的倍数，最后一个 attribute 可能读取
 * 超出实例边界的字节——由调用方保证 buffer 尾部有足够填充。
 *
 * <h2>Attribute divisor</h2>
 * 所有 attribute 设 {@code divisor = 1}——每个实例前进一次。
 * 与 {@link GLQuad} 的顶点 attribute（{@code divisor = 0}）配合，
 * 实现「每个实例画一个四边形」。
 *
 * <h2>动态扩容</h2>
 * {@link #upload} 在数据超出当前容量时自动调用 {@code glBufferData}
 * 重新分配。首次创建时可指定初始容量避免频繁扩容。
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 *
 * <h2>生命周期</h2>
 * 由 {@link #create} 创建，由 {@link #release()} 释放。不实现
 * {@link AutoCloseable}——释放必须在 GL 线程上执行。
 */
public final class GLInstanceBuffer {

    private final int vbo;
    private final int stride;
    private final int baseLocation;
    private int capacityFloats;
    private boolean released = false;

    private GLInstanceBuffer(int vbo, int stride, int baseLocation, int capacityFloats) {
        this.vbo = vbo;
        this.stride = stride;
        this.baseLocation = baseLocation;
        this.capacityFloats = capacityFloats;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 创建实例缓冲并绑定到 VAO 的 attribute。
     *
     * @param vao           目标 VAO（来自 {@link GLQuad#getVao()}）
     * @param stride        每实例 float 数
     * @param baseLocation  起始 attribute location（{@link GLQuad}
     *                      占用 location 0，通常从 1 开始）
     * @param initialFloats 初始容量（float 数），自动向上取整到
     *                      {@code stride} 的倍数
     * @throws IllegalArgumentException 参数非法
     */
    public static GLInstanceBuffer create(
            int vao, int stride, int baseLocation, int initialFloats) {
        if (stride < 1) {
            throw new IllegalArgumentException("stride must be >= 1: " + stride);
        }
        if (baseLocation < 0) {
            throw new IllegalArgumentException(
                    "baseLocation must be >= 0: " + baseLocation);
        }
        if (initialFloats < stride) {
            initialFloats = stride;
        }
        // 向上取整到 stride 的倍数
        initialFloats = ((initialFloats + stride - 1) / stride) * stride;

        int vbo = glGenBuffers();
        try {
            glBindVertexArray(vao);
            try {
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                glBufferData(GL_ARRAY_BUFFER,
                        (long) initialFloats * Float.BYTES,
                        GL_DYNAMIC_DRAW);

                int floatsPerAttr = 4;
                int attrCount = (stride + floatsPerAttr - 1) / floatsPerAttr;
                for (int i = 0; i < attrCount; i++) {
                    int loc = baseLocation + i;
                    glEnableVertexAttribArray(loc);
                    glVertexAttribPointer(loc, floatsPerAttr, GL_FLOAT, false,
                            stride * Float.BYTES,
                            (long) i * floatsPerAttr * Float.BYTES);
                    glVertexAttribDivisor(loc, 1);
                }
            } finally {
                glBindVertexArray(0);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }
        } catch (Throwable t) {
            glDeleteBuffers(vbo);
            throw t;
        }

        return new GLInstanceBuffer(vbo, stride, baseLocation, initialFloats);
    }

    // ═══════════════════════════════════════════════
    // 上传
    // ═══════════════════════════════════════════════

    /**
     * 上传实例数据。
     *
     * <p>buffer 中前 {@code count * stride} 个 float 被上传。超出当前
     * 容量时自动扩容。
     *
     * @param data  数据源，position 到 limit 之间至少有
     *              {@code count * stride} 个 float
     * @param count 实例数
     * @throws IllegalArgumentException count 非法或 buffer 空间不足
     */
    public void upload(FloatBuffer data, int count) {
        checkAlive();
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        int floats = count * stride;
        if (data.remaining() < floats) {
            throw new IllegalArgumentException(
                    "data has " + data.remaining() + " floats but need " + floats);
        }

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        try {
            if (floats > capacityFloats) {
                glBufferData(GL_ARRAY_BUFFER,
                        (long) floats * Float.BYTES,
                        GL_DYNAMIC_DRAW);
                capacityFloats = floats;
            }
            FloatBuffer slice = data.slice();
            slice.limit(floats);
            glBufferSubData(GL_ARRAY_BUFFER, 0L, slice);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 释放 VBO。必须在 GL 线程上调用。幂等。
     *
     * <p>VAO 中对应 attribute 的配置不会自动清除——如果 VAO 还被
     * 使用，后续 draw 可能读到无效数据。通常 VAO 和 instance buffer
     * 一起释放。
     */
    public void release() {
        if (released) return;
        glDeleteBuffers(vbo);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** VBO 句柄。 */
    public int getVbo() { return vbo; }

    /** 每实例的 float 数。 */
    public int getStride() { return stride; }

    /** 起始 attribute location。 */
    public int getBaseLocation() { return baseLocation; }

    /** 当前容量（float 数）。 */
    public int getCapacityFloats() { return capacityFloats; }

    /** 是否已释放。 */
    public boolean isReleased() { return released; }

    private void checkAlive() {
        if (released) throw new IllegalStateException("GLInstanceBuffer released");
    }
}