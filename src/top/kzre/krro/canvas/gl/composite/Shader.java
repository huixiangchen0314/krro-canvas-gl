package top.kzre.krro.canvas.gl.composite;

import java.util.List;

/**
 * 合成着色器。封装一次 draw call 的完整生命周期。
 *
 * <p><b>声明依赖</b>：{@link #tiles()} 返回本 shader 需要的全部
 * {@link TileBufferGroup}。{@link #instanceLocation(int)} 返回每个
 * group 的实例 attribute location——由 shader 的 vertex 声明决定。
 *
 * <p><b>不持有所有权</b>：shader 引用的 {@code GLProgram} 和
 * {@code TileBufferGroup} 由创建方管理生命周期。
 *
 * <p><b>线程契约</b>：{@code bind} / {@code unbind} 涉及 GL 调用，
 * 必须在 GL 线程。{@code tiles()} / {@code instanceLocation} /
 * {@code backdropUnit()} 是纯数据读取，任意线程可调。
 */
public interface Shader {

    /**
     * 返回本 shader 依赖的全部 {@link TileBufferGroup}。
     *
     * <p>列表顺序即 group 在 shader 逻辑中的角色顺序。单 group
     * shader 返回单元素列表。
     */
    List<TileBufferGroup> tiles();

    /**
     * 第 {@code groupIndex} 个 group 的实例 attribute location。
     *
     * <p>由 shader 的 vertex 声明决定——比如 group 0 用 location 1，
     * group 1 用 location 2。执行器把 group 的实例数据绑到这个
     * location 上。
     *
     * @param groupIndex group 在 {@link #tiles()} 列表中的下标
     * @return attribute location
     */
    int instanceLocation(int groupIndex);


    /** 激活 program，配置 uniform。 */
    void bind();

    /** 解绑 program。 */
    void unbind();
}