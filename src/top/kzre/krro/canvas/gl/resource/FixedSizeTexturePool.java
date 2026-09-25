package top.kzre.krro.canvas.gl.resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 固定尺寸的纹理池。
 *
 * <p><b>尺寸和格式在构造时确定</b>，之后所有借用都基于这个组合。
 * 池中所有纹理共享宽、高、层数、像素格式。
 *
 * <p><b>容量</b>：最多缓存 {@code capacity} 个空闲纹理。归还时
 * 如果已满，多余的直接交给 delegate 释放。
 *
 * <p><b>关闭语义</b>：
 * <ul>
 *   <li>{@link #close()} 释放所有空闲纹理并标记池为关闭状态</li>
 *   <li>关闭后 {@link #acquire()} 抛异常</li>
 *   <li>关闭后 {@link #release(GLTexture)} 直接交给 delegate 释放，
 *       不再缓存</li>
 *   <li>重复 {@code close()} 抛异常——非幂等，避免掩盖逻辑错误</li>
 * </ul>
 *
 * <p><b>线程契约</b>：{@code acquire} 涉及 GL 调用，必须在 GL 线程。
 * {@code release} 和 {@code close} 可在任意线程，实际释放由 delegate
 * 投递到 GL 线程。
 */
public final class FixedSizeTexturePool implements GLTexturePool, AutoCloseable {

    private final int         capacity;
    private final int         width;
    private final int         height;
    private final int         layers;
    private final PixelFormat pixelFormat;
    private final NoGLTexturePool delegate;

    /** 内部锁。避免用 {@code synchronized (this)} 被外部干扰。 */
    private final Object lock = new Object();

    /** 空闲纹理栈。LIFO——优先复用最近归还的。 */
    private final Deque<GLTexture> idle = new ArrayDeque<>();

    /** 关闭标记。volatile——读无需加锁。 */
    private volatile boolean closed = false;

    public FixedSizeTexturePool(int capacity,
                                int width, int height, int layers,
                                PixelFormat pixelFormat,
                                NoGLTexturePool delegate) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        if (width <= 0 || height <= 0 || layers <= 0) {
            throw new IllegalArgumentException(
                    "invalid size: " + width + "x" + height + "x" + layers);
        }
        if (pixelFormat == null) {
            throw new IllegalArgumentException("pixelFormat must not be null");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.capacity    = capacity;
        this.width       = width;
        this.height      = height;
        this.layers      = layers;
        this.pixelFormat = pixelFormat;
        this.delegate    = delegate;
    }

    // ═══════════════════════════════════════════════
    // 借用
    // ═══════════════════════════════════════════════

    /**
     * 获取一个纹理。优先复用空闲的；没有空闲时按池的尺寸和格式新建。
     *
     * @return 可用的纹理，所有权归调用方
     * @throws IllegalStateException 池已关闭
     */
    public GLTexture acquire() {
        if (closed) {
            throw new IllegalStateException("pool is closed");
        }


        synchronized (lock) {
            while (!idle.isEmpty()) {
                GLTexture t = idle.pollFirst();
                if (t != null && !t.isReleased()) {
                    return t;
                }
            }
        }

        long theadId = delegate.getGlExecutor().getTheadId();
        if(Thread.currentThread().getId() != theadId){
            throw new IllegalStateException("texture acquire must be called in gl thread, when no idle rest.");
        }

        return GLTexture.create(width, height, layers, pixelFormat);
    }

    // ═══════════════════════════════════════════════
    // 归还
    // ═══════════════════════════════════════════════

    /**
     * 归还纹理。
     *
     * <p>池未关闭时：空闲容量未满则缓存，已满则交给 delegate 释放。
     * 池已关闭时：直接交给 delegate 释放，不缓存。
     *
     * <p>null 或已释放的纹理静默忽略。
     */
    @Override
    public void release(GLTexture texture) {
        if (texture == null || texture.isReleased()) return;

        checkTexture(texture);

        // 快速路径：已关闭，直接释放。closed 是 volatile，无需加锁。
        if (closed) {
            delegate.release(texture);
            return;
        }

        // 慢路径：尝试缓存
        boolean store = false;
        synchronized (lock) {
            if (!closed && idle.size() < capacity) {
                idle.addFirst(texture);
                store = true;
            }
        }
        if (!store) {
            delegate.release(texture);
        }
    }

    /**
     * 校验纹理的尺寸和格式与池的配置一致。不匹配抛异常。
     *
     * <p>投递错误尺寸的纹理是程序员的逻辑 bug——池只服务固定尺寸
     * 的纹理，其他尺寸进来就是误用。早暴露早修。
     *
     * @throws IllegalArgumentException 尺寸或格式不匹配
     */
    private void checkTexture(GLTexture texture) {
        if (texture.getWidth()  != width
                || texture.getHeight() != height
                || texture.getLayers() != layers) {
            throw new IllegalArgumentException(
                    "texture size mismatch: pool=" + width + "x" + height + "x" + layers
                            + ", texture=" + texture.getWidth() + "x"
                            + texture.getHeight() + "x" + texture.getLayers());
        }
        if (!pixelFormat.equals(texture.getPixelFormat())) {
            throw new IllegalArgumentException(
                    "texture format mismatch: pool=" + pixelFormat
                            + ", texture=" + texture.getPixelFormat());
        }
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 关闭池。释放所有空闲纹理，标记为关闭状态。
     *
     * <p><b>不可重入</b>：重复调用抛异常。这是有意的——静默幂等
     * 会掩盖调用方的逻辑错误（比如两个地方都在关闭同一个池）。
     *
     * <p>已借出的纹理不释放，所有权在借用方。借用方归还时由
     * {@link #release(GLTexture)} 直接交给 delegate 释放。
     *
     * <p>锁外调用 {@code delegate.release}——缩短临界区。
     *
     * @throws IllegalStateException 池已关闭
     */
    @Override
    public void close() {
        List<GLTexture> toRelease;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("pool is already closed");
            }
            closed = true;
            toRelease = new ArrayList<>(idle);
            idle.clear();
        }
        for (GLTexture t : toRelease) {
            delegate.release(t);
        }
    }

    // ═══════════════════════════════════════════════
    // 状态
    // ═══════════════════════════════════════════════

    public int getIdleCount() {
        synchronized (lock) {
            return idle.size();
        }
    }

    /** 是否已关闭。volatile 读，无需加锁。 */
    public boolean isClosed() { return closed; }

    public int         getCapacity()    { return capacity; }
    public int         getWidth()       { return width; }
    public int         getHeight()      { return height; }
    public int         getLayers()      { return layers; }
    public PixelFormat getPixelFormat() { return pixelFormat; }
}