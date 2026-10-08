package top.kzre.krro.canvas.gl.composite;

/**
 * 一批合成的规划结果——图层组 + 其可见瓦片集合。
 */
public final class ShaderBatchBundle {
    final ShaderLayerBundle bundle;
    final ShaderLayerTileSet tileSet;

    ShaderBatchBundle(ShaderLayerBundle bundle, ShaderLayerTileSet tileSet) {
        this.bundle = bundle;
        this.tileSet = tileSet;
    }
}
