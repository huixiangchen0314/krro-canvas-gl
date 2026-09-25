(ns top.kzre.krro.canvas.gl.composite.render
  (:require
    [top.kzre.krro.canvas.core.layer.render.batch :as batch]
    [top.kzre.krro.canvas.gl.composite.rasterize :as rasterize]
    [top.kzre.krro.core.util.promise :as promise]
    [taoensso.timbre :as log])
  (:import
    (top.kzre.colorutils.blend Blends)
    (top.kzre.krro.canvas.gl.composite DefaultLayer GLCompositeContext Render)
    (top.kzre.krro.util.math KMath)
    (top.kzre.krro.util.tile TiledCanvas)))

(defn- canvas->layer
  [^TiledCanvas canvas]
  (DefaultLayer.
    (keyword "background")
    canvas
    (KMath/mat2dIdentity)
    true
    1.0
    Blends/NORMAL))

(defmethod batch/render-batch :gl
  [_ ^TiledCanvas backdrop-canvas layers
   {:keys [dirty-tiles tile-size gl-composite-context
           view-width view-height]
    :as   opts}]
  {:pre [(instance? GLCompositeContext gl-composite-context)
         (= tile-size (.getTileSize ^GLCompositeContext gl-composite-context))]}
  (let [^GLCompositeContext ctx gl-composite-context]
    (if (or (nil? layers)
            (empty? layers)
            (empty? dirty-tiles))
      (promise/resolved backdrop-canvas)
      (-> (rasterize/rasterize-layers layers opts)
          (promise/then
            (fn [rasterized-layers]
              (-> (try
                    (promise/from-completable-future
                      (Render/render
                        (into [(canvas->layer backdrop-canvas)]
                              rasterized-layers)
                        ctx
                        (int view-width) (int view-height)
                        dirty-tiles))
                    (catch Throwable t
                      (promise/rejected t)))
                  (promise/handle
                    (fn [^TiledCanvas result-canvas e]
                      (try
                        (if e
                          (throw e)
                          (do
                            (when result-canvas
                              (.mergeCanvas backdrop-canvas result-canvas))
                            backdrop-canvas))
                        (finally
                          (when result-canvas
                            (try
                              (.close result-canvas)
                              (catch Throwable t
                                (log/error t))))
                          (doseq [l rasterized-layers
                                  :let [^TiledCanvas canvas (:canvas l)]
                                  :when canvas]
                            (try
                              (.close canvas)
                              (catch Throwable t
                                (log/error t)))))))))))))))