package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

/**
 * 瓦片位置引用。描述"某画布上的某块瓦片"。
 *
 * <p>换页需要 TiledCanvas——Tile 不知道自己的容器。坐标由
 * {@link Tile#tx()} / {@link Tile#ty()} 提供。
 *
 * <p><b>为什么存 Tile 而不是 (tx, ty)</b>：Tile 自己携带坐标，
 * 换页直接用它，不需要再回 canvas 反查。存 Tile 引用也让后续
 * 的 pageOut / replaceTile 少一次查找。
 */
public final class TileRef {

    private final TiledCanvas canvas;
    private final Tile tile;

    public TileRef(TiledCanvas canvas, Tile tile) {
        if (canvas == null) throw new IllegalArgumentException("canvas must not be null");
        if (tile == null)   throw new IllegalArgumentException("tile must not be null");
        this.canvas = canvas;
        this.tile   = tile;
    }

    public TiledCanvas getCanvas() { return canvas; }
    public Tile       getTile()   { return tile; }

    /** 瓦片 x 坐标。转发自 {@link Tile#tx()}。 */
    public int getTx() { return tile.tx(); }

    /** 瓦片 y 坐标。转发自 {@link Tile#ty()}。 */
    public int getTy() { return tile.ty(); }

    /**
     * 把本引用指向的瓦片从 GPU 形态换回 CPU 形态。
     *
     * <p>内部调 {@link GLAtlas.GLTileData#pageOut(Tile)}：
     * <ul>
     *   <li>tile 当前持有 {@code GLTileData} → 引用计数不变，
     *       tile 换成持有 peer，atlas 槽位显式释放</li>
     *   <li>tile 不持有 {@code GLTileData} → 静默返回</li>
     * </ul>
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     */
    public void pageOut(){
        GLAtlas.GLTileData.pageOut(tile);
    }

    @Override
    public String toString() {
        return "TileRef{canvas=" + System.identityHashCode(canvas)
                + ", tx=" + getTx() + ", ty=" + getTy() + "}";
    }
}
