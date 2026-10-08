package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.composite.shaders.NormalShader;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.tile.TextureTileSlot;
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayout;
import top.kzre.krro.canvas.gl.tile.TileRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SimpleNormalFactory extends BaseFactory {
    public static final BaseFactory INSTANCE = new SimpleNormalFactory();

    @Override
    public Shader create(ShaderFactoryContext context) {
        GLBindable[] bindables = context.getBindables();
        GLCompositeContext compositeContext = context.getContext();
        ViewTileInfo grid = context.getGrid();
        ShaderLayerTileSet tileSet = context.getTileSet();
        GLTiledTextureLayout texTileLayout = context.getTextureLayout();
        GLProgramCache programs = compositeContext.getProgramCache();

        int viewWidth = compositeContext.getViewWidth();
        int viewHeight = compositeContext.getViewHeight();

        List<TextureTileSlot> slots = new ArrayList<>(tileSet.layerTileRefs.size());
        Map<TextureTileSlot, TileRef> slotMap = allocateSlots(tileSet.layerTileRefs, bindables, slots);

        ShaderPackedBuffer packed = packBuffers(tileSet, slots, texTileLayout, grid);

        TileBufferGroup group = new TileBufferGroup(
                ShaderConstants.TEXTURE_UNIT_TILE_TABLE,
                ShaderConstants.TEXTURE_UNIT_LAYER_TABLE,
                ShaderConstants.TEXTURE_UNIT_INDEX_TABLE,
                packed.offsets,
                packed.counts,
                packed.layerTable,
                packed.layerEntryCount,
                packed.tileTable,
                packed.tileEntryCount,
                packed.indexList,
                packed.indexCount,
                slotMap);


        GLProgram program = programs.get(Blends.NORMAL);
        return new NormalShader(
                program, group, viewWidth, viewHeight, texTileLayout);
    }
}
