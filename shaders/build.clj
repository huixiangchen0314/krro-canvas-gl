(ns build
  (:require [top.kzre.cljh.tools.build.api :as cljhb]
            [clojure.tools.build.api :as b]))

(def output-dir "out")

(defn- append
  "把 vs 追加到 m 的 k 字段上，去重后返回新 map。nil 字段当空集合处理。"
  [m k vs]
  (update m k #(vec (distinct (concat % vs)))))

(defn clean []
  (b/delete {:path output-dir}))

(defn- composite-task
  "合成任务公共参数：追加配置文件，缺省 output-path。"
  [{:keys [name] :as params}]
  (-> params
      (append :config-files ["conf/common.edn" "conf/composite.edn"])
      (update :output-path #(or % (str output-dir "/" name)))))

(defn- composite-vertex-task
  "合成顶点着色器：composite 参数 + vertex.clj 入口。
   output-path 由 composite-task 按 name 缺省为 out/<name>，此处不覆盖。"
  [params]
  (-> params
      (composite-task)
      (append :src-files ["src/composite/vertex.clj"])))

(defn- composite-one16-task
  "16 纹理单元合成公式：追加 macro-ns 与入口文件。"
  [{:keys [name] :as params}]
  (-> params
      (composite-task)
      (append :macro-namespaces ["composite.compo-one16-helper"])
      (append :src-files [(str "src/composite/" name ".clj")])))

(def composite-one16-task-names
  ["normal16" "multiply16" "screen16" "darken16" "soft_light16"
   "lighten16" "difference16" "exclusion16" "overlay16" "hard_light16"
   "color_dodge16" "color_burn16" "linear_light16" "vivid_light16" "pin_light16"
   "hard_mix16" "subtract16" "divide16" "linear_burn16" "linear_dodge16"])

(def tasks
  (-> (mapv #(composite-one16-task {:name %}) composite-one16-task-names)
      (conj (composite-vertex-task {:name "tile_comp"}))))

(defn compile-composite-shaders
  [_]
  (let [basis (cljhb/create-basis {})]
    (doseq [t tasks]
      (cljhb/compile-cljh (assoc t
                                 :basis basis
                                 :target "glsl"))))
  nil)