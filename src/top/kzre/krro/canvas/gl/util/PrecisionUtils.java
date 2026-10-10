package top.kzre.krro.canvas.gl.util;

/**
 * 数值精度转换工具。
 *
 * <p><b>线程契约</b>：纯函数，无状态，任意线程可调用。
 */
public final class PrecisionUtils {

    private PrecisionUtils() {}

    /**
     * float → IEEE 754 half（binary16）位模式。
     *
     * <p>采用 <b>round-to-nearest-even</b> 舍入——与 IEEE 754 规定的
     * 默认舍入模式一致，也与 x86 F16C、ARM 等硬件的 float→half 转换
     * 结果一致。临界舍入点（尾数恰好落在两 half 正中间）向偶数舍入。
     *
     * <p>处理所有边界情形：±0、±Inf、NaN、次正规数、上溢、下溢。
     * 溢出返回 ±Inf，下溢返回 ±0 或最小次正规数。
     *
     * <p><b>与 JDK 的关系</b>：若项目运行在 Java 20+，可改用
     * {@link Float#floatToFloat16(float)}——JDK 内置实现经过严格测试。
     * 本实现与其语义一致，可作为低版本 Java 的等价替代。
     *
     * @param f 输入 float
     * @return 16 位 half 位模式
     */
    public static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp  = (bits >>> 23) & 0xFF;
        int mant = bits & 0x7FFFFF;

        // Inf / NaN：NaN 置一个 quiet 位，Inf 保持
        if (exp == 0xFF) {
            return (short) (sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
        }

        // 重新偏置指数（float 偏移 127 → half 偏移 15）
        int newExp = exp - 127 + 15;

        // 上溢 → Inf
        if (newExp >= 0x1F) {
            return (short) (sign | 0x7C00);
        }

        int halfMant;
        if (newExp <= 0) {
            // 太小 → 直接归零
            if (newExp < -10) {
                return (short) sign;
            }
            // 次正规：补上隐藏位再右移
            // 无偏指数 < -14，尾数需要额外右移 (1 - newExp) 位；
            // 加上从 24 位表示到 10 位尾数的固定 13 位丢弃，
            // 总右移量 = 14 - newExp。
            mant |= 0x800000;
            int shift = 14 - newExp;
            halfMant = roundShift(mant, shift);
            // 舍入进位 → 最小规格化数
            if (halfMant >= 0x400) {
                return (short) (sign | 0x0400);
            }
            return (short) (sign | halfMant);
        }

        // 规格化：从 23 位尾数丢弃低 13 位
        halfMant = roundShift(mant, 13);
        // 尾数进位 → 指数进位
        if (halfMant >= 0x400) {
            halfMant = 0;
            newExp++;
            if (newExp >= 0x1F) {
                return (short) (sign | 0x7C00);
            }
        }
        return (short) (sign | (newExp << 10) | halfMant);
    }

    /**
     * 将 {@code mant} 右移 {@code shift} 位，保留高位，低 {@code shift}
     * 位按 round-to-nearest-even 舍入。返回保留值——可能因进位超过
     * 调用方期望的位宽（最多多 1 位），由调用方处理。
     *
     * <p>{@code shift} 的有效范围是 {@code [1, 24]}——调用方从 half
     * 转换场景推导。{@code shift == 0} 时返回 {@code mant} 不做舍入。
     */
    private static int roundShift(int mant, int shift) {
        if (shift <= 0) {
            return mant;
        }
        int truncated = mant & ((1 << shift) - 1);
        int kept      = mant >>> shift;
        int half      = 1 << (shift - 1);
        // round-to-nearest-even：
        //   大于一半 → 进位
        //   小于一半 → 不进位
        //   恰好等于一半 → 向偶数（kept 最低位为 0 保持，为 1 进位）
        if (truncated > half || (truncated == half && (kept & 1) == 1)) {
            kept++;
        }
        return kept;
    }

    /**
     * IEEE 754 half（binary16）位模式 → float。
     *
     * <p>half 的所有位模式都能精确转成 float——不存在精度损失，
     * 因此本方向无需舍入决策。
     *
     * <p><b>与 JDK 的关系</b>：若项目运行在 Java 20+，可改用
     * {@link Float#float16ToFloat(short)}。
     *
     * @param h 16 位 half 位模式
     * @return 转换后的 float
     */
    public static float halfToFloat(short h) {
        int bits = h & 0xFFFF;
        int sign = (bits & 0x8000) << 16;
        int exp  = (bits >>> 10) & 0x1F;
        int mant = bits & 0x3FF;

        if (exp == 0) {
            if (mant == 0) return Float.intBitsToFloat(sign);
            // 次正规数：规格化
            while ((mant & 0x400) == 0) {
                mant <<= 1;
                exp--;
            }
            exp++;
            mant &= 0x3FF;
            return Float.intBitsToFloat(
                    sign | ((exp + 127 - 15) << 23) | (mant << 13));
        }
        if (exp == 0x1F) {
            return Float.intBitsToFloat(sign | 0x7F800000 | (mant << 13));
        }
        return Float.intBitsToFloat(
                sign | ((exp + 127 - 15) << 23) | (mant << 13));
    }
}