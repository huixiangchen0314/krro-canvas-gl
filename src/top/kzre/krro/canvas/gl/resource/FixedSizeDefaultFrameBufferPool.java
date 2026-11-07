// FixedSizeFrameBufferPool.java
package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 不带深度附件的固定尺寸 FBO 池。
 *
 * <p>每个颜色层对应一个只有 {@code GL_COLOR_ATTACHMENT0} 的 FBO。
 * 若调用方启用 {@code GL_DEPTH_TEST}，行为未定义——必须自行关闭
 * depth test，或改用 {@link FixedSizeDepthFrameBufferPool}。
 */
public final class FixedSizeDefaultFrameBufferPool
        extends FixedSizeFrameBufferPool<DefaultGLFramebuffer> {

    private final FixedSizeTexturePool texturePool;

    /**
     * @param texturePool 颜色纹理池，所有权转移给本池
     */
    public FixedSizeDefaultFrameBufferPool(FixedSizeTexturePool texturePool) {
        super(Objects.requireNonNull(texturePool, "texturePool").getCapacity(),
                texturePool.getLayers());
        this.texturePool = texturePool;
    }

    @Override
    protected SlotHolder createHolder() {
        GLTexture tex = texturePool.acquire();
        boolean ok = false;
        try {
            int layers = tex.getLayers();
            List<DefaultGLFramebuffer> fbos = new ArrayList<>(layers);
            for (int i = 0; i < layers; i++) {
                fbos.add(DefaultGLFramebuffer.builder()
                        .colorAttachment(tex)
                        .colorLayer(i)
                        .glThread(getGlExecutor())
                        .build());
            }
            ok = true;
            return new Holder(tex, fbos);
        } finally {
            if (!ok) texturePool.release(tex);
        }
    }

    @Override
    protected void releaseHolderTextures(SlotHolder holder) {
        texturePool.release(((Holder) holder).texture);
    }

    @Override
    protected void closeTexturePools() {
        texturePool.close();
    }

    @Override
    public boolean isClosed() {
        return texturePool.isClosed();
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return texturePool.getGlExecutor();
    }

    private final class Holder extends SlotHolder {
        private final GLTexture texture;

        Holder(GLTexture texture, List<DefaultGLFramebuffer> fbos) {
            super(fbos);
            this.texture = texture;
        }

        @Override
        public GLTexture colorTexture() {
            return texture;
        }
    }
}