(ns top.kzre.krro.canvas.gl.core
  (:require
    [top.kzre.krro.canvas.core.layer.render.batch :as batch])
  (:import (top.kzre.krro.util.tile TiledCanvas)))


(defmethod batch/render-batch :gl
          [_ ^TiledCanvas backdrop-canvas layers opts]
  )