package top.kzre.krro.canvas.gl.composite;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合成规划专用后台执行器。
 *
 * <p><b>职责</b>：承载 {@link CompositePlanner#plan 规划阶段}的全部
 * CPU 侧工作——按 shader 分组、投影图层瓦片、收集纹理、构建 bindables、
 * 打包 {@code tileTable} / {@code layerTable} / {@code indexList}。这些
 * 都是纯数据结构操作，**不含 GL 调用**——GL 侧动作（绘制、FBO 绑定、
 * 纹理上传）由 {@link CompositeTask} 在 GL 线程完成。
 *
 * <p><b>为什么独立于 GL 线程</b>：规划可能包含大量遍历与打包——尤其
 * 在脏区大、图层多的场景。在 GL 线程上做这些工作会阻塞渲染；放到
 * 规划线程后，GL 线程只做真正需要 GL 上下文的部分。
 *
 * <p><b>为什么独立于下载线程</b>：规划任务与下载任务是两条独立
 * 流水线——规划为下一次合成做准备，下载处理上一次合成的结果。
 * 串到一条线程会互相排队。
 *
 * <p><b>单线程</b>：串行执行，保证规划任务按提交顺序完成，且避免
 * 对共享的 atlas 池状态产生并发访问（{@link top.kzre.krro.canvas.gl.tile.AtlasPoolPage}
 * 在规划阶段被读取）。
 *
 * <p><b>生命周期</b>：懒加载——首次 {@link #get()} 时创建。进程退出
 * 时由 {@link #shutdown()} 显式关闭。daemon 线程不阻塞 JVM 退出。
 *
 * <p><b>线程契约</b>：{@link #get()} 任意线程可调；返回的 executor
 * 允许任意线程提交任务。提交的任务本身不得调用 GL——它运行在
 * 规划线程上，不是 GL 线程。
 */
public final class PlanExecutor {

    private static final Object LOCK = new Object();
    private static volatile ExecutorService PLAN_EXECUTOR;

    private PlanExecutor() {}

    /**
     * 返回规划专用单线程执行器。
     *
     * <p>首次调用时创建——之后返回同一个实例。线程为 daemon——
     * JVM 退出不被阻塞。
     */
    public static ExecutorService get() {
        ExecutorService e = PLAN_EXECUTOR;
        if (e == null) {
            synchronized (LOCK) {
                e = PLAN_EXECUTOR;
                if (e == null) {
                    e = create();
                    PLAN_EXECUTOR = e;
                }
            }
        }
        return e;
    }

    /**
     * 关闭执行器。幂等。进程退出前调用。
     *
     * <p>关闭后 {@link #get()} 会重新创建——如果需要"永久关闭"语义，
     * 上层自行管理。
     */
    public static void shutdown() {
        synchronized (LOCK) {
            if (PLAN_EXECUTOR == null) return;
            PLAN_EXECUTOR.shutdown();
            try {
                if (!PLAN_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    PLAN_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                PLAN_EXECUTOR.shutdownNow();
            }
            PLAN_EXECUTOR = null;
        }
    }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    private static ExecutorService create() {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r,
                        "krro-canvas-plan-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(tf);
    }
}