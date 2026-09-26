package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.color.RGB;
import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.tile.GLTiledTextureLayer;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 合成执行编排。从图层列表开始，到结果 canvas 返回，
 * 全流程在 Java 侧完成。
 *
 * <p>调用方负责把所有图层（含背景）拼成一个 {@code List<ILayer>}
 * 传入——顺序即合成顺序，最底层在前。
 */
public final class Render {

    private Render() {}

    /**
     * 执行一次合成。
     *
     * <p>流程：
     * <ol>
     *   <li>视口调整——仅尺寸变化时调度 GL 线程</li>
     *   <li>借 ping-pong FBO 对</li>
     *   <li>规划——当前线程纯 CPU</li>
     *   <li>执行——GL 线程</li>
     *   <li>归还非结果 FBO，包装结果为 canvas</li>
     * </ol>
     *
     * @param layers     完整图层列表（含背景），最底层在前
     * @param ctx        合成上下文
     * @param viewWidth  逻辑视口宽（像素）
     * @param viewHeight 逻辑视口高（像素）
     * @param dirtyTiles 脏屏幕瓦片键集合
     * @return future，解析为结果 TiledCanvas；无脏区域时为 null
     */
    public static CompletableFuture<TiledCanvas> render(
            List<ILayer> layers,
            GLCompositeContext ctx,
            int viewWidth, int viewHeight,
            Set<Long> dirtyTiles) {

        if (layers == null) {
            throw new IllegalArgumentException("layers must not be null");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        if (dirtyTiles == null) {
            throw new IllegalArgumentException("dirtyTiles must not be null");
        }


        return glAdjustViewSize(ctx, viewWidth, viewHeight)
                .thenCompose(ignored -> doRender(
                        layers, ctx, dirtyTiles));
    }


    private static CompletableFuture<TiledCanvas> doRender(
            List<ILayer> layers,
            GLCompositeContext ctx,
            Set<Long> dirtyTiles) {

//        return CompletableFuture.completedFuture(
//                new TiledCanvas(ctx.getTileSize(), RGB.rgba(1, 0, 0, 1))
//                        .ensureTiles(dirtyTiles)
//        );

        FixedSizeFrameBufferPool fboPool  = ctx.getViewFrameBufferPool();
        SerialExecutor glExecutor = ctx.getGlExecutor();
        int tileSize = ctx.getTileSize();
        return glAcquireFboPair(fboPool, glExecutor)
                .thenCompose(pair -> {
                    GLFramebuffer fboA = pair[0];
                    GLFramebuffer fboB = pair[1];

                    CompositeRequest request = CompositePlanner.plan(
                            layers, ctx,
                            CompositeGLProgramManager.getCache(),
                            fboA, fboB, dirtyTiles);

                    if (request == null) {
                        fboPool.release(fboA);
                        fboPool.release(fboB);
                        TiledCanvas emptyCanvas = ctx.newCanvas();
                        return CompletableFuture.completedFuture(emptyCanvas);
                    }

                    return glExecutor.submit(new MockClearBlueCompositeTask(fboA))
                            .thenCompose(resultFbo ->{
                                GLFramebuffer other = (resultFbo == fboA) ? fboB : fboA;
                                fboPool.release(other);
                                TiledCanvas canvas = wrapResultCanvas(fboPool, glExecutor, resultFbo, tileSize);

                                return CompletableFuture.completedFuture(canvas);
                            });
                });
    }



    private static CompletableFuture<Void> glAdjustViewSize(
            GLCompositeContext ctx,
            int viewWidth, int viewHeight) {

        if (!ctx.needsViewSizeAdjust(viewWidth, viewHeight)) {
            return CompletableFuture.completedFuture(null);
        }
        SerialExecutor glExecutor = ctx.getGlExecutor();
        return glExecutor.submit(() -> {
            ctx.adjustViewSize(viewWidth, viewHeight);
            return null;
        });
    }

    private static CompletableFuture<GLFramebuffer[]> glAcquireFboPair(
            FixedSizeFrameBufferPool pool, SerialExecutor executor) {
        if (pool.getIdleCount() >= 2) {
            return CompletableFuture.completedFuture(
                    new GLFramebuffer[]{ pool.acquire(), pool.acquire() });
        }

        return executor.submit(() ->
                new GLFramebuffer[]{ pool.acquire(), pool.acquire() });
    }


    private static TiledCanvas wrapResultCanvas(
            FixedSizeFrameBufferPool pool,
            SerialExecutor glExecutor,
            GLFramebuffer fbo,
            int tileSize) {

        GLTexture tex   = fbo.getColorAttachment();
        int layer = fbo.getLayer();

        GLTiledTextureLayer view = new GLTiledTextureLayer(
                tex, tileSize,
                layer, 1,
                glExecutor,
                () -> pool.release(fbo)
        );

        return view.asCanvas();
    }
}