package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;
import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLProgramCache;
import top.kzre.krro.canvas.gl.util.Resources;

public final class CompositeGLProgramManager{
    private CompositeGLProgramManager(){}

    public static void init(GLProgramCache cache) {
        regSimpleNormal(cache);
    }

    public static void warmup(GLProgramCache cache) {
        cache.get(Blends.NORMAL);
    }

    /**
     * 注册 normal 混合的 shader。
     *
     * <p>只加载源码、注册工厂——实际编译延迟到首次 {@code get}。
     */
    private static void regSimpleNormal(GLProgramCache cache) {
        String vert = Resources.loadClassPathText(
                "resources/krro/canvas/gl/shaders/composite-noraml-simple.vert");
        String frag = Resources.loadClassPathText(
                "resources/krro/canvas/gl/shaders/composite-normal-simple.frag");

        cache.putIfAbsent(Blends.NORMAL,
                () -> GLProgram.create(vert, frag));
    }
}