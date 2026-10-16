package top.kzre.krro.canvas.gl.util;

public final class NumberUtils {
    private NumberUtils(){}

    public static int alignUp(int value, int multiple) {
        return ((value + multiple - 1) / multiple) * multiple;
    }

}
