package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;

public final class GLRgba8AtlasFactory implements GLAtlasFactory {
    private final GLTiledTextureLayout layout;

    public GLRgba8AtlasFactory(GLTiledTextureLayout layout) {
        this.layout = layout;
    }

    @Override
    public GLAtlas create() {
        int layers = layout.getLayers();
        int layerSize = layout.getLayerSize();
        return new GLAtlas(
                GLTexture.createRgba8(layerSize, layerSize, layers), layout);
    }

}
