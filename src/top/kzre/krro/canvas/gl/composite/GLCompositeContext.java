package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.tile.GLAtlasPool;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.canvas.gl.util.NumberUtils;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GL 合成上下文——视口级。
 *
 * <p><b>与享元的关系</b>：上下文借用 {@link GLCompositeFlyweight} 的
 * 共享资源（quad、atlas 池、shader 缓存、空画布模板、像素格式等），
 * 自己只持有<b>视口私有</b>资源：ping-pong FBO 池和视口尺寸。
 * 多个视口共享同一个 flyweight，各自的 context 只在视口级不同。
 *
 * <p><b>所有权</b>：flyweight 的所有权在外部（GL 上下文级管理器），
 * 上下文<b>不拥有、不关闭</b> flyweight。上下文 close 只释放自己的
 * FBO 池。
 *
 * <p><b>生命周期约束</b>：flyweight 必须比所有引用它的 context 长寿。
 * 外部管理器负责保证“先关闭所有 context，再关闭 flyweight”。违反
 * 此约束时，context 的操作会因 flyweight 已关闭而抛异常。
 *
 * <h2>线程契约</h2>
 *
 * <p>本类的大部分方法是 <b>GL 线程内部接口</b>——它们由 GL 线程内的
 * 合成流程调用，非 GL 线程调用会抛异常。
 *
 * <p><b>唯一例外是 {@link #close()}</b>：它是外部生命周期接口，
 * 允许从任意线程调用。非 GL 线程时会自动把实际的清理动作调度到
 * GL 线程执行。
 *
 * <p>访问器是纯数据读取，任意线程可读。
 */
public class GLCompositeContext implements AutoCloseable, GLThreadAffine {

    /**
     * 借用的享元，不拥有。
     */
    private final GLCompositeFlyweight flyweight;

    /**
     * 借用，不持有所有权。
     */
    private final SerialExecutor glExecutor;
    /**
     * FBO 池容量，构造时固定。
     */
    private final int viewFboPoolCapacity;
    /**
     * 关闭标记。CAS 保证重复调用被拒绝。
     */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /**
     * 视口私有：尺寸绑定的 ping-pong FBO 池。
     */
    private volatile FixedSizeFrameBufferPool viewFrameBufferPool;
    private volatile int viewWidth;
    private volatile int viewHeight;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    /**
     * @param flyweight           GL 上下文级共享资源，<b>不转移所有权</b>
     * @param viewFboPoolCapacity 视口 ping-pong FBO 池容量，必须 &gt; 0
     * @throws IllegalArgumentException flyweight 为 null 或容量 ≤ 0
     * @throws IllegalStateException    flyweight 已关闭
     */
    public GLCompositeContext(GLCompositeFlyweight flyweight,
                              int viewFboPoolCapacity) {
        if (flyweight == null) {
            throw new IllegalArgumentException("flyweight must not be null");
        }
        if (viewFboPoolCapacity <= 0) {
            throw new IllegalArgumentException(
                    "viewFboPoolCapacity must be > 0: " + viewFboPoolCapacity);
        }
        if (flyweight.isClosed()) {
            throw new IllegalStateException("flyweight is closed");
        }

        this.flyweight = flyweight;
        this.glExecutor = flyweight.getGlExecutor();
        this.viewFboPoolCapacity = viewFboPoolCapacity;
    }

    // ═══════════════════════════════════════════════
    // 视口级尺寸管理
    // ═══════════════════════════════════════════════

    /**
     * 判断视口尺寸是否需要调整。
     *
     * <p><b>线程契约</b>：纯数据读取，任意线程可调。
     */
    public boolean needsViewSizeAdjust(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        int tileSize = flyweight.getTileSize();
        int alignedW = NumberUtils.alignUp(width, tileSize);
        int alignedH = NumberUtils.alignUp(height, tileSize);
        return viewWidth != alignedW || viewHeight != alignedH;
    }

    /**
     * 调整视口尺寸。尺寸变化时重建 FBO 池。
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。
     *
     * @throws IllegalStateException    非 GL 线程调用，或上下文已关闭
     * @throws IllegalArgumentException width / height ≤ 0
     */
    public void adjustViewSize(int width, int height) {
        requireGlThread();
        if (closed.get()) {
            throw new IllegalStateException("context is closed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "invalid viewport: " + width + "x" + height);
        }

        int tileSize = flyweight.getTileSize();
        int alignedW = NumberUtils.alignUp(width, tileSize);
        int alignedH = NumberUtils.alignUp(height, tileSize);

        if (viewWidth == alignedW && viewHeight == alignedH) {
            return;
        }

        if (viewFrameBufferPool != null) {
            viewFrameBufferPool.close();
            viewFrameBufferPool = null;
        }

        viewFrameBufferPool = createFboPool(alignedW, alignedH);
        viewWidth = alignedW;
        viewHeight = alignedH;
    }

    private FixedSizeFrameBufferPool createFboPool(int width, int height) {
        FixedSizeTexturePool texturePool = new FixedSizeTexturePool(
                viewFboPoolCapacity,
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

    // ═══════════════════════════════════════════════
    // 共享资源代理（全部转发到 flyweight）
    // ═══════════════════════════════════════════════

    /**
     * 共享 quad。代理到 flyweight——首次调用时 flyweight 惰性创建。
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。
     *
     * @throws IllegalStateException 非 GL 线程、上下文已关闭、或 flyweight 已关闭
     */
    public GLQuad getQuad() {
        requireGlThread();
        if (closed.get()) {
            throw new IllegalStateException("context is closed");
        }
        return flyweight.getQuad();
    }

    public TiledCanvas newCanvas() {
        return flyweight.newCanvas();
    }

    public GLProgramCache getProgramCache() {
        return flyweight.getProgramCache();
    }

    public int getMinSingleDrawnTiles() {
        return flyweight.getMinSingleDrawnTiles();
    }

    // ═══════════════════════════════════════════════
    // 访问器（任意线程可读）
    // ═══════════════════════════════════════════════

    public FixedSizeFrameBufferPool getViewFrameBufferPool() {
        return viewFrameBufferPool;
    }

    public int getViewWidth() {
        return viewWidth;
    }

    public int getViewHeight() {
        return viewHeight;
    }

    public int getTileSize() {
        return flyweight.getTileSize();
    }

    public PixelFormat getPixelFormat() {
        return flyweight.getPixelFormat();
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return glExecutor;
    }

    public GLAtlasPool getAtlasPool() {
        return flyweight.getAtlasPool();
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ═══════════════════════════════════════════════
    // 外部生命周期接口
    // ═══════════════════════════════════════════════

    /**
     * 关闭上下文。只释放<b>视口私有</b>资源：FBO 池。
     *
     * <p><b>不释放共享资源</b>：flyweight 的所有权在外部，由
     * GL 上下文级管理器统一关闭。
     *
     * <p><b>线程契约</b>：任意线程可调。GL 线程同步执行，非 GL 线程
     * 提交到 GL 线程执行，调用方立即返回。
     *
     * <p><b>不可重入</b>：重复调用抛异常。CAS 保证检查与标记的原子性。
     *
     * @throws IllegalStateException 上下文已关闭
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            throw new IllegalStateException("context is already closed");
        }
        ExecutorUtils.runOnThread(glExecutor, this::doClose);
    }

    /**
     * 实际的清理动作。只在 GL 线程上执行。
     */
    private void doClose() {
        if (viewFrameBufferPool != null) {
            viewFrameBufferPool.close();
            viewFrameBufferPool = null;
        }
        viewWidth = 0;
        viewHeight = 0;
    }
}