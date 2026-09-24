package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLFramebuffer;

/**
 * 一次合成请求。规划阶段的顶层产出，执行阶段的顶层输入。
 *
 * <p><b>三部分</b>：
 * <ul>
 *   <li>{@link #getViewportGrid()} —— 屏幕瓦片网格</li>
 *   <li>{@link #getBundle()} —— 全部资源与执行序列</li>
 *   <li>{@link #getFboA()} / {@link #getFboB()} —— 双 FBO，用于 ping-pong</li>
 * </ul>
 *
 * <h2>规划阶段做什么</h2>
 *
 * <p>规划阶段在 CPU 线程完成，<b>纯计算，无 GL 副作用</b>。产出
 * 本类的全部字段。执行阶段只消费，不做任何决策。
 *
 * <p><b>1. 构建 ViewportGrid</b>：
 * <ul>
 *   <li>根据视口尺寸和瓦片边长，枚举所有可见的屏幕瓦片</li>
 *   <li>产出 {@code tileXY[]}——每个屏幕瓦片的坐标</li>
 *   <li>确定实例数 = 屏幕瓦片数</li>
 * </ul>
 *
 * <p><b>2. 分配 atlas 槽位</b>：
 * <ul>
 *   <li>遍历所有图层的所有瓦片，视口裁剪后逐块决定其在 atlas
 *       中的位置</li>
 *   <li>产出 {@code Map<AtlasSlot, TileRef>}——每块瓦片的目标槽位</li>
 *   <li>同一图层的瓦片尽量安排相邻槽位，提升采样缓存命中率</li>
 *   <li>容量不足时切批——本 request 只承载一批</li>
 * </ul>
 *
 * <p><b>3. 打包每个 group 的 buffer 数据</b>：
 * <ul>
 *   <li>{@code tileEntries[]}——每条目 12 float，描述一块图层瓦片
 *       的变换、uv、atlas 位置。字段内容直接来源于分配的 AtlasSlot
 *       和图层自身的变换、透明度</li>
 *   <li>{@code indexList[]}——扁平 int 数组，按屏幕瓦片顺序拼接。
 *       每个屏幕瓦片覆盖的图层瓦片索引连续存放</li>
 *   <li>{@code offsets[]} / {@code counts[]}——每个屏幕瓦片在本组
 *       indexList 中的起始和长度</li>
 * </ul>
 *
 * <p><b>4. 组装 Shader 序列</b>：
 * <ul>
 *   <li>每个 shader 声明它需要的 group、backdrop 单元、实例
 *       attribute location</li>
 *   <li>shader 的顺序即执行顺序——每个 shader 一次 draw call</li>
 *   <li>后一个 shader 的输出（写在 FBO 上）是前一个 shader 的输入</li>
 * </ul>
 *
 * <p><b>5. 收集 atlas 资源</b>：
 * <ul>
 *   <li>把本批用到的 atlas 按下标即单元排列</li>
 *   <li>与 {@code tileEntries} 里的 {@code atlasIndex} 字段对齐</li>
 * </ul>
 *
 * <h2>数据流的形状</h2>
 *
 * <pre>
 *   图层列表 + 视口
 *         ↓
 *   视口裁剪 → 可见瓦片集合
 *         ↓
 *   槽位规划 → Map<AtlasSlot, TileRef>
 *         ↓
 *   按屏幕瓦片分桶 → offsets[] / counts[] / indexList[]
 *         ↓
 *   打包 tileEntries[]（含 atlasIndex / layer / uv / 变换）
 *         ↓
 *   组装 Shader 序列
 *         ↓
 *   CompositeRequest
 * </pre>
 *
 * <p>整个过程是<b>自洽</b>的——所有输入来自图层数据和视口定义，
 * 所有产出都在 request 内部。执行阶段不需要再引入任何外部信息。
 *
 * <h2>规划与执行的边界</h2>
 *
 * <p>规划阶段<b>不接触 GL</b>：
 * <ul>
 *   <li>不分配纹理、不创建 FBO、不上传 buffer</li>
 *   <li>只做数据计算和结构组装</li>
 * </ul>
 *
 * <p>执行阶段<b>不做决策</b>：
 * <ul>
 *   <li>换入瓦片时按规划好的 slot 分配</li>
 *   <li>上传规划好的 buffer</li>
 *   <li>按规划好的 shader 顺序执行</li>
 * </ul>
 *
 * <p>两侧通过本类交接。规划阶段的全部不确定性（哪些瓦片可见、
 * 放哪个槽位、怎么分组、哪个 shader 先跑）在执行阶段都不存在。
 *
 * <h2>FBO 由外部提供</h2>
 *
 * <p>双 FBO 的所有权和生命周期由调用方管理。任务只借用它们做
 * ping-pong 渲染，不创建、不释放。
 *
 * <p>外部提供 FBO 的原因：
 * <ul>
 *   <li>控制尺寸和格式——不一定用默认的 RGBA8</li>
 *   <li>跨任务复用——避免每次创建/释放的开销</li>
 *   <li>规划封闭性——FBO 是 atlas 视图，和图层瓦片在同一个池
 *       里管理</li>
 * </ul>
 *
 * <p>任务结束时返回的 FBO 是结果的持有者：
 * <ul>
 *   <li>shader 数量为奇数 → 结果是 {@code fboA}</li>
 *   <li>shader 数量为偶数 → 结果是 {@code fboB}</li>
 * </ul>
 * 调用方通过返回值判断哪个 FBO 持有结果。
 *
 * <h2>backdrop</h2>
 *
 * <p>不作为独立概念。CPU 侧背景融入 {@link TileBufferBundle} 的
 * group，走普通瓦片路径；GPU 侧背景由外部在 FBO 上预渲染，或
 * 直接通过 bundle 的 group 表达。执行阶段没有任何 backdrop 相关
 * 的分支。
 *
 * <h2>工厂方法</h2>
 *
 * <p>用 {@link #of} 构造，不直接暴露构造器。
 *
 * <h2>线程契约</h2>
 *
 * <p>纯数据，任意线程可构造和读取。FBO 的实际使用在 GL 线程。
 */
public final class CompositeRequest {

    private final ViewportGrid     viewportGrid;
    private final TileBufferBundle bundle;
    private final GLFramebuffer    fboA;
    private final GLFramebuffer    fboB;

    private CompositeRequest(ViewportGrid viewportGrid,
                             TileBufferBundle bundle,
                             GLFramebuffer fboA,
                             GLFramebuffer fboB) {
        this.viewportGrid = viewportGrid;
        this.bundle       = bundle;
        this.fboA         = fboA;
        this.fboB         = fboB;
    }

    // ═══════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════

    /**
     * 构造合成请求。
     *
     * @param viewportGrid 屏幕瓦片网格，不能为 null
     * @param bundle       全部资源与执行序列，不能为 null
     * @param fboA         ping-pong 缓冲 A，不能为 null
     * @param fboB         ping-pong 缓冲 B，不能为 null
     */
    public static CompositeRequest of(ViewportGrid viewportGrid,
                                      TileBufferBundle bundle,
                                      GLFramebuffer fboA,
                                      GLFramebuffer fboB) {
        if (viewportGrid == null) throw new IllegalArgumentException("viewportGrid must not be null");
        if (bundle == null)       throw new IllegalArgumentException("bundle must not be null");
        if (fboA == null)         throw new IllegalArgumentException("fboA must not be null");
        if (fboB == null)         throw new IllegalArgumentException("fboB must not be null");
        if (fboA == fboB)         throw new IllegalArgumentException("fboA and fboB must differ");
        return new CompositeRequest(viewportGrid, bundle, fboA, fboB);
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public ViewportGrid     getViewportGrid() { return viewportGrid; }
    public TileBufferBundle getBundle()       { return bundle; }

    /** ping-pong 缓冲 A。所有权在调用方。 */
    public GLFramebuffer    getFboA()         { return fboA; }

    /** ping-pong 缓冲 B。所有权在调用方。 */
    public GLFramebuffer    getFboB()         { return fboB; }
}