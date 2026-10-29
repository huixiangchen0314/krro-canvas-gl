package top.kzre.krro.canvas.gl.composite;

public final class ShaderPackedBuffer {
    private final float[] layerTable;
    private final int layerEntryCount;
    private final float[] tileTable;
    private final int tileEntryCount;
    private final int[] offsets;
    private final int[] counts;
    private final int[] indexList;
    private final int indexCount;

    ShaderPackedBuffer(float[] layerTable, int layerEntryCount,
                       float[] tileTable, int tileEntryCount,
                       int[] offsets, int[] counts,
                       int[] indexList, int indexCount) {
        this.layerTable = layerTable;
        this.layerEntryCount = layerEntryCount;
        this.tileTable = tileTable;
        this.tileEntryCount = tileEntryCount;
        this.offsets = offsets;
        this.counts = counts;
        this.indexList = indexList;
        this.indexCount = indexCount;
    }

    /**
     * 图层表。每图层 8 float，构成 2 个 vec4。
     *
     * <p>调用方视为只读——不得修改返回数组。
     */
    public float[] getLayerTable() {
        return layerTable;
    }

    /**
     * 图层表条目数（= layerTable.length / 8）。
     */
    public int getLayerEntryCount() {
        return layerEntryCount;
    }

    /**
     * 瓦片表。每瓦片 8 float，构成 2 个 vec4。
     *
     * <p>调用方视为只读——不得修改返回数组。
     */
    public float[] getTileTable() {
        return tileTable;
    }

    /**
     * 瓦片表条目数（= tileTable.length / 8）。
     */
    public int getTileEntryCount() {
        return tileEntryCount;
    }

    /**
     * 每个屏幕瓦片在 indexList 中的起始下标。
     * 长度 = 屏幕瓦片总数。
     *
     * <p>调用方视为只读——不得修改返回数组。
     */
    public int[] getOffsets() {
        return offsets;
    }

    /**
     * 每个屏幕瓦片覆盖的瓦片数。{@code counts[i] == 0} 表示
     * 该屏幕位置本组无内容。
     *
     * <p>调用方视为只读——不得修改返回数组。
     */
    public int[] getCounts() {
        return counts;
    }

    /**
     * 索引表。按屏幕瓦片顺序拼接，每元素指向 tileTable 中的条目下标。
     *
     * <p>调用方视为只读——不得修改返回数组。
     */
    public int[] getIndexList() {
        return indexList;
    }

    /**
     * 索引总数（= indexList.length）。
     */
    public int getIndexCount() {
        return indexCount;
    }

    @Override
    public String toString() {
        int nonEmpty = 0;
        int maxCount = 0;
        StringBuilder head = new StringBuilder();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) {
                nonEmpty++;
                if (head.length() < 80) {
                    head.append(i).append(':').append(counts[i]).append(' ');
                }
            }
            if (counts[i] > maxCount) maxCount = counts[i];
        }

        return "PackedBuffers{"
                + "screenTiles=" + counts.length
                + ", nonEmpty=" + nonEmpty
                + ", maxCount=" + maxCount
                + ", totalIndices=" + indexCount
                + ", tileEntries=" + tileEntryCount
                + ", layerEntries=" + layerEntryCount
                + ", firstCounts=[" + head.toString().trim() + "]"
                + "}";
    }
}
