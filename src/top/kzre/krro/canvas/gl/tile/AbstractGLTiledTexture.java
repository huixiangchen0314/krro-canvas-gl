package top.kzre.krro.canvas.gl.tile;

import org.lwjgl.system.MemoryUtil;
import top.kzre.krro.canvas.core.layer.render.DownloadableTile;
import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.PixelCodec;
import top.kzre.krro.canvas.gl.resource.PixelFormat;
import top.kzre.krro.core.util.AsyncExecutor;
import top.kzre.krro.core.util.DEBUG;
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

        // TODO FIX 宽度编码。
        for (int i = 0; i < layerCount; i++) {
            int layer = layerOffset + i;
            for (int canvasRow = 0; canvasRow < tilesPerColumn; canvasRow++) {
                for (int canvasCol = 0; canvasCol < tilesPerRow; canvasCol++) {
                    int ty = layer * tilesPerColumn + canvasRow;
                    activeTiles.incrementAndGet();
                    int finalCanvasColumn = canvasCol;
                    int finalCanvasRow = canvasRow;
                    canvas.replaceTile(canvasCol, ty, ()->new GLTextureTileDataImpl(this, layer, finalCanvasColumn, finalCanvasRow));
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

    /**
     * 只读的瓦片视图。关联到 owner，参与 owner 的活跃计数。
     *
     * <h2>canvasColumn / canvasRow 的语义</h2>
     *
     * <p>这两个值描述"这块瓦片位于 canvas 网格的第几列、第几行"——
     * 是 <b>canvas 逻辑网格坐标</b>，<b>带方向语义</b>：{@code canvasRow = 0}
     * 是 canvas 的<b>顶行</b>，y 向下增长。
     *
     * <p>它和屏幕坐标系一致——canvas 网格、脏区、tileTable 里的
     * {@code tileX/tileY} 全都用这一套（y 向下）。合成时 shader 里的
     * {@code localUv.y} 也按这个方向增长。
     *
     * <h2>与 GL 纹理坐标的区别</h2>
     *
     * <p>GL 纹理原点在左下，v 向上——{@code glTexSubImage3D} /
     * {@code glReadPixels} 的 {@code y} 参数是 GL 坐标。
     *
     * <p>本类的 {@code canvasRow} <b>不能直接</b>乘 {@code tileSize} 传给
     * GL：
     * <ul>
     *   <li>{@code downloadTo} 里——{@code glReadPixels} 期望 GL 坐标，
     *       需要 {code glRow = tilesPerColumn - 1 - canvasRow}</li>
     *   <li>{@code getDescriptor} 里——{@code v0} 若是 GL 纹理 uv
     *       起点，同样需要翻</li>
     * </ul>
     *
     * <p>对比 {@code GLAtlas.Handle} 的 {@code dataColumn / dataRow}——
     * 那是 atlas 内部槽位坐标，<b>无方向语义</b>，直接传给 GL 不翻。
     * 本类是 canvas 网格坐标，<b>带方向</b>，跨到 GL 时要翻。
     *
     * <h2>字段命名</h2>
     *
     * <p>用 {@code canvasColumn / canvasRow} 而非裸 {@code column / row}——
     * 提示读代码的人"这是 canvas 网格坐标，不是 atlas 槽位，也不是 GL
     * 纹理坐标"。跨坐标系转换点必须显式翻转，不能沿用同一个名字。
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
        public GLTileDescriptor getDescriptor() {
            int unit = owner.getUnit();
            if (unit < 0) {
                throw new IllegalStateException(
                        "unit not assigned; call bind(n) first");
            }
            int tilesPerRow    = owner.tilesPerRow();
            int tilesPerColumn = owner.tilesPerColumn();

            // canvasRow 是 canvas 网格行（y 向下）；
            // v0 是 GL 纹理 v 起点（y 向上）——翻
            int glRow = glRow();

            return GLTileDescriptor.of(
                    layer, owner.tileSize(),
                    (float) canvasColumn / tilesPerRow,       // u0 —— 两套 x 一致
                    (float) glRow        / tilesPerColumn);   // v0 —— 翻
        }

        @Override
        public CompletableFuture<Void> downloadTo(TiledCanvas target, int tx, int ty) {
            int refCount = refCount();
            if (refCount <= 0) {
                throw new IllegalStateException("GLTextureTileDataImpl already released, refCount=" + refCount);
            }
            System.out.println("GLTextureTileDataImpl.downloadTo, refCount=" + refCount);
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

            return owner.glExecutor.submit(() -> {
                ByteBuffer packed = null;
                ByteBuffer out = null;
                try {
                    int glRow = glRow();
                    packed = owner.texture().downloadRegion(
                            layer, canvasColumn * tileSize, glRow * tileSize, tileSize, tileSize);

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

        private int glRow() {
            // canvas row → GL row
            return owner.tilesPerColumn() - 1 - canvasRow;
        }

        @Override
        protected void onRelease() {
            System.out.println("GLTextureTileDataImpl.onRelease, refCount="+ refCount()+" \n"
                            + DEBUG.stack());
            owner.decrementActiveTile();
        }

        @Override
        public int acquire() {
            System.out.println("GLTextureTileDataImpl.acquire, refCount="+ refCount()+" \n"
            + DEBUG.stack());

            return super.acquire();
        }

        @Override
        public int release() {
            System.out.println("GLTextureTileDataImpl.release, refCount="+ refCount()+" \n"
                    + DEBUG.stack());
            return super.release();
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