package top.kzre.krro.canvas.gl.tile;

/**
 * 瓦片纹理布局。不可变值对象。
 *
 * <p>描述一张 {@code GL_TEXTURE_2D_ARRAY} 内部如何切分瓦片：
 * <ul>
 *   <li>{@code layers}         —— texture array 的层数</li>
 *   <li>{@code tileSize}       —— 每个瓦片的边长（像素），全局固定</li>
 *   <li>{@code tilesPerRow}    —— 每层水平方向的瓦片数（列数）</li>
 *   <li>{@code tilesPerColumn} —— 每层垂直方向的瓦片数（行数）</li>
 * </ul>
 *
 * <p>由此推导：
 * <pre>
 *   tilesPerLayer = tilesPerRow * tilesPerColumn
 *   layerWidth    = tilesPerRow    * tileSize
 *   layerHeight   = tilesPerColumn * tileSize
 *   capacity      = layers * tilesPerLayer
 * </pre>
 *
 * <h2>工厂方法</h2>
 *
 * <p>构造器私有——按场景选择工厂：
 * <ul>
 *   <li>{@link #square(int, int, int)} —— 正方形网格。
 *       atlas 用这种。</li>
 *   <li>{@link #fromPixelSize(int, int, int, int)} —— 从像素尺寸
 *       推导（向上取整）。FBO 视图用这种——视口尺寸不一定是
 *       tileSize 的整数倍。</li>
 * </ul>
 *
 * <p><b>线程契约</b>：纯数据，无 GL 副作用，任意线程可构造和读取。
 */
public final class GLTiledTextureLayout {

    /**
     * texture array 的层数。
     */
    private final int layers;

    /**
     * 每个瓦片的边长（像素）。
     */
    private final int tileSize;

    /**
     * 每层水平方向瓦片数（列数）。
     */
    private final int tilesPerRow;

    /**
     * 每层垂直方向瓦片数（行数）。
     */
    private final int tilesPerColumn;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    private GLTiledTextureLayout(int layers, int tileSize,
                                 int tilesPerRow, int tilesPerColumn) {
        if (layers <= 0) {
            throw new IllegalArgumentException("layers must be > 0: " + layers);
        }
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        if (tilesPerRow <= 0) {
            throw new IllegalArgumentException(
                    "tilesPerRow must be > 0: " + tilesPerRow);
        }
        if (tilesPerColumn <= 0) {
            throw new IllegalArgumentException(
                    "tilesPerColumn must be > 0: " + tilesPerColumn);
        }
        this.layers = layers;
        this.tileSize = tileSize;
        this.tilesPerRow = tilesPerRow;
        this.tilesPerColumn = tilesPerColumn;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 正方形网格布局——{@code tilesPerRow == tilesPerColumn == tilesPerEdge}。
     *
     * <p>典型用途：atlas 纹理。每层切成
     * {@code tilesPerEdge × tilesPerEdge} 个瓦片。
     *
     * @param layers       texture array 层数，必须 &gt; 0
     * @param tileSize     瓦片边长（像素），必须 &gt; 0
     * @param tilesPerEdge 每层每边的瓦片数，必须 &gt; 0
     * @return 布局
     * @throws IllegalArgumentException 任一参数 ≤ 0
     */
    public static GLTiledTextureLayout square(int layers, int tileSize,
                                              int tilesPerEdge) {
        return new GLTiledTextureLayout(layers, tileSize, tilesPerEdge, tilesPerEdge);
    }

    /**
     * 从像素尺寸推导——向上取整支持非整除尺寸。
     *
     * <p>典型用途：FBO 视图。纹理尺寸不一定是 tileSize 的整数倍，
     * 例如 1920×1080 配 64px 瓦片 → {@code tilesPerRow=30}、
     * {@code tilesPerColumn=17}（覆盖到 1088，超过部分留空）。
     *
     * @param layers      层数
     * @param tileSize    瓦片边长
     * @param pixelWidth  层宽（像素），必须 &gt; 0
     * @param pixelHeight 层高（像素），必须 &gt; 0
     * @return 布局
     * @throws IllegalArgumentException 参数非法
     */
    public static GLTiledTextureLayout fromPixelSize(int layers, int tileSize,
                                                     int pixelWidth,
                                                     int pixelHeight) {
        if (pixelWidth <= 0 || pixelHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid pixel size: " + pixelWidth + "x" + pixelHeight);
        }
        int rows = (pixelWidth + tileSize - 1) / tileSize;
        int cols = (pixelHeight + tileSize - 1) / tileSize;
        return new GLTiledTextureLayout(layers, tileSize, rows, cols);
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /**
     * texture array 层数。
     */
    public int getLayers() {
        return layers;
    }

    /**
     * 瓦片边长（像素）。
     */
    public int getTileSize() {
        return tileSize;
    }

    /**
     * 每层水平方向瓦片数（列数）。
     */
    public int getTilesPerRow() {
        return tilesPerRow;
    }

    /**
     * 每层垂直方向瓦片数（行数）。
     */
    public int getTilesPerColumn() {
        return tilesPerColumn;
    }

    /**
     * 每层瓦片总数 = {@code tilesPerRow * tilesPerColumn}。
     */
    public int getTilesPerLayer() {
        return tilesPerRow * tilesPerColumn;
    }

    /**
     * 每层宽度（像素）= {@code tilesPerRow * tileSize}。
     */
    public int getLayerWidth() {
        return tilesPerRow * tileSize;
    }

    /**
     * 每层高度（像素）= {@code tilesPerColumn * tileSize}。
     */
    public int getLayerHeight() {
        return tilesPerColumn * tileSize;
    }

    /**
     * 总槽位 = {@code layers * tilesPerLayer}。
     */
    public int getCapacity() {
        return layers * getTilesPerLayer();
    }

    /**
     * 是否正方形网格。
     */
    public boolean isSquare() {
        return tilesPerRow == tilesPerColumn;
    }

    /**
     * 每层每边的瓦片数——仅正方形布局有效。
     *
     * <p>保留给既有的正方形 atlas 调用方。
     *
     * @throws IllegalStateException 布局非正方形
     */
    public int getTilesPerEdge() {
        if (tilesPerRow != tilesPerColumn) {
            throw new IllegalStateException(
                    "layout is not square: " + tilesPerRow + "x" + tilesPerColumn);
        }
        return tilesPerRow;
    }

    @Override
    public String toString() {
        return "GLTiledTextureLayout{layers=" + layers
                + ", tileSize=" + tileSize
                + ", tiles=" + tilesPerRow + "x" + tilesPerColumn
                + ", layerSize=" + getLayerWidth() + "x" + getLayerHeight()
                + ", capacity=" + getCapacity() + "}";
    }
}