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

    private final CompositeRequest request;

    public MockClearBlueCompositeTask(CompositeRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        this.request = request;
    }

    @Override
    public GLFramebuffer call() throws Exception {
        GLFramebuffer fboA = request.getFboA();

        fboA.bind();
        try {
            glClearColor(0f, 0f, 1f, 1f);   // 蓝色，不透明
            glClear(GL_COLOR_BUFFER_BIT);
        } finally {
            GLFramebuffer.unbind();
        }

        return fboA;
    }
}