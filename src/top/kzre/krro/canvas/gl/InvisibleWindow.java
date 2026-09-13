package top.kzre.krro.canvas.gl;

import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;

/**
 * 不可见窗口工具类,用于离屏渲染
 */
public final class InvisibleWindow {
    private long window;

    /**
     * 新建不可见 GLFW 环境
     */
    public void init() {
        if (!glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);
         window = glfwCreateWindow(1, 1, "Offscreen", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == 0L) {
            throw new RuntimeException("Failed to create GLFW window");
        }
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

    }

    public  void destroy() {
        if (window != 0L) {
            glfwDestroyWindow(window);
        }
        glfwTerminate();
    }
}
