// AbstractFixedSizeFrameBufferPool.java
package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.core.util.SerialExecutor;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 固定尺寸 FBO 池的抽象基类。
 *
 * <p><b>职责划分</b>：
 * <ul>
 *   <li>本类：slot 生命周期（借用/归还/回收/延迟关闭/并发控制），
 *       以及 {@link SlotHolder} 的通用 FBO 队列管理。</li>
 *   <li>子类：纹理管理（借/还/关闭纹理池）与 FBO 创建逻辑
 *       （{@link #createHolder()} / {@link #releaseHolderTextures} /
 *       {@link #closeTexturePools}）。</li>
 * </ul>
 *
 * <p><b>按需创建，峰值后回收</b>：{@link #acquire()} 优先从已有
 * Holder 取空闲 FBO；全部占用时调用 {@link #createHolder()} 创建新
 * Holder。归还时若某 Holder 全空闲且超出低水位，则移除并回收。
 *
 * <p><b>延迟关闭</b>：{@link #close()} 时全空闲 Holder 立即释放；
 * 有借出的 Holder 由 {@link DeferredClose} 接管，全部归还后释放。
 *
 * <p><b>线程契约</b>：{@code acquire} / {@code release} 涉及
 * 子类纹理池的借还，纹理池操作不调 GL，可在任意线程；GL 释放由
 * 子类投递到 GL 线程。
 */
public abstract class FixedSizeFrameBufferPool
    <T extends GLFramebuffer>
        implements GLFrameBufferPool, AutoCloseable {

    private final List<SlotHolder> slots = new ArrayList<>();
    private final Object lock = new Object();

    /** 持久保存的 FBO 总数（仅供子类/诊断使用）。 */
    private final int capacity;
    /** 持久保存的 slot 数（低水位）。 */
    private final int slotCapacity;

    /** 延迟关闭器。close 时仍有 FBO 借出——此字段非 null。 */
    private DeferredClose deferredClose = null;

    /**
     * @param slotCapacity  低水位 slot 数，必须 >= 2
     * @param layersPerSlot 每个 slot 的 FBO 数（即颜色纹理层数）
     */
    protected FixedSizeFrameBufferPool(int slotCapacity, int layersPerSlot) {
        if (slotCapacity < 2) {
            throw new IllegalArgumentException("slotCapacity must be >= 2");
        }
        if (layersPerSlot <= 0) {
            throw new IllegalArgumentException("layersPerSlot must be > 0");
        }
        this.slotCapacity = slotCapacity;
        this.capacity = slotCapacity * layersPerSlot;
    }

    // ═══════════════════════════════════════════════
    // 子类定制点
    // ═══════════════════════════════════════════════

    /**
     * 创建一个新的 slot Holder。
     *
     * <p>子类在此借纹理、为纹理每一层创建 FBO，返回一个
     * {@link SlotHolder} 实例。必须在 GL 线程调用。
     *
     * <p><b>异常安全</b>：若创建失败，子类负责归还已借的纹理
     * （持有 Holder 之前借的资源）。
     */
    protected abstract SlotHolder createHolder();

    /**
     * 归还 Holder 持有的纹理资源（如归还给 texturePool）。
     *
     * <p>在 slot 回收时调用；线程与 {@code release} 调用方相同。
     * 默认无操作。
     */
    protected abstract void releaseHolderTextures(SlotHolder holder);

    /**
     * 关闭底层纹理资源池。在 {@link #close()} 末尾调用。
     *
     * <p>延迟关闭路径中，之后归还纹理时纹理池必须支持"关闭后归还"。
     */
    protected abstract void closeTexturePools();

    // ═══════════════════════════════════════════════
    // Holder 抽象
    // ═══════════════════════════════════════════════

    /**
     * 一个 slot 的资源持有者（Holder）。封装该 slot 的全部 FBO，
     * 提供借用/归还/状态查询协议。抽象类通过此协议管理 slot 生命周期。
     *
     * <p>子类构造具体 Holder（持有纹理引用等），但 FBO 队列管理
     * 由本基类提供，子类不应覆写这些方法。
     */
    protected abstract class SlotHolder implements AutoCloseable {

        private final List<T> fbos;
        private final Deque<T> idle;

        protected SlotHolder(List<T> fbos) {
            if (fbos == null || fbos.isEmpty()) {
                throw new IllegalArgumentException("fbos must not be empty");
            }
            this.fbos = fbos;
            this.idle = new ArrayDeque<>(fbos);
        }

        /** 取一个空闲 FBO；无空闲返回 null。 */
        public final T tryAcquire() {
            return idle.pollFirst();
        }

        /** 归还 FBO 到空闲队列。 */
        public final void release(T fbo) {
            idle.addFirst(fbo);
        }

        /** 全部 FBO 都空闲。 */
        public final boolean isFullyIdle() {
            return idle.size() == fbos.size();
        }

        /** 借出中的 FBO 数。 */
        public final int outstanding() {
            return fbos.size() - idle.size();
        }

        /** 该 FBO 是否属于本 Holder。 */
        public final boolean owns(T fbo) {
            return fbos.contains(fbo);
        }

        /** 空闲 FBO 数。 */
        public final int idleCount() {
            return idle.size();
        }

        /** FBO 总数。 */
        public final int size() {
            return fbos.size();
        }

        /**
         * 该 Holder 对应的颜色纹理，用于 acquire2IdleExcluding 的
         * 排除判断。子类实现。
         */
        public abstract GLTexture colorTexture();

        /**
         * 关闭全部 FBO。必须在 GL 线程调用。子类不应覆写。
         */
        @Override
        public final void close() {
            RuntimeException e = ResourceUtils.closeDelayError(fbos);
            if (e != null) throw e;
        }
    }

    // ═══════════════════════════════════════════════
    // 生命周期 API：借用
    // ═══════════════════════════════════════════════

    /**
     * 获取一个空闲 FBO。使用后必须 {@link #release(T)} 归还。
     *
     * @throws IllegalStateException 池已进入关闭流程
     */
    public T acquire() {
        requireGlThread();

        synchronized (lock) {
            requireNotClosing();

            for (SlotHolder slot : slots) {
                T fbo = slot.tryAcquire();
                if (fbo != null) return fbo;
            }

            SlotHolder holder = createHolder();
            slots.add(holder);
            return holder.tryAcquire();
        }
    }

    public List<T> acquire2Idle() {
        return acquire2IdleExcluding(null, new ArrayList<>());
    }

    public List<T> acquire2IdleExcluding(Set<GLTexture> excludedTextures,
                                     List<T> out) {
        out.clear();
        synchronized (lock) {
            requireNotClosing();

            T a = null;
            T b = null;

            for (SlotHolder slot : slots) {
                if (excludedTextures != null
                        && excludedTextures.contains(slot.colorTexture())) {
                    continue;
                }
                T fbo = slot.tryAcquire();
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
                    for (SlotHolder slot : slots) {
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
            out.add(a);
            out.add(b);
        }
        return out;
    }

    // ═══════════════════════════════════════════════
    // 生命周期 API：归还
    // ═══════════════════════════════════════════════

    /**
     * 归还 FBO。
     *
     * <p>正常路径：slot 全空闲且 {@code slots.size() > slotCapacity} 时
     * 移除最尾部的空闲 slot，回收其纹理与 FBO。低水位以下的空闲 slot
     * 常驻，保留已创建的 FBO 避免下次 acquire 重新 glGenFramebuffers。
     *
     * <p>关闭路径：{@link #close()} 已调用且仍有借出——由
     * {@link DeferredClose} 接管，归零时触发 pending slot 释放。
     */
    @Override
    public void release(GLFramebuffer framebuffer) {
        if (framebuffer == null) return;
        if (framebuffer.isReleased()) return;
        System.out.println("release FrameBuffer");
        T fbo;
        try{
            //noinspection unchecked
            fbo = (T) framebuffer;
        }catch (ClassCastException e) {
            throw new IllegalArgumentException(
                    "framebuffer does not belong to this pool");
        }
        SlotHolder slotToClose = null;
        DeferredClose closer = null;
        boolean found = false;

        synchronized (lock) {
            if (deferredClose != null) {
                if (!deferredClose.owns(fbo)) {
                    throw new IllegalArgumentException(
                            "framebuffer does not belong to this pool");
                }
                found = true;
                if (deferredClose.onRelease()) {
                    closer = deferredClose;
                    deferredClose = null;
                }
            } else {
                for (int i = 0; i < slots.size(); i++) {
                    SlotHolder slot = slots.get(i);
                    if (!slot.owns(fbo)) continue;

                    found = true;
                    slot.release(fbo);

                    if (slot.isFullyIdle() && slots.size() > slotCapacity) {
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
            System.out.println("release slots, count=" + slotToClose.size());
            // 归还纹理（子类）+ GL 线程关闭 FBO
            releaseHolderTextures(slotToClose);
            ExecutorUtils.runOnThread(getGlExecutor(), slotToClose::close);
        }

        if (closer != null) {
            releaseSlots(closer.pendingSlots);
        }
    }

    // ═══════════════════════════════════════════════
    // 生命周期 API：关闭
    // ═══════════════════════════════════════════════

    /**
     * 关闭池。全空闲 slot 立即释放；有借出的延迟释放。
     * 最后调用 {@link #closeTexturePools()} 关闭纹理池。
     *
     * <p><b>非幂等</b>：重复调用抛异常。
     */
    @Override
    public void close() {
        List<SlotHolder> immediate;
        List<SlotHolder> pending;

        synchronized (lock) {
            if (deferredClose != null) {
                throw new IllegalStateException("pool already closed or closing");
            }

            immediate = new ArrayList<>();
            pending = new ArrayList<>();
            for (SlotHolder slot : slots) {
                if (slot.isFullyIdle()) {
                    immediate.add(slot);
                } else {
                    pending.add(slot);
                }
            }
            slots.clear();

            if (!pending.isEmpty()) {
                int outstanding = 0;
                for (SlotHolder slot : pending) {
                    outstanding += slot.outstanding();
                }
                deferredClose = new DeferredClose(pending, outstanding);
            }
        }

        if (!immediate.isEmpty()) {
            releaseSlots(immediate);
        }

        closeTexturePools();
    }

    /**
     * 批量释放：GL 线程关闭 FBO，当前线程归还纹理。
     */
    private void releaseSlots(List<SlotHolder> toRelease) {
        if (toRelease.isEmpty()) return;

        Throwable t = null;
        try {
            ExecutorUtils.runOnThread(getGlExecutor(), () ->
                    ResourceUtils.closeDelayError(toRelease));
        } catch (Throwable e) {
            t = e;
        }

        for (SlotHolder slot : toRelease) {
            try {
                releaseHolderTextures(slot);
            } catch (Throwable e) {
                t = ResourceUtils.accumulateException(t, e);
            }
        }

        if (t != null) {
            throw ResourceUtils.wrapError(t);
        }
    }

    // ═══════════════════════════════════════════════
    // 生命周期 API：ensure
    // ═══════════════════════════════════════════════

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

        SerialExecutor glExecutor = getGlExecutor();

        return ExecutorUtils.runOnThread(glExecutor,
                () -> {
                    synchronized (lock) {
                        if (deferredClose != null) return;
                    }

                    int nowIdle = countSlotsWithIdleAtLeastExcluding(leastIdle, excludedTextures);
                    int need = count - nowIdle;

                    for (int i = 0; i < need; i++) {
                        SlotHolder holder = createHolder();
                        synchronized (lock) {
                            if (deferredClose != null) {
                                // 关闭流程启动——刚创建的 holder 立即释放
                                releaseHolderTextures(holder);
                                holder.close();
                                return;
                            }
                            slots.add(holder);
                        }
                    }
                });
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

    /** 持久保存的 FBO 总数。 */
    protected final int getCapacity() {
        return capacity;
    }

    /** 低水位 slot 数。 */
    protected final int getSlotCapacity() {
        return slotCapacity;
    }

    /** 池是否已关闭。子类实现。 */
    public abstract boolean isClosed();

    @Override
    public abstract SerialExecutor getGlExecutor();

    private int countSlotsWithIdleAtLeastExcluding(int leastIdle,
                                                   Set<GLTexture> excludedTextures) {
        synchronized (lock) {
            int n = 0;
            for (SlotHolder slot : slots) {
                if (excludedTextures != null
                        && excludedTextures.contains(slot.colorTexture())) {
                    continue;
                }
                if (slot.idleCount() >= leastIdle) n++;
            }
            return n;
        }
    }

    /** 必须在 {@link #lock} 内调用。 */
    private void requireNotClosing() {
        if (deferredClose != null) {
            throw new IllegalStateException("pool is closing");
        }
    }

    // ═══════════════════════════════════════════════
    // 延迟关闭器
    // ═══════════════════════════════════════════════

    private final class DeferredClose {
        final List<SlotHolder> pendingSlots;
        private int remaining;

        DeferredClose(List<SlotHolder> pendingSlots, int remaining) {
            this.pendingSlots = pendingSlots;
            this.remaining = remaining;
        }

        boolean onRelease() {
            remaining--;
            return remaining == 0;
        }

        boolean owns(T fb) {
            for (SlotHolder slot : pendingSlots) {
                if (slot.owns(fb)) return true;
            }
            return false;
        }
    }
}