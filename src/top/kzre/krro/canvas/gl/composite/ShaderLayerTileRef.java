package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

/**
 * 一次投影中生成的瓦片引用——描述"某块画布上的某个瓦片"。
 *
 * <p><b>值语义</b>：基于 {@code (canvas, tile, layerTableIndex)}
 * 相等。canvas / tile 用引用相等——它们是图元不是值对象。
 *
 * <p><b>去重语义</b>：两个 ref 相等 = 同一个瓦片、同一画布、同一
 * layerTable 条目。投影阶段如果生成两条相等的 ref——说明投影去重
 * 失败——是上游 bug。
 */
public final class ShaderLayerTileRef {

    private final TiledCanvas canvas;
    private final Tile tile;
    private final int layerTableIndex;

    ShaderLayerTileRef(TiledCanvas canvas, Tile tile, int layerTableIndex) {
        this.canvas = canvas;
        this.tile = tile;
        this.layerTableIndex = layerTableIndex;
    }

    public TiledCanvas getCanvas() {
        return canvas;
    }

    public Tile getTile() {
        return tile;
    }

    public int getLayerTableIndex() {
        return layerTableIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderLayerTileRef)) return false;
        ShaderLayerTileRef other = (ShaderLayerTileRef) o;
        return this.canvas == other.canvas
                && this.tile == other.tile
                && this.layerTableIndex == other.layerTableIndex;
    }

    @Override
    public int hashCode() {
        int h = System.identityHashCode(canvas);
        h = 31 * h + System.identityHashCode(tile);
        h = 31 * h + layerTableIndex;
        return h;
    }

    @Override
    public String toString() {
        return "ShaderLayerTileRef{"
                + "layer=" + layerTableIndex
                + ", tile=" + tile
                + '}';
    }
}