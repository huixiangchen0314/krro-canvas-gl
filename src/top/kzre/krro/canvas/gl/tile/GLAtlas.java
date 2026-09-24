package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.UploadableTile;
import top.kzre.krro.canvas.gl.resource.*;
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
 * <p>布局由 {@link GLTiledTextureLayout} 描述。构造时校验纹理维度
 * 与布局一致。
 *
 * <h2>索引</h2>
 * 全局瓦片索引 {@code index ∈ [0, capacity)}，编码为：
 * <pre>
 *   layer = index / tilesPerLayer
 *   local = index % tilesPerLayer
 *   sx    = local % tilesPerEdge
 *   sy    = local / tilesPerEdge
 * </pre>
 * {@code allocated} 位图与 {@code tiles[]} 映射都以全局索引为下标。
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>{@code allocate} / {@code move} / {@code release} /
 *       {@code ensureUploaded} —— GL 线程</li>
 *   <li>{@code freeTile} 可能从任意线程进入（{@link GLTileData#release()}
 *       由引用计数触发）—— 内部用 {@code lock} 保护</li>
 *   <li>{@link GLTileData#markDirty()} —— 任意线程</li>
 * </ul>
 */
public final class GLAtlas implements GLBindable {

    /** 私有锁，避免外部用 {@code synchronized (atlas)} 干扰。 */
    private final Object lock = new Object();

    private final GLTexture texture;
    private final GLTiledTextureLayout layout;
    private final GLTexturePool texPool;

    private final BitSet allocated;
    private final GLTileData[] tiles;
    private final int capacity;

    private boolean released;

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
        this.capacity = layout.getCapacity();
        this.tiles     = new GLTileData[capacity];
        this.allocated = new BitSet(capacity);
        this.released  = false;
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



        Handle handle = allocateHandle();
        GLTileData tile = new GLTileData(peer, handle);
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
     * （{@code refCount == 1}），不能是已包装的 {@link GLTileData}。
     * 精确分配没有"幂等返回已有"的语义——位置对不上就是错误。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @param layer texture array 层索引，{@code [0, layout.getLayers())}
     * @param sx    层内列坐标（u 方向），{@code [0, tilesPerEdge)}
     * @param sy    层内行坐标（v 方向），{@code [0, tilesPerEdge)}
     * @param peer  瓦片数据，独占
     * @throws IndexOutOfBoundsException layer / sx / sy 越界
     * @throws IllegalStateException     peer 非独占、已是 GPU 瓦片、或槽位已占用
     */
    public GLTileData allocateAt(int layer, int sx, int sy, TileData peer) {
        if (peer instanceof GLTile) {
            throw new IllegalStateException(
                    "peer is already GPU-backed (class = "
                            + peer.getClass().getName()
                            + "); allocateAt requires a fresh CPU peer");
        }


        int index = encodeIndex(layer, sx, sy);
        Handle handle = allocateHandleAt(index);
        GLTileData tile = new GLTileData(peer, handle);
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

    /** 把 (layer, sx, sy) 编码为全局索引。越界抛异常。 */
    private int encodeIndex(int layer, int sx, int sy) {
        int layers        = layout.getLayers();
        int tilesPerEdge  = layout.getTilesPerEdge();
        int tilesPerLayer = layout.getTilesPerLayer();

        if (layer < 0 || layer >= layers) {
            throw new IndexOutOfBoundsException(
                    "layer " + layer + " out of [0, " + layers + ")");
        }
        if (sx < 0 || sx >= tilesPerEdge) {
            throw new IndexOutOfBoundsException(
                    "sx " + sx + " out of [0, " + tilesPerEdge + ")");
        }
        if (sy < 0 || sy >= tilesPerEdge) {
            throw new IndexOutOfBoundsException(
                    "sy " + sy + " out of [0, " + tilesPerEdge + ")");
        }
        return layer * tilesPerLayer + sy * tilesPerEdge + sx;
    }

    /** 把全局索引解码为 Handle。 */
    private Handle decodeHandle(int index) {
        int tilesPerLayer = layout.getTilesPerLayer();
        int tilesPerEdge  = layout.getTilesPerEdge();
        int layer = index / tilesPerLayer;
        int local = index % tilesPerLayer;
        int sx    = local % tilesPerEdge;
        int sy    = local / tilesPerEdge;
        return new Handle(index, layer, sx, sy);
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
            texPool.release(texture);
            allocated.clear();
            released = true;
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
     * 查询指定槽位当前的瓦片。
     *
     * <p>返回该位置已分配的 {@link GLTileData}；空槽位返回 {@code null}。
     * 用于规划阶段探查 atlas 现状，决定是命中缓存、换页还是新分配。
     *
     * <p><b>线程契约</b>：任意线程可读。内部用 {@code lock} 保护。
     *
     * @param layer 层索引
     * @param sx    层内列
     * @param sy    层内行
     * @return 该槽位的 GLTileData；空槽位返回 null
     * @throws IndexOutOfBoundsException layer / sx / sy 越界
     */
    public GLTileData getTileAt(int layer, int sx, int sy) {
        int index = encodeIndex(layer, sx, sy);
        synchronized (lock) {
            return tiles[index];
        }
    }

    /**
     * 查询指定槽位是否已占用。
     */
    public boolean isOccupiedAt(int layer, int sx, int sy) {
        int index = encodeIndex(layer, sx, sy);
        synchronized (lock) {
            return allocated.get(index);
        }
    }



    // ═══════════════════════════════════════════════
    // Handle
    // ═══════════════════════════════════════════════

    /**
     * 瓦片槽位句柄。携带解码后的 {@code (layer, sx, sy)}。
     */
    private final class Handle {
        private final int index;
        private final int layer;
        private final int sx, sy;
        private boolean valid = true;

        Handle(int index, int layer, int sx, int sy) {
            this.index = index;
            this.layer = layer;
            this.sx    = sx;
            this.sy    = sy;
        }

        int getIndex() { return index; }
        int getLayer() { return layer; }
        int getSx()    { return sx; }
        int getSy()    { return sy; }

        GLAtlas getGLAtlas() { return GLAtlas.this; }
        PixelFormat getPixelFormat() { return texture.getPixelFormat(); }

        void free() {
            if (!valid) return;
            valid = false;
            freeTile(index);
        }

        /** 上传本瓦片到层内的子矩形。 */
        void upload(ByteBuffer buffer) {
            int ts = layout.getTileSize();
            texture.uploadRegion(
                    layer,
                    sx * ts,
                    sy * ts,
                    ts,
                    ts,
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
     * <p>内部字段 {@code handle} 不做同步——访问完全落在 GL 线程内，
     * 由上述契约保证。
     */
    public static final class GLTileData implements TileData, GLTile, UploadableTile {
        private final TileData peer;
        /** 跨线程访问 */
        private final AtomicBoolean dirty = new AtomicBoolean(true);
        private GLAtlas.Handle handle;

        private GLTileData(TileData peer, GLAtlas.Handle handle) {
            this.peer   = peer;
            this.handle = handle;
        }

        /**
         * 把 {@link Tile} 上的 GPU 形态换回 CPU 形态。
         *
         * <p>如果 tile 当前持有 {@link GLTileData}：
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
            GLTileData gl = tile.queryData(GLTileData.class);
            if (gl == null) return;
            tile.replaceData(gl.peer);
            gl.handle.free();
        }

        @Override
        public GLTileDescriptor getDescriptor() {
            GLAtlas atlas = handle.getGLAtlas();


            GLTiledTextureLayout layout = atlas.getLayout();
            int edge = layout.getTilesPerEdge();
            return GLTileDescriptor.of(
                    handle.getLayer(),
                    layout.getTileSize(),
                    (float) handle.getSx() / edge,
                    (float) handle.getSy() / edge);
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
            GLAtlas atlas = handle.getGLAtlas();
            int ts = atlas.getLayout().getTileSize();
            int pixelCount = ts * ts;
            PixelFormat fmt = handle.getPixelFormat();

            FloatBuffer src = peer.floatBuffer();
            int needFloats = PixelCodec.cpuFloats(fmt, pixelCount);
            if (src.remaining() < needFloats) {
                throw new IllegalStateException(
                        "peer data too small: expected " + needFloats
                                + " floats, got " + src.remaining());
            }

            int needBytes = PixelCodec.gpuBytes(fmt, pixelCount);
            ByteBuffer tmp = MemoryUtil.memAlloc(needBytes);
            try {
                FloatBuffer slice = src.slice();
                slice.limit(needFloats);
                PixelCodec.pack(fmt, slice, pixelCount, tmp);
                tmp.flip();
                handle.upload(tmp);
            } finally {
                MemoryUtil.memFree(tmp);
            }
        }
    }
}