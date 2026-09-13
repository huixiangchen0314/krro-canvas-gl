package top.kzre.krro.canvas.gl.tile;

public interface GLUploadableTile extends GLTile {
    /**
     * 确保 CPU 侧数据已上传到显存。
     *
     * <p>幂等——已同步时 no-op。由脏标记驱动，只上传变化过的内容。
     *
     * <p><b>线程契约</b>：必须在 GL 线程上调用。
     */
    void ensureUploaded();
}
