package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.util.tile.DirectTileData;
import top.kzre.krro.util.tile.HeapTileData;
import top.kzre.krro.util.tile.MappedTileData;
import top.kzre.krro.util.tile.TileData;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.BitSet;

public final class GLAtlas {
    /** 私有锁，避免外部用 `synchronized (atlas)` 干扰。 */
    private final Object lock = new Object();

    private final GLTexture texture;       // ← 新增：GPU 存储
    private final int capacity;            // = texture.layers()
    private final int tileSize;            // = texture.width()
    private final BitSet allocated;
    private final int unit;
    private boolean released;

    public GLAtlas(int unit, GLTexture texture) {
        this.texture    = texture;
        this.capacity   = texture.layers();
        this.tileSize   = texture.width();
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

    /** 当前是否所有层都已分配。 */
    public boolean isFull() {
        return allocated.cardinality() == capacity;
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

        // 分配层（唯一需要锁的段）
        int layer;
        synchronized (lock) {
            layer = allocated.nextClearBit(0);
            if (layer >= capacity) {
                throw new IllegalStateException("GLAtlas full: " + capacity);
            }
            allocated.set(layer);
        }
        return new GLTileData(peer, new Handle(layer));
    }

    private void freeLayer(int layer) {
        synchronized (lock) {
            if (layer < 0 || layer >= capacity || !allocated.get(layer)) {
                throw new IllegalStateException("layer " + layer + " not allocated");
            }
            allocated.clear(layer);
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
     * @see  DirectTileData
     * @see  HeapTileData
     * @see  MappedTileData
     */
    public static final class GLTileData implements TileData, GLTile {
        // 对应的CPU 数据/ 可能是 DirectTileData/HeapTileData 或者是SwapTileData
        private final TileData peer;
        // 瓦片数据COW保证，无需式同步，保证可见性即可
        private volatile boolean dirty;
        private final GLTileDescriptor descriptor;

        private final GLAtlas.Handle handle;

        private GLTileData(TileData peer, GLAtlas.Handle handle) {
            this.peer = peer;
            this.handle = handle;
            // 默认是没有上传到显存的
            dirty = true;
            descriptor = GLTileDescriptor.of(handle.getUnit(), handle.getLayer(), handle.getTileSize());
        }

        /**
         * COW 单写，volatile 保证可见性
         */
        @Override
        public void markDirty(){
            this.dirty = true;
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
            // volatile 读，无需锁
            if (dirty){
                upload();
                dirty = false;
            }
        }
    }

}
