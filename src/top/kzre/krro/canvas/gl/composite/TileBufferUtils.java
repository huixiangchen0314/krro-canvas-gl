package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.tile.GLTileDescriptor;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合成阶段瓦片缓冲打包工具。
 *
 * <p><b>延迟构建</b>：{@code packBuffers} 从 shader 工厂挪到
 * 执行阶段——因为 {@link GLTileDescriptor} 的执行依赖
 * {@code bind} 之后才确定的纹理单元。调用方（{@code CompositeTask}）
 * 在瓦片换入、shader 构造之前调用。
 *
 * <p><b>输入</b>：
 * <ul>
 *   <li>{@code tileSet}——规划阶段的投影结果</li>
 *   <li>{@code descriptors}——与 {@code tileSet.layerTileRefs} 一一
 *       对应的 GPU 描述符，由 {@code GLTile.getDescriptor()} 生成</li>
 *   <li>{@code viewInfo}——屏幕瓦片网格</li>
 * </ul>
 *
 * <p><b>输出</b>：三张扁平 buffer——layerTable / tileTable / indexList。
 *
 * <p><b>线程契约</b>：纯 CPU，任意线程可调。无 GL 调用。
 */
public final class TileBufferUtils {

    private TileBufferUtils() {}

    /**
     * 打包瓦片缓冲。
     *
     * @param tileSet     规划阶段的投影结果
     * @param descriptors 与 {@code tileSet.layerTileRefs} 一一对应的
     *                    GPU 描述符
     * @param viewInfo    屏幕瓦片网格
     * @return 打包后的缓冲
     * @throws IllegalArgumentException {@code descriptors} 数量与
     *                                  {@code tileRefs} 不匹配
     * @throws IllegalStateException    桶里含有不属于本 tileSet 的 ref
     */
    public static ShaderPackedBuffer packBuffers(
            ShaderLayerTileSet tileSet,
            List<GLTileDescriptor> descriptors,
            ViewTileInfo viewInfo) {

        List<ShaderLayerTileRef> tileRefs = tileSet.layerTileRefs;
        List<float[]> layerEntries = tileSet.layerTable;
        Map<Long, List<ShaderLayerTileRef>> buckets = tileSet.porjectTilesMap;

        int totalTileCount = tileRefs.size();
        int screenTileCount = viewInfo.getViewTileCount();
        int tileSize = viewInfo.getTileSize();

        // ── descriptors 数量校验 ──
        if (descriptors.size() != totalTileCount) {
            throw new IllegalArgumentException(
                    "descriptors count " + descriptors.size()
                            + " != tileRefs count " + totalTileCount);
        }

        // ── 延迟索引构建：ref 对象 → 位置 ──
        // IdentityHashMap 用引用相等——每个 ref 都是唯一实例，前插只改
        // 位置不改对象本身，因此同一 ref 在整个生命周期里映射到同一位置。
        Map<ShaderLayerTileRef, Integer> refToIndex =
                new IdentityHashMap<>(totalTileCount * 2);
        for (int i = 0; i < totalTileCount; i++) {
            refToIndex.put(tileRefs.get(i), i);
        }
        System.out.println("a shader totalTileCount: " + totalTileCount);

        // ── layerTable 扁平化 ──
        float[] layerTable = new float[layerEntries.size() * 8];
        for (int i = 0; i < layerEntries.size(); i++) {
            System.arraycopy(layerEntries.get(i), 0, layerTable, i * 8, 8);
        }

        // ── tileTable：按位置排列，每 tile 8 float ──
        float[] tileTable = new float[totalTileCount * 8];
        for (int i = 0; i < totalTileCount; i++) {
            ShaderLayerTileRef layerTileRef = tileRefs.get(i);
            Tile tile = layerTileRef.getTile();
            GLTileDescriptor descriptor = descriptors.get(i);

            int unit = descriptor.getUnit();
            if (unit < 0){
                throw new IllegalStateException(
                        "descriptor unit not assigned; ensure bindables are bound before packBuffers");
            }

            float u0 = descriptor.getU0();
            float v0 = descriptor.getV0();

            // 瓦片在屏幕/画布空间的像素原点——与 shader 里 vPixel 同空间
            float glTileOriginX = tile.tx() * tileSize;
            float glTileOriginY = tile.ty() * tileSize;

            int base = i * 8;
            tileTable[base] = u0;
            tileTable[base + 1] = v0;
            tileTable[base + 2] = unit;
            tileTable[base + 3] = descriptor.getLayer();
            tileTable[base + 4] = glTileOriginX;
            tileTable[base + 5] = glTileOriginY;
            tileTable[base + 6] = layerTileRef.getLayerTableIndex();
            tileTable[base + 7] = 0f;

            if (i < 3) {
                System.out.println("[tileTable " + i + "]"
                        + " u0=" + tileTable[i*8]
                        + " v0=" + tileTable[i*8+1]
                        + " unit=" + tileTable[i*8+2]
                        + " texLayer=" + tileTable[i*8+3]
                        + " originX=" + tileTable[i*8+4]
                        + " originY=" + tileTable[i*8+5]);
            }
        }

        // ── 屏幕瓦片 → indexList ──
        int[] gridTileXY = viewInfo.getTileXY();
        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];

        int tileCount = 0;
        for (int i = 0; i < screenTileCount; i++) {
            int tileX = gridTileXY[i * 2];
            int tileY = gridTileXY[i * 2 + 1];
            List<ShaderLayerTileRef> b = buckets.get(TiledCanvas.pack(tileX, tileY));
            int sz = b == null ? 0 : b.size();
            counts[i] = sz;
            tileCount += sz;
        }

        int[] indexList = new int[tileCount];
        int cursor = 0;
        for (int i = 0; i < screenTileCount; i++) {
            offsets[i] = cursor;
            int sx = gridTileXY[i * 2];
            int sy = gridTileXY[i * 2 + 1];
            List<ShaderLayerTileRef> bucket = buckets.get(TiledCanvas.pack(sx, sy));
            if (bucket == null) continue;

            for (ShaderLayerTileRef ref : bucket) {
                Integer idx = refToIndex.get(ref);
                if (idx == null) {
                    // withRefs 的过滤保证了桶里不会有子集外的 ref。
                    // 走到这里说明上游投影状态不一致——立刻暴露，不要静默。
                    throw new IllegalStateException(
                            "bucket contains a ref not present in layerTileRefs: "
                                    + ref + " (canvas=" + ref.getCanvas() + ")");
                }
                indexList[cursor++] = idx;
            }
        }

        return new ShaderPackedBuffer(
                layerTable, layerEntries.size(),
                tileTable, totalTileCount,
                offsets, counts,
                indexList, tileCount);
    }
}