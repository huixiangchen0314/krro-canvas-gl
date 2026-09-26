package top.kzre.krro.canvas.gl.tile;

import java.util.*;

/**
 * Atlas 池：管理 {@link GLAtlas} 的生命周期。
 *
 * <p><b>定位</b>：池是 atlas 的容器，不关心 unit——unit 由外部
 * 分配和绑定。池只负责：
 * <ul>
 *   <li>按需创建 atlas，直到达到容量上限</li>
 *   <li>返回非满的 atlas 供分配瓦片</li>
 *   <li>取出空闲 atlas（所有瓦片已释放）并归还所有权</li>
 *   <li>压缩：把瓦片搬到尽可能少的 atlas 里</li>
 * </ul>
 *
 * <p><b>容量语义</b>：{@code capacity} 是池能容纳的 atlas 数量上限。
 * 达到上限后 {@link #acquire()} 在全部 atlas 满时返回 {@code null}，
 * 调用方需自行决定淘汰策略（pack / 拒绝 / 扩展池）。
 *
 * <p><b>惰性创建</b>：atlas 按需创建，避免冷启动一次性分配全部显存。
 *
 * <p><b>线程契约</b>：所有方法都涉及 GL 资源或状态变更，必须在
 * GL 线程上调用。池由单一线程独占，内部无同步。
 */
public final class GLAtlasPool implements AutoCloseable {

    // ═══════════════════════════════════════════════
    // 字段
    // ═══════════════════════════════════════════════

    /** 池能容纳的 atlas 数量上限。 */
    private final int capacity;

    /** atlas 创建工厂。 */
    private final GLAtlasFactory atlasFactory;

    /** 池中的 atlas。 */
    private final List<GLAtlas> atlases = new ArrayList<>();

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param capacity     池能容纳的 atlas 数量，必须 ≥ 1
     * @param atlasFactory atlas 创建工厂，不能为 null
     */
    public GLAtlasPool(int capacity, GLAtlasFactory atlasFactory) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        if (atlasFactory == null) {
            throw new IllegalArgumentException("atlasFactory must not be null");
        }
        this.capacity     = capacity;
        this.atlasFactory = atlasFactory;
    }

    // ═══════════════════════════════════════════════
    // 分配
    // ═══════════════════════════════════════════════

    /**
     * 返回第一个有空闲槽位的 atlas；全部满时创建新 atlas。
     *
     * @return 有空闲槽位的 atlas；已达容量上限且全部满时返回 {@code null}
     */
    public GLAtlas acquire() {
        for (GLAtlas atlas : atlases) {
            if (!atlas.isFull()) {
                return atlas;
            }
        }
        if (atlases.size() >= capacity) {
            return null;
        }
        GLAtlas atlas = atlasFactory.create();
        atlases.add(atlas);
        return atlas;
    }

    // ═══════════════════════════════════════════════
    // 统计
    // ═══════════════════════════════════════════════

    /**
     * 池的容量统计快照。
     *
     * <p>一次遍历拿到 capacity 和 count，避免重复遍历。
     * 不可变值对象。
     */
    public static final class PoolStats {

        private final int capacity;
        private final int count;

        public PoolStats(int capacity, int count) {
            this.capacity = capacity;
            this.count    = count;
        }

        /** 池中所有 atlas 的瓦片总容量。 */
        public int getCapacity() { return capacity; }

        /** 池中已占用的瓦片数量。 */
        public int getCount() { return count; }

        /** 空闲槽位数 = capacity - count。 */
        public int getFree() { return capacity - count; }

        @Override
        public String toString() {
            return "PoolStats{capacity=" + capacity
                    + ", count=" + count
                    + ", free=" + getFree() + "}";
        }
    }

    /**
     * 遍历池中所有 atlas，汇总容量与占用量。
     *
     * @return 统计快照；池为空时返回 {@code PoolStats(0, 0)}
     */
    public PoolStats getStats() {
        int tileCapacity = 0;
        int tileCount    = 0;
        for (GLAtlas atlas : atlases) {
            tileCapacity += atlas.getCapacity();
            tileCount    += atlas.getAllocatedCount();
        }
        return new PoolStats(tileCapacity, tileCount);
    }

    /**
     * 池中所有 atlas 的瓦片总容量。
     *
     * <p>便利方法，内部走 {@link #getStats()}。
     */
    public int getTileCapacity() {
        return getStats().getCapacity();
    }

    /**
     * 池中已占用的瓦片数量。
     *
     * <p>便利方法，内部走 {@link #getStats()}。
     */
    public int getTileCount() {
        return getStats().getCount();
    }

    /**
     * 返回当前池中 atlas 的快照视图。
     *
     * <p><b>快照语义</b>：返回的对象持有调用时刻的 atlas 数组副本。
     * 之后池的变化（acquire 新 atlas、extract 移除 atlas）不影响
     * 已返回的 page。调用方应在规划阶段开始时取快照，并在整个
     * 规划-执行周期内使用同一个 page。
     */
    public AtlasPoolPage page() {
        return new DefaultAtlasPoolPage(atlases.toArray(new GLAtlas[0]));
    }



    /**
     * 预热池——创建到指定数量的 atlas。
     *
     * <p><b>语义</b>：
     * <ul>
     *   <li>已有 {@code count} 个以上 atlas 时 no-op</li>
     *   <li>创建到 {@code count} 个——不检查空闲槽位，纯粹按数量</li>
     * </ul>
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。
     *
     * @param count 目标 atlas 数量，必须 {@code 0 <= count <= capacity}
     * @throws IllegalArgumentException count 为负或超过 capacity
     */
    public void warmup(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        if (count > capacity) {
            throw new IllegalArgumentException(
                    "count " + count + " exceeds pool capacity " + capacity);
        }
        while (atlases.size() < count) {
            atlases.add(atlasFactory.create());
        }
    }

    /**
     * 预热到容量上限。
     *
     * <p>等价于 {@code warmup(capacity)}。
     */
    public void warmup() {
        warmup(capacity);
    }


    // ═══════════════════════════════════════════════
    // 清理（转移语义）
    // ═══════════════════════════════════════════════

    /**
     * 取出一个空闲的 atlas（所有瓦片均已释放）。
     *
     * <p><b>转移语义</b>：atlas 从池中移除，所有权交给调用方。
     * 调用方负责 {@link GLAtlas#close()} 释放底层纹理。
     *
     * @return 被取出的 atlas；没有空闲 atlas 时返回 {@code null}
     */
    public GLAtlas extractIdle() {
        Iterator<GLAtlas> it = atlases.iterator();
        while (it.hasNext()) {
            GLAtlas atlas = it.next();
            if (atlas.isEmpty()) {
                it.remove();
                return atlas;
            }
        }
        return null;
    }

    /**
     * 取出所有空闲的 atlas。
     *
     * <p>语义同 {@link #extractIdle()}——每个返回的 atlas 所有权
     * 都转移给调用方。
     *
     * @return 被取出的 atlas 列表；没有空闲 atlas 时返回空列表
     */
    public List<GLAtlas> extractAllIdle() {
        List<GLAtlas> result = new ArrayList<>();
        GLAtlas atlas;
        while ((atlas = extractIdle()) != null) {
            result.add(atlas);
        }
        return result;
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 释放全部 atlas 的底层纹理并清空池。幂等。
     *
     * <p>与 {@code extractAllIdle} 不同——本方法<b>不转移</b>所有权，
     * 直接释放全部纹理。
     */
    @Override
    public void close() {
        for (GLAtlas atlas : atlases) {
            atlas.close();
        }
        atlases.clear();
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** 池能容纳的 atlas 数量上限。 */
    public int getCapacity() { return capacity; }

    /** 当前池中的 atlas 数量。 */
    public int getAtlasCount() { return atlases.size(); }

    /** 池中空闲 atlas 的数量。 */
    public int getIdleCount() {
        int n = 0;
        for (GLAtlas atlas : atlases) {
            if (atlas.isEmpty()) n++;
        }
        return n;
    }

    // ═══════════════════════════════════════════════
    // 整理 / 压缩
    // ═══════════════════════════════════════════════

    /**
     * 只清理空闲 atlas，不移动任何瓦片。
     *
     * <p>等价于 {@code pack} 但不做搬运——纯清理。
     */
    public void purge() {
        releaseIdle();
    }

    /**
     * 尽可能压缩，等价于 {@code pack(1)}。
     */
    public void pack() {
        pack(1);
    }

    /**
     * 整理池中的 atlas，把瓦片压缩到不超过 {@code keepAtlases} 个非空 atlas。
     *
     * <p><b>策略</b>：非空 atlas 按已分配数量<b>升序</b>排列——已分配最少
     * 的优先作为 src 搬空。dst 是已分配最多的若干个（保留集）。这样每次
     * 搬运的瓦片数量最少。
     *
     * <p><b>keepAtlases 语义</b>：
     * <ul>
     *   <li>{@code keepAtlases >= 1}：尝试压缩到 ≤ keepAtlases 个非空 atlas</li>
     *   <li>{@code keepAtlases = 0}：尽可能压缩——效果等同 keepAtlases = 1，
     *       因为只要池中有数据，非空 atlas 至少 1 个</li>
     * </ul>
     *
     * <p><b>尽力而为</b>：空间不足时搬不动的留在原 atlas，不报错。
     *
     * <p><b>必须在 GL 线程上调用。</b>
     *
     * @param keepAtlases 目标非空 atlas 数量上限，≥ 0
     */
    public void pack(int keepAtlases) {
        if (keepAtlases < 0) {
            throw new IllegalArgumentException("keepAtlases must be >= 0: " + keepAtlases);
        }

        int effectiveLimit = Math.max(keepAtlases, 1);

        List<GLAtlas> nonEmpty = collectNonEmpty();
        if (nonEmpty.size() <= effectiveLimit) {
            releaseIdle();
            return;
        }
        nonEmpty.sort(Comparator.comparingInt(GLAtlas::getAllocatedCount));

        // 保留集 = 末尾 effectiveLimit 个（已分配最多）
        // src 集 = 开头 (size - effectiveLimit) 个（已分配最少）
        int keepFrom = nonEmpty.size() - effectiveLimit;

        for (int i = 0; i < keepFrom; i++) {
            GLAtlas src = nonEmpty.get(i);
            if (src.isEmpty()) {
                continue;    // 已被前面的搬运清空
            }

            for (int j = keepFrom; j < nonEmpty.size() && !src.isEmpty(); j++) {
                GLAtlas dst = nonEmpty.get(j);
                if (dst.isFull()) {
                    continue;
                }
                for (GLAtlas.GLTileDataImpl tile : src.getActiveTiles()) {
                    if (dst.isFull()) {
                        break;
                    }
                    GLAtlas.move(dst, src, tile);
                }
            }
        }

        releaseIdle();
    }

    /** 收集所有非空 atlas。 */
    private List<GLAtlas> collectNonEmpty() {
        List<GLAtlas> result = new ArrayList<>();
        for (GLAtlas atlas : atlases) {
            if (!atlas.isEmpty()) {
                result.add(atlas);
            }
        }
        return result;
    }

    /** 取出并释放所有空闲 atlas。 */
    private void releaseIdle() {
        for (GLAtlas idle : extractAllIdle()) {
            idle.close();
        }
    }
}