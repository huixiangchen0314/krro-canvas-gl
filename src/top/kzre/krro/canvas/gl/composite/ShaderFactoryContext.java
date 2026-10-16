package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.util.Tuple4f;

public final class ShaderFactoryContext {
    private final ShaderLayerTileSet tileSet;
    private final ViewTileInfo grid;
    private final GLBindable[] bindables;
    private final GLCompositeContext context;
    private final Tuple4f imageAABB;

    public ShaderFactoryContext(ShaderLayerTileSet tileSet, ViewTileInfo grid, GLBindable[] bindables, GLCompositeContext context, Tuple4f imageAABB) {
        this.tileSet = tileSet;
        this.grid = grid;
        this.bindables = bindables;
        this.context = context;

        this.imageAABB = imageAABB;
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

    public Tuple4f getImageAABB() {
        return imageAABB;
    }
}
