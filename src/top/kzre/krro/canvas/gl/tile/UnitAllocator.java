package top.kzre.krro.canvas.gl.tile;

/**
 * 纹理单元分配器：决定「下一个 atlas 使用哪个单元」。
 *
 * <p>实现方维护自己的分配状态，{@link #allocate()} 返回可用的单元编号
 * 或 {@code -1}。
 *
 * <p><b>线程契约</b>：无需线程安全——实现由 {@link GLAtlasPool} <b>独占</b>，
 * 池的 {@code acquire()} 只在 GL 线程调用。不要在多个池或跨线程间共享
 * 同一个 allocator 实例。
 *
 * <p><b>单元不复用</b>：本接口没有 {@code release}——unit 一旦分配即
 * 永久占用，与「物理页」语义一致。
 */
@FunctionalInterface
public interface UnitAllocator {

    /**
     * 分配一个单元槽位。
     *
     * @return 单元索引（≥ 0）；无可用时返回 {@code -1}
     */
    int allocate();

    // ═══════════════════════════════════════════════
    // 默认实现
    // ═══════════════════════════════════════════════

    /**
     * 连续区间分配：闭区间 {@code [min, max]}，依次分配
     * {@code min, min+1, ..., max}。
     *
     * @param min 起始单元（含），必须 ≥ 0
     * @param max 结束单元（含），必须 ≥ min
     * @throws IllegalArgumentException 参数非法
     */
    static UnitAllocator range(int min, int max) {
        if (min < 0) {
            throw new IllegalArgumentException("min must be >= 0: " + min);
        }
        if (max < min) {
            throw new IllegalArgumentException(
                    "max must be >= min: min=" + min + ", max=" + max);
        }
        int[] next = {min};
        return () -> next[0] > max ? -1 : next[0]++;
    }

    /**
     * 连续区间分配：{@code [0, size-1]}，共 {@code size} 个单元。
     *
     * <p>等价于 {@code range(0, size - 1)}。
     *
     * @param size 单元数量，必须 ≥ 1
     * @throws IllegalArgumentException size 非法
     */
    static UnitAllocator range(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1: " + size);
        }
        return range(0, size - 1);
    }

    /**
     * 按给定顺序分配：依次返回 {@code units[0], units[1], ...}。
     *
     * <p>用于「单元有优先级」或「非连续分配」的场景。例如
     * {@code of(3, 1, 0, 2)} 会先返回 3，再 1，再 0，再 2。
     *
     * <p><b>参数校验</b>：数组非空，每个元素 ≥ 0。不检查重复——
     * 若返回重复 unit，{@link GLAtlasPool} 的 CAS 会检测到并抛异常。
     *
     * <p><b>防御性拷贝</b>：内部复制数组，调用方后续修改不影响分配器。
     *
     * @param units 预设的单元顺序，至少一个元素
     * @throws IllegalArgumentException 数组为空或含负数
     */
    static UnitAllocator of(int... units) {
        if (units == null || units.length == 0) {
            throw new IllegalArgumentException("units must not be empty");
        }
        int[] copy = units.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] < 0) {
                throw new IllegalArgumentException(
                        "units[" + i + "] must be >= 0: " + copy[i]);
            }
        }
        int[] idx = {0};
        return () -> idx[0] >= copy.length ? -1 : copy[idx[0]++];
    }
}