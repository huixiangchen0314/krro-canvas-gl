package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

public final class ShaderLayerTileRef {
    final TiledCanvas canvas;
    final Tile tile;
    final int layerTableIndex;

    ShaderLayerTileRef(TiledCanvas canvas, Tile tile,
                       int layerTableIndex) {
        this.canvas = canvas;
        this.tile = tile;
        this.layerTableIndex = layerTableIndex;

    }

    /** 瓦片所在的画布。 */
    public TiledCanvas getCanvas() { return canvas; }

    /** 瓦片对象本身。 */
    public Tile getTile() { return tile; }

    /** 该瓦片所属图层在 layerTable 中的条目下标。 */
    public int getLayerTableIndex() { return layerTableIndex; }

    @Override
    public String toString() {
        return "ShaderLayerTileRef{"
                + "layer=" + layerTableIndex
                + ", tile=" + tile
                + '}';
    }
}
