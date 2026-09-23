package top.kzre.krro.canvas.gl.tile;

/**
 * 瓦片纹理布局。不可变值对象。
 *
 * <p>描述一张 {@code GL_TEXTURE_2D_ARRAY} 内部如何切分瓦片：
 * <ul>
 *   <li>{@code layers}       —— texture array 的层数</li>
 *   <li>{@code tileSize}     —— 每个瓦片的边长（像素），全局固定</li>
 *   <li>{@code tilesPerEdge} —— 每层的每行/每列瓦片数。一层切成
 *       {@code tilesPerEdge × tilesPerEdge} 个瓦片</li>
 * </ul>
 *
 * <p>由此推导：
 * <pre>
 *   tilesPerLayer = tilesPerEdge²
 *   layerSize     = tilesPerEdge * tileSize
 *   capacity      = layers * tilesPerEdge²
 * </pre>
 *
 * <p><b>线程契约</b>：纯数据，无 GL 副作用，任意线程可构造和读取。
 */
public final class GLTiledTextureLayout {

    /** texture array 的层数。 */
    private final int layers;

    /** 每个瓦片的边长（像素）。 */
    private final int tileSize;

    /** 每层的每行瓦片数（等于每列瓦片数）。一层含 tilesPerEdge² 个瓦片。 */
    private final int tilesPerEdge;

    /**
     * @param layers       texture array 层数，必须 &gt; 0
     * @param tileSize     瓦片边长（像素），必须 &gt; 0
     * @param tilesPerEdge 每层每行的瓦片数，必须 &gt; 0
     */
    public GLTiledTextureLayout(int layers, int tileSize, int tilesPerEdge) {
        this.layers       = layers;
        this.tileSize     = tileSize;
        this.tilesPerEdge = tilesPerEdge;
    }

    /** texture array 层数。 */
    public int getLayers() { return layers; }

    /** 瓦片边长（像素）。 */
    public int getTileSize() { return tileSize; }

    /** 每层每行的瓦片数。 */
    public int getTilesPerEdge() { return tilesPerEdge; }

    /** 每层瓦片总数 = {@code tilesPerEdge²}。 */
    public int getTilesPerLayer() { return tilesPerEdge * tilesPerEdge; }

    /** 每层边长（像素）= {@code tilesPerEdge * tileSize}。 */
    public int getLayerSize() { return tilesPerEdge * tileSize; }

    /** 总槽位 = {@code layers * tilesPerEdge²}。 */
    public int getCapacity() { return layers * getTilesPerLayer(); }

    @Override
    public String toString() {
        return "GLTiledTextureLayout{layers=" + layers
                + ", tileSize=" + tileSize
                + ", tilesPerEdge=" + tilesPerEdge
                + ", layerSize=" + getLayerSize()
                + ", capacity=" + getCapacity() + "}";
    }
}