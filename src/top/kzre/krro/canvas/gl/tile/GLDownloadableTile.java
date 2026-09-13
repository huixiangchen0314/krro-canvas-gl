package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.TiledCanvas;

/**
 * 可下载瓦片——能把自己下载到目标画布的指定位置。
 *
 * <p>瓦片不知道自己「在哪」——位置由 canvas 的 key（{@code tx, ty}）
 * 决定。外部遍历 canvas 时知道位置，直接传给瓦片执行下载。
 *
 * <p><b>用途</b>：上层通过 {@link top.kzre.krro.util.tile.Tile#queryData(Class)}
 * 查询此能力，在遍历中执行下载。
 *
 * <pre>{@code
 * for (long key : canvas.getTiles()) {
 *     int tx = TiledCanvas.unpackTx(key);
 *     int ty = TiledCanvas.unpackTy(key);
 *     Tile tile = canvas.getTile(tx, ty);
 *     GLDownloadableTile dl = tile.queryData(GLDownloadableTile.class);
 *     if (dl != null) {
 *         dl.downloadTo(target, tx, ty);
 *     }
 * }
 * }</pre>
 *
 * <p><b>线程契约</b>：必须在 GL 线程上调用。
 */
public interface GLDownloadableTile extends GLTile {

    /**
     * 下载本瓦片到目标画布的 {@code (tx, ty)} 格。
     *
     * <p>坐标由调用方提供——瓦片内部的 {@code (layer, sx, sy)} 决定
     * 从纹理的哪个位置读取数据。
     *
     * @param target 目标画布
     * @param tx     目标格 x 坐标
     * @param ty     目标格 y 坐标
     */
    void downloadTo(TiledCanvas target, int tx, int ty);
}