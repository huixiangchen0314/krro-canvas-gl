package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;

/**
 * 全屏四边形：四个顶点的 {@code [0,1]²} 局部坐标，用于实例化渲染。
 *
 * <p>顶点数据顺序（triangle strip）：
 * <pre>
 *   索引 0: (0, 0)   左下
 *   索引 1: (1, 0)   右下
 *   索引 2: (0, 1)   左上
 *   索引 3: (1, 1)   右上
 * </pre>
 *
 * <h2>Attribute location</h2>
 *
 * <p><b>本类不假设任何 location</b>。VAO 创建时只有顶点数据，
 * attribute 的配置由使用方通过 {@link #bindPositionAttribute(int)}
 * 完成——使用方知道它的 shader 里顶点位置声明在哪个 location。
 *
 * <p>instance buffer 的 attribute 由使用方通过
 * {@link GLInstanceBuffer} 绑到其他 location。多个 attribute 的
 * location 分配是使用方的责任。
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 */
public final class GLQuad {

    private final int vao;
    private final int vbo;
    private boolean released = false;

    private GLQuad(int vao, int vbo) {
        this.vao = vao;
        this.vbo = vbo;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 创建全屏四边形。VAO + VBO 已就绪，顶点数据已上传。
     *
     * <p>attribute 未配置——使用方创建后调
     * {@link #bindPositionAttribute(int)} 声明顶点位置在哪个 location。
     */
    public static GLQuad create() {
        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        try {
            glBindVertexArray(vao);
            try {
                glBindBuffer(GL_ARRAY_BUFFER, vbo);
                float[] vertices = {
                        0f, 0f,
                        1f, 0f,
                        0f, 1f,
                        1f, 1f
                };
                glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
            } finally {
                glBindVertexArray(0);
                glBindBuffer(GL_ARRAY_BUFFER, 0);
            }
        } catch (Throwable t) {
            glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao);
            throw t;
        }
        return new GLQuad(vao, vbo);
    }

    // ═══════════════════════════════════════════════
    // Attribute 配置（由使用方调用）
    // ═══════════════════════════════════════════════

    /**
     * 声明顶点位置的 attribute location。
     *
     * <p>使用方知道它的 shader 里 {@code layout(location = N) in vec2 aPos}
     * 的 N——传进来，本类把它绑到 VAO。
     *
     * <p><b>每个 quad 实例调一次</b>——配置存入 VAO 后一直有效，
     * 后续 {@link #drawInstanced} 自动应用。
     *
     * @param location 顶点位置的 attribute location，必须 ≥ 0
     * @throws IllegalArgumentException location &lt; 0
     */
    public void bindPositionAttribute(int location) {
        checkAlive();
        if (location < 0) {
            throw new IllegalArgumentException(
                    "location must be >= 0: " + location);
        }
        glBindVertexArray(vao);
        try {
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glEnableVertexAttribArray(location);
            glVertexAttribPointer(location, 2, GL_FLOAT, false, 8, 0L);
        } finally {
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    // ═══════════════════════════════════════════════
    // 使用
    // ═══════════════════════════════════════════════

    /**
     * 绑定 VAO，使 vertex attribute 配置生效。
     */
    public void bind() {
        checkAlive();
        glBindVertexArray(vao);
    }

    /** 解绑当前 VAO。静态方法——GL 的绑定是全局状态。 */
    public static void unbind() {
        glBindVertexArray(0);
    }

    /**
     * 实例化绘制。
     *
     * @param instanceCount 实例数（tile 数）
     */
    public void drawInstanced(int instanceCount) {
        checkAlive();
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instanceCount);
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /** 释放 VAO 和 VBO。必须在 GL 线程上调用。幂等。 */
    public void release() {
        if (released) return;
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** VAO 句柄。 */
    public int getVao() { return vao; }

    /** VBO 句柄。 */
    public int getVbo() { return vbo; }

    /** 是否已释放。 */
    public boolean isReleased() { return released; }

    private void checkAlive() {
        if (released) throw new IllegalStateException("GLQuad released");
    }
}