package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.*;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseFactory implements ShaderFactory {

    protected static Map<TextureTileSlot, TileRef> allocateSlots(
            List<ShaderLayerTileRef> tileRefs,
            GLBindable[] bindables,
            List<TextureTileSlot> slotsOut) {

        Map<TextureTileSlot, TileRef> map = new LinkedHashMap<>();
        int index = 0;
        int slotIdx  = 0;

        for (ShaderLayerTileRef tileRef : tileRefs) {
            Tile tile = tileRef.tile;
            GLAtlas.GLTileData existing = tile.queryData(GLAtlas.GLTileData.class);
            if (existing != null) {
                TextureTileSlot slot = atlasSlotOf(existing, bindables);
                map.put(slot, new TileRef(tileRef.canvas, tile));
                slotsOut.add(slot);

                continue;
            }

            AbstractGLTiledTexture.GLTextureTileData texTile  = tile.queryData(AbstractGLTiledTexture.GLTextureTileData.class);
            if (texTile != null) {
                TextureTileSlot slot = texSlotOf(texTile, bindables);
                map.put(slot, new TileRef(tileRef.canvas, tile));
                slotsOut.add(slot);
                continue;
            }

            TextureTileSlot slot = null;
            while (slot == null) {
                if (index >= bindables.length) {
                    throw new IllegalStateException(
                            "atlas capacity exceeded: need=" + tileRefs.size()
                                    + ", bindables=" + bindables.length
                                    + ", stuck tile=" + tile
                                    + ", canvas=" + tileRef.canvas);
                }
                GLBindable bindable = bindables[index];
                if (!(bindable instanceof GLAtlas))
                {
                    index++;
                    slotIdx = 0;
                    continue;
                }
                GLAtlas atlas = (GLAtlas) bindable;


                GLTiledTextureLayout layout = atlas.getLayout();
                int capacity = layout.getCapacity();
                int perLayer = layout.getTilesPerLayer();
                int perEdge  = layout.getTilesPerEdge();

                while (slotIdx < capacity) {
                    int idx   = slotIdx++;
                    int layer = idx / perLayer;
                    int local = idx % perLayer;
                    int dataColumn   = local % perEdge;
                    int dataRow   = local / perEdge;
                    if (atlas.isOccupiedAt(layer, dataColumn, dataRow)) continue;
                    slot = new TextureTileSlot(index, layer, dataRow, dataColumn);
                    break;
                }
                if (slot == null)
                {
                    index++;
                    slotIdx = 0;
                }
            }
            map.put(slot, new TileRef(tileRef.canvas, tile));
            slotsOut.add(slot);
        }

        return map;
    }

    protected static TextureTileSlot atlasSlotOf(GLAtlas.GLTileData data,
                                                 GLBindable[] bindables) {
        GLAtlas owner = data.getAtlas();
        int bindUnit = slotOf(bindables, owner);
        return new TextureTileSlot(
                bindUnit,
                data.getLayer(),
                data.getDataRow(),
                data.getDataColumn());
    }

    protected static TextureTileSlot texSlotOf(AbstractGLTiledTexture.GLTextureTileData data,
                                               GLBindable[] bindables) {
        // FBO 的绑定应当取纹理，而不是包装对象
        GLTexture tex = data.getTexture();

        int bindUnit = slotOf(bindables, tex);
        return new TextureTileSlot(
                bindUnit,
                data.getLayer(),
                data.getRow(),
                data.getColumn()
        );
    }

    private static int slotOf(GLBindable[] bindables, Object owner) {
        int ownerIdx = -1;
        for (int i = 0; i < bindables.length; i++) {
            if (bindables[i] == owner) { ownerIdx = i; break; }
        }
        if (ownerIdx < 0) {
            throw new IllegalStateException(
                    "tile is bound to an atlas outside this page: "
                            + owner );
        }
        return ownerIdx;
    }

    protected static ShaderPackedBuffer packBuffers(
            ShaderLayerTileSet tileSet,
            List<TextureTileSlot> slots,
            GLTiledTextureLayout layout,
            ViewTileInfo grid) {

        List<ShaderLayerTileRef> tileRefs = tileSet.layerTileRefs;
        List<float[]> layerEntries = tileSet.layerTable;
        Map<Long, List<Integer>> buckets = tileSet.porjectTilesMap;

        int totalTileCount = tileRefs.size();
        int screenTileCount = grid.getViewTileCount();

        float[] layerTable = new float[layerEntries.size() * 8];
        for (int i = 0; i < layerEntries.size(); i++) {
            System.arraycopy(layerEntries.get(i), 0,
                    layerTable, i * 8, 8);
        }

        float[] tileTable = new float[totalTileCount * 8];
        for (int i = 0; i < totalTileCount; i++) {
            ShaderLayerTileRef layerTileRef = tileRefs.get(i);

            TextureTileSlot slot = slots.get(i);

            int edge = layout.getTilesPerEdge();
            float u0 = (float) slot.getDataColumn() / edge;
            float v0 = (float) slot.getDataRow() / edge;

            int base = i * 8;
            tileTable[base] = u0;
            tileTable[base + 1] = v0;
            tileTable[base + 2] = slot.getAtlasIndex();
            tileTable[base + 3] = slot.getLayer();
            tileTable[base + 4] = layerTileRef.glTileOriginX;
            tileTable[base + 5] = layerTileRef.glTileOriginY;
            tileTable[base + 6] = layerTileRef.layerTableIndex;
            tileTable[base + 7] = 0f;
        }

        int[] gridTileXY = grid.getTileXY();
        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];

        int tileCount = 0;
        for (int i = 0; i < screenTileCount; i++) {
            int tileX = gridTileXY[i * 2];
            int tileY = gridTileXY[i * 2 + 1];
            List<Integer> b = buckets.get(TiledCanvas.pack(tileX, tileY));
            int sz = b == null ? 0 : b.size();
            counts[i] = sz;
            tileCount += sz;
        }

        int[] indexList = new int[tileCount];
        int cursor = 0;
        for (int i = 0; i < screenTileCount; i++) {
            offsets[i] = cursor;
            int sx = gridTileXY[i * 2];
            int sy = gridTileXY[i * 2 + 1];
            List<Integer> bucket = buckets.get(TiledCanvas.pack(sx, sy));
            if (bucket != null) {
                for (int idx : bucket) {
                    indexList[cursor++] = idx;
                }
            }
        }

        return new ShaderPackedBuffer(
                layerTable, layerEntries.size(),
                tileTable, totalTileCount,
                offsets, counts,
                indexList, tileCount);
    }

}
