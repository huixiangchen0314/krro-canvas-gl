(ns build
  (:require [top.kzre.clj-build-template.simple :as tpl]))

(def params {:template (tpl/create-template)
             :artifact "krro-canvas-gl"
             :version "0.1.0"
             :src-dirs ["src" "resources"]
             :compile-java? true})

(defn clean [_]
      (tpl/clean))

(defn compile-java [_]
      (tpl/compile-java params))


(defn jar [_]
      (tpl/jar params))

(defn install [_]
      (tpl/install params))