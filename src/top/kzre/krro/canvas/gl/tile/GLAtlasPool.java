package top.kzre.krro.canvas.gl.tile;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Atlas 池：按 {@link UnitAllocator} 的决策创建并管理 {@link GLAtlas}。
 *
 * <p><b>定位</b>：池管理「物理页」——固定数量的 GPU 纹理绑定位置。
 *
 * <p><b>单元分配</b>：由外部 {@link UnitAllocator} 决定下一个 atlas 用
 * 哪个 unit。unit 必须落在 {@code [0, maxUnit)}，否则抛异常。
 *
 * <p><b>惰性创建</b>：atlas 按需创建。首次 {@link #acquire()} 才创建
 * 第一个，不足时继续创建。避免冷启动一次性分配全部显存。
 *
 * <p><b>线程契约</b>：
 * <ul>
 *   <li>{@link #acquire()} 可能创建新 atlas（GL 调用），必须在 GL 线程上调用</li>
 *   <li>{@link #get(int)} 只读数组，可在任意线程调用</li>
 *   <li>{@link #close()} 释放全部纹理，必须在 GL 线程上调用</li>
 * </ul>
 */
public final class GLAtlasPool {

    // ═══════════════════════════════════════════════
    // 字段
    // ═══════════════════════════════════════════════

    /** unit 上界。索引 = unit，长度固定。 */
    private final int maxUnit;

    /** 单元分配策略。 */
    private final UnitAllocator unitAllocator;

    private final GLAtlasFactory atlasFactory;

    /**
     * 池中的 atlas。索引 = unit；未创建的 unit 处为 {@code null}。
     * 长度固定为 {@code maxUnit}，不扩容。
     */
    private final AtomicReferenceArray<GLAtlas> atlases;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    public GLAtlasPool(int maxUnit, UnitAllocator unitAllocator, GLAtlasFactory atlasFactory) {
        this.atlasFactory = atlasFactory;
        if (maxUnit < 1) {
            throw new IllegalArgumentException("maxUnit must be >= 1: " + maxUnit);
        }
        if (unitAllocator == null) {
            throw new IllegalArgumentException("unitAllocator must not be null");
        }

        this.maxUnit       = maxUnit;
        this.unitAllocator = unitAllocator;
        this.atlases       = new AtomicReferenceArray<>(maxUnit);
    }

    // ═══════════════════════════════════════════════
    // 分配
    // ═══════════════════════════════════════════════

    /**
     * 返回第一个有空闲层的 atlas。
     *
     * <p>快速路径：扫描现有 atlas，返回第一个 {@code !isFull()} 的。
     * 全部满时，向 {@link UnitAllocator} 请求新 unit 创建 atlas。
     *
     * <p><b>必须在 GL 线程上调用。</b>
     *
     * @return 有空闲层的 atlas；池耗尽时返回 {@code null}
     */
    public GLAtlas acquire() {
        // 快速路径：现有 atlas 有空闲
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.get(i);
            if (atlas != null && !atlas.isFull()) {
                return atlas;
            }
        }
        // 慢速路径：请求新 unit
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
        int unit = unitAllocator.allocate();
        if (unit < 0) {
            return null;    // 池耗尽
        }
        if (unit >= maxUnit) {
            throw new IllegalStateException(
                    "UnitAllocator returned unit " + unit
                            + " but maxUnit = " + maxUnit);
        }

        GLAtlas newAtlas;
        try {
            newAtlas = atlasFactory.create(unit);
        } catch (Throwable t) {
            System.err.println("Failed to create atlas at unit " + unit + ": " + t);
            // 池子无法处理创建异常，直接抛出
            throw t;
        }

        if (!atlases.compareAndSet(unit, null, newAtlas)) {
            // 该 unit 已被占用——allocator 返回了重复 unit
            newAtlas.release();   // 释放刚创建的，避免泄漏
            throw new IllegalStateException(
                    "UnitAllocator returned unit " + unit
                            + " which already has an atlas");
        }
        return newAtlas;
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 释放全部 atlas 的底层纹理。必须在 GL 线程上调用。幂等。
     */
    public void close() {
        for (int i = 0; i < maxUnit; i++) {
            GLAtlas atlas = atlases.getAndSet(i, null);
            if (atlas != null) {
                atlas.release();
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public int getMaxUnit()  { return maxUnit; }


    /** 已创建的 atlas 数量。 */
    public int getAtlasCount() {
        int n = 0;
        for (int i = 0; i < maxUnit; i++) {
            if (atlases.get(i) != null) {
                n++;
            }
        }
        return n;
    }
}