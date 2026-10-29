package top.kzre.krro.canvas.gl.composite;

import java.util.*;

/**
 * 一次绘制的图层瓦片集合。{@link TileProjector} 的投影结果，
 * {@code BaseFactory} 的打包输入。
 *
 * <p><b>核心数据</b>：{@link #layerTileRefs} 是一个扁平数组，位置
 * 即绘制顺序。其他字段是围绕它的索引结构：
 * <ul>
 *   <li>{@link #layerTable}——每个图层一份变换 + 透明度记录</li>
 *   <li>{@link #porjectTilesMap}——屏幕瓦片 → 该瓦片上要绘制的
 *       <b>ref 对象列表</b>（不是 idx）</li>
 *   <li>{@link #layerIndex}——图层对象 → {@code layerTable} 槽位，去重用</li>
 *   <li>{@link #layerTileIndex}——(图层, 图层瓦片键) → ref 对象，去重用</li>
 * </ul>
 *
 * <p><b>为什么桶里存 ref 对象而不是 idx</b>：
 * {@link TileProjector#projectLayerIntoFirst} 会把 backdrop 的 ref
 * 前插到 {@code layerTileRefs} 头部，导致所有已有 ref 的位置整体后移。
 * 若桶里存 idx，则每次前插都要遍历所有桶做一次 {@code +shift} 重映射
 * ——既昂贵又容易漏改。存 ref 对象则完全规避这个问题：位置由
 * {@code packBuffers} 在最终阶段一次性映射，前插只改数组、不改桶。
 *
 * <p><b>分片隔离</b>：{@link #withRefs} 会深拷贝所有可变字段，并将
 * 每个桶过滤为"只包含子集内的 ref"。这样后续在子集上做的前插、
 * layerTable 追加都不会污染其他子集。
 *
 * <p><b>线程契约</b>：纯 CPU 数据，构造和使用均在合成规划线程。
 * 不跨线程共享。
 */
public final class ShaderLayerTileSet {

    /**
     * 扁平 ref 数组。位置即绘制顺序。顺序一经 {@code packBuffers}
     * 前的最终排列确定，运行期不再调整。
     */
    final List<ShaderLayerTileRef> layerTileRefs;

    /**
     * 图层表。每条记录 8 个 float：
     * <pre>
     *   [0..3] 逆变换矩阵前 4 元素
     *   [4..5] 逆变换矩阵平移
     *   [6]    图层透明度
     *   [7]    保留
     * </pre>
     *
     * <p>由 {@link #layerIndex} 保证每图层去重——同一图层只占一个
     * 槽位，无论它被多少屏幕瓦片投影命中。
     */
    final List<float[]> layerTable;

    /**
     * 屏幕瓦片桶。键是打包后的屏幕瓦片坐标，值是该屏幕瓦片上要
     * 绘制的 ref <b>对象</b>列表。
     *
     * <p><b>桶内顺序</b>：普通图层按投影顺序追加；backdrop 通过
     * {@code addAll(0, ...)} 前插。桶内顺序决定该屏幕瓦片内部
     * ref 的相对先后。
     *
     * <p><b>不允许跨子集共享</b>——{@link #withRefs} 会为每个子集
     * 深拷贝一份桶。
     */
    final Map<Long, List<ShaderLayerTileRef>> porjectTilesMap;

    /**
     * 图层 → {@code layerTable} 槽位。去重缓存。
     */
    final Map<ILayer, Integer> layerIndex;

    /**
     * (图层, 图层瓦片键) → ref 对象。去重缓存：同一图层瓦片被多个
     * 屏幕瓦片投影命中时只生成一份 ref。
     *
     * <p><b>为什么缓存 ref 对象而非位置</b>：位置会因 backdrop 前插
     * 而改变，缓存位置会在每次前插后失效。缓存对象则天然稳定——
     * 前插只是把它挪到数组别处，对象本身不变。
     */
    final Map<ShaderLayerTileKey, ShaderLayerTileRef> layerTileIndex;

    private ShaderLayerTileSet(List<ShaderLayerTileRef> layerTileRefs,
                               List<float[]> layerTable,
                               Map<Long, List<ShaderLayerTileRef>> porjectTilesMap,
                               Map<ILayer, Integer> layerIndex,
                               Map<ShaderLayerTileKey, ShaderLayerTileRef> layerTileIndex) {
        this.layerTileRefs = layerTileRefs;
        this.layerTable = layerTable;
        this.porjectTilesMap = porjectTilesMap;
        this.layerIndex = layerIndex;
        this.layerTileIndex = layerTileIndex;
    }

    private ShaderLayerTileSet() {
        this.layerTileRefs = new ArrayList<>();
        this.layerTable = new ArrayList<>();
        this.porjectTilesMap = new HashMap<>();
        this.layerIndex = new HashMap<>();
        this.layerTileIndex = new HashMap<>();
    }

    /**
     * 创建一个空的瓦片集合。
     *
     * <p>典型用法：
     * <pre>
     *   ShaderLayerTileSet out = ShaderLayerTileSet.create();
     *   TileProjector.projectLayersInto(layers, dirty, tileSize, out);
     * </pre>
     *
     * @return 全空集合
     */
    public static ShaderLayerTileSet create() {
        return new ShaderLayerTileSet();
    }

    public List<ShaderLayerTileRef> getLayerTileRefs() {
        return layerTileRefs;
    }

    /**
     * 用新的 {@code layerTileRefs} 派生一个独立子集。
     *
     * <p><b>用途</b>：分片。一个 batch 的瓦片超出 atlas 容量时，
     * 按容量切成多个子集，每个子集是一个 {@code withRefs} 视图。
     *
     * <p><b>独立语义</b>（相对旧版的浅拷贝）：
     * <ul>
     *   <li>{@code layerTileRefs} 使用传入列表——调用方负责它的
     *       idx 语义自洽</li>
     *   <li>{@code layerTable} 深拷贝——子集上追加的 backdrop
     *       layerTable 记录不会污染父集合</li>
     *   <li>{@code porjectTilesMap} 深拷贝并过滤：只保留值列表里
     *       出现在子集 {@code layerTileRefs} 中的 ref。子集后续做
     *       {@code projectLayerIntoFirst} 时前插的内容只落进自己的桶</li>
     *   <li>{@code layerIndex} / {@code layerTileIndex} 深拷贝——
     *       隔离各自的去重缓存</li>
     * </ul>
     *
     * @param layerTileRefs 新子集的 ref 列表
     * @return 与其他子集完全隔离的新集合
     */
    public ShaderLayerTileSet withRefs(List<ShaderLayerTileRef> layerTileRefs) {

        // 子集内 ref 的身份集合——用 IdentityHashMap 语义，
        // 避免 ShaderLayerTileRef 若有 equals 实现而误合并。
        Set<ShaderLayerTileRef> subset = new HashSet<>(layerTileRefs);

        Map<Long, List<ShaderLayerTileRef>> newBuckets =
                new HashMap<>(porjectTilesMap.size() * 2);
        for (Map.Entry<Long, List<ShaderLayerTileRef>> e : porjectTilesMap.entrySet()) {
            List<ShaderLayerTileRef> src = e.getValue();
            List<ShaderLayerTileRef> filtered = new ArrayList<>(src.size());
            for (ShaderLayerTileRef ref : src) {
                if (subset.contains(ref)) filtered.add(ref);
            }
            if (!filtered.isEmpty()) newBuckets.put(e.getKey(), filtered);
        }

        return new ShaderLayerTileSet(
                layerTileRefs,
                new ArrayList<>(layerTable),
                newBuckets,
                new HashMap<>(layerIndex),
                new HashMap<>(layerTileIndex));
    }
}