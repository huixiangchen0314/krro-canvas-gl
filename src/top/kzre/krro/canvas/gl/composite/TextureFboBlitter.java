package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLTexture;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 把 {@link GLTexture} 的某一层 blit 到当前绑定的 draw framebuffer。
 *
 * <p>用固定管线 {@code glBlitFramebuffer}——不需要 shader、不需要 quad。
 * 内部持有一个私有 read FBO，把源纹理的目标层临时 attach 上去，
 * blit 后立即分离。
 *
 * <p><b>目标 framebuffer 由调用方预先绑定</b>——blit 只操作
 * {@code GL_READ_FRAMEBUFFER}，不改变 {@code GL_DRAW_FRAMEBUFFER}。典型用法：
 * <pre>{@code
 * glBindFramebuffer(GL_FRAMEBUFFER, event.getFbo());
 * blitter.blit(backdropTexture, 0, event.getWidth(), event.getHeight());
 * }</pre>
 *
 * <p><b>所有权</b>：与 {@link GLTexture} 一致——不实现
 * {@link AutoCloseable}，显式在 GL 线程上调 {@link #close()}。释放幂等。
 *
 * <p><b>线程契约</b>：仅 GL 线程调用；与使用它的 GL 上下文同寿。
 */
public final class TextureFboBlitter implements AutoCloseable {

    private int readFbo = 0;
    private volatile boolean closed = false;

    public TextureFboBlitter() {
        // 延迟到首次 blit 时创建——构造不要求 GL 上下文
    }

    private int ensureReadFbo() {
        int fbo = readFbo;
        if (fbo == 0) {
            fbo = glGenFramebuffers();
            readFbo = fbo;
        }
        return fbo;
    }

    public void blit(GLTexture texture,
                     int layer,
                     int targetFbo,
                     int viewWidth, int viewHeight) {
        if (closed) {
            throw new IllegalStateException("ViewTextureBlitter has been released");
        }
        if (texture == null) {
            throw new NullPointerException("texture must not be null");
        }
        if (texture.isReleased()) {
            throw new IllegalStateException("texture has been released");
        }
        if (layer < 0 || layer >= texture.getLayers()) {
            throw new IndexOutOfBoundsException(
                    "layer " + layer + " out of [0, " + texture.getLayers() + ")");
        }
        if (viewWidth <= 0 || viewHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid dst size: " + viewWidth + "x" + viewHeight);
        }

        // ★ 先绑目标 FBO 并 clear
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, targetFbo);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);


        int fbo       = ensureReadFbo();
        int texHandle = texture.getHandle();
        int srcW      = texture.getWidth();
        int srcH      = texture.getHeight();


        glBindFramebuffer(GL_READ_FRAMEBUFFER, targetFbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        try {
            glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    texHandle, 0, layer);
            glReadBuffer(GL_COLOR_ATTACHMENT0);

            glBlitFramebuffer(
                    0, 0, srcW, srcH,
                    0, 0, viewWidth, viewHeight,
                    GL_COLOR_BUFFER_BIT, GL_NEAREST);

            glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    0, 0, 0);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("ViewTextureBlitter has been released");
        }
        if (readFbo != 0) {
            glDeleteFramebuffers(readFbo);
            readFbo = 0;
        }
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}