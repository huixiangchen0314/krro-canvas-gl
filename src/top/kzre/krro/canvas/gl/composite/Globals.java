package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLQuad;

/**
 * 合成管线的全局共享资源。
 *
 * <h2>Attribute location 约定</h2>
 *
 * <p>合成管线的所有 shader 约定：顶点位置在 {@code location = 0}。
 * 这是 <b>composite 层</b>的约定——资源层（{@link GLQuad}）不知道
 * 也不假设任何 location。
 *
 * <p>此约定与 shader 源码里的 {@code layout(location = 0) in vec2 aPos}
 * 一一对应。新增 shader 变体时，必须遵守这个约定，或修改本类的
 * 绑定值——两处永远对齐。
 *
 * <h2>线程契约</h2>
 *
 * <p>{@link #getQuad()} 内部创建 {@link GLQuad}，涉及 GL 调用，
 * 首次调用必须在 GL 线程。之后返回缓存实例，任意线程可读——但
 * 返回的对象本身的使用仍受 GL 线程约束。
 */
public final class Globals {

    /** composite 层约定：顶点位置的 attribute location。 */
    private static final int QUAD_POSITION_LOCATION = 0;

    /** 私有锁，避免外部用 {@code synchronized (Globals.class)} 干扰。 */
    private static final Object LOCK = new Object();

    /** 共享 quad。volatile——双重检查锁定。 */
    private static volatile GLQuad QUAD;

    private Globals() {}

    /**
     * 获取共享的 {@link GLQuad}。首次调用时创建并绑定位置 attribute。
     *
     * <p><b>首次调用必须在 GL 线程</b>——创建和绑定涉及 GL 调用。
     */
    public static GLQuad getQuad() {
        GLQuad q = QUAD;
        if (q == null) {
            synchronized (LOCK) {
                q = QUAD;
                if (q == null) {
                    q = GLQuad.create();
                    q.bindPositionAttribute(QUAD_POSITION_LOCATION);
                    QUAD = q;
                }
            }
        }
        return q;
    }

    /**
     * 释放共享的 {@link GLQuad}。幂等。
     *
     * <p><b>必须在 GL 线程调用</b>——释放涉及 GL 调用。
     */
    public static void close() {
        synchronized (LOCK) {
            GLQuad q = QUAD;
            if (q != null) {
                q.release();
                QUAD = null;
            }
        }
    }
}