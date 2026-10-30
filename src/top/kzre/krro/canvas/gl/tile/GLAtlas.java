package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;
import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.util.BufferUtils;
import top.kzre.krro.canvas.gl.util.Range;
import top.kzre.krro.util.tile.Tile;
import top.kzre.krro.util.tile.TileData;
import top.kzre.krro.util.tile.TiledCanvas;
import top.kzre.krro.util.tile.VersionedTile;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GL 图集：管理一张 {@link GLTexture} 中所有瓦片槽位的分配与释放。
 *
 * <p>布局由 {@link GLTiledTextureLayout} 描述。构造时校验纹理维度
 * 与布局一致。
 *
 * <h2>索引</h2>
 * 全局瓦片索引 {@code index ∈ [0, capacity)}，编码为：
 * <pre>
 *   layer = index / tilesPerLayer
 *   local = index % tilesPerLayer
 *   col   = local % tilesPerRow
 *   row   = local / tilesPerRow
 * </pre>
 * {@code allocated} 位图与 {@code tiles[]} 映射都以全局索引为下标。
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>{@code allocate} / {@code move} / {@code release} /
 *       {@code ensureUploaded} —— GL 线程</li>
 *   <li>{@code freeTile} 可能从任意线程进入（{@link GLTileDataImpl#release()}
 *       由引用计数触发）—— 内部用 {@code lock} 保护</li>
 *   <li>{@link GLTileDataImpl#markDirty()} —— 任意线程</li>
 * </ul>
 */
public final class GLAtlas implements GLBindable, AutoCloseable {

    /**
     * 私有锁，避免外部用 {@code synchronized (atlas)} 干扰。
     */
    private final Object lock = new Object();

    private final GLTexture texture;
    private final GLTiledTextureLayout layout;
    private final GLTexturePool texPool;

    private final BitSet allocated;
    private final GLTileDataImpl[] tiles;
    private final int capacity;

    private boolean closed;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param texture 底层纹理（{@code GL_TEXTURE_2D_ARRAY}）
     * @param layout  瓦片布局
     * @throws IllegalArgumentException 纹理维度与布局不一致
     */
    public GLAtlas(GLTexture texture, GLTiledTextureLayout layout, GLTexturePool texPool) {
        this.texPool = texPool;
        if (texture == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }
        if (texture.getWidth() != layout.getLayerWidth()
                || texture.getHeight() != layout.getLayerHeight()) {
            throw new IllegalArgumentException(
                    "texture dimensions " + texture.getWidth() + "x" + texture.getHeight()
                            + " do not match layout layer size "
                            + layout.getLayerWidth() + "x" + layout.getLayerHeight());
        }
        if (texture.getLayers() != layout.getLayers()) {
            throw new IllegalArgumentException(
                    "texture layers " + texture.getLayers()
                            + " do not match layout layers " + layout.getLayers());
        }

        this.texture = texture;
        this.layout = layout;
        this.capacity = layout.getCapacity();
        this.tiles = new GLTileDataImpl[capacity];
        this.allocated = new BitSet(capacity);
        this.closed = false;
    }

    /**
     * 把 tile 从 src 移到 dst。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @return 成功返回 true；dst 已满返回 false
     */
    public static boolean move(GLAtlas dst, GLAtlas src, GLTileDataImpl tile) {
        if (dst == src) {
            throw new IllegalArgumentException("dst and src must differ");
        }
        if (tile.handle.getGLAtlas() != src) {
            throw new IllegalStateException(
                    "tile does not belong to src atlas " + src);
        }

        Handle newHandle;
        try {
            newHandle = dst.allocateHandle();
        } catch (IllegalStateException e) {
            return false;
        }

        Handle oldHandle = tile.handle;
        tile.rebind(newHandle);
        dst.registerTile(newHandle.getIndex(), tile);
        oldHandle.close();

        return true;
    }

    public static boolean inPage(Tile tile) {
        if (tile == null || !tile.isValid()) {
            return false;
        }
        GLTileData tileData = tile.queryData(GLTileData.class);
        return tileData != null && tileData.isValid();
    }

    public static void keepGLTile(TiledCanvas canvas){
        canvas.keepTiles(GLAtlas::inPage);
    }

    public static boolean exclusivePage(Tile tile) {
        if (tile == null || !tile.isValid()) {
            return false;
        }
        GLTileData tileData = tile.queryData(GLTileData.class);
        return tileData != null && tileData.isValid() && isExclusive(tileData);
    }

    public static boolean isExclusive(GLTileData tile) {
        return tile.getProxyRefCount() == 1;
    }

    /**
     * 把 {@link Tile} 上的 GPU 形态换回 CPU 形态。
     *
     * <p><b>线程契约</b>：涉及 atlas 槽位释放，必须在 GL 线程上调用。
     */
    public static boolean pageOut(Tile tile) {
        if (tile == null) return false;
        GLTileDataImpl gl = tile.queryData(GLTileDataImpl.class);
        if (gl == null) return false;
        int proxyRefCount = gl.proxyRefCount.get();
        if (proxyRefCount != 1) {
            throw new IllegalStateException(
                    "pageOut should called when tile exclusively hold tileData gl proxy");
        }
        tile.replaceData(gl.peer);
        return true;
    }

    public GLTiledTextureLayout getLayout() {
        return layout;
    }

    // ═══════════════════════════════════════════════
    // 分配
    // ═══════════════════════════════════════════════

    /**
     * 绑定底层纹理到指定纹理单元。
     *
     * <p><b>有 GL 副作用</b>——必须在 GL 线程上调用。
     * unit 由调用方（规划阶段的结果）决定，atlas 不持有 unit 状态。
     *
     * @param unit 纹理单元索引，必须 ≥ 0
     * @throws IllegalArgumentException unit &lt; 0
     */
    @Override
    public void bind(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException("unit must be >= 0, got " + unit);
        }
        this.texture.bind(unit);
    }

    // ═══════════════════════════════════════════════
    // 精确分配
    // ═══════════════════════════════════════════════

    public boolean isFull() {
        synchronized (lock) {
            return allocated.cardinality() == capacity;
        }
    }

    public boolean isEmpty() {
        synchronized (lock) {
            return allocated.isEmpty();
        }
    }

    public int getAllocatedCount() {
        synchronized (lock) {
            return allocated.cardinality();
        }
    }

    public int getFreeCount() {
        synchronized (lock) {
            return capacity - allocated.cardinality();
        }
    }

    /**
     * 分配一个瓦片槽位，<b>独占转移</b> peer 的所有权。
     *
     * <p><b>幂等</b>：若 peer 已经是绑定到本 atlas 的 {@link GLTileDataImpl}，
     * 直接返回它。
     *
     * <p><b>跨 atlas 防护</b>：若 peer 是绑定到其他 atlas 的
     * {@link GLTileDataImpl}，抛异常。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @throws IllegalStateException atlas 已满、peer 跨 atlas、或 peer 非独占
     */
    public GLTileDataImpl allocate(TileData peer) {
        if (peer instanceof GLTile) {
            throw new IllegalStateException(
                    "peer is already GPU-backed (class = "
                            + peer.getClass().getName()
                            + "); allocateAt requires a fresh CPU peer");
        }


        Handle handle = allocateHandle();
        GLTileDataImpl tile = new GLTileDataImpl(peer, handle);
        registerTile(handle.getIndex(), tile);
        return tile;
    }

    // ═══════════════════════════════════════════════
    // 索引编解码
    // ═══════════════════════════════════════════════

    /**
     * 在指定槽位分配瓦片，<b>独占转移</b> peer 的所有权。
     *
     * <p>与 {@link #allocate(TileData)} 的区别：
     * <ul>
     *   <li>{@code allocate}   —— 自动找空闲槽位</li>
     *   <li>{@code allocateAt} —— 精确指定位置，位置已被占用则抛异常</li>
     * </ul>
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @param layer      texture array 层索引
     * @param dataColumn 层内列坐标（u 方向）
     * @param dataRow    层内行坐标（v 方向）
     * @param peer       瓦片数据，独占
     * @throws IndexOutOfBoundsException 索引越界
     * @throws IllegalStateException     peer 非独占、已是 GPU 瓦片、或槽位已占用
     */
    public GLTileDataImpl allocateAt(int layer, int dataColumn, int dataRow, TileData peer) {
        if (peer instanceof GLTile) {
            throw new IllegalStateException(
                    "peer is already GPU-backed (class = "
                            + peer.getClass().getName()
                            + "); allocateAt requires a fresh CPU peer");
        }

        int index = encodeIndex(layer, dataColumn, dataRow);
        Handle handle = allocateHandleAt(index);
        GLTileDataImpl tile = new GLTileDataImpl(peer, handle);
        registerTile(index, tile);
        return tile;
    }

    /**
     * 从 startLayerIndex 开始，连续上传 count 个图层。
     *
     * <p><b>前置条件</b>：这 count 个层内的所有瓦片槽位必须已全部分配。
     *
     * <p><b>总是上传</b>：不检查瓦片脏标记，无条件把 peer 数据重新 pack
     * 并上传到 GPU。
     *
     * <p><b>一次调用</b>：内部把 count 层的数据 pack 到一个连续 staging
     * 缓冲，用一次 {@code glTexSubImage3D(zoffset, depth=count)} 覆盖整段。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @param startLayerIndex 起始层索引
     * @param count           连续层数
     * @throws IndexOutOfBoundsException 层范围越界
     * @throws IllegalArgumentException  count ≤ 0 或总字节数超 int 上限
     * @throws IllegalStateException     范围内有未分配的槽位
     */
    public void uploadLayers(int startLayerIndex, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0: " + count);
        }
        int totalLayers = layout.getLayers();
        if (startLayerIndex < 0 || startLayerIndex + count > totalLayers) {
            throw new IndexOutOfBoundsException(
                    "layer range [" + startLayerIndex + ", "
                            + (startLayerIndex + count) + ") out of [0, "
                            + totalLayers + ")");
        }

        int tilesPerLayer = layout.getTilesPerLayer();
        int tileSize = layout.getTileSize();
        int layerWidth = layout.getLayerWidth();
        int layerHeight = layout.getLayerHeight();
        int bytesPerPixel = texture.getBytesPerPixel();
        int bytesPerTile = tileSize * tileSize * bytesPerPixel;
        int rowBytes = tileSize * bytesPerPixel;
        long layerBytes = (long) layerWidth * layerHeight * bytesPerPixel;
        long totalBytes = (long) count * layerBytes;
        if (totalBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "batch too large: " + totalBytes + " bytes");
        }

        // ── 校验并快照 tile 引用（锁内）──
        GLTileDataImpl[] batch = new GLTileDataImpl[count * tilesPerLayer];
        synchronized (lock) {
            int cursor = 0;
            for (int i = 0; i < count; i++) {
                int layer = startLayerIndex + i;
                int base = layer * tilesPerLayer;
                for (int local = 0; local < tilesPerLayer; local++) {
                    GLTileDataImpl tile = tiles[base + local];
                    if (tile == null) {
                        throw new IllegalStateException(
                                "slot not allocated: layer=" + layer
                                        + ", local=" + local);
                    }
                    batch[cursor++] = tile;
                }
            }
        }

        // ── staging + 逐 tile pack ──
        PixelFormat fmt = texture.getPixelFormat();
        int pixelCount = tileSize * tileSize;
        int needFloats = PixelCodec.cpuFloats(fmt, pixelCount);

        ByteBuffer staging = MemoryUtil.memAlloc((int) totalBytes);
        ByteBuffer tileTmp = MemoryUtil.memAlloc(bytesPerTile);
        try {
            int tileIdx = 0;
            for (int i = 0; i < count; i++) {
                long layerOffset = (long) i * layerBytes;
                for (int local = 0; local < tilesPerLayer; local++) {
                    GLTileDataImpl tile = batch[tileIdx++];
                    packTileInto(tile, staging, layerOffset,
                            tileTmp, fmt, needFloats,
                            tileSize, layerWidth, bytesPerPixel, rowBytes);
                }
            }
            staging.flip();

            // ── 一次上传 count 层 ──
            texture.uploadRegion3D(
                    startLayerIndex, count,
                    0, 0, layerWidth, layerHeight,
                    staging);
        } finally {
            MemoryUtil.memFree(tileTmp);
            MemoryUtil.memFree(staging);
        }
    }

    /**
     * 查找本 atlas 中最大的一段连续全空闲层。
     *
     * <p>整层空闲 = 该层所有槽位未分配。
     *
     * @return 空闲层区间；没有全空闲层时返回 {@link Range#EMPTY}
     */
    public Range findMaxContiguousFreeLayers() {
        synchronized (lock) {
            int tilesPerLayer = layout.getTilesPerLayer();
            int layers = layout.getLayers();

            int bestStart = -1, bestCount = 0;
            int curStart = -1, curCount = 0;

            for (int layer = 0; layer < layers; layer++) {
                int base = layer * tilesPerLayer;
                boolean layerFree = true;
                for (int i = 0; i < tilesPerLayer; i++) {
                    if (allocated.get(base + i)) {
                        layerFree = false;
                        break;
                    }
                }
                if (layerFree) {
                    if (curStart < 0) {
                        curStart = layer;
                        curCount = 1;
                    } else {
                        curCount++;
                    }
                    if (curCount > bestCount) {
                        bestCount = curCount;
                        bestStart = curStart;
                    }
                } else {
                    curStart = -1;
                    curCount = 0;
                }
            }
            return bestStart < 0 ? Range.EMPTY : new Range(bestStart, bestCount);
        }
    }

    /**
     * 把单个 tile 的 peer 数据 pack + flip 后写入 staging 的对应位置。
     *
     * <p>复用一个 tileTmp 中转——避免每次 memAlloc。
     */
    private void packTileInto(GLTileDataImpl tile,
                              ByteBuffer staging, long layerOffset,
                              ByteBuffer tileTmp,
                              PixelFormat fmt, int needFloats,
                              int tileSize, int layerWidth,
                              int bytesPerPixel, int rowBytes) {
        // ── pack 到 tileTmp ──
        tileTmp.clear();
        FloatBuffer src = tile.peer.floatBuffer();
        if (src.remaining() != needFloats) {
            throw new IllegalStateException(
                    "peer data size mismatch: expected " + needFloats
                            + " floats, got " + src.remaining()
                            + " (tile=" + tile + ")");
        }
        FloatBuffer slice = src.slice();
        slice.limit(needFloats);
        PixelCodec.packPreAlpha(fmt, slice, tileSize * tileSize, tileTmp);
        tileTmp.flip();

        // ── 行序翻转，转 GL 坐标系 ──
        BufferUtils.flipRows(tileTmp, tileSize, rowBytes);

        // ── 逐行拷贝到 staging ──
        int px = tile.getDataColumn() * tileSize;
        int py = tile.getDataRow() * tileSize;
        int layerRowBytes = layerWidth * bytesPerPixel;

        long srcBase = MemoryUtil.memAddress(tileTmp);
        long dstBase = MemoryUtil.memAddress(staging);

        for (int y = 0; y < tileSize; y++) {
            long srcRowAddr = srcBase + (long) y * rowBytes;
            long dstRowAddr = dstBase
                    + layerOffset
                    + (long) (py + y) * layerRowBytes
                    + (long) px * bytesPerPixel;
            MemoryUtil.memCopy(srcRowAddr, dstRowAddr, rowBytes);
        }
    }

    /**
     * 分配一个槽位，返回 Handle。不含 peer 绑定逻辑。
     */
    private Handle allocateHandle() {
        int index;
        synchronized (lock) {
            index = allocated.nextClearBit(0);
            if (index >= capacity) {
                throw new IllegalStateException("GLAtlas full: " + capacity);
            }
            allocated.set(index);
        }
        return decodeHandle(index);
    }

    // ═══════════════════════════════════════════════
    // 整理 / 移动
    // ═══════════════════════════════════════════════

    /**
     * 把 (layer, dataColumn, dataRow) 编码为全局索引。越界抛异常。
     */
    private int encodeIndex(int layer, int dataColumn, int dataRow) {
        int layers = layout.getLayers();
        int tilesPerRow = layout.getTilesPerRow();
        int tilesPerColumn = layout.getTilesPerColumn();
        int tilesPerLayer = layout.getTilesPerLayer();

        if (layer < 0 || layer >= layers) {
            throw new IndexOutOfBoundsException(
                    "layer " + layer + " out of [0, " + layers + ")");
        }
        if (dataColumn < 0 || dataColumn >= tilesPerRow) {
            throw new IndexOutOfBoundsException(
                    "dataColumn " + dataColumn + " out of [0, " + tilesPerRow + ")");
        }
        if (dataRow < 0 || dataRow >= tilesPerColumn) {
            throw new IndexOutOfBoundsException(
                    "dataRow " + dataRow + " out of [0, " + tilesPerColumn + ")");
        }
        return layer * tilesPerLayer + dataRow * tilesPerRow + dataColumn;
    }

    /**
     * 把全局索引解码为 Handle。
     */
    private Handle decodeHandle(int index) {
        int tilesPerLayer = layout.getTilesPerLayer();
        int tilesPerRow = layout.getTilesPerRow();
        int layer = index / tilesPerLayer;
        int local = index % tilesPerLayer;
        int dataColumn = local % tilesPerRow;
        int dataRow = local / tilesPerRow;
        return new Handle(index, layer, dataColumn, dataRow);
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 在指定索引分配 Handle。已占用则抛异常。
     */
    private Handle allocateHandleAt(int index) {
        if (index < 0 || index >= capacity) {
            throw new IndexOutOfBoundsException(
                    "index " + index + " out of [0, " + capacity + ")");
        }
        synchronized (lock) {
            if (allocated.get(index)) {
                throw new IllegalStateException(
                        "slot already occupied at index " + index);
            }
            allocated.set(index);
        }
        return decodeHandle(index);
    }

    private void registerTile(int index, GLTileDataImpl tile) {
        synchronized (lock) {
            tiles[index] = tile;
        }
    }

    private void freeTile(int index) {
        synchronized (lock) {
            if (index < 0 || index >= capacity || !allocated.get(index)) {
                throw new IllegalStateException(
                        "tile " + index + " not allocated");
            }
            allocated.clear(index);
            tiles[index] = null;
        }
    }

    /**
     * 当前所有活跃 tile 的快照。
     */
    List<GLTileDataImpl> getActiveTiles() {
        List<GLTileDataImpl> result = new ArrayList<>();
        synchronized (lock) {
            for (int i = allocated.nextSetBit(0);
                 i >= 0;
                 i = allocated.nextSetBit(i + 1)) {
                GLTileDataImpl tile = tiles[i];
                if (tile != null) {
                    result.add(tile);
                }
            }
        }
        return result;
    }

    /**
     * 释放底层纹理。必须在 GL 线程上调用。幂等。
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            texPool.release(texture);
            allocated.clear();
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "GLAtlas{layout=" + layout
                + ", allocated=" + allocated.cardinality()
                + "}";
    }

    public int getCapacity() {
        return capacity;
    }

    // TODO 包内作用域 会更好吗?

    /**
     * 查询指定槽位是否已占用。
     */
    public boolean isOccupiedAt(int layer, int dataColumn, int dataRow) {
        int index = encodeIndex(layer, dataColumn, dataRow);
        synchronized (lock) {
            return allocated.get(index);
        }
    }


    // ═══════════════════════════════════════════════
    // Handle
    // ═══════════════════════════════════════════════

    public interface GLTileData {
        GLAtlas getAtlas();

        int getLayer();

        int getDataColumn();

        int getDataRow();

        boolean isDirty();

        boolean isValid();

        int getProxyRefCount();
    }

    // ═══════════════════════════════════════════════
    // GLTileData
    // ═══════════════════════════════════════════════

    /**
     * GL 瓦片：{@link TileData} 的 GPU 装饰。
     */
    public static final class GLTileDataImpl
            implements TileData, GLTileData, GLTile, UploadableTile, VersionedTile {
        private final TileData peer;
        private final AtomicBoolean dirty = new AtomicBoolean(true);
        private final AtomicInteger proxyRefCount = new AtomicInteger(1);
        private GLAtlas.Handle handle;

        private GLTileDataImpl(TileData peer, GLAtlas.Handle handle) {
            this.peer = peer;
            this.handle = handle;
        }

        @Override
        public int getProxyRefCount() {
            return proxyRefCount.get();
        }

        @Override
        public Object version() {
            checkValid();
            if (peer instanceof VersionedTile) {
                return ((VersionedTile) peer).version();
            }
            return peer;
        }


        @Override
        public GLTileDescriptor getDescriptor() {
            checkValid();

            GLAtlas atlas = handle.getGLAtlas();
            GLTiledTextureLayout layout = atlas.getLayout();

            int tilesPerRow = layout.getTilesPerRow();
            int tilesPerColumn = layout.getTilesPerColumn();

            return GLTileDescriptor.of(
                    atlas.texture.getUnit(),
                    handle.getLayer(),
                    layout.getTileSize(),
                    (float) handle.getDataColumn() / tilesPerRow,
                    (float) handle.getDataRow() / tilesPerColumn);
        }

        private void rebind(Handle newHandle) {
            this.handle = newHandle;
            this.dirty.set(true);
        }

        @Override
        public void markDirty() {
            this.dirty.set(true);
        }

        @Override
        public void ensureUploaded() {
            checkValid();

            if (dirty.compareAndSet(true, false)) {
                upload();
            }
        }

        private void checkValid() {
            if (!isValid()) {
                throw new IllegalStateException(
                        "Handle already closed:" +
                                "handle: " + handle +
                                "tile:" + this
                );
            }
        }

        // ── TileData 代理 ─────────────────────────────

        @Override
        public float[] getPixels() {
            return peer.getPixels();
        }

        @Override
        public FloatBuffer floatBuffer() {
            return peer.floatBuffer();
        }

        @Override
        public int refCount() {
            return peer.refCount();
        }

        @Override
        public boolean valid() {
            return peer.valid();
        }

        @Override
        public int acquireIfValid() {
            return peer.acquireIfValid();
        }

        @Override
        public int getByteSize() {
            return peer.getByteSize();
        }

        @Override
        public TileData copy() {
            return peer.copy();
        }

        @Override
        public int acquire() {
            checkValid();

            int c = peer.acquire();
            proxyRefCount.incrementAndGet();
//            System.out.println("Acquired tile at " + this
//            + "handle: " + handle
//            + "stack: " + DEBUG.stack());
            return c;
        }

        @Override
        public int release() {
            checkValid();

            int after = peer.release();
            boolean free = proxyRefCount.decrementAndGet() == 0;
            if (free) {
                handle.close();
            }
//            System.out.println("Released tile at " + this
//                    + "handle: " + handle
//                    + "stack: " + DEBUG.stack());
            return after;
        }

        // ── 上传 ─────────────────────────────────────

        private void upload() {
            GLAtlas atlas = handle.getGLAtlas();
            int tileSize = atlas.getLayout().getTileSize();
            int pixelCount = tileSize * tileSize;
            PixelFormat fmt = handle.getPixelFormat();

            FloatBuffer src = peer.floatBuffer();
            int needFloats = PixelCodec.cpuFloats(fmt, pixelCount);
            if (src.remaining() != needFloats) {
                throw new IllegalStateException(
                        "peer data size mismatch: expected exactly " + needFloats
                                + " floats, got " + src.remaining());
            }

            int needBytes = PixelCodec.gpuBytes(fmt, pixelCount);
            ByteBuffer tmp = MemoryUtil.memAlloc(needBytes);

            try {
                FloatBuffer slice = src.slice();
                slice.limit(needFloats);
                PixelCodec.packPreAlpha(fmt, slice, pixelCount, tmp);

                tmp.flip();
                BufferUtils.flipRows(
                        tmp, tileSize,
                        tileSize * fmt.internalBytesPerPixel());
                handle.upload(tmp);
            } finally {
                MemoryUtil.memFree(tmp);
            }
        }

        @Override
        public GLAtlas getAtlas() {
            return handle.getGLAtlas();
        }

        @Override
        public int getLayer() {
            return handle.getLayer();
        }

        @Override
        public int getDataColumn() {
            return handle.getDataColumn();
        }

        @Override
        public int getDataRow() {
            return handle.getDataRow();
        }

        @Override
        public boolean isDirty() {
            return dirty.get();
        }

        @Override
        public boolean isValid() {
            return !handle.isClosed();
        }

        @Override
        public String toString() {
            GLAtlas atlas = handle.getGLAtlas();
            return super.toString() + "{"
                    + "atlas=" + atlas
                    + ", layer=" + handle.getLayer()
                    + ", row=" + handle.getDataRow()
                    + ", col=" + handle.getDataColumn()
                    + ", dirty=" + dirty.get()
                    + ", refCount=" + peer.refCount()
                    + ", proxyRefCount=" + proxyRefCount.get()
                    + ", peer=" + peer.getClass().getSimpleName()
                    + "}";
        }
    }

    /**
     * 瓦片槽位句柄。携带解码后的 {@code (layer, dataColumn, dataRow)}。
     *
     * <p>{@code dataColumn} / {@code dataRow} 是 atlas 数据布局坐标，
     * <b>没有方向语义</b>——由线性槽位索引解码而来：
     * <pre>
     *   layer      = index / tilesPerLayer
     *   local      = index % tilesPerLayer
     *   dataColumn = local % tilesPerRow
     *   dataRow    = local / tilesPerRow
     * </pre>
     */
    private final class Handle {
        private final int index;
        private final int layer;
        private final int dataColumn;
        private final int dataRow;

        private final AtomicBoolean closed = new AtomicBoolean(false);

        Handle(int index, int layer, int dataColumn, int dataRow) {
            this.index = index;
            this.layer = layer;
            this.dataColumn = dataColumn;
            this.dataRow = dataRow;
        }

        int getIndex() {
            return index;
        }

        int getLayer() {
            return layer;
        }

        int getDataColumn() {
            return dataColumn;
        }

        int getDataRow() {
            return dataRow;
        }

        GLAtlas getGLAtlas() {
            return GLAtlas.this;
        }

        PixelFormat getPixelFormat() {
            return texture.getPixelFormat();
        }

        /**
         * 关闭本槽位句柄。释放 atlas 中的槽位。
         *
         * <p><b>不可重入</b>——重复调用抛 {@link IllegalStateException}。
         */
        void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "Handle already closed:"
                                + "handle: " + this
                );
            }
            freeTile(index);
        }

        boolean isClosed() {
            return closed.get();
        }

        /**
         * 上传本瓦片到层内的子矩形。
         */
        void upload(ByteBuffer buffer) {
            if (closed.get()) {
                throw new IllegalStateException(
                        "Handle already closed:"
                                + "handle: " + this
                );
            }
            int tileSize = layout.getTileSize();
            texture.uploadRegion(
                    layer,
                    dataColumn * tileSize,
                    dataRow * tileSize,
                    tileSize, tileSize,
                    buffer);
        }

        @Override
        public String toString() {
            return "Handle{"
                    + "index=" + index
                    + ", layer=" + layer
                    + ", dataColumn=" + dataColumn
                    + ", dataRow=" + dataRow
                    + ", closed=" + closed.get()
                    + '}';
        }
    }
}