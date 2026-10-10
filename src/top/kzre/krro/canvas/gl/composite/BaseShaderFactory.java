package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.tile.*;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class BaseShaderFactory implements ShaderFactory {

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
            GLBindable[] bindables ,
            ViewTileInfo grid) {
        List<ShaderLayerTileRef> tileRefs = tileSet.layerTileRefs;
        List<float[]> layerEntries = tileSet.layerTable;
        Map<Long, List<ShaderLayerTileRef>> buckets = tileSet.porjectTilesMap;

        int totalTileCount = tileRefs.size();
        int screenTileCount = grid.getViewTileCount();
        int tileSize = grid.getTileSize();
        int viewHeight = grid.getViewHeight();

        // ── 延迟索引构建：此处才把 ref 对象映射为它在 layerTileRefs 中的位置 ──
        //
        // IdentityHashMap 用引用相等——每个 ref 都是唯一实例，前插只改
        // 位置不改对象本身，因此同一 ref 在整个生命周期里映射到同一位置。
        Map<ShaderLayerTileRef, Integer> refToIndex =
                new IdentityHashMap<>(totalTileCount * 2);
        for (int i = 0; i < totalTileCount; i++) {
            refToIndex.put(tileRefs.get(i), i);
        }

        // ── layerTable 扁平化 ──
        float[] layerTable = new float[layerEntries.size() * 8];
        for (int i = 0; i < layerEntries.size(); i++) {
            System.arraycopy(layerEntries.get(i), 0, layerTable, i * 8, 8);
        }

        // ── tileTable：按位置排列，每 tile 8 float ──
        float[] tileTable = new float[totalTileCount * 8];
        for (int i = 0; i < totalTileCount; i++) {
            ShaderLayerTileRef layerTileRef = tileRefs.get(i);
            Tile tile = layerTileRef.tile;
            TextureTileSlot slot = slots.get(i);
            int unit = slot.getUnit();
            GLBindable bindable = bindables[unit];
            int edgeU;
            int edgeV;
            if (bindable instanceof GLAtlas ) {
                GLAtlas atlas = (GLAtlas) bindable;
                GLTiledTextureLayout texLayout = atlas.getLayout();
                edgeU = texLayout.getTilesPerEdge();
                edgeV = texLayout.getTilesPerEdge();
            } else if (bindable instanceof GLTexture ) {
                // FBO 颜色附件——2D 纹理，按 tileSize 划分网格。
                // 视口非正方形时 edgeU ≠ edgeV。
                GLTexture texture = (GLTexture) bindable;
                edgeU = Math.max(1, texture.getWidth()  / tileSize);
                edgeV = Math.max(1, texture.getHeight() / tileSize);
            } else {
                throw new IllegalArgumentException("unsupported bindable: " + bindable);
            }

            float u0 = (float) slot.getDataColumn() / edgeU;
            float v0 = (float) slot.getDataRow() / edgeV;

            float glTileOriginX = tile.tx() * tileSize;
            float glTileOriginY = tile.ty() * tileSize;

            int base = i * 8;
            tileTable[base] = u0;
            tileTable[base + 1] = v0;
            tileTable[base + 2] = unit;
            tileTable[base + 3] = slot.getLayer();
            tileTable[base + 4] = glTileOriginX;
            tileTable[base + 5] = glTileOriginY;
            tileTable[base + 6] = layerTileRef.layerTableIndex;
            tileTable[base + 7] = 0f;
        }

        // ── 屏幕瓦片 → indexList ──
        int[] gridTileXY = grid.getTileXY();
        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];

        int tileCount = 0;
        for (int i = 0; i < screenTileCount; i++) {
            int tileX = gridTileXY[i * 2];
            int tileY = gridTileXY[i * 2 + 1];
            List<ShaderLayerTileRef> b = buckets.get(TiledCanvas.pack(tileX, tileY));
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
            List<ShaderLayerTileRef> bucket = buckets.get(TiledCanvas.pack(sx, sy));
            if (bucket == null) continue;

            for (ShaderLayerTileRef ref : bucket) {
                Integer idx = refToIndex.get(ref);
                if (idx == null) {
                    // withRefs 的过滤保证了桶里不会有子集外的 ref。
                    // 走到这里说明上游投影状态不一致——立刻暴露，不要静默。
                    throw new IllegalStateException(
                            "bucket contains a ref not present in layerTileRefs: "
                                    + ref + " (canvas=" + ref.canvas + ")");
                }
                indexList[cursor++] = idx;
            }
        }

        return new ShaderPackedBuffer(
                layerTable, layerEntries.size(),
                tileTable, totalTileCount,
                offsets, counts,
                indexList, tileCount);
    }

}
