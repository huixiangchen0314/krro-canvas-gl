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
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayout;
import top.kzre.krro.canvas.gl.tile.TileRef;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.*;

/**
 * 合成规划器。
 *
 * <p><b>输入</b>：图层列表、上下文、ping-pong FBO、脏屏幕瓦片集合。
 *
 * <p><b>输出</b>：{@link CompositeRequest}。{@code dirtyTiles} 为空
 * 时返回 {@code null}——调用方跳过本次合成。
 *
 * <p><b>规划阶段不接触 GL</b>。所有分配、打包都是纯 CPU 计算，
 * 由规划线程执行，与 GL 线程的渲染并行。
 */
public final class CompositePlanner {

    private static final int TILE_TABLE_UNIT  = 16;
    private static final int INDEX_TABLE_UNIT = 17;

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
        if (dirtyTiles.isEmpty()) {
            return null;
        }

        AtlasPoolPage page = context.getAtlasPool().page();

        // ── 构建只含脏屏幕瓦片的 grid ──
        int[] tileXY = new int[dirtyTiles.size() * 2];
        int cursor = 0;
        for (Long key : dirtyTiles) {
            tileXY[cursor++] = TiledCanvas.unpackTx(key);
            tileXY[cursor++] = TiledCanvas.unpackTy(key);
        }
        ViewportGrid grid = ViewportGrid.createPartial(
                viewWidth, viewHeight, tileSize, tileXY);

        // ── 收集覆盖到脏屏幕瓦片的图层瓦片 ──
        List<VisibleTile> visible = collectVisibleTiles(layers, dirtyTiles, tileSize);
        if (visible.isEmpty()) {
            return null;
        }

        // ── 分配槽位 ──
        GLAtlas[] atlases = page.getAtlases();
        Map<AtlasSlot, TileRef> slotMap = allocateSlots(visible, atlases);

        // ── 打包 buffer ──
        PackedBuffers packed = packBuffers(
                visible, slotMap, atlases, grid, tileSize);

        // ── 组装 group / shader / bundle ──
        TileBufferGroup group = new TileBufferGroup(
                TILE_TABLE_UNIT,
                INDEX_TABLE_UNIT,
                packed.offsets,
                packed.counts,
                packed.tileEntries,
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

        return CompositeRequest.of(page, grid, Collections.singletonList(bundle), fboA, fboB);
    }

    // ═══════════════════════════════════════════════
    // 收集可见瓦片
    // ═══════════════════════════════════════════════

    /**
     * 收集图层瓦片——只保留 AABB 覆盖到 {@code dirtyTiles} 中某个
     * 屏幕瓦片的。
     */
    private static List<VisibleTile> collectVisibleTiles(
            List<ILayer> layers, Set<Long> dirtyTiles, int tileSize) {

        List<VisibleTile> result = new ArrayList<>();

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

                result.add(new VisibleTile(
                        canvas, tile, tx, ty, mat, alpha, aabb));
            }
        }
        return result;
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

    /**
     * 图层瓦片在视口空间的 AABB。用四角变换取包围盒。
     */
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

        return new float[]{
                Math.min(Math.min(x00, x10), Math.min(x01, x11)),
                Math.min(Math.min(y00, y10), Math.min(y01, y11)),
                Math.max(Math.max(x00, x10), Math.max(x01, x11)),
                Math.max(Math.max(y00, y10), Math.max(y01, y11)),
        };
    }

    // ═══════════════════════════════════════════════
    // 分配槽位
    // ═══════════════════════════════════════════════

    /**
     * 为每块可见瓦片找一个空闲 atlas 槽位。
     *
     * <p>线性扫描，跳过已占用。规划线程和 GL 线程并行，锁竞争不
     * 存在——atlas 池由 GL 线程独占，此处只读探查。
     */
    private static Map<AtlasSlot, TileRef> allocateSlots(
            List<VisibleTile> visible, GLAtlas[] atlases) {

        Map<AtlasSlot, TileRef> map = new LinkedHashMap<>();
        int atlasIdx = 0;
        int slotIdx  = 0;

        for (VisibleTile vt : visible) {
            AtlasSlot slot = null;
            while (slot == null) {
                if (atlasIdx >= atlases.length) {
                    throw new IllegalStateException("atlas capacity exceeded");
                }
                GLAtlas atlas = atlases[atlasIdx];
                if (atlas == null) {
                    atlasIdx++;
                    slotIdx = 0;
                    continue;
                }
                GLTiledTextureLayout layout = atlas.getLayout();
                int capacity = layout.getCapacity();

                while (slotIdx < capacity) {
                    int idx = slotIdx++;
                    int layer = idx / layout.getTilesPerLayer();
                    int local = idx % layout.getTilesPerLayer();
                    int col   = local % layout.getTilesPerEdge();
                    int row   = local / layout.getTilesPerEdge();

                    if (atlas.isOccupiedAt(layer, col, row)) continue;
                    slot = new AtlasSlot(atlasIdx, layer, row, col);
                    break;
                }
                if (slot == null) {
                    atlasIdx++;
                    slotIdx = 0;
                }
            }
            map.put(slot, new TileRef(vt.canvas, vt.tile));
        }
        return map;
    }

    // ═══════════════════════════════════════════════
    // 打包 buffer
    // ═══════════════════════════════════════════════

    private static PackedBuffers packBuffers(
            List<VisibleTile> visible,
            Map<AtlasSlot, TileRef> slotMap,
            GLAtlas[] atlases,
            ViewportGrid grid,
            int tileSize) {

        int n = visible.size();
        int screenTileCount = grid.getScreenTileCount();

        // tile → slot 反查
        Map<Tile, AtlasSlot> tileToSlot = new IdentityHashMap<>();
        for (Map.Entry<AtlasSlot, TileRef> e : slotMap.entrySet()) {
            tileToSlot.put(e.getValue().getTile(), e.getKey());
        }

        // ── tileEntries：12 float / 条目 ──
        float[] tileEntries = new float[n * 12];
        for (int i = 0; i < n; i++) {
            VisibleTile vt = visible.get(i);
            AtlasSlot slot = tileToSlot.get(vt.tile);
            if (slot == null) {
                throw new IllegalStateException("tile without slot");
            }
            GLTiledTextureLayout layout =
                    atlases[slot.getAtlasIndex()].getLayout();
            int edge = layout.getTilesPerEdge();
            float u0 = (float) slot.getCol() / edge;
            float v0 = (float) slot.getRow() / edge;

            int base = i * 12;
            tileEntries[base]      = vt.mat[0];
            tileEntries[base + 1]  = vt.mat[1];
            tileEntries[base + 2]  = vt.mat[2];
            tileEntries[base + 3]  = vt.mat[3];
            tileEntries[base + 4]  = vt.mat[4];
            tileEntries[base + 5]  = vt.mat[5];
            tileEntries[base + 6]  = u0;
            tileEntries[base + 7]  = v0;
            tileEntries[base + 8]  = slot.getAtlasIndex();
            tileEntries[base + 9]  = slot.getLayer();
            tileEntries[base + 10] = vt.alpha;
            tileEntries[base + 11] = 0f;
        }

        // ── 屏幕瓦片坐标 → grid 内下标 ──
        int[] gridTileXY = grid.getTileXY();
        Map<Long, Integer> screenIndex = new HashMap<>(screenTileCount * 2);
        for (int i = 0; i < screenTileCount; i++) {
            int sx = gridTileXY[i * 2];
            int sy = gridTileXY[i * 2 + 1];
            screenIndex.put(TiledCanvas.pack(sx, sy), i);
        }

        // ── 分桶：每块图层瓦片按 AABB 覆盖的屏幕瓦片入桶 ──
        List<List<Integer>> buckets = new ArrayList<>(screenTileCount);
        for (int i = 0; i < screenTileCount; i++) {
            buckets.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++) {
            VisibleTile vt = visible.get(i);
            int tx0 = (int) Math.floor(vt.aabb[0] / tileSize);
            int ty0 = (int) Math.floor(vt.aabb[1] / tileSize);
            int tx1 = (int) Math.floor((vt.aabb[2] - 0.001f) / tileSize);
            int ty1 = (int) Math.floor((vt.aabb[3] - 0.001f) / tileSize);

            for (int ty = ty0; ty <= ty1; ty++) {
                for (int tx = tx0; tx <= tx1; tx++) {
                    Integer idx = screenIndex.get(TiledCanvas.pack(tx, ty));
                    if (idx != null) {
                        buckets.get(idx).add(i);
                    }
                }
            }
        }

        // ── offsets / counts / indexList ──
        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];
        List<Integer> flat = new ArrayList<>();

        for (int i = 0; i < screenTileCount; i++) {
            List<Integer> bucket = buckets.get(i);
            offsets[i] = flat.size();
            counts[i]  = bucket.size();
            flat.addAll(bucket);
        }
        int[] indexList = new int[flat.size()];
        for (int i = 0; i < indexList.length; i++) {
            indexList[i] = flat.get(i);
        }

        return new PackedBuffers(
                tileEntries, n, offsets, counts, indexList, indexList.length);
    }

    // ═══════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════

    /** 视口裁剪后的可见图层瓦片记录。 */
    private static final class VisibleTile {
        final TiledCanvas canvas;
        final Tile        tile;
        final int         tx, ty;
        final float[]     mat;
        final float       alpha;
        final float[]     aabb;

        VisibleTile(TiledCanvas canvas, Tile tile, int tx, int ty,
                    float[] mat, float alpha, float[] aabb) {
            this.canvas = canvas;
            this.tile   = tile;
            this.tx     = tx;
            this.ty     = ty;
            this.mat    = mat;
            this.alpha  = alpha;
            this.aabb   = aabb;
        }
    }

    /** 打包后的 buffer 数据。 */
    private static final class PackedBuffers {
        final float[] tileEntries;
        final int     tileEntryCount;
        final int[]   offsets;
        final int[]   counts;
        final int[]   indexList;
        final int     indexCount;

        PackedBuffers(float[] tileEntries, int tileEntryCount,
                      int[] offsets, int[] counts,
                      int[] indexList, int indexCount) {
            this.tileEntries    = tileEntries;
            this.tileEntryCount = tileEntryCount;
            this.offsets        = offsets;
            this.counts         = counts;
            this.indexList      = indexList;
            this.indexCount     = indexCount;
        }
    }
}