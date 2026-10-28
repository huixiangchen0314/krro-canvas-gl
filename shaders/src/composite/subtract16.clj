(ns composite.subtract16
  (:require
    [composite.comp-one]
    [composite.comp-one16]
    [composite.tile-layout]
    [composite.composite :as comp])
  (:require-macros
    [composite.compo-one16-helper :refer :all]))

(composite comp/subtract)

