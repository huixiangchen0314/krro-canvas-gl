package top.kzre.krro.canvas.gl.composite;

import java.util.List;

/**
 * 同一 shader 的图层组——{@link ShaderDrwBundler} 的产出。
 *
 * <p><b>语义</b>：这些图层可以用同一个 shader 一次或多次绘制。
 * bundle 是"执行单元"，不是"绘制单元"——一个 bundle 可以进一步
 * 切分成多个批次（受 atlas 容量限制），但所有批次共享同一个 shader。
 *
 * <p><b>shaderKey</b>：字符串标识，不是 Shader 实例。Planner 按此
 * 标识从 program 缓存/工厂取到实际 shader。标识的语义由上层约定——
 * 常见形式是 blend 模式名、材质类型名等。
 *
 * <p><b>顺序契约</b>：
 * <ul>
 *   <li>{@code layers} 的顺序即合成顺序，最底层在前。</li>
 *   <li>同一 bundle 内的图层必须连续或按 bundler 的规则排列，
 *       具体顺序由 {@link ShaderDrwBundler} 决定。</li>
 * </ul>
 *
 * <p><b>不可变性</b>：本类只持有引用，不做防御性拷贝。
 * 构造方保证 {@code layers} 在 bundle 生命周期内不被修改。
 * 若需修改，请构造新实例。
 *
 * <p><b>线程契约</b>：字段 final，安全发布；只读使用可多线程。
 */
public final class ShaderLayerBundle {

    private final String       shaderKey;
    private final List<ILayer> layers;

    /**
     * @param shaderKey shader 标识，非空
     * @param layers    使用该 shader 的图层列表，非空
     * @throws NullPointerException 任一参数为 {@code null}
     */
    public ShaderLayerBundle(String shaderKey, List<ILayer> layers) {
        if (shaderKey == null) throw new NullPointerException("shaderKey");
        if (layers == null) throw new NullPointerException("layers");
        this.shaderKey = shaderKey;
        this.layers = layers;
    }

    /** shader 标识。 */
    public String getShaderKey() {
        return shaderKey;
    }

    /**
     * 使用该 shader 的图层列表。
     *
     * <p>顺序即合成顺序，最底层在前。契约：调用方不得修改返回的列表。
     */
    public List<ILayer> getLayers() {
        return layers;
    }
}