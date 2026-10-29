package top.kzre.krro.canvas.gl.resource;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FLOAT;

/**
 * {@link GLInstanceBuffer} 的 float 视图。
 *
 * <p><b>视图语义</b>：本类不拥有 GL 资源——只把字节级核心解释为
 * float 语义。核心的生命周期由外部管理（通常由池借出 / 归还）。
 * 因此本类<b>没有 close</b>——释放是核心的事。
 *
 * <p><b>与核心的关系</b>：
 * <ul>
 *   <li>stride：{@code strideFloats * Float.BYTES}</li>
 *   <li>attribute 拆分：{@link #bind(int)} 按每 4 float 一个 vec4
 *       从 {@code baseLocation} 起连续分配 location，尾部不足 4 个
 *       float 用一个 vecN</li>
 *   <li>upload：FloatBuffer 零拷贝 reinterpret 为 ByteBuffer 后交给核心</li>
 * </ul>
 *
 * <p><b>线程契约</b>：
 * <ul>
 *   <li>{@link #create}、{@link #bind(int)}、{@link #upload} 内部的 GL
 *       调用必须在 GL 线程执行；</li>
 *   <li>{@link #isClosed()} 可被任意线程读——它透传核心的
 *       {@code volatile closed}。</li>
 * </ul>
 */
public final class FloatInstanceBuffer {

    private final GLInstanceBuffer inner;
    private final int strideFloats;

    private FloatInstanceBuffer(GLInstanceBuffer inner, int strideFloats) {
        this.inner = inner;
        this.strideFloats = strideFloats;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 创建 float 视图，内部自动建核心。
     *
     * @param strideFloats     每实例 float 数，至少 1
     * @param initialInstances 初始容量（实例数），至少 1
     */
    public static FloatInstanceBuffer create(int strideFloats, int initialInstances) {
        if (strideFloats < 1) {
            throw new IllegalArgumentException(
                    "strideFloats must be >= 1: " + strideFloats);
        }
        GLInstanceBuffer inner = GLInstanceBuffer.create(
                strideFloats * Float.BYTES, initialInstances);
        return new FloatInstanceBuffer(inner, strideFloats);
    }

    /**
     * 包装已有核心。要求字节 stride 是 4 的倍数且至少 4。
     *
     * @throws IllegalArgumentException inner 为 null、字节 stride 不是
     *                                  4 的倍数，或小于 4
     */
    public static FloatInstanceBuffer wrap(GLInstanceBuffer inner) {
        if (inner == null) {
            throw new IllegalArgumentException("inner must not be null");
        }
        if (inner.getStrideBytes() < Float.BYTES
                || inner.getStrideBytes() % Float.BYTES != 0) {
            throw new IllegalArgumentException(
                    "byte stride " + inner.getStrideBytes()
                            + " is not a positive multiple of 4");
        }
        return new FloatInstanceBuffer(inner, inner.getStrideBytes() / Float.BYTES);
    }

    // ═══════════════════════════════════════════════
    // 绑定
    // ═══════════════════════════════════════════════

    /**
     * 把本视图的 float attribute 配置写入<b>当前已绑定的 VAO</b>。
     *
     * <p><b>拆分规则</b>：从 {@code baseLocation} 起，每 4 个 float 一个
     * {@code vec4}；尾部不足 4 个 float 用一个 {@code vecN}
     * （{@code N = 余数}）。每个 attribute 的 {@code divisor = 1}
     * （逐实例更新）。
     *
     * <p><b>VAO 契约</b>：与核心一致——本方法<b>不绑定/不恢复 VAO</b>。
     * 调用方（pass）必须在调用前自行 {@code glBindVertexArray(targetVao)}。
     *
     * <p><b>越界校验</b>：由核心 {@link GLInstanceBuffer#bind(List)} 完成。
     * 本视图拆分出的 attribute 天然满足
     * {@code offsetBytes + components * 4 <= strideBytes}。
     *
     * @param baseLocation 第一个 attribute 的 location，必须 ≥ 0
     * @return 本次占用的 location 数，调用方可用它继续分配下一个 buffer
     * 的 baseLocation
     * @throws IllegalArgumentException baseLocation &lt; 0
     * @throws IllegalStateException    核心已关闭
     */
    public int bind(int baseLocation) {
        if (baseLocation < 0) {
            throw new IllegalArgumentException(
                    "baseLocation must be >= 0: " + baseLocation);
        }

        List<GLInstanceBuffer.Attribute> attrs = new ArrayList<>();
        int offsetFloats = 0;
        int remaining = strideFloats;
        int loc = baseLocation;
        while (remaining > 0) {
            int comps = Math.min(remaining, 4);
            attrs.add(new GLInstanceBuffer.Attribute(
                    loc, comps, GL_FLOAT,
                    offsetFloats * Float.BYTES, false, 1));
            offsetFloats += comps;
            remaining -= comps;
            loc++;
        }
        inner.bind(attrs);
        return loc - baseLocation;
    }

    // ═══════════════════════════════════════════════
    // 上传
    // ═══════════════════════════════════════════════

    /**
     * 上传实例数据。零拷贝——FloatBuffer 的剩余部分直接视图化为
     * ByteBuffer 交给核心。
     *
     * <p><b>direct 要求</b>：{@code data} 必须是 direct buffer。堆内
     * {@code FloatBuffer} 没有稳定的 native 地址，无法零拷贝；若需要
     * 从堆内数组上传，调用方先用 {@code MemoryUtil.memAllocFloat} 拷贝。
     *
     * @param data  position 到 limit 之间至少 {@code count * strideFloats} 个 float
     * @param count 实例数
     * @throws IllegalArgumentException count &lt; 0、data 空间不足，或 data 非 direct
     * @throws IllegalStateException    核心已关闭
     */
    public void upload(FloatBuffer data, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0: " + count);
        }
        int floats = count * strideFloats;
        if (data.remaining() < floats) {
            throw new IllegalArgumentException(
                    "data has " + data.remaining() + " floats but need " + floats);
        }
        if (!data.isDirect()) {
            throw new IllegalArgumentException(
                    "data must be a direct FloatBuffer");
        }

        FloatBuffer slice = data.slice();
        slice.limit(floats);
        ByteBuffer bytes = MemoryUtil.memByteBuffer(
                MemoryUtil.memAddress(slice),
                floats * Float.BYTES);
        inner.upload(bytes, count);
    }

    // ═══════════════════════════════════════════════
    // 视图访问器
    // ═══════════════════════════════════════════════

    /**
     * 底层核心。视图不拥有核心——调用方持有核心的所有权，通常
     * 由池管理（借出 / 归还）。
     */
    public GLInstanceBuffer buffer() {
        return inner;
    }

    /**
     * 每实例 float 数。
     */
    public int getStrideFloats() {
        return strideFloats;
    }

    /**
     * 每实例字节数（透传核心）。
     */
    public int getStrideBytes() {
        return inner.getStrideBytes();
    }

    /**
     * 实例容量（透传核心）。
     */
    public int getCapacityInstances() {
        return inner.getCapacityInstances();
    }

    /**
     * 核心的 VBO 句柄（透传，调试用）。
     */
    public int getVbo() {
        return inner.getVbo();
    }

    /**
     * 核心是否已释放。视图不释放核心，但可查询状态。
     * 可被任意线程读——透传核心的 {@code volatile closed}。
     */
    public boolean isClosed() {
        return inner.isClosed();
    }
}