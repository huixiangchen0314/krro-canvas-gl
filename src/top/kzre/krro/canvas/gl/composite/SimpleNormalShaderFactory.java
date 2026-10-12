package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.composite.shaders.NormalShader;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.tile.TextureTileSlot;
import top.kzre.krro.canvas.gl.tile.TileRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SimpleNormalShaderFactory implements ShaderFactory {
    public static final ShaderFactory INSTANCE = new SimpleNormalShaderFactory();

    @Override
    public Shader create(ShaderFactoryContext context) {
        GLBindable[] bindables = context.getBindables();
        GLCompositeContext compositeContext = context.getContext();
        ViewTileInfo viewInfo = context.getGrid();
        ShaderLayerTileSet tileSet = context.getTileSet();
        int tileSize = context.getContext().getTileSize();
        GLProgramCache programs = compositeContext.getProgramCache();

        return new NormalShader(
                ()->programs.get(Blends.NORMAL), tileSet, viewInfo, tileSize, bindables);
    }
}
