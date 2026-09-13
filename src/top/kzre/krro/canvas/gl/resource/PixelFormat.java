package top.kzre.krro.canvas.gl.resource;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 像素格式：GL 纹理格式的统一描述。
 *
 * <p>封装四件事——它们总是成组出现，分开传容易不一致：
 * <ul>
 *   <li>{@code internalFormat}：纹理内部存储格式</li>
 *   <li>{@code format} / {@code type}：上传与下载时的客户端格式</li>
 *   <li>{@code bytesPerPixel}：每像素字节数——推导 tileLength 和 buffer 大小</li>
 * </ul>
 *
 * <p><b>float 槽位约定</b>：CPU 侧数据按 {@code float[]} / {@code FloatBuffer}
 * 访问，每个槽位 4 字节。因此：
 * <ul>
 *   <li>{@link #RGBA8}：每像素 4 字节 = 1 个 float 槽位——打包 RGBA</li>
 *   <li>{@link #RGBA16F}：每像素 8 字节 = 2 个 float 槽位</li>
 *   <li>{@link #RGBA32F}：每像素 16 字节 = 4 个 float 槽位</li>
 * </ul>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * PixelFormat fmt = PixelFormat.RGBA8;
 * GLTexture tex = GLTexture.create(w, h, layers, fmt);
 * int tileLength = fmt.tileLength(tileSize);   // 每瓦片的 float 数量
 * }</pre>
 */
public enum PixelFormat {

    /** RGBA 8 位/通道，每像素 4 字节，1 个 float 槽位。最常用。 */
    RGBA8(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, 4),

    /** RGBA 16 位浮点/通道，每像素 8 字节，2 个 float 槽位。 */
    RGBA16F(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT, 8),

    /** RGBA 32 位浮点/通道，每像素 16 字节，4 个 float 槽位。 */
    RGBA32F(GL_RGBA32F, GL_RGBA, GL_FLOAT, 16),

    /** 单通道 8 位，每像素 1 字节。 */
    R8(GL_R8, GL_RED, GL_UNSIGNED_BYTE, 1),

    /** 单通道 32 位浮点，每像素 4 字节，1 个 float 槽位。 */
    R32F(GL_R32F, GL_RED, GL_FLOAT, 4),
    ;

    private final int internalFormat;
    private final int format;
    private final int type;
    private final int bytesPerPixel;

    PixelFormat(int internalFormat, int format, int type, int bytesPerPixel) {
        this.internalFormat = internalFormat;
        this.format         = format;
        this.type           = type;
        this.bytesPerPixel  = bytesPerPixel;
    }

    /** 纹理内部格式，用于 {@code glTexImage3D} / {@code glTexStorage3D}。 */
    public int getInternalFormat() { return internalFormat; }

    /** 客户端格式，用于 {@code glTexSubImage3D} / {@code glGetTexImage}。 */
    public int getFormat() { return format; }

    /** 客户端类型，用于 {@code glTexSubImage3D} / {@code glGetTexImage}。 */
    public int getType() { return type; }

    /** 每像素字节数。 */
    public int getBytesPerPixel() { return bytesPerPixel; }

    /** 每像素的 float 槽位数（每槽 4 字节）。 */
    public int getFloatsPerPixel() { return bytesPerPixel / Float.BYTES; }

    /** {@code tileSize × tileSize} 瓦片的字节数。 */
    public int tileByteSize(int tileSize) {
        return tileSize * tileSize * bytesPerPixel;
    }

    /**
     * 一个 {@code tileSize × tileSize} 瓦片在客户端侧的 float 数量。
     *
     * <p>由 {@code bytesPerPixel / 4} 推导——客户端数据以 float 为单位
     * （Float32 元素），每个元素 4 字节。
     * <ul>
     *   <li>RGBA8：4 bytes/像素 → 1 float/像素</li>
     *   <li>RGBA16F：8 bytes/像素 → 2 float/像素</li>
     *   <li>RGBA32F：16 bytes/像素 → 4 float/像素</li>
     * </ul>
     *
     * <p><b>前置条件</b>：{@code bytesPerPixel} 必须是 4 的倍数。
     * 当前所有格式满足；如果未来加入 R8 之类的单字节格式，需要单独处理。
     */
    public int tileFloats(int tileSize) {
        return tileSize * tileSize * bytesPerPixel / Float.BYTES;
    }

    /** {@code tileSize × tileSize} 瓦片的 float 数量——即 {@code tileLength}。 */
    public int tileLength(int tileSize) {
        return tileSize * tileSize * bytesPerPixel / Float.BYTES;
    }
}