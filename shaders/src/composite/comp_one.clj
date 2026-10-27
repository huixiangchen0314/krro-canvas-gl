(ns composite.comp-one
  "单组合成骨架。

   ── 参数（按用途分组）────────────────────────────────

   ▸ entry 输入
     vPixel  —— 世界像素坐标（float2）
     vOffset —— tile 起始索引（int）
     vCount  —— tile 数量（int）

   ▸ 布局 uniform
     uTileSize       —— 单 tile 尺寸（float）
     uImageMinPoint  —— 图像边界下界（float2）
     uImageMaxPoint  —— 图像边界上界（float2）

   ▸ 纹理资源
     uIndexTable     —— tile 索引 buffer（samplerBuffer）
     uTileTable      —— tile 元数据 buffer（samplerBuffer）
     uLayerTable     —— 图层矩阵 buffer（samplerBuffer）
     uTextureScales  —— 每个 unit 的 UV 缩放（vec2[13]）

   ▸ 策略函数（通过符号注入，^:inline 让前端在降级时内联）
     sample-fn       —— (unit, uv3) → 采样颜色
     compose-fn      —— (src, dst) → 合成结果

   ── 不参数化 ────────────────────────────────────────
   layer-transform —— 所有合成策略共享同一个 2x2 + 平移公式，
                     从 composite.tile-layout 直接引用，不走参数。

   ── buffer 布局 ─────────────────────────────────────
   tile-idx 是 uint——取 .x 得到 tile 索引。
   e0 = (uv0.xy, unit, texLayer)
   e1 = (tileOrigin.xy, layerEntry, _)
   mat2d4 = (m00, m01, m10, m11)
   mat2d2 = (tx, ty, alpha, _)"
  (:require
    [composite.tile-fold :as tile-fold]
    [composite.tile-layout :as layout]
    [top.kzre.homunculus.backend.shader.dsl :refer :all]))

(defn ^:inline comp-one
  "单组合成骨架。边界检查 + tile 折叠 + per-tile 采样与合成。"
  [vPixel vOffset vCount
   uTileSize uImageMinPoint uImageMaxPoint
   uIndexTable uTileTable uLayerTable uTextureScales
   sample-fn compose-fn]
  (if (<= vCount 0)
    (float4 0.0 0.0 0.0 0.0)
    (if (or (any (lessThan vPixel uImageMinPoint))
            (any (greaterThan vPixel uImageMaxPoint)))
      (float4 0.0 0.0 0.0 0.0)
      (tile-fold/fold-tiles
        uIndexTable vOffset vCount
        (fn [tile-offset color-acc]
          (let [tex-location (load uTileTable (+ (* tile-offset 2) 0))
                tile-origin+layer-offset (load uTileTable (+ (* tile-offset 2) 1))
                uv0 (float2 (:x tex-location) (:y tex-location))
                tex-unit (int (:z tex-location))
                tex-layer (int (:w tex-location))
                tile-origin      (float2 (:x tile-origin+layer-offset) (:y tile-origin+layer-offset))
                layer-offset (int (:z tile-origin+layer-offset))
                mat2d4 (load uLayerTable (+ (* layer-offset 2) 0))
                mat2d2+alpha (load uLayerTable (+ (* layer-offset 2) 1))
                layer-pos (layout/layer-transform mat2d4 mat2d2+alpha vPixel)
                local-uv (/ (- layer-pos tile-origin) uTileSize)
                local-uv (float2 (:x local-uv) (- 1.0 (:y local-uv)))]
            (if (or (any (lessThan local-uv (float2 0.0 0.0)))
                    (any (greaterThan local-uv (float2 1.0 1.0))))
              color-acc
              (let [tile-scale (nth uTextureScales tex-unit)
                    uv    (+ uv0 (* local-uv tile-scale))
                    canvas-color     (sample-fn tex-unit (float3 (:x uv) (:y uv) tex-layer))
                    layer-color    (* canvas-color (:z mat2d2+alpha))]
                (compose-fn layer-color color-acc)))))))))