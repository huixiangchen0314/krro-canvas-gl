package top.kzre.krro.canvas.gl.resource;

public interface GLTexturePool extends GLThreadAffine {
    void release(GLTexture texture);
}
