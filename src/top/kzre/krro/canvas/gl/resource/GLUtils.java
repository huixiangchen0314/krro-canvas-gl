package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL11.GL_BYTE;
import static org.lwjgl.opengl.GL11.GL_DOUBLE;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_INT;
import static org.lwjgl.opengl.GL11.GL_SHORT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL30.GL_HALF_FLOAT;
import static org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_10F_11F_11F_REV;
import static org.lwjgl.opengl.GL33.GL_INT_2_10_10_10_REV;
import static org.lwjgl.opengl.GL33.GL_UNSIGNED_INT_2_10_10_10_REV;

/**
 * GL 常量与字节布局相关的纯函数工具。
 *
 * <p><b>无 GL 调用</b>：本类所有方法只做常量判断和整数运算，不调用
 * 任何 {@code gl*} 函数，因此不要求 GL 线程、不要求 current context。
 *
 * <p><b>线程契约</b>：任意线程可调用。
 */
public final class GLUtils {

    private GLUtils() {}

    /**
     * 计算一个顶点 attribute 占用的字节数。
     *
     * <p><b>普通标量类型</b>：{@code components * sizeOf(glType)}。
     *
     * <p><b>packed 类型</b>（{@code GL_INT_2_10_10_10_REV} /
     * {@code GL_UNSIGNED_INT_2_10_10_10_REV} /
     * {@code GL_UNSIGNED_INT_10F_11F_11F_REV}）：固定 4 字节，
     * <b>与 {@code components} 无关</b>。这类类型把 4 个分量压进一个
     * 32 位字，{@code glVertexAttribPointer} 的 size 只决定分量如何
     * 解包，不改变存储宽度。
     *
     * <p><b>未知 {@code glType}</b>：抛异常。宁可拒绝，不静默返回一个
     * 可能错误的字节数——长度校验依赖这个值，错算会导致越界检查失效。
     *
     * @param glType     GL 类型常量
     * @param components 分量数 1~4（对 packed 类型被忽略）
     * @return 该 attribute 在 VBO 中占用的字节数
     * @throws IllegalArgumentException 未知 {@code glType}
     */
    public static int attributeByteSize(int glType, int components) {
        switch (glType) {
            // ── 1 字节 / 分量 ──
            case GL_BYTE:
            case GL_UNSIGNED_BYTE:
                return components;

            // ── 2 字节 / 分量 ──
            case GL_SHORT:
            case GL_UNSIGNED_SHORT:
            case GL_HALF_FLOAT:
                return components * 2;

            // ── 4 字节 / 分量 ──
            case GL_INT:
            case GL_UNSIGNED_INT:
            case GL_FLOAT:
                return components * 4;

            // ── 8 字节 / 分量 ──
            case GL_DOUBLE:
                return components * 8;

            // ── packed：整个 attribute 固定 4 字节 ──
            case GL_INT_2_10_10_10_REV:
            case GL_UNSIGNED_INT_2_10_10_10_REV:
            case GL_UNSIGNED_INT_10F_11F_11F_REV:
                return 4;

            default:
                throw new IllegalArgumentException(
                        "unsupported glType 0x" + Integer.toHexString(glType));
        }
    }
}