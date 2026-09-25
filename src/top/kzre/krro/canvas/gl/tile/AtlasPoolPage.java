package top.kzre.krro.canvas.gl.tile;

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
 * <p><b>线程契约</b>：
 * <ul>
 *   <li>{@code getAtlases} —— 纯数据读取，任意线程可调</li>
 *   <li>{@code allocateAt} —— 涉及 atlas 分配和 tile 替换，
 *       必须在 GL 线程</li>
 * </ul>
 */
public interface AtlasPoolPage {

    /**
     * 本页涉及的全部 atlas。数组下标即 atlasIndex，
     * 也是 {@link AtlasSlot#getAtlasIndex()} 和 bundle 的
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
    boolean allocateAt(AtlasSlot slot, TileRef ref);
}