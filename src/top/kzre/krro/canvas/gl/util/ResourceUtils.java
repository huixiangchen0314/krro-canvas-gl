package top.kzre.krro.canvas.gl.util;

import java.util.Arrays;

/**
 * 资源关闭工具。批量关闭多个 {@link AutoCloseable}，把所有关闭
 * 异常延迟到全部关闭完成后再统一抛出。
 *
 * <p><b>为什么需要</b>：try-with-resources 里如果某个 close 抛异常，
 * 后续资源的 close 仍会被调用——但那是语法糖在起作用。当资源在
 * 集合里、需要循环关闭时，一个 close 抛异常会中断循环，后面的资源
 * 全部泄漏。本工具保证所有资源都被关闭，异常最后统一抛出。
 *
 * <p><b>异常合并策略</b>：
 * <ul>
 *   <li>全部成功 → 返回 {@code null}</li>
 *   <li>一个失败 → 返回该异常</li>
 *   <li>多个失败 → 首个异常作为主异常，其余作为 suppressed 附加</li>
 * </ul>
 *
 * <p><b>异常类型</b>：返回值类型为 {@link RuntimeException}。若某个
 * close 抛的是受检异常，会被包装为 {@code RuntimeException}——关闭
 * 路径上不鼓励受检异常打断。
 *
 * <p><b>返回而非抛出</b>：调用方可以选择忽略或向上传播：
 * <pre>{@code
 * RuntimeException t = ResourceUtils.closeDelayError(a, b, c);
 * if (t != null) throw t;
 * }</pre>
 *
 * <p><b>null 容忍</b>：{@code closeables} 数组为 null、元素为 null、
 * 或 iterable 为 null 均安全跳过，不抛 NPE。
 *
 * <p><b>线程契约</b>：纯工具方法，无状态。不涉及 GL——如果被关闭
 * 的资源本身有线程约束（如 {@code GLTexture}），那是资源侧的契约。
 */
public final class ResourceUtils {

    private ResourceUtils() {}

    /**
     * 关闭一组资源。所有资源都会被尝试关闭，即便前面的关闭抛异常。
     *
     * @param closeables 待关闭资源，可为 null 或包含 null 元素
     * @return 合并后的异常；全部成功返回 null
     */
    public static RuntimeException closeDelayError(AutoCloseable... closeables) {
        if (closeables == null || closeables.length == 0) return null;
        Throwable error = null;
        for (AutoCloseable c : closeables) {
            error = accumulateException(error, closeSilently(c));
        }
        return wrapError(error);
    }

    /**
     * 关闭一个资源集合。所有资源都会被尝试关闭，即便前面的关闭抛异常。
     *
     * @param closeables 待关闭资源，可为 null；元素可为 null
     * @return 合并后的异常；全部成功返回 null
     */
    public static RuntimeException closeDelayError(
            Iterable<? extends AutoCloseable> closeables) {
        if (closeables == null) {
            return null;
        }
        Throwable first = null;
        for (AutoCloseable c : closeables) {
            Throwable t = closeSilently(c);
            first = accumulateException(first, t);
        }
        return wrapError(first);
    }

    /**
     * 执行一组 Runnable。所有 Runnable 都会被尝试执行，即便前面的
     * 执行抛异常——异常延迟到全部执行完成后再统一返回。
     *
     * <p><b>与 {@link #closeDelayError} 的关系</b>：语义一致，只是
     * 目标从"关闭资源"扩展为"执行任意动作"。适用于必须全部执行完、
     * 单个失败不中断的场景（批量清理、批量提交）。
     *
     * @param runnables 待执行动作，可为 null 或包含 null 元素
     * @return 合并后的异常；全部成功返回 null
     */
    public static RuntimeException runDelayError(Runnable... runnables) {
        if (runnables == null || runnables.length == 0) return null;
        Throwable error = null;
        for (Runnable r : runnables) {
            error = accumulateException(error, runSilently(r));
        }
        return wrapError(error);
    }

    /**
     * 执行一个 Runnable 集合。所有 Runnable 都会被尝试执行，即便
     * 前面的执行抛异常。
     *
     * @param runnables 待执行动作，可为 null；元素可为 null
     * @return 合并后的异常；全部成功返回 null
     */
    public static RuntimeException runDelayError(
            Iterable<? extends Runnable> runnables) {
        if (runnables == null) {
            return null;
        }
        Throwable first = null;
        for (Runnable r : runnables) {
            Throwable t = runSilently(r);
            first = accumulateException(first, t);
        }
        return wrapError(first);
    }

// ═══════════════════════════════════════════════
// 内部
// ═══════════════════════════════════════════════

    /**
     * 执行单个 Runnable，吞掉异常并返回。
     *
     * <p>{@link RuntimeException} 原样返回；受检 {@link Exception}
     * 包装为 {@code RuntimeException}；{@link Error} 原样透传。
     */
    private static Throwable runSilently(Runnable r) {
        if (r == null) return null;
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    /**
     * 关闭单个资源，吞掉异常并返回。
     *
     * <p>{@link RuntimeException} 原样返回；受检 {@link Exception}
     * 包装为 {@code RuntimeException}；{@link Error} 原样透传（
     * 不包装，因为 Error 语义上不应被滥用为控制流）。
     */
    private static Throwable closeSilently(AutoCloseable c) {
        if (c == null) return null;
        try {
            c.close();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    public static RuntimeException wrapError(Throwable t) {
        if (t == null) {
            return null;
        }
        if (t instanceof RuntimeException) return (RuntimeException) t;
        if (t instanceof Error){
            throw (Error) t;
        }
        return new RuntimeException(t);
    }

    /** 累积异常：首个作为主异常，后续作为 suppressed。 */
    public static Throwable accumulateException(Throwable first,
                                                Throwable next) {
        if (next == null) return first;
        if (first == null) return next;
        if (first == next) return first;
        first.addSuppressed(next);
        return first;
    }
}