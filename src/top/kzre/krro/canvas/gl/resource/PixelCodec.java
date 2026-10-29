package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.canvas.gl.util.ByteUtils;
import top.kzre.krro.canvas.gl.util.PrecisionUtils;

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
 * <p><b>预乘语义</b>：{@link #packPreAlpha} 与 {@link #unpackPreAlpha}
 * 处理 premultiplied alpha。上传路径用前者把非预乘数据转预乘存储，
 * 下载路径用后者把预乘数据还原为非预乘。仅对 4 通道格式有效——
 * R/RG/RGB 无 alpha 通道，退化为普通 {@link #pack}/{@link #unpack}。
 *
 * <p><b>线程契约</b>：纯数据转换，无 GL 调用，任意线程可调用。
 */
public final class PixelCodec {

    private PixelCodec() {
    }

    // ═══════════════════════════════════════════════
    // 计量
    // ═══════════════════════════════════════════════

    /**
     * 每像素通道数，由 {@code glClientFormat} 推导。
     */
    public static int channels(PixelFormat fmt) {
        switch (fmt.getGlClientFormat()) {
            case GL_RED:
                return 1;
            case GL_RG:
                return 2;
            case GL_RGB:
                return 3;
            case GL_RGBA:
                return 4;
            default:
                throw new IllegalStateException(
                        "unknown client format: 0x"
                                + Integer.toHexString(fmt.getGlClientFormat()));
        }
    }

    /**
     * CPU 侧需要的 float 数量。
     */
    public static int cpuFloats(PixelFormat fmt, int pixelCount) {
        return pixelCount * channels(fmt);
    }

    /**
     * GPU 侧占用的字节数。
     */
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
     * @throws IllegalArgumentException      buffer 剩余空间不足
     * @throws UnsupportedOperationException 格式无打包实现
     */
    public static void pack(PixelFormat fmt, FloatBuffer src, int pixelCount,
                            ByteBuffer dst) {
        int needFloats = cpuFloats(fmt, pixelCount);
        int needBytes = gpuBytes(fmt, pixelCount);
        checkRoom(src.remaining() >= needFloats,
                "src", src.remaining(), needFloats, "floats");
        checkRoom(dst.remaining() >= needBytes,
                "dst", dst.remaining(), needBytes, "bytes");

        ByteBuffer out = ensureNativeOrder(dst);

        switch (fmt.getGlInternalFormat()) {
            case GL_RGBA8:
                packRgba8(src, pixelCount, out);
                break;
            case GL_R8:
                packR8(src, pixelCount, out);
                break;
            case GL_R32F:
                packR32f(src, pixelCount, out);
                break;
            case GL_RGBA32F:
                packRgba32f(src, pixelCount, out);
                break;
            case GL_RGBA16F:
                packRgba16f(src, pixelCount, out);
                break;
            default:
                throw new UnsupportedOperationException(
                        "pack not supported for format: " + fmt);
        }

        advanceIfDuplicate(dst, out, needBytes);
    }

    /**
     * 打包并预乘：CPU 非预乘 float → GPU 预乘字节。
     *
     * <p>与 {@link #pack} 相同，只是每个像素先执行 {@code rgb *= a}
     * 再写入。上传路径使用——保证图集纹理内部统一为预乘语义，
     * 后续着色器无需再做 {@code c.rgb *= c.a}。
     *
     * <p><b>逐像素流式处理</b>：不分配临时缓冲、不修改 {@code src}
     * 内容——只推进 position。源缓冲可安全复用。
     *
     * <p><b>4 通道限制</b>：仅 RGBA 格式有效。R/RG/RGB 无 alpha
     * 通道，退化为普通 {@link #pack}。
     *
     * @throws IllegalArgumentException      buffer 剩余空间不足
     * @throws UnsupportedOperationException 格式无打包实现
     */
    public static void packPreAlpha(PixelFormat fmt, FloatBuffer src, int pixelCount,
                                    ByteBuffer dst) {
        int needFloats = cpuFloats(fmt, pixelCount);
        int needBytes = gpuBytes(fmt, pixelCount);
        checkRoom(src.remaining() >= needFloats,
                "src", src.remaining(), needFloats, "floats");
        checkRoom(dst.remaining() >= needBytes,
                "dst", dst.remaining(), needBytes, "bytes");

        ByteBuffer out = ensureNativeOrder(dst);

        switch (fmt.getGlInternalFormat()) {
            case GL_RGBA8:
                packRgba8PreAlpha(src, pixelCount, out);
                break;
            case GL_R8:
                packR8(src, pixelCount, out);
                break;
            case GL_R32F:
                packR32f(src, pixelCount, out);
                break;
            case GL_RGBA32F:
                packRgba32fPreAlpha(src, pixelCount, out);
                break;
            case GL_RGBA16F:
                packRgba16fPreAlpha(src, pixelCount, out);
                break;
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
     * 两个 buffer 的 position 都会前进相应数量。
     */
    public static void unpack(PixelFormat fmt, ByteBuffer src, int pixelCount,
                              FloatBuffer dst) {
        int needFloats = cpuFloats(fmt, pixelCount);
        int needBytes = gpuBytes(fmt, pixelCount);
        checkRoom(src.remaining() >= needBytes,
                "src", src.remaining(), needBytes, "bytes");
        checkRoom(dst.remaining() >= needFloats,
                "dst", dst.remaining(), needFloats, "floats");

        ByteBuffer in = ensureNativeOrder(src);

        switch (fmt.getGlInternalFormat()) {
            case GL_RGBA8:
                unpackRgba8(in, pixelCount, dst);
                break;
            case GL_R8:
                unpackR8(in, pixelCount, dst);
                break;
            case GL_R32F:
                unpackR32f(in, pixelCount, dst);
                break;
            case GL_RGBA32F:
                unpackRgba32f(in, pixelCount, dst);
                break;
            case GL_RGBA16F:
                unpackRgba16f(in, pixelCount, dst);
                break;
            default:
                throw new UnsupportedOperationException(
                        "unpack not supported for format: " + fmt);
        }

        advanceIfDuplicate(src, in, needBytes);
    }

    /**
     * 解包并反预乘：GPU 预乘字节 → CPU 非预乘 float。
     *
     * <p>与 {@link #unpack} 相同，只是每个像素再执行 {@code rgb /= a}。
     * 下载路径使用——把内部预乘的图集内容还原为调用方期望的非预乘表示。
     *
     * <p><b>alpha == 0 的处理</b>：{@code a == 0} 时除法无定义。约定
     * rgb 写 0——完全透明像素的颜色不参与视觉，任何取值都等价。
     *
     * <p><b>逐像素流式处理</b>：不分配临时缓冲、不修改 {@code src}
     * 内容——只推进 position。
     *
     * <p><b>4 通道限制</b>：仅 RGBA 格式有效。其余格式退化为普通
     * {@link #unpack}。
     *
     * @throws IllegalArgumentException      buffer 剩余空间不足
     * @throws UnsupportedOperationException 格式无解包实现
     */
    public static void unpackPreAlpha(PixelFormat fmt, ByteBuffer src, int pixelCount,
                                      FloatBuffer dst) {
        int needFloats = cpuFloats(fmt, pixelCount);
        int needBytes = gpuBytes(fmt, pixelCount);
        checkRoom(src.remaining() >= needBytes,
                "src", src.remaining(), needBytes, "bytes");
        checkRoom(dst.remaining() >= needFloats,
                "dst", dst.remaining(), needFloats, "floats");

        ByteBuffer in = ensureNativeOrder(src);

        switch (fmt.getGlInternalFormat()) {
            case GL_RGBA8:
                unpackRgba8PreAlpha(in, pixelCount, dst);
                break;
            case GL_R8:
                unpackR8(in, pixelCount, dst);
                break;
            case GL_R32F:
                unpackR32f(in, pixelCount, dst);
                break;
            case GL_RGBA32F:
                unpackRgba32fPreAlpha(in, pixelCount, dst);
                break;
            case GL_RGBA16F:
                unpackRgba16fPreAlpha(in, pixelCount, dst);
                break;
            default:
                throw new UnsupportedOperationException(
                        "unpack not supported for format: " + fmt);
        }

        advanceIfDuplicate(src, in, needBytes);
    }

    // ═══════════════════════════════════════════════
    // 各格式实现——普通路径
    // ═══════════════════════════════════════════════

    private static void packRgba8(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(ByteUtils.toByte(src.get()));
            dst.put(ByteUtils.toByte(src.get()));
            dst.put(ByteUtils.toByte(src.get()));
            dst.put(ByteUtils.toByte(src.get()));
        }
    }

    private static void unpackRgba8(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(ByteUtils.toFloat8(src.get()));
            dst.put(ByteUtils.toFloat8(src.get()));
            dst.put(ByteUtils.toFloat8(src.get()));
            dst.put(ByteUtils.toFloat8(src.get()));
        }
    }

    private static void packR8(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(ByteUtils.toByte(src.get()));
        }
    }

    private static void unpackR8(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            dst.put(ByteUtils.toFloat8(src.get()));
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
            dst.putShort(PrecisionUtils.floatToHalf(src.get()));
        }
    }

    private static void unpackRgba16f(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n * 4; i++) {
            dst.put(PrecisionUtils.halfToFloat(src.getShort()));
        }
    }

    // ═══════════════════════════════════════════════
    // 各格式实现——预乘路径
    // ═══════════════════════════════════════════════

    /**
     * 打包 + 预乘。逐像素 {@code rgb *= a} 后写入。
     */
    private static void packRgba8PreAlpha(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            float r = src.get();
            float g = src.get();
            float b = src.get();
            float a = src.get();
            dst.put(ByteUtils.toByte(r * a));
            dst.put(ByteUtils.toByte(g * a));
            dst.put(ByteUtils.toByte(b * a));
            dst.put(ByteUtils.toByte(a));
        }
    }

    /**
     * 解包 + 反预乘。逐像素 {@code rgb /= a} 后写入。
     */
    private static void unpackRgba8PreAlpha(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            float r = ByteUtils.toFloat8(src.get());
            float g = ByteUtils.toFloat8(src.get());
            float b = ByteUtils.toFloat8(src.get());
            float a = ByteUtils.toFloat8(src.get());
            if (a > 0f) {
                float inv = 1f / a;
                dst.put(r * inv);
                dst.put(g * inv);
                dst.put(b * inv);
            } else {
                dst.put(0f);
                dst.put(0f);
                dst.put(0f);
            }
            dst.put(a);
        }
    }

    private static void packRgba32fPreAlpha(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            float r = src.get();
            float g = src.get();
            float b = src.get();
            float a = src.get();
            dst.putFloat(r * a);
            dst.putFloat(g * a);
            dst.putFloat(b * a);
            dst.putFloat(a);
        }
    }

    private static void unpackRgba32fPreAlpha(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            float r = src.getFloat();
            float g = src.getFloat();
            float b = src.getFloat();
            float a = src.getFloat();
            if (a > 0f) {
                float inv = 1f / a;
                dst.put(r * inv);
                dst.put(g * inv);
                dst.put(b * inv);
            } else {
                dst.put(0f);
                dst.put(0f);
                dst.put(0f);
            }
            dst.put(a);
        }
    }

    private static void packRgba16fPreAlpha(FloatBuffer src, int n, ByteBuffer dst) {
        for (int i = 0; i < n; i++) {
            float r = src.get();
            float g = src.get();
            float b = src.get();
            float a = src.get();
            dst.putShort(PrecisionUtils.floatToHalf(r * a));
            dst.putShort(PrecisionUtils.floatToHalf(g * a));
            dst.putShort(PrecisionUtils.floatToHalf(b * a));
            dst.putShort(PrecisionUtils.floatToHalf(a));
        }
    }

    private static void unpackRgba16fPreAlpha(ByteBuffer src, int n, FloatBuffer dst) {
        for (int i = 0; i < n; i++) {
            float r = PrecisionUtils.halfToFloat(src.getShort());
            float g = PrecisionUtils.halfToFloat(src.getShort());
            float b = PrecisionUtils.halfToFloat(src.getShort());
            float a = PrecisionUtils.halfToFloat(src.getShort());
            if (a > 0f) {
                float inv = 1f / a;
                dst.put(r * inv);
                dst.put(g * inv);
                dst.put(b * inv);
            } else {
                dst.put(0f);
                dst.put(0f);
                dst.put(0f);
            }
            dst.put(a);
        }
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