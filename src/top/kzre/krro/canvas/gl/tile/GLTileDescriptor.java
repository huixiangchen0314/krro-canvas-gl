package top.kzre.krro.canvas.gl.tile;

/**
 * GL 瓦片描述符：定位一块瓦片的 GPU 侧采样位置。
 *
 * <p>本类是<b>纯数据</b>——只描述一块 tile 在 GPU 上「在哪」，不涉及
 * 打包方式、attribute 布局、shader 消费方式。这些由外部的渲染管线定义。
 *
 * <p><b>字段语义</b>：
 * <ul>
 *   <li>{@link #getUnit()}     纹理单元——多 atlas 场景下区分 atlas</li>
 *   <li>{@link #getLayer()}    texture array 层索引</li>
 *   <li>{@link #getTileSize()} tile 边长（像素）</li>
 *   <li>{@link #getU0()} / {@link #getV0()}  层内 uv 起点</li>
 * </ul>
 *
 * <p><b>方向约定</b>：纹理坐标原点在左下角（GL 约定）。uv 终点由
 * {@code u0 + tileSize / layerSize} 推导——{@code layerSize} 的具体来源
 * 由外部决定（uniform / 常量 / 每层不同，都行）。
 */
public final class GLTileDescriptor {

    // ═══════════════════════════════════════════════
    // 字段
    // ═══════════════════════════════════════════════

    /** 纹理单元。多 atlas 场景下区分 atlas。 */
    private final int unit;

    /** texture array 中的层索引。 */
    private final int layer;

    /** tile 边长（像素）。 */
    private final int tileSize;

    /** 层内 uv 起点 u（左下角）。 */
    private final float u0;

    /** 层内 uv 起点 v（左下角）。 */
    private final float v0;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    private GLTileDescriptor(int unit, int layer, int tileSize,
                             float u0, float v0) {
        this.unit     = unit;
        this.layer    = layer;
        this.tileSize = tileSize;
        this.u0       = u0;
        this.v0       = v0;
    }

    // ═══════════════════════════════════════════════
    // 工厂
    // ═══════════════════════════════════════════════

    /**
     * 标准构造：一层一块 tile，uv 起点为 (0, 0)。
     */
    public static GLTileDescriptor of(int unit, int layer, int tileSize) {
        return new GLTileDescriptor(unit, layer, tileSize, 0f, 0f);
    }

    /**
     * 完整构造：指定层内 uv 起点（用于一层多块 tile 的子区域）。
     */
    public static GLTileDescriptor of(int unit, int layer, int tileSize,
                                      float u0, float v0) {
        return new GLTileDescriptor(unit, layer, tileSize, u0, v0);
    }

    // ═══════════════════════════════════════════════
    // 访问器
    // ═══════════════════════════════════════════════

    public int   getUnit()     { return unit; }
    public int   getLayer()    { return layer; }
    public int   getTileSize() { return tileSize; }
    public float getU0()       { return u0; }
    public float getV0()       { return v0; }

    // ═══════════════════════════════════════════════
    // Object
    // ═══════════════════════════════════════════════

    @Override
    public String toString() {
        return "GLTileDescriptor{"
                + "unit=" + unit
                + ", layer=" + layer
                + ", tileSize=" + tileSize
                + ", uv0=[" + u0 + "," + v0 + "]"
                + '}';
    }
}