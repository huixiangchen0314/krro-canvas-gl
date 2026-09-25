package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

/**
 * GL 线程安全的纹理释放。把释放动作投递到 GL 线程执行。
 *
 * <p><b>不池化</b>：每次 release 都实际释放纹理。池化逻辑由外层
 * 的 {@link FixedSizeTexturePool} 承担，本类只保证释放操作在正确
 * 的线程上执行。
 *
 * <p><b>线程契约</b>：{@code release} 可在任意线程调用。
 */
public final class NoGLTexturePool implements GLTexturePool {

    private final SerialExecutor glExecutor;
    private final long           threadId;

    public NoGLTexturePool(SerialExecutor glExecutor) {
        if (glExecutor == null) {
            throw new IllegalArgumentException("glExecutor must not be null");
        }
        this.glExecutor = glExecutor;
        this.threadId   = glExecutor.getTheadId();
    }

    public SerialExecutor getGlExecutor() {
        return glExecutor;
    }

    @Override
    public void release(GLTexture texture) {
        if (texture == null || texture.isReleased()) return;

        if (Thread.currentThread().getId() == threadId) {
            texture.release();
        } else {
            glExecutor.submit(texture::release);
        }
    }
}