package top.kzre.krro.canvas.gl.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单线程后台执行器的实例持有者。
 *
 * <p><b>用途</b>：承载某条管线 CPU 侧的串行任务——下载后的行序翻转、
 * 像素解包，以及其它不该占用 GL 线程的杂项工作。与 GL 线程解耦：
 * GL 线程只做 GL 调用，CPU 侧工作提交到这里。
 *
 * <p><b>单线程</b>：任务顺序执行，避免共享状态竞争（例如
 * {@link top.kzre.krro.canvas.gl.util.BufferUtils BufferUtils} 的
 * {@code ThreadLocal} 中转缓冲只在这条线程内使用）。任务量不大时
 * 也无需并行。
 *
 * <p><b>实例语义</b>：每个实例独立持有一条 daemon 线程，随实例
 * 创建而创建、随 {@link #close()} 而终止。不同实例之间互不影响——
 * 全局单例由调用方自行封装（见 {@code MiscExecutor}）。
 *
 * <p><b>线程命名</b>：{@code threadNamePrefix + 全局序号}。序号是
 * 进程内静态递增，保证任何实例创建的线程名在进程内唯一。
 *
 * <p><b>生命周期</b>：构造即创建 executor。{@link #close()} 幂等；
 * 关闭后再调用 {@link #get()} 抛 {@link IllegalStateException}。
 *
 * <p><b>线程契约</b>：{@link #get()} 任意线程可调；返回的 executor
 * 允许任意线程提交任务。
 */
public final class ExecutorHolder implements AutoCloseable {

    /**
     * 全局递增序号——保证不同实例的线程名唯一。
     */
    private static final AtomicInteger INSTANCE_SEQ = new AtomicInteger();

    /**
     * close 时等待任务完成的最长秒数；超时则强制中断。
     */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final ExecutorService executor;
    private final String threadName;
    private volatile boolean closed = false;

    /**
     * 创建并启动一条后台线程。
     *
     * @param threadNamePrefix 线程名前缀，非空。实例会在此前缀后追加
     *                         一个全局递增序号形成最终线程名。
     * @throws NullPointerException 前缀为 null
     */
    public ExecutorHolder(String threadNamePrefix) {
        if (threadNamePrefix == null) {
            throw new NullPointerException("threadNamePrefix");
        }
        this.threadName = threadNamePrefix + INSTANCE_SEQ.incrementAndGet();
        this.executor = create(threadName);
    }

    private static ExecutorService create(String baseName) {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                int n = seq.incrementAndGet();
                String name = (n == 1) ? baseName : baseName + "-" + n;
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newSingleThreadExecutor(tf);
    }

    /**
     * 返回持有的执行器。任意线程可调。
     *
     * @return 单线程 executor，可提交任意任务
     * @throws IllegalStateException 实例已关闭
     */
    public ExecutorService get() {
        if (closed) {
            throw new IllegalStateException(
                    "ExecutorHolder[" + threadName + "] is closed");
        }
        return executor;
    }

    /**
     * 本实例持有的线程名。
     */
    public String getThreadName() {
        return threadName;
    }

    /**
     * 是否已经关闭。
     */
    public boolean isClosed() {
        return closed;
    }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    /**
     * 关闭执行器。幂等。
     *
     * <p>先 {@code shutdown()} 温和关闭——已在队列中的任务会继续完成。
     * 若 {@value #SHUTDOWN_TIMEOUT_SECONDS} 秒内未终止，则
     * {@code shutdownNow()} 强制中断。
     *
     * <p><b>不抛异常</b>：关闭过程中的中断请求会恢复线程的中断标记，
     * 不向外传播——close 语义上不应失败。
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}