package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.GLTexturePool;

public final class GLRgba8AtlasFactory implements GLAtlasFactory {
    private final GLTiledTextureLayout layout;
    private final GLTexturePool texPool;
    public GLRgba8AtlasFactory(GLTiledTextureLayout layout, GLTexturePool texPool) {
        this.layout = layout;
        this.texPool = texPool;
    }

    @Override
    public GLAtlas create() {
        int layers = layout.getLayers();
        int layerSize = layout.getLayerSize();
        return new GLAtlas(
                GLTexture.createRgba8(layerSize, layerSize, layers), layout, texPool);
    }

}
