package top.kzre.krro.canvas.gl.tile;

/**
 * Atlas 工厂：为指定 unit 创建一个新的 {@link GLAtlas}。
 *
 * <p>调用方（{@link GLAtlasPool}）保证：同一 unit 只会调用一次。
 * 实现无需处理重复创建。
 *
 * <p><b>线程契约</b>：{@link #create()} 涉及 GL 调用，必须在 GL 线程上执行。
 *
 * <p><b>失败语义</b>：创建失败时抛异常——调用方不会重试，直接向上传播。
 */
@FunctionalInterface
public interface GLAtlasFactory {

    GLAtlas create();

}