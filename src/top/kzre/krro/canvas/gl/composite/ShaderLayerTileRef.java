package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

public final class ShaderLayerTileRef {
    final TiledCanvas canvas;
    final Tile tile;
    final int layerTableIndex;
    final int glTileOriginX;
    final int glTileOriginY;

    ShaderLayerTileRef(TiledCanvas canvas, Tile tile,
                       int layerTableIndex, int glTileOriginX, int glTileOriginY) {
        this.canvas = canvas;
        this.tile = tile;
        this.layerTableIndex = layerTableIndex;
        this.glTileOriginX = glTileOriginX;
        this.glTileOriginY = glTileOriginY;
    }
}
