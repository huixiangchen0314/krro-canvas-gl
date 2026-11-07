// FixedSizeDepthFrameBufferPool.java
package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.core.util.SerialExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 带深度附件的固定尺寸 FBO 池。
 *
 * <p>每个颜色层对应的 FBO 同时附着：
 * <ul>
 *   <li>{@code GL_COLOR_ATTACHMENT0} → 颜色纹理第 {@code i} 层</li>
 *   <li>{@code GL_DEPTH_ATTACHMENT}  → 深度纹理第 {@code i} 层</li>
 * </ul>
 *
 * <p>每个 Holder 借一张颜色纹理 + 一张深度纹理；Holder 回收时两张
 * 纹理都归还。颜色/深度纹理的宽高必须一致，深度层数不少于颜色层数。
 */
public final class FixedSizeDepthFrameBufferPool
        extends FixedSizeFrameBufferPool<DepthGLFramebuffer> {

    private final FixedSizeTexturePool colorTexturePool;
    private final FixedSizeTexturePool depthTexturePool;

    public FixedSizeDepthFrameBufferPool(FixedSizeTexturePool colorTexturePool,
                                         FixedSizeTexturePool depthTexturePool) {
        super(Objects.requireNonNull(colorTexturePool, "colorTexturePool").getCapacity(),
                colorTexturePool.getLayers());
        if (depthTexturePool == null) {
            throw new IllegalArgumentException("depthTexturePool must not be null");
        }
        if (depthTexturePool.getLayers() < colorTexturePool.getLayers()) {
            throw new IllegalArgumentException(
                    "depthTexturePool layers (" + depthTexturePool.getLayers()
                            + ") < colorTexturePool layers ("
                            + colorTexturePool.getLayers() + ")");
        }
        this.colorTexturePool = colorTexturePool;
        this.depthTexturePool = depthTexturePool;
    }

    @Override
    protected SlotHolder createHolder() {
        GLTexture color = colorTexturePool.acquire();
        GLTexture depth = null;
        boolean ok = false;
        try {
            depth = depthTexturePool.acquire();

            if (depth.getWidth() != color.getWidth()
                    || depth.getHeight() != color.getHeight()) {
                throw new IllegalStateException(
                        "depth texture size mismatch: color="
                                + color.getWidth() + "x" + color.getHeight()
                                + ", depth=" + depth.getWidth()
                                + "x" + depth.getHeight());
            }
            if (depth.getLayers() < color.getLayers()) {
                throw new IllegalStateException(
                        "depth texture has " + depth.getLayers()
                                + " layers, need at least " + color.getLayers());
            }

            int layers = color.getLayers();
            List<DepthGLFramebuffer> fbos = new ArrayList<>(layers);
            for (int i = 0; i < layers; i++) {
                fbos.add(DepthGLFramebuffer.builder()
                        .colorAttachment(color)
                        .colorLayer(i)
                        .depthAttachment(depth)
                        .depthLayer(i)
                        .glThread(getGlExecutor())
                        .build());
            }

            ok = true;
            return new Holder(color, depth, fbos);
        } finally {
            if (!ok) {
                if (depth != null) depthTexturePool.release(depth);
                colorTexturePool.release(color);
            }
        }
    }

    @Override
    protected void releaseHolderTextures(SlotHolder holder) {
        Holder h = (Holder) holder;
        colorTexturePool.release(h.colorTexture);
        depthTexturePool.release(h.depthTexture);
    }

    @Override
    protected void closeTexturePools() {
        Throwable e = null;
        try {
            colorTexturePool.close();
        } catch (Throwable t) {
            e = t;
        }
        try {
            depthTexturePool.close();
        } catch (Throwable t) {
            e = ResourceUtils.accumulateException(e, t);
        }
        if (e != null) {
            throw ResourceUtils.wrapError(e);
        }
    }

    @Override
    public boolean isClosed() {
        return colorTexturePool.isClosed();
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return colorTexturePool.getGlExecutor();
    }

    private final class Holder extends SlotHolder {
        private final GLTexture colorTexture;
        private final GLTexture depthTexture;

        Holder(GLTexture colorTexture, GLTexture depthTexture, List<DepthGLFramebuffer> fbos) {
            super(fbos);
            this.colorTexture = colorTexture;
            this.depthTexture = depthTexture;
        }

        @Override
        public GLTexture colorTexture() {
            return colorTexture;
        }
    }
}