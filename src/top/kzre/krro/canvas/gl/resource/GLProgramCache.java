package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

import java.util.HashMap;
import java.util.Map;

/**
 * GLProgram 缓存。按 key 缓存已编译的 program。
 *
 *
 * <h2>线程契约</h2>
 * <ul>
 *   <li>{@code register} —— 任意线程（通常在启动时单线程调用）</li>
 *   <li>{@code peek} / {@code isCompiled} —— 任意线程（纯读）</li>
 *   <li>{@code get} —— 缓存命中时任意线程；未命中需编译时 GL 线程</li>
 *   <li>{@code close} —— GL 线程（释放所有 program）</li>
 * </ul>
 *
 * <p>缓存本身不做同步——调用方保证线程正确性。启动时注册、运行期
 * 只读/命中缓存是常态；未命中触发编译时应由 GL 线程执行。
 */
public final class GLProgramCache implements GLThreadAffine, AutoCloseable {
    private final SerialExecutor glThread;
    /**
     * key → factory。启动时注册，运行期只读。
     */
    private final Map<Object, GLProgramFactory> factoryMap = new HashMap<>();

    /**
     * key → 已编译的 program。未命中触发编译后写入。
     */
    private final Map<Object, GLProgram> programMap = new HashMap<>();

    public GLProgramCache(SerialExecutor glThread) {
        this.glThread = glThread;
    }

    // ═══════════════════════════════════════════════
    // 注册
    // ═══════════════════════════════════════════════

    /**
     * 注册 key → factory 的映射。
     *
     * @param key     唯一标识，例如混合模式字符串
     * @param factory 按需编译 program 的工厂
     * @throws IllegalArgumentException key 或 factory 为 null
     * @throws IllegalStateException    key 已注册
     */
    public boolean putIfAbsent(Object key, GLProgramFactory factory) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        if (factoryMap.containsKey(key)) {
           return false;
        }
        factoryMap.put(key, factory);
        return true;
    }

    // ═══════════════════════════════════════════════
    // 获取
    // ═══════════════════════════════════════════════

    /**
     * 按 key 取 program。缓存命中直接返回；未命中时调 factory 编译
     * 并缓存。
     *
     * <p><b>线程契约</b>：未命中且需要编译时必须在 GL 线程。缓存
     * 命中时任意线程。
     *
     * @param key 注册时的标识
     * @return 已编译的 program，所有权在本缓存——不要在外部 release
     * @throws IllegalArgumentException key 未注册
     */
    public GLProgram get(Object key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        GLProgram p = programMap.get(key);
        if (p != null) {
            return p;
        }
        GLProgramFactory factory = factoryMap.get(key);
        if (factory == null) {
            throw new IllegalArgumentException("key not registered: " + key);
        }
        p = factory.create();
        programMap.put(key, p);
        return p;
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 释放全部已编译的 program，清空缓存。
     *
     * <p><b>线程契约</b>：GL 线程（program 的 release 涉及 GL 调用）。
     *
     * <p>释放后缓存不可再用——所有 key 的注册也一并清除。
     */
    @Override
    public void close() {
        for (GLProgram p : programMap.values()) {
            p.release();
        }
        programMap.clear();
        factoryMap.clear();
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return glThread;
    }
}