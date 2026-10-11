package top.kzre.krro.canvas.gl.composite;

import top.kzre.colorutils.blend.Blends;

import java.util.Collections;
import java.util.List;

public final class MockShaderDrawBundler implements ShaderDrawBundler {

    public static final MockShaderDrawBundler INSTANCE = new MockShaderDrawBundler();

    @Override
    public List<ShaderLayerBundle> bundle(List<ILayer> layers) {
        if (layers == null) throw new NullPointerException("layers");
        if (layers.isEmpty()) return Collections.emptyList();
        return Collections.singletonList(
                new ShaderLayerBundle(Blends.NORMAL, layers));
    }
}