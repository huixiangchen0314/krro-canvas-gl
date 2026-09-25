package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.DownloadableTile;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.PixelCodec;
import top.kzre.krro.canvas.gl.resource.PixelFormat;
import top.kzre.krro.core.util.AsyncExecutor;
import top.kzre.krro.util.tile.AbstractTileData;
import top.kzre.krro.util.tile.TileData;
import top.kzre.krro.util.tile.TiledCanvas;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 瓦片纹理视图的抽象基类。
 *
 * <p><b>通用契约</b>：把一张 {@link GLTexture} 的某段层范围暴露为
 * 一个 {@link TiledCanvas}。每个瓦片视图的引用计数归零时递减活跃
 * 计数；全部归零时调 {@link #onAllViewsReleased()}。
 *
 * <p><b>子类职责</b>：
 * <ul>
 *   <li>构造时调 {@link #populateCanvas(int, int)} 注入层范围的视图</li>
 *   <li>实现 {@link #onAllViewsReleased()} 定义归零时的动作——
 *       完整纹理归还给池，层范围视图调用借用回调</li>
 * </ul>
 *
 * <p><b>线程契约</b>：{@code bind} 涉及 GL 调用，必须在 GL 线程。
 * 其他方法（访问器）任意线程可读。视图归零的触发可能在任意线程，
 * 由 {@code AbstractTileData} 的引用计数机制驱动。
 */
public abstract class AbstractGLTiledTexture implements GLBindable {

    protected final GLTexture      texture;
    protected final TiledCanvas    canvas;
    protected final AsyncExecutor  glExecutor;

    /** 活跃视图计数。归零时触发 onAllViewsReleased。 */
    private final AtomicInteger activeTiles = new AtomicInteger(0);

    /** 当前纹理单元。-1 表示尚未绑定。 */
    protected volatile int unit = -1;

    /** 是否已释放（视图计数归零后自动置位）。 */
    protected volatile boolean released = false;

    protected AbstractGLTiledTexture(GLTexture texture, int tileSize,
                                     AsyncExecutor glExecutor) {
        if (texture == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        if (tileSize < 1) {
            throw new IllegalArgumentException("tileSize must be >= 1: " + tileSize);
        }
        if (glExecutor == null) {
            throw new IllegalArgumentException("glExecutor must not be null");
        }
        if (texture.getWidth() % tileSize != 0) {
            throw new IllegalArgumentException(
                    "texture width " + texture.getWidth()
                            + " must be a multiple of tileSize " + tileSize);
        }
        if (texture.getHeight() % tileSize != 0) {
            throw new IllegalArgumentException(
                    "texture height " + texture.getHeight()
                            + " must be a multiple of tileSize " + tileSize);
        }

        int channels = PixelCodec.channels(texture.getPixelFormat());
        float[] defaultPixel = new float[channels];   // 全零

        this.texture    = texture;
        this.glExecutor = glExecutor;
        this.canvas     = new TiledCanvas(tileSize, defaultPixel);
    }
    /**
     * 每行瓦片数——X 方向。
     */
    int tilesPerRow() {
        return texture.getWidth() / canvas.getTileSize();
    }

    /**
     * 每列瓦片数——Y 方向。
     */
    int tilesPerColumn() {
        return texture.getHeight() / canvas.getTileSize();
    }


    /**
     * 注入层范围的视图。子类构造时调用。
     *
     * @param layerOffset 起始层（含），全局索引
     * @param layerCount  层数
     */
    protected final void populateCanvas(int layerOffset, int layerCount) {
        int tilesPerRow    = tilesPerRow();
        int tilesPerColumn = tilesPerColumn();

        for (int i = 0; i < layerCount; i++) {
            int layer = layerOffset + i;
            for (int sy = 0; sy < tilesPerColumn; sy++) {
                for (int sx = 0; sx < tilesPerRow; sx++) {
                    int tx = sx;
                    int ty = layer * tilesPerColumn + sy;
                    activeTiles.incrementAndGet();
                    GLTextureTileDataImpl view =
                            new GLTextureTileDataImpl(this, layer, sx, sy);
                    canvas.replaceTile(tx, ty, view);
                }
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 对外接口
    // ═══════════════════════════════════════════════

    /**
     * 以 {@link TiledCanvas} 形式访问瓦片容器。
     *
     * <p>清空 canvas 会让所有视图引用归零，触发
     * {@link #onAllViewsReleased()}。
     */
    public TiledCanvas asCanvas() { return canvas; }

    @Override
    public void bind(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException("unit must be >= 0, got " + unit);
        }
        if (released) {
            throw new IllegalStateException("view has been released");
        }
        this.unit = unit;
        this.texture.bind(unit);
    }

    /** 当前纹理单元。-1 表示尚未绑定。 */
    public int getUnit() { return unit; }

    /** 是否已释放（视图计数归零后自动置位）。 */
    public boolean isReleased() { return released; }

    // ═══════════════════════════════════════════════
    // 内部——供 GLTextureTileDataImpl 访问
    // ═══════════════════════════════════════════════

    GLTexture texture() { return texture; }
    int tileSize()      { return canvas.getTileSize(); }

    /**
     * 视图引用归零时调用——递减计数。计数归零时触发
     * {@link #onAllViewsReleased()}。
     */
    private void decrementActiveTile() {
        int remaining = activeTiles.decrementAndGet();

        if (remaining < 0) {
            throw new IllegalStateException(
                    "activeTiles went negative: " + remaining + " — double release");
        }
        if (remaining == 0 && !released) {
            released = true;
            onAllViewsReleased();
        }
    }

    /**
     * 所有视图归零时的动作。子类实现。
     *
     * <p>完整纹理归零时归还底层纹理；层范围视图归零时通知借用方。
     */
    protected abstract void onAllViewsReleased();

    // ═══════════════════════════════════════════════
    // 视图类型
    // ═══════════════════════════════════════════════

    /** 能查询 owner 的能力接口。 */
    public interface GLTextureTileData {
        AbstractGLTiledTexture getOwner();
    }

    /** 只读的瓦片视图。关联到 owner，参与 owner 的活跃计数。 */
    public static final class GLTextureTileDataImpl extends AbstractTileData
            implements GLTile, GLTextureTileData, DownloadableTile {

        private final AbstractGLTiledTexture owner;
        private final int layer;
        private final int sx, sy;

        GLTextureTileDataImpl(AbstractGLTiledTexture owner,
                              int layer, int sx, int sy) {
            this.owner = owner;
            this.layer = layer;
            this.sx    = sx;
            this.sy    = sy;
        }

        @Override
        public AbstractGLTiledTexture getOwner() { return owner; }

        @Override
        public GLTileDescriptor getDescriptor() {
            int unit = owner.getUnit();
            if (unit < 0) {
                throw new IllegalStateException(
                        "unit not assigned; call bind(n) first");
            }
            int tilesPerRow    = owner.tilesPerRow();
            int tilesPerColumn = owner.tilesPerColumn();
            return GLTileDescriptor.of(
                    layer, owner.tileSize(),
                    (float) sx / tilesPerRow,     // u0
                    (float) sy / tilesPerColumn); // v0
        }

        @Override
        public CompletableFuture<Void> downloadTo(TiledCanvas target, int tx, int ty) {
            int ts = owner.tileSize();
            int targetTs = target.getTileSize();
            if (ts != targetTs) {
                throw new IllegalArgumentException(
                        "tile size mismatch: source=" + ts + ", target=" + targetTs);
            }

            PixelFormat fmt = owner.texture().getPixelFormat();
            int channels = PixelCodec.channels(fmt);
            int targetChannels = target.getChannels();
            if (channels != targetChannels) {
                throw new IllegalArgumentException(
                        "channel mismatch: format=" + channels
                                + ", canvas=" + targetChannels
                                + ", fmt=" + fmt);
            }

            int pixelCount = ts * ts;

            return owner.glExecutor.submit(() -> {
                ByteBuffer packed = null;
                ByteBuffer out = null;
                try {
                    packed = owner.texture().downloadRegion(
                            layer, sx * ts, sy * ts, ts, ts);

                    int needFloats = PixelCodec.cpuFloats(fmt, pixelCount);
                    out = MemoryUtil.memAlloc(needFloats * Float.BYTES);
                    FloatBuffer dst = out.asFloatBuffer();
                    PixelCodec.unpack(fmt, packed, pixelCount, dst);
                    dst.flip();

                    target.replaceTile(tx, ty, out);
                    out = null;

                    return null;
                } finally {
                    if (packed != null) MemoryUtil.memFree(packed);
                    if (out != null) MemoryUtil.memFree(out);
                }
            });
        }

        @Override
        protected void onRelease() {
            owner.decrementActiveTile();
        }

        @Override
        public int getByteSize() {
            PixelFormat fmt = owner.texture().getPixelFormat();
            int ts = owner.tileSize();
            return ts * ts * fmt.internalBytesPerPixel();
        }

        @Override public float[]    getPixels()    { throw new UnsupportedOperationException("GPU-only"); }
        @Override public FloatBuffer floatBuffer() { throw new UnsupportedOperationException("GPU-only"); }
        @Override public TileData copy()        { throw new UnsupportedOperationException("cannot be copied"); }
    }
}