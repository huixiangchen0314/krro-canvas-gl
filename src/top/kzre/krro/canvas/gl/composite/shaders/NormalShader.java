package top.kzre.krro.canvas.gl.composite.shaders;

import top.kzre.krro.canvas.gl.composite.AbstractShader;
import top.kzre.krro.canvas.gl.composite.TileBufferGroup;
import top.kzre.krro.canvas.gl.resource.GLProgram;

import java.util.Collections;
import java.util.List;

/**
 * Normal 混合的合成 shader。单 group。
 */
public final class NormalShader extends AbstractShader {

    /** 单 group 的实例 attribute location。 */
    private static final int INSTANCE_LOCATION = 1;

    private final TileBufferGroup group;

    public NormalShader(GLProgram program, int backdropUnit, TileBufferGroup group) {
        super(program, backdropUnit);
        if (group == null) throw new IllegalArgumentException("group must not be null");
        this.group = group;
    }

    @Override
    public List<TileBufferGroup> tiles() {
        return Collections.singletonList(group);
    }

    @Override
    public int instanceLocation(int groupIndex) {
        if (groupIndex != 0) {
            throw new IndexOutOfBoundsException("groupIndex: " + groupIndex);
        }
        return INSTANCE_LOCATION;
    }

    @Override
    protected void configure() {
        GLProgram p = getProgram();
        p.setInt("uTileTable",  group.getTileTableUnit());
        p.setInt("uIndexTable", group.getIndexTableUnit());
    }
}