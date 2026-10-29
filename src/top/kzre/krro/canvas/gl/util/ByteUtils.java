package top.kzre.krro.canvas.gl.util;

/**
 * 8 位无符号字节与 [0,1] float 之间的转换。
 *
 * <p><b>约定</b>：
 * <ul>
 *   <li>{@code byte} 一律按无符号解释（0..255）。调用方从
 *       {@link java.nio.ByteBuffer} 读出后直接传入即可——本类内部
 *       用 {@code & 0xFF} 处理符号扩展。</li>
 *   <li>float 的 [0,1] 区间线性映射到 [0,255]；越界值饱和到端点。</li>
 * </ul>
 *
 * <p><b>舍入</b>：float→byte 采用 round-half-away-from-zero 的等价
 * 形式 {@code (int)(v * 255 + 0.5)}——仅在正值域使用（输入经饱和
 * 钳制前先算乘加，负值最后统一截到 0，不会有半值舍入歧义）。
 *
 * <p><b>线程契约</b>：纯函数，无状态，任意线程可调用。
 */
public final class ByteUtils {

    private ByteUtils() {
    }

    /**
     * 饱和转换：float [0,1] → 无符号字节 [0,255]。
     *
     * <p>{@code v <= 0} 返回 {@code 0}；{@code v >= 1} 返回 {@code (byte) 255}；
     * 其余按 {@code (byte)(v * 255 + 0.5)} 舍入。
     *
     * @param v 输入值
     * @return 8 位无符号表示（Java byte 类型，视作无符号）
     */
    public static byte toByte(float v) {
        int b = (int) (v * 255f + 0.5f);
        if (b < 0) return 0;
        if (b > 255) return (byte) 255;
        return (byte) b;
    }

    /**
     * 无符号字节 → float [0,1]。
     *
     * <p>{@code byte} 参数被视为无符号——内部用 {@code & 0xFF} 转换。
     *
     * @param b 8 位无符号值（Java byte 类型）
     * @return [0,1] 区间的 float
     */
    public static float toFloat8(byte b) {
        return (b & 0xFF) / 255f;
    }
}