package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.resource.PixelFormat;
import top.kzre.krro.canvas.gl.tile.GLAtlasFactory;
import top.kzre.krro.canvas.gl.tile.GLAtlasPool;
import top.kzre.krro.core.util.SerialExecutor;

import java.util.concurrent.CompletableFuture;

/**
 * {@link GLCompositeContext} 的链式构建器。
 *
 * <h2>必填</h2>
 * <ul>
 *   <li>{@code glExecutor} —— GL 线程执行器</li>
 *   <li>{@code tileSize} —— 瓦片边长</li>
 *   <li>{@code atlasCapacity} —— atlas 池容量</li>
 *   <li>{@code atlasFactory} —— atlas 创建工厂</li>
 * </ul>
 *
 * <h2>可选</h2>
 * <ul>
 *   <li>{@code viewFboPoolCapacity} —— 视口 FBO 池缓存容量，默认 4</li>
 *   <li>{@code pixelFormat} —— 合成工作格式，默认 {@link PixelFormat#RGBA8}</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * GLCompositeContext ctx = new GLCompositeContextBuilder()
 *         .glExecutor(glExecutor)
 *         .tileSize(64)
 *         .atlasCapacity(4)
 *         .atlasFactory(factory)
 *         .build()
 *         .join();
 * }</pre>
 */
public final class GLCompositeContextBuilder {

    private SerialExecutor glExecutor;
    private int            tileSize;
    private int            atlasCapacity;
    private GLAtlasFactory atlasFactory;

    private int         viewFboPoolCapacity = 4;
    private PixelFormat pixelFormat         = PixelFormat.RGBA8;

    // ═══════════════════════════════════════════════
    // 必填
    // ═══════════════════════════════════════════════

    public GLCompositeContextBuilder glExecutor(SerialExecutor glExecutor) {
        this.glExecutor = glExecutor;
        return this;
    }

    public GLCompositeContextBuilder tileSize(int tileSize) {
        this.tileSize = tileSize;
        return this;
    }

    public GLCompositeContextBuilder atlasCapacity(int atlasCapacity) {
        this.atlasCapacity = atlasCapacity;
        return this;
    }

    public GLCompositeContextBuilder atlasFactory(GLAtlasFactory atlasFactory) {
        this.atlasFactory = atlasFactory;
        return this;
    }

    // ═══════════════════════════════════════════════
    // 可选
    // ═══════════════════════════════════════════════

    public GLCompositeContextBuilder viewFboPoolCapacity(int capacity) {
        this.viewFboPoolCapacity = capacity;
        return this;
    }

    public GLCompositeContextBuilder pixelFormat(PixelFormat pixelFormat) {
        this.pixelFormat = pixelFormat;
        return this;
    }

    // ═══════════════════════════════════════════════
    // 构建
    // ═══════════════════════════════════════════════

    /**
     * 构建 {@link GLCompositeContext}。
     *
     * <p>参数校验在调用线程同步执行——缺失或非法立即抛异常。
     * context 构造在 GL 线程完成——返回的 future 解析时已就绪。
     *
     * @throws IllegalStateException    必填参数缺失
     * @throws IllegalArgumentException 参数非法
     */
    public CompletableFuture<GLCompositeContext> build() {
        if (glExecutor == null) {
            throw new IllegalStateException("glExecutor is required");
        }
        if (tileSize <= 0) {
            throw new IllegalStateException(
                    "tileSize is required and must be > 0: " + tileSize);
        }
        if (atlasCapacity <= 0) {
            throw new IllegalStateException(
                    "atlasCapacity is required and must be > 0: " + atlasCapacity);
        }
        if (atlasFactory == null) {
            throw new IllegalStateException("atlasFactory is required");
        }
        if (viewFboPoolCapacity <= 0) {
            throw new IllegalArgumentException(
                    "viewFboPoolCapacity must be > 0: " + viewFboPoolCapacity);
        }
        if (pixelFormat == null) {
            throw new IllegalArgumentException("pixelFormat must not be null");
        }

        return glExecutor.submit(() -> {
            GLAtlasPool pool = new GLAtlasPool(atlasCapacity, atlasFactory);
            pool.warmup();
            GLCompositeContext context = new GLCompositeContext(
                    glExecutor, pool, tileSize,
                    viewFboPoolCapacity, pixelFormat);
            GLProgramCache programCache = context.getProgramCache();
            programCache.get(Blends.NORMAL);
            return context;
        });
    }
}