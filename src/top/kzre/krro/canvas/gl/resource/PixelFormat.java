package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 像素格式。不可变值对象。
 *
 * <p>封装 GL 纹理格式的完整描述——三个字段总是成组出现，分开传
 * 容易不一致：
 * <ul>
 *   <li>{@code glInternalFormat} —— GPU 内部存储格式</li>
 *   <li>{@code glClientFormat}   —— 客户端通道布局</li>
 *   <li>{@code glType}           —— 客户端分量类型</li>
 * </ul>
 *
 * <p>每像素字节数由 {@code glInternalFormat} 派生，不单独存储。
 *
 * <p><b>深度格式</b>：纯深度格式（{@link #DEPTH16} / {@link #DEPTH24} /
 * {@link #DEPTH32F}）可作为 {@code GLFramebuffer} 的 depth attachment。
 * {@link #DEPTH24_STENCIL8} 是打包 depth-stencil 格式，<b>不满足</b>
 * {@code GLFramebuffer} 的"纯深度"约束，仅作数据描述，供未来
 * depth-stencil FBO 封装使用。
 *
 * <p><b>线程契约</b>：纯数据，无 GL 副作用，任意线程可构造和读取。
 */
public final class PixelFormat {

    // ═══════════════════════════════════════════════
    // 颜色格式
    // ═══════════════════════════════════════════════

    /**
     * RGBA 8 位/通道，每像素 4 字节。最常用。
     */
    public static final PixelFormat RGBA8 =
            new PixelFormat(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);

    /**
     * RGBA 16 位浮点/通道，每像素 8 字节。
     */
    public static final PixelFormat RGBA16F =
            new PixelFormat(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT);

    /**
     * RGBA 32 位浮点/通道，每像素 16 字节。
     */
    public static final PixelFormat RGBA32F =
            new PixelFormat(GL_RGBA32F, GL_RGBA, GL_FLOAT);

    /**
     * 单通道 8 位，每像素 1 字节。
     */
    public static final PixelFormat R8 =
            new PixelFormat(GL_R8, GL_RED, GL_UNSIGNED_BYTE);

    /**
     * 单通道 32 位浮点，每像素 4 字节。
     */
    public static final PixelFormat R32F =
            new PixelFormat(GL_R32F, GL_RED, GL_FLOAT);

    // ═══════════════════════════════════════════════
    // 深度格式（纯深度）
    // ═══════════════════════════════════════════════

    /**
     * 16 位定点深度，每像素 2 字节。精度低，适合对 depth 精度不敏感的
     * 离屏 pass。
     */
    public static final PixelFormat DEPTH16 =
            new PixelFormat(GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT);

    /**
     * 24 位定点深度，每像素 3 字节。前向渲染 / 后处理的常用默认深度格式。
     */
    public static final PixelFormat DEPTH24 =
            new PixelFormat(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT);

    /**
     * 32 位浮点深度，每像素 4 字节。精度最高，适合大深度范围或
     * 需要读回深度做重建的场景。
     */
    public static final PixelFormat DEPTH32F =
            new PixelFormat(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT);

    // ═══════════════════════════════════════════════
    // 打包 depth-stencil（非纯深度）
    // ═══════════════════════════════════════════════

    /**
     * 24 位深度 + 8 位 stencil 打包，每像素 4 字节。
     *
     * <p><b>不是纯深度格式</b>——不满足
     * {@code FboUtils.requirePureDepthFormat} 的约束，不能作为
     * {@code GLFramebuffer.Builder#depthAttachment}。保留此常量是为了
     * 让未来 {@code GLDepthStencilFramebuffer} 之类的封装能复用同一
     * 套格式描述。
     */
    public static final PixelFormat DEPTH24_STENCIL8 =
            new PixelFormat(GL_DEPTH24_STENCIL8, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8);

    private final int glInternalFormat;
    private final int glClientFormat;
    private final int glType;

    PixelFormat(int glInternalFormat, int glClientFormat, int glType) {
        this.glInternalFormat = glInternalFormat;
        this.glClientFormat = glClientFormat;
        this.glType = glType;
    }

    /**
     * 纹理内部格式，用于 {@code glTexImage3D} / {@code glTexStorage3D}。
     */
    public int getGlInternalFormat() {
        return glInternalFormat;
    }

    /**
     * 客户端格式，用于 {@code glTexSubImage3D} / {@code glGetTexImage}。
     */
    public int getGlClientFormat() {
        return glClientFormat;
    }

    /**
     * 客户端类型，用于 {@code glTexSubImage3D} / {@code glGetTexImage}。
     */
    public int getGlType() {
        return glType;
    }

    /**
     * 每像素字节数。由 {@code glInternalFormat} 派生。
     *
     * @throws IllegalStateException 未知的内部格式
     */
    public int internalBytesPerPixel() {
        switch (glInternalFormat) {
            // ── 颜色 ──
            case GL_RGBA8:
                return 4;
            case GL_RGBA16F:
                return 8;
            case GL_RGBA32F:
                return 16;
            case GL_R8:
                return 1;
            case GL_R32F:
                return 4;

            // ── 纯深度 ──
            case GL_DEPTH_COMPONENT16:
                return 2;
            case GL_DEPTH_COMPONENT24:
                return 3;
            case GL_DEPTH_COMPONENT32F:
                return 4;

            // ── 打包 depth-stencil ──
            case GL_DEPTH24_STENCIL8:
                return 4;

            default:
                throw new IllegalStateException(
                        "unknown internal format: 0x"
                                + Integer.toHexString(glInternalFormat));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PixelFormat)) return false;
        PixelFormat that = (PixelFormat) o;
        return glInternalFormat == that.glInternalFormat
                && glClientFormat == that.glClientFormat
                && glType == that.glType;
    }

    @Override
    public int hashCode() {
        int h = glInternalFormat;
        h = 31 * h + glClientFormat;
        h = 31 * h + glType;
        return h;
    }
}