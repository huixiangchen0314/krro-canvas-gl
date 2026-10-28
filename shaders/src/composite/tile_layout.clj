(ns composite.tile-layout
  "瓦片合成的布局约定——vertex 和 fragment 共享。

   ── 通用配置 ──────────────────────────────────────────
   ▸ uTileSize / uViewport         —— vertex 坐标变换
   ▸ uImageMinPoint / uImageMaxPoint —— fragment 边界裁剪

   ── varying ──────────────────────────────────────────
   ▸ TileVarying —— VS out / FS in 共享

   ── buffer 布局约定 ──────────────────────────────────
   ▸ uTileTable  —— 每 2 项一个 tile entry：
                    e0 = (uv0.xy, unit, texLayer)
                    e1 = (tileOrigin.xy, layerEntry, _)
   ▸ uLayerTable —— 每 2 项一个 layer entry：
                    mat2d4 = (m00, m01, m10, m11)   2x2 线性变换
                    mat2d2 = (tx, ty, alpha, _)     平移 + 透明度

   字段含义是约定——变种只改 texture 槽位和采样分派。"
  )

;; ═══════════════════════════════════════════
;; 通用配置
;; ═══════════════════════════════════════════

(defuniform uTileSize      float)
(defuniform uViewport      float2)
(defuniform uImageMinPoint float2)
(defuniform uImageMaxPoint float2)

;; ═══════════════════════════════════════════
;; varying
;; ═══════════════════════════════════════════

(defrecord TileVarying
  [^:position ^float4 pos
   ^:user0    ^float2 vPixel
   ^:user1    ^int    vOffset
   ^:user2    ^int    vCount])

;; ═══════════════════════════════════════════
;; 布局变换
;; ═══════════════════════════════════════════

(defn ^:inline layer-transform
  "用 layer entry 的 mat2d4 / mat2d2 把像素坐标变换到图层空间。
   vpos 视图位置。

   mat2d4 = (m00, m01, m10, m11)   2x2 线性变换
   mat2d2 = (tx, ty, alpha, _)     平移 + 透明度"
  [mat2d4 mat2d2 vpos]
  (float2 (+ (+ (* (:x mat2d4) (:x vpos))
                (* (:z mat2d4) (:y vpos)))
             (:x mat2d2))
          (+ (+ (* (:y mat2d4) (:x vpos))
                (* (:w mat2d4) (:y vpos)))
             (:y mat2d2))))