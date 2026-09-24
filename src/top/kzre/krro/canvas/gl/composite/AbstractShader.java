package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLProgram;

/**
 * {@link Shader} 的通用实现。
 */
public abstract class AbstractShader implements Shader {

    private final GLProgram program;

    protected AbstractShader(GLProgram program) {
        if (program == null) throw new IllegalArgumentException("program must not be null");

        this.program      = program;
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