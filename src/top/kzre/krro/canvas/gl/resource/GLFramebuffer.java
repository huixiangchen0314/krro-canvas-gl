package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.glFramebufferTextureLayer;

/**
 * 离屏帧缓冲：把渲染输出到纹理的某一层。
 *
 * <p><b>借用语义</b>：FBO 只借用颜色纹理，不持有其所有权。
 * {@link #release()} 只删除 FBO 对象，不释放纹理。纹理的生命周期
 * 由创建它的一方管理。
 *
 * <h2>颜色附着</h2>
 * FBO 附着 {@link GLTexture} 的指定层作为 {@code GL_COLOR_ATTACHMENT0}。
 * 同一张多层纹理可以被多个 FBO 借用，各自附着不同的层。
 *
 * <h2>尺寸</h2>
 * FBO 尺寸由颜色附着纹理的层尺寸决定。{@link #bind()} 同时设置
 * {@code glViewport}。
 *
 * <h2>生命周期顺序</h2>
 * 调用方必须保证：
 * <ul>
 *   <li>纹理先于 FBO 创建</li>
 *   <li>FBO 先于纹理释放</li>
 * </ul>
 * 反序会导致 FBO 指向无效纹理，渲染静默失败。
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 */
public final class GLFramebuffer {

    private final int fbo;
    private final GLTexture colorAttachment;
    private final int layer;
    private final int width;
    private final int height;
    private boolean released = false;

    private GLFramebuffer(int fbo, GLTexture colorAttachment, int layer,
                          int width, int height) {
        this.fbo = fbo;
        this.colorAttachment = colorAttachment;
        this.layer = layer;
        this.width = width;
        this.height = height;
    }

    // ═══════════════════════════════════════════════
    // 包装
    // ═══════════════════════════════════════════════

    /**
     * 创建 FBO，以给定纹理的指定层作为颜色附着。
     *
     * <p><b>纹理所有权仍在调用方</b>——FBO 只借用。调用方负责在
     * FBO 释放后再释放纹理。
     *
     * @param colorAttachment 颜色附着纹理，必须为 {@code GL_TEXTURE_2D_ARRAY}
     * @param layer           层索引，{@code [0, texture.getLayers())}
     * @return 完整可用的 FBO
     * @throws IllegalArgumentException 参数非法
     * @throws IllegalStateException    FBO 不完整
     */
    public static GLFramebuffer wrap(GLTexture colorAttachment, int layer) {
        if (colorAttachment == null) {
            throw new IllegalArgumentException("colorAttachment must not be null");
        }
        if (layer < 0 || layer >= colorAttachment.getLayers()) {
            throw new IllegalArgumentException(
                    "layer " + layer + " out of [0, " + colorAttachment.getLayers() + ")");
        }
        int fbo = glGenFramebuffers();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            try {
                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        colorAttachment.getHandle(), 0, layer);
                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException(
                            "FBO incomplete: status = 0x"
                                    + Integer.toHexString(status));
                }
            } finally {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
            }
        } catch (Throwable t) {
            glDeleteFramebuffers(fbo);
            throw t;
        }
        return new GLFramebuffer(fbo, colorAttachment, layer,
                colorAttachment.getWidth(), colorAttachment.getHeight());
    }

    // ═══════════════════════════════════════════════
    // 使用
    // ═══════════════════════════════════════════════

    /**
     * 绑定为当前渲染目标，同时设置 viewport。
     */
    public void bind() {
        checkAlive();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
    }

    /**
     * 解绑，回到默认帧缓冲。静态方法。
     *
     * <p>不恢复 viewport。
     */
    public static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * 清空颜色缓冲。
     */
    public void clear(float r, float g, float b, float a) {
        checkAlive();
        bind();
        glClearColor(r, g, b, a);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 删除 FBO 对象。必须在 GL 线程上调用。幂等。
     *
     * <p><b>不释放颜色纹理</b>——纹理所有权在调用方。调用方负责在
     * 所有借用它的 FBO 都释放后，再释放纹理。
     */
    public void release() {
        if (released) return;
        glDeleteFramebuffers(fbo);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** FBO 句柄。 */
    public int getFbo() { return fbo; }

    /**
     * 颜色附着纹理。所有权在调用方，不要在外部 release。
     */
    public GLTexture getColorAttachment() { return colorAttachment; }

    /** 颜色附着的层索引。 */
    public int getLayer() { return layer; }

    /** 宽（像素）。 */
    public int getWidth() { return width; }

    /** 高（像素）。 */
    public int getHeight() { return height; }

    /** 是否已释放。 */
    public boolean isReleased() { return released; }

    private void checkAlive() {
        if (released) throw new IllegalStateException("GLFramebuffer released");
    }
}