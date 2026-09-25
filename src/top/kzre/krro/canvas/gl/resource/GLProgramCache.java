package top.kzre.krro.canvas.gl.resource;

import java.util.HashMap;
import java.util.Map;

/**
 * GLProgram 缓存。按 key 缓存已编译的 program。
 *
 * <p><b>注册与获取分离</b>：
 * <ul>
 *   <li>{@link #register} —— 注册 key → factory 的映射，构造时配置</li>
 *   <li>{@link #get} —— 按 key 取 program，未编译时调 factory 编译</li>
 *   <li>{@link #peek} —— 只查询已编译的 program，纯读</li>
 * </ul>
 *
 * <p><b>用途</b>：规划阶段用 {@link #peek} 预判 program 是否已编译，
 * 决定是否需要调度 GL 线程先编译；执行阶段用 {@link #get} 拿 program。
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
public final class GLProgramCache implements AutoCloseable {

    /** key → factory。启动时注册，运行期只读。 */
    private final Map<Object, GLProgramFactory> factoryMap = new HashMap<>();

    /** key → 已编译的 program。未命中触发编译后写入。 */
    private final Map<Object, GLProgram> programMap = new HashMap<>();

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
    public void register(Object key, GLProgramFactory factory) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        if (factoryMap.containsKey(key)) {
            throw new IllegalStateException("key already registered: " + key);
        }
        factoryMap.put(key, factory);
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
    // 查询（纯读）
    // ═══════════════════════════════════════════════

    /**
     * 返回已编译的 program，未编译返回 {@code null}。纯读，任意线程。
     *
     * <p>用于规划阶段预判是否需要调度 GL 线程编译。
     */
    public GLProgram peek(Object key) {
        return programMap.get(key);
    }

    /** 指定 key 的 program 是否已编译。纯读，任意线程。 */
    public boolean isCompiled(Object key) {
        return programMap.containsKey(key);
    }

    /** 已注册的 key 数量。 */
    public int getRegisteredCount() {
        return factoryMap.size();
    }

    /** 已编译的 program 数量。 */
    public int getCompiledCount() {
        return programMap.size();
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
}