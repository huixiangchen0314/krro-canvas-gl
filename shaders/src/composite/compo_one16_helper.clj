(ns composite.compo-one16-helper
  (:require [top.kzre.homunculus.frontend.shader.dsl :refer :all]))

;; ── 宏辅助 ────────────────────────────────────────────

(def ^:private common-args
  "所有合成 shader 共用的采样参数，顺序固定。"
  ['composite.tile-layout/uTileSize
   'composite.tile-layout/uImageMinPoint
   'composite.tile-layout/uImageMaxPoint
   'composite.comp-one16/uIndexTable
   'composite.comp-one16/uTileTable
   'composite.comp-one16/uLayerTable
   'composite.comp-one16/uTextureScales
   'composite.comp-one16/sample-atlas])

(defn- expand-composite
  "composite 的实际展开逻辑，独立成函数便于测试与复用。"
  [shader-name composite-fn]
  (when-not (symbol? shader-name)
    (throw (ex-info "composite: shader-name 必须是符号"
                    {:got shader-name :type (type shader-name)})))
  (when-not (symbol? composite-fn)
    (throw (ex-info "composite: composite-fn 必须是符号"
                    {:got composite-fn :type (type composite-fn)})))
  (let [v-pixel  'vPixel
        v-offset 'vOffset
        v-count  'vCount]
    `(defshader :fragment ~shader-name
                ~(with-meta
                  [`~(with-meta v-pixel {:tag 'float2 :user0 true})
                   `~(with-meta v-offset {:tag 'int :user1 true})
                   `~(with-meta v-count {:tag 'int :user2 true})]
                  {:target0 true})
                (~'composite.comp-one/comp-one
                  ~v-pixel ~v-offset ~v-count
                  ~@common-args
                  ~composite-fn))))

;; ── 宏 ────────────────────────────────────────────────

(defmacro composite
  "生成一个 tileFS 风格的 fragment shader。

  composite-fn 必须是合成函数名（符号），签名同 normal：
  (fn [src dst] -> vec4)，src/dst 为预乘 vec4。

  可选 shader-name 用于自定义 shader 名，避免多次调用冲突。"
  ([composite-fn]
   (expand-composite 'tileFS composite-fn))
  ([shader-name composite-fn]
   (expand-composite shader-name composite-fn)))