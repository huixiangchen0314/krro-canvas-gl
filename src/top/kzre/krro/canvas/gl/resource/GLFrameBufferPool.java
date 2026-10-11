package top.kzre.krro.canvas.gl.resource;

public interface GLFrameBufferPool extends GLThreadAffine{
    void release(GLFramebuffer framebuffer);
}
