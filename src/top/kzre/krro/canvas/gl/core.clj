(ns top.kzre.krro.canvas.gl.core
  (:require
    [top.kzre.krro.canvas.core.layer.render.batch :as batch])
  (:import
    (top.kzre.krro.canvas.gl GLExecutors)
    (top.kzre.krro.util.tile TiledCanvas)))

(defmethod batch/render-batch :gl
          [_ ^TiledCanvas backdrop-canvas layers
           {:keys [view-width
                   view-height
                   tile-size
                   atlas-pool
                   gl-executor]
            ;; 默认走全局gl线程，注意，atlas-pool 必须在对应的gl线程分配
            :or   {gl-executor (GLExecutors/offscreen)}
            :as   opts}]
  ;; TODO
  ;; 安排渲染任务
  (throw (ex-info "unsupported" {}))
  )