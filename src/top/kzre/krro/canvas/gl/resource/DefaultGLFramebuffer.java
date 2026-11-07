package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glFramebufferTextureLayer;

/**
 * 默认的 FBO，只有颜色缓冲
 */
public final class DefaultGLFramebuffer extends ColorAttachedFrameBuffer {
    private final GLTexture colorAttachment;
    private final int colorLayer;

    private DefaultGLFramebuffer(int fbo,
                                 int width, int height,
                                 SerialExecutor glThread,
                         GLTexture colorAttachment, int colorLayer) {
        super(fbo, width, height, glThread);
        this.colorAttachment = colorAttachment;
        this.colorLayer = colorLayer;
        colorAttachment.assocFrameBuffer(colorLayer, this);
    }

    @Override
    public int getColorLayer() {
        return colorLayer;
    }

    @Override
    public GLTexture getColorAttachment() {
        return colorAttachment;
    }

    /**
     * 清空颜色缓冲
     */
    @Override
    public void clear(float r, float g, float b, float a) {
        glClearColor(r, g, b, a);
        glClear(GL_COLOR_BUFFER_BIT);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private GLTexture colorAttachment;
        private int colorLayer = -1;
        private SerialExecutor glThread;

        public Builder glThread(SerialExecutor glThread) {
            this.glThread = glThread;
            return this;
        }

        public Builder colorAttachment(GLTexture colorAttachment) {
            this.colorAttachment = colorAttachment;
            return this;
        }

        public Builder colorLayer(int colorLayer) {
            this.colorLayer = colorLayer;
            return this;
        }


        /**
         * 构造 FBO。
         *
         * @throws IllegalArgumentException 参数非法（null、层越界、尺寸不匹配）
         * @throws IllegalStateException    FBO 不完整
         */
        public DefaultGLFramebuffer build() {
            // ── 参数校验 ──
            if (glThread == null) {
                throw new IllegalStateException("GL thread has not been set");
            }
            if (colorAttachment == null) {
                throw new IllegalArgumentException(
                        "colorAttachment must not be null");
            }
            if (colorLayer < 0 || colorLayer >= colorAttachment.getLayers()) {
                throw new IllegalArgumentException(
                        "colorLayer " + colorLayer + " out of [0, "
                                + colorAttachment.getLayers() + ")");
            }


            // ── 创建 FBO ──
            int fbo = glGenFramebuffers();
            boolean ok = false;
            try {
                glBindFramebuffer(GL_FRAMEBUFFER, fbo);

                glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        colorAttachment.getHandle(), 0, colorLayer);

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

            return new DefaultGLFramebuffer(
                    fbo,colorAttachment.getWidth(), colorAttachment.getHeight(),
                    glThread,
                    colorAttachment, colorLayer
            );
        }
    }

}
