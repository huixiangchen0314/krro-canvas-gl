package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL11.GL_STENCIL_INDEX;

import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT32;
import static org.lwjgl.opengl.GL30.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH32F_STENCIL8;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL30.GL_STENCIL_INDEX8;

/**
 * FBO 格式判定的纯函数工具。
 *
 * <p><b>无 GL 调用</b>：本类只做常量比较，不调用任何 {@code gl*}
 * 函数，不要求 GL 线程、不要求 current context。
 *
 * <p><b>只处理 internal format</b>：参数是 {@code glTexImage2D} /
 * {@code glRenderbufferStorage} 的 {@code internalformat} 值（sized
 * 格式，如 {@code GL_DEPTH_COMPONENT24}），不是传输格式
 * （{@code format}，如 {@code GL_DEPTH_COMPONENT}），也不是元素
 * 类型（{@code type}，如 {@code GL_UNSIGNED_INT}）。
 *
 * <p><b>不支持 renderbuffer 判定</b>：renderbuffer 的 internal format
 * 通过 {@code glGetRenderbufferParameteriv} 查询，不在本类职责内。
 * 需要时由调用方查询后传值进来。
 *
 * <p><b>线程契约</b>：任意线程可调用。
 */
public final class FboUtils {

    private FboUtils() {}

    // ═══════════════════════════════════════════════
    // 纯深度格式
    // ═══════════════════════════════════════════════

    /**
     * 判断 internal format 是否为<b>纯深度</b>格式。
     *
     * <p>纯深度格式可以挂载到 {@code GL_DEPTH_ATTACHMENT}，不能挂载
     * 到 {@code GL_DEPTH_STENCIL_ATTACHMENT}。
     *
     * <p><b>支持的格式</b>：{@code GL_DEPTH_COMPONENT16} /
     * {@code GL_DEPTH_COMPONENT24} / {@code GL_DEPTH_COMPONENT32F}。
     *
     * <p><b>不支持</b>：
     * <ul>
     *   <li>{@code GL_DEPTH_COMPONENT32}（32 位整数深度）——很少用，
     *       需要时显式加白名单</li>
     *   <li>{@code GL_DEPTH_COMPONENT}（unsized）——驱动选默认精度，
     *       精度不可控</li>
     *   <li>所有打包 depth-stencil 格式——用
     *       {@link #isPackedDepthStencilFormat(int)} 判定</li>
     * </ul>
     */
    public static boolean isPureDepthFormat(int glInternalFormat) {
        return glInternalFormat == GL_DEPTH_COMPONENT16
                || glInternalFormat == GL_DEPTH_COMPONENT24
                || glInternalFormat == GL_DEPTH_COMPONENT32F;
    }

    /**
     * 校验 internal format 是纯深度格式，不满足时抛异常。
     *
     * @param glInternalFormat internal format 值
     * @param context          调用方上下文（如 "GLFramebuffer"），
     *                         用于错误信息
     * @throws IllegalArgumentException 不是纯深度格式
     */
    public static void requirePureDepthFormat(int glInternalFormat,
                                              String context) {
        if (isPureDepthFormat(glInternalFormat)) {
            return;
        }
        String hint = isPackedDepthStencilFormat(glInternalFormat)
                ? " — for packed depth-stencil use GLDepthStencilFramebuffer"
                : "";
        throw new IllegalArgumentException(
                context + " only supports pure depth internal formats "
                        + "(GL_DEPTH_COMPONENT16/24/32F); got 0x"
                        + Integer.toHexString(glInternalFormat)
                        + hint);
    }

    // ═══════════════════════════════════════════════
    // 打包 depth-stencil 格式
    // ═══════════════════════════════════════════════

    /**
     * 判断 internal format 是否为<b>打包 depth-stencil</b>格式。
     *
     * <p>打包格式挂载到 {@code GL_DEPTH_STENCIL_ATTACHMENT}，不能
     * 挂载到 {@code GL_DEPTH_ATTACHMENT} 或 {@code GL_STENCIL_ATTACHMENT}。
     *
     * <p><b>支持的格式</b>：{@code GL_DEPTH24_STENCIL8} /
     * {@code GL_DEPTH32F_STENCIL8}。
     */
    public static boolean isPackedDepthStencilFormat(int glInternalFormat) {
        return glInternalFormat == GL_DEPTH24_STENCIL8
                || glInternalFormat == GL_DEPTH32F_STENCIL8;
    }

    // ═══════════════════════════════════════════════
    // 纯 stencil 格式
    // ═══════════════════════════════════════════════

    /**
     * 判断 internal format 是否为<b>纯 stencil</b>格式。
     *
     * <p>纯 stencil 格式挂载到 {@code GL_STENCIL_ATTACHMENT}。
     *
     * <p><b>支持的格式</b>：{@code GL_STENCIL_INDEX8}。
     */
    public static boolean isPureStencilFormat(int glInternalFormat) {
        return glInternalFormat == GL_STENCIL_INDEX8;
    }

    // ═══════════════════════════════════════════════
    // 格式描述（诊断用）
    // ═══════════════════════════════════════════════

    /**
     * 返回 internal format 的可读描述。用于日志和错误信息。
     *
     * <p>已知格式返回名字，未知返回十六进制。
     */
    public static String describeFormat(int glInternalFormat) {
        switch (glInternalFormat) {
            case GL_DEPTH_COMPONENT16:      return "GL_DEPTH_COMPONENT16";
            case GL_DEPTH_COMPONENT24:      return "GL_DEPTH_COMPONENT24";
            case GL_DEPTH_COMPONENT32:      return "GL_DEPTH_COMPONENT32";
            case GL_DEPTH_COMPONENT32F:     return "GL_DEPTH_COMPONENT32F";
            case GL_DEPTH24_STENCIL8:       return "GL_DEPTH24_STENCIL8";
            case GL_DEPTH32F_STENCIL8:      return "GL_DEPTH32F_STENCIL8";
            case GL_STENCIL_INDEX8:         return "GL_STENCIL_INDEX8";
            case GL_STENCIL_INDEX:          return "GL_STENCIL_INDEX";
            default:                        return "0x" + Integer.toHexString(glInternalFormat);
        }
    }
}