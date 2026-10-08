package top.kzre.krro.canvas.gl.composite;

public final class ShaderLayerTileKey {
    final ILayer layer;
    final long tileKey;

    private ShaderLayerTileKey(ILayer layer, long tileKey) {
        this.layer = layer;
        this.tileKey = tileKey;
    }

    public static ShaderLayerTileKey of(ILayer layer, long tileKey){
        return new ShaderLayerTileKey(layer, tileKey);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderLayerTileKey)) return false;
        ShaderLayerTileKey that = (ShaderLayerTileKey) o;
        return tileKey == that.tileKey && layer == that.layer;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(layer) + Long.hashCode(tileKey);
    }
}
