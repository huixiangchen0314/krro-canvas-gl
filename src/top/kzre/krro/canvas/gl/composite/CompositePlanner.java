package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
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

import java.util.*;

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
 * <h2>槽位复用</h2>
 *
 * <p>可见瓦片若已是 GPU 形态（{@link GLTileData}），规划阶段直接
 * 复用它当前所在的槽位，不再分配新槽。只有 CPU 侧的瓦片才走
 * {@code allocateSlots} 的空槽扫描。这样 atlas 占用稳定在"当前
 * 在 GPU 上的瓦片数"，不会随帧数单调增长。
 */
public final class CompositePlanner {

    private CompositePlanner() {}

    // ═══════════════════════════════════════════════
    // 主流程
    // ═══════════════════════════════════════════════

    public static CompositeRequest plan(
            List<ILayer> layers,
            GLCompositeContext context,
            GLProgramCache programs,
            GLFramebuffer fboA,
            GLFramebuffer fboB,
            Set<Long> dirtyTiles) {

        int viewWidth  = context.getViewWidth();
        int viewHeight = context.getViewHeight();
        int tileSize   = context.getTileSize();


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

        AtlasPoolPage page = context.getAtlasPool().page();

        // ── 构建只含脏屏幕瓦片的 grid ──
        ViewportGrid grid = buildViewGrid(dirtyTiles, viewWidth, viewHeight, tileSize);

        // ── 收集可见瓦片 + 构建图层表 ──
        List<VisibleTile> visibleTiles = new ArrayList<>();
        List<float[]> layerTable = new ArrayList<>();
        collectVisibleTiles(layers, dirtyTiles, tileSize,
                visibleTiles, layerTable);
        if (visibleTiles.isEmpty()) {
            return null;
        }

        // ── 分配槽位（下标与 visibleTiles 一一对应） ──
        GLAtlas[] atlases = page.getAtlases();
        List<AtlasSlot> slots = new ArrayList<>(visibleTiles.size());
        Map<AtlasSlot, TileRef> slotMap = allocateSlots(visibleTiles, atlases, slots);

        // ── 打包 buffer ──
        PackedBuffers packed = packBuffers(
                visibleTiles, slots, layerTable, atlases, grid, tileSize);

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

    private static ViewportGrid buildViewGrid(Set<Long> dirtyTiles, int viewWidth, int viewHeight, int tileSize) {
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
    // 收集可见瓦片 + 构建图层表
    // ═══════════════════════════════════════════════

    private static void collectVisibleTiles(
            List<ILayer> layers, Set<Long> dirtyTiles, int tileSize,
            List<VisibleTile> visibleOut, List<float[]> layerEntriesOut) {

        Map<ILayer, Integer> layerEntryMap = new IdentityHashMap<>();

        for (ILayer layer : layers) {
            if (!layer.isVisible()) continue;
            TiledCanvas canvas = layer.getCanvas();
            if (canvas == null) continue;

            float alpha = layer.getOpacity();
            float[] mat = layer.getTransform();

            for (long key : canvas.getTiles()) {
                int tx = TiledCanvas.unpackTx(key);
                int ty = TiledCanvas.unpackTy(key);
                Tile tile = canvas.getTile(tx, ty);
                if (tile == null) continue;

                float[] aabb = tileAabb(mat, tx, ty, tileSize);
                if (!coversDirty(aabb, tileSize, dirtyTiles)) continue;

                Integer layerTableIndex = layerEntryMap.get(layer);
                if (layerTableIndex == null) {
                    layerTableIndex = layerEntriesOut.size();
                    layerEntryMap.put(layer, layerTableIndex);
                    layerEntriesOut.add(computeLayerEntry(mat, alpha));
                }

                visibleOut.add(new VisibleTile(
                        canvas, tile, tx, ty, layerTableIndex, aabb));
            }
        }
    }

    private static float[] computeLayerEntry(float[] mat, float alpha) {
        float a = mat[0], b = mat[1], c = mat[2], d = mat[3];
        float tx = mat[4], ty = mat[5];

        float det = a * d - b * c;
        if (KMath.isNearZero(det)) {
            throw new IllegalStateException(
                    "singular layer transform (det ≈ 0)");
        }
        float invDet = 1f / det;

        float invA =  d * invDet;
        float invB = -b * invDet;
        float invC = -c * invDet;
        float invD =  a * invDet;
        float invTx = -(invA * tx + invC * ty);
        float invTy = -(invB * tx + invD * ty);

        return new float[]{ invA, invB, invC, invD, invTx, invTy, alpha, 0f };
    }

    private static boolean coversDirty(float[] aabb, int tileSize,
                                       Set<Long> dirtyTiles) {
        int tx0 = (int) Math.floor(aabb[0] / tileSize);
        int ty0 = (int) Math.floor(aabb[1] / tileSize);
        int tx1 = (int) Math.floor((aabb[2] - 0.001f) / tileSize);
        int ty1 = (int) Math.floor((aabb[3] - 0.001f) / tileSize);

        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                if (dirtyTiles.contains(TiledCanvas.pack(tx, ty))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static float[] tileAabb(float[] m, int tx, int ty, int tileSize) {
        float a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5];
        float x0 = tx * tileSize, y0 = ty * tileSize;
        float x1 = x0 + tileSize,  y1 = y0 + tileSize;

        float x00 = a * x0 + c * y0 + e;
        float y00 = b * x0 + d * y0 + f;
        float x10 = a * x1 + c * y0 + e;
        float y10 = b * x1 + d * y0 + f;
        float x01 = a * x0 + c * y1 + e;
        float y01 = b * x0 + d * y1 + f;
        float x11 = a * x1 + c * y1 + e;
        float y11 = b * x1 + d * y1 + f;

        float minX = KMath.min(x00, x10);
        minX = KMath.min(minX, x01);
        minX = KMath.min(minX, x11);

        float minY = KMath.min(y00, y10);
        minY = KMath.min(minY, y01);
        minY = KMath.min(minY, y11);

        float maxX = KMath.max(x00, x10);
        maxX = KMath.max(maxX, x01);
        maxX = KMath.max(maxX, x11);

        float maxY = KMath.max(y00, y10);
        maxY = KMath.max(maxY, y01);
        maxY = KMath.max(maxY, y11);

        return new float[]{ minX, minY, maxX, maxY };
    }

    // ═══════════════════════════════════════════════
    // 分配槽位
    // ═══════════════════════════════════════════════

    /**
     * 为每块可见瓦片决定槽位。
     *
     * <p><b>复用优先</b>：瓦片若已是 {@link GLTileData} 形态——说明
     * 它已经待在某个 atlas 槽位上——直接复用它当前的槽位，不分配。
     * 这条路径是稳态下的常规路径：每一帧重复合成的可见瓦片都会
     * 命中复用。
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
            System.out.println("Beore query, Tile: " + vt.tile);
            // ── 1. 已在 GPU：复用当前槽位 ──
            GLTileData existing = vt.tile.queryData(GLTileData.class);
            if (existing != null) {
                System.out.println("Tiles already in GPU: " + existing);
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
                                    + ", stuck tx=" + vt.tx + " ty=" + vt.ty
                                    + ", canvas=" + vt.canvas);
                }
                GLAtlas atlas = atlases[atlasIdx];
                System.out.println("Atlas: " + atlas);
                if (atlas == null) { atlasIdx++; slotIdx = 0; continue; }

                GLTiledTextureLayout layout = atlas.getLayout();
                int capacity = layout.getCapacity();
                int perLayer = layout.getTilesPerLayer();
                int perEdge  = layout.getTilesPerEdge();

                while (slotIdx < capacity) {
                    int idx   = slotIdx++;
                    int layer = idx / perLayer;
                    int local = idx % perLayer;
                    int col   = local % perEdge;
                    int row   = local / perEdge;
                    if (atlas.isOccupiedAt(layer, col, row)) continue;
                    slot = new AtlasSlot(atlasIdx, layer, row, col);
                    break;
                }
                if (slot == null) { atlasIdx++; slotIdx = 0; }
            }
            map.put(slot, new TileRef(vt.canvas, vt.tile));
            slotsOut.add(slot);
            placed++;
        }

        if (reused > 0 || placed - reused > 0) {
            System.out.println("[allocateSlots] visible=" + visible.size()
                    + ", reused=" + reused
                    + ", new=" + (placed - reused));
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
                            + owner + " (tx=" + vt.tx + ", ty=" + vt.ty + ")");
        }
        return new AtlasSlot(
                ownerIdx,
                data.getLayer(),
                data.getRow(),
                data.getCol());
    }

    // ═══════════════════════════════════════════════
    // 打包 buffer
    // ═══════════════════════════════════════════════

    private static PackedBuffers packBuffers(
            List<VisibleTile> visible,
            List<AtlasSlot> slots,
            List<float[]> layerEntries,
            GLAtlas[] atlases,
            ViewportGrid grid,
            int tileSize) {

        int n = visible.size();
        int screenTileCount = grid.getViewTileCount();

        float[] layerTable = new float[layerEntries.size() * 8];
        for (int i = 0; i < layerEntries.size(); i++) {
            System.arraycopy(layerEntries.get(i), 0,
                    layerTable, i * 8, 8);
        }

        float[] tileTable = new float[n * 8];
        for (int i = 0; i < n; i++) {
            VisibleTile vt = visible.get(i);
            AtlasSlot slot = slots.get(i);
            GLTiledTextureLayout layout =
                    atlases[slot.getAtlasIndex()].getLayout();
            int edge = layout.getTilesPerEdge();
            float u0 = (float) slot.getCol() / edge;
            float v0 = (float) slot.getRow() / edge;

            int base = i * 8;
            tileTable[base]     = u0;
            tileTable[base + 1] = v0;
            tileTable[base + 2] = slot.getAtlasIndex();   // unit
            tileTable[base + 3] = slot.getLayer();        // texLayer
            tileTable[base + 4] = vt.tx;                  // tileX
            tileTable[base + 5] = vt.ty;                  // tileY
            tileTable[base + 6] = vt.layerTableIndex;          // layerEntry
            tileTable[base + 7] = 0f;                     // pad
        }

        int[] gridTileXY = grid.getTileXY();
        Map<Long, Integer> screenIndex = new HashMap<>(screenTileCount * 2);
        for (int i = 0; i < screenTileCount; i++) {
            int sx = gridTileXY[i * 2];
            int sy = gridTileXY[i * 2 + 1];
            screenIndex.put(TiledCanvas.pack(sx, sy), i);
        }

        List<List<Integer>> buckets = new ArrayList<>(screenTileCount);
        for (int i = 0; i < screenTileCount; i++) {
            buckets.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++) {
            VisibleTile vt = visible.get(i);
            float[] aabb = vt.aabb;

            int tx0 = (int) Math.floor(aabb[0] / tileSize);
            int ty0 = (int) Math.floor(aabb[1] / tileSize);
            int tx1 = (int) Math.floor((aabb[2] - 0.001f) / tileSize);
            int ty1 = (int) Math.floor((aabb[3] - 0.001f) / tileSize);

            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    Integer si = screenIndex.get(TiledCanvas.pack(tx, ty));
                    if (si != null) {
                        buckets.get(si).add(i);
                    }
                }
            }
        }

        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];
        int total = 0;
        for (List<Integer> bucket : buckets) total += bucket.size();

        int[] indexList = new int[total];
        int cursor = 0;
        for (int i = 0; i < screenTileCount; i++) {
            List<Integer> bucket = buckets.get(i);
            offsets[i] = cursor;
            counts[i]  = bucket.size();
            for (int idx : bucket) {
                indexList[cursor++] = idx;
            }
        }

        return new PackedBuffers(
                layerTable, layerEntries.size(),
                tileTable, n,
                offsets, counts,
                indexList, total);
    }

    // ═══════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════

    private static final class VisibleTile {
        final TiledCanvas canvas;
        final Tile        tile;
        final int         tx, ty;
        final int layerTableIndex;
        final float[]     aabb;

        VisibleTile(TiledCanvas canvas, Tile tile,
                    int tx, int ty, int layerTableIndex, float[] aabb) {
            this.canvas     = canvas;
            this.tile       = tile;
            this.tx         = tx;
            this.ty         = ty;
            this.layerTableIndex = layerTableIndex;
            this.aabb       = aabb;
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
    }
}