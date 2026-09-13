package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.PixelFormat;
import top.kzre.krro.util.tile.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GL 图集：管理一张 {@link GLTexture} 中所有瓦片槽位的分配与释放。
 *
 * <p>一个 atlas 对应一张 {@code GL_TEXTURE_2D_ARRAY}：
 * <ul>
 *   <li>每层是一个 {@code layerSize × layerSize} 的方形区域</li>
 *   <li>层内按 {@code gridSize × gridSize} 网格摆放瓦片</li>
 *   <li>{@code gridSize = layerSize / tileSize}（如 256/64 = 4）</li>
 *   <li>总槽位 {@code capacity = layers × gridSize²}</li>
 * </ul>
 *
 * <h2>索引</h2>
 * 全局瓦片索引 {@code index ∈ [0, capacity)}，编码为：
 * <pre>
 *   layer = index / tilesPerLayer
 *   local = index % tilesPerLayer
 *   sx    = local % gridSize
 *   sy    = local / gridSize
 * </pre>
 * {@code allocated} 位图与 {@code tiles[]} 映射都以全局索引为下标。
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>{@code allocate} / {@code move} / {@code release} /
 *       {@code ensureUploaded} / {@code ensureDownloaded} —— GL 线程</li>
 *   <li>{@code freeTile} 可能从任意线程进入（{@link GLTileData#release()}
 *       由引用计数触发）—— 内部用 {@code lock} 保护</li>
 *   <li>{@link GLTileData#markDirty()} —— 任意线程</li>
 * </ul>
 */
public final class GLAtlas {


    /** 私有锁，避免外部用 {@code synchronized (atlas)} 干扰。 */
    private final Object lock = new Object();

    private final GLTexture texture;
    private final int capacity;          // = layers × tilesPerLayer
    private final int tileSize;
    private final int gridSize;          // 每层网格边长
    private final int tilesPerLayer;     // = gridSize²
    private final BitSet allocated;      // 全局瓦片索引

    private boolean released;
    private final GLTileData[] tiles;    // 全局瓦片索引 → tile
    private  int unit = -1;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param texture  底层纹理（{@code GL_TEXTURE_2D_ARRAY}）
     * @param tileSize 瓦片边长（像素）
     * @throws IllegalArgumentException 纹理尺寸与 tileSize 不匹配
     */
    public GLAtlas(GLTexture texture, int tileSize) {
        if (texture == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        if (tileSize < 1) {
            throw new IllegalArgumentException("tileSize must be >= 1: " + tileSize);
        }
        if (texture.getWidth() != texture.getHeight()) {
            throw new IllegalArgumentException(
                    "Texture must be square: " + texture.getWidth() + "x" + texture.getHeight());
        }
        if (texture.getWidth() % tileSize != 0) {
            throw new IllegalArgumentException(
                    "Texture size " + texture.getWidth()
                            + " must be a multiple of tile size " + tileSize);
        }

        this.texture       = texture;
        this.tileSize      = tileSize;
        this.gridSize      = texture.getWidth() / tileSize;
        this.tilesPerLayer = gridSize * gridSize;
        this.capacity      = texture.getLayers() * tilesPerLayer;
        this.tiles         = new GLTileData[capacity];
        this.allocated     = new BitSet(capacity);
        this.released      = false;
    }

    /**
     * 创建 RGBA8 atlas：{@code layerSize / tileSize} 决定层内网格。
     *
     * @param layerSize 层边长（像素），必须为 tileSize 的倍数
     * @param tileSize  瓦片边长（像素）
     * @param layers    层数
     */
    public static GLAtlas createRgba8(int layerSize, int tileSize, int layers) {
        return new GLAtlas(
                GLTexture.createRgba8(layerSize, layerSize, layers),
                tileSize);
    }

    /**
     * 绑定 atlas 到指定纹理单元。
     *
     * <p>设置纹理单元并绑定底层纹理——之后 shader 里对应的
     * {@code sampler2DArray} 可以从该 unit 采样。后续
     * {@link GLTileData#getDescriptor()} 返回的 descriptor 里的 unit
     * 与此一致。
     *
     * <p><b>有 GL 副作用</b>——不是纯属性设置。必须在 GL 线程上调用。
     *
     * @param unit 纹理单元索引，必须 ≥ 0
     * @throws IllegalArgumentException unit &lt; 0
     */
    public void bind(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException(
                    "unit must be >= 0, got " + unit);
        }
        this.unit = unit;
        this.texture.bind(unit);
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════


    public int getTileSize()      { return tileSize; }
    public int getGridSize()      { return gridSize; }
    public int getTilesPerLayer() { return tilesPerLayer; }
    public int getCapacity()      { return capacity; }
    public int getUnit()          { return unit; }

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
     * <p><b>幂等</b>：若 peer 已经是绑定到本 atlas 的 {@link GLTileData}，
     * 直接返回它。
     *
     * <p><b>跨 atlas 防护</b>：若 peer 是绑定到其他 atlas 的
     * {@link GLTileData}，抛异常。
     *
     * <p><b>独占前置条件</b>：非 GLTileData 的 peer 必须满足
     * {@code refCount == 1}。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @throws IllegalStateException atlas 已满、peer 跨 atlas、或 peer 非独占
     */
    public GLTileData allocate(TileData peer) {
        if (peer instanceof GLTile) {
            if (peer instanceof GLTileData) {
                GLTileData existing = (GLTileData) peer;
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

        int rc = peer.refCount();
        if (rc != 1) {
            throw new IllegalStateException(
                    "peer must be exclusively owned (refCount == 1) for transfer, "
                            + "but refCount = " + rc);
        }

        Handle handle = allocateHandle();
        GLTileData tile = new GLTileData(peer, handle);
        registerTile(handle.getIndex(), tile);
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
        int layer = index / tilesPerLayer;
        int local = index % tilesPerLayer;
        int sx    = local % gridSize;
        int sy    = local / gridSize;
        return new Handle(index, layer, sx, sy);
    }

    /** 包级私有——供 move 在目标 atlas 上注册。 */
    void registerTile(int index, GLTileData tile) {
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
    public static boolean move(GLAtlas dst, GLAtlas src, GLTileData tile) {
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
        oldHandle.free();

        return true;
    }

    /** 当前所有活跃 tile 的快照。 */
    public List<GLTileData> getActiveTiles() {
        List<GLTileData> result = new ArrayList<>();
        synchronized (lock) {
            for (int i = allocated.nextSetBit(0);
                 i >= 0;
                 i = allocated.nextSetBit(i + 1)) {
                GLTileData tile = tiles[i];
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
    public void release() {
        synchronized (lock) {
            if (released) return;
            texture.release();
            allocated.clear();
            released = true;
        }
    }

    @Override
    public String toString() {
        return "GLAtlas{unit=" + unit
                + ", capacity=" + capacity
                + ", allocated=" + allocated.cardinality()
                + "}";
    }

    // ═══════════════════════════════════════════════
    // Handle
    // ═══════════════════════════════════════════════

    /**
     * 瓦片槽位句柄。携带解码后的 {@code (layer, sx, sy)}。
     */
    private final class Handle {
        private final int index;      // 全局瓦片索引
        private final int layer;
        private final int sx, sy;     // 层内格坐标
        private boolean valid = true;

        Handle(int index, int layer, int sx, int sy) {
            this.index = index;
            this.layer = layer;
            this.sx    = sx;
            this.sy    = sy;
        }

        int getIndex()    { return index; }
        int getLayer()    { return layer; }
        int getSx()       { return sx; }
        int getSy()       { return sy; }
        int getTileSize() { return tileSize; }
        int getGridSize() { return gridSize; }
        int getUnit()     { return unit; }

        GLAtlas getGLAtlas() { return GLAtlas.this; }
        PixelFormat getPixelFormat() { return texture.getPixelFormat(); }

        void free() {
            if (!valid) return;
            valid = false;
            freeTile(index);
        }

        /** 上传本瓦片到层内的子矩形。 */
        void upload(ByteBuffer buffer) {
            texture.uploadRegion(
                    layer,
                    sx * tileSize,
                    sy * tileSize,
                    tileSize,
                    tileSize,
                    buffer);
        }


    }

    // ═══════════════════════════════════════════════
    // GLTileData
    // ═══════════════════════════════════════════════

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
     * <p>内部字段 {@code handle} / {@code descriptor} 不做同步——访问
     * 完全落在 GL 线程内，由上述契约保证。
     */
    public static final class GLTileData implements TileData, GLTile, UploadableTile {
        private final TileData peer;
        /** 跨线程访问：CPU 线程 markDirty，GL 线程 ensureUploaded 清。 */
        private final AtomicBoolean dirty = new AtomicBoolean(true);
        private GLAtlas.Handle handle;

        private GLTileData(TileData peer, GLAtlas.Handle handle) {

            this.peer   = peer;
            this.handle = handle;
        }


        @Override
        public GLTileDescriptor getDescriptor() {
            GLAtlas atlas = handle.getGLAtlas();
            int unit = atlas.getUnit();
            if (unit < 0) {
                throw new IllegalStateException(
                        "atlas unit not assigned; call GLAtlas.setUnit(n) before "
                                + "collecting descriptors. atlas=" + atlas);
            }
            return GLTileDescriptor.of(
                    unit,
                    handle.getLayer(),
                    handle.getTileSize(),
                    (float) handle.getSx() / handle.getGridSize(),
                    (float) handle.getSy() / handle.getGridSize());
        }

        /**
         * 重新绑定到新槽位。只在 GL 线程调用。
         */
        void rebind(Handle newHandle) {
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
        @Override public int        acquire()        { return peer.acquire(); }
        @Override public int        refCount()       { return peer.refCount(); }
        @Override public boolean    valid()          { return peer.valid(); }
        @Override public int        acquireIfValid() { return peer.acquireIfValid(); }
        @Override public int        getByteSize()    { return peer.getByteSize(); }
        @Override public TileData   copy()           { return peer.copy(); }

        @Override
        public int release() {
            int count = peer.release();
            if (count == 0) {
                handle.free();
            }
            return count;
        }

        // ── 上传 ─────────────────────────────────────

        private void upload() {
            int tileSize = handle.getTileSize();
            PixelFormat fmt = handle.getPixelFormat();
            int expectedFloats = fmt.tileFloats(tileSize);
            int expectedBytes  = expectedFloats * Float.BYTES;

            if (peer instanceof DirectTileData) {
                DirectTileData d = (DirectTileData) peer;
                uploadSliced(d.buffer(), expectedBytes);
            } else if (peer instanceof MappedTileData) {
                MappedTileData s = (MappedTileData) peer;
                uploadSliced(s.mapping(), expectedBytes);
            } else if (peer instanceof HeapTileData) {
                HeapTileData h = (HeapTileData) peer;
                FloatBuffer src = h.floatBuffer();
                if (src.remaining() < expectedFloats) {
                    throw new IllegalStateException(
                            "peer data too small: expected " + expectedFloats
                                    + " floats, got " + src.remaining());
                }
                ByteBuffer tmp = MemoryUtil.memAlloc(expectedBytes);
                try {
                    FloatBuffer dst = tmp.asFloatBuffer();
                    FloatBuffer slice = src.slice();
                    slice.limit(expectedFloats);
                    dst.put(slice);
                    handle.upload(tmp);
                } finally {
                    MemoryUtil.memFree(tmp);
                }
            } else {
                throw new UnsupportedOperationException(
                        "unsupported peer type: " + peer.getClass().getName());
            }
        }

        /**
         * 用 slice 限制上传范围——只传前 {@code expectedBytes} 字节。
         */
        private void uploadSliced(ByteBuffer buf, int expectedBytes) {
            if (buf.remaining() < expectedBytes) {
                throw new IllegalStateException(
                        "peer buffer too small: expected " + expectedBytes
                                + " bytes, got " + buf.remaining());
            }
            ByteBuffer slice = buf.slice();
            slice.limit(expectedBytes);
            handle.upload(slice);
        }
    }

}