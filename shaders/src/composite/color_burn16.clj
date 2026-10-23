(ns composite.color-burn16
  (:require
    [composite.comp-one]
    [composite.comp-one16]
    [composite.tile-layout]
    [composite.composite :as comp]
    [composite.compo-one16-helper :refer :all]
    [top.kzre.homunculus.backend.shader.dsl :refer :all]))

(composite comp/color-burn)