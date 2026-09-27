package top.kzre.krro.canvas.gl.util;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * 缓冲区操作工具。
 */
public final class BufferUtils {

    private BufferUtils() {}

    /**
     * 每线程复用的行翻转中转缓冲。
     *
     * <p>{@code flipRows} 的调用集中在 GL 线程（下载路径）——每条
     * 线程一份缓冲即可，不需要跨线程共享、不需要锁。
     *
     * <p>容量只增不减：首次分配 {@code rowBytes} 字节，之后遇到更大的
     * 行宽时重新分配更大的数组。稳态下一次分配、终身复用。
     */
    private static final ThreadLocal<byte[]> FLIP_TMP = new ThreadLocal<>();

    /**
     * 按行倒序翻转一个字节缓冲区的行序。
     *
     * <p>用于把 {@code glReadPixels} 的输出从 GL 行序（低 y 在前）翻回
     * 图像行序（顶行在前）。{@code glReadPixels} 逐行从低 y 读到高 y——
     * 输出第一行是纹理低 y 那行（图像底行）；调用方若期望第一行是图像
     * 顶行，需调用本方法翻一次。
     *
     * <p><b>就地修改</b>：{@code buf} 的内容被直接改写——不分配新缓冲。
     * 调用前须保证 {@code buf} 至少有 {@code rows * rowBytes} 字节的有效
     * 内容，且 position / limit 覆盖该区间。
     *
     * <p><b>绝对索引</b>：通过 {@link MemoryUtil#memAddress} 拿首地址，
     * 按偏移直接读写——不受 position / limit 影响。
     *
     * <p><b>线程契约</b>：任意线程可调。中转缓冲是 {@link ThreadLocal}，
     * 每条线程各自持有——无跨线程共享，无需加锁。
     *
     * @param buf      目标缓冲区，就地修改
     * @param rows     行数
     * @param rowBytes 每行字节数
     * @throws IllegalArgumentException {@code rows <= 0} 或 {@code rowBytes <= 0}
     */
    public static void flipRows(ByteBuffer buf, int rows, int rowBytes) {
        if (rows <= 0) {
            throw new IllegalArgumentException("rows must be > 0: " + rows);
        }
        if (rowBytes <= 0) {
            throw new IllegalArgumentException("rowBytes must be > 0: " + rowBytes);
        }
        if (rows == 1) {
            return;   // 单行无需翻
        }

        // 借用（或分配）本线程的中转缓冲——容量不足时扩容
        byte[] tmp = FLIP_TMP.get();
        if (tmp == null || tmp.length < rowBytes) {
            tmp = new byte[rowBytes];
            FLIP_TMP.set(tmp);
        }

        long base = MemoryUtil.memAddress(buf);

        for (int top = 0, bot = rows - 1; top < bot; top++, bot--) {
            long topAddr = base + (long) top * rowBytes;
            long botAddr = base + (long) bot * rowBytes;

            // top → tmp
            MemoryUtil.memByteBuffer(topAddr, rowBytes).get(tmp);
            // bot → top
            MemoryUtil.memCopy(botAddr, topAddr, rowBytes);
            // tmp → bot
            MemoryUtil.memByteBuffer(botAddr, rowBytes).put(tmp);
        }
    }

    /**
     * 清除本线程的中转缓冲。
     *
     * <p>当前 GL 线程长存——不需要主动清理。仅在线程池 / 短生命周期
     * 线程场景下调用，避免 {@link ThreadLocal} 的 value 在线程复用时
     * 残留。
     */
    public static void cleanupThreadLocal() {
        FLIP_TMP.remove();
    }
}