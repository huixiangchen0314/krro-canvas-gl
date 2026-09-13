package top.kzre.krro.canvas.gl.resource;

import org.lwjgl.opengl.ARBTextureStorage;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL30.*;

/**
 * GL 纹理封装，统一基于 {@code GL_TEXTURE_2D_ARRAY} 表达两种用途：
 *
 * <ul>
 *   <li><b>tile 存储</b>：每层存放一个 tile，整块 canvas 可用一次 draw call 渲染。
 *       此时 {@code layers = tile 数量}。</li>
 *   <li><b>离屏渲染目标</b>：作为 {@link GLFramebuffer} 的颜色附着。
 *       此时 {@code layers = 1}，语义上退化为一张 2D 纹理。</li>
 * </ul>
 *
 * <h2>存储分配</h2>
 * 优先使用 GL 4.2 的 {@code glTexStorage3D}（不可变存储）：
 * <ol>
 *   <li>若当前上下文为 OpenGL 4.2+ 核心，使用 {@link GL42#glTexStorage3D}。</li>
 *   <li>否则若支持 {@code ARB_texture_storage} 扩展，使用
 *       {@link ARBTextureStorage#glTexStorage3D}。</li>
 *   <li>都不满足时回退到 {@link GL12#glTexImage3D}（可变存储，GL 1.2+ 兼容）。</li>
 * </ol>
 * 分配之后不再重新分配——所有内容更新走 {@code glTexSubImage3D}。
 *
 * <h2>采样参数</h2>
 * 默认使用 {@code GL_NEAREST} 过滤与 {@code GL_CLAMP_TO_EDGE} 寻址。
 * 这是画布 / tile 场景的合理默认（像素精确、不重复）。如需其他参数，
 * 请在创建后自行调用 {@code glTexParameteri} 覆盖。
 *
 * <h2>线程契约</h2>
 * <b>所有方法都必须在 GL 线程（current context）上调用。</b>
 * 典型方式是通过
 * {@code GLExecutors.offscreen().submit(() -> texture.uploadLayer(...))}
 * 把调用提交到 GL 线程执行。本类不做线程检查。
 *
 * <h2>所有权</h2>
 * 创建者拥有该纹理，负责调用 {@link #release()}。本类
 * <b>不实现 {@link AutoCloseable}</b>——因为 GL 资源释放必须在 GL 线程上，
 * 而 {@code try-with-resources} 可能在任何线程上触发 {@code close()}，
 * 容易造成隐式跨线程 GL 调用。请显式在 GL 任务里调用 {@code release()}。
 *
 * <h2>幂等性</h2>
 * {@link #release()} 幂等。释放后所有其他方法抛 {@link IllegalStateException}。
 */
public final class GLTexture {

    // ═══════════════════════════════════════════════
    // 内部状态
    // ═══════════════════════════════════════════════

    private final int         handle;
    private final int         width;
    private final int         height;
    private final int         layers;      // texture array 层数；2D 场景为 1
    private final PixelFormat pixelFormat;

    private boolean released = false;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════
    private int downloadFbo = 0;   // 懒创建的下载专用 FBO

    private GLTexture(int handle,
                      int width, int height, int layers,
                      PixelFormat pixelFormat) {
        this.handle      = handle;
        this.width       = width;
        this.height      = height;
        this.layers      = layers;
        this.pixelFormat = pixelFormat;
    }

    // ═══════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════

    /**
     * 创建一张 RGBA8 纹理数组（最常用），不初始化内容。
     *
     * @param width  tile 宽（像素），必须 &gt; 0
     * @param height tile 高（像素），必须 &gt; 0
     * @param layers 层数（tile 数量），必须 &gt; 0
     * @return 新的纹理对象；调用方拥有所有权，负责 {@link #release()}
     * @throws IllegalArgumentException 尺寸非法
     */
    public static GLTexture createRgba8(int width, int height, int layers) {
        return create(width, height, layers, PixelFormat.RGBA8);
    }

    /**
     * 创建单层 RGBA8 纹理（2D 形态），常用于 FBO 颜色附着。
     */
    public static GLTexture createRgba8(int width, int height) {
        return createRgba8(width, height, 1);
    }

    /**
     * 通用创建：指定像素格式。
     *
     * <p>创建过程：
     * <ol>
     *   <li>生成纹理句柄；</li>
     *   <li>绑定到 {@code GL_TEXTURE_2D_ARRAY}；</li>
     *   <li>分配存储（{@code glTexStorage3D} 或 {@code glTexImage3D} 回退）；</li>
     *   <li>设置默认采样参数（NEAREST + CLAMP_TO_EDGE）；</li>
     *   <li>解绑。</li>
     * </ol>
     *
     * <p>若过程中抛异常，已生成的纹理句柄会被释放，不泄漏。
     *
     * @param width       宽（像素），必须 &gt; 0
     * @param height      高（像素），必须 &gt; 0
     * @param layers      层数，必须 &gt; 0
     * @param pixelFormat 像素格式，决定 internalFormat / clientFormat /
     *                    clientType / bytesPerPixel
     * @return 新的纹理对象；调用方拥有所有权
     * @throws IllegalArgumentException 参数非法
     */
    public static GLTexture create(int width, int height, int layers,
                                   PixelFormat pixelFormat) {
        if (width <= 0 || height <= 0 || layers <= 0) {
            throw new IllegalArgumentException(
                    "invalid dims: " + width + "x" + height + "x" + layers);
        }
        if (pixelFormat == null) {
            throw new IllegalArgumentException("pixelFormat must not be null");
        }

        int handle = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
            try {
                allocateStorage(width, height, layers, pixelFormat);

                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            } finally {
                glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
            }
        } catch (Throwable t) {
            // 分配 / 参数设置失败：释放刚创建的句柄，避免泄漏
            glDeleteTextures(handle);
            throw t;
        }

        return new GLTexture(handle, width, height, layers, pixelFormat);
    }

    /**
     * 分配存储。
     *
     * <p><b>前置条件</b>：目标纹理必须已经绑定到 {@code GL_TEXTURE_2D_ARRAY}。
     * 本方法不管理绑定状态，也不解绑。
     */
    private static void allocateStorage(int width, int height, int layers,
                                        PixelFormat pixelFormat) {
        int internal = pixelFormat.getInternalFormat();
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL42) {
            GL42.glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1,
                    internal, width, height, layers);
        } else if (caps.GL_ARB_texture_storage) {
            ARBTextureStorage.glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1,
                    internal, width, height, layers);
        } else {
            GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    internal, width, height, layers,
                    0,
                    pixelFormat.getFormat(),
                    pixelFormat.getType(),
                    (ByteBuffer) null);
        }
    }

    // ═══════════════════════════════════════════════
    // 上传 / 下载
    // ═══════════════════════════════════════════════

    /**
     * 上传一个 tile（一层的全部内容）。
     *
     * <p>{@code data} 在调用返回后不再被引用——GL 已完成拷贝。
     * 调用方可以立即复用 / 释放 buffer。
     *
     * @param layer 层索引，{@code 0 <= layer < layers}
     * @param data  CPU 侧像素数据，大小至少
     *              {@code width * height * pixelFormat.getBytesPerPixel()}
     * @throws IllegalStateException     纹理已释放
     * @throws IndexOutOfBoundsException layer 越界
     */
    public void uploadLayer(int layer, ByteBuffer data) {
        checkAlive();
        checkLayer(layer);

        glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
        try {
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    0, 0, layer,
                    width, height, 1,
                    pixelFormat.getFormat(),
                    pixelFormat.getType(),
                    data);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    /**
     * 上传指定矩形区域（脏区更新）。
     *
     * <p>只更新 {@code (x, y, w, h)} 覆盖的像素。其余部分保持不变。
     * 用于增量更新 tile 中的局部脏区，避免整层重传。
     *
     * @param layer 层索引，{@code 0 <= layer < layers}
     * @param x     区域左上角 x
     * @param y     区域左上角 y
     * @param w     区域宽
     * @param h     区域高
     * @param data  像素数据，大小至少
     *              {@code w * h * pixelFormat.getBytesPerPixel()}
     * @throws IllegalStateException     纹理已释放
     * @throws IndexOutOfBoundsException layer 或区域越界
     */
    public void uploadRegion(int layer, int x, int y, int w, int h, ByteBuffer data) {
        checkAlive();
        checkLayer(layer);
        checkRegion(x, y, w, h);

        glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
        try {
            glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    x, y, layer,
                    w, h, 1,
                    pixelFormat.getFormat(),
                    pixelFormat.getType(),
                    data);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    /**
     * 下载指定层的整个内容到 CPU。
     *
     * <p>返回的 buffer 由 {@link MemoryUtil#memAlloc} 分配，<b>调用方负责释放</b>。
     *
     * @param layer 层索引
     * @return 新分配的堆外 buffer，大小
     *         {@code width * height * pixelFormat.getBytesPerPixel()}
     */
    public ByteBuffer downloadLayer(int layer) {
        checkAlive();
        checkLayer(layer);

        ByteBuffer buffer = MemoryUtil.memAlloc(
                width * height * pixelFormat.getBytesPerPixel());
        glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
        try {
            glGetTexImage(GL_TEXTURE_2D_ARRAY, 0,
                    pixelFormat.getFormat(),
                    pixelFormat.getType(),
                    buffer);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
        return buffer;
    }

    /**
     * 下载指定层的矩形区域到 CPU。
     *
     * <p>只读取 {@code (x, y, w, h)} 覆盖的像素，不下载整层。
     * 坐标原点在左下角（与 {@link #uploadRegion} 对称）。
     *
     * <p>内部使用一个懒创建的下载专用 FBO——避免每次调用都
     * {@code glGenFramebuffers} / {@code glDeleteFramebuffers}。
     *
     * <p><b>性能提示</b>：{@code glReadPixels} 会同步等待 GPU 完成
     * 之前的绘制命令。仅在最终输出时调用。
     *
     * <p>返回的 buffer 由 {@link MemoryUtil#memAlloc} 分配，<b>调用方负责释放</b>：
     * <pre>{@code
     * ByteBuffer buf = texture.downloadRegion(0, 64, 64, 64, 64);
     * try {
     *     // 使用 buf
     * } finally {
     *     MemoryUtil.memFree(buf);
     * }
     * }</pre>
     *
     * @param layer 层索引，{@code 0 <= layer < layers}
     * @param x     区域左下角 x
     * @param y     区域左下角 y
     * @param w     区域宽
     * @param h     区域高
     * @return 新分配的堆外 buffer，大小
     *         {@code w * h * pixelFormat.getBytesPerPixel()}
     * @throws IllegalStateException     纹理已释放
     * @throws IndexOutOfBoundsException layer 或区域越界
     */
    public ByteBuffer downloadRegion(int layer, int x, int y, int w, int h) {
        checkAlive();
        checkLayer(layer);
        checkRegion(x, y, w, h);

        if (downloadFbo == 0) {
            downloadFbo = glGenFramebuffers();
        }

        ByteBuffer buffer = MemoryUtil.memAlloc(w * h * pixelFormat.getBytesPerPixel());

        int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, downloadFbo);
        try {
            glFramebufferTextureLayer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    handle, 0, layer);
            glReadPixels(x, y, w, h,
                    pixelFormat.getFormat(),
                    pixelFormat.getType(),
                    buffer);
        } finally {
            // 恢复到调用前的 FBO 绑定——避免污染渲染状态
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
        }
        return buffer;
    }

    // ═══════════════════════════════════════════════
    // 绑定
    // ═══════════════════════════════════════════════

    /**
     * 绑定到指定纹理单元。
     *
     * <p>对应操作：{@code glActiveTexture(GL_TEXTURE0 + unit)} 后
     * {@code glBindTexture(GL_TEXTURE_2D_ARRAY, handle)}。
     *
     * @param unit 纹理单元索引，{@code 0 <= unit < GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS}
     * @throws IllegalStateException 纹理已释放
     */
    public void bind(int unit) {
        checkAlive();
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
    }

    /**
     * 解绑指定纹理单元上的 2D_ARRAY 目标。
     *
     * <p>便利方法，等价于：
     * {@code glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D_ARRAY, 0);}
     */
    public static void unbind(int unit) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    // ═══════════════════════════════════════════════
    // 释放
    // ═══════════════════════════════════════════════

    /**
     * 释放纹理。必须在 GL 线程上调用。幂等——重复调用是 no-op。
     *
     * <p>释放后再调用其他方法（除查询外）会抛 {@link IllegalStateException}。
     */
    public void release() {
        if (released) return;
        if (downloadFbo != 0) {
            glDeleteFramebuffers(downloadFbo);
            downloadFbo = 0;
        }
        glDeleteTextures(handle);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════

    /** GL 纹理句柄。 */
    public int getHandle()              { return handle; }
    /** 宽（像素）。 */
    public int getWidth()               { return width; }
    /** 高（像素）。 */
    public int getHeight()              { return height; }
    /** 层数（tile 数量）。 */
    public int getLayers()              { return layers; }
    /** 像素格式。 */
    public PixelFormat getPixelFormat() { return pixelFormat; }
    /** 每像素字节数（从 pixelFormat 取）。 */
    public int getBytesPerPixel()       { return pixelFormat.getBytesPerPixel(); }
    /** 是否已经释放。 */
    public boolean isReleased()         { return released; }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    private void checkAlive() {
        if (released) {
            throw new IllegalStateException("GLTexture has been released");
        }
    }

    private void checkLayer(int layer) {
        if (layer < 0 || layer >= layers) {
            throw new IndexOutOfBoundsException(
                    "layer " + layer + " out of [0, " + layers + ")");
        }
    }

    private void checkRegion(int x, int y, int w, int h) {
        if (x < 0 || y < 0 || w <= 0 || h <= 0
                || x + w > width || y + h > height) {
            throw new IndexOutOfBoundsException(
                    "region [" + x + "," + y + " " + w + "x" + h
                            + "] out of " + width + "x" + height);
        }
    }
}