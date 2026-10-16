package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.util.NumberUtils;

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
 * {@code GL_READ_FRAMEBUFFER}，不改变 {@code GL_DRAW_FRAMEBUFFER}。
 *
 * <p><b>尺寸对齐</b>：源纹理的物理尺寸在分配时向上对齐到 tileSize 的
 * 整数倍（例如逻辑高 692、tileSize 64 → 物理高 704）。多出的
 * {@code alignUp(h) - h} 行 padding 位于纹理<b>底部</b>——有效内容
 * 贴在纹理高 y 侧。blit 时源区域从 padding 之后起算，避免把 padding
 * 算入缩放。
 *
 * <p><b>目标 framebuffer 与源纹理的尺寸独立</b>——源和目标尺寸可以
 * 不一致，由 {@code glBlitFramebuffer} 完成缩放。典型的 UI 尺寸 →
 * 物理尺寸（DPI 缩放）就是这条路径。
 *
 * <p><b>所有权</b>：与 {@link GLTexture} 一致——不实现
 * {@link AutoCloseable}，显式在 GL 线程上调 {@link #close()}。释放幂等。
 *
 * <p><b>线程契约</b>：仅 GL 线程调用；与使用它的 GL 上下文同寿。
 */
public final class TiledTextureBlitter implements AutoCloseable {

    /**
     * 纹理尺寸的对齐单位——通常是 tileSize。用于计算底部 padding 高度。
     */
    private final int tileSize;

    /**
     * 私有 read FBO——懒创建，首次 blit 时分配。
     */
    private int readFbo = 0;

    private volatile boolean closed = false;

    /**
     * @param tileSize 纹理尺寸的对齐单位，必须 &gt; 0
     * @throws IllegalArgumentException tileSize &le; 0
     */
    public TiledTextureBlitter(int tileSize) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        this.tileSize = tileSize;
        // FBO 延迟到首次 blit 时创建——构造不要求 GL 上下文
    }

    private int ensureReadFbo() {
        int fbo = readFbo;
        if (fbo == 0) {
            fbo = glGenFramebuffers();
            readFbo = fbo;
        }
        return fbo;
    }

    /**
     * 把纹理指定层 blit 到目标 framebuffer。
     *
     * <p>源区域从纹理的 padding 之后起算：{@code y ∈ [diffH, texture.getHeight())}，
     * 其中 {@code diffH = alignUp(srcHeight, tileSize) - srcHeight}。源区
     * 宽度从 x=0 起、到 srcWidth 止。
     *
     * <p>目标区域为 {@code (0, 0, dstWidth, dstHeight)}。源和目标尺寸
     * 不等时由 {@code glBlitFramebuffer} 缩放。
     *
     * <p>blit 前会 clear 目标 framebuffer——调用方不必预先 clear。
     *
     * @param texture   源纹理，非 null 且未释放
     * @param layer     源层索引，{@code 0 ≤ layer < texture.getLayers()}
     * @param srcWidth  源有效宽（像素），通常等于逻辑视口宽
     * @param srcHeight 源有效高（像素），通常等于逻辑视口高
     * @param targetFbo 目标 framebuffer 的 GL 句柄
     * @param dstWidth  目标宽（像素），必须 &gt; 0
     * @param dstHeight 目标高（像素），必须 &gt; 0
     * @throws NullPointerException      texture 为 null
     * @throws IllegalStateException     blitter 或 texture 已释放
     * @throws IndexOutOfBoundsException layer 越界
     * @throws IllegalArgumentException  dstWidth 或 dstHeight &le; 0
     */
    public void blit(GLTexture texture,
                     int layer,
                     int srcWidth, int srcHeight,
                     int targetFbo,
                     int dstWidth, int dstHeight) {
        if (closed) {
            throw new IllegalStateException("TiledTextureBlitter has been released");
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
        if (dstWidth <= 0 || dstHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid dst size: " + dstWidth + "x" + dstHeight);
        }

        // 纹理底部 padding 行数——有效内容贴在纹理高 y 侧
        int paddingH = NumberUtils.alignUp(srcHeight, tileSize) - srcHeight;

        int fbo = ensureReadFbo();
        int texHandle = texture.getHandle();

        glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
        try {
            glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    texHandle, 0, layer);
            glReadBuffer(GL_COLOR_ATTACHMENT0);

            glBlitFramebuffer(
                    0, paddingH, srcWidth, texture.getHeight(),
                    0, 0, dstWidth, dstHeight,
                    GL_COLOR_BUFFER_BIT, GL_NEAREST);

            glFramebufferTextureLayer(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    0, 0, 0);
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        }
    }

    /**
     * 释放内部 read FBO。必须在 GL 线程上调用。非幂等——重复调用抛异常。
     */
    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("TiledTextureBlitter has been released");
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