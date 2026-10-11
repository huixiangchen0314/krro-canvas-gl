package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.util.tile.Tile;

import java.util.List;
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

    /**
     * 把瓦片分配到任意一个有空闲槽位的 atlas——不指定具体位置。
     *
     * <p><b>与 {@link #allocateAt(TextureTileSlot, TileRef)} 的区别</b>：
     * <ul>
     *   <li>{@code allocateAt}——调用方指定 atlas + 层 + 层内坐标，
     *       用于规划阶段决定好精确位置的场景</li>
     *   <li>{@code allocate}——池自行挑选 atlas 和空闲槽位，
     *       用于无需关心具体位置、只要"换进 GPU"的场景</li>
     * </ul>
     *
     * <p><b>选择策略</b>：按 {@link #getAtlases()} 顺序遍历，
     * 取第一个未满的 atlas，在其中找一个空闲槽位换入。
     * 不保证分配的位置具有任何连续性——若调用方需要连续分配，
     * 应走 {@code allocateAt} 显式指定。
     *
     * <p><b>副作用</b>：瓦片被换入 GPU 形态（peer 被
     * {@link GLAtlas.GLTileDataImpl} 包装），并加入"已提升瓦片"
     * 队列——后续 {@link #purge()} 或 {@link #ensureSlotsKeep}
     * 可将其驱逐。
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用——换入过程涉及 GL 调用。
     *
     * @param ref 瓦片引用，非 null
     * @return true 表示换入成功；false 表示所有 atlas 均已满，
     *         无法分配
     * @throws NullPointerException ref 为 null
     */
    boolean allocate(TileRef ref);

    /**
     * 尝试把 {@code tiles} 中尽可能多的瓦片连续分配到整层——
     * 用于批量上传。未分配的尾部瓦片写入 {@code restTiles}。
     *
     * <h2>分配策略</h2>
     *
     * <p>按 {@code tiles} 顺序依次填充连续空闲层：
     * <ol>
     *   <li>遍历 atlas，寻找连续空闲的整层段——层内全部槽位未占用</li>
     *   <li>找到 {@code K} 个连续空闲层，就分配前 {@code K × tilesPerLayer}
     *       个瓦片到这些层——{@code tiles[i]} 落到 {@code startLayer + i / tilesPerLayer}
     *       层的第 {@code i % tilesPerLayer} 个槽位</li>
     *   <li>未能填入的尾部瓦片按原顺序追加到 {@code restTiles}</li>
     * </ol>
     *
     * <h2>多 atlas 场景</h2>
     *
     * <p>若单个 atlas 的连续空闲层不足，只分配该 atlas 能满足的部分——
     * 不跨 atlas 合批。跨 atlas 的瓦片需要调用方对每个 atlas 分别调用，
     * 或者直接走 {@link #allocate(TileRef)} 逐个分配。
     *
     * <h2>最少分配粒度</h2>
     *
     * <p>本方法以"整层"为单位分配：
     * <ul>
     *   <li>若 {@code tiles.size() < tilesPerLayer}——一个整层都填不满，
     *       不分配，全部写入 {@code restTiles}，返回 {@code null}</li>
     *   <li>若可用连续层数为 K——分配 {@code K × tilesPerLayer} 个瓦片，
     *       返回 {@code Range(startLayer, K)}</li>
     * </ul>
     *
     * <h2>多次调用</h2>
     *
     * <p>本方法一次最多使用一个 atlas。若待分配瓦片跨越多个 atlas，
     * 调用方需循环调用——每轮把 {@code restTiles} 作为下一轮的
     * {@code tiles} 输入。典型模式：
     * <pre>{@code
     * List<TileRef> remaining = new ArrayList<>(tiles);
     * List<TileRef> rest = new ArrayList<>();
     * while (true) {
     *     rest.clear();
     *     AtlasLayerRange r = page.allocateContinuously(remaining, rest);
     *     if (r == null) break;
     *     allocations.add(r);
     *     remaining = new ArrayList<>(rest);
     * }
     *
     * }</pre>
     * <h2>返回值</h2>
     *
     * <p>返回的 {@link AtlasLayerRange} 表示分配的层范围：
     * <ul>
     *   <li>{@code start}——起始层索引</li>
     *   <li>{@code count}——连续层数</li>
     * </ul>
     * 调用方拿到后可以直接调用
     * {@code atlas.uploadLayers(start, count)} 一次性上传。

     * <p>返回 {@code null} 表示一个整层都未能分配——全部瓦片写入
     * {@code restTiles}。
     *
     * <h2>副作用</h2>
     *
     * <p>被分配的瓦片换入 GPU 形态（同 {@link #allocateAt}），
     * 并加入"已提升瓦片"队列。已分配瓦片的顺序与 {@code tiles} 中
     * 的位置一一对应——调用方可以据此准备 uploadLayers 的 staging
     * 数据。
     *
     * <h2>线程契约</h2>
     *
     * <p>必须在 GL 线程调用——换入过程涉及 GL 调用。
     *
     * @param tiles     待分配的瓦片列表，按顺序分配；非 null
     * @param restTiles 未分配的尾部瓦片将按原顺序追加到此列表；
     *                  调用方预先提供（可以是空列表）；非 null
     * @return 分配的层范围 {@code (startLayer, count)}；
     *         一个整层都未分配时返回 {@code null}
     * @throws NullPointerException tiles 或 restTiles 为 null
     * @throws IllegalArgumentException tiles 为空
     */
    AtlasLayerRange allocateContinuously(List<TileRef> tiles, List<TileRef> restTiles);
}