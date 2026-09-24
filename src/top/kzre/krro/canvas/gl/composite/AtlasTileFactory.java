package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.tile.GLAtlas;
import top.kzre.krro.util.tile.DefaultTile;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TileData;
import top.kzre.krro.util.tile.TileFactory;

/**
 * 任意分配的瓦片工厂。把 CPU 侧瓦片换入 atlas 的空闲槽位。
 *
 * <p><b>与 {@link SlottedTileFactory} 的区别</b>：
 * <ul>
 *   <li>{@code SlottedTileFactory} —— 精确分配。规划阶段决定每块
 *       瓦片放哪个槽位，通过 mapper 返回</li>
 *   <li>{@code AtlasTileFactory} —— 任意分配。不关心具体位置，
 *       由 atlas 自行选择空闲槽位</li>
 * </ul>
 *
 * <p><b>典型场景</b>：背景画布的瓦片上传。背景的槽位不参与后续
 * shader 的 tileEntries 索引——它只需要在 atlas 上被采样，不需要
 * 精确引用。这时用本工厂，省去规划阶段为每块瓦片决定槽位的开销。
 *
 * <p><b>容量</b>：atlas 满时抛 {@link IllegalStateException}。
 * 这是规划错误——调用方需要保证 atlas 有足够的空闲槽位，通常由
 * 规划阶段的前置条件保证。
 *
 * <p><b>引用计数</b>：{@code atlas.allocate(data)} 不改变 data
 * 的引用计数。新 tile 对 data 的持有由调用方通过 acquire 保证——
 * 比如 {@code TiledCanvas.mergeCanvas} 内部的 acquire。
 *
 * <p><b>线程契约</b>：{@code create} 由 {@code TiledCanvas} 在
 * 创建 tile 时调用，涉及 atlas 分配，必须在 GL 线程上执行。
 */
public final class AtlasTileFactory implements TileFactory {

    /**
     * 目标 atlas。所有经过本工厂的瓦片都分配到这张 atlas 的空闲
     * 槽位。
     */
    private final GLAtlas atlas;

    public AtlasTileFactory(GLAtlas atlas) {
        if (atlas == null) {
            throw new IllegalArgumentException("atlas must not be null");
        }
        this.atlas = atlas;
    }

    @Override
    public Tile create(int tx, int ty, TileData data) {
        if (atlas.isFull()) {
            throw new IllegalStateException(
                    "atlas is full, cannot allocate tile ("
                            + tx + ", " + ty + ")");
        }
        GLAtlas.GLTileData gl = atlas.allocate(data);
        return new DefaultTile(tx, ty, gl);
    }
}