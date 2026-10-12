package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;
import top.kzre.krro.canvas.gl.resource.GLProgram;

import java.util.List;
import java.util.function.Supplier;

/**
 * {@link Shader} 的通用实现。
 */
public abstract class AbstractShader implements Shader {

    private final Supplier<GLProgram> programSupplier;
    private volatile GLProgram program;
    private final GLBindable[] bindables;
    private final List<ShaderLayerTileRef> tiles;
    protected AbstractShader(Supplier<GLProgram> programSupplier, GLBindable[] bindables, List<ShaderLayerTileRef> tiles) {
        this.bindables = bindables;
        this.tiles = tiles;
        if (programSupplier == null) throw new IllegalArgumentException("program must not be null");
        this.programSupplier = programSupplier;
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
        getProgram().use();
        configure();
    }

    @Override
    public final void unbind() {
        GLProgram.unuse();
    }

    protected abstract void configure();

    protected final GLProgram getProgram() {
        GLProgram p = program;
        if (p == null) {
            p = programSupplier.get();
            program = p;
        }
        return program;
    }
}