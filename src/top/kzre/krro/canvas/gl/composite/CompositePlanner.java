package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.core.layer.LayerUtils;
import top.kzre.krro.canvas.gl.composite.shaders.NormalShader;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;
import top.kzre.krro.canvas.gl.tile.AtlasSlot;
import top.kzre.krro.canvas.gl.tile.GLAtlas;
import top.kzre.krro.canvas.gl.tile.GLAtlas.GLTileData;
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayout;
import top.kzre.krro.canvas.gl.tile.TileRef;
import top.kzre.krro.util.math.KMath;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 合成规划器。
 *
 * <p><b>输入</b>：图层列表、上下文、ping-pong FBO、脏屏幕瓦片集合。
 *
 * <p><b>输出</b>：{@link CompositeRequest}。{@code dirtyTiles} 为空
 * 或可见瓦片为空时返回 {@code null}——调用方跳过本次合成。
 *
 * <p><b>规划阶段不接触 GL</b>。所有分配、打包都是纯 CPU 计算，
 * 由规划线程执行，与 GL 线程的渲染并行。
 *
 * <h2>规划产物</h2>
 * <ul>
 *   <li>{@code layerTable}——每图层 8 float：逆变换 + alpha</li>
 *   <li>{@code tileTable}——每瓦片 8 float：uv 起点 + unit + texLayer
 *       + 图层内坐标 + layerEntry</li>
 *   <li>{@code offsets} / {@code counts} / {@code indexList}——
 *       屏幕瓦片到 tileTable 条目的索引</li>
 * </ul>
 *
 * <h2>精确收集与分桶</h2>
 *
 * <p>每块脏屏幕瓦片经逆变换（{@link LayerUtils#transformTile}）精确
 * 映射到覆盖它的图层瓦片——同一次逆变换同时产出：
 * <ul>
 *   <li>可见瓦片列表（按 {@code (layer, tileKey)} 去重）</li>
 *   <li>屏幕瓦片 → 可见瓦片下标列表的分桶</li>
 * </ul>
 * 不再用 AABB 反推屏幕归属——逆变换的结果本身就是精确覆盖关系。
 *
 * <h2>槽位复用</h2>
 *
 * <p>可见瓦片若已是 GPU 形态（{@link GLTileData}），规划阶段直接
 * 复用它当前所在的槽位，不再分配新槽。只有 CPU 侧的瓦片才走
 * {@code allocateSlots} 的空槽扫描。
 */
public final class CompositePlanner {

    private CompositePlanner() {}

    // ═══════════════════════════════════════════════
    // 主流程
    // ═══════════════════════════════════════════════

    public static CompositeRequest plan(
            List<ILayer> layers,
            GLCompositeContext context,

            GLFramebuffer fboA,
            GLFramebuffer fboB,
            Set<Long> dirtyTiles) {

        int viewWidth  = context.getViewWidth();
        int viewHeight = context.getViewHeight();
        int tileSize   = context.getTileSize();
        GLProgramCache programs = context.getProgramCache();

        if (viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalStateException(
                    "view size not set; call adjustViewSize first");
        }
        if (layers == null) {
            throw new IllegalArgumentException("layers must not be null");
        }
        if (dirtyTiles == null) {
            throw new IllegalArgumentException("dirtyTiles must not be null");
        }
        if (layers.isEmpty() || dirtyTiles.isEmpty()) {
            return null;
        }
        List<Long> dirtyList = new ArrayList<>(dirtyTiles);
        Collections.sort(dirtyList);

        AtlasPoolPage page = context.getAtlasPool().page();

        // ── 构建只含脏屏幕瓦片的 grid ──
        ViewportGrid grid = buildViewGrid(
                dirtyList, viewWidth, viewHeight, tileSize);

        // ── 逆变换收集可见瓦片 + 精确分桶 ──
        VisibleSet visibleSet = collectVisibleTiles(layers, dirtyList, tileSize);
        if (visibleSet.visible.isEmpty()) {
            System.out.println("[CompositePlanner] No visible tiles found");
            return null;
        }
        System.out.println("[CompositePlanner] Found visible tiles: " + visibleSet.visible.size());

        // ── 分配槽位（下标与 visible 一一对应） ──
        GLAtlas[] atlases = page.getAtlases();
        List<AtlasSlot> slots = new ArrayList<>(visibleSet.visible.size());
        Map<AtlasSlot, TileRef> slotMap =
                allocateSlots(visibleSet.visible, atlases, slots);

        // ── 打包 buffer ──
        PackedBuffers packed = packBuffers(visibleSet, slots, atlases, grid);
        System.out.println("[CompositePlanner] packedBuffers: " + packed);
        // ── 组装 group / shader / bundle ──
        TileBufferGroup group = new TileBufferGroup(
                ShaderConstants.TEXTURE_UNIT_TILE_TABLE,
                ShaderConstants.TEXTURE_UNIT_LAYER_TABLE,
                ShaderConstants.TEXTURE_UNIT_INDEX_TABLE,
                packed.offsets,
                packed.counts,
                packed.layerTable,
                packed.layerEntryCount,
                packed.tileTable,
                packed.tileEntryCount,
                packed.indexList,
                packed.indexCount,
                slotMap);

        GLProgram program = programs.get(Blends.NORMAL);
        GLTiledTextureLayout layout = atlases[0].getLayout();
        Shader shader = new NormalShader(
                program, group, viewWidth, viewHeight, layout);

        GLBindable[] bindables = new GLBindable[atlases.length];
        System.arraycopy(atlases, 0, bindables, 0, atlases.length);

        TileBufferBundle bundle = new TileBufferBundle(shader, bindables);

        return CompositeRequest.of(context,
                grid,
                Collections.singletonList(bundle),
                page,
                fboA, fboB);
    }

    private static ViewportGrid buildViewGrid(List<Long> dirtyTiles,
                                              int viewWidth, int viewHeight,
                                              int tileSize) {
        int[] tileXY = new int[dirtyTiles.size() * 2];
        int cursor = 0;
        for (Long key : dirtyTiles) {
            tileXY[cursor++] = TiledCanvas.unpackTx(key);
            tileXY[cursor++] = TiledCanvas.unpackTy(key);
        }
        return ViewportGrid.createPartial(
                viewWidth, viewHeight, tileSize, tileXY);
    }

    // ═══════════════════════════════════════════════
    // 收集可见瓦片 + 精确分桶
    // ═══════════════════════════════════════════════

    /**
     * 逆变换收集：每块脏屏幕瓦片 → 覆盖它的图层瓦片。
     *
     * <p>同一次逆变换同时产出可见瓦片列表和屏幕分桶——不再用 AABB
     * 反推屏幕归属。可见瓦片按 {@code (layer, tileKey)} 去重：同一块
     * 图层瓦片被多个脏屏幕瓦片覆盖时，只创建一个条目；多个脏屏幕
     * 瓦片的桶都指向同一个下标。
     *
     * @return 可见瓦片列表、图层表、屏幕分桶
     */
    private static VisibleSet collectVisibleTiles(
            List<ILayer> layers, List<Long> dirtyTiles, int tileSize) {

        Map<ILayer, Integer> layerEntryMap = new IdentityHashMap<>();
        Map<VisibleKey, Integer> visibleIndex = new HashMap<>();

        List<VisibleTile> visible = new ArrayList<>();
        List<float[]> layerTable = new ArrayList<>();
        Map<Long, List<Integer>> buckets = new HashMap<>();

        for (ILayer layer : layers) {
            if (!layer.isVisible()) continue;
            TiledCanvas canvas = layer.getCanvas();
            if (canvas == null) continue;

            float alpha = layer.getOpacity();
            float[] mat2d = layer.getTransform();
            float[] inv = KMath.mat2dInv(mat2d);
            if (inv == null) {
                throw new IllegalStateException(
                        "singular layer transform (det ≈ 0)");
            }

            for (Long dirtyKey : dirtyTiles) {
                Set<Long> layerKeys = LayerUtils.transformTile(dirtyKey, tileSize, inv);
                if (layerKeys == null || layerKeys.isEmpty()) continue;

                for (Long layerKey : layerKeys) {
                    int tx = TiledCanvas.unpackTx(layerKey);
                    int ty = TiledCanvas.unpackTy(layerKey);
                    Tile tile = canvas.getTile(tx, ty);
                    if (tile == null) continue;

                    VisibleKey vk = new VisibleKey(layer, layerKey);
                    Integer idx = visibleIndex.get(vk);
                    if (idx == null) {
                        Integer lei = layerEntryMap.get(layer);
                        if (lei == null) {
                            lei = layerTable.size();
                            layerEntryMap.put(layer, lei);
                            layerTable.add(new float[]{
                                    inv[0], inv[1], inv[2], inv[3], inv[4], inv[5],
                                    alpha, 0f
                            });
                        }
                        idx = visible.size();
                        visible.add(new VisibleTile(canvas, tile, lei));
                        visibleIndex.put(vk, idx);
                    }

                    buckets.computeIfAbsent(dirtyKey, k -> new ArrayList<>())
                            .add(idx);
                }
            }
        }

        return new VisibleSet(visible, layerTable, buckets);
    }


    // ═══════════════════════════════════════════════
    // 分配槽位
    // ═══════════════════════════════════════════════

    /**
     * 为每块可见瓦片决定槽位。
     *
     * <p><b>复用优先</b>：瓦片若已是 {@link GLTileData} 形态——说明
     * 它已经待在某个 atlas 槽位上——直接复用它当前的槽位，不分配。
     *
     * <p><b>新分配</b>：只有 CPU 侧的瓦片才走空槽扫描。扫描从上次
     * 停下的位置继续，线性推进，不做回绕。
     *
     * <p>返回的 {@code slotMap} 用于换出（槽位 → TileRef）；同时按
     * {@code visible} 的顺序把槽位追加到 {@code slotsOut}——下标 i 的
     * 槽位就是 {@code visible[i]} 的槽位。
     *
     * <p><b>线程契约</b>：纯读探查。规划线程与 GL 线程并行，atlas
     * 池由 GL 线程独占，此处只读。
     */
    private static Map<AtlasSlot, TileRef> allocateSlots(
            List<VisibleTile> visible, GLAtlas[] atlases,
            List<AtlasSlot> slotsOut) {

        Map<AtlasSlot, TileRef> map = new LinkedHashMap<>();
        int atlasIdx = 0;
        int slotIdx  = 0;
        int placed   = 0;
        int reused   = 0;

        for (VisibleTile vt : visible) {
            // ── 1. 已在 GPU：复用当前槽位 ──
            GLTileData existing = vt.tile.queryData(GLTileData.class);
            if (existing != null) {
                AtlasSlot slot = slotOf(existing, atlases, vt);
                map.put(slot, new TileRef(vt.canvas, vt.tile));
                slotsOut.add(slot);
                reused++;
                placed++;
                continue;
            }

            // ── 2. CPU 侧瓦片：分配空槽位 ──
            AtlasSlot slot = null;
            while (slot == null) {
                if (atlasIdx >= atlases.length) {
                    throw new IllegalStateException(
                            "atlas capacity exceeded: need=" + visible.size()
                                    + ", placed=" + placed
                                    + ", reused=" + reused
                                    + ", atlases=" + atlases.length
                                    + ", stuck tile=" + vt.tile
                                    + ", canvas=" + vt.canvas);
                }
                GLAtlas atlas = atlases[atlasIdx];
                if (atlas == null) { atlasIdx++; slotIdx = 0; continue; }

                GLTiledTextureLayout layout = atlas.getLayout();
                int capacity = layout.getCapacity();
                int perLayer = layout.getTilesPerLayer();
                int perEdge  = layout.getTilesPerEdge();

                while (slotIdx < capacity) {
                    int idx   = slotIdx++;
                    int layer = idx / perLayer;
                    int local = idx % perLayer;
                    int dataColumn   = local % perEdge;
                    int dataRow   = local / perEdge;
                    if (atlas.isOccupiedAt(layer, dataColumn, dataRow)) continue;
                    slot = new AtlasSlot(atlasIdx, layer, dataRow, dataColumn);
                    break;
                }
                if (slot == null) { atlasIdx++; slotIdx = 0; }
            }
            map.put(slot, new TileRef(vt.canvas, vt.tile));
            slotsOut.add(slot);
            placed++;
        }

        return map;
    }

    /**
     * 把瓦片当前的 {@link GLTileData} 转成规划侧的 {@link AtlasSlot}。
     *
     * <p>atlas 实例须与 page 快照里的某个下标身份相同——否则说明
     * 瓦片挂在池外的 atlas 上，规划无法处理。
     */
    private static AtlasSlot slotOf(GLTileData data, GLAtlas[] atlases,
                                    VisibleTile vt) {
        GLAtlas owner = data.getAtlas();
        int ownerIdx = -1;
        for (int i = 0; i < atlases.length; i++) {
            if (atlases[i] == owner) { ownerIdx = i; break; }
        }
        if (ownerIdx < 0) {
            throw new IllegalStateException(
                    "tile is bound to an atlas outside this page: "
                            + owner +": " + vt.tile);
        }
        return new AtlasSlot(
                ownerIdx,
                data.getLayer(),
                data.getDataRow(),
                data.getDataColumn());
    }

    // ═══════════════════════════════════════════════
    // 打包 buffer
    // ═══════════════════════════════════════════════
    private static PackedBuffers packBuffers(
            VisibleSet visibleSet,
            List<AtlasSlot> slots,
            GLAtlas[] atlases,
            ViewportGrid grid) {

        List<VisibleTile> visible = visibleSet.visible;
        List<float[]> layerEntries = visibleSet.layerTable;
        Map<Long, List<Integer>> buckets = visibleSet.buckets;

        int n = visible.size();
        int screenTileCount = grid.getViewTileCount();

        // ── layerTable ──
        float[] layerTable = new float[layerEntries.size() * 8];
        for (int i = 0; i < layerEntries.size(); i++) {
            System.arraycopy(layerEntries.get(i), 0,
                    layerTable, i * 8, 8);
        }

        // ── tileTable ──
        float[] tileTable = new float[n * 8];
        for (int i = 0; i < n; i++) {
            VisibleTile vt = visible.get(i);
            Tile tile = vt.tile;
            int tx = tile.tx();
            int ty = tile.ty();
            AtlasSlot slot = slots.get(i);
            GLTiledTextureLayout layout =
                    atlases[slot.getAtlasIndex()].getLayout();
            int edge = layout.getTilesPerEdge();
            float u0 = (float) slot.getDataColumn() / edge;
            float v0 = (float) slot.getDataRow() / edge;

            int base = i * 8;
            tileTable[base]     = u0;
            tileTable[base + 1] = v0;
            tileTable[base + 2] = slot.getAtlasIndex();   // unit
            tileTable[base + 3] = slot.getLayer();        // texLayer
            tileTable[base + 4] = tx;                     // tileX
            tileTable[base + 5] = ty;                     // tileY
            tileTable[base + 6] = vt.layerTableIndex;     // layerEntry
            tileTable[base + 7] = 0f;                     // pad
        }

        // ══════════════════════════════════════════════
        // 日志 A：layerTable 和 tileTable 内容
        // ══════════════════════════════════════════════
        StringBuilder sb = new StringBuilder();
        sb.append("[pack] n=").append(n)
                .append(" screenTiles=").append(screenTileCount)
                .append(" layerEntries=").append(layerEntries.size())
                .append(" buckets=").append(buckets.size())
                .append('\n');

        sb.append("[pack] layerTable:\n");
        for (int i = 0; i < layerEntries.size(); i++) {
            float[] le = layerEntries.get(i);
            sb.append("  [").append(i).append("] ")
                    .append("invA=").append(le[0])
                    .append(" invB=").append(le[1])
                    .append(" invC=").append(le[2])
                    .append(" invD=").append(le[3])
                    .append(" invTx=").append(le[4])
                    .append(" invTy=").append(le[5])
                    .append(" alpha=").append(le[6])
                    .append('\n');
        }

        sb.append("[pack] tileTable (每条 8 float):\n");
        for (int i = 0; i < n; i++) {
            int base = i * 8;
            sb.append("  [").append(i).append("] ")
                    .append("u0=").append(tileTable[base])
                    .append(" v0=").append(tileTable[base + 1])
                    .append(" unit=").append((int) tileTable[base + 2])
                    .append(" texLayer=").append((int) tileTable[base + 3])
                    .append(" tileX=").append((int) tileTable[base + 4])
                    .append(" tileY=").append((int) tileTable[base + 5])
                    .append(" layerEntry=").append((int) tileTable[base + 6])
                    .append('\n');
        }

        // ══════════════════════════════════════════════
        // 日志 B：grid 坐标 + 每格 count
        // ══════════════════════════════════════════════
        int[] gridTileXY = grid.getTileXY();
        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];

        int tileCount = 0;

        sb.append("[pack] grid + counts:\n");
        for (int i = 0; i < screenTileCount; i++) {
            int tileX = gridTileXY[i * 2];
            int tileY = gridTileXY[i * 2 + 1];
            List<Integer> b = buckets.get(TiledCanvas.pack(tileX, tileY));
            int sz = b == null ? 0 : b.size();
            counts[i] = sz;
            tileCount += sz;

            sb.append("  [").append(i).append("]")
                    .append(" (").append(tileX).append(",").append(tileY).append(")")
                    .append(" count=").append(sz)
                    .append(" bucketKey=").append(TiledCanvas.pack(tileX, tileY))
                    .append(" hit=").append(b != null)
                    .append('\n');
        }
        sb.append("[pack] tileCount=").append(tileCount).append('\n');

        // ══════════════════════════════════════════════
        // 展平 indexList
        // ══════════════════════════════════════════════
        int[] indexList = new int[tileCount];
        int cursor = 0;
        for (int i = 0; i < screenTileCount; i++) {
            offsets[i] = cursor;
            int sx = gridTileXY[i * 2];
            int sy = gridTileXY[i * 2 + 1];
            List<Integer> bucket = buckets.get(TiledCanvas.pack(sx, sy));
            if (bucket != null) {
                for (int idx : bucket) {
                    indexList[cursor++] = idx;
                }
            }
        }

        // ══════════════════════════════════════════════
        // 日志 C：offsets + indexList 内容
        // ══════════════════════════════════════════════
        sb.append("[pack] offsets:\n");
        for (int i = 0; i < screenTileCount; i++) {
            sb.append("  [").append(i).append("] offset=").append(offsets[i])
                    .append(" count=").append(counts[i]).append('\n');
        }

        sb.append("[pack] indexList (").append(indexList.length).append("): ");
        for (int i = 0; i < indexList.length; i++) {
            sb.append(indexList[i]);
            if (i < indexList.length - 1) sb.append(' ');
        }
        sb.append('\n');

        // ══════════════════════════════════════════════
        // 日志 D：buckets 原始内容——键与值
        // ══════════════════════════════════════════════
        sb.append("[pack] buckets raw (key -> list):\n");
        for (Map.Entry<Long, List<Integer>> e : buckets.entrySet()) {
            long k = e.getKey();
            sb.append("  key=").append(k)
                    .append(" (").append(TiledCanvas.unpackTx(k))
                    .append(",").append(TiledCanvas.unpackTy(k)).append(")")
                    .append(" -> ").append(e.getValue())
                    .append('\n');
        }

        System.out.println(sb);

        return new PackedBuffers(
                layerTable, layerEntries.size(),
                tileTable, n,
                offsets, counts,
                indexList, tileCount);
    }

    // ═══════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════

    /** 可见瓦片收集结果：列表 + 图层表 + 屏幕分桶。 */
    private static final class VisibleSet {
        final List<VisibleTile>        visible;
        final List<float[]>            layerTable;
        final Map<Long, List<Integer>> buckets;

        VisibleSet(List<VisibleTile> visible,
                   List<float[]> layerTable,
                   Map<Long, List<Integer>> buckets) {
            this.visible    = visible;
            this.layerTable = layerTable;
            this.buckets    = buckets;
        }
    }

    /**
     * 可见瓦片的去重键——同一图层对象同一瓦片 key 只记一次。
     *
     * <p>{@code layer} 按引用相等（{@code ==}）——与收集时
     * {@link IdentityHashMap} 的语义一致。
     */
    private static final class VisibleKey {
        final ILayer layer;
        final long   tileKey;

        VisibleKey(ILayer layer, long tileKey) {
            this.layer   = layer;
            this.tileKey = tileKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof VisibleKey)) return false;
            VisibleKey that = (VisibleKey) o;
            return tileKey == that.tileKey && layer == that.layer;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(layer) + Long.hashCode(tileKey);
        }
    }

    private static final class VisibleTile {
        final TiledCanvas canvas;
        final Tile        tile;
        final int         layerTableIndex;

        VisibleTile(TiledCanvas canvas, Tile tile,
                    int layerTableIndex) {
            this.canvas          = canvas;
            this.tile            = tile;
            this.layerTableIndex = layerTableIndex;
        }
    }

    private static final class PackedBuffers {
        final float[] layerTable;
        final int     layerEntryCount;
        final float[] tileTable;
        final int     tileEntryCount;
        final int[]   offsets;
        final int[]   counts;
        final int[]   indexList;
        final int     indexCount;

        PackedBuffers(float[] layerTable, int layerEntryCount,
                      float[] tileTable, int tileEntryCount,
                      int[] offsets, int[] counts,
                      int[] indexList, int indexCount) {
            this.layerTable      = layerTable;
            this.layerEntryCount = layerEntryCount;
            this.tileTable       = tileTable;
            this.tileEntryCount  = tileEntryCount;
            this.offsets         = offsets;
            this.counts          = counts;
            this.indexList       = indexList;
            this.indexCount      = indexCount;
        }

        @Override
        public String toString() {
            int nonEmpty = 0;
            int maxCount = 0;
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] > 0) {
                    nonEmpty++;
                    if (head.length() < 80) {
                        head.append(i).append(':').append(counts[i]).append(' ');
                    }
                }
                if (counts[i] > maxCount) maxCount = counts[i];
            }

            return "PackedBuffers{"
                    + "screenTiles=" + counts.length
                    + ", nonEmpty=" + nonEmpty
                    + ", maxCount=" + maxCount
                    + ", totalIndices=" + indexCount
                    + ", tileEntries=" + tileEntryCount
                    + ", layerEntries=" + layerEntryCount
                    + ", firstCounts=[" + head.toString().trim() + "]"
                    + "}";
        }
    }
}