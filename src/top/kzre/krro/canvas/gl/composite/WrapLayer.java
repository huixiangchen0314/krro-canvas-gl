package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.util.tile.TiledCanvas;

/**
 * {@link ILayer} 的默认实现。不可变值对象。
 *
 * <p><b>用途</b>：Java 侧构造简单图层的便捷入口。Clojure 侧通常
 * 有自己的 record 实现 {@code ILayer}，不需要本类。本类是给纯 Java
 * 调用方和测试用的。
 *
 * <p><b>线程契约</b>：纯数据，任意线程可构造和读取。
 */
public final class WrapLayer extends LayerBase {
    /**
     * @param id        图层标识，null 允许（不参与合成逻辑）
     * @param canvas    光栅化后的局部空间瓦片，null 表示无内容
     * @param transform 6 元素仿射矩阵 {@code [a b c d tx ty]}，不能为 null
     * @param visible   是否可见
     * @param opacity   透明度，范围 {@code [0, 1]}
     * @param blendMode 混合模式标识，取值来自 {@link Blends}
     */
    public WrapLayer(Object id, TiledCanvas canvas, float[] transform, boolean visible, float opacity, String blendMode) {
        super(id, canvas, transform, visible, opacity, blendMode);
    }

    @Override
    public void close() {

    }
}