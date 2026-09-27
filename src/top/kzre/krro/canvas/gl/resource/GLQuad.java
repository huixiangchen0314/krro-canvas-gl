package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;

/**
 * 全屏四边形：四个顶点的 {@code [0,1]²} 局部坐标，用于实例化渲染。
 *
 * <h2>方向语义</h2>
 *
 * <p>{@code aPos ∈ [0,1]²} 的 y 方向由 {@link Orientation} 声明。
 * shader 里的 {@code world = aInstance.xy * uTileSize + aPos * uTileSize}
 * 得到屏幕像素坐标——aPos.y 的语义决定 fragment 的 {@code vPixel.y}
 * 和屏幕方向是否一致。
 *
 * <ul>
 *   <li>{@link Orientation#Y_DOWN}——{@code aPos.y} 向下增长。
 *       {@code (0,0)} 是四边形左上。{@code vPixel.y} 与屏幕方向一致
 *       （向下增大），后续所有基于 y 的推导不需要额外翻转。</li>
 *   <li>{@link Orientation#Y_UP}——{@code aPos.y} 向上增长。
 *       {@code (0,0)} 是四边形左下——GL 传统约定。{@code vPixel.y}
 *       与屏幕方向相反，shader 里需要 {@code 1 - aPos.y} 才能得到
 *       屏幕方向的像素坐标。</li>
 * </ul>
 *
 * <p><b>推荐 Y_DOWN</b>——canvas 网格、脏区、tile 索引、纹理 uv 全
 * 都按"顶在下标 0"表达。让 quad 也按这个方向，shader 里没有翻转。
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

    /**
     * aPos.y 的方向约定。
     *
     * <p>顶点在几何上覆盖相同的屏幕矩形——两种方向只在
     * <b>aPos 值到屏幕位置的映射</b>上有差异。方向的选择决定
     * shader 里是否需要额外的 y 翻转。
     */
    public enum Orientation {

        /**
         * aPos.y 向上增长。{@code (0,0)} 是左下角——GL 传统约定。
         *
         * <p>顶点数据：{@code (0,1) (1,1) (0,0) (1,0)}。几何位置仍是
         * 屏幕四角，但 aPos.y=0 对应屏幕底、aPos.y=1 对应屏幕顶。
         *
         * <p>shader 里需要 {@code world = (aInstance.xy + vec2(aPos.x, 1-aPos.y)) * uTileSize}
         * 才能得到屏幕方向正确的像素坐标。
         */
        Y_UP,

        /**
         * aPos.y 向下增长。{@code (0,0)} 是左上——屏幕约定。
         *
         * <p>顶点数据：{@code (0,0) (1,0) (0,1) (1,1)}。aPos.y=0 对应
         * 屏幕顶、aPos.y=1 对应屏幕底——与 vPixel / 屏幕像素方向一致。
         *
         * <p>shader 里直接 {@code world = (aInstance.xy + aPos) * uTileSize}，
         * 无翻转。
         */
        Y_DOWN
    }

    private final int vao;
    private final int vbo;
    private final Orientation orientation;
    private boolean released = false;

    private GLQuad(int vao, int vbo, Orientation orientation) {
        this.vao         = vao;
        this.vbo         = vbo;
        this.orientation = orientation;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 创建 GL 传统方向的 quad（{@link Orientation#Y_UP}）。
     *
     * <p>{@code aPos.y} 向上增长，{@code (0,0)} 是左下——GL 约定。
     * 画布 / 屏幕方向的使用方显式传 {@link Orientation#Y_DOWN}。
     */
    public static GLQuad create() {
        return create(Orientation.Y_UP);
    }

    /**
     * 创建指定方向的 quad。
     *
     * <p>VAO + VBO 已就绪，顶点数据已上传。attribute 未配置——
     * 使用方创建后调 {@link #bindPositionAttribute(int)}。
     *
     * @param orientation aPos.y 的方向约定，不能为 null
     * @throws IllegalArgumentException orientation 为 null
     */
    public static GLQuad create(Orientation orientation) {
        if (orientation == null) {
            throw new IllegalArgumentException("orientation must not be null");
        }

        int vao = glGenVertexArrays();
        int vbo = glGenBuffers();
        try {
            glBindVertexArray(vao);
            try {
                glBindBuffer(GL_ARRAY_BUFFER, vbo);

                // TRIANGLE_STRIP 顺序 (0,1,2,3) 决定四边形拓扑：
                //   顶点 0-1 上边，顶点 2-3 下边（屏幕上）
                // 顶点数据只提供 aPos 值——几何位置由 shader 的
                // world = aInstance.xy + aPos 映射得到。
                //
                // Y_DOWN: aPos.y = 0 是屏幕顶 → 顶点 0,1 写 y=0
                // Y_UP:   aPos.y = 0 是屏幕底 → 顶点 0,1 写 y=1
                float[] vertices;
                if (orientation == Orientation.Y_DOWN) {
                    vertices = new float[]{
                            0f, 0f,   // 屏幕顶左 —— aPos = (0, 0)
                            1f, 0f,   // 屏幕顶右 —— aPos = (1, 0)
                            0f, 1f,   // 屏幕底左 —— aPos = (0, 1)
                            1f, 1f    // 屏幕底右 —— aPos = (1, 1)
                    };
                } else {
                    vertices = new float[]{
                            0f, 1f,   // 屏幕顶左 —— aPos = (0, 1)
                            1f, 1f,   // 屏幕顶右 —— aPos = (1, 1)
                            0f, 0f,   // 屏幕底左 —— aPos = (0, 0)
                            1f, 0f    // 屏幕底右 —— aPos = (1, 0)
                    };
                }
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
        return new GLQuad(vao, vbo, orientation);
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
     * @throws IllegalStateException    已释放
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

    /**
     * 本 quad 的 aPos 方向语义。shader 侧据此决定 {@code vPixel} /
     * {@code localUv} 是否需要 y 翻转。
     */
    public Orientation getOrientation() { return orientation; }

    /** 是否已释放。 */
    public boolean isReleased() { return released; }

    private void checkAlive() {
        if (released) throw new IllegalStateException("GLQuad released");
    }
}