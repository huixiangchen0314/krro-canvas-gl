(ns top.kzre.krro.canvas.gl.composite.merge
  "合并的GPU注册注册函数。先把画布转光栅图层后转发到 render-batch"
  (:require
    [taoensso.timbre :as log]
    [top.kzre.krro.canvas.core.layer.render.batch :as batch]
    [top.kzre.krro.canvas.gl.composite.render]
    [top.kzre.krro.core.util.promise :as promise]
    [top.kzre.krro.canvas.core.layer.render.download :as download])
  (:import (top.kzre.krro.util.tile TiledCanvas)))

(defonce ^:private gl-merge-layers-fn*
         (fn [layers ^TiledCanvas backdrop
              opts]
           ;; 接入 render,异步，避免同线程发起请求
           (log/info "merge layer counts:" (count layers))
           (->
             ((get-method batch/render-batch :gl)
              :gl
              backdrop
              layers
              opts)
             ;; 立刻下载到 cpu 来
             (promise/then
               (fn [c]
                 (download/download! c))))))

(defn gl-merge-layers-fn [] gl-merge-layers-fn*)