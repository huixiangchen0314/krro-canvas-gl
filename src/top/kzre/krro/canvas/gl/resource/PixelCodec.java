package top.kzre.krro.canvas.gl.resource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 像素数据编解码器：CPU float 表示 ↔ GPU 字节表示之间的转换。
 *
 * <p><b>CPU 侧约定</b>：每通道一个 float。8bit 格式约定 [0,1]，
 * 浮点格式无范围限制。通道数由 {@link PixelFormat#getGlClientFormat()} 推导。
 *
 * <p><b>GPU 侧</b>：由 {@link PixelFormat#getGlInternalFormat()} 决定实际布局。
 *
 * <p><b>字节序</b>：读写按 {@link ByteOrder#nativeOrder()} 进行。
 * 传入的 buffer 若不是 native order，方法内部在 duplicate 上切换，
 * 不改变调用方 buffer 的 order。
 *
 * <p><b>线程契约</b>：纯数据转换，无 GL 调用，任意线程可调用。
 */
public final class PixelCodec {

    private PixelCodec() {}

    // ═══════════════════════════════════════════════
    // 计量
    // ═══════════════════════════════════════════════

    /** 每像素通道数，由 {@code glClientFormat} 推导。 */
    public static int channels(PixelFormat fmt) {
        switch (fmt.getGlClientFormat()) {
            case GL_RED:  return 1;
            case GL_RG:   return 2;
            case GL_RGB:  return 3;
            case GL_RGBA: return 4;
            default:
                throw new IllegalStateException(
                        "unknown client format: 0x"
                                + Integer.toHexString(fmt.getGlClientFormat()));
        }
    }

    /** CPU 侧需要的 float 数量。 */
    public static int cpuFloats(PixelFormat fmt, int pixelCount) {
        return pixelCount * channels(fmt);
    }

    /** GPU 侧占用的字节数。 */
    public static int gpuBytes(PixelFormat fmt, int pixelCount) {
        return pixelCount * fmt.internalBytesPerPixel();
    }

    // ═══════════════════════════════════════════════
    // 打包 / 解包
    // ═══════════════════════════════════════════════

    /**
     * 打包：CPU float → GPU 字节。
     *
     * <p>读 {@code src} 的 {@code cpuFloats(fmt, pixelCount)} 个 float，
     * 写 {@code dst} 的 {@code gpuBytes(fmt, pixelCount)} 个字节。
     * 两个 buffer 的 position 都会前进相应数量。
     *
     * @throws IllegalArgumentException buffer 剩余空间不足
     * @throws UnsupportedOperationException 格式无打包实现
     */
    public static void pack(PixelFormat fmt, FloatBuffer src, int pixelCount,
                            ByteBuffer dst) {
        int needFloats = cpuFloats(fmt, pixelCount);
        int needBytes  = gpuBytes(fmt, pixelCount);
        checkRoom(src.remaining() >= needFloats,
                "src", src.remaining(), needFloats, "floats");
        checkRoom(dst.remaining() >= needBytes,
                "dst", dst.remaining(), needBytes, "bytes");

        ByteBuffer out = ensureNativeOrder(dst);

        switch (fmt.getGlInternalFormat()) {
            case GL_RGBA8:   packRgba8(src, pixelCount, out);   break;
            case GL_R8:      packR8(src, pixelCount, out);      break;
            case GL_R32F:    packR32f(src, pixelCount, out);    break;
            case GL_RGBA32F: packRgba32f(src, pixelCount, out); break;
            case GL_RGBA16F: packRgba16f(src, pixelCount, out); break;
            default:
                throw new UnsupportedOperationException(
                        "pack not supported for format: " + fmt);
        }

        advanceIfDuplicate(dst, out, needBytes);
    }

    /**
     * 解包：GPU 字节 → CPU float。
     *
     * <p>读 {@code src} 的 {@code gpuBytes(fmt, pixelCount)} 个字节，
     * 写 {@code dst} 的 {@code cpuFloats(fmt, pixelCount)} 个 float。
     */
    public static void unpack(PixelFormat fmt, ByteBuffer src, int pixelCount,
                              FloatBuffer dst) {
        int needFloats = cpuFloats(fmt, pixelCount);
        int needBytes  = gpuBytes(fmt, pixelCount);
        checkRoom(src.remaining() >= needBytes,
                "src", src.remaining(), needBytes, "bytes");
        checkRoom(dst.remaining() >= needFloats,
                "dst", dst.remaining(), needFloats, "floats");

        ByteBuffer in = ensureNativeOrder(src);

        switch (fmt.getGlInternalFormat()) {
            case GL_RGBA8:   unpackRgba8(in, pixelCount, dst);   break;
            case GL_R8:      unpackR8(in, pixelCount, dst);      break;
            case GL_R32F:    unpackR32f(in, pixelCount, dst);    break;
            case GL_RGBA32F: unpackRgba32f(in, pixelCount, dst); break;
            case GL_RGBA16F: unpackRgba16f(in, pixelCount, dst); break;
            default:
                throw new UnsupportedOperationException(
                        "unpack not supported for format: " + fmt);
        }

        advanceIfDuplicate(src, in, needBytes);
    }

    // ═══════════════════════════════════════════════
    // 各格式实现
    // ═══════════════════════════════════════════════

    private static void packRgba8(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(toByte(src.get()));
            dst.put(toByte(src.get()));
            dst.put(toByte(src.get()));
            dst.put(toByte(src.get()));
        }
    }

    private static void unpackRgba8(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(toFloat8(src.get()));
            dst.put(toFloat8(src.get()));
            dst.put(toFloat8(src.get()));
            dst.put(toFloat8(src.get()));
        }
    }

    private static void packR8(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(toByte(src.get()));
        }
    }

    private static void unpackR8(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(toFloat8(src.get()));
        }
    }

    private static void packR32f(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.putFloat(src.get());
        }
    }

    private static void unpackR32f(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(src.getFloat());
        }
    }

    private static void packRgba32f(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n * 4; i++) {
            dst.putFloat(src.get());
        }
    }

    private static void unpackRgba32f(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n * 4; i++) {
            dst.put(src.getFloat());
        }
    }

    private static void packRgba16f(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n * 4; i++) {
            dst.putShort(floatToHalf(src.get()));
        }
    }

    private static void unpackRgba16f(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n * 4; i++) {
            dst.put(halfToFloat(src.getShort()));
        }
    }

    // ═══════════════════════════════════════════════
    // 数值转换
    // ═══════════════════════════════════════════════

    private static byte toByte(float v) {
        int b = (int) (v * 255f + 0.5f);
        if (b < 0) return 0;
        if (b > 255) return (byte) 255;
        return (byte) b;
    }

    private static float toFloat8(byte b) {
        return (b & 0xFF) / 255f;
    }

    /**
     * float → IEEE 754 half 位模式。
     *
     * <p>若项目运行在 Java 20+，可改用 {@code Float.floatToFloat16(float)}，
     * 该方法为 JDK 内置，经过严格测试。
     */
    static short floatToHalf(float f) {
        int bits = Float.floatToIntBits(f);
        int sign = (bits >>> 16) & 0x8000;
        int exp  = (bits >>> 23) & 0xFF;
        int mant = bits & 0x7FFFFF;

        if (exp == 0xFF) {
            // Inf / NaN
            return (short) (sign | 0x7C00 | (mant != 0 ? 0x200 : 0));
        }
        int newExp = exp - 127 + 15;
        if (newExp >= 0x1F) {
            return (short) (sign | 0x7C00);        // 溢出 → Inf
        }
        if (newExp <= 0) {
            if (newExp < -10) return (short) sign; // 下溢 → 0
            mant |= 0x800000;
            int shift = 14 - newExp;
            return (short) (sign | (mant >>> shift));
        }
        return (short) (sign | (newExp << 10) | (mant >>> 13));
    }

    /**
     * IEEE 754 half 位模式 → float。
     *
     * <p>若项目运行在 Java 20+，可改用 {@code Float.float16ToFloat(short)}。
     */
    static float halfToFloat(short h) {
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

    // ═══════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════

    private static ByteBuffer ensureNativeOrder(ByteBuffer buf) {
        return buf.order() == ByteOrder.nativeOrder()
                ? buf
                : buf.duplicate().order(ByteOrder.nativeOrder());
    }

    private static void advanceIfDuplicate(ByteBuffer original, ByteBuffer used,
                                           int bytes) {
        if (used != original) {
            original.position(original.position() + bytes);
        }
    }

    private static void checkRoom(boolean ok, String name,
                                  int have, int need, String unit) {
        if (!ok) {
            throw new IllegalArgumentException(
                    name + " has " + have + " " + unit
                            + ", need " + need);
        }
    }
}