package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLFramebuffer;
import top.kzre.krro.canvas.gl.tile.AtlasPoolPage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次合成请求。规划阶段的顶层产出，执行阶段的顶层输入。
 *
 * <p><b>四部分</b>：
 * <ul>
 *   <li>{@link #getPage()} —— atlas 换页能力，所有 bundle 共享</li>
 *   <li>{@link #getViewportGrid()} —— 屏幕瓦片网格</li>
 *   <li>{@link #getDraws()} —— 按顺序执行的 bundle 列表</li>
 *   <li>{@link #getFboA()} / {@link #getFboB()} —— ping-pong 双 FBO</li>
 * </ul>
 *
 * <h2>page 的提升</h2>
 *
 * <p>{@code AtlasPoolPage} 从 bundle 内部提升到 request 层级——
 * 换页能力服务于整个合成任务，多个 bundle 共享同一个 atlas 池。
 * bundle 只描述"这次 draw call 需要什么"，不重复持有 page。
 *
 * <h2>bundles 列表</h2>
 *
 * <p>每个 {@link ShaderDraw} 对应一次 draw call。列表顺序即
 * 执行顺序。第 i 个 bundle 的输出写 {@code pool[i % 2]}。
 *
 * <h2>线程契约</h2>
 *
 * <p>纯数据，任意线程可构造和读取。FBO 的实际使用在 GL 线程。
 */
public final class CompositeRequest {

    private final AtlasPoolPage page;
    private final ViewTileInfo viewTileInfo;
    private final List<ShaderDraw> draws;
    private final GLFramebuffer fboA;
    private final GLFramebuffer fboB;
    private final GLCompositeContext context;
    private CompositeRequest(GLCompositeContext context, ViewTileInfo viewTileInfo, List<ShaderDraw> draws, AtlasPoolPage page,
                             GLFramebuffer fboA,
                             GLFramebuffer fboB) {
        this.page         = page;
        this.viewTileInfo = viewTileInfo;
        this.draws = draws;
        this.fboA         = fboA;
        this.fboB         = fboB;
        this.context = context;
    }

    public static CompositeRequest of(GLCompositeContext context,
                                      ViewTileInfo viewTileInfo,
                                      List<ShaderDraw> bundles,
                                      AtlasPoolPage page,
                                      GLFramebuffer fboA,
                                      GLFramebuffer fboB) {
        if (page == null)         throw new IllegalArgumentException("page must not be null");
        if (viewTileInfo == null) throw new IllegalArgumentException("viewportGrid must not be null");
        if (bundles == null)      throw new IllegalArgumentException("bundles must not be null");
        for (ShaderDraw b : bundles) {
            if (b == null) {
                throw new IllegalArgumentException("bundles must not contain null");
            }
        }
        if (fboA == null)         throw new IllegalArgumentException("fboA must not be null");
        if (fboB == null)         throw new IllegalArgumentException("fboB must not be null");
        if (fboA == fboB)         throw new IllegalArgumentException("fboA and fboB must differ");

        return new CompositeRequest(
                context,
                viewTileInfo,
                Collections.unmodifiableList(new ArrayList<>(bundles)),
                page,
                fboA, fboB);
    }

    /** atlas 换页能力。所有 bundle 共享。 */
    public AtlasPoolPage          getPage()         { return page; }

    public ViewTileInfo getViewportGrid() { return viewTileInfo; }
    public List<ShaderDraw> getDraws()      { return draws; }

    public GLFramebuffer          getFboA()         { return fboA; }
    public GLFramebuffer          getFboB()         { return fboB; }

    @Override
    public String toString() {
        return "CompositeRequest{grid=" + viewTileInfo
                + ", bundles=" + draws.size() + "}";
    }

    public GLCompositeContext getContext() {
        return context;
    }
}