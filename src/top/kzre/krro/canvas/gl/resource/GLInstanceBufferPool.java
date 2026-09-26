package top.kzre.krro.canvas.gl.resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 实例缓冲池。
 *
 * <h2>设计前提</h2>
 *
 * <p>本池基于以下场景约束：
 * <ul>
 *   <li><b>场景稳定</b>——典型工作负载是大量 Normal 图层 + 少量其他
 *       混合模式。group 大小可预测，一个固定值覆盖所有 group。</li>
 *   <li><b>峰值可控</b>——上层通过缓存 / 限流控制同时提交的合成请求数。
 *       池不负责应对数据洪峰——那是上层职责。这里保证峰值不崩，但不
 *       保证极端优化的资源利用。</li>
 *   <li><b>回落可靠</b>——借出无上限，峰值来多少给多少；idle 有上限，
 *       峰值后自动回落。稳态资源占用可控。</li>
 * </ul>
 *
 * <p>由此导出：不按需求分桶（所有 buffer 等价）、不动态扩容（尺寸固定）、
 * 借出无上限、idle 有上限。
 *
 * <h2>借用语义</h2>
 *
 * <p>每次 {@link #acquire()} 优先复用空闲 buffer，池空则按固定实例容量
 * 新建。借出无上限——峰值需要多少就建多少。
 *
 * <h2>固定尺寸</h2>
 *
 * <p>池内所有 buffer 的字节 stride 和实例容量一致，由构造参数确定。
 * 不做分桶、不按需扩容——group 大小是规划期的固定值，超过池配置即
 * 配置错误，早暴露。
 *
 * <h2>关闭语义</h2>
 *
 * <p>{@link #close()} <b>不可重入</b>——重复调用抛异常。关闭时逐
 * buffer 释放，单个失败不中断其余——全部尝试后统一抛出。
 *
 * <p><b>归还已关闭的 buffer 是错误</b>——{@link #release} 抛异常。
 * 静默忽略会掩盖调用方的生命周期错误。
 *
 * <h2>VAO 绑定</h2>
 *
 * <p>池不管 attribute 布局——借出后调用方调
 * {@link GLInstanceBuffer#bindTo} 绑到目标 VAO。归还时不改 VAO，
 * 下次借出时调用方会重绑。
 *
 * <h2>线程契约</h2>
 *
 * <p>所有方法必须在 GL 线程调用。VBO 的整个生命周期在 GL 线程内——
 * 不存在跨线程释放。
 */
public final class GLInstanceBufferPool implements AutoCloseable {

    private final int strideBytes;
    private final int instancesPerBuffer;
    private final int idleCapacity;

    private final Object lock = new Object();
    private final Deque<GLInstanceBuffer> idle = new ArrayDeque<>();
    private volatile boolean closed = false;

    /**
     * @param strideBytes        每实例字节数
     * @param instancesPerBuffer 每 buffer 的实例容量（固定）
     * @param idleCapacity       空闲缓存上限（软上限）——不是借出上限。
     *                           峰值后 idle 自动回落到此数。
     */
    public GLInstanceBufferPool(int strideBytes,
                                int instancesPerBuffer,
                                int idleCapacity) {
        if (strideBytes < 1) {
            throw new IllegalArgumentException(
                    "strideBytes must be >= 1: " + strideBytes);
        }
        if (instancesPerBuffer < 1) {
            throw new IllegalArgumentException(
                    "instancesPerBuffer must be >= 1: " + instancesPerBuffer);
        }
        if (idleCapacity < 0) {
            throw new IllegalArgumentException(
                    "idleCapacity must be >= 0: " + idleCapacity);
        }
        this.strideBytes = strideBytes;
        this.instancesPerBuffer = instancesPerBuffer;
        this.idleCapacity = idleCapacity;
    }

    /**
     * 借一个实例缓冲。优先复用空闲的；池空则新建。
     *
     * <p><b>无借出上限</b>——峰值需要多少就给多少。借出的 buffer 归
     * 调用方所有，用后必须 {@link #release} 归还。
     *
     * @return 可用的 buffer
     * @throws IllegalStateException 池已关闭
     */
    public GLInstanceBuffer acquire() {
        if (closed) {
            throw new IllegalStateException("pool is closed");
        }

        GLInstanceBuffer b = idle.pollFirst();
        if (b != null) {
            return b;
        }
        return GLInstanceBuffer.create(strideBytes, instancesPerBuffer);
    }

    /**
     * 归还实例缓冲。idle 未满则缓存；已满或已关闭则直接释放。
     *
     * <p><b>不允许归还已关闭的 buffer</b>——那说明调用方的生命周期
     * 管理出错，早暴露早修。{@code null} 同样拒绝——调用方应自行保证
     * 传进来的是有效 buffer。
     *
     * <p><b>线程契约</b>：GL 线程。
     *
     * @throws IllegalArgumentException buffer 为 null 或 stride 与池不一致
     * @throws IllegalStateException    buffer 已关闭
     */
    public void release(GLInstanceBuffer buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (buffer.isClosed()) {
            throw new IllegalStateException(
                    "cannot release a closed buffer");
        }
        if (buffer.getStrideBytes() != strideBytes) {
            throw new IllegalArgumentException(
                    "stride mismatch: pool=" + strideBytes
                            + ", buffer=" + buffer.getStrideBytes());
        }

        boolean store = false;
        synchronized (lock) {
            if (!closed && idle.size() < idleCapacity) {
                idle.addFirst(buffer);
                store = true;
            }
        }
        if (!store) {
            buffer.close();
        }
    }

    /**
     * 关闭池。释放所有空闲 buffer，标记关闭。
     *
     * <p><b>不可重入</b>——重复调用抛异常。
     *
     * <p><b>释放策略</b>：逐 buffer 释放，单个失败不中断其余——
     * 全部尝试后统一抛出。第一个异常作为主异常，后续失败以
     * {@code suppressed} 附加。
     *
     * <p>已借出的 buffer 不受影响——归还时由 {@link #release} 处理。
     *
     * <p><b>线程契约</b>：GL 线程。
     *
     * @throws IllegalStateException 池已关闭
     * @throws RuntimeException      一个或多个 buffer 释放失败
     */
    @Override
    public void close() {
        List<GLInstanceBuffer> toRelease;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("pool is already closed");
            }
            closed = true;
            toRelease = new ArrayList<>(idle);
            idle.clear();
        }

        Throwable firstFailure = null;
        for (GLInstanceBuffer b : toRelease) {
            try {
                b.close();
            } catch (Throwable t) {
                if (firstFailure == null) {
                    firstFailure = t;
                } else {
                    firstFailure.addSuppressed(t);
                }
            }
        }
        if (firstFailure != null) {
            throw new RuntimeException(
                    "failed to close one or more instance buffers "
                            + "(count=" + toRelease.size() + ")",
                    firstFailure);
        }
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** 每实例字节数。 */
    public int getStrideBytes()         { return strideBytes; }

    /** 每 buffer 的实例容量。 */
    public int getInstancesPerBuffer()  { return instancesPerBuffer; }

    /** 空闲缓存上限。 */
    public int getIdleCapacity()        { return idleCapacity; }

    /** 当前空闲 buffer 数。 */
    public int getIdleCount()           { synchronized (lock) { return idle.size(); } }

    /** 是否已关闭。 */
    public boolean isClosed()           { return closed; }
}