package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.util.Range;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TileFactory;
import top.kzre.krro.util.tile.TiledCanvas;
import top.kzre.krro.util.tile.util.WeakReferenceQueue;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * {@link AtlasPoolPage} 的默认实现。
 */
public class DefaultAtlasPoolPage implements AtlasPoolPage {

    private final GLAtlas[] atlases;

    /**
     * 被提升到 显存的瓦片
     */
    private final WeakReferenceQueue<Tile> liftedTiles = new WeakReferenceQueue<>();

    public DefaultAtlasPoolPage(GLAtlas[] atlases) {
        if (atlases == null || atlases.length == 0) {
            throw new IllegalArgumentException("atlases must not be empty");
        }
        this.atlases = atlases;
    }

    @Override
    public void purge() {
        liftedTiles.cleanup();

        Iterator<Tile> it = liftedTiles.iterator();
        while (it.hasNext()) {
            Tile tile = it.next();
            if (!isOutdate(tile)) continue;

            GLAtlas.GLTileDataImpl gl =
                    tile.queryData(GLAtlas.GLTileDataImpl.class);
            if (gl == null) {
                // 已是 CPU 形态——从队列移除
                it.remove();
                continue;
            }

            if (!gl.isValid()) {
                // 死代理——release 归零时 handle 已关闭，槽位已释放。
                // Tile 已放弃所有权，对象已死——直接移除队列条目。
                it.remove();
                continue;
            }

            if (gl.getProxyRefCount() > 1) {
                // 外部还持有——跳过，等它释放后下次再清
                continue;
            }

            // 有效且独占——正常换出
            GLAtlas.GLTileDataImpl.pageOut(tile);
            it.remove();
        }
    }

    @Override
    public int ensureSlotsKeep(int counts, Set<Tile> keepTiles) {
        if (counts < 0) {
            throw new IllegalArgumentException("counts must be >= 0: " + counts);
        }

        liftedTiles.cleanup();

        int currentFree = 0;
        for (GLAtlas atlas : atlases) {
            currentFree += atlas.getFreeCount();
        }
        if (currentFree >= counts) {
            return currentFree;
        }

        int extraNeed = counts - currentFree;

        // ── 第一遍：驱逐脏瓦片 ──
        Iterator<Tile> it = liftedTiles.iterator();
        while (it.hasNext() && extraNeed > 0) {
            Tile tile = it.next();
            if (keepTiles != null && keepTiles.contains(tile)) continue;
            if (!isOutdate(tile)) continue;

            GLAtlas.GLTileDataImpl gl =
                    tile.queryData(GLAtlas.GLTileDataImpl.class);
            if (gl == null) {
                it.remove();
                continue;
            }
            if (!gl.isValid()) {
                // 死代理——直接移除
                it.remove();
                continue;
            }
            if (gl.getProxyRefCount() > 1) {
                // 外部持有——跳过，不驱逐
                continue;
            }

            if (GLAtlas.GLTileDataImpl.pageOut(tile)) {
                extraNeed--;
                currentFree++;
            }
            it.remove();
        }

        // ── 第二遍：驱逐干净瓦片 ──
        if (extraNeed > 0) {
            it = liftedTiles.iterator();
            while (it.hasNext() && extraNeed > 0) {
                Tile tile = it.next();
                if (keepTiles != null && keepTiles.contains(tile)) continue;
                if (isOutdate(tile)) continue;  // 脏的已在第一遍处理

                GLAtlas.GLTileDataImpl gl =
                        tile.queryData(GLAtlas.GLTileDataImpl.class);
                if (gl == null) {
                    it.remove();
                    continue;
                }

                if (!gl.isValid()) {
                    it.remove();
                    continue;
                }

                if (gl.getProxyRefCount() > 1) {
                    continue;
                }

                if (GLAtlas.GLTileDataImpl.pageOut(tile)) {
                    extraNeed--;
                    currentFree++;
                }
                it.remove();
            }
        }

        return currentFree;
    }



    /**
     * 瓦片是否脏——脏 = GPU 数据已过期，驱逐代价最小。
     */
    private boolean isOutdate(Tile tile) {
        GLAtlas.GLTileData uploadableTile = tile.queryData(GLAtlas.GLTileData.class);
        return uploadableTile == null || uploadableTile.isDirty();
    }

    @Override
    public GLAtlas[] getAtlases() {
        return atlases;
    }

    @Override
    public boolean allocateAt(TextureTileSlot slot, TileRef ref) {
        int idx  = slot.getUnit();
        GLAtlas atlas = atlases[idx];
        if (atlas == null) {
            throw new IllegalArgumentException("No atlas with idx  " + idx );
        }

        int layer = slot.getLayer();
        int dataColumn   = slot.getDataColumn();
        int dataRow   = slot.getDataRow();

        if (atlas.isOccupiedAt(layer, dataColumn, dataRow)) {
            return false;
        }

        TileFactory factory = new SlottedTileFactory(atlases, (tx, ty) -> slot);
        pageIn(ref, factory);

        // 记录提升的瓦片，方便换下
        liftedTiles.add(ref.getTile());
        return true;
    }

    @Override
    public boolean allocate(TileRef ref) {
        GLAtlas idleAtlas = null;
        for (GLAtlas atlas : atlases) {
            if (!atlas.isFull()) {
                idleAtlas = atlas;
                break;
            }
        }
        if (idleAtlas == null){
            return false;
        }
        AtlasTileFactory factory = new AtlasTileFactory(idleAtlas);
        pageIn(ref, factory);

        // 记录提升的瓦片，方便换下
        liftedTiles.add(ref.getTile());
        return true;
    }

    @Override
    public AtlasLayerRange allocateContinuously(List<TileRef> tiles,
                                                List<TileRef> restTiles) {
        if (tiles == null) throw new NullPointerException("tiles");
        if (restTiles == null) throw new NullPointerException("restTiles");

        if (tiles.isEmpty()) {
            return null;
        }

        // ── 1. 找空闲层最多的 atlas ──
        int bestAtlasIdx = -1;
        Range bestRange = Range.EMPTY;

        for (int i = 0; i < atlases.length; i++) {
            GLAtlas atlas = atlases[i];
            if (atlas == null) continue;
            Range free = atlas.findMaxContiguousFreeLayers();
            if (free.getCount() > bestRange.getCount()) {
                bestAtlasIdx = i;
                bestRange = free;
            }
        }

        if (bestAtlasIdx < 0) {
            restTiles.addAll(tiles);
            return null;
        }

        GLAtlas atlas = atlases[bestAtlasIdx];
        int tilesPerLayer = atlas.getLayout().getTilesPerLayer();
        int tilesPerEdge  = atlas.getLayout().getTilesPerEdge();

        // ── 2. 计算能填充的整层数 ──
        int fullLayers = Math.min(tiles.size() / tilesPerLayer,
                bestRange.getCount());
        if (fullLayers == 0) {
            restTiles.addAll(tiles);
            return null;
        }

        int toAllocate = fullLayers * tilesPerLayer;

        // ── 3. 用 allocateAt 精确放置 ──
        for (int i = 0; i < toAllocate; i++) {
            int layerOffset = i / tilesPerLayer;
            int local = i % tilesPerLayer;
            int dataColumn = local % tilesPerEdge;
            int dataRow    = local / tilesPerEdge;

            int layer = bestRange.getStart() + layerOffset;

            TextureTileSlot slot = new TextureTileSlot(
                    bestAtlasIdx, layer, dataRow, dataColumn);

            if (!allocateAt(slot, tiles.get(i))) {
                throw new IllegalStateException(
                        "slot unexpectedly occupied: atlas=" + bestAtlasIdx
                                + ", layer=" + layer
                                + ", col=" + dataColumn
                                + ", row=" + dataRow);
            }
        }

        // ── 4. 剩余写回 ──
        for (int i = toAllocate; i < tiles.size(); i++) {
            restTiles.add(tiles.get(i));
        }

        // ── 5. 返回分配范围 ──
        return new AtlasLayerRange(atlas, bestRange.getStart(), fullLayers);
    }

    /**
     * 用给定 factory 把瓦片换入。
     *
     * <p>通过临时 generator 画布创建新 tile（factory 在此换入），
     * 再 merge 回原画布。generator 关闭时释放临时引用。
     */
    private void pageIn(TileRef ref, TileFactory factory) {
        TiledCanvas canvas = ref.getCanvas();
        try (TiledCanvas generator = new TiledCanvas(
                canvas.getTileSize(),
                canvas.getDefaultPixel(),
                factory)) {
            generator.replaceTile(ref.getTile());
            canvas.mergeCanvas(generator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}