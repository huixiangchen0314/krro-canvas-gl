package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL30.*;

/**
 * 离屏帧缓冲：把渲染输出到纹理而非默认帧缓冲。
 *
 * <p>用途：合成输出。backdrop 的 GPU 表示是一个 FBO，颜色附着为一张
 * {@link GLTexture}（layers=1 的 2D_ARRAY）。合成时所有 draw call
 * 写入这个 FBO，完成后由 FBO 的颜色附着提供结果。
 *
 * <h2>颜色附着</h2>
 * FBO 持有一张 {@link GLTexture} 作为 {@code GL_COLOR_ATTACHMENT0}。
 * <b>纹理所有权在 FBO</b>——{@link #release()} 会一并释放它。调用方
 * 不需要（也不应该）单独 release 颜色纹理。
 *
 * <h2>尺寸</h2>
 * FBO 尺寸由颜色附着的纹理决定。{@link #bind()} 会同时设置
 * {@code glViewport}。
 *
 * <h2>完整性检查</h2>
 * {@link #create} 在绑定附着后检查 {@code glCheckFramebufferStatus}。
 * 不完整时抛异常，避免后续渲染静默失败。
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 *
 * <h2>生命周期</h2>
 * 由 {@link #create} 创建，由 {@link #release()} 释放。不实现
 * {@link AutoCloseable}——释放必须在 GL 线程上执行。
 */
public final class GLFramebuffer {

    private final int fbo;
    private final GLTexture colorAttachment;
    private final int width;
    private final int height;
    private boolean released = false;

    private GLFramebuffer(int fbo, GLTexture colorAttachment, int width, int height) {
        this.fbo = fbo;
        this.colorAttachment = colorAttachment;
        this.width = width;
        this.height = height;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 创建 FBO，以给定纹理作为颜色附着。
     *
     * <p>纹理所有权转移给 FBO——调用方不应再持有引用或单独释放。
     * 创建失败时纹理<b>不会</b>被释放（调用方仍需自己清理）。
     *
     * @param colorAttachment 颜色附着纹理，通常为
     *                        {@code GLTexture.createRgba8(w, h)} 创建
     * @return 完整可用的 FBO
     * @throws IllegalStateException FBO 不完整
     */
    public static GLFramebuffer create(GLTexture colorAttachment) {
        if (colorAttachment == null) {
            throw new IllegalArgumentException("colorAttachment must not be null");
        }
        int fbo = glGenFramebuffers();
        try {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            try {
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D_ARRAY,
                        colorAttachment.getHandle(), 0);
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
        return new GLFramebuffer(fbo, colorAttachment,
                colorAttachment.getWidth(),
                colorAttachment.getHeight());
    }

    // ═══════════════════════════════════════════════
    // 使用
    // ═══════════════════════════════════════════════

    /**
     * 绑定为当前渲染目标，同时设置 viewport。
     *
     * <p>对应 {@code glBindFramebuffer(GL_FRAMEBUFFER, fbo)} +
     * {@code glViewport(0, 0, width, height)}。之后的 draw call
     * 写入这个 FBO。
     */
    public void bind() {
        checkAlive();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
    }

    /**
     * 解绑，回到默认帧缓冲。静态方法——GL 的绑定是全局状态。
     *
     * <p>注意：不恢复 viewport。如果后续要渲染到默认帧缓冲，调用方
     * 需要自行设置合适的 viewport。
     */
    public static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * 清空颜色缓冲。
     *
     * <p>先 {@link #bind()} 再清空——保证清的是本 FBO 而不是其他目标。
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
     * 释放 FBO 和颜色附着纹理。必须在 GL 线程上调用。幂等。
     *
     * <p>颜色纹理的所有权在本 FBO——{@code release()} 会一并释放它。
     */
    public void release() {
        if (released) return;
        glDeleteFramebuffers(fbo);
        colorAttachment.release();
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** FBO 句柄。 */
    public int getFbo() { return fbo; }

    /** 颜色附着纹理。所有权在 FBO，不要在外部 release。 */
    public GLTexture getColorAttachment() { return colorAttachment; }

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