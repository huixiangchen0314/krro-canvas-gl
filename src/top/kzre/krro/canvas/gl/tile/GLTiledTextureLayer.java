package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.core.util.AsyncExecutor;

/**
 * 层范围的 {@link top.kzre.krro.util.tile.TiledCanvas} 视图。
 * <b>借用语义</b>——只借用整张纹理的某段层范围，不拥有纹理。
 *
 * <p>所有视图归零时调用构造时传入的 {@link Runnable} 通知借用方。
 * 借用方负责决定后续动作（比如把纹理归还给池，或通知其他借用者）。
 *
 * <p><b>与 {@link GLTiledTexture} 的关系</b>：一个完整纹理可以被
 * 多个层范围视图借用。每个借用者独立追踪自己的活跃计数，归零时
 * 各自通知。整张纹理的实际释放由拥有者（比如 {@code GLTiledTexture}
 * 或某个管理者）在合适的时机执行。
 *
 * <p><b>canvas 坐标</b>：视图的瓦片坐标保持全局——{@code ty} 是
 * {@code layer * tilesPerEdge + sy}，和完整纹理的坐标系一致。
 * 外部使用 canvas 时不需要关心 layerOffset。
 */
public final class GLTiledTextureLayer extends AbstractGLTiledTexture {

    private final int      layerOffset;
    private final int      layerCount;
    private final Runnable onReleased;

    /**
     * @param texture     底层纹理，借用
     * @param tileSize    瓦片边长
     * @param layerOffset 起始层（含），全局索引
     * @param layerCount  借用层数
     * @param glExecutor  GL 执行器，用于 downloadTo 等异步操作
     * @param onReleased  所有视图归零时的回调
     */
    public GLTiledTextureLayer(GLTexture texture, int tileSize,
                               int layerOffset, int layerCount,
                               AsyncExecutor glExecutor,
                               Runnable onReleased) {
        super(texture, tileSize, glExecutor);
        if (layerOffset < 0) {
            throw new IllegalArgumentException("layerOffset must be >= 0: " + layerOffset);
        }
        if (layerCount <= 0) {
            throw new IllegalArgumentException("layerCount must be > 0: " + layerCount);
        }
        if (layerOffset + layerCount > texture.getLayers()) {
            throw new IllegalArgumentException(
                    "layer range [" + layerOffset + ", " + (layerOffset + layerCount)
                            + ") out of [0, " + texture.getLayers() + ")");
        }
        if (onReleased == null) {
            throw new IllegalArgumentException("onReleased must not be null");
        }

        this.layerOffset = layerOffset;
        this.layerCount  = layerCount;
        this.onReleased  = onReleased;

        populateCanvas(layerOffset, layerCount);
    }

    @Override
    protected void onAllViewsReleased() {
        System.out.println("GLTiledTextureLayer#onAllViewsReleased");
        onReleased.run();
    }

    /** 起始层（含）。 */
    public int getLayerOffset() { return layerOffset; }

    /** 借用层数。 */
    public int getLayerCount() { return layerCount; }
}