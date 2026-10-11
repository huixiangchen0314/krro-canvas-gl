package top.kzre.krro.canvas.gl.composite;

public final class ShaderConstants {
    private ShaderConstants() {}

    // 属性绑定
    public static final int ATTRIBUTE_POSITION_QUAD = 0;



    // 最大供图层合成的纹理单元数[0...MAX_LAYER_TEXTURE_UNIT)
    public static final int MAX_LAYER_TEXTURE_UNIT_COUNT = 16;

    // 表绑定
    public static final int TEXTURE_UNIT_TILE_TABLE = 16;
    public static final int TEXTURE_UNIT_LAYER_TABLE = 17;
    public static final int TEXTURE_UNIT_INDEX_TABLE = 18;
}
