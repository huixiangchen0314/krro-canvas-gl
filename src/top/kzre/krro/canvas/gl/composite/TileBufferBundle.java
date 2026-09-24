package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;

/**
 * 瓦片缓冲区束。一次 draw call 的完整资源清单。
 *
 * <p><b>两部分</b>：
 * <ul>
 *   <li>{@code shader}    —— 本次 draw call 的着色器</li>
 *   <li>{@code bindables} —— 需要绑定到纹理单元的资源数组，
 *       下标即单元号</li>
 * </ul>
 *
 * <p><b>AtlasPoolPage 不在这里</b>：换页能力属于整个 request，
 * 不属于单个 bundle。多个 bundle 共享同一个 page，由
 * {@link CompositeRequest} 持有。
 *
 * <p><b>线程契约</b>：纯数据，任意线程可构造和读取。
 */
public final class TileBufferBundle {

    private final Shader       shader;
    private final GLBindable[] bindables;

    public TileBufferBundle(Shader shader, GLBindable[] bindables) {
        if (shader == null) {
            throw new IllegalArgumentException("shader must not be null");
        }
        if (bindables == null) {
            throw new IllegalArgumentException("bindables must not be null");
        }
        this.shader    = shader;
        this.bindables = bindables.clone();
    }

    public Shader        getShader()    { return shader; }

    /**
     * 需要绑定到纹理单元的资源。下标即单元号，null 位置跳过。
     * 返回内部数组引用，调用方不得修改。
     */
    public GLBindable[]  getBindables() { return bindables; }

    @Override
    public String toString() {
        return "TileBufferBundle{shader=" + shader.getClass().getSimpleName()
                + ", bindables=" + bindables.length + "}";
    }
}