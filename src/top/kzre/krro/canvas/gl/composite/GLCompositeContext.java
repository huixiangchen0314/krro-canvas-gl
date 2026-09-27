package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.tile.GLAtlasPool;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GL 合成上下文。上层持有，管理跨任务复用的 GL 资源。
 *
 * <p><b>持有内容</b>：
 * <ul>
 *   <li>{@code glExecutor} —— 借用，不持有所有权</li>
 *   <li>{@code atlasPool} —— 拥有，关闭时释放</li>
 *   <li>{@code viewFrameBufferPool} —— 拥有，视口尺寸绑定的
 *       ping-pong FBO 池</li>
 * </ul>
 *
 * <h2>构造时的所有权转移</h2>
 *
 * <p>构造时 {@code atlasPool} 的所有权转移给本类。调用方不应在
 * 构造后再使用 atlasPool，也不应单独关闭它——本类的 {@link #close()}
 * 会负责。
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
 *
 * <table>
 *   <caption>方法线程契约</caption>
 *   <tr><th>方法</th><th>允许的线程</th></tr>
 *   <tr><td>{@link #close()}</td><td>任意——自动调度</td></tr>
 *   <tr><td>{@link #adjustViewSize(int, int)}</td>
 *       <td>仅 GL 线程</td></tr>
 *   <tr><td>所有访问器</td><td>任意——纯读</td></tr>
 * </table>
 */
public class GLCompositeContext implements AutoCloseable {

    // ═══════════════════════════════════════════════
    // 借用资源
    // ═══════════════════════════════════════════════

    /** 借用，不持有所有权。 */
    private final SerialExecutor glExecutor;

    private final GLAtlasPool atlasPool;

    /**
     * 本上下文共享的 quad。GL 线程首次 {@link #getQuad()} 时创建，
     * {@link #close()} 时释放。
     *
     * <p>volatile 保证 close 时的可见性——创建只发生在 GL 线程，
     * 读可能发生在任意线程（但 getQuad 仍在 GL 线程调用）。
     */
    private volatile GLQuad quad;

    private volatile FixedSizeFrameBufferPool viewFrameBufferPool;
    /**
     * 空画布模板，用于产生空画布
     */
    private final TiledCanvas emptyCanvasTpl;

    private final int tileSize;
    private final int viewFboPoolCapacity;
    private final PixelFormat pixelFormat;
    private final NoGLTexturePool noTexPool;

    /** 关闭标记。CAS 保证重复调用被拒绝。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile int viewWidth;
    private volatile int viewHeight;

    private final CompositeGLProgramManager glProgramManager = new CompositeGLProgramManager();

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    public GLCompositeContext(SerialExecutor glExecutor,
                              GLAtlasPool atlasPool,
                              int tileSize,
                              int viewFboPoolCapacity,
                              PixelFormat pixelFormat) {
        if (glExecutor == null) {
            throw new IllegalArgumentException("glExecutor must not be null");
        }
        if (atlasPool == null) {
            throw new IllegalArgumentException("atlasPool must not be null");
        }
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        if (viewFboPoolCapacity <= 0) {
            throw new IllegalArgumentException("viewFboPoolCapacity must be > 0: " + viewFboPoolCapacity);
        }
        if (pixelFormat == null) {
            throw new IllegalArgumentException("pixelFormat must not be null");
        }

        this.glExecutor      = glExecutor;
        this.atlasPool       = atlasPool;
        this.tileSize        = tileSize;
        this.viewFboPoolCapacity = viewFboPoolCapacity;
        this.pixelFormat = pixelFormat;
        this.noTexPool = new NoGLTexturePool(glExecutor);

        int channels = PixelCodec.channels(pixelFormat);
        float[] defaultPixels = new float[channels];
        this.emptyCanvasTpl = new TiledCanvas(tileSize, defaultPixels);
        emptyCanvasTpl.setReadonly(true);
    }


    /**
     * 判断视口尺寸是否需要调整。
     *
     * <p><b>用途</b>：调用方在提交 GL 任务前先做一次纯 CPU 判断——
     * 尺寸未变时跳过整个调度，避免无谓的跨线程同步。
     *
     * <p><b>线程契约</b>：纯数据读取，任意线程可调。内部只读
     * {@code viewWidth} / {@code viewHeight}（volatile）和常量
     * {@code tileSize}，无 GL 调用。
     *
     * <p><b>竞态</b>：判断与实际 {@code adjustViewSize} 之间存在窗口期。
     * 多线程同时调整时，判断可能过时——但 {@code adjustViewSize} 内部
     * 会再次检查，最终行为一致。判断只是"快速路径"，不是"唯一防线"。
     *
     * @param width  目标视口宽（像素），未对齐
     * @param height 目标视口高（像素），未对齐
     * @return true 表示需要调度 {@link #adjustViewSize(int, int)}
     */
    public boolean needsViewSizeAdjust(int width, int height) {
        if (width <= 0 || height <= 0) {
            return false;
        }
        int alignedW = alignUp(width,  tileSize);
        int alignedH = alignUp(height, tileSize);
        return viewWidth != alignedW || viewHeight != alignedH;
    }


    // ═══════════════════════════════════════════════
    // GL 线程内部接口
    // ═══════════════════════════════════════════════

    /**
     * 调整视口尺寸。尺寸变化时重建 FBO 池。
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用。
     *
     * @throws IllegalStateException    非 GL 线程调用，或上下文已关闭
     * @throws IllegalArgumentException width / height ≤ 0
     */
    public void adjustViewSize(int width, int height) {
        checkGlThread();
        if (closed.get()) {
            throw new IllegalStateException("context is closed");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "invalid viewport: " + width + "x" + height);
        }

        int alignedW = alignUp(width,  tileSize);
        int alignedH = alignUp(height, tileSize);

        if (viewWidth == alignedW && viewHeight == alignedH) {
            return;
        }

        if (viewFrameBufferPool != null) {
            viewFrameBufferPool.close();
            viewFrameBufferPool = null;
        }

        viewFrameBufferPool = createFboPool(alignedW, alignedH);
        viewWidth  = alignedW;
        viewHeight = alignedH;
    }

    private FixedSizeFrameBufferPool createFboPool(int width, int height) {
        FixedSizeTexturePool texturePool = new FixedSizeTexturePool(
                1,
                width, height, viewFboPoolCapacity,
                pixelFormat,
                noTexPool);
        try {
            return new FixedSizeFrameBufferPool(texturePool);
        } catch (Throwable t) {
            try { texturePool.close(); } catch (Throwable ignored) { }
            throw t;
        }
    }

    /**
     * 获取本上下文的共享 {@link GLQuad}。首次调用时创建并绑定位置
     * attribute。
     *
     * <p><b>线程契约</b>：必须在 GL 线程调用——涉及 GL 创建与 VAO 配置。
     *
     * <p><b>生命周期</b>：quad 属于本上下文，{@link #close()} 时释放。
     * 一个 context 一个 quad——多个 context 各有自己的 quad，互不共享，
     * 避免跨 context 使用 GL 对象。
     *
     * @throws IllegalStateException 非 GL 线程调用，或上下文已关闭
     */
    public GLQuad getQuad() {
        checkGlThread();
        if (closed.get()) {
            throw new IllegalStateException("context is closed");
        }
        GLQuad q = quad;
        if (q == null) {
            q = GLQuad.create(GLQuad.Orientation.Y_DOWN);
            q.bindPositionAttribute(ShaderConstants.ATTRIBUTE_POSITION_QUAD);
            quad = q;
        }
        return q;
    }

    // ═══════════════════════════════════════════════
    // 访问器（任意线程可读）
    // ═══════════════════════════════════════════════

    public FixedSizeFrameBufferPool getViewFrameBufferPool() {
        return viewFrameBufferPool;
    }

    public int getViewWidth()  { return viewWidth; }
    public int getViewHeight() { return viewHeight; }
    public int getTileSize()   { return tileSize; }

    public SerialExecutor getGlExecutor() { return glExecutor; }

    /** atlas 池。所有权在本类——不要在外部关闭。 */
    public GLAtlasPool getAtlasPool() { return atlasPool; }

    public boolean isClosed() { return closed.get(); }

    // ═══════════════════════════════════════════════
    // 外部生命周期接口
    // ═══════════════════════════════════════════════

    /**
     * 关闭上下文。释放拥有的资源：FBO 池和 atlas 池。
     *
     * <p><b>唯一允许外部直接调用的方法</b>。可在任意线程调用：
     * <ul>
     *   <li>GL 线程 → 同步执行清理</li>
     *   <li>非 GL 线程 → 提交到 GL 线程执行，调用方立即返回</li>
     * </ul>
     *
     * <p><b>不释放借用资源</b>：{@code glExecutor} 的所有权不在本类。
     *
     * <p><b>释放顺序</b>：FBO 池 → atlas 池。FBO 池引用 atlas 的纹理，
     * 先释放 FBO 侧避免悬挂引用。
     *
     * <p><b>不可重入</b>：重复调用抛异常。CAS 保证检查与标记的原子性，
     * 并发的 close 只会有一个成功。
     *
     * <p><b>异步语义</b>：非 GL 线程调用时，返回不代表 GL 清理已完成，
     * 仅代表已提交。调用方如需要保证清理完成，应在 GL 线程内调用，
     * 或自行同步提交的任务。
     *
     * @throws IllegalStateException 上下文已关闭
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            throw new IllegalStateException("context is already closed");
        }
        if (Thread.currentThread().getId() == glExecutor.getTheadId()) {
            doClose();
        } else {
            glExecutor.submit(this::doClose);
        }
    }

    /** 实际的清理动作。只在 GL 线程上执行。 */
    private void doClose() {
        if (viewFrameBufferPool != null) {
            viewFrameBufferPool.close();
            viewFrameBufferPool = null;
        }
        // quad 清理
        GLQuad q = quad;
        if (q != null) {
            q.release();
            quad = null;
        }
        atlasPool.close();
        viewWidth  = 0;
        viewHeight = 0;
    }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    private void checkGlThread() {
        if (Thread.currentThread().getId() != glExecutor.getTheadId()) {
            throw new IllegalStateException(
                    "must be called on the GL thread");
        }

    }

    public PixelFormat getPixelFormat() { return pixelFormat; }

    public TiledCanvas newCanvas()
    {
        return emptyCanvasTpl.copy();
    }

    public TiledCanvas newReadonlyCanvas()
    {
        return emptyCanvasTpl.copy().setReadonly(true);
    }

    public GLProgramCache getProgramCache()
    {
        return glProgramManager.getCache();
    }

    private static int alignUp(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }
}