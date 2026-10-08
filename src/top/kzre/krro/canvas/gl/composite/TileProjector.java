package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.core.layer.LayerUtils;
import top.kzre.krro.util.math.KMath;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TileProjector {
    private TileProjector(){}

    public static ShaderLayerTileSet projectLayersInto(
            List<ILayer> layers,
            List<Long> dirtyTiles,
            int tileSize,
            ShaderLayerTileSet out) {

//        int tilesPerColumn = view.getViewHeight() / tileSize;
        for (ILayer layer : layers) {
            projectLayerInto(layer, dirtyTiles, tileSize, out);
        }
        return out;
    }

    public static ShaderLayerTileSet projectLayerInto(ILayer layer, List<Long> dirtyTiles, int tileSize, ShaderLayerTileSet out) {
        if (!layer.isVisible()) return out;

        float[] mat2d = layer.getTransform();
        float[] inv = KMath.mat2dInv(mat2d);
        if (inv == null) {
            throw new IllegalStateException(
                    "singular layer transform (det ≈ 0)");
        }
        for (Long dirtyKey : dirtyTiles) {
            projectTileInto(layer, dirtyKey, tileSize, inv, out);
        }
        return out;
    }



    private static void projectTileInto(ILayer layer, Long dirtyKey, int tileSize, float[] inv,
                                       ShaderLayerTileSet out) {
        TiledCanvas canvas = layer.getCanvas();
        if (canvas == null) return;

        Set<Long> layerKeys = LayerUtils.transformTile(dirtyKey, tileSize, inv);
        if (layerKeys == null || layerKeys.isEmpty()) return;

        Map<ShaderLayerTileKey, Integer> layerTileIndex = out.layerTileIndex;
        Map<ILayer, Integer> layerIndex = out.layerIndex;
        List<float[]> layerTable = out.layerTable;
        Map<Long, List<Integer>> porjectTilesMap = out.porjectTilesMap;
        List<ShaderLayerTileRef> layerTileRefs = out.layerTileRefs;
        for (Long layerKey : layerKeys) {
            int tx = TiledCanvas.unpackTx(layerKey);
            int ty = TiledCanvas.unpackTy(layerKey);
            Tile tile = canvas.getTile(tx, ty);
            if (tile == null) continue;

            ShaderLayerTileKey projectedTileKey = ShaderLayerTileKey.of(layer, layerKey);
            Integer idx = layerTileIndex.get(projectedTileKey);
            if (idx == null) {
                Integer li = layerIndex.get(layer);
                if (li == null) {
                    li = layerTable.size();
                    layerIndex.put(layer, li);

                    layerTable.add(new float[]{
                            inv[0], inv[1], inv[2], inv[3],
                            inv[4], inv[5], layer.getOpacity(), 0f
                    });
                }

                idx = layerTileRefs.size();
                int glTileOriginX = tx * tileSize;
                int glTileOriginY = ty * tileSize;
                layerTileRefs.add(new ShaderLayerTileRef(canvas, tile, li, glTileOriginX, glTileOriginY));
                layerTileIndex.put(projectedTileKey, idx);
            }

            porjectTilesMap.computeIfAbsent(dirtyKey, k -> new ArrayList<>())
                    .add(idx);
        }
    }

    public static ShaderLayerTileSet projectLayerIntoFirst(
            ILayer layer, List<Long> dirtyTiles, int tileSize, ShaderLayerTileSet out) {

        if (!layer.isVisible()) return out;

        float[] mat2d = layer.getTransform();
        float[] inv = KMath.mat2dInv(mat2d);
        if (inv == null) {
            throw new IllegalStateException(
                    "singular layer transform (det ≈ 0)");
        }
        for (Long dirtyKey : dirtyTiles) {
            projectTileIntoFirst(layer, dirtyKey, tileSize, inv, out);
        }
        return out;
    }

    private static void projectTileIntoFirst(
            ILayer layer, Long dirtyKey, int tileSize, float[] inv,
            ShaderLayerTileSet out) {

        TiledCanvas canvas = layer.getCanvas();
        if (canvas == null) return;

        Set<Long> layerKeys = LayerUtils.transformTile(dirtyKey, tileSize, inv);
        if (layerKeys == null || layerKeys.isEmpty()) return;

        List<ShaderLayerTileRef> layerTileRefs = out.layerTileRefs;
        List<float[]> layerTable = out.layerTable;

        // 1. 先为本 dirty tile 收集本图层要贡献的 ref（尚不知最终 idx）
        List<ShaderLayerTileRef> newRefs = new ArrayList<>(layerKeys.size());
        for (Long layerKey : layerKeys) {
            int tx = TiledCanvas.unpackTx(layerKey);
            int ty = TiledCanvas.unpackTy(layerKey);
            Tile tile = canvas.getTile(tx, ty);
            if (tile == null) continue;

            // backdrop 每 dirty tile 一份，直接为它分配独立的 layerTable 槽位
            int li = layerTable.size();
            layerTable.add(new float[]{
                    inv[0], inv[1], inv[2], inv[3],
                    inv[4], inv[5], layer.getOpacity(), 0f
            });
            newRefs.add(new ShaderLayerTileRef(canvas, tile, li,
                    tx * tileSize, ty * tileSize));
        }

        if (newRefs.isEmpty()) return;

        int shift = newRefs.size();

        // 2. 已有 ref 全体后移——索引是位置，位置变了，桶里旧的 idx 也必须跟着移
        layerTileRefs.addAll(0, newRefs);  // 前插（ArrayList 会整体搬移，O(n)，可接受）

        // 3. 所有旧桶里的 idx 整体 +shift
        for (List<Integer> bucket : out.porjectTilesMap.values()) {
            bucket.replaceAll(integer -> integer + shift);
        }

        // 4. backdrop 自己的 idx = 0..shift-1，前插到本 dirtyKey 桶的头部
        List<Integer> prepend = new ArrayList<>(shift);
        for (int i = 0; i < shift; i++) prepend.add(i);

        out.porjectTilesMap.computeIfAbsent(dirtyKey, k -> new ArrayList<>())
                .addAll(0, prepend);
    }
}
