package top.kzre.krro.canvas.gl.composite;

public final class ShaderPackedBuffer {
    final float[] layerTable;
    final int layerEntryCount;
    final float[] tileTable;
    final int tileEntryCount;
    final int[] offsets;
    final int[] counts;
    final int[] indexList;
    final int indexCount;

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
