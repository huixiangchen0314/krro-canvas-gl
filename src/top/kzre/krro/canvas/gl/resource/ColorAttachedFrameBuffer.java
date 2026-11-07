package top.kzre.krro.canvas.gl.resource;

import top.kzre.krro.core.util.SerialExecutor;

public abstract class ColorAttachedFrameBuffer extends GLFramebuffer {
    protected ColorAttachedFrameBuffer(int fbo, int width, int height, SerialExecutor glThread) {
        super(fbo, width, height, glThread);
    }

    public abstract int getColorLayer();

    public abstract GLTexture getColorAttachment();

    public abstract void clear(float r, float g, float b, float a);
}
