package top.kzre.krro.canvas.gl.util;

import top.kzre.krro.core.util.SerialExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public final class ExecutorUtils {
    private ExecutorUtils() {
    }

    public static CompletableFuture<Void> runOnThread(SerialExecutor executor, Runnable runnable) {
        if (Thread.currentThread().getId() == executor.getThreadId()) {
            runnable.run();
            return CompletableFuture.completedFuture(null);
        } else {
            return executor.submit(runnable);
        }
    }

    public static <T> CompletableFuture<T> runOnThread(SerialExecutor executor, Callable<T> runnable) {
        if (Thread.currentThread().getId() == executor.getThreadId()) {
            T result;
            try {
                result = runnable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            return CompletableFuture.completedFuture(result);
        } else {
            return executor.submit(runnable);
        }
    }
}
