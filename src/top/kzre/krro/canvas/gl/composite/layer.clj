(ns top.kzre.krro.canvas.gl.composite.layer
  (:import
    (top.kzre.colorutils.blend Blends)
    (top.kzre.krro.canvas.gl.composite WrapLayer)
    [top.kzre.krro.util.math KMath]
    (top.kzre.krro.util.tile TiledCanvas)))


(defn wrap-layer [layer & {:keys [default-prefix]
                           :or {default-prefix "wrap-layer"}}]
  (WrapLayer.
    (:id layer default-prefix)
    (:canvas layer)
    (:transform layer (KMath/mat2dIdentity))
    (:visible layer true)
    (:opacity layer 1.0)
    (name (:blend-mode layer :normal))))


(defn wrap-canvas-as-layer
  "把背景画布包装成图层，不释放"
  [^TiledCanvas canvas & {:keys [prefix]
                          :or {prefix "wrap-canvas-layer-"}}]
  (WrapLayer.
    (keyword (str prefix (random-uuid)))
    canvas
    (KMath/mat2dIdentity)
    true
    1.0
    Blends/NORMAL))