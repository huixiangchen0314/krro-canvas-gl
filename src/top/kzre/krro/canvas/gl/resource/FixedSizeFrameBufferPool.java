package top.kzre.krro.canvas.gl.resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 固定尺寸的 FBO 池。装饰 {@link FixedSizeTexturePool}，把池中
 * 纹理的每一层包装为一个 {@link GLFramebuffer}。
 *
 * <p><b>按需创建</b>：构造时不占用任何纹理。{@link #acquire()} 优先
 * 从已有的 {@link TextureSlot} 取空闲 FBO；全部占用时才从 texturePool
 * 借一张新纹理，创建新的 slot。
 *
 * <p><b>容量</b>：{@code capacity = texturePool.getCapacity()
 * × texturePool.getLayers()}。slot 数上限 = {@code texturePool.getCapacity()}。
 * 达到上限且全部 slot 都无空闲时，{@code acquire} 抛异常。
 *
 * <p><b>装饰语义</b>：构造时接管 texturePool 的所有权——本池关闭
 * 时一并关闭 texturePool。调用方不应在构造后再使用 texturePool。
 *
 * <p><b>关闭语义</b>：
 * <ul>
 *   <li>释放所有 FBO</li>
 *   <li>归还全部纹理给 texturePool</li>
 *   <li>关闭 texturePool</li>
 *   <li>有 FBO 借出未归还时抛异常</li>
 *   <li>重复 {@code close()} 抛异常</li>
 * </ul>
 *
 * <p><b>线程契约</b>：{@code acquire} / {@code release} 无 GL 调用
 * （FBO 在 slot 创建时已就绪），可在任意线程。{@code close} 涉及
 * GL 调用，通过 delegate 投递到 GL 线程。
 */
public final class FixedSizeFrameBufferPool implements GLFrameBufferPool, AutoCloseable {

    private final int                  maxSlots;
    private final int                  capacity;
    private final FixedSizeTexturePool texturePool;

    /** 已创建的 slot。按需增长，上限 {@code maxSlots}。 */
    private final List<TextureSlot> slots = new ArrayList<>();

    /** 借出中的 FBO 计数。用于 close 前置检查，避免遍历。 */
    private int activeCount = 0;

    private final Object lock = new Object();
    private volatile boolean closed = false;

    /**
     * 装饰 texturePool。
     *
     * <p><b>所有权转移</b>：构造后 texturePool 归本池所有，
     * 调用方不再持有其引用或关闭它。
     *
     * @param texturePool 多层纹理的池
     * @throws IllegalArgumentException texturePool 为 null
     */
    public FixedSizeFrameBufferPool(FixedSizeTexturePool texturePool) {
        if (texturePool == null) {
            throw new IllegalArgumentException("texturePool must not be null");
        }
        this.texturePool = texturePool;
        this.maxSlots    = texturePool.getCapacity();
        this.capacity    = maxSlots * texturePool.getLayers();
    }

    // ═══════════════════════════════════════════════
    // 借用
    // ═══════════════════════════════════════════════

    /**
     * 获取一个空闲 FBO。使用后必须
     * {@link #release(GLFramebuffer)} 归还。
     *
     * <p>优先复用已有 slot 的空闲 FBO；全部占用时从 texturePool
     * 借一张新纹理创建 slot。达到 slot 数上限且全部占用时抛异常。
     *
     * @throws IllegalStateException 池已关闭，或没有空闲 FBO
     */
    public GLFramebuffer acquire() {
        if (closed) {
            throw new IllegalStateException("pool is closed");
        }

        synchronized (lock) {

            // 优先复用已有 slot
            for (TextureSlot slot : slots) {
                GLFramebuffer fbo = slot.tryAcquire();
                if (fbo != null) {
                    activeCount++;
                    return fbo;
                }
            }

            // 全部占用——尝试创建新 slot
            if (slots.size() >= maxSlots) {
                throw new IllegalStateException(
                        "no idle framebuffer: all " + capacity + " are in use");
            }

            GLTexture tex = texturePool.acquire();
            TextureSlot slot = new TextureSlot(tex);
            slots.add(slot);
            activeCount++;
            return slot.tryAcquire();
        }
    }

    // ═══════════════════════════════════════════════
    // 归还
    // ═══════════════════════════════════════════════

    /**
     * 归还 FBO。池未关闭时放回所属 slot；已关闭时静默忽略。
     *
     * <p>不属于本池的、已释放的、null 的 FBO 都静默忽略。
     */
    @Override
    public void release(GLFramebuffer framebuffer) {
        if (framebuffer == null) return;
        if (framebuffer.isReleased()) return;

        // closed 是 volatile——读无需加锁。快速路径。
        if (closed) {
            throw new IllegalStateException("pool is closed");
        }

        synchronized (lock) {
            for (TextureSlot slot : slots) {
                if (slot.owns(framebuffer)) {
                    slot.release(framebuffer);
                    activeCount--;
                    return;
                }
            }
            throw new IllegalArgumentException(
                    "framebuffer does not belong to this pool");
        }
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 关闭池。释放所有 FBO，归还全部纹理，关闭 texturePool。
     *
     * <p><b>不可重入</b>：重复调用抛异常。
     *
     * <p><b>前置条件</b>：所有 FBO 都已归还。有借出时抛异常。
     *
     * <p>锁外调用 GL 释放动作——缩短临界区。
     *
     * @throws IllegalStateException 池已关闭，或仍有 FBO 借出
     */
    @Override
    public void close() {

        List<TextureSlot> toClose;
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("pool is already closed");
            }
            if (activeCount > 0) {
                throw new IllegalStateException(
                        "cannot close: " + activeCount
                                + " framebuffer(s) still in use");
            }
            closed = true;
            toClose = new ArrayList<>(slots);
            slots.clear();
        }

        for (TextureSlot slot : toClose) {
            slot.releaseAll();
            texturePool.release(slot.texture());
        }
        texturePool.close();
    }

    // ═══════════════════════════════════════════════
    // 状态
    // ═══════════════════════════════════════════════

    /** 池的理论容量 = slot 数上限 × 每张纹理的层数。 */
    public int getCapacity() { return capacity; }

    /** 已创建的 slot 数。 */
    public int getSlotCount() {
        synchronized (lock) {
            return slots.size();
        }
    }

    /** 空闲 FBO 总数。 */
    public int getIdleCount() {
        synchronized (lock) {
            int n = 0;
            for (TextureSlot slot : slots) {
                n += slot.idleCount();
            }
            return n;
        }
    }

    /** 借出中的 FBO 数。 */
    public int getActiveCount() {
        synchronized (lock) {
            return activeCount;
        }
    }

    public boolean isClosed() { return closed; }

    // ═══════════════════════════════════════════════
    // 内部类：一张纹理 + 它的全部 FBO
    // ═══════════════════════════════════════════════

    /**
     * 一个纹理槽位。管一张多层纹理，为每层创建一个 FBO。
     */
    private final static class TextureSlot {

        private final GLTexture                 texture;
        private final List<GLFramebuffer>       fbos;
        private final Deque<GLFramebuffer>      idle;

        TextureSlot(GLTexture texture) {
            this.texture = texture;
            int layers = texture.getLayers();

            List<GLFramebuffer> list = new ArrayList<>(layers);
            for (int i = 0; i < layers; i++) {
                list.add(GLFramebuffer.wrap(texture, i));
            }
            this.fbos = list;
            this.idle = new ArrayDeque<>(list);
        }

        GLTexture texture() { return texture; }

        /** 取一个空闲 FBO，没有返回 null。 */
        GLFramebuffer tryAcquire() {
            return idle.pollFirst();
        }

        /** 归还 FBO。 */
        void release(GLFramebuffer fbo) {
            idle.addFirst(fbo);
        }

        /** 释放本 slot 的全部 FBO（不管借出与否）。关闭时使用。 */
        void releaseAll() {
            for (GLFramebuffer fbo : fbos) {
                fbo.release();
            }
        }

        boolean owns(GLFramebuffer fbo) {
            // FBO 数量 = layers，通常很小。线性查找够用。
            return fbos.contains(fbo);
        }

        int idleCount() {
            return idle.size();
        }
    }
}