(ns top.kzre.krro.canvas.gl.core
  (:require
    [top.kzre.krro.canvas.core.layer.render.batch :as batch])
  (:import
    (top.kzre.krro.canvas.gl.compositor GLCompositor)
    (top.kzre.krro.util.tile TiledCanvas)))

;; 合成器实例
(defonce ^:private compositor* (GLCompositor.))

(defn composite-task [])

;; TODO tile-size 提取到 canvas.core 作为全局共享约定
(defmethod batch/render-batch :gl
          [_ ^TiledCanvas backdrop-canvas layers opts]
  ;; TODO
  ;; 安排渲染任务
  (throw (ex-info "unsupported" {}))
  )