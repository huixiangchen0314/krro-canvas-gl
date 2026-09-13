package top.kzre.krro.canvas.gl.resource;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL20.*;

/**
 * GL 着色器程序封装。
 *
 * <p>封装一个已编译链接的 GL program object，管理其生命周期、
 * uniform location 缓存与 uniform 上传。组合方式：
 * <pre>{@code
 * GLProgram prog = GLProgram.create(vertexSrc, fragmentSrc);
 * try {
 *     prog.use();
 *     prog.setInt("uAtlas", 0);
 *     prog.setMat2D("uTransform", layerTransform);
 *     // ... draw calls ...
 * } finally {
 *     GLProgram.unuse();
 *     prog.release();
 * }
 * }</pre>
 *
 * <h2>Uniform location 缓存</h2>
 * {@link #uniformLocation(String)} 首次查询某个名字时调用
 * {@code glGetUniformLocation}，之后从内部 {@code HashMap} 命中。
 * 因此同一 program 上反复调用 {@code setXxx} 无重复查询开销。
 *
 * <p><b>查询失败</b>：如果 uniform 不存在或已被编译器优化掉，
 * {@code glGetUniformLocation} 返回 {@code -1}，本类会抛
 * {@link IllegalArgumentException}——不静默忽略。这是有意为之：
 * 拼错的 uniform 名或未使用的 uniform 应该尽早暴露。
 *
 * <h2>矩阵命名</h2>
 * <ul>
 *   <li>{@link #setMat2D} —— 6 元素 2D 仿射矩阵 {@code [a b c d e f]}，
 *       内部补齐为 {@code mat3} 上传。命名沿用 2D 图形学惯例
 *       （Skia / Cairo / CSS {@code matrix()}）</li>
 *   <li>{@link #setMat4} —— 16 元素 4×4 矩阵，直接上传</li>
 * </ul>
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 * 典型方式是通过
 * {@code GLExecutors.offscreen().submit(() -> { ... })} 提交执行。
 * 本类不做线程检查。
 *
 * <h2>生命周期</h2>
 * <ul>
 *   <li>由 {@link #create} 创建，持有编译链接后的 program object</li>
 *   <li>由 {@link #release()} 释放。<b>不实现 {@link AutoCloseable}</b>——
 *       GL 释放必须在 GL 线程上执行，而 {@code try-with-resources}
 *       可能在任何线程上触发 {@code close()}</li>
 *   <li>{@code release()} 幂等；释放后所有其他方法抛
 *       {@link IllegalStateException}</li>
 * </ul>
 *
 * <h2>编译失败</h2>
 * {@link #create} 在着色器编译或程序链接失败时抛
 * {@link GLProgramException}，附带 GL 的 info log。创建过程中
 * 已分配的 shader / program 会被清理，不泄漏。
 */
public final class GLProgram {

    private final int handle;
    private final Map<String, Integer> uniformLocations = new HashMap<>();
    private boolean released = false;

    private GLProgram(int handle) {
        this.handle = handle;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 编译并链接顶点 + 片段着色器。
     *
     * <p>过程：
     * <ol>
     *   <li>编译顶点着色器</li>
     *   <li>编译片段着色器</li>
     *   <li>创建 program，附上两个 shader</li>
     *   <li>链接</li>
     *   <li>删除 shader object（program 已持有其编译结果）</li>
     * </ol>
     *
     * <p>任一步失败都会清理已分配的 GL 对象并抛出
     * {@link GLProgramException}。
     *
     * @param vertexSource   顶点着色器源码，GLSL 版本需与上下文匹配
     * @param fragmentSource 片段着色器源码
     * @return 编译链接成功的 program
     * @throws GLProgramException 编译或链接失败，message 含 GL info log
     */
    public static GLProgram create(String vertexSource, String fragmentSource) {
        int vs = compile(GL_VERTEX_SHADER, vertexSource);
        int fs = compile(GL_FRAGMENT_SHADER, fragmentSource);
        int program = glCreateProgram();
        try {
            glAttachShader(program, vs);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                throw new GLProgramException("Program link failed: " + log);
            }
        } finally {
            glDeleteShader(vs);
            glDeleteShader(fs);
        }
        return new GLProgram(program);
    }

    /**
     * 编译单个着色器。
     *
     * <p>失败时删除已创建的 shader 并抛异常。
     */
    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new GLProgramException(
                    "Shader compile failed (" + type + "): " + log);
        }
        return shader;
    }

    // ═══════════════════════════════════════════════
    // 使用
    // ═══════════════════════════════════════════════

    /**
     * 将本 program 设为当前 program。
     *
     * <p>对应 {@code glUseProgram(handle)}。之后的 uniform 设置
     * 和 draw call 都作用于本 program，直到 {@link #unuse()} 或
     * 另一个 {@code use()}。
     *
     * @throws IllegalStateException program 已释放
     */
    public void use() {
        checkAlive();
        glUseProgram(handle);
    }

    /**
     * 解绑当前 program，回到「无 program」状态。
     *
     * <p>对应 {@code glUseProgram(0)}。静态方法——不依赖任何
     * 实例，因为 GL 的 current program 是全局状态。
     */
    public static void unuse() {
        glUseProgram(0);
    }

    // ═══════════════════════════════════════════════
    // Uniform
    // ═══════════════════════════════════════════════

    /**
     * 查询 uniform location，结果缓存在内部 map 中。
     *
     * <p>首次查询某个名字时调用 {@code glGetUniformLocation}；
     * 之后从缓存返回。同一 program 上反复设置同一个 uniform
     * 无重复查询开销。
     *
     * @param name uniform 名（GLSL 源码中的标识符）
     * @return 非负的 location 值
     * @throws IllegalArgumentException uniform 不存在或已被优化掉
     */
    public int uniformLocation(String name) {
        return uniformLocations.computeIfAbsent(name, n -> {
            int loc = glGetUniformLocation(handle, n);
            if (loc < 0) {
                throw new IllegalArgumentException(
                        "Uniform not found (or optimized out): " + n);
            }
            return loc;
        });
    }

    /** 上传 int uniform（用于 sampler 单元、标志位等）。 */
    public void setInt(String name, int v) { glUniform1i(uniformLocation(name), v); }

    /** 上传 float uniform。 */
    public void setFloat(String name, float v) { glUniform1f(uniformLocation(name), v); }

    /** 上传 vec2 uniform。 */
    public void setVec2(String name, float x, float y) {
        glUniform2f(uniformLocation(name), x, y);
    }

    /** 上传 vec3 uniform。 */
    public void setVec3(String name, float x, float y, float z) {
        glUniform3f(uniformLocation(name), x, y, z);
    }

    /** 上传 vec4 uniform。 */
    public void setVec4(String name, float x, float y, float z, float w) {
        glUniform4f(uniformLocation(name), x, y, z, w);
    }

    /**
     * 上传 2D 仿射矩阵（6 元素 {@code [a b c d e f]}）。
     *
     * <p>内部补齐为列主序的 {@code mat3}：
     * <pre>
     *   | a  b  0 |
     *   | c  d  0 |
     *   | e  f  1 |
     * </pre>
     *
     * <p>对应 shader 中的 {@code uniform mat3}。命名 {@code mat2d}
     * 沿用 2D 图形学惯例——Skia、Cairo、CSS {@code matrix()} 都用
     * 6 元素表示 2D 仿射变换。
     *
     * @param name  uniform 名
     * @param mat2d 6 元素仿射矩阵
     * @throws IllegalArgumentException 数组长度 &lt; 6
     */
    public void setMat2D(String name, float[] mat2d) {
        if (mat2d == null || mat2d.length < 6) {
            throw new IllegalArgumentException(
                    "mat2d must have at least 6 elements, got "
                            + (mat2d == null ? "null" : mat2d.length));
        }
        float[] mat3f = {
                mat2d[0], mat2d[1], 0,
                mat2d[2], mat2d[3], 0,
                mat2d[4], mat2d[5], 1
        };
        glUniformMatrix3fv(uniformLocation(name), false, mat3f);
    }

    /**
     * 上传 4×4 矩阵（16 元素，列主序）。
     *
     * @param name uniform 名
     * @param m16  16 元素列主序矩阵
     * @throws IllegalArgumentException 数组长度 &lt; 16
     */
    public void setMat4(String name, float[] m16) {
        if (m16 == null || m16.length < 16) {
            throw new IllegalArgumentException(
                    "m16 must have at least 16 elements, got "
                            + (m16 == null ? "null" : m16.length));
        }
        glUniformMatrix4fv(uniformLocation(name), false, m16);
    }

    /**
     * 上传 vec2 数组 uniform（如 uniform vec2 uOffsets[8]）。
     *
     * @param name uniform 名
     * @param arr  扁平化的 vec2 数组——{@code [x0,y0,x1,y1,...]}
     */
    public void setVec2Array(String name, float[] arr) {
        glUniform2fv(uniformLocation(name), arr);
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 释放 program object。必须在 GL 线程上调用。幂等。
     *
     * <p>释放后所有其他方法（除查询外）抛 {@link IllegalStateException}。
     */
    public void release() {
        if (released) return;
        glDeleteProgram(handle);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** GL program object 句柄。 */
    public int getHandle() { return handle; }

    /** 是否已释放。 */
    public boolean isReleased() { return released; }

    private void checkAlive() {
        if (released) throw new IllegalStateException("GLProgram released");
    }

    // ═══════════════════════════════════════════════
    // 异常
    // ═══════════════════════════════════════════════

    /**
     * 着色器编译或程序链接失败。
     *
     * <p>message 包含 GL 的 info log——用于定位语法错误、类型
     * 不匹配、未声明的符号等。
     */
    public static final class GLProgramException extends RuntimeException {
        public GLProgramException(String message) { super(message); }
    }
}