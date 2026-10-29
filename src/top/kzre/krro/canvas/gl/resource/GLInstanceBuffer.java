package top.kzre.krro.canvas.gl.resource;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL33.glVertexAttribDivisor;

/**
 * 实例数据缓冲。字节级 VBO——GL 的本质。
 *
 * <p><b>字节级语义</b>：{@code strideBytes} 是每实例的字节数；
 * {@code upload} 接受 {@link ByteBuffer}；{@code bind} 时调用方
 * 逐 attribute 给类型、分量、偏移。混合类型（float + int + 打包
 * 整数）由调用方描述，缓冲不做假设。
 *
 * <p><b>VAO 职责</b>：本类<b>不拥有</b> VAO。{@link #bind(List)} 假定
 * 调用方已绑定目标 VAO，只写 attribute 映射。VAO 是 pass 的职责，
 * buffer 只负责自己的数据与 attribute 描述。
 *
 * <p><b>不直接面向业务调用方</b>：日常使用走 {@link FloatInstanceBuffer}
 * 等特化视图。本类只服务视图层和需要精确控制字节布局的场景。
 *
 * <p><b>关闭语义</b>：{@link #close()} <b>不可重入</b>——重复调用抛
 * 异常。GL 资源的关闭是决定性动作，重复关闭意味着调用方逻辑错误
 * （比如两处代码都在关），静默幂等会掩盖 bug。跟池的 {@code close}
 * 语义一致。
 *
 * <p><b>线程契约</b>：
 * <ul>
 *   <li>{@link #create}、{@link #bind}、{@link #upload}、{@link #close}
 *       内部的 GL 调用必须在 GL 线程执行；</li>
 *   <li>{@link #isClosed()} 与 {@link #close()} 的状态标记可被任意线程
 *       读写——{@code closed} 是 {@code volatile}，保证跨线程可见性。
 *       但 {@code close()} 里的 {@code glDeleteBuffers} 仍会落在调用
 *       线程上，<b>调用方必须保证它跑在 GL 线程</b>。</li>
 * </ul>
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
     * 入口校验。
     */
    private static void checkAttrs(List<Attribute> attrs) {
        if (attrs == null || attrs.isEmpty()) {
            throw new IllegalArgumentException("attrs must not be empty");
        }
    }

    // ═══════════════════════════════════════════════
    // VAO 绑定
    // ═══════════════════════════════════════════════

    /**
     * 把本 buffer 的 attribute 配置写入<b>当前已绑定的 VAO</b>。
     *
     * <p><b>VAO 契约</b>：本方法<b>不绑定/不恢复 VAO</b>——调用方（pass）
     * 必须在调用前自行 {@code glBindVertexArray(targetVao)}。GL 状态是
     * 共用的，VAO 是 pass 的职责，buffer 只负责自己的 attribute 映射。
     *
     * <p>本方法只改 {@code GL_ARRAY_BUFFER} 绑定，结束时解绑到 0。
     *
     * <p><b>越界校验</b>：配置前先对全部 attribute 检查
     * {@code offsetBytes + attributeByteSize(glType, components) <= strideBytes}。
     * 越界时 {@code glVertexAttribPointer} 不会报错，只会静默读到下一个
     * 实例的数据；未知 {@code glType} 由 {@link GLUtils#attributeByteSize}
     * 抛异常。校验在写任何 GL 状态之前完成，失败时 VAO 里不会留下
     * 半套 attribute。
     *
     * <p>绑定后 VAO 记住映射——之后 {@link #upload} 更新数据不需要重绑
     * attribute，但每次绘制前 pass 仍需自己绑 VAO。
     *
     * @param attrs attribute 描述列表，不能为空
     * @throws IllegalArgumentException attrs 为 null / 空，attribute 越界，
     *                                  或 {@code glType} 未知
     * @throws IllegalStateException    本 buffer 已关闭
     */
    public void bind(List<Attribute> attrs) {
        checkAlive();
        checkAttrs(attrs);
        validateAttrs(attrs);            // 先全量校验，再动 GL 状态

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        try {
            configureAttrs(attrs);
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    /**
     * 校验每个 attribute 的字节范围是否落在 stride 内。
     *
     * <p>不调用任何 GL 函数，纯算术；在 {@code glBindBuffer} 之前执行，
     * 失败时 GL 状态完全未变。
     */
    private void validateAttrs(List<Attribute> attrs) {
        for (Attribute a : attrs) {
            int size = GLUtils.attributeByteSize(a.glType, a.components);
            int end = a.offsetBytes + size;
            if (end > strideBytes) {
                throw new IllegalArgumentException(
                        "attribute " + a.location + " ends at byte " + end
                                + " but stride is " + strideBytes
                                + " (offset=" + a.offsetBytes
                                + ", components=" + a.components
                                + ", glType=0x" + Integer.toHexString(a.glType)
                                + ", size=" + size + ")");
            }
        }
    }

    /**
     * 在已绑定的 {@code GL_ARRAY_BUFFER} + 已绑定的 VAO 上配置
     * 所有 attribute。调用方负责 buffer / VAO 的绑定与解绑，
     * 并保证已通过 {@link #validateAttrs} 校验。
     */
    private void configureAttrs(List<Attribute> attrs) {
        for (Attribute a : attrs) {
            glEnableVertexAttribArray(a.location);
            glVertexAttribPointer(a.location, a.components, a.glType,
                    a.normalized, strideBytes, a.offsetBytes);
            glVertexAttribDivisor(a.location, a.divisor);
        }
    }

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
    // 上传
    // ═══════════════════════════════════════════════

    /**
     * 关闭，释放 VBO。GL 调用部分必须在 GL 线程执行。
     *
     * <p><b>不可重入</b>——重复调用抛 {@link IllegalStateException}。
     * 这是有意的：静默幂等会掩盖调用方的逻辑错误（比如两处代码都
     * 在关同一个 buffer）。
     *
     * <p><b>线程</b>：{@code closed} 是 {@code volatile}，标记本身可被
     * 任意线程读写；但 {@code glDeleteBuffers} 必须在 GL 线程执行——
     * 调用方负责把 {@code close()} 调度到 GL 线程。
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
    // 关闭
    // ═══════════════════════════════════════════════

    public int getVbo() {
        return vbo;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public int getStrideBytes() {
        return strideBytes;
    }

    public int getCapacityInstances() {
        return capacityInstances;
    }

    /**
     * 是否已关闭。可被任意线程调用——{@code closed} 是 {@code volatile}。
     */
    public boolean isClosed() {
        return closed;
    }

    private void checkAlive() {
        if (closed) throw new IllegalStateException("GLInstanceBuffer closed");
    }

    /**
     * 单个 attribute 的描述。
     */
    public static final class Attribute {
        public final int location;
        public final int components;
        public final int glType;
        public final int offsetBytes;
        public final boolean normalized;
        public final int divisor;

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
            if (location < 0) throw new IllegalArgumentException("location < 0");
            if (components < 1 || components > 4)
                throw new IllegalArgumentException("components must be 1~4");
            if (offsetBytes < 0) throw new IllegalArgumentException("offsetBytes < 0");
            if (divisor < 0) throw new IllegalArgumentException("divisor < 0");
            this.location = location;
            this.components = components;
            this.glType = glType;
            this.offsetBytes = offsetBytes;
            this.normalized = normalized;
            this.divisor = divisor;
        }
    }
}