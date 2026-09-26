package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.util.Resources;

/**
 * 合成管线的 GLProgram 工厂注册中心。
 *
 * <p><b>实例化</b>：每个 {@link GLCompositeContext} 或上层管线持有
 * 自己的实例。构造时加载 shader 源码并注册工厂——只读文件，无 GL
 * 调用。program 的实际编译延迟到首次 {@code get}，由 GL 线程执行。
 *
 * <p><b>生命周期</b>：实例持有的 {@link GLProgramCache} 需要显式
 * {@link #close()}——释放所有已编译 program，GL 线程调用。
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>构造器 —— 加载源码、注册工厂；任意线程安全（无 GL 调用）</li>
 *   <li>{@link #getCache()} —— 纯读，任意线程</li>
 *   <li>{@link GLProgramCache#get} —— 首次触发编译需要 GL 线程</li>
 *   <li>{@link #close()} —— GL 线程</li>
 * </ul>
 */
public final class CompositeGLProgramManager implements AutoCloseable {

    private final GLProgramCache cache = new GLProgramCache();
    private boolean closed = false;

    public CompositeGLProgramManager() {
        regSimpleNormal();
    }

    /** 返回本实例持有的 program 缓存。 */
    public GLProgramCache getCache() {
        return cache;
    }

    // ═══════════════════════════════════════════════
    // 注册
    // ═══════════════════════════════════════════════

    /**
     * 注册 normal 混合的 shader。
     *
     * <p>只加载源码、注册工厂——实际编译延迟到首次 {@code get}。
     */
    private void regSimpleNormal() {
        String vert = Resources.loadClassPathText(
                "resources/krro/canvas/gl/shaders/composite-noraml-simple.vert");
        String frag = Resources.loadClassPathText(
                "resources/krro/canvas/gl/shaders/composite-normal-simple.frag");

        cache.register(Blends.NORMAL,
                () -> GLProgram.create(vert, frag));
    }

    // ═══════════════════════════════════════════════
    // 关闭
    // ═══════════════════════════════════════════════

    /**
     * 释放缓存持有的所有 program。必须在 GL 线程调用。
     *
     * <p><b>不可重入</b>——重复调用抛异常。
     */
    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException(
                    "CompositeGLProgramManager already closed");
        }
        cache.close();
        closed = true;
    }

    /** 是否已关闭。 */
    public boolean isClosed() {
        return closed;
    }
}