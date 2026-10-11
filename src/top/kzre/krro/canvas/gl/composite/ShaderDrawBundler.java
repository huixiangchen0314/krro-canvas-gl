package top.kzre.krro.canvas.gl.composite;

import java.util.List;

/**
 * 图层分组器——按 shader 对图层分组。
 *
 * <p><b>职责</b>：把连续的图层列表切分成若干 {@link ShaderLayerBundle}，
 * 每个 bundle 使用同一个 shader。
 *
 * <p>分组的依据是每个图层的混合模式（blend mode）到 shader 的映射。
 * 同一 shader 的连续图层应合并到一个 bundle；shader 变化处切分。
 *
 * <p><b>顺序契约</b>：
 * <ul>
 *   <li>输入 layers 的顺序即合成顺序，最底层在前。</li>
 *   <li>输出 bundles 的顺序即执行顺序。</li>
 *   <li>同一 bundle 内，layers 的相对顺序与输入一致。</li>
 *   <li>跨 bundle 的相对顺序与输入一致——不允许重排。</li>
 * </ul>
 *
 * <p><b>合并策略</b>：相邻的、映射到同一 shader 的图层应合并为一个
 * bundle；不相邻的同 shader 图层是否合并由实现决定——但若合并会
 * 破坏上述顺序契约，则不得合并。
 *
 * <p><b>边界情况</b>：
 * <ul>
 *   <li>{@code layers} 为 {@code null} 时抛异常。</li>
 *   <li>{@code layers} 为空时返回空列表。</li>
 *   <li>不可见图层是否参与分组由实现决定——推荐先过滤再分组。</li>
 * </ul>
 *
 * <p><b>线程契约</b>：纯函数，无状态。
 */
public interface ShaderDrawBundler {
    /**
     * 按 shader 对图层分组。
     *
     * @param layers 完整图层列表，顺序即合成顺序，最底层在前
     * @return 按顺序排列的 bundle 列表；{@code layers} 为空时返回空列表
     * @throws IllegalArgumentException {@code layers} 为 {@code null}
     */
    List<ShaderLayerBundle> bundle(List<ILayer> layers);
}