package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.canvas.gl.util.Tuple4f;
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
 *
 * <h2>线程阶段</h2>
 * <ul>
 *   <li><b>调用线程</b>：入口校验</li>
 *   <li><b>glExecutor</b>：视口调整、GL 执行、非结果 FBO 归还</li>
 *   <li><b>PlanExecutor</b>：规划（纯 CPU）、canvas 构造与瓦片保留</li>
 * </ul>
 * 三个阶段分属不同线程池——避免同一队列内的任务互相等待造成死锁。
 */
public final class Render {

    private Render() {}

    public static CompletableFuture<TiledCanvas> render(
            List<ILayer> layers,
            GLCompositeContext ctx,
            int viewWidth, int viewHeight,
            Tuple4f imageAABB,
            Set<Long> dirtyTiles) {

        if (layers == null) {
            throw new IllegalArgumentException("layers must not be null");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("ctx must not be null");
        }
        if (imageAABB == null) {
            throw new IllegalArgumentException("imageAABB must not be null");
        }
        if (dirtyTiles == null) {
            throw new IllegalArgumentException("dirtyTiles must not be null");
        }

        long tStart = System.nanoTime();

        return glAdjustViewSize(ctx, viewWidth, viewHeight)
                .thenCompose(ignored -> doRender(layers, ctx, imageAABB, dirtyTiles, tStart));
    }

    // ═══════════════════════════════════════════════
    // 主流程
    // ═══════════════════════════════════════════════

    private static CompletableFuture<TiledCanvas> doRender(
            List<ILayer> layers,
            GLCompositeContext ctx,
            Tuple4f imageAABB,
            Set<Long> dirtyTiles,
            long tStart) {

        FixedSizeFrameBufferPool fboPool = ctx.getViewFrameBufferPool();
        SerialExecutor glExecutor = ctx.getGlExecutor();
        int tileSize = ctx.getTileSize();
        Set<GLTexture> extraTextures = LayerUtils.collectTextures(layers);

        long tCollect = System.nanoTime();

        return glAcquireFboPair(fboPool, extraTextures)
                .thenComposeAsync(
                        // 总是调度出去更安全，否则就必须排除法，不能是下载线程，不能是GL 线程，
                        // 这样子更糟糕，因为GL 线程必定1个，但下载线程，以及日后可能拓展的线程并不稳定
                        pair -> {
                            long tAcquire = System.nanoTime();
                            return planAndSubmit(
                                    pair, layers, ctx, imageAABB, dirtyTiles,
                                    fboPool, glExecutor,  tileSize,
                                    tStart, tCollect, tAcquire);
                        },
                        PlanExecutor.get());
    }

    /**
     * 规划 + 提交 GL 执行。运行在 {@link PlanExecutor} 线程上。
     */
    private static CompletableFuture<TiledCanvas> planAndSubmit(
            GLFramebuffer[] pair,
            List<ILayer> layers,
            GLCompositeContext ctx,
            Tuple4f imageAABB,
            Set<Long> dirtyTiles,
            FixedSizeFrameBufferPool fboPool,
            SerialExecutor glExecutor,
            int tileSize,
            long tStart,
            long tCollect,
            long tAcquire) {

        GLFramebuffer fboA = pair[0];
        GLFramebuffer fboB = pair[1];

        CompositeRequest request;
        try {
            request = CompositePlanner.plan(layers, ctx, fboA, fboB, imageAABB, dirtyTiles);
        } catch (Throwable t) {
            fboPool.release(fboA);
            fboPool.release(fboB);
            System.err.println("plan failed");
            throw t;
        }

        long tPlan = System.nanoTime();

        if (request == null) {
            System.out.println("empty plan, return empty canvas");
            fboPool.release(fboA);
            fboPool.release(fboB);
            long tNow = System.nanoTime();
            logTimes(tStart, tCollect, tAcquire, tPlan, tNow, tNow, "empty");
            return CompletableFuture.completedFuture(ctx.newCanvas());
        }

        return ExecutorUtils.runOnThread(glExecutor, new CompositeTask(request))
                .thenApply(resultFbo -> {
                    GLFramebuffer other = (resultFbo == fboA) ? fboB : fboA;
                    fboPool.release(other);
                    return resultFbo;
                })
                .thenApplyAsync(resultFbo -> {
                    long tExec = System.nanoTime();
                    TiledCanvas canvas = FBOUtils.asCanvas(
                            resultFbo, fboPool, glExecutor, tileSize);
                    canvas.keepTiles(dirtyTiles);
                    assert !canvas.getTiles().isEmpty();

                    long tWrap = System.nanoTime();
                    logTimes(tStart, tCollect, tAcquire, tPlan, tExec, tWrap, "ok");
                    return canvas;
                }, PlanExecutor.get());
    }

    private static void logTimes(long tStart, long tCollect, long tAcquire,
                                 long tPlan, long tExec, long tWrap, String tag) {
        System.out.println("[render-timing " + tag + "]"
                + " collect=" + ms(tCollect - tStart) + "ms"
                + " acquire=" + ms(tAcquire - tCollect) + "ms"
                + " plan="    + ms(tPlan - tAcquire) + "ms"
                + " exec="    + ms(tExec - tPlan) + "ms"
                + " wrap="    + ms(tWrap - tExec) + "ms"
                + " | total=" + ms(tWrap - tStart) + "ms");
    }

    private static String ms(long ns) {
        return String.format("%.2f", ns / 1_000_000.0);
    }

    // ═══════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════

    public static CompletableFuture<Void> glAdjustViewSize(
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
            FixedSizeFrameBufferPool pool,
            Set<GLTexture> excluded) {

        return pool.ensureSlotsWithIdleAtLeastExcluding(2, 1, excluded)
                .thenApply(v -> pool.acquire2IdleExcluding(excluded));
    }
}