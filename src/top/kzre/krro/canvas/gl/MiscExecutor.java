package top.kzre.krro.canvas.gl;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 合成管线的通用后台执行器。
 *
 * <p><b>用途</b>：承载合成管线的 CPU 侧工作——比如下载后的行序翻转、
 * 像素解包、以及其它不该占用 GL 线程的杂项任务。与 GL 线程解耦——
 * GL 线程只做 GL 调用，CPU 侧工作提交到这里。
 *
 * <p><b>单线程</b>：单线程保证任务顺序执行，避免共享状态竞争（例如
 * {@link top.kzre.krro.canvas.gl.util.BufferUtils} 的 {@code ThreadLocal}
 * 中转缓冲只在这一个线程内被使用）。任务量不大时也无需并行。
 *
 * <p><b>生命周期</b>：懒加载——首次 {@link #get()} 时创建。进程退出时
 * 由 {@link #shutdown()} 显式关闭。daemon 线程不阻塞 JVM 退出。
 *
 * <p><b>线程契约</b>：{@link #get()} 任意线程可调；返回的 executor
 * 允许任意线程提交任务。
 */
public final class MiscExecutor {

    private static final Object LOCK = new Object();
    private static volatile ExecutorService MISC_EXECUTOR;

    private MiscExecutor() {}

    /**
     * 返回通用杂项单线程执行器。
     *
     * <p>首次调用时创建——之后返回同一个实例。线程为 daemon——
     * JVM 退出不被阻塞。
     */
    public static ExecutorService get() {
        ExecutorService e = MISC_EXECUTOR;
        if (e == null) {
            synchronized (LOCK) {
                e = MISC_EXECUTOR;
                if (e == null) {
                    e = create();
                    MISC_EXECUTOR = e;
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
            if (MISC_EXECUTOR == null) return;
            MISC_EXECUTOR.shutdown();
            try {
                if (!MISC_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    MISC_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                MISC_EXECUTOR.shutdownNow();
            }
            MISC_EXECUTOR = null;
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
                        "krro-canvas-gl-misc-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(tf);
    }
}