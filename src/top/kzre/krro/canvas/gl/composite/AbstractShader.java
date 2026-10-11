package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLProgram;

import java.util.Collections;
import java.util.List;

/**
 * {@link Shader} 的通用实现。
 */
public abstract class AbstractShader implements Shader {

    private final GLProgram program;
    private final GLBindable[] bindables;
    private final List<ShaderLayerTileRef> tiles;
    protected AbstractShader(GLProgram program, GLBindable[] bindables, List<ShaderLayerTileRef> tiles) {
        this.bindables = bindables;
        this.tiles = tiles;
        if (program == null) throw new IllegalArgumentException("program must not be null");
        this.program      = program;
    }

    @Override
    public GLBindable[] getBindables() {
        return bindables;
    }

    @Override
    public List<ShaderLayerTileRef> getTiles() {
        return tiles;
    }

    @Override
    public final void bind() {
        program.use();
        configure();
    }

    @Override
    public final void unbind() {
        GLProgram.unuse();
    }

    protected abstract void configure();

    public final GLProgram getProgram() { return program; }
}