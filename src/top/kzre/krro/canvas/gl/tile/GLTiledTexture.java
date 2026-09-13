package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.PixelFormat;
import top.kzre.krro.util.tile.AbstractTileData;
import top.kzre.krro.util.tile.TileData;
import top.kzre.krro.util.tile.TiledCanvas;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GPU 纹理的 {@link TiledCanvas} 适配。
 *
 * <p>内部创建并持有一个 {@link TiledCanvas}，构造时把所有瓦片区域
 * {@code (layer, sx, sy)} 注册为 canvas 的 tile。每个 tile 的
 * {@link TileData} 是一个只读的 {@link GLTextureTileData} 视图。
 *
 * <h2>生命周期</h2>
 * <p><b>没有显式 close</b>——生命周期完全绑定到 canvas 视图计数：
 * <ul>
 *   <li>构造时注入 N 个视图，{@code activeTiles = N}</li>
 *   <li>每个视图引用归零 → {@code onRelease} → 计数递减</li>
 *   <li>计数归零 → 自动投递 {@code texture.release()} 到 GL 线程</li>
 * </ul>
 *
 * <p>调用方只需清空 canvas（{@code canvas.clear()} 或释放所有视图的
 * 外部引用），纹理自动释放。
 *
 * <h2>像素访问</h2>
 * <p>canvas <b>不设只读标志</b>——但逐像素路径在到达
 * {@link GLTextureTileData#getPixels} / {@link GLTextureTileData#floatBuffer}
 * 时抛 {@link UnsupportedOperationException}——GPU 视图没有 CPU 侧数据。
 * 瓦片级操作（{@code clear} / {@code deleteTile}）不涉及像素访问，安全。
 *
 * <h2>线程契约</h2>
 * 释放动作由 {@code onRelease} 投递到 GL 线程——可在任意线程触发。
 */
public final class GLTiledTexture {

    private final GLTexture texture;
    private final TiledCanvas canvas;
    private final Executor  glExecutor;

    /** 活跃视图计数——归零时自动释放纹理。 */
    private final AtomicInteger activeTiles = new AtomicInteger(0);

    /** 纹理单元。-1 表示尚未绑定。 */
    private int unit = -1;

    /** 是否已释放——归零后为 true，防止重复释放。 */
    private volatile boolean released = false;

    public GLTiledTexture(GLTexture texture, int tileSize, Executor glExecutor) {
        if (texture == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        if (tileSize < 1) {
            throw new IllegalArgumentException("tileSize must be >= 1: " + tileSize);
        }
        if (glExecutor == null) {
            throw new IllegalArgumentException("glExecutor must not be null");
        }
        if (texture.getWidth() != texture.getHeight()) {
            throw new IllegalArgumentException(
                    "texture must be square: "
                            + texture.getWidth() + "x" + texture.getHeight());
        }
        if (texture.getWidth() % tileSize != 0) {
            throw new IllegalArgumentException(
                    "texture size " + texture.getWidth()
                            + " must be a multiple of tileSize " + tileSize);
        }

        this.texture    = texture;
        this.glExecutor = glExecutor;
        this.canvas     = buildTiledCanvas(texture, tileSize);
    }

    private TiledCanvas buildTiledCanvas(GLTexture texture, int tileSize) {
        int gridSize = texture.getWidth() / tileSize;
        int layers   = texture.getLayers();

        TiledCanvas c = new TiledCanvas(tileSize);
        for (int layer = 0; layer < layers; layer++) {
            for (int sy = 0; sy < gridSize; sy++) {
                for (int sx = 0; sx < gridSize; sx++) {
                    int tx = sx;
                    int ty = layer * gridSize + sy;
                    activeTiles.incrementAndGet();
                    GLTextureTileData view = new GLTextureTileData(this, layer, sx, sy);
                    c.replaceTile(tx, ty, view);
                }
            }
        }
        return c;
    }

    // ═══════════════════════════════════════════════
    // 对外接口
    // ═══════════════════════════════════════════════

    /**
     * 以 {@link TiledCanvas} 形式访问本纹理的瓦片容器。
     *
     * <p>清空 canvas（{@code canvas.clear()}）会让所有视图引用归零，
     * 自动触发纹理释放。
     */
    public TiledCanvas asCanvas() { return canvas; }

    /**
     * 绑定到指定纹理单元——设置 unit 并绑定底层纹理。
     *
     * <p><b>有 GL 副作用</b>——必须在 GL 线程上调用。
     *
     * @throws IllegalArgumentException unit &lt; 0
     * @throws IllegalStateException    纹理已释放
     */
    public void bind(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException("unit must be >= 0, got " + unit);
        }
        if (released) {
            throw new IllegalStateException("GLTiledTexture has been released");
        }
        this.unit = unit;
        this.texture.bind(unit);
    }

    /** 当前纹理单元。-1 表示尚未绑定。 */
    public int getUnit() { return unit; }

    /** 是否已释放（视图计数归零后自动置位）。 */
    public boolean isReleased() { return released; }

    // ═══════════════════════════════════════════════
    // 内部——供 GLTextureTileData 访问
    // ═══════════════════════════════════════════════

    GLTexture texture() { return texture; }
    int tileSize()    { return canvas.getTileSize(); }
    int gridSize()    { return texture.getWidth() / canvas.getTileSize(); }

    /**
     * 视图引用归零时调用——递减计数。
     *
     * <p><b>归零时自动释放纹理</b>——投递 {@code texture.release()} 到
     * GL 线程。这是本类唯一的释放触发点——生命周期完全绑定到 canvas
     * 的视图计数。
     *
     * <p>每个视图只会在引用归零时触发一次——计数从 N 到 0 只会发生
     * 一次，释放只投递一次。
     */
   private void decrementActiveTile() {
        int remaining = activeTiles.decrementAndGet();

        if (remaining < 0) {
            throw new IllegalStateException(
                    "activeTiles went negative: " + remaining + " — double release");
        }
        if (remaining == 0 && !released) {
            released = true;
            glExecutor.execute(texture::release);
        }
    }

    // ═══════════════════════════════════════════════
    // 视图类型
    // ═══════════════════════════════════════════════

    public static final class GLTextureTileData extends AbstractTileData
            implements GLDownloadableTile {

        private final GLTiledTexture owner;
        private final int layer;
        private final int sx, sy;

        GLTextureTileData(GLTiledTexture owner, int layer, int sx, int sy) {
            this.owner = owner;
            this.layer = layer;
            this.sx    = sx;
            this.sy    = sy;
        }

        // ── GLTile ────────────────────────────────────

        @Override
        public GLTileDescriptor getDescriptor() {
            int unit = owner.getUnit();
            if (unit < 0) {
                throw new IllegalStateException(
                        "GLTiledTexture unit not assigned; call bind(n) first");
            }
            int grid = owner.gridSize();
            return GLTileDescriptor.of(
                    unit, layer, owner.tileSize(),
                    (float) sx / grid, (float) sy / grid);
        }

        // ── GLDownloadableTile ────────────────────────

        @Override
        public void downloadTo(TiledCanvas target, int tx, int ty) {
            assertRgba8(target);
            int ts = owner.tileSize();

            ByteBuffer packed = owner.texture().downloadRegion(
                    layer, sx * ts, sy * ts, ts, ts);
            try {
                ByteBuffer unpacked = unpackRgba8(packed, ts * ts);
                target.replaceTile(tx, ty, unpacked);
            } finally {
                MemoryUtil.memFree(packed);
            }
        }

        // ── AbstractTileData ──────────────────────────

        @Override
        protected void onRelease() {
            owner.decrementActiveTile();
        }

        @Override
        public int getByteSize() {
            PixelFormat fmt = owner.texture().getPixelFormat();
            int ts = owner.tileSize();
            return ts * ts * fmt.getBytesPerPixel();
        }

        // ── CPU 侧访问：不支持 ───────────────────────

        @Override
        public float[] getPixels() {
            throw new UnsupportedOperationException("GLTextureTileData is GPU-only");
        }

        @Override
        public FloatBuffer floatBuffer() {
            throw new UnsupportedOperationException("GLTextureTileData is GPU-only");
        }

        @Override
        public TileData copy() {
            throw new UnsupportedOperationException(
                    "GLTextureTileData cannot be copied");
        }

        private void assertRgba8(TiledCanvas target) {
            if (owner.texture().getPixelFormat() != PixelFormat.RGBA8) {
                throw new UnsupportedOperationException(
                        "downloadTo only supports RGBA8; got "
                                + owner.texture().getPixelFormat());
            }
            if (target.getChannels() != 4) {
                throw new IllegalStateException(
                        "TiledCanvas.channels must be 4, got " + target.getChannels());
            }
        }

        private static ByteBuffer unpackRgba8(ByteBuffer packed, int pixelCount) {
            ByteBuffer out = MemoryUtil.memAlloc(pixelCount * 4 * Float.BYTES);
            FloatBuffer dst = out.asFloatBuffer();
            ByteBuffer src  = packed.duplicate();
            for (int i = 0; i < pixelCount; i++) {
                int rgba = src.getInt();
                dst.put(((rgba >> 24) & 0xFF) / 255f);
                dst.put(((rgba >> 16) & 0xFF) / 255f);
                dst.put(((rgba >>  8) & 0xFF) / 255f);
                dst.put(( rgba        & 0xFF) / 255f);
            }
            dst.flip();
            return out;
        }


    }
}