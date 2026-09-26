(ns top.kzre.krro.canvas.gl.composite.render
  (:require
    [taoensso.timbre :as log]
    [top.kzre.krro.canvas.core.layer.render.batch :as batch]
    [top.kzre.krro.canvas.gl.composite.layer :as layer]
    [top.kzre.krro.core.util.tracker :as tracker]
    [top.kzre.krro.canvas.gl.composite.rasterize :as rasterize]
    [top.kzre.krro.core.util.promise :as promise])
  (:import
    (top.kzre.krro.canvas.gl.composite GLCompositeContext Render)
    (top.kzre.krro.util.tile TiledCanvas)))


(defmethod batch/render-batch :gl
  [_ ^TiledCanvas backdrop-canvas layer-stack
   {:keys [dirty-tiles tile-size gl-composite-context
           view-width view-height]
    :as   opts}]
  {:pre [(instance? GLCompositeContext gl-composite-context)
         (= tile-size (.getTileSize ^GLCompositeContext gl-composite-context))]}
  (let [^GLCompositeContext ctx gl-composite-context]
    (if (or (nil? layer-stack)
            (empty? layer-stack)
            (empty? dirty-tiles))
      (promise/resolved backdrop-canvas)
      (-> (rasterize/rasterize-layers layer-stack opts)
          (promise/then
            (fn [{:keys [layers tracker]}]
              (-> (try
                    (promise/from-completable-future
                      (Render/render
                        (into [(layer/wrap-canvas-as-layer backdrop-canvas)]
                              layers)
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
                            (.keepTiles result-canvas dirty-tiles)
                            (.mergeCanvas backdrop-canvas result-canvas)))
                        (finally
                          (try
                            (.close result-canvas)
                            (catch Throwable t
                              (log/error t)))
                          (try
                            (tracker/close! tracker)
                            (catch Throwable t
                              (log/error t)))
                          )))))))))))