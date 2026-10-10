package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TileFactory;
import top.kzre.krro.util.tile.TiledCanvas;
import top.kzre.krro.util.tile.util.WeakReferenceQueue;

import java.util.Iterator;
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
            if (!isOutdate(tile)) {
                continue;
            }
            GLAtlas.GLTileDataImpl.pageOut(tile);
            it.remove();
        }
    }

    @Override
    public int ensureSlotsKeep(int counts, Set<Tile> keepTiles) {
        if (counts <= 0) {
            throw new IllegalArgumentException("counts must be > 0: " + counts);
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
            if (keepTiles != null && keepTiles.contains(tile)) {
                continue;
            }
            if (!isOutdate(tile)) {
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
                if (keepTiles != null && keepTiles.contains(tile)) {
                    continue;
                }
                if (isOutdate(tile)) {
                    continue;  // 脏的已在第一遍处理完
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