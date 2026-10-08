package top.kzre.krro.canvas.gl.composite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ShaderLayerTileSet {
    final List<ShaderLayerTileRef> layerTileRefs;
    final List<float[]> layerTable;
    final Map<Long, List<Integer>> porjectTilesMap;
    final Map<ILayer, Integer> layerIndex;
    final Map<ShaderLayerTileKey, Integer> layerTileIndex;
    private ShaderLayerTileSet(List<ShaderLayerTileRef> layerTileRefs,
                              List<float[]> layerTable,
                              Map<Long, List<Integer>> porjectTilesMap,
                              Map<ILayer, Integer> layerIndex,
                              Map<ShaderLayerTileKey, Integer> layerTileIndex) {
        this.layerTileRefs = layerTileRefs;
        this.layerTable = layerTable;
        this.porjectTilesMap = porjectTilesMap;
        this.layerIndex = layerIndex;
        this.layerTileIndex = layerTileIndex;
    }

    private ShaderLayerTileSet() {
        this.layerTileRefs = new ArrayList<>();
        this.layerTable = new ArrayList<>();
        this.porjectTilesMap = new HashMap<>();
        this.layerIndex = new HashMap<>();
        this.layerTileIndex = new HashMap<>();
    }

    public static ShaderLayerTileSet create(){
        return new ShaderLayerTileSet();
    }

    public ShaderLayerTileSet withRefs(List<ShaderLayerTileRef> layerTileRefs){
        return new ShaderLayerTileSet(layerTileRefs,
                layerTable, porjectTilesMap, layerIndex, layerTileIndex);
    }
}
