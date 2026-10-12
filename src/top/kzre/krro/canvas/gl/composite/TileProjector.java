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
    private TileProjector() {}

    // ═══════════════════════════════════════════════
    // 追加语义
    // ═══════════════════════════════════════════════

    public static ShaderLayerTileSet projectLayersInto(
            List<ILayer> layers,
            List<Long> dirtyTiles,
            int tileSize,
            ShaderLayerTileSet out) {
        for (ILayer layer : layers) {
            projectLayerInto(layer, dirtyTiles, tileSize, out);
        }
        return out;
    }

    public static ShaderLayerTileSet projectLayerInto(
            ILayer layer, List<Long> dirtyTiles, int tileSize, ShaderLayerTileSet out) {

        if (!layer.isVisible()) return out;

        float[] mat2d = layer.getTransform();

        float[] inv = KMath.mat2dInv(mat2d);
        if (inv == null) {
            throw new IllegalStateException("singular layer transform (det ≈ 0)");
        }
        for (Long dirtyKey : dirtyTiles) {
            projectTileInto(layer, dirtyKey, tileSize, inv, out);
        }
        return out;
    }

    /** identity transform：dirtyKey 直接就是 layerKey，不需要 transformTile。 */
    private static void projectTileIdentityInto(
            ILayer layer, Long dirtyKey, ShaderLayerTileSet out) {

        TiledCanvas canvas = layer.getCanvas();
        if (canvas == null) return;

        int tx = TiledCanvas.unpackTx(dirtyKey);
        int ty = TiledCanvas.unpackTy(dirtyKey);
        Tile tile = canvas.getTile(tx, ty);
        if (tile == null) return;

        Map<ShaderLayerTileKey, ShaderLayerTileRef> layerTileIndex = out.layerTileIndex;
        Map<ILayer, Integer> layerIndex = out.layerIndex;
        List<float[]> layerTable = out.layerTable;
        List<ShaderLayerTileRef> layerTileRefs = out.layerTileRefs;

        ShaderLayerTileKey key = ShaderLayerTileKey.of(layer, dirtyKey);
        ShaderLayerTileRef ref = layerTileIndex.get(key);
        if (ref == null) {
            Integer li = layerIndex.get(layer);
            if (li == null) {
                li = layerTable.size();
                layerIndex.put(layer, li);
                layerTable.add(new float[]{
                        1f, 0f, 0f, 1f, 0f, 0f, layer.getOpacity(), 0f
                });
            }
            ref = new ShaderLayerTileRef(canvas, tile, li);
            layerTileRefs.add(ref);
            layerTileIndex.put(key, ref);
        }

        out.porjectTilesMap.computeIfAbsent(dirtyKey, k -> new ArrayList<>())
                .add(ref);
    }

    private static void projectTileInto(
            ILayer layer, Long dirtyKey, int tileSize, float[] inv,
            ShaderLayerTileSet out) {

        TiledCanvas canvas = layer.getCanvas();
        if (canvas == null) return;

        Set<Long> layerKeys = LayerUtils.transformTile(dirtyKey, tileSize, inv);
        if (layerKeys == null || layerKeys.isEmpty()) return;

        Map<ShaderLayerTileKey, ShaderLayerTileRef> layerTileIndex = out.layerTileIndex;
        Map<ILayer, Integer> layerIndex          = out.layerIndex;
        List<float[]> layerTable                 = out.layerTable;
        Map<Long, List<ShaderLayerTileRef>> porjectTilesMap = out.porjectTilesMap;
        List<ShaderLayerTileRef> layerTileRefs   = out.layerTileRefs;

        List<ShaderLayerTileRef> bucket = porjectTilesMap.computeIfAbsent(
                dirtyKey, k -> new ArrayList<>());

        for (Long layerKey : layerKeys) {
            int tx = TiledCanvas.unpackTx(layerKey);
            int ty = TiledCanvas.unpackTy(layerKey);
            Tile tile = canvas.getTile(tx, ty);
            if (tile == null) continue;

            ShaderLayerTileKey projectedTileKey = ShaderLayerTileKey.of(layer, layerKey);
            ShaderLayerTileRef ref = layerTileIndex.get(projectedTileKey);
            if (ref == null) {
                Integer li = layerIndex.get(layer);
                if (li == null) {
                    li = layerTable.size();
                    layerIndex.put(layer, li);
                    layerTable.add(new float[]{
                            inv[0], inv[1], inv[2], inv[3],
                            inv[4], inv[5], layer.getOpacity(), 0f
                    });
                }
                ref = new ShaderLayerTileRef(canvas, tile, li);
                layerTileRefs.add(ref);
                layerTileIndex.put(projectedTileKey, ref);
            }

            bucket.add(ref);
        }
    }

    // ═══════════════════════════════════════════════
    // 前插语义（backdrop）
    // ═══════════════════════════════════════════════

    public static ShaderLayerTileSet projectLayerIntoFirst(
            ILayer layer, List<Long> dirtyTiles, int tileSize, ShaderLayerTileSet out) {

        if (!layer.isVisible()) return out;

        float[] mat2d = layer.getTransform();

        // 快速路径：标准变换——屏幕瓦片 = 图层瓦片，跳过求逆与 AABB 投影。
        if (KMath.mat2dIsIdentity(mat2d)) {
            for (int i = dirtyTiles.size() - 1; i >= 0; i--) {
                projectTileIdentityFirst(layer, dirtyTiles.get(i), out);
            }
            return out;
        }

        float[] inv = KMath.mat2dInv(mat2d);
        if (inv == null) {
            throw new IllegalStateException("singular layer transform (det ≈ 0)");
        }
        for (int i = dirtyTiles.size() - 1; i >= 0; i--) {
            projectTileIntoFirst(layer, dirtyTiles.get(i), tileSize, inv, out);
        }
        return out;
    }

    /**
     * identity transform 下的 backdrop 前插。
     *
     * <p>屏幕瓦片与图层瓦片一一对应：dirtyKey 直接就是 layerKey。
     * 每个 dirtyKey 恰好贡献一个 ref——不存在"一块屏幕瓦片对应多个
     * 图层瓦片"的情形，因此这里用单次头插，不涉及 {@code newRefs}
     * 列表的构造。
     */
    private static void projectTileIdentityFirst(
            ILayer layer, Long dirtyKey, ShaderLayerTileSet out) {

        TiledCanvas canvas = layer.getCanvas();
        if (canvas == null) return;

        int tx = TiledCanvas.unpackTx(dirtyKey);
        int ty = TiledCanvas.unpackTy(dirtyKey);
        Tile tile = canvas.getTile(tx, ty);
        if (tile == null) return;

        List<ShaderLayerTileRef> layerTileRefs = out.layerTileRefs;
        List<float[]> layerTable = out.layerTable;
        Map<ILayer, Integer> layerIndex = out.layerIndex;
        Map<Long, List<ShaderLayerTileRef>> porjectTilesMap = out.porjectTilesMap;

        Integer li = layerIndex.get(layer);
        if (li == null) {
            li = layerTable.size();
            layerIndex.put(layer, li);
            layerTable.add(new float[]{
                    1f, 0f, 0f, 1f, 0f, 0f, layer.getOpacity(), 0f
            });
        }
        final int layerTableIdx = li;

        ShaderLayerTileRef ref = new ShaderLayerTileRef(canvas, tile, layerTableIdx);

        // 逆序遍历调用方 + 单次头插 = 最终顺序与 dirtyTiles 正序一致。
        layerTileRefs.add(0, ref);
        porjectTilesMap.computeIfAbsent(dirtyKey, k -> new ArrayList<>())
                .add(0, ref);
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
        Map<ILayer, Integer> layerIndex = out.layerIndex;
        Map<Long, List<ShaderLayerTileRef>> porjectTilesMap = out.porjectTilesMap;

        Integer li = layerIndex.get(layer);
        if (li == null) {
            li = layerTable.size();
            layerIndex.put(layer, li);
            layerTable.add(new float[]{
                    inv[0], inv[1], inv[2], inv[3],
                    inv[4], inv[5], layer.getOpacity(), 0f
            });
        }
        final int layerTableIdx = li;

        List<ShaderLayerTileRef> newRefs = new ArrayList<>(layerKeys.size());
        for (Long layerKey : layerKeys) {
            int tx = TiledCanvas.unpackTx(layerKey);
            int ty = TiledCanvas.unpackTy(layerKey);
            Tile tile = canvas.getTile(tx, ty);
            if (tile == null) continue;

            newRefs.add(new ShaderLayerTileRef(canvas, tile, layerTableIdx));
        }

        if (newRefs.isEmpty()) return;

        layerTileRefs.addAll(0, newRefs);
        porjectTilesMap.computeIfAbsent(dirtyKey, k -> new ArrayList<>())
                .addAll(0, newRefs);
    }
}