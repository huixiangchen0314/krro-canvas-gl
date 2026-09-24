(ns top.kzre.krro.canvas.gl.core
  (:require
    [top.kzre.krro.canvas.core.layer.render.batch :as batch])
  (:import
    (top.kzre.krro.canvas.gl.composite GLCompositeContext)
    (top.kzre.krro.util.tile TiledCanvas)))

(defn adjust-view-size [^GLCompositeContext ctx])

(defmethod batch/render-batch :gl
          [_ ^TiledCanvas backdrop-canvas layers
           {:keys [view-width
                   view-height
                   gl-composite-context
                   ]
            :as   opts}]
  {:pre [(instance? GLCompositeContext gl-composite-context)]}
  ;; TODO
  ;; 安排渲染任务
  (throw (ex-info "unsupported" {}))
  )