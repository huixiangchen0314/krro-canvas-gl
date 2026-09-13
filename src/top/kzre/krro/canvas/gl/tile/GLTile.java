package top.kzre.krro.canvas.gl.tile;

public interface GLTile {

    /**
     * 产出描述符，用于绘制阶段定位此瓦片。
     */
    GLTileDescriptor getDescriptor();

}