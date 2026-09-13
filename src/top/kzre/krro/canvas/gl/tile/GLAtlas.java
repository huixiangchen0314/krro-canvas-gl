package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.util.tile.DirectTileData;
import top.kzre.krro.util.tile.HeapTileData;
import top.kzre.krro.util.tile.MappedTileData;
import top.kzre.krro.util.tile.TileData;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GLAtlas {
    /** 私有锁，避免外部用 `synchronized (atlas)` 干扰。 */
    private final Object lock = new Object();

    private final GLTexture texture;       // ← 新增：GPU 存储
    private final int capacity;            // = texture.layers()
    private final int tileSize;            // = texture.width()
    private final BitSet allocated;
    private final int unit;
    private boolean released;
    private final GLTileData[] tiles;

    public GLAtlas(int unit, GLTexture texture) {
        this.texture    = texture;
        this.capacity   = texture.getLayers();
        this.tiles = new GLTileData[capacity];
        this.tileSize   = texture.getWidth();
        this.unit = unit;
        this.allocated  = new BitSet(capacity);
        released    = false;
    }


    public static GLAtlas createRgba8(int unit, int tileSize, int capacity) {
        return new GLAtlas(unit, GLTexture.createRgba8(tileSize, tileSize, capacity));
    }

    public int getTileSize() {
        return tileSize;
    }
    public int getCapacity() {
        return capacity;
    }


    /** 包级私有——供 move 在目标 atlas 上注册。 */
    void registerTile(int layer, GLTileData tile) {
        synchronized (lock) {
            tiles[layer] = tile;
        }
    }


    /**
     * 释放底层纹理。必须在 GL 线程上调用。幂等。
     * 释放后所有已分配的 Handle 失效。
     */
    public void release() {
        if (released) return;
        texture.release();
        allocated.clear();
        released = true;
    }



    /** 分配一个层，返回 Handle。不含 peer 绑定逻辑。 */
    private Handle allocateHandle() {
        int layer;
        synchronized (lock) {
            layer = allocated.nextClearBit(0);
            if (layer >= capacity) {
                throw new IllegalStateException("GLAtlas full: " + capacity);
            }
            allocated.set(layer);
        }
        return new Handle(layer);
    }

    /**
     * 把 tile 从 src 移到 dst。
     *
     * <p><b>语义</b>：
     * <ol>
     *   <li>在 dst 上分配一个新层</li>
     *   <li>原地更新 tile 的 handle 和 descriptor，指向新层</li>
     *   <li>标记 tile 为脏——下次 {@link GLTileData#ensureUploaded()}
     *       会从 peer 重新上传到新层</li>
     *   <li>释放 src 上的旧层</li>
     * </ol>
     *
     * <p><b>不立即上传</b>——数据仍由 peer 持有，新层的显存内容在下次
     * 上传时填充。这与 tile 的生命周期模型一致：脏标记延迟到使用时刻。
     *
     * <p><b>所有权</b>：tile 实例不变，调用方持有的引用继续有效。
     * tile 的 peer 引用不变。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     *
     * @return 成功返回 true；dst 已满返回 false
     * @throws IllegalArgumentException src == dst
     * @throws IllegalStateException    tile 不属于 src、或任一 atlas 已释放
     */
    public static boolean move(GLAtlas dst, GLAtlas src, GLTileData tile) {
        if (dst == src) {
            throw new IllegalArgumentException("dst and src must differ");
        }
        if (tile.handle.getGLAtlas() != src) {
            throw new IllegalStateException(
                    "tile does not belong to src atlas (unit=" + src.unit + ")");
        }

        Handle newHandle;
        try {
            newHandle = dst.allocateHandle();
        } catch (IllegalStateException e) {
            return false;
        }

        Handle oldHandle = tile.handle;
        tile.rebind(newHandle);
        dst.registerTile(newHandle.getLayer(), tile);   // ← 在目标注册
        oldHandle.free();                               // 释放源层（会清 src.tiles[layer]）

        return true;
    }

    /**
     * 分配一个层，<b>独占转移</b> peer 的所有权。
     *
     * <p><b>幂等</b>：若 peer 已经是绑定到本 atlas 的 {@link GLTileData}，
     * 直接返回它，不再分配新层。
     *
     * <p><b>跨 atlas 防护</b>：若 peer 是绑定到<b>其他</b> atlas 的
     * {@link GLTileData}，抛异常——一块数据不能同时绑定到两个 atlas。
     *
     * <p><b>独占前置条件</b>：非 GLTileData 的 peer 必须是独占状态
     * （{@code refCount == 1}）。
     *
     * @throws IllegalStateException atlas 已满、peer 跨 atlas、或 peer 非独占
     */

    public GLTileData allocate(TileData peer) {
        // 幂等 / 跨 atlas 防护（只读，无需锁）
        if (peer instanceof GLTile) {
            if (peer instanceof GLTileData) {
                GLTileData existing = (GLTileData) peer;
                if (existing.handle.getGLAtlas() == this) {
                    return existing;
                }
                throw new IllegalStateException(
                        "peer is a GLTileData bound to a different atlas; ...");
            }
            throw new IllegalStateException(
                    "peer is already a GPU-backed tile (class = "
                            + peer.getClass().getName() + "), ...");
        }

        // 独占前置条件（只读 peer，无需锁）
        int rc = peer.refCount();
        if (rc != 1) {
            throw new IllegalStateException(
                    "peer must be exclusively owned (refCount == 1) for transfer, ...");
        }

        Handle handle = allocateHandle();
        GLTileData tile = new GLTileData(peer, handle);
        registerTile(handle.getLayer(), tile);
        return tile;
    }

    private void freeLayer(int layer) {
        synchronized (lock) {
            if (layer < 0 || layer >= capacity || !allocated.get(layer)) {
                throw new IllegalStateException("layer " + layer + " not allocated");
            }
            // 因为freeLayer可能在 外部线程被调用，导致所有地方都要加锁
            allocated.clear(layer);
            tiles[layer] = null;
        }
    }

    /** 当前所有活跃 tile 的快照。 */
    public List<GLTileData> getActiveTiles() {
        List<GLTileData> result = new ArrayList<>();
        synchronized (lock) {
            for (int layer = allocated.nextSetBit(0);
                 layer >= 0;
                 layer = allocated.nextSetBit(layer + 1)) {
                GLTileData tile = tiles[layer];
                if (tile != null) {
                    result.add(tile);
                }
            }
        }
        return result;
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

    /**
     * 分块句柄，和每个 Atlas 绑定
     */
    private final class Handle {
        private final int layer;
        private boolean valid = true;

        Handle(int layer) { this.layer = layer; }

        int getLayer() { return layer; }
        int getTileSize(){
            return tileSize;
        }

        // 同步改，无其他地方读，COW 接口保证独占
        void free() {
            if (!valid) return;
            valid = false;
            freeLayer(layer);
        }

        int getUnit(){
            return unit;
        }

        GLAtlas getGLAtlas(){
            return GLAtlas.this;
        }

        void upload(ByteBuffer buffer) {
            texture.uploadLayer(layer, buffer);
        }
    }

    /**
     * GL 瓦片：TileData 的 GPU 装饰。
     *
     * <p><b>线程契约</b>：
     * <ul>
     *   <li>{@link #markDirty()} —— 任意线程</li>
     *   <li>{@link #ensureUploaded()} / {@link #getDescriptor()} /
     *       {@link #release()} —— 必须在 GL 线程上调用</li>
     *   <li>其他 {@link TileData} 代理方法 —— 由 peer 的契约决定</li>
     * </ul>
     *
     * <p>内部字段 {@code handle} / {@code descriptor} 不做同步——它们的
     * 访问完全落在 GL 线程内，由上述契约保证。违反契约会产生数据竞争，
     * 但按契约调用是安全的。
     */
    public static final class GLTileData implements TileData, GLTile {
        // 对应的CPU 数据/ 可能是 DirectTileData/HeapTileData 或者是SwapTileData
        private final TileData peer;
        /** 跨线程访问：CPU 线程 markDirty，GL 线程 ensureUploaded 清。 */
        private final AtomicBoolean dirty = new AtomicBoolean(true);
        private GLTileDescriptor descriptor;
        private GLAtlas.Handle handle;

        private GLTileData(TileData peer, GLAtlas.Handle handle) {
            this.peer = peer;
            this.handle = handle;
            descriptor = GLTileDescriptor.of(handle.getUnit(), handle.getLayer(), handle.getTileSize());
        }

        /**
         * 重新绑定到新层。只在 GL 线程调用。
         */
        void rebind(Handle newHandle) {
            this.handle = newHandle;
            this.descriptor = GLTileDescriptor.of(
                    newHandle.getUnit(), newHandle.getLayer(), newHandle.getTileSize());
            this.dirty.set(true);    // 新层是空的，必须重传
        }


        @Override
        public void markDirty() {
            this.dirty.set(true);
        }

        @Override
        public float[] getPixels() {
            return peer.getPixels();
        }

        @Override
        public FloatBuffer floatBuffer() {
            return peer.floatBuffer();
        }

        @Override
        public int acquire() {
            return peer.acquire();
        }

        @Override
        public int release() {
            int count = peer.release();
            if (count == 0) {
                handle.free();
            }
            return count;
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

        private void upload() {
            if (peer instanceof DirectTileData) {
                DirectTileData d = (DirectTileData) peer;
                handle.upload(d.buffer());
            } else if (peer instanceof MappedTileData) {
                MappedTileData s = (MappedTileData) peer;
                handle.upload(s.mapping());
            } else if (peer instanceof HeapTileData) {
                HeapTileData h = (HeapTileData) peer;
                ByteBuffer tmp = MemoryUtil.memAlloc(peer.getByteSize());
                try {
                    tmp.asFloatBuffer().put(h.floatBuffer());
                    handle.upload(tmp);
                } finally {
                    MemoryUtil.memFree(tmp);
                }
            } else {
                throw new UnsupportedOperationException(
                        "unsupported peer type: " + peer.getClass().getName());
            }
        }

        @Override
        public GLTileDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public void ensureUploaded() {
            if (dirty.compareAndSet(true, false)) {
                upload();
            }
        }
    }

}
