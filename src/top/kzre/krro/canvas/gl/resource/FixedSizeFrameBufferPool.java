package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.core.util.SerialExecutor;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 固定尺寸的 FBO 池。装饰 {@link FixedSizeTexturePool}，把池中
 * 纹理的每一层包装为一个 {@link GLFramebuffer}。
 *
 * <p><b>按需创建，峰值后回收</b>：每次 {@link #acquire()} 优先从
 * 已有 slot 取空闲 FBO；全部占用时借一张新纹理创建新 slot。归还
 * 时如果某个 slot 的全部 FBO 都空闲，该 slot 从池中移除，纹理
 * 归还 texturePool。slot 数跟随实际并发持有量动态升降。
 *
 * <p><b>延迟关闭</b>：{@link #close()} 时——texturePool 立即关闭，
 * 全空闲的 slot 立即释放（归还纹理 + 关闭 FBO）；仍有 FBO 借出的
 * slot 由 {@link DeferredClose} 接管，等这些 FBO 全部归还后释放。
 * 已借出的 FBO 在使用期间其底层纹理不会被提前释放。
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

    /**
     * 延迟关闭器。close 时若仍有 FBO 借出——此字段非 null，
     * 全部归还后触发实际释放并置回 null。
     */
    private DeferredClose deferredClose = null;

    private final Object lock = new Object();

    /**
     * 持久保存的 fbo 数量
     */
    private final int capacity;

    /**
     * 持久保存的 slot, 每个slot是一张纹理
     */
    private final int slotCapacity;

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
        this.slotCapacity = texturePool.getCapacity();
        if (slotCapacity < 2) {
            throw new IllegalArgumentException("slotCapacity must be >= 2");
        }
        this.capacity = texturePool.getCapacity() * texturePool.getLayers();
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
     * @throws IllegalStateException 池已进入关闭流程
     */
    public GLFramebuffer acquire() {
        requireGlThread();

        synchronized (lock) {
            requireNotClosing();

            for (TextureSlot slot : slots) {
                GLFramebuffer fbo = slot.tryAcquire();
                if (fbo != null) {
                    return fbo;
                }
            }

            GLTexture tex = texturePool.acquire();
            TextureSlot slot = TextureSlot.create(tex);
            slots.add(slot);
            return slot.tryAcquire();
        }
    }

    /**
     * 从两个不同的纹理各取一个空闲 FBO。
     *
     * <p>等价于 {@link #acquire2IdleExcluding(Set) acquire2IdleExcluding(null)}。
     */
    public GLFramebuffer[] acquire2Idle() {
        return acquire2IdleExcluding(null);
    }

    /**
     * 从两个不同的纹理各取一个空闲 FBO，并排除指定纹理。
     *
     * <p><b>用途</b>：ping-pong 合成需要两张颜色附件不同的 FBO——
     * draw i 写其中一张、读另一张。同一张纹理的不同 layer 共享同一
     * 颜色附件，不能满足要求。
     *
     * <p><b>只复用不扩容</b>：本方法仅从已有 slot 的空闲队列取 FBO。
     * 不足时抛异常——不尝试借新纹理。调用方应先通过
     * {@code ensureSlotsWithIdleAtLeastExcluding(2, 1, excluded)} 补足
     * 容量，成功后再调用本方法。
     *
     * <p><b>线程契约</b>：不涉及 GL 调用——可在任意线程执行。
     * 但返回的两个 FBO 后续使用（绑定、绘制、释放）必须在 GL 线程上。
     *
     * <p><b>契约</b>：返回的两个 FBO 颜色附件保证是不同纹理对象，
     * 且都不在 {@code excludedTextures} 中。使用后必须分别
     * {@link #release(GLFramebuffer)} 归还。
     *
     * @param excludedTextures 需要排除的纹理集合，null 表示不排除
     * @return 两个来自不同纹理的空闲 FBO
     * @throws IllegalStateException 空闲 FBO 不足两个（未先 ensure），
     *                               或来自两个不同纹理的空闲 FBO 不足，
     *                               或池已进入关闭流程
     */
    public GLFramebuffer[] acquire2IdleExcluding(Set<GLTexture> excludedTextures) {
        synchronized (lock) {
            requireNotClosing();

            GLFramebuffer a = null;
            GLFramebuffer b = null;

            for (TextureSlot slot : slots) {
                if (excludedTextures != null
                        && excludedTextures.contains(slot.texture())) {
                    continue;
                }
                GLFramebuffer fbo = slot.tryAcquire();
                if (fbo == null) continue;

                if (a == null) {
                    a = fbo;
                } else {
                    b = fbo;
                    break;
                }
            }

            if (a == null || b == null) {
                if (a != null) {
                    for (TextureSlot slot : slots) {
                        if (slot.owns(a)) {
                            slot.release(a);
                            break;
                        }
                    }
                }
                throw new IllegalStateException(
                        "acquire2IdleExcluding: fewer than 2 slots with idle FBO; "
                                + "call ensureSlotsWithIdleAtLeastExcluding(2, 1, excluded) first");
            }

            return new GLFramebuffer[]{ a, b };
        }
    }

    // ═══════════════════════════════════════════════
    // 归还
    // ═══════════════════════════════════════════════

    /**
     * 归还 FBO。
     *
     * <p><b>正常路径</b>：slot 全部空闲时不一定回收——只有当
     * {@code slots.size() > slotCapacity} 时才移除最尾部的空闲 slot，
     * 归还其纹理给 texturePool。低水位以下的空闲 slot 常驻——保留
     * 已经创建好的 FBO，避免下次 acquire 又走 glGenFramebuffers。
     *
     * <p><b>关闭路径</b>：{@link #close()} 已调用且仍有借出——由
     * {@link DeferredClose} 接管。本方法递减其剩余计数，归零时触发
     * 所有 pending slot 的释放。
     *
     * <p>不属于本池的 FBO 抛异常——静默忽略会导致 FBO 永久泄漏。
     * null 或已释放的 FBO 静默忽略（幂等归还）。
     */
    @Override
    public void release(GLFramebuffer framebuffer) {
        if (framebuffer == null) return;
        if (framebuffer.isReleased()) return;

        TextureSlot slotToClose = null;
        DeferredClose closer = null;
        boolean found = false;

        synchronized (lock) {
            if (deferredClose != null) {
                // ── 关闭中——由 closer 接管 ──
                if (!deferredClose.owns(framebuffer)) {
                    throw new IllegalArgumentException(
                            "framebuffer does not belong to this pool");
                }
                found = true;
                if (deferredClose.onRelease()) {
                    closer = deferredClose;
                    deferredClose = null;
                }
            } else {
                // ── 正常路径 ──
                for (int i = 0; i < slots.size(); i++) {
                    TextureSlot slot = slots.get(i);
                    if (!slot.owns(framebuffer)) continue;

                    found = true;
                    slot.release(framebuffer);

                    if (slot.isFullyIdle() && slots.size() > slotCapacity) {
                        texturePool.release(slot.texture());
                        slotToClose = slots.remove(i);
                    }
                    break;
                }
            }
        }

        if (!found) {
            throw new IllegalArgumentException(
                    "framebuffer does not belong to this pool");
        }

        if (slotToClose != null) {
            // 归还单个 slot——释放其 FBO
            List<TextureSlot> singleton = Collections.singletonList(slotToClose);
            ExecutorUtils.submitIfThread(getGlExecutor(), () ->
                    ResourceUtils.closeDelayError(singleton));
        }

        if (closer != null) {
            releaseSlots(closer.pendingSlots);
        }
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 关闭池。
     *
     * <p><b>texturePool 立即关闭</b>——它自身处理"关闭后归还"的语义，
     * 后续归还给它的纹理仍会正常释放。
     *
     * <p><b>全空闲的 slot 立即释放</b>——归还纹理、关闭 FBO。
     *
     * <p><b>有借出的 slot 延迟释放</b>——由 {@link DeferredClose} 接管，
     * 等这些 FBO 全部归还后释放。
     *
     * <p><b>非幂等</b>：重复调用抛异常。
     */
    @Override
    public void close() {
        List<TextureSlot> immediate;
        List<TextureSlot> pending;

        synchronized (lock) {
            if (deferredClose != null) {
                throw new IllegalStateException(
                        "pool already closed or closing");
            }

            immediate = new ArrayList<>();
            pending  = new ArrayList<>();
            for (TextureSlot slot : slots) {
                if (slot.isFullyIdle()) {
                    immediate.add(slot);
                } else {
                    pending.add(slot);
                }
            }
            slots.clear();

            if (!pending.isEmpty()) {
                int outstanding = 0;
                for (TextureSlot slot : pending) {
                    outstanding += slot.outstanding();
                }
                deferredClose = new DeferredClose(pending, outstanding);
            }
        }

        // 全空闲的 slot 立即释放
        if (!immediate.isEmpty()) {
            releaseSlots(immediate);
        }

        // 纹理池立即关闭——不再接受新的 acquire，但归还仍有效
        texturePool.close();
    }

    /**
     * 释放一组 slot——归还纹理给 texturePool、在 GL 线程上关闭 FBO。
     *
     * <p>texturePool 可能已关闭——其 release 语义允许关闭后归还，
     * 会直接释放对应纹理。
     */
    private void releaseSlots(List<TextureSlot> toRelease) {
        if (toRelease.isEmpty()) return;

        for (TextureSlot slot : toRelease) {
            try {
                texturePool.release(slot.texture());
            } catch (RuntimeException ignored) {
                // 单个纹理归还失败不中断——继续处理其他 slot
            }
        }

        ExecutorUtils.submitIfThread(getGlExecutor(), () ->
                ResourceUtils.closeDelayError(toRelease));
    }

    /**
     * 池是否已关闭。转发自底层 {@link FixedSizeTexturePool}。
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
     * 统计空闲 FBO 数不低于 {@code leastIdle}、且纹理不在排除集合中的
     * slot 数量。
     */
    private int countSlotsWithIdleAtLeastExcluding(int leastIdle,
                                                   Set<GLTexture> excludedTextures) {
        synchronized (lock) {
            int n = 0;
            for (TextureSlot slot : slots) {
                if (excludedTextures != null
                        && excludedTextures.contains(slot.texture())) {
                    continue;
                }
                if (slot.idleCount() >= leastIdle) {
                    n++;
                }
            }
            return n;
        }
    }

    /**
     * 确保至少有 {@code count} 个 slot 满足"空闲 FBO 数 >= leastIdle"，
     * 且纹理不在 {@code excludedTextures} 中。不足则借新纹理创建新 slot。
     *
     * <p><b>快速路径</b>：已满足时返回已完成的 future，无 GL 调度。
     *
     * <p><b>慢速路径</b>：提交到 GL 线程创建新 slot。GL 线程上的二次
     * 检查保证并发调用不会超量创建。
     *
     * @throws IllegalStateException 池已进入关闭流程
     */
    public CompletableFuture<Void> ensureSlotsWithIdleAtLeastExcluding(
            int count,
            int leastIdle,
            Set<GLTexture> excludedTextures) {

        synchronized (lock) {
            requireNotClosing();
        }

        int currentIdle = countSlotsWithIdleAtLeastExcluding(leastIdle, excludedTextures);
        if (currentIdle >= count) {
            return CompletableFuture.completedFuture(null);
        }

        SerialExecutor glExecutor = texturePool.getGlExecutor();

        return ExecutorUtils.submitIfThread(glExecutor,
                () -> {
                    synchronized (lock) {
                        if (deferredClose != null) return;
                    }

                    int nowIdle = countSlotsWithIdleAtLeastExcluding(leastIdle, excludedTextures);
                    int need = count - nowIdle;

                    for (int i = 0; i < need; i++) {
                        GLTexture tex = texturePool.acquire();
                        TextureSlot slot = TextureSlot.create(tex);

                        synchronized (lock) {
                            if (deferredClose != null) {
                                // 关闭流程启动——刚创建的 slot 立即释放
                                texturePool.release(slot.texture());
                                slot.close();
                                return;
                            }
                            slots.add(slot);
                        }
                    }
                });
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return texturePool.getGlExecutor();
    }

    /** 必须在 {@link #lock} 内调用。 */
    private void requireNotClosing() {
        if (deferredClose != null) {
            throw new IllegalStateException("pool is closing");
        }
    }

    // ═══════════════════════════════════════════════
    // 内部类
    // ═══════════════════════════════════════════════

    /**
     * 延迟关闭器。{@link #close()} 时仍有 FBO 借出时创建——接管这些
     * slot，等待剩余借出归还。归零时由 {@link #release(GLFramebuffer)}
     * 触发释放。
     */
    private static final class DeferredClose {

        final List<TextureSlot> pendingSlots;
        private int remaining;

        DeferredClose(List<TextureSlot> pendingSlots, int remaining) {
            this.pendingSlots = pendingSlots;
            this.remaining = remaining;
        }

        /**
         * 递减剩余借出计数。返回 {@code true} 表示全部归还——由调用方
         * 触发释放。在 pool 的 {@code lock} 内调用。
         */
        boolean onRelease() {
            remaining--;
            return remaining == 0;
        }

        /** 判断 framebuffer 是否属于接管的 slot 之一。在 {@code lock} 内调用。 */
        boolean owns(GLFramebuffer fb) {
            for (TextureSlot slot : pendingSlots) {
                if (slot.owns(fb)) return true;
            }
            return false;
        }
    }

    /**
     * 一个纹理槽位。管一张多层纹理，为每层创建一个 FBO。
     */
    private final static class TextureSlot implements AutoCloseable {

        private final GLTexture texture;
        private final List<GLFramebuffer> fbos;
        private final Deque<GLFramebuffer> idle;
        private final int capacity;
        private TextureSlot(GLTexture texture,
                            List<GLFramebuffer> fbos,
                            Deque<GLFramebuffer> idle) {
            this.texture = texture;
            this.fbos = fbos;
            capacity = fbos.size();
            this.idle = idle;
        }

        private static TextureSlot create(GLTexture texture) {
            int layers = texture.getLayers();
            List<GLFramebuffer> list = new ArrayList<>(layers);
            for (int i = 0; i < layers; i++) {
                list.add(GLFramebuffer.wrap(texture, i));
            }
            return new TextureSlot(texture, list, new ArrayDeque<>(list));
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
            return idle.size() == capacity;
        }

        /** 借出中的 FBO 数。 */
        int outstanding() {
            return capacity - idle.size();
        }

        boolean owns(GLFramebuffer fbo) {
            return fbos.contains(fbo);
        }

        int idleCount() {
            return idle.size();
        }

        @Override
        public void close() {
            RuntimeException exception = ResourceUtils.closeDelayError(fbos);
            if (exception != null) {
                throw exception;
            }
        }
    }
}