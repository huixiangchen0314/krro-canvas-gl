package top.kzre.krro.canvas.gl.composite;

import java.util.Arrays;

/**
 * 视口瓦片网格。描述本批次要渲染的屏幕瓦片集合。
 *
 * <p><b>纯数据</b>：只描述"哪些屏幕瓦片"——坐标、总数、以及网格
 * 的几何参数（视口尺寸、瓦片边长）。不含实例数据、不含各组
 * offset/count、不含 shader。
 *
 * <h2>为什么独立于 TileBufferGroup</h2>
 *
 * <p>屏幕瓦片网格是<b>视口级</b>属性，不随 group 变化。多个 group
 * 共享同一个网格——它们的 tileXY 完全一致，只是每个屏幕瓦片上覆盖
 * 的图层瓦片不同。把网格放进 group 会导致：
 * <ul>
 *   <li>每组重复存储 tileXY，浪费带宽</li>
 *   <li>多组场景下 instanceCount 归属不明——该用哪个 group 的？</li>
 * </ul>
 *
 * <p>拆出来后，网格独立，draw call 的实例数唯一来自本类。各组的
 * offset/count 由 {@link TileBufferGroup} 分别持有。
 *
 * <h2>数据结构</h2>
 *
 * <p>{@code tileXY} 每屏幕瓦片 2 个 int：{@code [tileX, tileY]}。
 * 顺序即实例顺序——第 i 个屏幕瓦片的坐标是
 * {@code tileXY[2i]} / {@code tileXY[2i+1]}。
 *
 * <h2>执行阶段</h2>
 *
 * <p>执行器把本类的 tileXY 和各组的 offsets/counts 拼成 VBO 的
 * 实例数据：
 * <pre>
 *   [tileX, tileY, g0.offset, g0.count, g1.offset, g1.count, ...]
 * </pre>
 * draw call 的实例数 = {@link #getViewTileCount()}。
 *
 * <h2>线程契约</h2>
 *
 * <p>纯数据，任意线程可构造和读取。{@link #getTileXY()} 返回内部
 * 数组引用，调用方不得修改。
 */
public final class ViewportGrid {

    /** 视口宽度（像素）。 */
    private final int viewWidth;

    /** 视口高度（像素）。 */
    private final int viewHeight;

    /** 瓦片边长（像素）。 */
    private final int tileSize;

    /** 屏幕瓦片坐标。每屏幕瓦片 2 int：{@code [tileX, tileY]}。 */
    private final int[] tileXY;

    /** 屏幕瓦片总数。 */
    private final int viewTileCount;

    /**
     * @param viewWidth       视口宽度（像素），必须 &gt; 0
     * @param viewHeight      视口高度（像素），必须 &gt; 0
     * @param tileSize        瓦片边长（像素），必须 &gt; 0
     * @param tileXY          屏幕瓦片坐标数组，长度至少
     *                        {@code 2 * screenTileCount}
     * @param viewTileCount 屏幕瓦片总数，必须 &ge; 0
     * @throws IllegalArgumentException 参数非法
     */
    public ViewportGrid(int viewWidth, int viewHeight, int tileSize,
                        int[] tileXY, int viewTileCount) {
        if (viewWidth <= 0) {
            throw new IllegalArgumentException("viewWidth must be > 0: " + viewWidth);
        }
        if (viewHeight <= 0) {
            throw new IllegalArgumentException("viewHeight must be > 0: " + viewHeight);
        }
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        if (viewTileCount < 0) {
            throw new IllegalArgumentException(
                    "screenTileCount must be >= 0: " + viewTileCount);
        }
        if (tileXY == null || tileXY.length < viewTileCount * 2) {
            throw new IllegalArgumentException(
                    "tileXY must have length >= " + (viewTileCount * 2));
        }
        this.viewWidth       = viewWidth;
        this.viewHeight      = viewHeight;
        this.tileSize        = tileSize;
        this.tileXY          = Arrays.copyOf(tileXY, viewTileCount * 2);
        this.viewTileCount = viewTileCount;
    }

    /**
     * 创建覆盖整个视口的密集网格。
     *
     * <p>所有在视口范围内的屏幕瓦片都被包含——从 (0, 0) 到
     * {@code (cols-1, rows-1)}，其中
     * {@code cols = ceil(viewWidth / tileSize)}，
     * {@code rows = ceil(viewHeight / tileSize)}。
     *
     * <p>适合全屏渲染。稀疏网格用构造函数直接指定坐标。
     *
     * @throws IllegalArgumentException viewWidth / viewHeight / tileSize
     *                                  任一非正
     */
    public static ViewportGrid createFull(int viewWidth, int viewHeight, int tileSize) {
        if (viewWidth <= 0 || viewHeight <= 0 || tileSize <= 0) {
            throw new IllegalArgumentException(
                    "viewWidth/viewHeight/tileSize must be > 0");
        }
        int cols = (viewWidth + tileSize - 1) / tileSize;
        int rows = (viewHeight + tileSize - 1) / tileSize;
        int count = cols * rows;

        int[] tileXY = new int[count * 2];
        int idx = 0;
        for (int ty = 0; ty < rows; ty++) {
            for (int tx = 0; tx < cols; tx++) {
                tileXY[idx++] = tx;
                tileXY[idx++] = ty;
            }
        }
        return new ViewportGrid(viewWidth, viewHeight, tileSize, tileXY, count);
    }

    public static ViewportGrid createPartial(int viewWidth, int viewHeight, int tileSize, int[] tileXY) {
        return new ViewportGrid(viewWidth, viewHeight, tileSize, tileXY, tileXY.length / 2);
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    /** 视口宽度（像素）。 */
    public int getViewWidth() { return viewWidth; }

    /** 视口高度（像素）。 */
    public int getViewHeight() { return viewHeight; }

    /** 瓦片边长（像素）。 */
    public int getTileSize() { return tileSize; }

    /** 屏幕瓦片总数。 */
    public int getViewTileCount() { return viewTileCount; }

    /**
     * 屏幕瓦片坐标数组。每 2 int 一对 {@code [tileX, tileY]}。
     *
     * <p>返回内部数组引用，调用方不得修改。
     */
    public int[] getTileXY() { return tileXY; }

    /** 第 i 个屏幕瓦片的 x 坐标。 */
    public int getTileX(int i) { return tileXY[i * 2]; }

    /** 第 i 个屏幕瓦片的 y 坐标。 */
    public int getTileY(int i) { return tileXY[i * 2 + 1]; }

    // ═══════════════════════════════════════════════
    // Object
    // ═══════════════════════════════════════════════

    @Override
    public String toString() {
        return "ViewportGrid{viewport=" + viewWidth + "x" + viewHeight
                + ", tileSize=" + tileSize
                + ", screenTileCount=" + viewTileCount + "}";
    }
}