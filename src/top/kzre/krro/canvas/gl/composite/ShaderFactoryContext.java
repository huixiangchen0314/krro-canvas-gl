package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;

public final class ShaderFactoryContext {
    private final ShaderLayerTileSet tileSet;
    private final ViewTileInfo grid;
    private final GLBindable[] bindables;
    private final GLCompositeContext context;


    public ShaderFactoryContext(ShaderLayerTileSet tileSet, ViewTileInfo grid, GLBindable[] bindables, GLCompositeContext context) {
        this.tileSet = tileSet;
        this.grid = grid;
        this.bindables = bindables;
        this.context = context;

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

}
