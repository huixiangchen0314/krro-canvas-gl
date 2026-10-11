package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
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
 *   <li><b>调用线程</b>：入口校验、dirty 集合补全</li>
 *   <li><b>glExecutor</b>：视口调整、GL 执行、非结果 FBO 归还</li>
 *   <li><b>PlanExecutor</b>：规划（纯 CPU）、canvas 构造与瓦片保留</li>
 * </ul>
 * 三个阶段分属不同线程池——避免同一队列内的任务互相等待造成死锁。
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
     *   <li>规划——PlanExecutor 纯 CPU</li>
     *   <li>执行——glExecutor</li>
     *   <li>归还非结果 FBO，包装结果为 canvas——PlanExecutor</li>
     * </ol>
     *
     * @param layers     完整图层列表（含背景），最底层在前
     * @param ctx        合成上下文
     * @param viewWidth  逻辑视口宽（像素）
     * @param viewHeight 逻辑视口高（像素）
     * @param dirtyTiles 脏屏幕瓦片键集合；null 表示全屏
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
                .thenCompose(ignored -> doRender(layers, ctx, dirtyTiles));
    }

    // ═══════════════════════════════════════════════
    // 主流程
    // ═══════════════════════════════════════════════

    private static CompletableFuture<TiledCanvas> doRender(
            List<ILayer> layers,
            GLCompositeContext ctx,
            Set<Long> dirtyTiles) {

        FixedSizeFrameBufferPool fboPool = ctx.getViewFrameBufferPool();
        SerialExecutor glExecutor = ctx.getGlExecutor();
        int tileSize = ctx.getTileSize();
        Set<GLTexture> extraTextures = LayerUtils.collectTextures(layers);

        // 借 ping-pong FBO 对——内部决定是否走 GL 线程扩容
        return glAcquireFboPair(fboPool, extraTextures)
                // 借到后切到 PlanExecutor：规划与后续步骤都是纯 CPU
                // 但规划后要向 glExecutor 提交 CompositeTask——
                // 两个线程池独立推进，不互相阻塞
                .thenComposeAsync(
                        pair -> planAndSubmit(
                                pair, layers, ctx, dirtyTiles,
                                fboPool, glExecutor, tileSize),
                        PlanExecutor.get());
    }

    /**
     * 规划 + 提交 GL 执行。运行在 {@link PlanExecutor} 线程上。
     *
     * <p>返回的 future 在 canvas 构造完成后解析——完成线程是
     * PlanExecutor（正常路径与 null 请求路径）。
     */
    private static CompletableFuture<TiledCanvas> planAndSubmit(
            GLFramebuffer[] pair,
            List<ILayer> layers,
            GLCompositeContext ctx,
            Set<Long> dirtyTiles,
            FixedSizeFrameBufferPool fboPool,
            SerialExecutor glExecutor,
            int tileSize) {

        GLFramebuffer fboA = pair[0];
        GLFramebuffer fboB = pair[1];

        // ── 规划——纯 CPU，在 PlanExecutor 线程 ──
        CompositeRequest request;
        try {
            request = CompositePlanner.plan(layers, ctx, fboA, fboB, dirtyTiles);
        } catch (Throwable t) {
            // 规划失败：归还两个 FBO，异常向上传播
            fboPool.release(fboA);
            fboPool.release(fboB);
            throw t;
        }

        if (request == null) {
            // 无脏区或所有批次为空——归还 FBO，返回空 canvas
            fboPool.release(fboA);
            fboPool.release(fboB);
            return CompletableFuture.completedFuture(ctx.newCanvas());
        }

        // ── 执行——GL 线程 ──
        return ExecutorUtils.submitIfThread(glExecutor, new CompositeTask(request))
                // 仍在 GL 线程：归还非结果 FBO
                .thenApply(resultFbo -> {
                    GLFramebuffer other = (resultFbo == fboA) ? fboB : fboA;
                    fboPool.release(other);
                    return resultFbo;
                })
                // 切到 PlanExecutor：canvas 构造 + keepTiles 都是纯 CPU。
                // 若在 GL 线程上做这些事，后续若需要 glExecutor 会自锁。
                .thenApplyAsync(resultFbo -> {
                    TiledCanvas canvas = FBOUtils.asCanvas(
                            resultFbo, fboPool, glExecutor, tileSize);
                    canvas.keepTiles(dirtyTiles);
                    return canvas;
                }, PlanExecutor.get());
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

    /**
     * 借一对颜色附件不同的 FBO。
     *
     * <p>{@code ensureSlotsWithIdleAtLeastExcluding} 决定是否需要
     * GL 调度补足容量——不够时走 glExecutor 创建新 slot；够时
     * 同步从已有 slot 复用。之后 {@code acquire2IdleExcluding} 是
     * 纯复用路径，无 GL 调用。
     *
     * <p>返回的 future 完成线程不固定——快速路径是调用线程，
     * 慢速路径是 glExecutor。
     */
    private static CompletableFuture<GLFramebuffer[]> glAcquireFboPair(
            FixedSizeFrameBufferPool pool,
            Set<GLTexture> excluded) {

        return pool.ensureSlotsWithIdleAtLeastExcluding(2, 1, excluded)
                .thenApply(v -> pool.acquire2IdleExcluding(excluded));
    }
}