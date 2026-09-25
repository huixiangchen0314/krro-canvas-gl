package top.kzre.krro.canvas.gl.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 内部工具类。
 */
public final class Resources {

    private Resources() {}

    /**
     * 从 classpath 加载文本文件，以 UTF-8 解码。
     *
     * <p><b>路径必须绝对</b>——以 {@code /} 开头，相对 classpath 根。
     * 拒绝相对路径，避免"以哪个类所在包为基准"的歧义。
     *
     * @param classpath 资源路径，必须以 {@code /} 开头，不能为 null
     * @return 文件内容（UTF-8 解码）
     * @throws IllegalArgumentException classpath 为 null 或不以 {@code /} 开头
     * @throws IllegalStateException    资源不存在或读取失败
     */
    public static String loadClassPathText(String classpath) {
        if (classpath == null) {
            throw new IllegalArgumentException("classpath must not be null");
        }

        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(classpath)) {
            if (in == null) {
                throw new IllegalStateException(
                        "classpath resource not found: " + classpath);
            }
            byte[] bytes = readAll(in);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "failed to read classpath resource: " + classpath, e);
        }
    }

    /**
     * 读尽输入流。Java 8 兼容——{@code InputStream.readAllBytes}
     * 是 Java 9+ 的方法。
     */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}