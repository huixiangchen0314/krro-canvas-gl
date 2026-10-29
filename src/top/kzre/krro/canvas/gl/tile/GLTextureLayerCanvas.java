package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.Set;

/**
 * 值对象，纹理层作为画布
 */
public final class GLTextureLayerCanvas {
    private final TiledCanvas canvas;
    private final AbstractGLTiledTexture.GLTextureTileData tileData;

    private GLTextureLayerCanvas(TiledCanvas canvas, AbstractGLTiledTexture.GLTextureTileData tileData) {
        this.canvas = canvas;
        this.tileData = tileData;
    }

    /**
     * 从画布创建，改方法假定画布的所有瓦片都是来自 纹理的同一层。
     */
    public static GLTextureLayerCanvas fromCanvas(TiledCanvas canvas) {
        Set<Long> tiles = canvas.getTiles();
        if (tiles.isEmpty()) {
            throw new IllegalArgumentException("no canvas tiles found");
        }
        Long key = tiles.iterator().next();
        Tile tile = canvas.getTile(key);
        if (tile == null) {
            throw new IllegalArgumentException("no canvas tile found");
        }
        AbstractGLTiledTexture.GLTextureTileData tileData = tile.queryData(AbstractGLTiledTexture.GLTextureTileData.class);
       if (tileData == null) {
           throw new IllegalArgumentException("tile data is not GLTextureTileData");
       }
        return new GLTextureLayerCanvas(canvas, tileData);
    }

    public TiledCanvas getCanvas() {
        return canvas;
    }

    public GLTexture getTexture() {
        return tileData.getTexture();
    }

    public int getLayer() {
        return tileData.getLayer();
    }
}
