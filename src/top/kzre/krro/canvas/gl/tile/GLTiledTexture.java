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

/**
 * GPU 纹理的 {@link TiledCanvas} 适配。
 *
 * <p>内部创建并持有一个<b>只读</b> {@link TiledCanvas}，构造时把所有
 * 瓦片区域 {@code (layer, sx, sy)} 注册为 canvas 的 tile。每个 tile 的
 * {@link TileData} 是一个只读的 {@link GLTextureTileData} 视图。
 *
 * <h2>坐标映射</h2>
 * <pre>
 *   tx = sx
 *   ty = layer * gridSize + sy
 * </pre>
 *
 * <h2>生命周期</h2>
 * <p>本类的生命周期<b>直接跟随纹理</b>——{@link #close()} 释放纹理，
 * 没有引用计数。canvas 中的视图只是「读取窗口」——不参与生命周期管理。
 *
 * <p>canvas 是只读的——CPU 侧的 {@code clear()} 会抛异常。生命周期
 * 必须通过 {@link #close()} 显式结束。
 *
 * <h2>线程契约</h2>
 * <p><b>{@link #bind} / {@link #downloadTo} / {@link #close}
 * 必须在 GL 线程上调用。</b>
 * {@link #asCanvas()} 返回的 canvas 读取操作可任意线程。
 */
public final class GLTiledTexture implements AutoCloseable{

    private final GLTexture texture;
    private final TiledCanvas canvas;
    private final Executor  glExecutor;

    /** 纹理单元。-1 表示尚未绑定。 */
    private int unit = -1;

    /** 是否已关闭——防止重复 close。 */
    private volatile boolean closed = false;

    /**
     * @param texture    底层纹理（{@code GL_TEXTURE_2D_ARRAY}），必须方形，
     *                   边长整除 {@code tileSize}。<b>所有权转移给本类。</b>
     * @param tileSize   瓦片边长（像素）
     * @param glExecutor 投递释放任务的执行器——必须绑到 GL 线程
     * @throws IllegalArgumentException 参数非法
     */
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

        this.canvas = buildTiledCanvas(texture, tileSize);
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
                    GLTextureTileData view = new GLTextureTileData(this, layer, sx, sy);
                    c.replaceTile(tx, ty, view);
                }
            }
        }
        c.setReadonly(true);
        return c;
    }

    // ═══════════════════════════════════════════════
    // 对外接口
    // ═══════════════════════════════════════════════

    /**
     * 以 {@link TiledCanvas} 形式访问本纹理的瓦片容器。
     *
     * <p><b>同一实体的两个视图</b>——{@code GLTiledTexture} 和它返回的
     * canvas 描述同一份数据。canvas 是<b>只读</b>的——CPU 侧写操作无意义。
     *
     * <p>生命周期由 {@link #close()} 结束——canvas 自身不能触发释放。
     */
    public TiledCanvas asCanvas() {
        return canvas;
    }

    /**
     * 绑定到指定纹理单元——设置 unit 并绑定底层纹理。
     *
     * <p><b>有 GL 副作用</b>——必须在 GL 线程上调用。
     *
     * @param unit 纹理单元索引，必须 ≥ 0
     * @throws IllegalArgumentException unit &lt; 0
     */
    public void bind(int unit) {
        if (unit < 0) {
            throw new IllegalArgumentException("unit must be >= 0, got " + unit);
        }
        this.unit = unit;
        this.texture.bind(unit);
    }

    /** 当前纹理单元。-1 表示尚未绑定。 */
    public int getUnit() { return unit; }

    /** 是否已关闭。 */
    public boolean isClosed() { return closed; }

    /**
     * 把纹理的所有瓦片下载到目标画布。
     *
     * <p>目标画布的 {@code tileSize} 必须与本纹理一致——坐标一一对应。
     *
     * <p><b>必须在 GL 线程上调用。</b>下载会同步等待 GPU。
     */
    public void downloadTo(TiledCanvas target) {
        assertRgba8(target);
        if (target.getTileSize() != tileSize()) {
            throw new IllegalStateException(
                    "tileSize mismatch: expected " + tileSize()
                            + ", got " + target.getTileSize());
        }

        int grid   = gridSize();
        int layers = texture.getLayers();

        for (int layer = 0; layer < layers; layer++) {
            for (int sy = 0; sy < grid; sy++) {
                for (int sx = 0; sx < grid; sx++) {
                    downloadTile(target, layer, sx, sy);
                }
            }
        }
    }

    /**
     * 关闭——释放底层纹理。幂等。
     *
     * <p><b>为什么必须显式 close</b>：canvas 是只读的——CPU 侧的
     * {@code clear()} 会抛异常。生命周期无法通过 canvas 语义自然结束。
     *
     * <p>投递 {@code texture.release()} 到 GL 线程——<b>不等待</b>。
     *
     * <p><b>必须在 GL 线程上调用。</b>
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        glExecutor.execute(texture::release);
    }

    // ═══════════════════════════════════════════════
    // 内部——供 GLTextureTileData 访问
    // ═══════════════════════════════════════════════

    GLTexture texture() { return texture; }
    int tileSize()    { return canvas.getTileSize(); }
    int gridSize()    { return texture.getWidth() / canvas.getTileSize(); }

    // ═══════════════════════════════════════════════
    // 下载辅助
    // ═══════════════════════════════════════════════

    private void downloadTile(TiledCanvas target, int layer, int sx, int sy) {
        int ts = tileSize();
        int tx = sx;
        int ty = layer * gridSize() + sy;

        ByteBuffer packed = texture.downloadRegion(
                layer, sx * ts, sy * ts, ts, ts);
        try {
            ByteBuffer unpacked = unpackRgba8(packed, ts * ts);
            target.replaceTile(tx, ty, unpacked);
        } finally {
            MemoryUtil.memFree(packed);
        }
    }

    private void assertRgba8(TiledCanvas target) {
        if (texture.getPixelFormat() != PixelFormat.RGBA8) {
            throw new UnsupportedOperationException(
                    "downloadTo only supports RGBA8; got " + texture.getPixelFormat());
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

    // ═══════════════════════════════════════════════
    // 视图类型
    // ═══════════════════════════════════════════════

    /**
     * 纯 GPU 瓦片视图：指向 {@link GLTexture} 中某个瓦片区域。
     *
     * <p><b>唯一用途是占位</b>——让 canvas 有内容、让外部遍历时有对象
     * 可拿。下载操作由 {@link GLTiledTexture#downloadTo} 承担。
     *
     * <p><b>生命周期</b>：视图不参与 owner 的生命周期管理。owner 的生命
     * 周期由 {@link GLTiledTexture#close()} 直接控制。
     *
     * <p><b>CPU 侧访问不支持</b>——{@link #getPixels} / {@link #floatBuffer}
     * / {@link #copy} 抛 {@link UnsupportedOperationException}。
     */
    public static final class GLTextureTileData extends AbstractTileData implements GLTile {

        private final GLTiledTexture owner;
        private final int layer;
        private final int sx, sy;

        GLTextureTileData(GLTiledTexture owner, int layer, int sx, int sy) {
            this.owner = owner;
            this.layer = layer;
            this.sx    = sx;
            this.sy    = sy;
        }

        @Override
        public GLTileDescriptor getDescriptor() {
            int unit = owner.getUnit();
            if (unit < 0) {
                throw new IllegalStateException(
                        "GLTiledTexture unit not assigned; call bind(n) before "
                                + "collecting descriptors");
            }
            int grid = owner.gridSize();
            return GLTileDescriptor.of(
                    unit,
                    layer,
                    owner.tileSize(),
                    (float) sx / grid,
                    (float) sy / grid);
        }

        @Override
        protected void onRelease() {
            // 视图生命周期不参与 owner 的管理——无操作。
        }

        @Override
        public int getByteSize() {
            PixelFormat fmt = owner.texture().getPixelFormat();
            int ts = owner.tileSize();
            return ts * ts * fmt.getBytesPerPixel();
        }

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
                    "GLTextureTileData cannot be copied; it's a read-only GPU view");
        }
    }
}