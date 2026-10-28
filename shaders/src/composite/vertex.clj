(ns composite.vertex
  "瓦片合成约定布局的通用顶点着色器。

   输入：
   ▸ aPos      —— 单位四边形 [0,1]²，y 向下
   ▸ aInstance —— [tileX, tileY, offset, count]

   输出：
   ▸ gl_Position —— NDC 位置
   ▸ vPixel / vOffset / vCount —— 见 TileVarying"
  (:require
    [composite.tile-layout :as layout]))

(defshader :vertex tileVS
           [^:position ^float2 aPos
            ^:user0    ^float4 aInstance]
           (let [tile-xy (float2 (:x aInstance) (:y aInstance))
                 world   (* (+ tile-xy aPos) layout/uTileSize)
                 ndc     (- (* (/ world layout/uViewport) 2.0) (float2 1.0 1.0))]
             (layout/->TileVarying
               (float4 (:x ndc) (- 0.0 (:y ndc)) 0.0 1.0)
               world
               (+ (:z aInstance) 0.5)
               (+ (:w aInstance) 0.5))))