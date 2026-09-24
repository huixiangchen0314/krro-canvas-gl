package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.resource.GLTexturePool;
import top.kzre.krro.core.util.AsyncExecutor;
import top.kzre.krro.util.tile.TiledCanvas;

/**
 * 完整纹理的 {@link TiledCanvas} 视图。拥有整张纹理——
 * 所有视图归零时把纹理归还给 {@link GLTexturePool}。
 *
 * <p>构造时注入全部层的视图。适合"独占整张纹理"的场景。
 * 如果只想用其中几层，用 {@link GLTiledTextureLayer} 借用。
 */
public final class GLTiledTexture extends AbstractGLTiledTexture {

    private final GLTexturePool texPool;

    public GLTiledTexture(GLTexture texture, int tileSize,
                          GLTexturePool texPool,
                          AsyncExecutor glExecutor) {
        super(texture, tileSize, glExecutor);
        if (texPool == null) {
            throw new IllegalArgumentException("texPool must not be null");
        }
        this.texPool = texPool;
        populateCanvas(0, texture.getLayers());
    }

    @Override
    protected void onAllViewsReleased() {
        texPool.release(texture);
    }
}