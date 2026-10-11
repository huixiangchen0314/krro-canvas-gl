package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

/**
 * GL 线程亲和标记。实现此接口的对象的 GL 操作必须在指定线程上执行。
 *
 * <p><b>语义</b>：GL 资源（纹理、FBO、程序等）与创建它们的 GL 上下文
 * 绑定，而 GL 上下文在同一时刻只对一条线程可见。因此这些对象的
 * 创建、使用、销毁都必须回到创建它们的线程——即 {@link #getGlExecutor()}
 * 所属的 worker 线程。
 *
 * <p><b>契约</b>：调用方在调用实现类的任何涉及 GL 的方法前，
 * 必须确保当前线程等于 {@code getGlExecutor()} 的 worker 线程。
 * 违反者结果是未定义行为——驱动可能崩溃、静默失败或产生随机错误。
 * 实现类应在公开入口调用 {@link #requireGlThread()}，把违规尽早
 * 暴露为异常。
 *
 * <p><b>例外</b>：不与 GL 交互的方法（纯数据结构操作、状态查询、
 * 投递任务）不受此约束。具体哪些方法豁免由实现类的文档说明。
 *
 * <p><b>线程契约</b>：本接口自身只是只读访问器——{@link #getGlExecutor()}
 * 及所有 default 方法可在任意线程调用。
 */
public interface GLThreadAffine {

    /**
     * 本对象所属的 GL 线程。
     *
     * <p>返回的 executor 用于把 GL 操作调度到正确的线程上。
     * 实现类不应缓存该值——应在每次调用时转发到源头，以容忍
     * executor 重建的场景。
     *
     * @return 本对象绑定的 GL 线程执行器，非 null
     */
    SerialExecutor getGlExecutor();

    /**
     * 当前线程是否为本对象所属的 GL 线程。
     *
     * <p>用于调用方自行分支——需要决定"直接执行还是投递到 GL 线程"
     * 的场景。若只是要断言线程正确性，用 {@link #requireGlThread()}。
     *
     * @return true 表示当前线程可以安全地执行 GL 操作
     */
    default boolean isOnGlThread() {
        return Thread.currentThread().getId() == getGlExecutor().getThreadId();
    }

    /**
     * 断言当前线程为本对象所属的 GL 线程；不满足则抛异常。
     *
     * <p>供实现类在公开 GL 入口的首行调用——把线程违规尽早暴露为
     * 明确的异常，而不是等到驱动内部崩溃或静默出错。
     *
     * <p><b>异常消息包含当前线程名</b>，便于定位是哪条线程越界。
     *
     * @throws IllegalStateException 当前线程不是 GL 线程
     */
    default void requireGlThread() {
        if (!isOnGlThread()) {
            throw new IllegalStateException(
                    "operation requires GL thread; current="
                            + Thread.currentThread().getName());
        }
    }
}