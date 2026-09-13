package top.kzre.krro.canvas.gl.tile;

/**
 * Atlas 工厂：为指定 unit 创建一个新的 {@link GLAtlas}。
 *
 * <p>调用方（{@link GLAtlasPool}）保证：同一 unit 只会调用一次。
 * 实现无需处理重复创建。
 *
 * <p><b>线程契约</b>：{@link #create(int)} 涉及 GL 调用，必须在 GL 线程上执行。
 *
 * <p><b>失败语义</b>：创建失败时抛异常——调用方不会重试，直接向上传播。
 */
@FunctionalInterface
public interface GLAtlasFactory {

    /**
     * 为指定 unit 创建 atlas。
     *
     * @param unit 纹理单元编号
     * @return 新的 atlas，非 null
     * @throws RuntimeException GL 资源创建失败
     */
    GLAtlas create(int unit);

    /**
     * 常用工厂：RGBA8 纹理数组。
     *
     * @param tileSize 每层边长（像素）
     * @param capacity 每 atlas 层数
     */
    static GLAtlasFactory rgba8(int tileSize, int capacity) {
        if (tileSize < 1) {
            throw new IllegalArgumentException("tileSize must be >= 1: " + tileSize);
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1: " + capacity);
        }
        return unit -> GLAtlas.createRgba8(unit, tileSize, capacity);
    }
}