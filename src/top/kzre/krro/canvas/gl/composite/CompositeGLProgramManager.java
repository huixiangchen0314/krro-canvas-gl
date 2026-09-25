package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.util.Resources;

/**
 * 合成管线的 GLProgram 工厂注册中心。
 *
 * <p>类加载时静态初始化，把混合模式 → shader 源码的映射注册到
 * {@link GLProgramCache}。program 的实际编译在首次 {@code get}
 * 时发生——由 GL 线程执行，不在静态初始化里编译。
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>静态初始化在类加载时执行——只读 shader 源码文件，无 GL 调用，
 *       任意线程安全</li>
 *   <li>{@link #getCache()} 返回共享缓存，任意线程可读</li>
 *   <li>缓存上的 {@code get} 首次访问触发编译，需要 GL 线程</li>
 *   <li>缓存的 {@code close} 释放全部 program，需要 GL 线程</li>
 * </ul>
 */
public final class CompositeGLProgramManager {

    private static final GLProgramCache CACHE = new GLProgramCache();

    static {
        regSimpleNormal();
    }

    private CompositeGLProgramManager() {}

    /** 返回共享的 program 缓存。 */
    public static GLProgramCache getCache() {
        return CACHE;
    }

    // ═══════════════════════════════════════════════
    // 注册
    // ═══════════════════════════════════════════════

    /**
     * 注册 normal 混合的 shader。
     *
     * <p>只加载源码、注册工厂——实际编译延迟到首次 {@code get}。
     */
    private static void regSimpleNormal() {
        String vert = Resources.loadClassPathText(
                "resources/krro/canvas/gl/shaders/composite-noraml-simple.vert");
        String frag = Resources.loadClassPathText(
                "resources/krro/canvas/gl/shaders/composite-normal-simple.frag");

        CACHE.register(Blends.NORMAL,
                () -> GLProgram.create(vert, frag));
    }
}