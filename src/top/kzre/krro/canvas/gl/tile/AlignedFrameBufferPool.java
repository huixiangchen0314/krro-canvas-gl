package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.FixedSizeFrameBufferPool;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.canvas.gl.util.NumberUtils;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 {@code tileSize} 对齐的 FBO 池持有者——{@link FixedSizeFrameBufferPool}
 * 的“对齐 + 重建”包装，不是独立的 GL 环境。
 *
 * <p><b>职责</b>：持有一个 {@link FixedSizeFrameBufferPool}，当请求尺寸
 * （对齐到 {@code tileSize} 后）变化时销毁旧池、创建新池。尺寸未变时
 * {@link #resize(int, int)} 是 no-op。
 *
 * <p><b>不拥有 GL 线程</b>：本类不实现 {@code GLThreadAffine}。GL 线程
 * 亲和性完全由内部的 {@link FixedSizeFrameBufferPool} 保证——调用方
 * 通过 {@link #getPool()} 拿到 pool 后再走它的 {@code getGlExecutor()}。
 *
 * <p><b>延迟初始化</b>：构造时不创建 GL 资源。首次
 * {@link #resize(int, int)} 时创建池。{@link #getPool()} 在池未创建时
 * 抛异常——调用方必须先 resize。
 *
 * <p><b>对齐</b>：{@code width} / {@code height} 向上对齐到
 * {@code tileSize} 的整数倍。{@link #needResize(int, int)} 和
 * {@link #resize(int, int)} 使用同一套对齐逻辑。
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>{@link #needResize(int, int)} —— 任意线程。纯 CPU 判断。</li>
 *   <li>{@link #resize(int, int)} —— <b>必须在 GL 线程调用</b>。
 *       本类不做线程检查——线程正确性由调用方保证（和
 *       {@link PoolFactory#create} 的契约一致）。</li>
 *   <li>{@link #getPool()} / {@link #getWidth()} / {@link #getHeight()} /
 *       {@link #isInitialized()} —— 任意线程。纯读 volatile 字段。</li>
 *   <li>{@link #close()} —— 任意线程。pool 未创建时直接标记关闭；
 *       pool 已创建时调度到 pool 的 GL 线程执行。</li>
 * </ul>
 *
 * <p><b>close 不可重入</b>：重复调用抛 {@link IllegalStateException}。
 */
public final class AlignedFrameBufferPool implements AutoCloseable {

    /**
     * 池工厂。
     *
     * <p><b>线程契约</b>：{@link #create(int, int)} 在 GL 线程调用。
     * 实现方不需要再做线程检查——调用方保证。
     */
    public interface PoolFactory {
        FixedSizeFrameBufferPool create(int width, int height);
    }

    private final PoolFactory factory;
    private final int tileSize;

    /** 当前池的对齐尺寸。volatile——任意线程可读。 */
    private volatile int width;
    private volatile int height;

    /** 当前池。volatile——GL 线程写，任意线程读。 */
    private volatile FixedSizeFrameBufferPool pool;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param tileSize 对齐粒度，必须 &gt; 0
     * @param factory  池工厂，不能为 null
     * @throws IllegalArgumentException 任一参数非法
     */
    public AlignedFrameBufferPool(int tileSize, PoolFactory factory) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.tileSize = tileSize;
        this.factory  = factory;
    }

    // ═══════════════════════════════════════════════
    // 尺寸判断（任意线程）
    // ═══════════════════════════════════════════════

    /**
     * 判断是否需要调整尺寸。
     *
     * <p><b>线程契约</b>：任意线程。纯数据读取，无 GL 调用。
     *
     * @param width  目标宽（像素），未对齐
     * @param height 目标高（像素），未对齐
     * @return true 表示需要调度 {@link #resize(int, int)}
     */
    public boolean needResize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        int alignedW = NumberUtils.alignUp(width,  tileSize);
        int alignedH = NumberUtils.alignUp(height, tileSize);
        return this.width != alignedW || this.height != alignedH;
    }

    // ═══════════════════════════════════════════════
    // 尺寸调整（GL 线程）
    // ═══════════════════════════════════════════════

    /**
     * 调整尺寸。尺寸（对齐后）变化时销毁旧池、创建新池；未变化时
     * no-op。
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。本类不做线程检查——
     * 线程正确性由调用方保证（{@link PoolFactory#create} 的契约是
     * “在 GL 线程调用”）。
     *
     * <p><b>失败语义</b>：先创建新池再关旧池。{@code factory.create}
     * 失败时 {@code pool} / {@code width} / {@code height} 全部保持
     * 旧值，状态一致，调用方可重试。旧池关闭失败被吞掉——新池已
     * 可用，旧池的资源泄漏不应阻塞渲染。
     *
     * @throws IllegalStateException    池已关闭
     * @throws IllegalArgumentException width / height ≤ 0
     */
    public void resize(int width, int height) {
        if (closed.get()) {
            throw new IllegalStateException("pool is closed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "invalid size: " + width + "x" + height);
        }

        int alignedW = NumberUtils.alignUp(width,  tileSize);
        int alignedH = NumberUtils.alignUp(height, tileSize);

        if (this.width == alignedW && this.height == alignedH) {
            return;
        }

        FixedSizeFrameBufferPool old = this.pool;

        // 先创建新池——失败时旧状态保持不变
        this.pool   = factory.create(alignedW, alignedH);
        this.width  = alignedW;
        this.height = alignedH;

        // 后关旧池——失败静默吞掉，不阻塞新池
        if (old != null) {
            try {
                old.close();
            } catch (Throwable ex) {
                System.err.println("failed to close old gl thread pool: " + ex);
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 访问器（任意线程）
    // ═══════════════════════════════════════════════

    /**
     * 当前池。
     *
     * @throws IllegalStateException 池未初始化（从未调用 resize）
     */
    public FixedSizeFrameBufferPool getPool() {
        FixedSizeFrameBufferPool p = pool;
        if (p == null) {
            throw new IllegalStateException(
                    "pool not initialized; call resize() first");
        }
        return p;
    }

    /** 当前池的对齐宽度（像素）。未初始化时为 0。 */
    public int getWidth() {
        return width;
    }

    /** 当前池的对齐高度（像素）。未初始化时为 0。 */
    public int getHeight() {
        return height;
    }

    /** 池是否已初始化。 */
    public boolean isInitialized() {
        return pool != null;
    }

    /** 是否已关闭。 */
    public boolean isClosed() {
        return closed.get();
    }

    // ═══════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════

    /**
     * 关闭。释放当前池（如果有）。
     *
     * <p><b>线程契约</b>：任意线程。
     * <ul>
     *   <li>pool 未创建 → 直接标记关闭，无 GL 资源需要清理</li>
     *   <li>pool 已创建 → 调度到 pool 的 GL 线程执行清理</li>
     * </ul>
     *
     * <p><b>不可重入</b>：重复调用抛异常。
     *
     * @throws IllegalStateException 已关闭
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            throw new IllegalStateException("already closed");
        }
        FixedSizeFrameBufferPool p = this.pool;
        if (p == null) {
            return;   // 未初始化，无需调度
        }
        ExecutorUtils.runOnThread(p.getGlExecutor(), this::doClose);
    }

    /** 实际清理。只在 GL 线程上执行。 */
    private void doClose() {
        FixedSizeFrameBufferPool p = this.pool;
        if (p != null) {
            try {
                p.close();
            } catch (Throwable ignored) {
                System.err.println("AlignedFrameBufferPool closed failed");
            }
            this.pool = null;
        }
        this.width  = 0;
        this.height = 0;
    }
}