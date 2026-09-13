package top.kzre.krro.canvas.gl;

import top.kzre.krro.core.util.SerialExecutor;

/**
 * GL 执行器工具类
 */
public final class GLExecutors {

    private static volatile SerialExecutor defaultExecutor = null;

    /**
     * 返回默认的离屏渲染执行器
     */
    public static synchronized SerialExecutor offscreen() {
        if (defaultExecutor == null) {
            defaultExecutor = newOffscreen(1000, 5000);
        }
        return defaultExecutor;
    }

    /**
     * 新建离屏渲染执行器
     */
    public static SerialExecutor newOffscreen(int capacity, long timeoutMs) {
        // 闭包捕获不可见窗口
        InvisibleWindow window = new InvisibleWindow();
        return new SerialExecutor("krro-canvas-gl-offscreen-",
                capacity,
                SerialExecutor.OverflowPolicy.DROP_OLDEST,
                timeoutMs,
                window::init,
                window::destroy
                );
    }
}
