package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.tile.GLAtlas;

/**
 * Atlas 池的换页接口。把画布上的瓦片换入 atlas 槽位。
 *
 * <p><b>职责</b>：管理 tile 的 CPU → GPU 换入过程。规划阶段决定
 * 槽位映射，执行阶段通过本接口把映射落实。
 *
 * <p><b>两种分配模式</b>：
 * <ul>
 *   <li>{@link #allocateAt} —— 精确分配。规划阶段已决定槽位，
 *       执行阶段按映射落实</li>
 * </ul>
 *
 * <p><b>atlas 数组即单元表</b>：{@link #getAtlases()} 返回的数组
 * 下标即纹理单元。null 位置表示该单元不绑定 atlas。
 *
 * <p><b>线程契约</b>：涉及 atlas 分配和 tile 替换，必须在 GL 线程
 * 上调用。{@code getAtlases} 是纯数据读取，任意线程可调。
 */
public interface AtlasPoolPage {

    /**
     * 本页涉及的全部 atlas。下标即纹理单元，null 表示该单元不绑定。
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