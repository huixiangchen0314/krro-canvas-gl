package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.TileData;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Atlas 池：管理 {@link GLAtlas} 的生命周期，unit 从预设序列中分配。
 *
 * <p><b>定位</b>：池管理「物理页」——固定数量的 GPU 纹理绑定位置。
 *
 * <p><b>单元序列</b>：构造时给定 {@code int[] units}——unit 编号的固定
 * 顺序。池按顺序扫描找下一个未分配的 unit。支持非连续、
 * 有优先级的序列（如 {@code [3, 1, 0, 2]}）。
 *
 * <p><b>惰性创建</b>：atlas 按需创建，避免冷启动一次性分配全部显存。
 *
 * <p><b>单元可回收</b>：{@link #extractIdle()} 取出空闲 atlas 后，
 * 它占用的 unit 归还池，可被后续 {@link #acquire()} 复用。
 *
 * <p><b>线程契约</b>：所有方法（含 acquire / extract / close）都涉及
 * GL 资源或状态变更，必须在 GL 线程上调用。池由单一线程独占，
 * 内部无同步。
 */
public final class GLAtlasPool {

    // ═══════════════════════════════════════════════
    // 字段
    // ═══════════════════════════════════════════════

    /** 预设的 unit 序列。 */
    private final int[] units;

    /** unit 上界 = max(units) + 1。数组长度。 */
    private final int maxUnit;

    /** atlas 创建工厂。 */
    private final GLAtlasFactory atlasFactory;

    /** 已分配的 unit 集合。 */
    private final BitSet assigned;

    /** 池中的 atlas。索引 = unit；未创建的 unit 处为 null。 */
    private final AtomicReferenceArray<GLAtlas> atlases;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /** 顺序分配：units = [0, 1, 2, ..., maxUnit-1]。 */
    public GLAtlasPool(int maxUnit, GLAtlasFactory atlasFactory) {
        this(sequential(maxUnit), atlasFactory);
    }

    /** 自定义顺序。 */
    public GLAtlasPool(int[] units, GLAtlasFactory atlasFactory) {
        if (units == null || units.length == 0) {
            throw new IllegalArgumentException("units must not be empty");
        }
        if (atlasFactory == null) {
            throw new IllegalArgumentException("atlasFactory must not be null");
        }
        int max = 0;
        for (int unit : units) {
            if (unit < 0) {
                throw new IllegalArgumentException("unit must be >= 0: " + unit);
            }
            if (unit > max) max = unit;
        }
        this.units        = units.clone();
        this.maxUnit = max + 1;
        this.atlasFactory = atlasFactory;
        this.assigned     = new BitSet(maxUnit);
        this.atlases      = new AtomicReferenceArray<>(maxUnit);
    }

    private static int[] sequential(int maxUnit) {
        if (maxUnit < 1) {
            throw new IllegalArgumentException("maxUnit must be >= 1: " + maxUnit);
        }
        int[] arr = new int[maxUnit];
        for (int i = 0; i < maxUnit; i++) arr[i] = i;
        return arr;
    }



    // ═══════════════════════════════════════════════
    // 分配
    // ═══════════════════════════════════════════════

    /**
     * 返回第一个有空闲层的 atlas；全部满时创建新 atlas。
     *
     * @return 有空闲层的 atlas；池耗尽时返回 {@code null}
     */
    public GLAtlas acquire() {
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.get(i);
            if (atlas != null && !atlas.isFull()) {
                return atlas;
            }
        }
        return createAtlas();
    }

    /**
     * 按 unit 索引访问 atlas。
     *
     * @throws IllegalStateException 该 unit 尚未创建
     */
    public GLAtlas get(int unit) {
        if (unit < 0 || unit >= maxUnit) {
            throw new IndexOutOfBoundsException(
                    "unit " + unit + " out of [0, " + maxUnit + ")");
        }
        GLAtlas atlas = atlases.get(unit);
        if (atlas == null) {
            throw new IllegalStateException(
                    "atlas at unit " + unit + " has not been created");
        }
        return atlas;
    }

    // ═══════════════════════════════════════════════
    // 创建
    // ═══════════════════════════════════════════════

    private GLAtlas createAtlas() {
        for (int unit : units) {
            if (!assigned.get(unit)) {
                return createAtlasAt(unit);
            }
        }
        return null;    // 池耗尽
    }

    private GLAtlas createAtlasAt(int unit) {
        GLAtlas atlas;
        try {
            atlas = atlasFactory.create(unit);
        } catch (Throwable t) {
            System.err.println("Failed to create atlas at unit " + unit + ": " + t);
            throw t;
        }
        assigned.set(unit);
        if (!atlases.compareAndSet(unit, null, atlas)) {
            assigned.clear(unit);
            atlas.release();
            throw new IllegalStateException(
                    "unit " + unit + " already has an atlas");
        }
        return atlas;
    }

    // ═══════════════════════════════════════════════
    // 清理（转移语义）
    // ═══════════════════════════════════════════════

    /**
     * 取出一个空闲的 atlas（所有层均已释放）。
     *
     * <p><b>转移语义</b>：atlas 从池中移除，所有权交给调用方。
     * 调用方负责 {@link GLAtlas#release()} 释放底层纹理。
     *
     * <p>被取出 atlas 占用的 unit 归还池，后续 {@link #acquire()}
     * 可以重新使用该 unit 创建新 atlas。
     *
     * @return 被取出的 atlas；没有空闲 atlas 时返回 {@code null}
     */
    public GLAtlas extractIdle() {
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.get(i);
            if (atlas == null || !atlas.isEmpty()) {
                continue;
            }
            if (atlases.compareAndSet(i, atlas, null)) {
                assigned.clear(i);
                return atlas;
            }
        }
        return null;
    }

    /**
     * 取出所有空闲的 atlas。
     *
     * <p>语义同 {@link #extractIdle()}——每个返回的 atlas 所有权
     * 都转移给调用方，unit 归还池。
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
     * 释放全部 atlas 的底层纹理。幂等。
     *
     * <p>与 {@code extractAllIdle} 不同——本方法<b>不转移</b>所有权，
     * 直接释放全部纹理并清空池。
     */
    public void close() {
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.getAndSet(i, null);
            if (atlas != null) {
                assigned.clear(i);
                atlas.release();
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public int getMaxUnit() { return maxUnit; }

    public int getAtlasCount() {
        int n = 0;
        for (int i = 0; i < maxUnit; i++) {
            if (atlases.get(i) != null) n++;
        }
        return n;
    }

    public int getIdleCount() {
        int n = 0;
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.get(i);
            if (atlas != null && atlas.isEmpty()) n++;
        }
        return n;
    }


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
     * 整理池中的 atlas，把瓦片压缩到不超过 {@code keepUnits} 个非空 atlas。
     *
     * <p><b>策略</b>：非空 atlas 按已分配层数<b>升序</b>排列——已分配最少
     * 的优先作为 src 搬空。dst 是已分配最多的若干个（保留集）。这样每次
     * 搬运的 tile 数量最少。
     *
     * <p><b>keepUnits 语义</b>：
     * <ul>
     *   <li>{@code keepUnits >= 1}：尝试压缩到 ≤ keepUnits 个非空 atlas</li>
     *   <li>{@code keepUnits = 0}：尽可能压缩——效果等同 keepUnits = 1，
     *       因为只要池中有数据，非空 atlas 至少 1 个</li>
     * </ul>
     *
     * <p><b>尽力而为</b>：空间不足时搬不动的留在原 atlas，不报错。
     *
     * <p><b>必须在 GL 线程上调用。</b>
     *
     * @param keepUnits 目标非空 atlas 数量上限，≥ 0
     */
    public void pack(int keepUnits) {
        if (keepUnits < 0) {
            throw new IllegalArgumentException("keepUnits must be >= 0: " + keepUnits);
        }

        int effectiveLimit = Math.max(keepUnits, 1);

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
                for (GLAtlas.GLTileData tile : src.getActiveTiles()) {
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
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.get(i);
            if (atlas != null && !atlas.isEmpty()) {
                result.add(atlas);
            }
        }
        return result;
    }

    /** 取出并释放所有空闲 atlas。 */
    private void releaseIdle() {
        for (GLAtlas idle : extractAllIdle()) {
            idle.release();
        }
    }
}