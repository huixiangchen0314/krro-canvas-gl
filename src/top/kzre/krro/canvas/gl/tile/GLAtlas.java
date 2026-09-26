package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;
import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.core.util.DEBUG;
import top.kzre.krro.util.tile.*;

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
 *   col   = local % tilesPerEdge
 *   row   = local / tilesPerEdge
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

    /** 私有锁，避免外部用 {@code synchronized (atlas)} 干扰。 */
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
        if (texture.getWidth() != layout.getLayerSize()
                || texture.getHeight() != layout.getLayerSize()) {
            throw new IllegalArgumentException(
                    "texture dimensions " + texture.getWidth() + "x" + texture.getHeight()
                            + " do not match layout layer size " + layout.getLayerSize());
        }
        if (texture.getLayers() != layout.getLayers()) {
            throw new IllegalArgumentException(
                    "texture layers " + texture.getLayers()
                            + " do not match layout layers " + layout.getLayers());
        }

        this.texture   = texture;
        this.layout    = layout;
        this.capacity  = layout.getCapacity();
        this.tiles     = new GLTileDataImpl[capacity];
        this.allocated = new BitSet(capacity);
        this.closed = false;
    }

    public GLTiledTextureLayout getLayout() { return layout; }

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

    // ═══════════════════════════════════════════════
    // 分配
    // ═══════════════════════════════════════════════

    /**
     * 分配一个瓦片槽位，<b>独占转移</b> peer 的所有权。
     *
     * <p><b>幂等</b>：若 peer 已经是绑定到本 atlas 的 {@link GLTileDataImpl}，
     * 直接返回它。
     *
     * <p><b>跨 atlas 防护</b>：若 peer 是绑定到其他 atlas 的
     * {@link GLTileDataImpl}，抛异常。
     *
     * <p><b>独占前置条件</b>：非 GLTileData 的 peer 必须满足
     * {@code refCount == 1}。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @throws IllegalStateException atlas 已满、peer 跨 atlas、或 peer 非独占
     */
    public GLTileDataImpl allocate(TileData peer) {
        if (peer instanceof GLTile) {
            if (peer instanceof GLTileDataImpl) {
                GLTileDataImpl existing = (GLTileDataImpl) peer;
                if (existing.handle.getGLAtlas() == this) {
                    return existing;
                }
                throw new IllegalStateException(
                        "peer is a GLTileData bound to a different atlas");
            }
            throw new IllegalStateException(
                    "peer is already a GPU-backed tile (class = "
                            + peer.getClass().getName() + ")");
        }


        Handle handle = allocateHandle();
        GLTileDataImpl tile = new GLTileDataImpl(peer, handle);
        registerTile(handle.getIndex(), tile);
        return tile;
    }

    // ═══════════════════════════════════════════════
    // 精确分配
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
     * <p><b>前置条件</b>：peer 是 CPU 侧独占的 {@link TileData}
     * （{@code refCount == 1}），不能是已包装的 {@link GLTileDataImpl}。
     * 精确分配没有"幂等返回已有"的语义——位置对不上就是错误。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @param layer texture array 层索引，{@code [0, layout.getLayers())}
     * @param dataColumn   层内列坐标（u 方向），{@code [0, tilesPerEdge)}
     * @param dataRow   层内行坐标（v 方向），{@code [0, tilesPerEdge)}
     * @param peer  瓦片数据，独占
     * @throws IndexOutOfBoundsException layer / dataColumn / dataRow 越界
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

    /** 分配一个槽位，返回 Handle。不含 peer 绑定逻辑。 */
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
    // 索引编解码
    // ═══════════════════════════════════════════════

    /** 把 (layer, dataColumn, dataRow) 编码为全局索引。越界抛异常。 */
    private int encodeIndex(int layer, int dataColumn, int dataRow) {
        int layers        = layout.getLayers();
        int tilesPerEdge  = layout.getTilesPerEdge();
        int tilesPerLayer = layout.getTilesPerLayer();

        if (layer < 0 || layer >= layers) {
            throw new IndexOutOfBoundsException(
                    "layer " + layer + " out of [0, " + layers + ")");
        }
        if (dataColumn < 0 || dataColumn >= tilesPerEdge) {
            throw new IndexOutOfBoundsException(
                    "dataColumn " + dataColumn + " out of [0, " + tilesPerEdge + ")");
        }
        if (dataRow < 0 || dataRow >= tilesPerEdge) {
            throw new IndexOutOfBoundsException(
                    "dataRow " + dataRow + " out of [0, " + tilesPerEdge + ")");
        }
        return layer * tilesPerLayer + dataRow * tilesPerEdge + dataColumn;
    }

    /** 把全局索引解码为 Handle。 */
    private Handle decodeHandle(int index) {
        int tilesPerLayer = layout.getTilesPerLayer();
        int tilesPerEdge  = layout.getTilesPerEdge();
        int layer = index / tilesPerLayer;
        int local = index % tilesPerLayer;
        int dataColumn   = local % tilesPerEdge;
        int dataRow   = local / tilesPerEdge;
        return new Handle(index, layer, dataColumn, dataRow);
    }

    /** 在指定索引分配 Handle。已占用则抛异常。 */
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

    // ═══════════════════════════════════════════════
    // 整理 / 移动
    // ═══════════════════════════════════════════════

    /**
     * 把 tile 从 src 移到 dst。
     *
     * <p><b>语义</b>：
     * <ol>
     *   <li>在 dst 上分配一个新槽位</li>
     *   <li>原地更新 tile 的 handle 和 descriptor</li>
     *   <li>标记 tile 为脏——下次 {@code ensureUploaded} 从 peer 重传</li>
     *   <li>释放 src 上的旧槽位</li>
     * </ol>
     *
     * <p><b>不立即上传</b>——数据仍由 peer 持有，新层内容延迟到使用时填充。
     *
     * <p><b>所有权</b>：tile 实例不变，调用方引用继续有效。
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

    /** 当前所有活跃 tile 的快照。 */
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

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 释放底层纹理。必须在 GL 线程上调用。幂等。
     * 释放后所有已分配的 Handle 失效。
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

    /**
     * 瓦片槽位句柄。携带解码后的 {@code (layer, dataColumn, dataRow)}。
     *
     * <h2>dataColumn / dataRow 的语义</h2>
     *
     * <p>这两个值描述"这块瓦片放在 atlas 纹理层的第几列、第几行"——
     * 是 <b>atlas 数据布局坐标</b>，<b>没有方向语义</b>。
     *
     * <p>它们由线性槽位索引 {@code index} 解码而来：
     * <pre>
     *   layer      = index / tilesPerLayer
     *   local      = index % tilesPerLayer
     *   dataColumn = local % tilesPerEdge
     *   dataRow    = local / tilesPerEdge
     * </pre>
     * 分配算法线性扫描空槽位，{@code dataRow} 只是"第几个空槽位落在第几
     * 行"，与屏幕位置、图层网格没有任何对应关系。
     *
     * <h2>与带方向语义坐标的区别</h2>
     *
     * <p>项目里其他"行"坐标是带方向的——{@code canvas} 网格行 y 向下
     * （顶行是 0），GL 纹理行 y 向上（底行是 0）。跨这两套坐标系的
     * 转换需要在转换点显式翻转（{@code height - 1 - row}）。
     *
     * <p>{@code dataColumn} / {@code dataRow} <b>不属于任何一套</b>：
     * <ul>
     *   <li>作为纹理坐标传给 {@code glTexSubImage3D} 的 {@code (x, y)} 时——
     *       直接乘 {@code tileSize}，不翻。它在此处被当作 GL 纹理坐标使用，
     *       与数据的采样方向由 shader 侧保证一致</li>
     *   <li>转成 {@code GLTileDescriptor} 的 uv 起点时——
     *       {@code u = dataColumn / tilesPerEdge}、
     *       {@code v = dataRow / tilesPerEdge}，同样不翻</li>
     * </ul>
     *
     * <p>与其叫 {@code col / row} 容易让人误以为有方向，这里用
     * {@code dataColumn / dataRow} 明确它是"数据放在哪一格"，方向由
     * 使用它的上下文决定。
     *
     * <h2>生命周期</h2>
     *
     * <p>{@link #closed} 标记该 handle 是否仍然指向 atlas 中的有效槽位。
     * {@link #close()} 将其置 false 并清空 atlas 中对应的位图和引用——
     * 之后对该 handle 的任何操作要么是 no-op（{@code free} 幂等），
     * 要么抛出异常（若校验）。
     *
     * <h2>线程契约</h2>
     *
     * <p>字段可变（{@code valid} 的翻转），访问完全在 GL 线程内——
     * atlas 的分配、上传、释放路径全部由 GL 线程驱动。
     */
    private final class Handle {
        private final int index;
        private final int layer;
        private final int dataColumn;
        private final int dataRow;

        /** 关闭标记。CAS 保证 check-then-act 原子——并发 close 只有一个成功。 */
        private final AtomicBoolean closed = new AtomicBoolean(false);

        Handle(int index, int layer, int dataColumn, int dataRow) {
            this.index = index;
            this.layer = layer;
            this.dataColumn = dataColumn;
            this.dataRow = dataRow;
        }

        int getIndex()       { return index; }
        int getLayer()       { return layer; }
        int getDataColumn()  { return dataColumn; }
        int getDataRow()     { return dataRow; }

        GLAtlas getGLAtlas()         { return GLAtlas.this; }
        PixelFormat getPixelFormat() { return texture.getPixelFormat(); }

        /**
         * 关闭本槽位句柄。释放 atlas 中的槽位。
         *
         * <p><b>不可重入</b>——重复调用抛 {@link IllegalStateException}。
         *
         * <p><b>并发安全</b>：{@code closed} 是 {@link AtomicBoolean}，
         * {@code close()} 走 CAS——多个线程同时调用只有一个成功翻转，
         * 其余抛异常。不会出现两次 {@code freeTile}。
         *
         * <p>之前版本用 {@code if (!closed) return} 静默返回是写反了逻辑——
         * 未关闭时不执行释放。当前版本语义是"恰好一次"。
         */
        void close() {
            if (!closed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "Handle already closed: layer=" + layer
                                + ", dataColumn=" + dataColumn
                                + ", dataRow=" + dataRow);
            }
            freeTile(index);
        }

        /** 上传本瓦片到层内的子矩形。 */
        void upload(ByteBuffer buffer) {
            if (closed.get()) {
                throw new IllegalStateException(
                        "Handle already closed: layer=" + layer
                                + ", dataColumn=" + dataColumn
                                + ", dataRow=" + dataRow);
            }
            int tileSize = layout.getTileSize();

            texture.uploadRegion(
                    layer,
                    dataColumn * tileSize,
                    dataRow * tileSize,
                    tileSize,
                    tileSize,
                    buffer);
        }
    }

    // ═══════════════════════════════════════════════
    // GLTileData
    // ═══════════════════════════════════════════════

    public interface GLTileData {
        GLAtlas getAtlas();
        int getLayer();
        int getDataColumn();
        int getDataRow();
    }

    /**
     * GL 瓦片：{@link TileData} 的 GPU 装饰。
     *
     * <p><b>线程契约</b>：
     * <ul>
     *   <li>{@link #markDirty()} —— 任意线程</li>
     *   <li>{@link #ensureUploaded()} /
     *       {@link #getDescriptor()} / {@link #release()} —— GL 线程</li>
     *   <li>其他 {@link TileData} 代理方法 —— 由 peer 契约决定</li>
     * </ul>
     *
     * <p>内部字段 {@code handle} 不做同步——访问完全落在 GL 线程内，
     * 由上述契约保证。
     */
    public static final class GLTileDataImpl
            implements TileData, GLTileData, GLTile, UploadableTile, VersionedTile {
        private final TileData peer;
        /** 跨线程访问 */
        private final AtomicBoolean dirty = new AtomicBoolean(true);
        private GLAtlas.Handle handle;

        /**
         *代理的 refCount,与 peer 的引用计数分别处理
         * 初始构造为1，调用工厂的TiledCanvas 持有
         */
        private final AtomicInteger proxyRefCount = new AtomicInteger(1);

        private GLTileDataImpl(TileData peer, GLAtlas.Handle handle) {
            this.peer   = peer;
            this.handle = handle;
        }

        @Override
        public Object version() {
            if (peer instanceof VersionedTile) {
                return ((VersionedTile) peer).version();
            }
            return peer;
        }

        /**
         * 把 {@link Tile} 上的 GPU 形态换回 CPU 形态。
         *
         * <p>如果 tile 当前持有 {@link GLTileDataImpl}：
         * <ol>
         *   <li>{@code tile.replaceData(peer)} —— 引用计数不变，
         *       Tile 的持有从 GLTileData 换成 peer</li>
         *   <li>{@code handle.free()} —— 显式释放 atlas 槽位</li>
         * </ol>
         *
         * <p>如果 tile 为空，或不持有 GLTileData，静默返回。
         *
         * <p><b>线程契约</b>：涉及 atlas 槽位释放，必须在 GL 线程上调用。
         * 调用方需保证 tile 的 dataRef 在本方法执行期间不被并发修改。
         *
         * @param tile 目标瓦片，可为 null
         */
        public static void pageOut(Tile tile) {
            if (tile == null) return;
            GLTileDataImpl gl = tile.queryData(GLTileDataImpl.class);
            if (gl == null) return;
            tile.replaceData(gl.peer);
            gl.handle.close();
        }

        @Override
        public GLTileDescriptor getDescriptor() {
            GLAtlas atlas = handle.getGLAtlas();

            GLTiledTextureLayout layout = atlas.getLayout();
            int edge = layout.getTilesPerEdge();
            return GLTileDescriptor.of(
                    handle.getLayer(),
                    layout.getTileSize(),
                    (float) handle.getDataColumn() / edge,
                    (float) handle.getDataRow() / edge);
        }

        /**
         * 重新绑定到新槽位。只在 GL 线程调用。
         */
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
            if (dirty.compareAndSet(true, false)) {
                upload();
            }
        }

        // ── TileData 代理 ─────────────────────────────

        @Override public float[]    getPixels()      { return peer.getPixels(); }
        @Override public FloatBuffer floatBuffer()   { return peer.floatBuffer(); }

        @Override public int        refCount()       { return peer.refCount(); }
        @Override public boolean    valid()          { return peer.valid(); }
        @Override public int        acquireIfValid() { return peer.acquireIfValid(); }
        @Override public int        getByteSize()    { return peer.getByteSize(); }
        @Override public TileData   copy()           { return peer.copy(); }

        @Override
        public int acquire() {
            int c = peer.acquire();
            proxyRefCount.incrementAndGet();
            System.out.println("[GLTileDataImpl#acquire] -> " + c + " " + this
                    + " at " + DEBUG.stack());
            return c;
        }

        // TODO 更加详细考虑
        @Override
        public int release() {

            int after  = peer.release();
            boolean free = proxyRefCount.decrementAndGet() == 0;
            System.out.println("[GLTileDataImpl#release]"
                    + " " + this
                    + "free: " + free
                    + " at \n" + DEBUG.stack());

            if (free) {
                handle.close();
            }
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

            System.out.println("[upload.begin]"
                    + " tileSize=" + tileSize
                    + " pixelCount=" + pixelCount
                    + " fmt.internal=0x" + Integer.toHexString(fmt.getGlInternalFormat())
                    + " fmt.client=0x" + Integer.toHexString(fmt.getGlClientFormat())
                    + " fmt.type=0x" + Integer.toHexString(fmt.getGlType())
                    + " channels=" + PixelCodec.channels(fmt)
                    + " src.remaining=" + src.remaining()
                    + " needFloats=" + needFloats
                    + " needBytes=" + needBytes
                    + " peer.class=" + peer.getClass().getSimpleName());

            try {
                FloatBuffer slice = src.slice();
                slice.limit(needFloats);
                System.out.println("[upload.beforePack] slice.remaining=" + slice.remaining()
                        + " tmp.capacity=" + tmp.capacity()
                        + " tmp.position=" + tmp.position()
                        + " tmp.limit=" + tmp.limit());

                PixelCodec.pack(fmt, slice, pixelCount, tmp);

                System.out.println("[upload.afterPack] tmp.position=" + tmp.position()
                        + " tmp.limit=" + tmp.limit()
                        + " tmp.remaining=" + tmp.remaining());

                tmp.flip();

                System.out.println("[upload.afterFlip] tmp.position=" + tmp.position()
                        + " tmp.limit=" + tmp.limit()
                        + " tmp.remaining=" + tmp.remaining()
                        + " expectedBytes=" + needBytes
                        + " matches=" + (tmp.remaining() == needBytes));

                // 前 32 字节的 hex——看 pack 是否真的写了非零
                int peek = Math.min(32, tmp.remaining());
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < peek; i++) {
                    hex.append(String.format("%02x ", tmp.get(i) & 0xFF));
                }
                System.out.println("[upload.bytes] first " + peek + ": " + hex);

                handle.upload(tmp);
                System.out.println("[upload.done]");
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
        public String toString() {
            GLAtlas atlas = handle.getGLAtlas();
            return super.toString() + "{"
                    + "atlas=" + System.identityHashCode(atlas)
                    + ", layer=" + handle.getLayer()
                    + ", row=" + handle.getDataRow()
                    + ", col=" + handle.getDataColumn()
                    + ", dirty=" + dirty.get()
                    + ", refs=" + peer.refCount()
                    + ", peer=" + peer.getClass().getSimpleName()
                    + "}";
        }
    }


}