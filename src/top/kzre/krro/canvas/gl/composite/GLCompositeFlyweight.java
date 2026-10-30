package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.*;
import top.kzre.krro.canvas.gl.tile.GLAtlasFactory;
import top.kzre.krro.canvas.gl.tile.GLAtlasPool;
import top.kzre.krro.canvas.gl.util.ExecutorUtils;
import top.kzre.krro.canvas.gl.util.ResourceUtils;
import top.kzre.krro.core.util.SerialExecutor;
import top.kzre.krro.util.tile.TiledCanvas;

import java.util.concurrent.CompletableFuture;

/**
 * GL 上下文级共享资源。一个 GL 执行器一份，被该执行器上的所有视口共享。
 *
 * <p><b>持有内容</b>：
 * <ul>
 *   <li>{@code glExecutor} —— 借用，不持有所有权</li>
 *   <li>{@code atlasPool} —— 拥有。瓦片图集池，所有视口共享</li>
 *   <li>{@code quad} —— 拥有。共享全屏四边形</li>
 *   <li>{@code glProgramManager} —— 拥有。Shader program 缓存</li>
 *   <li>{@code noTexPool} —— 拥有。纹理池的 delegate</li>
 *   <li>{@code emptyCanvasTpl} —— 拥有。空画布模板</li>
 * </ul>
 *
 * <p><b>线程契约</b>：访问器任意线程；{@link #getQuad()} 仅 GL 线程；
 * {@link #close()} 任意线程——非 GL 线程会自动调度。
 */
public final class GLCompositeFlyweight implements GLThreadAffine, AutoCloseable {
    private final SerialExecutor glExecutor;
    private final GLAtlasPool atlasPool;
    private final int tileSize;
    private final NoGLTexturePool noTexPool;
    private final CompositeGLProgramManager glProgramManager;

    private final GLQuad quad;
    private volatile boolean closed = false;

    public GLCompositeFlyweight(SerialExecutor glExecutor,
                                int tileSize,
                                GLAtlasPool atlasPool, CompositeGLProgramManager glProgramManager, GLQuad quad) {
        this.glProgramManager = glProgramManager;
        this.quad = quad;
        if (glExecutor == null) {
            throw new IllegalArgumentException("glExecutor must not be null");
        }
        if (atlasPool == null) {
            throw new IllegalArgumentException("atlasPool must not be null");
        }
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }


        this.glExecutor = glExecutor;
        this.atlasPool = atlasPool;
        this.tileSize = tileSize;
        this.noTexPool = new NoGLTexturePool(glExecutor);


    }

    // ── 访问器 ────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public SerialExecutor getGlExecutor() {
        return glExecutor;
    }

    public GLAtlasPool getAtlasPool() {
        return atlasPool;
    }

    public int getTileSize() {
        return tileSize;
    }


    public NoGLTexturePool getNoTexPool() {
        return noTexPool;
    }

    public GLProgramCache getProgramCache() {
        return glProgramManager.getCache();
    }

    public boolean isClosed() {
        return closed;
    }

    public int getMinSingleDrawnTiles() {
        return 200;
    }



    // ── 生命周期 ──────────────────────────────────

    /**
     * 共享 quad。首次调用时惰性创建。
     */
    public GLQuad getQuad() {
        if (closed) {
            throw new IllegalStateException("shared resources closed");
        }
        return quad;
    }

    @Override
    public void close() {
        if (closed) {
            throw new IllegalStateException("shared resources already closed");
        }
        closed = true;
        ExecutorUtils.runOnThread(glExecutor, this::doClose);
    }

    private void doClose() {
        RuntimeException error = ResourceUtils.closeDelayError(quad, atlasPool);
        if (error != null) {
            throw error;
        }
    }

    public static class Builder {
        private SerialExecutor glExecutor;
        private int tileSize;
        private int atlasCapacity;
        private GLAtlasFactory atlasFactory;

        private static GLQuad buildQuad() {
            GLQuad q;
            q = GLQuad.create(GLQuad.Orientation.Y_DOWN);
            q.bindPositionAttribute(ShaderConstants.ATTRIBUTE_POSITION_QUAD);
            return q;
        }

        public Builder glExecutor(SerialExecutor glExecutor) {
            this.glExecutor = glExecutor;
            return this;
        }

        public Builder tileSize(int tileSize) {
            this.tileSize = tileSize;
            return this;
        }

        public Builder atlasCapacity(int atlasCapacity) {
            this.atlasCapacity = atlasCapacity;
            return this;
        }

        public Builder atlasFactory(GLAtlasFactory atlasFactory) {
            this.atlasFactory = atlasFactory;
            return this;
        }

        public CompletableFuture<GLCompositeFlyweight> build() {
            if (glExecutor == null) {
                throw new IllegalStateException("glExecutor must not be null");
            }
            if (tileSize <= 0) {
                throw new IllegalStateException("tileSize must be > 0: " + tileSize);
            }
            if (atlasCapacity <= 0) {
                throw new IllegalStateException("atlasCapacity must be > 0: " + atlasCapacity);
            }
            if (atlasFactory == null) {
                throw new IllegalStateException("atlasPool must not be null");
            }

            return ExecutorUtils.runOnThread(glExecutor, () ->
            {
                GLQuad glQuad = buildQuad();
                GLProgramCache programs = new GLProgramCache(glExecutor);
                CompositeGLProgramManager programManager = new CompositeGLProgramManager(programs);
                programManager.warmup();
                GLAtlasPool glAtlasPool = new GLAtlasPool(glExecutor, atlasCapacity, atlasFactory);
                glAtlasPool.warmup();
                return new GLCompositeFlyweight(
                        glExecutor,
                        tileSize,
                        glAtlasPool,
                        programManager,
                        glQuad);
            });
        }
    }
}