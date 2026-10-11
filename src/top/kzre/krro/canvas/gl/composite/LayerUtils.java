package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.AbstractGLTiledTexture;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LayerUtils {
    private LayerUtils() {}

    public static Set<GLTexture> collectTextures(List<ILayer> layers) {
        Set<GLTexture> result = new HashSet<>();
        for (ILayer layer : layers) {
            TiledCanvas canvas = layer.getCanvas();
            if (canvas == null) continue;
            for (Long tileKey : canvas.getTiles()) {
                Tile tile = canvas.getTile(tileKey);
                if (tile == null) continue;
                AbstractGLTiledTexture.GLTextureTileData td =
                        tile.queryData(AbstractGLTiledTexture.GLTextureTileData.class);
                if (td != null) {
                    result.add(td.getTexture());
                }
            }
        }
        return result;
    }
}
