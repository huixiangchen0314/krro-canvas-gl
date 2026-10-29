package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.DownloadableTile;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.PixelCodec;
import top.kzre.krro.canvas.gl.resource.PixelFormat;
import top.kzre.krro.canvas.gl.util.BufferUtils;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.*;

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
 * <p><b>非整除尺寸</b>：纹理尺寸不要求是 tileSize 的整数倍——
 * 例如 1920×1080 配 64px 瓦片时 {@code tilesPerRow=30}、
 * {@code tilesPerColumn=17}（覆盖到 1088，超出部分留空）。网格
 * 计算用向上取整。
 *
 * <p><b>线程契约</b>：{@code bind} 涉及 GL 调用，必须在 GL 线程。
 * 其他方法（访问器）任意线程可读。视图归零的触发可能在任意线程，
 * 由 {@code AbstractTileData} 的引用计数机制驱动。
 */
public abstract class AbstractGLTiledTexture implements GLBindable {

    protected final GLTexture       texture;
    protected final TiledCanvas     canvas;
    protected final SerialExecutor  glExecutor;

    /** 纹理布局——构造时确定，不可变。 */
    private final GLTiledTextureLayout layout;

    /** 活跃视图计数。归零时触发 onAllViewsReleased。 */
    private final AtomicInteger activeTiles = new AtomicInteger(0);

    /** 是否已释放（视图计数归零后自动置位）。 */
    protected volatile boolean released = false;

    protected AbstractGLTiledTexture(GLTexture texture, int tileSize,
                                     SerialExecutor glExecutor) {
        if (texture == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        if (tileSize < 1) {
            throw new IllegalArgumentException("tileSize must be >= 1: " + tileSize);
        }
        if (glExecutor == null) {
            throw new IllegalArgumentException("glExecutor must not be null");
        }

        int channels = PixelCodec.channels(texture.getPixelFormat());
        float[] defaultPixel = new float[channels];   // 全零

        this.texture    = texture;
        this.glExecutor = glExecutor;
        this.canvas     = new TiledCanvas(tileSize, defaultPixel);
        this.layout     = GLTiledTextureLayout.fromPixelSize(
                texture.getLayers(),
                tileSize,
                texture.getWidth(),
                texture.getHeight());
    }

    /**
     * 注入层范围的视图。子类构造时调用。
     *
     * @param layerOffset 起始层（含），全局索引
     * @param layerCount  层数
     */
    protected final void populateCanvas(int layerOffset, int layerCount) {
        int tilesPerRow    = layout.getTilesPerRow();
        int tilesPerColumn = layout.getTilesPerColumn();

        // TODO FIX 宽度编码。
        for (int i = 0; i < layerCount; i++) {
            int layer = layerOffset + i;
            for (int canvasRow = 0; canvasRow < tilesPerColumn; canvasRow++) {
                for (int canvasCol = 0; canvasCol < tilesPerRow; canvasCol++) {
                    int ty = i * tilesPerColumn + canvasRow;
                    activeTiles.incrementAndGet();
                    int finalCanvasColumn = canvasCol;
                    int finalCanvasRow = canvasRow;
                    canvas.replaceTile(canvasCol, ty,
                            () -> new GLTextureTileDataImpl(this, layer,
                                    finalCanvasColumn, finalCanvasRow));
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

    /** 纹理布局。 */
    public GLTiledTextureLayout getLayout() { return layout; }

    @Override
    public void bind(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException("unit must be >= 0, got " + unit);
        }
        if (released) {
            throw new IllegalStateException("view has been released");
        }
        this.texture.bind(unit);
    }

    /** 当前纹理单元。-1 表示尚未绑定。 */
    public int getUnit() { return texture.getUnit(); }

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
     */
    protected abstract void onAllViewsReleased();

    // ═══════════════════════════════════════════════
    // 视图类型
    // ═══════════════════════════════════════════════

    /** 能查询 owner 的能力接口。 */
    public interface GLTextureTileData {
        AbstractGLTiledTexture getOwner();
        GLTexture getTexture();
        int getLayer();
        int getRow();
        int getColumn();
    }

    /**
     * 只读的瓦片视图。关联到 owner，参与 owner 的活跃计数。
     *
     * <h2>canvasColumn / canvasRow 的语义</h2>
     *
     * <p>这两个值描述"这块瓦片位于 canvas 网格的第几列、第几行"——
     * 是 <b>canvas 逻辑网格坐标</b>，<b>带方向语义</b>：{@code canvasRow = 0}
     * 是 canvas 的<b>顶行</b>，y 向下增长。
     *
     * <p>GL 纹理原点在左下，v 向上。跨到 GL 需要翻一次。
     *
     * <h2>线程契约</h2>
     *
     * <p>字段 immutable——构造后不变。读取任意线程安全。
     */
    public static final class GLTextureTileDataImpl extends AbstractTileData
            implements GLTile, GLTextureTileData, DownloadableTile {

        private final AbstractGLTiledTexture owner;
        private final int layer;
        private final int canvasColumn;
        private final int canvasRow;

        GLTextureTileDataImpl(AbstractGLTiledTexture owner,
                              int layer, int canvasColumn, int canvasRow) {
            this.owner = owner;
            this.layer = layer;
            this.canvasColumn = canvasColumn;
            this.canvasRow = canvasRow;
        }

        @Override
        public AbstractGLTiledTexture getOwner() { return owner; }

        @Override
        public GLTexture getTexture() {
            return owner.texture;
        }

        @Override
        public int getLayer() {
            return layer;
        }

        @Override
        public int getRow() {
            return glRow();
        }

        @Override
        public int getColumn() {
            return canvasColumn;
        }

        @Override
        public GLTileDescriptor getDescriptor() {
            int unit = owner.texture.getUnit();
            if (unit < 0) {
                throw new IllegalStateException(
                        "unit not assigned; call bind(n) first");
            }
            GLTiledTextureLayout layout = owner.getLayout();
            int tilesPerRow    = layout.getTilesPerRow();
            int tilesPerColumn = layout.getTilesPerColumn();

            // canvasRow 是 canvas 网格行（y 向下）；
            // v0 是 GL 纹理 v 起点（y 向上）——翻
            int glRow = glRow();

            return GLTileDescriptor.of(
                    unit,
                    layer,
                    owner.tileSize(),
                    (float) canvasColumn / tilesPerRow,   // u0 —— x 方向不翻
                    (float) glRow / tilesPerColumn); // v0 —— y 方向翻
        }

        @Override
        public CompletableFuture<Void> downloadTo(TiledCanvas target, int tx, int ty) {
            long beginTime = System.nanoTime();
            int refCount = refCount();
            if (refCount <= 0) {
                throw new IllegalStateException(
                        "GLTextureTileDataImpl already released, refCount=" + refCount);
            }
            int tileSize = owner.tileSize();
            int targetTs = target.getTileSize();
            if (tileSize != targetTs) {
                throw new IllegalArgumentException(
                        "tile size mismatch: source=" + tileSize + ", target=" + targetTs);
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

            int pixelCount = tileSize * tileSize;
            int glRow = glRow();

            // ── GL 线程：只做 readback ──
            return ExecutorUtils.runOnThread(
                            owner.glExecutor,
                            () -> {
                                long downloadBegin = System.nanoTime();
                                ByteBuffer result = owner.texture().downloadRegion(
                                        layer,
                                        canvasColumn * tileSize,
                                        glRow * tileSize,
                                        tileSize, tileSize);
                                long downloadEnd = System.nanoTime();
                                System.out.printf("downloadTo: %.3fms%n", (downloadEnd - downloadBegin) / 1_000_000.0);
                                return result;
                            })
                    // ── CPU 线程：flip / unpack / replaceTile ──
                    .thenApplyAsync(packed -> {
                        long alreadyDownloadTime = System.nanoTime();
                        try {
                            // glReadPixels 逐行从低 y 到高 y——输出第一行是瓦片底行；
                            // canvas 期望第一行是瓦片顶行——翻一次行序
                            BufferUtils.flipRows(
                                    packed, tileSize,
                                    tileSize * fmt.internalBytesPerPixel());
                            long alreadyFlipRowsTime = System.nanoTime();
                            int needFloats = PixelCodec.cpuFloats(fmt, pixelCount);
                            ByteBuffer out = MemoryUtil.memAlloc(needFloats * Float.BYTES);
                            try {
                                FloatBuffer dst = out.asFloatBuffer();
                                PixelCodec.unpackPreAlpha(fmt, packed, pixelCount, dst);
                                dst.flip();
                                long alreadyUnpackTime = System.nanoTime();
                                target.replaceTile(tx, ty, out);
                                out = null;   // 所有权交给 target

                                System.out.printf(
                                        "[download] total=%.3fms | download=%.1f%% flipRows=%.1f%% unpack=%.1f%% replaceTile=%.1f%%%n",
                                        (System.nanoTime() - beginTime) / 1_000_000.0,
                                        (alreadyDownloadTime - beginTime) * 100.0 / (System.nanoTime() - beginTime),
                                        (alreadyFlipRowsTime - alreadyDownloadTime) * 100.0 / (System.nanoTime() - beginTime),
                                        (alreadyUnpackTime - alreadyFlipRowsTime) * 100.0 / (System.nanoTime() - beginTime),
                                        (System.nanoTime() - alreadyUnpackTime) * 100.0 / (System.nanoTime() - beginTime));
                            } finally {
                                if (out != null) MemoryUtil.memFree(out);
                            }
                        } finally {
                            MemoryUtil.memFree(packed);
                        }
                        return null;
                    }, DownloadExecutor.get());
        }

        private int glRow() {
            // canvas row → GL row
            return owner.getLayout().getTilesPerColumn() - 1 - canvasRow;
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
        @Override public TileData   copy()         { throw new UnsupportedOperationException("cannot be copied"); }
    }
}