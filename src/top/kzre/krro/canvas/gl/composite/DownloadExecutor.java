package top.kzre.krro.canvas.gl.composite;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载路径专用后台执行器。
 *
 * <p><b>职责</b>：承载 FBO / 纹理下载后的全部 CPU 侧处理——
 * 行序翻转、像素解包、反预乘、格式转换、内存拷贝。**不含 GL 调用**
 * ——{@code glReadPixels} 等 GL 侧动作仍在 GL 线程完成；本执行器只
 * 处理 GL 调用已产出的字节缓冲。
 *
 * <p><b>单线程</b>：串行执行，保证下载任务按提交顺序完成。同时
 * 复用 {@link top.kzre.krro.canvas.gl.util.BufferUtils BufferUtils}
 * 的 {@code ThreadLocal} 中转缓冲——单线程下缓冲只被一条线程持有，
 * 无跨线程争用。
 *
 * <p><b>生命周期</b>：懒加载——首次 {@link #get()} 时创建。进程退出
 * 时由 {@link #shutdown()} 显式关闭。daemon 线程不阻塞 JVM 退出。
 *
 * <p><b>线程契约</b>：{@link #get()} 任意线程可调；返回的 executor
 * 允许任意线程提交任务。提交的任务本身不得调用 GL——它运行在
 * 下载线程上，不是 GL 线程。
 */
public final class DownloadExecutor {

    private static final Object LOCK = new Object();
    private static volatile ExecutorService DOWNLOAD_EXECUTOR;

    private DownloadExecutor() {}

    /**
     * 返回下载专用单线程执行器。
     *
     * <p>首次调用时创建——之后返回同一个实例。线程为 daemon——
     * JVM 退出不被阻塞。
     */
    public static ExecutorService get() {
        ExecutorService e = DOWNLOAD_EXECUTOR;
        if (e == null) {
            synchronized (LOCK) {
                e = DOWNLOAD_EXECUTOR;
                if (e == null) {
                    e = create();
                    DOWNLOAD_EXECUTOR = e;
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
            if (DOWNLOAD_EXECUTOR == null) return;
            DOWNLOAD_EXECUTOR.shutdown();
            try {
                if (!DOWNLOAD_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                    DOWNLOAD_EXECUTOR.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                DOWNLOAD_EXECUTOR.shutdownNow();
            }
            DOWNLOAD_EXECUTOR = null;
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
                        "krro-canvas-download-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(tf);
    }
}