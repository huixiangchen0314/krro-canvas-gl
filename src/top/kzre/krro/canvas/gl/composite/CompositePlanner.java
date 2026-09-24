package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLFramebuffer;

import java.util.List;

public class CompositePlanner {

    /**
     * 规划合成请求。注意context应当已经调整过了。
     * @param context
     * @param layers
     * @return
     */
    public static CompositeRequest plan(
            List<ILayer> layers,
            GLCompositeContext context,
            GLFramebuffer fboA,
            GLFramebuffer fboB){

        return null;

    }
}
