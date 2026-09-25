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
public final class DefaultLayer implements ILayer {

    private final Object      id;
    private final TiledCanvas canvas;
    private final float[]     transform;
    private final boolean     visible;
    private final float       opacity;
    private final String      blendMode;

    /**
     * @param id        图层标识，null 允许（不参与合成逻辑）
     * @param canvas    光栅化后的局部空间瓦片，null 表示无内容
     * @param transform 6 元素仿射矩阵 {@code [a b c d tx ty]}，不能为 null
     * @param visible   是否可见
     * @param opacity   透明度，范围 {@code [0, 1]}
     * @param blendMode 混合模式标识，取值来自 {@link Blends}
     */
    public DefaultLayer(Object id,
                        TiledCanvas canvas,
                        float[] transform,
                        boolean visible,
                        float opacity,
                        String blendMode) {
        if (transform == null || transform.length < 6) {
            throw new IllegalArgumentException(
                    "transform must be a 6-element affine matrix");
        }
        if (blendMode == null) {
            throw new IllegalArgumentException("blendMode must not be null");
        }
        if (opacity < 0f || opacity > 1f) {
            throw new IllegalArgumentException(
                    "opacity must be in [0, 1]: " + opacity);
        }

        this.id        = id;
        this.canvas    = canvas;
        this.transform = transform.clone();
        this.visible   = visible;
        this.opacity   = opacity;
        this.blendMode = blendMode;
    }

    // ═══════════════════════════════════════════════
    // ILayer
    // ═══════════════════════════════════════════════

    @Override public Object      getId()        { return id; }
    @Override public TiledCanvas getCanvas()    { return canvas; }
    @Override public float[]     getTransform() { return transform; }
    @Override public boolean     isVisible()    { return visible; }
    @Override public float       getOpacity()   { return opacity; }
    @Override public String      getBlendMode() { return blendMode; }

    // ═══════════════════════════════════════════════
    // Object
    // ═══════════════════════════════════════════════

    @Override
    public String toString() {
        return "DefaultLayer{id=" + id
                + ", visible=" + visible
                + ", opacity=" + opacity
                + ", blend=" + blendMode
                + ", canvas=" + (canvas == null ? "null" : "present")
                + "}";
    }
}