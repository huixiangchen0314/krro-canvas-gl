package top.kzre.krro.canvas.gl.resource;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * 实例数据缓冲。字节级 VBO——GL 的本质。
 *
 * <p><b>字节级语义</b>：{@code strideBytes} 是每实例的字节数；
 * {@code upload} 接受 {@link ByteBuffer}；{@code bindTo} 时调用方
 * 逐 attribute 给类型、分量、偏移。混合类型（float + int + 打包
 * 整数）由调用方描述，缓冲不做假设。
 *
 * <p><b>不直接面向业务调用方</b>：日常使用走 {@link FloatInstanceBuffer}
 * 等特化视图。本类只服务视图层和需要精确控制字节布局的场景。
 *
 * <p><b>关闭语义</b>：{@link #close()} <b>不可重入</b>——重复调用抛
 * 异常。GL 资源的关闭是决定性动作，重复关闭意味着调用方逻辑错误
 * （比如两处代码都在关），静默幂等会掩盖 bug。跟池的 {@code close}
 * 语义一致。
 *
 * <p><b>线程契约</b>：所有方法必须在 GL 线程调用。
 */
public final class GLInstanceBuffer implements AutoCloseable {

    private final int vbo;
    private final int strideBytes;
    private final int capacityInstances;
    private volatile boolean closed = false;

    private GLInstanceBuffer(int vbo, int strideBytes, int capacityInstances) {
        this.vbo = vbo;
        this.strideBytes = strideBytes;
        this.capacityInstances = capacityInstances;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * @param strideBytes      每实例字节数，至少 1
     * @param initialInstances 初始容量（实例数），至少 1
     */
    public static GLInstanceBuffer create(int strideBytes, int initialInstances) {
        if (strideBytes < 1) {
            throw new IllegalArgumentException("strideBytes must be >= 1: " + strideBytes);
        }
        if (initialInstances < 1) initialInstances = 1;

        int vbo = glGenBuffers();
        try {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            try {
                glBufferData(GL_ARRAY_BUFFER,
                        (long) initialInstances * strideBytes,
                        GL_DYNAMIC_DRAW);
            } finally {
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }
        } catch (Throwable t) {
            glDeleteBuffers(vbo);
            throw t;
        }
        return new GLInstanceBuffer(vbo, strideBytes, initialInstances);
    }

    // ═══════════════════════════════════════════════
    // Attribute 描述
    // ═══════════════════════════════════════════════

    /**
     * 单个 attribute 的描述。
     */
    public static final class Attribute {
        public final int     location;
        public final int     components;
        public final int     glType;
        public final int     offsetBytes;
        public final boolean normalized;
        public final int     divisor;

        /**
         * @param location    attribute location
         * @param components  分量数 1~4
         * @param glType      GL_FLOAT / GL_INT / GL_UNSIGNED_BYTE / ...
         * @param offsetBytes 相对实例起始的字节偏移
         * @param normalized  是否归一化（整数转 float 时用）
         * @param divisor     通常为 1（每实例一次）
         */
        public Attribute(int location, int components, int glType,
                         int offsetBytes, boolean normalized, int divisor) {
            if (location < 0)      throw new IllegalArgumentException("location < 0");
            if (components < 1 || components > 4)
                throw new IllegalArgumentException("components must be 1~4");
            if (offsetBytes < 0)   throw new IllegalArgumentException("offsetBytes < 0");
            if (divisor < 0)       throw new IllegalArgumentException("divisor < 0");
            this.location    = location;
            this.components  = components;
            this.glType      = glType;
            this.offsetBytes = offsetBytes;
            this.normalized  = normalized;
            this.divisor     = divisor;
        }
    }

    // ═══════════════════════════════════════════════
    // VAO 绑定
    // ═══════════════════════════════════════════════

    /**
     * 按 {@code attrs} 把本 buffer 绑到目标 VAO。
     *
     * <p>绑定后 VAO 记住映射——上传新数据不需要重绑。
     *
     * @param vao   目标 VAO
     * @param attrs attribute 描述列表，不能为空
     * @throws IllegalStateException 本 buffer 已关闭
     */
    public void bindTo(int vao, List<Attribute> attrs) {
        checkAlive();
        if (attrs == null || attrs.isEmpty()) {
            throw new IllegalArgumentException("attrs must not be empty");
        }

        glBindVertexArray(vao);
        try {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            try {
                for (Attribute a : attrs) {
                    glEnableVertexAttribArray(a.location);
                    glVertexAttribPointer(a.location, a.components, a.glType,
                            a.normalized, strideBytes, (long) a.offsetBytes);
                    glVertexAttribDivisor(a.location, a.divisor);
                }
            } finally {
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }
        } finally {
            glBindVertexArray(0);
        }
    }

    // ═══════════════════════════════════════════════
    // 上传
    // ═══════════════════════════════════════════════

    /**
     * 上传实例数据。超出容量抛异常——池化设计下尺寸固定。
     *
     * <p><b>不重新绑定 VAO</b>——VBO 句柄不变，VAO 里记录的映射依然有效。
     *
     * @param data  position 到 limit 之间至少 {@code count * strideBytes} 字节
     * @param count 实例数
     * @throws IllegalArgumentException count 超容量或 data 空间不足
     * @throws IllegalStateException    本 buffer 已关闭
     */
    public void upload(ByteBuffer data, int count) {
        checkAlive();
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        if (count > capacityInstances) {
            throw new IllegalArgumentException(
                    "count " + count + " exceeds capacity " + capacityInstances);
        }
        int bytes = count * strideBytes;
        if (data.remaining() < bytes) {
            throw new IllegalArgumentException(
                    "data has " + data.remaining() + " bytes but need " + bytes);
        }

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        try {
            if (bytes > 0) {
                ByteBuffer slice = data.slice();
                slice.limit(bytes);
                glBufferSubData(GL_ARRAY_BUFFER, 0L, slice);
            }
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 关闭，释放 VBO。必须在 GL 线程调用。
     *
     * <p><b>不可重入</b>——重复调用抛 {@link IllegalStateException}。
     * 这是有意的：静默幂等会掩盖调用方的逻辑错误（比如两处代码都
     * 在关同一个 buffer）。
     *
     * @throws IllegalStateException 已关闭
     */
    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("GLInstanceBuffer already closed");
        }
        glDeleteBuffers(vbo);
        closed = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public int  getVbo()               { return vbo; }
    public int  getStrideBytes()       { return strideBytes; }
    public int  getCapacityInstances() { return capacityInstances; }
    public boolean isClosed()          { return closed; }

    private void checkAlive() {
        if (closed) throw new IllegalStateException("GLInstanceBuffer closed");
    }
}