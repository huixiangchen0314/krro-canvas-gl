(ns top.kzre.krro.canvas.gl.composite.rasterize
  (:require [top.kzre.krro.canvas.gl.composite.layer :as layer]
            [top.kzre.krro.core.util.promise :as promise]
            [top.kzre.krro.core.util.tracker :as tracker]))

(defmulti rasterize-layer
          "把图层光栅化为瓦片容器。

           输入：
             layer   图层
             opts    上下文：、tile-size、gl-executor、atlas ...

          返回：
            Promise<TiledCanvas>——该图层的局部空间瓦片，空白图层
            返回空 canvas。

           <b>不应用图层变换</b>：产出局部空间像素，变换由合成阶段
           作为实例数据携带，在顶点着色器里应用。

           与 CPU 路径的取舍不同。CPU 光栅化和 blit 的成本随目标画布
           大小增长，应用变换后只在视口内光栅化，裁剪收益随视口/图层
           面积比放大，非常显著——代价是每次变换都要重新光栅化。
           GPU 路径相反：计算快，blit 成本低，变换后裁剪省下的工作量
           有限；而局部空间瓦片跨变换可复用，缓存收益超过裁剪收益。
           所以 GPU 路径保留局部空间，用粗筛代替精确裁剪。

           <b>视口粗筛</b>：用 viewport 和变换估算可见瓦片范围，
           跳过完全不可见的。变换只作筛选依据，不写进瓦片数据。

           <b>执行线程</b>：调用线程，不在 GL 线程。方法内部可以
           submit GL 任务并等待——调用线程不是 GL 线程，不会死锁。
           从 GL 线程调用会死锁。

           <b>返回瓦片的介质</b>：允许返回 CPU 侧 TileData，也允许
           返回已上传的 GLTileData。上层会统一检查每个瓦片的实际
           介质，未上传的补上传。方法可根据自身特点选择何时上传。

           <b>职责</b>：
             - 决定生成哪些瓦片（视口粗筛 + 图层自身范围）
             - 决定 CPU 光栅化的工作分配（整体准备一次，还是逐瓦片）
             - 决定是否在此阶段上传 GPU（可选，非必需）"
          (fn [layer _opts] (:type layer)))

(defn rasterize-layers
  [layers opts]
  (if (empty? layers)
    (promise/resolved {:tracker nil :layers []})
    (let [tk (tracker/auto-closeable-tracker)]
      (-> (promise/all
            (mapv (fn [layer]
                    (-> (rasterize-layer layer opts)
                        (promise/ensure-promise)
                        (promise/fmap
                          (fn [layer]
                            (tracker/track! tk layer)
                            layer))))
                  layers))
          (promise/fmap
            (fn [layers]
              {:tracker tk :layers layers}))
          (promise/handle
            (fn [v e]
              (if e
                (do
                  (tracker/close! tk)
                  (throw e))
                v)))))))


(defmethod rasterize-layer :raster
  [layer _]
  (layer/wrap-layer layer))