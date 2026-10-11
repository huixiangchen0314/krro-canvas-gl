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
        RuntimeException error = null;
        for (AutoCloseable c : closeables) {
            error = accumulateException(error, closeSilently(c));
        }
        return error;
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
        RuntimeException first = null;
        for (AutoCloseable c : closeables) {
            RuntimeException t = closeSilently(c);
            first = accumulateException(first, t);
        }
        return first;
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
    private static RuntimeException closeSilently(AutoCloseable c) {
        if (c == null) return null;
        try {
            c.close();
            return null;
        } catch (RuntimeException e) {
            return e;
        } catch (Exception e) {
            return new RuntimeException(e);
        } catch (Error e) {
            // Error 不该被包装——原样抛出，让调用方感知
            throw e;
        }
    }

    /** 累积异常：首个作为主异常，后续作为 suppressed。 */
    public static RuntimeException accumulateException(RuntimeException first,
                                                        RuntimeException next) {
        if (next == null) return first;
        if (first == null) return next;
        if (first == next) return first;
        first.addSuppressed(next);
        return first;
    }
}