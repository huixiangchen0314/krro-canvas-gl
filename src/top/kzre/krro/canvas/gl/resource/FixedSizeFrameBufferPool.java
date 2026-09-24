package top.kzre.krro.canvas.gl.resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 固定尺寸的 FBO 池。装饰 {@link FixedSizeTexturePool}，把池中
 * 纹理的每一层包装为一个 {@link GLFramebuffer}。
 *
 * <p><b>无容量上限</b>：FBO 是纹理层的借用视图，轻量。池本身不
 * 限制 FBO 数量——真正的资源约束在 {@link FixedSizeTexturePool}
 * 内部（缓存容量），而不是池的容量。
 *
 * <p><b>按需创建 slot</b>：每次 {@link #acquire()} 优先从已有的
 * {@link TextureSlot} 取空闲 FBO；全部占用时从 texturePool 借一张
 * 新纹理，创建新 slot。slot 数可能增长——它对应"用户同时持有的
 * 最大 FBO 数"。用户不归还导致的无界增长是内存泄漏，由用户负责。
 *
 * <p><b>slot 的角色</b>：slot 维持纹理和 FBO 的强引用，用于
 * {@link #close()} 时统一释放。归还的 FBO 回到所属 slot 的空闲
 * 队列，供后续 acquire 复用。
 *
 * <p><b>装饰语义</b>：构造时接管 texturePool 的所有权——本池关闭
 * 时一并关闭 texturePool。
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

    private final FixedSizeTexturePool texturePool;

    /** 已创建的 slot。按需增长，无上限。 */
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
    }

    // ═══════════════════════════════════════════════
    // 借用
    // ═══════════════════════════════════════════════

    /**
     * 获取一个空闲 FBO。使用后必须
     * {@link #release(GLFramebuffer)} 归还。
     *
     * <p>优先复用已有 slot 的空闲 FBO；全部占用时从 texturePool
     * 借一张新纹理创建新 slot。无容量上限——纹理池内部按需创建，
     * 真正约束在纹理池的缓存策略。
     *
     * @throws IllegalStateException 池已关闭
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

            // 全部占用——借新纹理，创建新 slot
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
     * 归还 FBO。池未关闭时放回所属 slot；已关闭时抛异常。
     *
     * <p>不属于本池的 FBO 抛异常——静默忽略会导致 FBO 永久泄漏
     * （另一个池永远收不到它）。
     */
    @Override
    public void release(GLFramebuffer framebuffer) {
        if (framebuffer == null) return;
        if (framebuffer.isReleased()) return;
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

    /** 已创建的 slot 数。对应"用户同时持有过的最大 FBO 数"。 */
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

        private final GLTexture            texture;
        private final List<GLFramebuffer>  fbos;
        private final Deque<GLFramebuffer> idle;

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

        GLFramebuffer tryAcquire() {
            return idle.pollFirst();
        }

        void release(GLFramebuffer fbo) {
            idle.addFirst(fbo);
        }

        void releaseAll() {
            for (GLFramebuffer fbo : fbos) {
                fbo.release();
            }
        }

        boolean owns(GLFramebuffer fbo) {
            return fbos.contains(fbo);
        }

        int idleCount() {
            return idle.size();
        }
    }
}