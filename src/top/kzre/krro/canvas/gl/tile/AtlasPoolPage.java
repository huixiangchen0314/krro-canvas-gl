package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.Tile;

import java.util.Set;

/**
 * Atlas 池的换页接口。把画布上的瓦片换入 atlas 槽位。
 *
 * <p><b>职责</b>：
 * <ul>
 *   <li>暴露 atlas 数组——规划阶段据此分配槽位</li>
 *   <li>提供精确换入——执行阶段按规划好的槽位落实</li>
 * </ul>
 *
 * <p><b>数组快照</b>：{@link #getAtlases()} 返回调用时刻的 atlas
 * 数组副本。之后池的变化不影响已返回的 page。规划器应在规划
 * 开始时取快照，整个规划-执行周期用同一个 page。
 *
 * <p><b>线程契约</b>：本接口的实现不保证线程安全。调用方必须
 * 自行串行化——所有方法应在同一线程调用。
 */
public interface AtlasPoolPage {

    /**
     * 驱逐所有脏瓦片。
     *
     * <p>遍历"已提升瓦片"队列，对每个脏瓦片执行 {@code pageOut}——
     * 从 GPU 卸载并从队列移除。干净瓦片保留不动。
     *
     * <p><b>和 {@link #ensureSlotsKeep} 的区别</b>：
     * <ul>
     *   <li>{@code purge}——无条件驱逐所有脏瓦片，不管空闲多少</li>
     *   <li>{@code ensureSlotsKeep}——只驱逐到满足目标空闲数，且保留 {@code keepTiles}</li>
     * </ul>
     *
     * <p><b>副作用</b>：被驱逐的瓦片从 GPU 侧卸载。CPU 侧瓦片数据不受影响。
     */
    void purge();

    /**
     * 确保 atlas 至少有 {@code counts} 个空闲槽位——保留 {@code keepTiles}。
     *
     * <p><b>驱逐策略</b>：
     * <ol>
     *   <li>优先驱逐：不在 {@code keepTiles} 中的脏瓦片</li>
     *   <li>其次驱逐：不在 {@code keepTiles} 中的干净瓦片</li>
     *   <li>不驱逐：{@code keepTiles} 中的瓦片</li>
     * </ol>
     *
     * <p>驱逐持续到空闲满足 {@code counts}，或只剩 {@code keepTiles}
     * 中的瓦片为止。返回驱逐后的实际空闲槽位数——调用方通过比较
     * 返回值与 {@code counts} 判断是否满足。
     *
     * <p><b>副作用</b>：被驱逐的瓦片从 GPU 侧卸载（{@code pageOut}）。
     * CPU 侧瓦片数据不受影响，下次可重新提升。
     *
     * @param counts    期望的空闲槽位数，须 &gt; 0
     * @param keepTiles 不可驱逐的瓦片集合；{@code null} 表示无保留
     * @return 驱逐后 atlas 的实际空闲槽位数
     * @throws IllegalArgumentException {@code counts <= 0}
     */
    int ensureSlotsKeep(int counts, Set<Tile> keepTiles);

    /**
     * 本页涉及的全部 atlas。数组下标即 atlasIndex，
     * 也是 {@link TextureTileSlot#getUnit()} 和 bundle 的
     * {@code bindables} 下标。
     *
     * <p>null 位置表示该下标没有 atlas——规划阶段跳过。
     *
     * <p>返回数组视为只读。调用方不得修改。
     */
    GLAtlas[] getAtlases();

    /**
     * 把瓦片分配在指定 atlas 槽位。
     *
     * <p>语义：
     * <ul>
     *   <li>槽位空闲 → 分配，tile 换入 GPU 形态，返回 true</li>
     *   <li>槽位被占 → 返回 false，由调用方决定是否换出旧占用者</li>
     *   <li>slot 越界 → 抛 {@link IllegalArgumentException}</li>
     * </ul>
     *
     * @param slot 目标槽位
     * @param ref  瓦片引用
     * @return true 表示成功，false 表示槽位已被占用
     * @throws IllegalArgumentException slot 越界或 atlas 不存在
     */
    boolean allocateAt(TextureTileSlot slot, TileRef ref);
}