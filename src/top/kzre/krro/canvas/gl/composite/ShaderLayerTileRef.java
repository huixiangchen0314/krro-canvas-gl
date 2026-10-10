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
}
