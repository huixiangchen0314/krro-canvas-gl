package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;

/**
 * 全屏四边形：四个顶点的 {@code [0,1]²} 局部坐标，用于实例化渲染。
 *
 * <p>合成场景下，所有 tile 共用同一个 quad——每个 tile 的 descriptor
 * 作为 instance attribute 携带，draw call 用
 * {@code glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, n)} 一次画出
 * n 个 tile。
 *
 * <p>顶点数据顺序（triangle strip）：
 * <pre>
 *   索引 0: (0, 0)   左下
 *   索引 1: (1, 0)   右下
 *   索引 2: (0, 1)   左上
 *   索引 3: (1, 1)   右上
 * </pre>
 * 局部坐标 {@code (0,0)} 在左下角——与 GL 纹理坐标原点一致。
 *
 * <h2>Attribute 布局</h2>
 * <ul>
 *   <li>{@code location = 0}：{@code vec2 aQuad}——顶点局部坐标</li>
 * </ul>
 * 其他 attribute（instance descriptor）由 {@link GLInstanceBuffer}
 * 绑定到更高 location。
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 *
 * <h2>生命周期</h2>
 * 由 {@link #create()} 创建，由 {@link #release()} 释放。不实现
 * {@link AutoCloseable}——释放必须在 GL 线程上执行。
 */
public final class GLQuad {

    /** 顶点 attribute location：局部坐标。 */
    public static final int LOCATION_QUAD = 0;

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
     * 创建全屏四边形。
     *
     * <p>创建 VAO + VBO，上传 4 个顶点的局部坐标，配置 location 0
     * 的 attribute pointer。失败时清理已分配对象。
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

                glEnableVertexAttribArray(LOCATION_QUAD);
                glVertexAttribPointer(LOCATION_QUAD, 2, GL_FLOAT, false, 8, 0L);
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
    // 使用
    // ═══════════════════════════════════════════════

    /**
     * 绑定 VAO，使 vertex attribute 配置生效。
     *
     * <p>绑定后 instance buffer 的 upload 和 draw 都作用于此 VAO。
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
     * <p>对应 {@code glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count)}。
     * 调用前必须已 {@link #bind()} 并上传了 instance 数据。
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