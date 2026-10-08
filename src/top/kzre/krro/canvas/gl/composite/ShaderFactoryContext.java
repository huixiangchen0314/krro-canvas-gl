package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayout;

public final class ShaderFactoryContext {
    private final ShaderLayerTileSet tileSet;
    private final ViewTileInfo grid;
    private final GLBindable[] bindables;
    private final GLCompositeContext context;
    private final GLTiledTextureLayout textureLayout;


    public ShaderFactoryContext(ShaderLayerTileSet tileSet, ViewTileInfo grid, GLBindable[] bindables, GLCompositeContext context, GLTiledTextureLayout textureLayout) {
        this.tileSet = tileSet;
        this.grid = grid;
        this.bindables = bindables;
        this.context = context;
        this.textureLayout = textureLayout;

    }

    public ShaderLayerTileSet getTileSet() {
        return tileSet;
    }

    public ViewTileInfo getGrid() {
        return grid;
    }

    public GLBindable[] getBindables() {
        return bindables;
    }

    public GLCompositeContext getContext() {
        return context;
    }

    public GLTiledTextureLayout getTextureLayout() {
        return textureLayout;
    }
}
