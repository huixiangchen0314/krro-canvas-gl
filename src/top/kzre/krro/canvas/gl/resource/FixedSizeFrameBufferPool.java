package top.kzre.krro.canvas.gl.resource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 固定尺寸的 FBO 池。装饰 {@link FixedSizeTexturePool}，把池中
 * 纹理的每一层包装为一个 {@link GLFramebuffer}。
 *
 * <p><b>按需创建，峰值后回收</b>：每次 {@link #acquire()} 优先从
 * 已有 slot 取空闲 FBO；全部占用时借一张新纹理创建新 slot。归还
 * 时如果某个 slot 的全部 FBO 都空闲，该 slot 从池中移除，纹理
 * 归还 texturePool。slot 数跟随实际并发持有量动态升降。
 *
 * <p><b>无容量上限</b>：真正的资源约束在 {@link FixedSizeTexturePool}
 * 的缓存策略。slot 数可能增长到"用户同时持有的最大 FBO 数"——
 * 用户不归还导致的无界增长是用户侧的内存泄漏。
 *
 * <p><b>无自有生命周期</b>：本池只是 texturePool 之上的视图层。
 * {@link #close()} 转发给 texturePool，不拒绝用户归还。已借出的
 * FBO 在归还时归还纹理——如果 texturePool 已关闭，纹理池会直接
 * 释放它。
 *
 * <p><b>线程契约</b>：{@code acquire} / {@code release} 涉及
 * texturePool 的借还，但 texturePool 的操作不调 GL（GL 释放由
 * delegate 投递），所以可在任意线程。{@code close} 转发给
 * texturePool，其 GL 释放也由 delegate 处理。
 */
public final class FixedSizeFrameBufferPool implements GLFrameBufferPool, AutoCloseable {

    private final FixedSizeTexturePool texturePool;

    /** 已创建的 slot。随 acquire/release 动态增减。 */
    private final List<TextureSlot> slots = new ArrayList<>();

    /** 借出中的 FBO 计数。 */
    private int activeCount = 0;

    private final Object lock = new Object();

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
     * 借一张新纹理创建新 slot。
     *
     * @throws IllegalStateException texturePool 已关闭
     */
    public GLFramebuffer acquire() {
        synchronized (lock) {
            for (TextureSlot slot : slots) {
                GLFramebuffer fbo = slot.tryAcquire();
                if (fbo != null) {
                    activeCount++;
                    return fbo;
                }
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
     * 归还 FBO。
     *
     * <p>归还后如果所属 slot 的全部 FBO 都空闲，该 slot 从池中移除，
     * 纹理归还 texturePool——峰值过后 slot 数回落。
     *
     * <p>不属于本池的 FBO 抛异常——静默忽略会导致 FBO 永久泄漏。
     * null 或已释放的 FBO 静默忽略（幂等归还）。
     */
    @Override
    public void release(GLFramebuffer framebuffer) {
        if (framebuffer == null) return;
        if (framebuffer.isReleased()) return;

        synchronized (lock) {
            for (int i = 0; i < slots.size(); i++) {
                TextureSlot slot = slots.get(i);
                if (slot.owns(framebuffer)) {
                    slot.release(framebuffer);
                    activeCount--;

                    // 全部空闲——移除 slot，归还纹理
                    if (slot.isFullyIdle()) {
                        slots.remove(i);
                        texturePool.release(slot.texture());
                    }
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
     * 关闭底层纹理池。释放 texturePool 中缓存的空闲纹理。
     *
     * <p><b>转发语义</b>：本池没有自有生命周期。已借出的 FBO 不受
     * 影响——它们在归还时归还纹理给 texturePool，如果 texturePool
     * 已关闭，纹理池会直接释放它。
     *
     * <p><b>幂等</b>：由 texturePool.close 的内部语义决定。
     */
    @Override
    public void close() {
        texturePool.close();
    }

    /**
     * 池是否已关闭。转发自底层 {@link FixedSizeTexturePool}。
     *
     * <p>本池没有独立于 texturePool 的生命周期。查询关闭状态等于
     * 查询 texturePool 的关闭状态。
     */
    public boolean isClosed() {
        return texturePool.isClosed();
    }

    // ═══════════════════════════════════════════════
    // 状态
    // ═══════════════════════════════════════════════

    /** 已创建的 slot 数。 */
    public int getSlotCount() {
        synchronized (lock) {
            return slots.size();
        }
    }

    /**
     * 当前可立即复用的空闲 FBO 数。
     *
     * <p><b>用途</b>：外部在调度 GL 线程前预判——空闲数足够时
     * {@link #acquire()} 从已有 slot 取，无 GL 调用；空闲数不足时
     * acquire 会借新纹理并 wrap 出 FBO，需要 GL 调度。
     *
     * <p><b>只统计已 wrap 的 FBO</b>：texturePool 里的空闲纹理虽然
     * 已在显存，但 wrap 成 FBO 需要 {@code glFramebufferTextureLayer}
     * 调用——不是立即可用的。所以不计入本数。
     *
     * <p><b>不是池持有的资源</b>：空闲 FBO 是 slot 内部的视图——
     * 反映的是"曾经借出、已归还、等待复用"的 FBO。
     *
     * <p><b>快照语义</b>：并发场景下数值会变化。
     */
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

        /** 全部 FBO 都空闲。 */
        boolean isFullyIdle() {
            return idle.size() == fbos.size();
        }

        boolean owns(GLFramebuffer fbo) {
            return fbos.contains(fbo);
        }

        int idleCount() {
            return idle.size();
        }
    }
}