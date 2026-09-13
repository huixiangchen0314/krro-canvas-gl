package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.util.tile.DirectTileData;
import top.kzre.krro.util.tile.HeapTileData;
import top.kzre.krro.util.tile.SwapTileData;
import top.kzre.krro.util.tile.TileData;

import java.util.BitSet;

public final class GLAtlas {

    private final GLTexture texture;       // ← 新增：GPU 存储
    private final int capacity;            // = texture.layers()
    private final int tileSize;            // = texture.width()
    private final BitSet allocated;

    public GLAtlas(GLTexture texture) {
        this.texture    = texture;
        this.capacity   = texture.layers();
        this.tileSize   = texture.width();
        this.allocated  = new BitSet(capacity);
    }


    public static GLAtlas createRgba8(int tileSize, int capacity) {
        return new GLAtlas(GLTexture.createRgba8(tileSize, tileSize, capacity));
    }

    public int getTileSize() {
        return tileSize;
    }
    public int getCapacity() {
        return capacity;
    }


    /** 分配一个层，包装 peer 成 GLTileData。 */
    public GLTileData allocateTile(TileData peer) {
        int layer = allocated.nextClearBit(0);
        if (layer >= capacity) {
            throw new IllegalStateException("GLAtlas full: " + capacity);
        }
        allocated.set(layer);
        return new GLTileData(peer, new Handle(layer));
    }

    private void freeLayer(int layer) {
        if (layer < 0 || layer >= capacity || !allocated.get(layer)) {
            throw new IllegalStateException("layer " + layer + " not allocated");
        }
        allocated.clear(layer);
    }


    /**
     * 分块句柄，和每个 Atlas 绑定
     */
    private final class Handle {
        private final int layer;
        private boolean valid = true;

        Handle(int layer) { this.layer = layer; }

        int getLayer() { return layer; }

        void free() {
            if (!valid) return;
            valid = false;
            freeLayer(layer);
        }
    }



    /**
     * @see  DirectTileData
     * @see  HeapTileData
     * @see  SwapTileData
     */
    public static final class GLTileData implements TileData {
        // 对应的CPU 数据/ 可能是 DirectTileData/HeapTileData 或者是SwapTileData
        private final TileData peer;
        // 瓦片数据COW保证，无需显示同步，保证可见性即可
        private volatile boolean dirty;

        private final GLAtlas.Handle handle;

        private GLTileData(TileData peer, GLAtlas.Handle handle) {
            this.peer = peer;
            this.handle = handle;
        }

        public boolean isDirty(){
            return dirty;
        }

        @Override
        public void markDirty(){
            this.dirty = true;
        }

        @Override
        public float[] getPixels() {
            return peer.getPixels();
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

    }

}
