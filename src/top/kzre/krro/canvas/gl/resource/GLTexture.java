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

    private final int     handle;         // GL texture id
    private final int     width;
    private final int     height;
    private final int     layers;         // texture array 层数；2D 场景为 1
    private final int     internalFormat; // 如 GL_RGBA8
    private final int     format;         // 如 GL_RGBA
    private final int     type;           // 如 GL_UNSIGNED_BYTE
    private final int     bytesPerPixel;  // 由 format + type 推导，构造时算好

    private boolean released = false;

    // ═══════════════════════════════════════════════
    // 构造
    // ═══════════════════════════════════════════════

    private GLTexture(int handle,
                      int width, int height, int layers,
                      int internalFormat, int format, int type,
                      int bytesPerPixel) {
        this.handle         = handle;
        this.width          = width;
        this.height         = height;
        this.layers         = layers;
        this.internalFormat = internalFormat;
        this.format         = format;
        this.type           = type;
        this.bytesPerPixel  = bytesPerPixel;
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
        return create(width, height, layers,
                GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
    }

    /**
     * 创建单层 RGBA8 纹理（2D 形态），常用于 FBO 颜色附着。
     *
     * @param width  宽（像素）
     * @param height 高（像素）
     */
    public static GLTexture createRgba8(int width, int height) {
        return createRgba8(width, height, 1);
    }

    /**
     * 通用创建：指定 internal format / format / type。
     *
     * <p>创建过程会：
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
     * @param width          宽（像素），必须 &gt; 0
     * @param height         高（像素），必须 &gt; 0
     * @param layers         层数，必须 &gt; 0
     * @param internalFormat GL 内部格式（如 {@code GL_RGBA8}）
     * @param format         客户端数据格式（如 {@code GL_RGBA}），
     *                       用于后续 {@code uploadLayer} / {@code downloadLayer}
     * @param type           客户端数据类型（如 {@code GL_UNSIGNED_BYTE}）
     * @return 新的纹理对象；调用方拥有所有权
     * @throws IllegalArgumentException     尺寸非法，或 format / type 组合不支持
     * @throws UnsupportedOperationException format / type 组合未在本类中实现
     */
    public static GLTexture create(int width, int height, int layers,
                                   int internalFormat, int format, int type) {
        if (width <= 0 || height <= 0 || layers <= 0) {
            throw new IllegalArgumentException(
                    "invalid dims: " + width + "x" + height + "x" + layers);
        }

        int bpp    = computeBytesPerPixel(format, type);
        int handle = glGenTextures();
        try {
            glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
            try {
                allocateStorage(width, height, layers, internalFormat, format, type);

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

        return new GLTexture(handle, width, height, layers,
                internalFormat, format, type, bpp);
    }

    /**
     * 分配存储。
     *
     * <p><b>前置条件</b>：目标纹理必须已经绑定到 {@code GL_TEXTURE_2D_ARRAY}。
     * 本方法不管理绑定状态，也不解绑。
     */
    private static void allocateStorage(int width, int height, int layers,
                                        int internalFormat, int format, int type) {
        GLCapabilities caps = GL.getCapabilities();
        if (caps.OpenGL42) {
            GL42.glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1,
                    internalFormat, width, height, layers);
        } else if (caps.GL_ARB_texture_storage) {
            ARBTextureStorage.glTexStorage3D(GL_TEXTURE_2D_ARRAY, 1,
                    internalFormat, width, height, layers);
        } else {
            GL12.glTexImage3D(GL_TEXTURE_2D_ARRAY, 0,
                    internalFormat, width, height, layers,
                    0, format, type, (ByteBuffer) null);
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
     * @param data  CPU 侧像素数据，大小至少 {@code width * height * bytesPerPixel}
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
                    format, type, data);
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
     * @param data  像素数据，大小至少 {@code w * h * bytesPerPixel}
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
                    format, type, data);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        }
    }

    /**
     * 下载指定层的数据到 CPU。
     *
     * <p>返回的 buffer 由 {@link MemoryUtil#memAlloc} 分配，<b>调用方负责释放</b>：
     * <pre>{@code
     * ByteBuffer buf = texture.downloadLayer(0);
     * try {
     *     // 使用 buf
     * } finally {
     *     MemoryUtil.memFree(buf);
     * }
     * }</pre>
     *
     * @param layer 层索引，{@code 0 <= layer < layers}
     * @return 新分配的堆外 buffer，大小 {@code width * height * bytesPerPixel}
     * @throws IllegalStateException     纹理已释放
     * @throws IndexOutOfBoundsException layer 越界
     */
    public ByteBuffer downloadLayer(int layer) {
        checkAlive();
        checkLayer(layer);

        ByteBuffer buffer = MemoryUtil.memAlloc(width * height * bytesPerPixel);
        glBindTexture(GL_TEXTURE_2D_ARRAY, handle);
        try {
            glGetTexImage(GL_TEXTURE_2D_ARRAY, 0, format, type, buffer);
        } finally {
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
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
        glDeleteTextures(handle);
        released = true;
    }

    // ═══════════════════════════════════════════════
    // 查询
    // ═══════════════════════════════════════════════

    /** GL 纹理句柄。 */
    public int getHandle()         { return handle; }
    /** 宽（像素）。 */
    public int getWidth()          { return width; }
    /** 高（像素）。 */
    public int getHeight()         { return height; }
    /** 层数（tile 数量）。 */
    public int getLayers()         { return layers; }
    /** GL 内部格式。 */
    public int getInternalFormat() { return internalFormat; }
    /** 客户端数据格式。 */
    public int getFormat()         { return format; }
    /** 客户端数据类型。 */
    public int getType()           { return type; }
    /** 每像素字节数，由 format + type 推导。 */
    public int getBytesPerPixel()  { return bytesPerPixel; }
    /** 是否已经释放。 */
    public boolean isReleased() { return released; }

    // ═══════════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════════

    /**
     * 由 format + type 推导每像素字节数。
     *
     * <p>只覆盖本类当前支持的组合。若需扩展（如浮点、half-float、
     * 整数格式），在此处新增分支即可。
     */
    private static int computeBytesPerPixel(int format, int type) {
        int channels;
        switch (format) {
            case GL_RGBA:
            case GL_BGRA:
                channels = 4; break;
            case GL_RGB:
            case GL_BGR:
                channels = 3; break;
            case GL_RG:
                channels = 2; break;
            case GL_RED:
                channels = 1; break;
            default:
                throw new UnsupportedOperationException(
                        "bytesPerPixel: unknown format 0x"
                                + Integer.toHexString(format));
        }

        int bytesPerChannel;
        switch (type) {
            case GL_UNSIGNED_BYTE:
            case GL_BYTE:
                bytesPerChannel = 1; break;
            case GL_UNSIGNED_SHORT:
            case GL_SHORT:
            case GL_HALF_FLOAT:
                bytesPerChannel = 2; break;
            case GL_UNSIGNED_INT:
            case GL_INT:
            case GL_FLOAT:
                bytesPerChannel = 4; break;
            default:
                throw new UnsupportedOperationException(
                        "bytesPerPixel: unknown type 0x"
                                + Integer.toHexString(type));
        }

        return channels * bytesPerChannel;
    }

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