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
     * @throws IllegalStateException texturePool 已关闭
     */
    public GLFramebuffer acquire() {
        requireGlThread();

        synchronized (lock) {
            for (TextureSlot slot : slots) {
                GLFramebuffer fbo = slot.tryAcquire();
                if (fbo != null) {
                    activeCount++;
                    return fbo;
                }
            }

            GLTexture tex = texturePool.acquire();
            TextureSlot slot = TextureSlot.create(tex);
            slots.add(slot);
            activeCount++;
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
     *                               或来自两个不同纹理的空闲 FBO 不足
     */
    public GLFramebuffer[] acquire2IdleExcluding(Set<GLTexture> excludedTextures) {
        synchronized (lock) {
            GLFramebuffer a = null;
            GLFramebuffer b = null;

            for (TextureSlot slot : slots) {
                if (excludedTextures != null
                        && excludedTextures.contains(slot.texture())) {
                    System.out.println("skip excluding texture: " + slot.texture()
                    + "excluded: " + excludedTextures.size()
                    + "slots size: " + slots.size());
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
                // 已取出的 a 归还，避免泄漏——b 为空时 a 已经离开 idle 队列
                if (a != null) {
                    // 找到 a 所属的 slot 归还
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

            activeCount += 2;
            return new GLFramebuffer[]{ a, b };
        }
    }

    // ═══════════════════════════════════════════════
    // 归还
    // ═══════════════════════════════════════════════

    /**
     * 归还 FBO。
     *
     * <p><b>低水位保留</b>：slot 全部空闲时不一定回收——只有当
     * {@code slots.size() > slotCapacity} 时才移除最尾部的空闲 slot，
     * 归还其纹理给 texturePool。低水位以下的空闲 slot 常驻——保留
     * 已经创建好的 FBO，避免下次 acquire 又走 glGenFramebuffers。
     *
     * <p>峰值过后 slot 数回落到 {@code slotCapacity}，不再继续缩水。
     *
     * <p>不属于本池的 FBO 抛异常——静默忽略会导致 FBO 永久泄漏。
     * null 或已释放的 FBO 静默忽略（幂等归还）。
     */
    @Override
    public void release(GLFramebuffer framebuffer) {
        if (framebuffer == null) return;
        if (framebuffer.isReleased()) return;

        TextureSlot slotToClose = null;
        boolean found = false;

        synchronized (lock) {
            for (int i = 0; i < slots.size(); i++) {
                TextureSlot slot = slots.get(i);
                if (!slot.owns(framebuffer)) continue;

                found = true;
                slot.release(framebuffer);
                activeCount--;

                // 全空闲且当前 slot 数超过低水位——回收。
                // 低水位以下的空闲 slot 保留常驻，避免下次 acquire
                // 又要重新 glGenFramebuffers。
                if (slot.isFullyIdle() && slots.size() > slotCapacity) {
//                    System.out.println("release fbo pool slot, "
//                    + "slot size:" + slots.size());
                    texturePool.release(slot.texture());
                    slotToClose = slots.remove(i);
                }
                break;
            }
        }

        if (!found) {
            throw new IllegalArgumentException(
                    "framebuffer does not belong to this pool");
        }

        if (slotToClose != null) {
            ExecutorUtils.submitIfThread(getGlExecutor(), slotToClose::close);
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
        List<TextureSlot> toClose;
        synchronized (lock) {
            toClose = new ArrayList<>(slots);
            slots.clear();
        }

        // 归还纹理——逐个 try，单个失败不中断
        RuntimeException error = null;
        for (TextureSlot slot : toClose) {
            try {
                texturePool.release(slot.texture());
            } catch (RuntimeException e) {
                error = ResourceUtils.accumulateException(error, e);
            }
        }

        final RuntimeException texError = error;
        try {
            ExecutorUtils.submitIfThread(getGlExecutor(), () -> {
                RuntimeException fboError = ResourceUtils.closeDelayError(toClose);
                RuntimeException merged = ResourceUtils.accumulateException(texError, fboError);
                if (merged != null) throw merged;
            });
        } finally {
            texturePool.close();
        }
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
     * 统计空闲 FBO 数不低于 {@code leastIdle}、且纹理不在排除集合中的
     * slot 数量。
     *
     * <p><b>用途</b>：调用方在调度前预判 {@link #acquire2IdleExcluding(Set)
     * acquire2Excluding} 是否会触发 {@code texturePool.acquire()} 的 GL
     * 调度——即"不借新纹理、仅从现有 slot 取出两张不同纹理的空闲 FBO"
     * 是否可行。判据：
     * <pre>
     *   countSlotsWithIdleAtLeastExcluding(1, excluded) &gt;= 2
     * </pre>
     *
     * <p><b>快照语义</b>：并发场景下数值随时会变，不构成任何保留承诺——
     * 调用方自行理解后续 {@code acquire} 仍可能借新纹理。
     *
     * @param leastIdle        每个 slot 计入统计所需的最少空闲 FBO 数；
     *                         0 时统计全部未被排除的 slot
     * @param excludedTextures 需要排除的纹理集合，null 表示不排除
     * @return 满足条件的 slot 数
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
     * <p><b>与 {@link #acquire2IdleExcluding} 的关系</b>：本方法是"预扩容量"
     * 的显式操作——调用方在真正需要之前确保池里有足够的可用 slot，之后
     * {@code acquire2Excluding} 就能走纯复用路径（无 GL 调度）。
     *
     * <p><b>快速路径</b>：已满足时返回已完成的 future，无 GL 调度。
     *
     * <p><b>慢速路径</b>：提交到 GL 线程创建新 slot。提交时不在锁内
     * 做 GL 调用——锁只用于更新 {@code slots} 列表。
     *
     * <p><b>并发</b>：两次并发调用可能触发两次提交，但 GL 线程上的
     * 二次检查保证不会超量创建——后执行者发现已补足时直接返回。
     *
     * @param count             需要的 slot 数
     * @param leastIdle         每个计入的 slot 最少空闲 FBO 数
     * @param excludedTextures  需要排除的纹理集合，null 表示不排除
     * @return future，完成后池中至少 {@code count} 个 slot 满足条件
     */
    public CompletableFuture<Void> ensureSlotsWithIdleAtLeastExcluding(
            int count,
            int leastIdle,
            Set<GLTexture> excludedTextures) {

        // 快速路径——不涉及 GL，可在任意线程判断
        int currentIdle = countSlotsWithIdleAtLeastExcluding(leastIdle, excludedTextures);
        if (currentIdle >= count) {
            return CompletableFuture.completedFuture(null);
        }

        SerialExecutor glExecutor = texturePool.getGlExecutor();

        return ExecutorUtils.submitIfThread(glExecutor,
                () -> {
                    // GL 线程上重数——并发调用可能已经补足
                    int nowIdle = countSlotsWithIdleAtLeastExcluding(leastIdle, excludedTextures);
                    int need = count - nowIdle;

                    for (int i = 0; i < need; i++) {
                        // 借一张纹理——可能来自 texturePool 的空闲队列，
                        // 也可能新建。两者都需要 GL 线程，此处已在。
                        GLTexture tex = texturePool.acquire();

                        // TextureSlot 构造会为每一层 wrap 出一个 GLFramebuffer，
                        // 涉及 GL 调用——必须在锁外。
                        TextureSlot slot = TextureSlot.create(tex);

                        // 只有把 slot 纳入 slots 列表需要互斥。
                        synchronized (lock) {
                            slots.add(slot);
                        }
                    }
                });
    }

    /** 借出中的 FBO 数。 */
    public int getActiveCount() {
        synchronized (lock) {
            return activeCount;
        }
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return texturePool.getGlExecutor();
    }

    // ═══════════════════════════════════════════════
    // 内部类：一张纹理 + 它的全部 FBO
    // ═══════════════════════════════════════════════

    /**
     * 一个纹理槽位。管一张多层纹理，为每层创建一个 FBO。
     */
    private final static class TextureSlot  implements AutoCloseable{
        private final GLTexture            texture;
        private final List<GLFramebuffer>  fbos;
        private final Deque<GLFramebuffer> idle;

        private TextureSlot(GLTexture texture, List<GLFramebuffer> fbos, Deque<GLFramebuffer> idle) {
            this.texture = texture;
            this.fbos = fbos;
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
            return idle.size() == fbos.size();
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