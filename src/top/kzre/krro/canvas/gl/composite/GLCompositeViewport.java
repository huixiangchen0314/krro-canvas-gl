package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;
import top.kzre.krro.canvas.gl.tile.GLAtlasPool;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.TiledCanvas;

/**
 * 视口级上下文。每个视口一份，引用共享的 {@link GLCompositeFlyweight}。
 *
 * <p><b>持有内容</b>：
 * <ul>
 *   <li>{@code flyweight} —— 私有，不对外泄漏</li>
 *   <li>{@code viewFrameBufferPool} —— 拥有。视口尺寸绑定的 ping-pong FBO 池</li>
 *   <li>{@code viewWidth} / {@code viewHeight} —— 视口尺寸（对齐到 tileSize）</li>
 * </ul>
 *
 * <p><b>与 shared 的关系</b>：共享资源一律通过本类上的<b>代理方法</b>访问，
 * 外部调用方不应持有 {@link GLCompositeFlyweight}。
 *
 * <p><b>线程契约</b>：访问器任意线程；{@link #adjustViewSize(int, int)}
 * 仅 GL 线程；{@link #close()} 任意线程——自动调度。
 */
public final class GLCompositeViewport implements GLThreadAffine, AutoCloseable {

    private final GLCompositeFlyweight flyweight;

    private volatile FixedSizeFrameBufferPool viewFrameBufferPool;
    private volatile int viewWidth;
    private volatile int viewHeight;

    private volatile boolean closed = false;

    private GLCompositeViewport(GLCompositeFlyweight flyweight) {
        this.flyweight = flyweight;
    }

    public static GLCompositeViewport create(GLCompositeFlyweight flyweight) {
        if (flyweight == null) {
            throw new IllegalArgumentException("flyweight must not be null");
        }
        return new GLCompositeViewport(flyweight);
    }

    // ── 共享资源代理（对外 API） ─────────────────

    public int getTileSize() {
        return flyweight.getTileSize();
    }

    public PixelFormat getPixelFormat() {
        return flyweight.getPixelFormat();
    }

    public GLAtlasPool getAtlasPool() {
        return flyweight.getAtlasPool();
    }

    public AtlasPoolPage getAtlasPoolPage() {
        return flyweight.getAtlasPoolPage();
    }

    public GLProgramCache getProgramCache() {
        return flyweight.getProgramCache();
    }

    public int getMinSingleDrawnTiles() {
        return flyweight.getMinSingleDrawnTiles();
    }

    /**
     * 共享 quad。仅 GL 线程调用。
     */
    public GLQuad getQuad() {
        return flyweight.getQuad();
    }

    public TiledCanvas newCanvas() {
        return flyweight.newCanvas();
    }

    public TiledCanvas newReadonlyCanvas() {
        return flyweight.newReadonlyCanvas();
    }

    // ── 尺寸 ──────────────────────────────────────

    public boolean needsViewSizeAdjust(int width, int height) {
        if (width <= 0 || height <= 0) return false;
        int alignedW = alignUp(width, flyweight.getTileSize());
        int alignedH = alignUp(height, flyweight.getTileSize());
        return viewWidth != alignedW || viewHeight != alignedH;
    }

    /**
     * 调整视口尺寸。仅 GL 线程。
     */
    public void adjustViewSize(int width, int height) {
        requireGlThread();
        if (closed) {
            throw new IllegalStateException("viewport context is closed");
        }
        if (width <= 0 || height <= 0)
            throw new IllegalArgumentException("invalid viewport: " + width + "x" + height);

        int alignedW = alignUp(width, flyweight.getTileSize());
        int alignedH = alignUp(height, flyweight.getTileSize());

        if (viewWidth == alignedW && viewHeight == alignedH) return;

        if (viewFrameBufferPool != null) {
            viewFrameBufferPool.close();
            viewFrameBufferPool = null;
        }

        viewFrameBufferPool = createFboPool(alignedW, alignedH);
        viewWidth = alignedW;
        viewHeight = alignedH;
    }

    private FixedSizeFrameBufferPool createFboPool(int width, int height) {
        // 4 个纹理，每个纹理 2 个层 应该足够了
        FixedSizeTexturePool texturePool = new FixedSizeTexturePool(
                4,
                width, height, 2,
                flyweight.getPixelFormat(),
                flyweight.getNoTexPool());
        try {
            return new FixedSizeFrameBufferPool(texturePool);
        } catch (Throwable t) {
            try {
                texturePool.close();
            } catch (Throwable ignored) {
            }
            throw t;
        }
    }

    // ── 访问器 ────────────────────────────────────

    public FixedSizeFrameBufferPool getViewFrameBufferPool() {
        return viewFrameBufferPool;
    }

    public int getViewWidth() {
        return viewWidth;
    }

    public int getViewHeight() {
        return viewHeight;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return flyweight.getGlExecutor();
    }

    // ── 生命周期 ──────────────────────────────────

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("viewport context already closed");
        }
        closed = true;
        ExecutorUtils.submitIfThread(flyweight.getGlExecutor(), this::doClose);
    }

    private void doClose() {
        if (viewFrameBufferPool != null) {
            viewFrameBufferPool.close();
            viewFrameBufferPool = null;
        }
        viewWidth = 0;
        viewHeight = 0;
    }

    private static int alignUp(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }
}