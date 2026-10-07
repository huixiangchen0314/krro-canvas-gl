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

        ViewportGrid grid = buildViewGrid(
                dirtyList, viewWidth, viewHeight, tileSize);

        VisibleSet visibleSet = collectVisibleTiles(layers, dirtyList, tileSize, grid);
        if (visibleSet.visible.isEmpty()) {
            return null;
        }

        GLAtlas[] atlases = page.getAtlases();
        List<AtlasSlot> slots = new ArrayList<>(visibleSet.visible.size());
        Map<AtlasSlot, TileRef> slotMap =
                allocateSlots(visibleSet.visible, atlases, slots);


        PackedBuffers packed = packBuffers(visibleSet, slots, atlases, grid);

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

    private static VisibleSet collectVisibleTiles(
            List<ILayer> layers, List<Long> dirtyTiles, int tileSize,
            ViewportGrid view) {

        Map<ILayer, Integer> layerEntryMap = new IdentityHashMap<>();
        Map<VisibleKey, Integer> visibleIndex = new HashMap<>();

        List<VisibleTile> visible = new ArrayList<>();
        List<float[]> layerTable = new ArrayList<>();
        Map<Long, List<Integer>> buckets = new HashMap<>();
        int tilesPerColumn = view.getViewHeight() / tileSize;
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
                                    inv[0], inv[1], inv[2], inv[3],
                                    inv[4], inv[5], alpha, 0f
                            });
                        }

                        idx = visible.size();
                        int glTileOriginX = tx * tileSize;
                        int glTileOriginY = ty * tileSize;
                        visible.add(new VisibleTile(canvas, tile, lei, glTileOriginX, glTileOriginY));
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

    private static Map<AtlasSlot, TileRef> allocateSlots(
            List<VisibleTile> visible, GLAtlas[] atlases,
            List<AtlasSlot> slotsOut) {

        Map<AtlasSlot, TileRef> map = new LinkedHashMap<>();
        int atlasIdx = 0;
        int slotIdx  = 0;
        int placed   = 0;
        int reused   = 0;

        for (VisibleTile vt : visible) {
            GLTileData existing = vt.tile.queryData(GLTileData.class);
            if (existing != null) {
                AtlasSlot slot = slotOf(existing, atlases, vt);
                map.put(slot, new TileRef(vt.canvas, vt.tile));
                slotsOut.add(slot);
                reused++;
                placed++;
                continue;
            }

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

            AtlasSlot slot = slots.get(i);
            GLTiledTextureLayout layout =
                    atlases[slot.getAtlasIndex()].getLayout();
            int edge = layout.getTilesPerEdge();
            float u0 = (float) slot.getDataColumn() / edge;
            float v0 = (float) slot.getDataRow() / edge;

            int base = i * 8;
            tileTable[base]     = u0;
            tileTable[base + 1] = v0;
            tileTable[base + 2] = slot.getAtlasIndex();
            tileTable[base + 3] = slot.getLayer();
            tileTable[base + 4] = vt.glTileOriginX;
            tileTable[base + 5] = vt.glTileOriginY;
            tileTable[base + 6] = vt.layerTableIndex;
            tileTable[base + 7] = 0f;
        }

        // ── grid + counts + indexList ──
        int[] gridTileXY = grid.getTileXY();
        int[] offsets = new int[screenTileCount];
        int[] counts  = new int[screenTileCount];

        int tileCount = 0;
        for (int i = 0; i < screenTileCount; i++) {
            int tileX = gridTileXY[i * 2];
            int tileY = gridTileXY[i * 2 + 1];
            List<Integer> b = buckets.get(TiledCanvas.pack(tileX, tileY));
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
            List<Integer> bucket = buckets.get(TiledCanvas.pack(sx, sy));
            if (bucket != null) {
                for (int idx : bucket) {
                    indexList[cursor++] = idx;
                }
            }
        }

        return new PackedBuffers(
                layerTable, layerEntries.size(),
                tileTable, n,
                offsets, counts,
                indexList, tileCount);
    }

    // ═══════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════

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
        final int glTileOriginX;
        final int glTileOriginY;
        VisibleTile(TiledCanvas canvas, Tile tile,
                    int layerTableIndex, int glTileOriginX, int glTileOriginY) {
            this.canvas          = canvas;
            this.tile            = tile;
            this.layerTableIndex = layerTableIndex;
            this.glTileOriginX   = glTileOriginX;
            this.glTileOriginY   = glTileOriginY;
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