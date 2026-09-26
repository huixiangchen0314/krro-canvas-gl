package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.TileFactory;
import top.kzre.krro.util.tile.TiledCanvas;

/**
 * {@link AtlasPoolPage} 的默认实现。
 */
public class DefaultAtlasPoolPage implements AtlasPoolPage {

    private final GLAtlas[] atlases;

    public DefaultAtlasPoolPage(GLAtlas[] atlases) {
        if (atlases == null || atlases.length == 0) {
            throw new IllegalArgumentException("atlases must not be empty");
        }
        this.atlases = atlases;
    }


    @Override
    public GLAtlas[] getAtlases() {
        return atlases;
    }

    @Override
    public boolean allocateAt(AtlasSlot slot, TileRef ref) {
        int idx  = slot.getAtlasIndex();
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