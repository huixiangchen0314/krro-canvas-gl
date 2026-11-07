package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

import static org.lwjgl.opengl.GL30.*;

public abstract class GLFramebuffer implements AutoCloseable, GLThreadAffine{

    /**
     * 解绑，回到默认帧缓冲。静态方法。
     *
     * <p>不恢复 viewport。
     */
    public static void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    private final int fbo;
    private final int width;
    private final int height;
    private boolean released = false;
    private final SerialExecutor glThread;

    protected GLFramebuffer(int fbo,
                            int width, int height,
                            SerialExecutor glThread) {
        this.fbo             = fbo;
        this.width           = width;
        this.height          = height;
        this.glThread = glThread;
    }

    /**
     * 绑定为当前渲染目标，同时设置 viewport。
     * 默认不设置viewport
     */
    public void bind() {
        bind(false);
    }

    public void bind(boolean fullViewport) {
        checkReleased();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        if (fullViewport) {
            glViewport(0, 0, width, height);
        }
    }

    public int getHandle() {
        return fbo;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isReleased() {
        return released;
    }

    @Override
    public void close() {
        if (released) return;
        glDeleteFramebuffers(fbo);
        released = true;
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return glThread;
    }

    private void checkReleased() {
        if (released) throw new IllegalStateException("GLFramebuffer released");
    }
}
