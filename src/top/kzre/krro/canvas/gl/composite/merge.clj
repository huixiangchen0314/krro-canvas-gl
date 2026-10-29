(ns top.kzre.krro.canvas.gl.composite.merge
  "合并的GPU注册注册函数。先把画布转光栅图层后转发到 render-batch"
  (:require
    [top.kzre.krro.canvas.core.layer.render.batch :as batch]
    [top.kzre.krro.canvas.gl.composite.render]
    [top.kzre.krro.core.util.promise :as promise])
  (:import
    (top.kzre.krro.util.math KMath)
    (top.kzre.krro.util.tile TiledCanvas)))

(defonce ^:private gl-merge-layers-fn*
         (fn [layers ^TiledCanvas backdrop
              opts]
           (if (seq layers)
             (if (and (.isEmpty (.getTiles backdrop))
                      (= (count layers) 1)
                      (KMath/mat2dIsIdentity (:transform (nth layers 0))))
               ;; 快速路径, backdrop是空的，
               ;; 并且只有单个图层，且图层变换是标准变换，结果等于图层画布
               (promise/resolved
                 (.keepTiles (.mergeCanvas backdrop (:canvas (nth layers 0)))
                             (:dirty-tiles opts)))
               ((get-method batch/render-batch :gl)
                :gl
                backdrop
                layers
                opts))
             (promise/resolved backdrop))))

(defn gl-merge-layers-fn [] gl-merge-layers-fn*)