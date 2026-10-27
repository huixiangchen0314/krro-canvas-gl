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
    (top.kzre.krro.canvas.gl.util Tuple4f)
    (top.kzre.krro.util.tile TiledCanvas)))


(defmethod batch/render-batch :gl
  [_ ^TiledCanvas backdrop-canvas layer-stack
   {:keys [dirty-tiles tile-size gl-composite-context
           view-width view-height
           image-width image-height image-aabb image-tiles]
    :as   opts}]
  {:pre [(instance? GLCompositeContext gl-composite-context)
         (= tile-size (.getTileSize ^GLCompositeContext gl-composite-context))
         ;; 不能在gl线程发起调度，破坏线程模型，可能死锁
         (not= (.getId (Thread/currentThread))
               (.getThreadId (.getGlExecutor gl-composite-context)))]}
  (log/info "gl render-batch")
  (let [^GLCompositeContext ctx gl-composite-context]
    (if (or (nil? layer-stack)
            (empty? layer-stack)
            (empty? dirty-tiles))
      ;; 快速路径1, 没有需要合成的
      (promise/resolved backdrop-canvas)
      ;; 快速路径2，单个标准变换图层，空背景合成
      (-> (rasterize/rasterize-layers layer-stack opts)
          (promise/then
            (fn [{:keys [layers tracker]}]
              (let [beginRenderTime (System/nanoTime)]
                (-> (try
                      (promise/from-completable-future
                        (Render/render
                          (into [(layer/wrap-canvas-as-layer backdrop-canvas :prefix "gl-render-backdrop-")]
                                layers)
                          ctx
                          (int view-width) (int view-height)
                          (Tuple4f. (:min-x image-aabb) (:min-y image-aabb)
                                    (:max-x image-aabb) (:max-y image-aabb))
                          dirty-tiles))
                      (catch Throwable t
                        (log/error t "render failed")
                        (promise/rejected t)))
                    (promise/handle
                      (fn [^TiledCanvas result-canvas e]
                        (let [currentTime (System/nanoTime)]
                          ;(log/info "batch/render-batch :gl"
                          ;          "\nrender=" (long (/ (- currentTime beginRenderTime) 1e6)) "ms"
                          ;          "\n result canvas tile counts:" (.size (.getTiles result-canvas)))
                          (try
                            (if e
                              (throw e)
                              (.mergeCanvas backdrop-canvas result-canvas))
                            (finally
                              (try
                                (.close result-canvas)
                                (catch Throwable t
                                  (log/error t "close result canvas failed")
                                  (log/error e "render failed")
                                  (when e (throw e))))
                              (tracker/close! tracker))))))))))))))