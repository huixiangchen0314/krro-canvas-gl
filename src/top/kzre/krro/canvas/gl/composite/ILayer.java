package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.util.tile.TiledCanvas;

/**
 * GL 合成用的图层抽象。
 *
 * <p><b>定位</b>：Clojure 侧完成光栅化后，把图层数据通过本接口
 * 交给 Java 侧的合成管线。它是光栅化和合成之间的边界契约。
 *
 * <p><b>数据来源</b>：
 * <ul>
 *   <li>{@code canvas} —— Clojure 光栅化的产物，局部空间瓦片</li>
 *   <li>其余字段 —— 从图层原始定义中提取，规划阶段直接消费</li>
 * </ul>
 *
 * <p><b>变换语义</b>：{@code transform} 是图层正向变换，本地 → 视口。
 * 逆变换由 shader 在片段阶段计算——合成管线不预求逆。
 *
 * <p><b>线程契约</b>：纯数据，任意线程可构造和读取。
 * @see top.kzre.colorutils.blend.Blends
 */
public interface ILayer {

    /**
     * 图层标识。用于调试、日志、缓存。不参与合成逻辑。
     */
    Object getId();

    /**
     * 光栅化后的局部空间瓦片。
     *
     * <p>返回 {@code null} 表示该图层无内容——规划阶段跳过。
     */
    TiledCanvas getCanvas();

    /**
     * 图层正向变换，6 元素仿射矩阵 {@code [a b c d tx ty]}，
     * 本地 → 视口。
     *
     * <p>返回数组视为只读——调用方不得修改。
     */
    float[] getTransform();

    /**
     * 图层是否可见。不可见的图层在规划阶段跳过。
     */
    boolean isVisible();

    /**
     * 图层透明度，作用于采样结果的 alpha 通道。
     *
     * <p>范围 {@code [0, 1]}。
     */
    float getOpacity();

    /**
     * 混合模式标识。
     *
     * <p><b>取值来源</b>：{@link  top.kzre.colorutils.blend.Blends}
     *
     * <p><b>消费方式</b>：规划阶段用字符串查 shader 变体表。
     * 未识别的字符串抛异常——说明 Clojure 侧新增了未在 Java 侧
     * 注册的混合模式。
     */
    String getBlendMode();
}