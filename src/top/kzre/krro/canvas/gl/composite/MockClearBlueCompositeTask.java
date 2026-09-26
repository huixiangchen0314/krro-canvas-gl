package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLFramebuffer;

import java.util.concurrent.Callable;

import static org.lwjgl.opengl.GL11.*;

/**
 * Mock 合成任务。不做真实合成，把 fboA 清成蓝色并返回。
 *
 * <p>用于验证调用链——不依赖 atlas、shader、换页等资源。
 */
public class MockClearBlueCompositeTask implements Callable<GLFramebuffer> {

    private final GLFramebuffer fbo;

    public MockClearBlueCompositeTask(GLFramebuffer fbo) {
       this.fbo = fbo;
    }

    @Override
    public GLFramebuffer call() throws Exception {

        fbo.bind();
        try {
            // 代尔夫特蓝
            glClearColor(31.0f / 255.0f, 48.0f / 255.0f, 94.0f / 255.0f, 0.8f);
            glClear(GL_COLOR_BUFFER_BIT);
        } finally {
            GLFramebuffer.unbind();
        }

        return fbo;
    }
}