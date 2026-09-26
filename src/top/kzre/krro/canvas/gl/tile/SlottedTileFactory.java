package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.tile.GLAtlas.GLTileDataImpl;
import top.kzre.krro.util.tile.*;

/**
 * 按槽位绑定 atlas 的瓦片工厂。规划阶段和 {@link TiledCanvas} 之间的桥梁。
 *
 * <p>{@link TiledCanvas} 通过本工厂创建 tile 时，根据 {@link AtlasSlot}
 * 的 {@code unit} 选中对应的 atlas，把 {@link TileData} 换入指定槽位，
 * 并用 {@link DefaultTile} 承载换入后的 {@link GLTileDataImpl}。
 *
 * <p><b>为什么在 composite 包</b>：本工厂依赖 {@link AtlasSlot}——那是
 * 规划阶段的产物，属于 composite 层。放在 tile 包会让 tile 包反向
 * 依赖 composite 包，破坏分层。放在这里，依赖方向是 composite → tile，
 * 单向。
 *
 * <p><b>换页归 TiledCanvas 驱动</b>：factory 是 TiledCanvas 的构造
 * 参数，每次创建 tile 都会经过本工厂。GPU 形态的建立发生在
 * TiledCanvas 的创建路径上，不通过外部劫持已有 tile 的 data。
 *
 * <p><b>线程契约</b>：工厂的 {@code create} 由 TiledCanvas 在创建
 * tile 时调用。调用方必须保证本次调用在 GL 线程上（atlas 分配
 * 需要 GL 上下文）——通常由构建 GPU 画布的流程保证。
 */
public final class SlottedTileFactory implements TileFactory {

    /**
     * 坐标到槽位的映射。规划阶段决定，工厂只执行。
     */
    @FunctionalInterface
    public interface TileSlotMapper {
        AtlasSlot apply(int tx, int ty);
    }

    /**
     * 本批使用的 atlas 组。{@code atlases[i]} 绑定 unit {@code i}。
     */
    private final GLAtlas[] atlases;

    private final TileSlotMapper slotOf;

    /**
     * @param atlases 本批使用的 atlas 组，下标即 unit
     * @param slotOf  {@code (tx, ty) → TileSlot}，规划阶段决定每个坐标
     *                对应的 atlas 位置
     */
    public SlottedTileFactory(GLAtlas[] atlases, TileSlotMapper slotOf) {
        if (atlases == null || atlases.length == 0) {
            throw new IllegalArgumentException("atlases must not be empty");
        }
        if (slotOf == null) {
            throw new IllegalArgumentException("slotOf must not be null");
        }
        this.atlases = atlases;
        this.slotOf  = slotOf;
    }

    @Override
    public Tile create(int tx, int ty, TileData data) {
        // 已经是 GPU 形态：直接承载，不重复换入
        if (data instanceof GLAtlas.GLTileData) {
            return new DefaultTile(tx, ty, data);
        }

        AtlasSlot slot  = slotOf.apply(tx, ty);
        GLAtlas  atlas = atlases[slot.getAtlasIndex()];
        GLTileDataImpl gl = atlas.allocateAt(
                slot.getLayer(), slot.getDataColumn(), slot.getDataRow(), data);
        return new DefaultTile(tx, ty, gl);
    }
}