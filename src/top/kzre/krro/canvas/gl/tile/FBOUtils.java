package top.kzre.krro.canvas.gl.tile;

import clojure.lang.Keyword;
import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.composite.ILayer;
import top.kzre.krro.canvas.gl.composite.WrapLayer;
import top.kzre.krro.canvas.gl.resource.FixedSizeFrameBufferPool;
import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.math.KMath;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.UUID;

/**
 * FBO 与画布 / 图层的桥接工具。
 *
 * <p><b>核心思想</b>：FBO 不是终点，而是一块可被当作画布使用的纹理层。
 * 通过 {@link GLTiledTextureLayer} 包装后，FBO 变成普通 {@link TiledCanvas}，
 * 可以继续参与下一轮合成，也可以作为结果返回给调用方。
 *
 * <p><b>两种所有权语义</b>：
 * <ul>
 *   <li>{@link #asCanvas}——<b>转移</b>。画布持有 FBO，画布关闭时
 *       自动归还 FBO 到池。</li>
 *   <li>{@link #wrapCanvas}——<b>借用</b>。画布不持有 FBO，
 *       关闭时不做任何事。FBO 的生命周期由调用方自己管理。</li>
 * </ul>
 *
 * <p><b>使用场景</b>：
 * <pre>
 *   合成结果返回给调用方        -> asCanvas（转移所有权）
 *   中间批次作为 backdrop 使用   -> wrapCanvas（借用，用后即弃）
 *   现有 FBO 作为图层参与合成    -> wrapLayer
 * </pre>
 *
 * <p><b>线程契约</b>：所有方法在 GL 线程调用。构造的 canvas / layer
 * 之后的使用遵循 {@link TiledCanvas} / {@link ILayer} 的线程契约。
 */
public final class FBOUtils {

    private FBOUtils() {
    }

    /**
     * 把 FBO 包装成画布——<b>转移所有权</b>。
     *
     * <p>返回的画布持有 FBO 的引用。画布关闭时（{@code close()}）
     * 自动调用 {@code pool.release(fbo)} 归还 FBO。
     *
     * <p>用于把合成结果交给调用方——调用方关闭画布 = 释放 FBO。
     *
     * @param fbo        待包装的 FBO
     * @param pool       FBO 所属的池，画布关闭时归还
     * @param glExecutor GL 线程执行器
     * @param tileSize   瓦片尺寸，用于确定画布的瓦片划分
     * @return 画布视图，持有 FBO 所有权
     */
    public static TiledCanvas asCanvas(
            GLFramebuffer fbo, FixedSizeFrameBufferPool pool,
            SerialExecutor glExecutor,
            int tileSize) {

        GLTexture tex = fbo.getColorAttachment();
        int layer = fbo.getLayer();

        GLTiledTextureLayer view = new GLTiledTextureLayer(
                tex, tileSize,
                layer, 1,
                glExecutor,
                () -> pool.release(fbo)
        );

        return view.asCanvas();
    }

    /**
     * 把 FBO 包装成画布——<b>借用</b>。
     *
     * <p>返回的画布不持有 FBO。画布关闭时不做任何事。FBO 的生命周期
     * 完全由调用方管理——调用方必须确保画布使用期间 FBO 有效，
     * 并在画布使用完毕后自行释放 FBO。
     *
     * <p>用于中间批次作为 backdrop——FBO 属于当前批次，下一批次
     * 使用完后由执行流程释放，不应由画布代为管理。
     *
     * @param fbo        待包装的 FBO
     * @param tileSize   瓦片尺寸
     * @param glExecutor GL 线程执行器
     * @return 画布视图，不持有 FBO 所有权
     */
    public static TiledCanvas wrapCanvas(
            GLFramebuffer fbo, int tileSize, SerialExecutor glExecutor) {

        GLTiledTextureLayer tiledTexture = wrapTiledTexture(glExecutor, fbo, tileSize);

        return tiledTexture.asCanvas();
    }

    public static GLTiledTextureLayer wrapTiledTexture(SerialExecutor glExecutor, GLFramebuffer fbo, int tileSize) {
        GLTexture tex = fbo.getColorAttachment();
        int layer = fbo.getLayer();

        return new GLTiledTextureLayer(
                tex, tileSize,
                layer, 1,
                glExecutor,
                () -> {
                }
        );
    }

    /**
     * 把 FBO 包装成图层——用于把现有 FBO 作为合成输入。
     *
     * <p>FBO 被包装为一个 {@link WrapLayer}，使用恒等变换、完全不透明、
     * 普通混合模式。语义等价于"把这块 FBO 的内容贴到目标画布的同一位置"。
     *
     * <p><b>借用语义</b>：内部通过 {@link #wrapCanvas} 包装，图层
     * 不持有 FBO。调用方负责 FBO 的生命周期。
     *
     * <p>典型用途：
     * <ul>
     *   <li>把上一批次的输出作为下一批次的 backdrop 图层</li>
     *   <li>把某个中间 FBO 作为最终合成的输入层</li>
     * </ul>
     *
     * @param fbo        待包装的 FBO
     * @param tileSize   瓦片尺寸
     * @param glExecutor GL 线程执行器
     * @return 图层视图，不持有 FBO 所有权
     */
    public static ILayer wrapLayer(
            GLFramebuffer fbo, int tileSize, SerialExecutor glExecutor
    ) {
        TiledCanvas canvas = wrapCanvas(fbo, tileSize, glExecutor);
        return new WrapLayer(
                Keyword.intern(null, "fbo-" + UUID.randomUUID()),
                canvas,
                KMath.mat2dIdentity(),
                true,
                1.0f,
                Blends.NORMAL
        );
    }

}