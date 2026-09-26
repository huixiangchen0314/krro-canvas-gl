(ns top.kzre.krro.canvas.gl.composite.layer
  (:import
    (top.kzre.colorutils.blend Blends)
    (top.kzre.krro.canvas.gl.composite WrapLayer)
    [top.kzre.krro.util.math KMath]
    (top.kzre.krro.util.tile TiledCanvas)))


(defn wrap-layer [layer]
  (WrapLayer.
    (:id layer)
    (:canvas layer)
    (:transform layer)
    (:visible layer true)
    (:opacity layer 1.0)
    (str (:blend-mode layer :normal))))


(defn wrap-canvas-as-layer
  "把背景画布包装成图层，不释放"
  [^TiledCanvas canvas]
  (WrapLayer.
    (keyword (str "wrap-layer-" (random-uuid)))
    canvas
    (KMath/mat2dIdentity)
    true
    1.0
    Blends/NORMAL))