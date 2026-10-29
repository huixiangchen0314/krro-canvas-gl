package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL32.glFramebufferTextureLayer;

/**
 * 离屏帧缓冲：把渲染输出到纹理的某一层（可选同时附着 depth 层）。
 *
 * <p><b>借用语义</b>：FBO 只借用颜色 / 深度纹理，不持有其所有权。
 * {@link #close()} 只删除 FBO 对象，不释放任何纹理。纹理的生命周期
 * 由创建它的一方管理。
 *
 * <h2>颜色附着</h2>
 * FBO 附着 {@link GLTexture} 的指定层作为 {@code GL_COLOR_ATTACHMENT0}。
 * 同一张多层纹理可以被多个 FBO 借用，各自附着不同的层。
 *
 * <h2>深度附着（可选）</h2>
 * 通过 {@link Builder#depthAttachment(GLTexture)} +
 * {@link Builder#depthLayer(int)} 附着 {@code GL_DEPTH_ATTACHMENT}。
 * 不传则 FBO 无深度缓冲——此时 {@code glEnable(GL_DEPTH_TEST)} 的
 * 行为是未定义的，调用方必须自行关闭 depth test 或提供 depth attachment。
 *
 * <p>depth attachment 的宽高必须与 color attachment 一致。
 *
 * <h2>尺寸</h2>
 * FBO 尺寸由颜色附着纹理的层尺寸决定。{@link #bind()} 同时设置
 * {@code glViewport}。
 *
 * <h2>不支持</h2>（需要时用新的封装，不要在本类上扩展）：
 * <p>
 * <ul>
 *   <li>MRT（{@code GL_COLOR_ATTACHMENT1~31}）——用 {@code GLMRTFramebuffer}</li>
 *   <li>打包 depth-stencil（{@code GL_DEPTH_STENCIL_ATTACHMENT}）——
 *       用 {@code GLDepthStencilFramebuffer}</li>
 *   <li>纯 stencil（{@code GL_STENCIL_ATTACHMENT}）</li>
 *   <li>layered / cubemap / 3D 纹理附着</li>
 *   <li>depth attachment 只接受纹理</b>，不接受 renderbuffer。
 *  需要 renderbuffer 作为 depth 时，用独立的 FBO 封装
 *  （renderbuffer 不能被采样，适用场景是“只写不读”的纯 depth test，
 * 和需要读回的纹理 depth 不是同一类需求）。</li>
 * </ul>
 *
 * 这个边界是<b>有意的</b>：单一颜色 + 可选深度覆盖了前向渲染、
 * 后处理、离屏合成 90% 的场景；超出这个范围的 FBO 使用方式差异大
 * （attachment 数量、格式约束、mip 级别、layer 选择），塞进一个类
 * 会让构造逻辑和校验逻辑爆炸。
 * </p>
 * <h2>生命周期顺序</h2>
 * 调用方必须保证：
 * <ul>
 *   <li>所有借用的纹理先于 FBO 创建</li>
 *   <li>FBO 先于所有借用的纹理释放</li>
 * </ul>
 * 反序会导致 FBO 指向无效纹理，渲染静默失败。
 *
 * <h2>线程契约</h2>
 * <b>所有方法必须在 GL 线程（current context）上调用。</b>
 */
public final class GLFramebuffer implements AutoCloseable {

    private final int fbo;
    private final GLTexture colorAttachment;
    private final int colorLayer;
    /** 可空。null 表示无深度附着。 */
    private final GLTexture depthAttachment;
    private final int depthLayer;
    private final int width;
    private final int height;
    private boolean released = false;

    private GLFramebuffer(int fbo,
                          GLTexture colorAttachment, int colorLayer,
                          GLTexture depthAttachment, int depthLayer,
                          int width, int height) {
        this.fbo             = fbo;
        this.colorAttachment = colorAttachment;
        this.colorLayer      = colorLayer;
        this.depthAttachment = depthAttachment;
        this.depthLayer      = depthLayer;
        this.width           = width;
        this.height          = height;
    }

    // ═══════════════════════════════════════════════
    // 兼容入口
    // ═══════════════════════════════════════════════

    /**
     * 创建 FBO，以给定纹理的指定层作为颜色附着。等价于
     * {@code builder().colorAttachment(t).colorLayer(l).build()}。
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
        return builder()
                .colorAttachment(colorAttachment)
                .colorLayer(layer)
                .build();
    }

    // ═══════════════════════════════════════════════
    // Builder
    // ═══════════════════════════════════════════════

    public static Builder builder() {
        return new Builder();
    }

    /**
     * FBO 构造器。
     *
     * <p><b>必填</b>：{@code colorAttachment} + {@code colorLayer}。
     *
     * <p><b>可选</b>：{@code depthAttachment} + {@code depthLayer}。
     * 不传则 FBO 无深度缓冲。
     *
     * <p>{@link #build()} 必须在 GL 线程调用。
     */
    public static final class Builder {

        private GLTexture colorAttachment;
        private int colorLayer = -1;

        private GLTexture depthAttachment;
        private int depthLayer = 0;

        public Builder colorAttachment(GLTexture colorAttachment) {
            this.colorAttachment = colorAttachment;
            return this;
        }

        public Builder colorLayer(int colorLayer) {
            this.colorLayer = colorLayer;
            return this;
        }

        public Builder depthAttachment(GLTexture depthAttachment) {
            this.depthAttachment = depthAttachment;
            return this;
        }

        public Builder depthLayer(int depthLayer) {
            this.depthLayer = depthLayer;
            return this;
        }

        /**
         * 构造 FBO。
         *
         * @throws IllegalArgumentException 参数非法（null、层越界、尺寸不匹配）
         * @throws IllegalStateException    FBO 不完整
         */
        public GLFramebuffer build() {
            // ── 参数校验 ──
            if (colorAttachment == null) {
                throw new IllegalArgumentException(
                        "colorAttachment must not be null");
            }
            if (colorLayer < 0 || colorLayer >= colorAttachment.getLayers()) {
                throw new IllegalArgumentException(
                        "colorLayer " + colorLayer + " out of [0, "
                                + colorAttachment.getLayers() + ")");
            }
            if (depthAttachment != null) {
                PixelFormat pf = depthAttachment.getPixelFormat();
                if (pf == null) {
                    throw new IllegalStateException(
                            "depthAttachment has null PixelFormat");
                }
                FboUtils.requirePureDepthFormat(pf.getGlInternalFormat(), "GLFramebuffer");
                if (depthLayer < 0 || depthLayer >= depthAttachment.getLayers()) {
                    throw new IllegalArgumentException(
                            "depthLayer " + depthLayer + " out of [0, "
                                    + depthAttachment.getLayers() + ")");
                }
                if (depthAttachment.getWidth() != colorAttachment.getWidth()
                        || depthAttachment.getHeight() != colorAttachment.getHeight()) {
                    throw new IllegalArgumentException(
                            "depth attachment size mismatch: color="
                                    + colorAttachment.getWidth() + "x"
                                    + colorAttachment.getHeight()
                                    + ", depth=" + depthAttachment.getWidth()
                                    + "x" + depthAttachment.getHeight());
                }
            }

            // ── 创建 FBO ──
            int fbo = glGenFramebuffers();
            boolean ok = false;
            try {
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);

                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        colorAttachment.getHandle(), 0, colorLayer);

                if (depthAttachment != null) {
                    glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                            depthAttachment.getHandle(), 0, depthLayer);
                }

                int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException(
                            "FBO incomplete: status = 0x"
                                    + Integer.toHexString(status));
                }

                ok = true;
            } finally {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                if (!ok) {
                    glDeleteFramebuffers(fbo);
                }
            }

            return new GLFramebuffer(
                    fbo,
                    colorAttachment, colorLayer,
                    depthAttachment, depthLayer,
                    colorAttachment.getWidth(), colorAttachment.getHeight());
        }
    }

    // ═══════════════════════════════════════════════
    // 使用
    // ═══════════════════════════════════════════════

    /**
     * 解绑，回到默认帧缓冲。静态方法。
     *
     * <p>不恢复 viewport。
     */
    public static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * 绑定为当前渲染目标，同时设置 viewport。
     */
    public void bind() {
        checkAlive();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
    }

    /**
     * 清空颜色缓冲；有 depth attachment 时同时清深度缓冲。
     */
    public void clear(float r, float g, float b, float a) {
        checkAlive();
        bind();
        glClearColor(r, g, b, a);
        int mask = GL_COLOR_BUFFER_BIT;
        if (depthAttachment != null) {
            mask |= GL_DEPTH_BUFFER_BIT;
        }
        glClear(mask);
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 删除 FBO 对象。必须在 GL 线程上调用。幂等。
     *
     * <p><b>不释放任何纹理</b>——颜色 / 深度纹理的所有权都在调用方。
     * 调用方负责在所有借用它的 FBO 都释放后，再释放纹理。
     */
    @Override
    public void close() {
        if (released) return;
        glDeleteFramebuffers(fbo);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** FBO 句柄。 */
    public int getFbo() {
        return fbo;
    }

    /**
     * 颜色附着纹理。所有权在调用方，不要在外部 release。
     */
    public GLTexture getColorAttachment() {
        return colorAttachment;
    }

    /**
     * 颜色附着的层索引。等价于 {@link #getColorLayer()}。
     */
    @Deprecated
    public int getLayer() {
        return colorLayer;
    }

    /** 颜色附着的层索引。 */
    public int getColorLayer() {
        return colorLayer;
    }

    /**
     * 深度附着纹理。所有权在调用方；无深度附着时返回 {@code null}。
     */
    public GLTexture getDepthAttachment() {
        return depthAttachment;
    }

    /** 深度附着的层索引。无深度附着时无意义。 */
    public int getDepthLayer() {
        return depthLayer;
    }

    /** 是否有深度附着。 */
    public boolean hasDepth() {
        return depthAttachment != null;
    }

    /** 宽（像素）。 */
    public int getWidth() {
        return width;
    }

    /** 高（像素）。 */
    public int getHeight() {
        return height;
    }

    /** 是否已释放。 */
    public boolean isReleased() {
        return released;
    }

    private void checkAlive() {
        if (released) throw new IllegalStateException("GLFramebuffer released");
    }
}